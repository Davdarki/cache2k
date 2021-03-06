package org.cache2k.core;

/*
 * #%L
 * cache2k implementation
 * %%
 * Copyright (C) 2000 - 2019 headissue GmbH, Munich
 * %%
 * ;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.CacheEntry;
import org.cache2k.CacheException;
import org.cache2k.core.operation.LoadedEntry;
import org.cache2k.expiry.ExpiryPolicy;
import org.cache2k.event.CacheEntryExpiredListener;
import org.cache2k.expiry.ExpiryTimeValues;
import org.cache2k.integration.AdvancedCacheLoader;
import org.cache2k.event.CacheEntryCreatedListener;
import org.cache2k.event.CacheEntryRemovedListener;
import org.cache2k.event.CacheEntryUpdatedListener;
import org.cache2k.integration.CacheWriter;
import org.cache2k.integration.CacheWriterException;
import org.cache2k.CustomizationException;
import org.cache2k.integration.AsyncCacheLoader;
import org.cache2k.core.experimentalApi.AsyncCacheWriter;
import org.cache2k.core.operation.ExaminationEntry;
import org.cache2k.core.operation.Progress;
import org.cache2k.core.operation.Semantic;
import org.cache2k.core.storageApi.StorageCallback;
import org.cache2k.core.storageApi.StorageAdapter;
import org.cache2k.core.storageApi.StorageEntry;
import org.cache2k.integration.ExceptionInformation;
import org.cache2k.integration.RefreshedTimeWrapper;

import java.util.concurrent.Executor;

import static org.cache2k.core.Entry.ProcessingState.*;

/**
 * This is a method object to perform an operation on an entry.
 *
 * @author Jens Wilke
 */
@SuppressWarnings({"SynchronizeOnNonFinalField", "unchecked"})
public abstract class EntryAction<K, V, R> extends Entry.PiggyBack implements
  Runnable,
  AsyncCacheLoader.Context<K, V>,
  AsyncCacheLoader.Callback<V>, AsyncCacheWriter.Callback, Progress<K, V, R> {

  public static final Entry NON_FRESH_DUMMY = new Entry();

  InternalCache<K, V> userCache;
  HeapCache<K, V> heapCache;
  K key;
  Semantic<K, V, R> operation;

  /**
   * Reference to the entry we do processing for or the dummy entry {@link #NON_FRESH_DUMMY}
   * The field is volatile, to be safe when we get a callback from a different thread.
   */
  volatile Entry<K, V> heapEntry;
  ExaminationEntry<K, V> heapOrLoadedEntry;
  V newValueOrException;
  V oldValueOrException;
  R result;

  /**
   * Only set on request, use getter {@link #getMutationStartTime()}
   */
  long mutationStartTime;

  long lastRefreshTime;
  long loadStartedTime;
  RuntimeException exceptionToPropagate;
  boolean remove;
  /** Special case of remove, expiry is in the past */
  boolean expiredImmediately;
  long expiry = 0;
  /**
   * We locked the entry, don't lock it again.
   */
  boolean entryLocked = false;
  /**
   * True if entry had some data after locking. Clock for expiry is not checked.
   * Also true if entry contains data in refresh probation.
   */
  boolean heapDataValid = false;
  boolean storageDataValid = false;

  boolean storageRead = false;
  boolean storageMiss = false;

  boolean heapMiss = false;

  boolean wantData = false;
  boolean countMiss = false;
  boolean heapHit = false;
  boolean doNotCountAccess = false;

  boolean loadAndRestart = false;
  boolean load = false;

  /** Stats for load should be counted as refresh */
  boolean refresh = false;

  /**
   * Fresh load in first round with {@link #loadAndRestart}.
   * Triggers that we always say it is present.
   */
  boolean successfulLoad = false;

  boolean suppressException = false;

  Thread syncThread;

  /**
   * Callback on on completion, set if client request is async.
   */
  CompletedCallback completedCallback;

  /**
   * Linked list of actions waiting for execution after this one.
   * Guarded by the entry lock.
   */
  private EntryAction nextAction = null;

  private int semanticCallback = 0;

  /**
   * Action is completed
   */
  private boolean completed;

  /**
   * Called on the processing action to enqueue another action
   * to be executed next. Insert at the tail of the double linked
   * list. We are not part of the list.
   */
  public void enqueueToExecute(EntryAction v) {
    EntryAction next;
    EntryAction target = this;
    while ((next = target.nextAction) != null) {
      target = next;
    }
    target.nextAction = v;
  }

  @SuppressWarnings("unchecked")
  public EntryAction(HeapCache<K,V> _heapCache, InternalCache<K,V> _userCache,
                     Semantic<K, V, R> op, K k, Entry<K, V> e, CompletedCallback cb) {
    super(null);
    heapCache = _heapCache;
    userCache = _userCache;
    operation = op;
    key = k;
    if (e != null) {
      heapEntry = e;
    } else {
      heapEntry = (Entry<K,V>) NON_FRESH_DUMMY;
    }
    if (cb == null) {
      syncThread = Thread.currentThread();
    } else {
      completedCallback = cb;
    }
  }

  @SuppressWarnings("unchecked")
  public EntryAction(HeapCache<K,V> _heapCache, InternalCache<K,V> _userCache,
                     Semantic<K, V, R> op, K k, Entry<K, V> e) {
    this(_heapCache, _userCache, op, k, e, null);
  }

  @Override
  public Executor getExecutor() {
    return executor();
  }

  protected abstract Executor executor();

  /**
   * Provide the cache loader, if present.
   */
  protected AdvancedCacheLoader<K, V> loader() {
    return heapCache.loader;
  }

  protected AsyncCacheLoader<K, V> asyncLoader() {
    return null;
  }

  /**
   * Provide the standard metrics for updating.
   */
  protected CommonMetrics.Updater metrics() {
    return heapCache.metrics;
  }

  /**
   * Provide the writer, default null.
   */
  protected CacheWriter<K, V> writer() {
    return null;
  }

  /**
   * True if there is any listener defined. Default false.
   */
  protected boolean mightHaveListeners() {
    return false;
  }

  /**
   * Provide the registered listeners for entry creation.
   */
  protected CacheEntryCreatedListener<K, V>[] entryCreatedListeners() {
    return null;
  }

  /**
   * Provide the registered listeners for entry update.
   */
  protected CacheEntryUpdatedListener<K, V>[] entryUpdatedListeners() {
    return null;
  }

  /**
   * Provide the registered listeners for entry removal.
   */
  protected CacheEntryRemovedListener<K, V>[] entryRemovedListeners() {
    return null;
  }

  protected CacheEntryExpiredListener<K, V>[] entryExpiredListeners() {
    return null;
  }

  @SuppressWarnings("unchecked")
  protected abstract TimingHandler<K,V> timing();

  @Override
  public K getKey() {
    return heapEntry.getKey();
  }

  @Override
  public long getLoadStartTime() {
    return getMutationStartTime();
  }

  @Override
  public long getMutationStartTime() {
    if (mutationStartTime > 0) {
      return mutationStartTime;
    }
    return mutationStartTime = millis();
  }

  @Override
  public CacheEntry<K, V> getCurrentEntry() {
    if (heapEntry.isVirgin()) {
      return null;
    }
    return heapCache.returnEntry(heapEntry);
  }

  @Override
  public boolean isLoaderPresent() {
    return userCache.isLoaderPresent();
  }

  @Override
  public boolean isPresent() {
    doNotCountAccess = true;
    return successfulLoad || heapEntry.hasFreshData(heapCache.getClock());
  }

  @Override
  public boolean isExpiryTimeReachedOrInRefreshProbation() {
    doNotCountAccess = true;
    long nrt = heapEntry.getNextRefreshTime();
    if (nrt == Entry.EXPIRED_REFRESHED) {
      return true;
    }
    if (nrt >=0 && nrt < Entry.DATA_VALID) {
      return false;
    }
    return Math.abs(nrt) <= millis();
  }

  @Override
  public boolean isPresentOrInRefreshProbation() {
    doNotCountAccess = true;
    return
      successfulLoad ||
      heapEntry.getNextRefreshTime() == Entry.EXPIRED_REFRESHED ||
      heapEntry.hasFreshData(heapCache.getClock());
  }

  @Override
  public boolean isPresentOrMiss() {
    if (successfulLoad || heapEntry.hasFreshData(heapCache.getClock())) {
      return true;
    }
    countMiss = true;
    return false;
  }

  @Override
  public void run() {
    try {
      start();
    } catch (CacheClosedException ignore) {
    }
  }

  /**
   * Entry point to execute this action.
   */
  public void start() {
    int callbackCount = semanticCallback;
    operation.start(this);
  }

  @Override
  public void wantData() {
    semanticCallback++;
    wantData = true;
    retrieveDataFromHeap();
  }

  public void retrieveDataFromHeap() {
    Entry<K, V> e = heapEntry;
    if (e == NON_FRESH_DUMMY) {
      e = heapCache.lookupEntry(key);
      if (e == null) {
        heapMiss();
        return;
      }
    }
    heapHit(e);
  }

  private long millis() {
    return heapCache.getClock().millis();
  }

  public void heapMiss() {
    heapMiss = true;
    heapOrLoadedEntry = heapEntry;
    examine();
  }

  public void heapHit(Entry<K, V> e) {
    heapHit = true;
    heapEntry = e;
    heapOrLoadedEntry = heapEntry;
    examine();
  }

  public void examine() {
    int callbackCount = semanticCallback;
    operation.examine(this, heapOrLoadedEntry);
  }

  @Override
  public void noMutation() {
    semanticCallback++;
    if (successfulLoad) {
      updateDidNotTriggerDifferentMutationStoreLoadedValue();
      return;
    }
    finish();
  }

  @Override
  public void wantMutation() {
    semanticCallback++;
    if (!entryLocked) {
      if (lockForNoHit(MUTATE)) { return; }
      if (wantData) {
        countMiss = false;
        examine();
        return;
      }
    } else {
      heapEntry.nextProcessingStep(MUTATE);
    }
    checkExpiryBeforeMutation();
  }

  /**
   * Check whether we are executed on an expired entry before the
   * timer event for the expiry was received. In case expiry listeners
   * are present, we want to make sure that an expiry is sent before
   * a mutation (especially load) happens.
   */
  public void checkExpiryBeforeMutation() {
    if (entryExpiredListeners() == null) {
      noExpiryListenersPresent();
      return;
    }
    long nrt = heapEntry.getNextRefreshTime();
    if (nrt < 0 && millis() >= -nrt) {
      boolean justExpired = false;
      synchronized (heapEntry) {
        nrt = heapEntry.getNextRefreshTime();
        if (nrt < 0 && millis() >= -nrt) {
          justExpired = true;
          heapEntry.setNextRefreshTime(Entry.EXPIRED);
          timing().stopStartTimer(0, heapEntry);
          heapDataValid = false;
        }
      }
      if (justExpired) {
        CacheEntry<K, V> entryCopy = heapCache.returnCacheEntry(heapEntry);
        sendExpiryEvents(entryCopy);
        metrics().expiredKept();
      }
    }
    continueWithMutation();
  }

  public void noExpiryListenersPresent() {
    continueWithMutation();
  }

  public void continueWithMutation() {
    int callbackCount = semanticCallback;
    operation.mutate(this, heapOrLoadedEntry);
  }

  public void finish() {
    noMutationRequested();
  }

  @Override
  public void loadAndRestart() {
    loadAndRestart = true;
    load();
  }

  @Override
  public void refresh() {
    refresh = true;
    load();
  }

  @SuppressWarnings("unchecked")
  @Override
  public void load() {
    semanticCallback++;
    if (!isLoaderPresent()) {
      mutationAbort(new CacheException("load requested but no loader defined"));
      return;
    }
    heapEntry.nextProcessingStep(LOAD);
    checkLocked();
    load = true;
    Entry<K, V> e = heapEntry;
    long t0 = heapCache.isUpdateTimeNeeded() ? lastRefreshTime = loadStartedTime = getMutationStartTime() : 0;
    if (e.getNextRefreshTime() == Entry.EXPIRED_REFRESHED) {
      long nrt = e.getRefreshProbationNextRefreshTime();
      if (nrt > t0) {
        reviveRefreshedEntry(nrt);
        return;
      }
    }
    AsyncCacheLoader<K, V> _asyncLoader;
    if ((_asyncLoader = asyncLoader()) != null) {
      heapEntry.nextProcessingStep(LOAD_ASYNC);
      try {
        _asyncLoader.load(key, this, this);
      } catch (Throwable ouch) {
        onLoadFailure(ouch);
        /*
        Don't propagate exception to the direct caller here via exceptionToPropagate.
        The exception might be temporarily e.g. no execution reject and masked
        by the resilience.
         */
        return;
      }
      asyncExecutionStartedWaitIfSynchronousCall();
      return;
    }
    AdvancedCacheLoader<K, V> loader = loader();
    V v;
    try {
      if (e.isVirgin()) {
        v = loader.load(key, t0, null);
      } else {
        v = loader.load(key, t0, e);
      }
    } catch (Throwable ouch) {
      onLoadFailureIntern(ouch);
      return;
    }
    onLoadSuccessIntern(v);
  }

  public void reviveRefreshedEntry(long nrt) {
    metrics().refreshedHit();
    Entry<K, V> e = heapEntry;
    newValueOrException = e.getValueOrException();
    lastRefreshTime = e.getRefreshTime();
    expiry = nrt;
    expiryCalculated();
  }

  /**
   * @return true, in case this is an async call and enqueued the operation
   *         in the running one
   */
  private boolean lockForNoHit(int ps) {
    if (entryLocked) {
      heapEntry.nextProcessingStep(ps);
      return false;
    }
    Entry<K, V> e = heapEntry;
    if (e == NON_FRESH_DUMMY) {
      e = heapCache.lookupOrNewEntryNoHitRecord(key);
    }
    for(;;) {
      synchronized (e) {
        if (tryEnqueueOperationInCurrentlyProcessing(e)) {
          return true;
        }
        if (waitForConcurrentProcessingOrStop(ps, e)) {
          return false;
        }
      }
      e = heapCache.lookupOrNewEntryNoHitRecord(key);
    }
  }

  /**
   * If entry is currently processing, and this is an async request, we can
   * enqueue this operation in a waitlist that gets executed when
   * the processing has completed.
   */
  private boolean tryEnqueueOperationInCurrentlyProcessing(Entry e) {
    if (e.isProcessing() && completedCallback != null) {
      EntryAction runningAction = e.getEntryAction();
      if (runningAction != null) {
        runningAction.enqueueToExecute(this);
        return true;
      }
    }
    return false;
  }

  /**
   * Wait for concurrent processing. The entry might be gone as cause of the concurrent operation.
   * In this case we need to insert a new entry.
   *
   * @return true if we got the entry lock, false if we need to spin
   */
  private boolean waitForConcurrentProcessingOrStop(int ps, Entry e) {
    e.waitForProcessing();
    if (!e.isGone()) {
      e.startProcessing(ps, this);
      entryLocked = true;
      heapDataValid = e.isDataValidOrProbation();
      heapHit = !e.isVirgin();
      heapEntry = e;
      return true;
    }
    return false;
  }

  @Override
  public void onLoadSuccess(V v) {
    checkEntryStateOnLoadCallback();
    onLoadSuccessIntern(v);
  }

  /**
   * The load failed, resilience and refreshing needs to be triggered
   */
  @SuppressWarnings("unchecked")
  @Override
  public void onLoadFailure(Throwable t) {
    checkEntryStateOnLoadCallback();
    onLoadFailureIntern(t);
  }

  /**
   * Make sure only one callback succeeds. The entry reference is volatile,
   * so we are sure its there.
   */
  private void checkEntryStateOnLoadCallback() {
    synchronized (heapEntry) {
      if (!heapEntry.checkAndSwitchProcessingState(LOAD_ASYNC, LOAD_COMPLETE) || completed) {
        throw new IllegalStateException("async callback on wrong entry state. duplicate callback?");
      }
    }
    checkLocked();
  }

  private void checkLocked() {
    if (!entryLocked) {
      throw new AssertionError();
    }
  }

  private void onLoadSuccessIntern(V v) {
    if (v instanceof RefreshedTimeWrapper) {
      RefreshedTimeWrapper wr = (RefreshedTimeWrapper<V>)v;
      lastRefreshTime = wr.getRefreshTime();
      v = (V) wr.getValue();
    }

    newValueOrException = v;
    loadCompleted();
  }

  private void onLoadFailureIntern(Throwable t) {
    newValueOrException = (V) new ExceptionWrapper(key, t, loadStartedTime, heapEntry);
    loadCompleted();
  }

  public void loadCompleted() {
    heapEntry.nextProcessingStep(LOAD_COMPLETE);
    entryLocked = true;
    if (!metrics().isDisabled() && heapCache.isUpdateTimeNeeded()) {
      long _loadCompletedTime = millis();
      long _delta = _loadCompletedTime - loadStartedTime;
      if (refresh) {
        metrics().refresh(_delta);
      } else if (heapEntry.isVirgin() || !storageRead) {
        metrics().load(_delta);
      } else {
        metrics().reload(_delta);
      }
    }
    mutationCalculateExpiry();
  }

  @Override
  public void result(R r) {
    result = r;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void entryResult(final ExaminationEntry e) {
    result = (R) heapCache.returnEntry(e);
  }

  @Override
  public RuntimeException propagateException(final K key, final ExceptionInformation inf) {
    return heapCache.exceptionPropagator.propagateException(key, inf);
  }

  @Override
  public void put(V value) {
    semanticCallback++;
    heapEntry.nextProcessingStep(MUTATE);
    newValueOrException = value;
    if (!heapCache.isUpdateTimeNeeded()) {
      lastRefreshTime = 0;
    } else {
      lastRefreshTime = getMutationStartTime();
    }
    mutationCalculateExpiry();
  }

  @Override
  public void remove() {
    semanticCallback++;
    heapEntry.nextProcessingStep(MUTATE);
    remove = true;
    mutationMayCallWriter();
  }

  @Override
  public void expire(long expiryTime) {
    semanticCallback++;
    heapEntry.nextProcessingStep(EXPIRE);
    newValueOrException = heapEntry.getValueOrException();
    if (heapCache.isUpdateTimeNeeded()) {
      lastRefreshTime = heapEntry.getRefreshTime();
    }
    expiry = expiryTime;
    if (newValueOrException instanceof ExceptionWrapper) {
      setUntil(ExceptionWrapper.class.cast(newValueOrException));
    }
    checkKeepOrRemove();
  }

  @Override
  public void putAndSetExpiry(final V value, final long expiryTime, final long refreshTime) {
    semanticCallback++;
    heapEntry.nextProcessingStep(MUTATE);
    newValueOrException = value;
    if (refreshTime >= 0) {
      lastRefreshTime = refreshTime;
    } else {
      if (heapCache.isUpdateTimeNeeded()) {
        lastRefreshTime = getMutationStartTime();
      }
    }
    if (newValueOrException instanceof ExceptionWrapper) {
      setUntil(ExceptionWrapper.class.cast(newValueOrException));
    }
    if (expiryTime != ExpiryTimeValues.NEUTRAL) {
      expiry = expiryTime;
      expiryCalculated();
    } else {
      mutationCalculateExpiry();
    }
  }

  public void mutationCalculateExpiry() {
    heapEntry.nextProcessingStep(EXPIRY);
    if (newValueOrException instanceof ExceptionWrapper) {
      try {
        expiry = 0;
        ExceptionWrapper ew = (ExceptionWrapper) newValueOrException;
        if ((heapEntry.isDataValid() || heapEntry.isExpiredState()) && heapEntry.getException() == null) {
          expiry = timing().suppressExceptionUntil(heapEntry, ew);
        }
        if (expiry > loadStartedTime) {
          suppressException = true;
          newValueOrException = heapEntry.getValueOrException();
          lastRefreshTime = heapEntry.getRefreshTime();
          metrics().suppressedException();
          heapEntry.setSuppressedLoadExceptionInformation(ew);
        } else {
          if (load) {
            metrics().loadException();
          }
          expiry = timing().cacheExceptionUntil(heapEntry, ew);
        }
        setUntil(ew);
      } catch (Throwable ex) {
        if (load) {
          resiliencePolicyException(new ResiliencePolicyException(ex));
          return;
        }
        expiryCalculationException(ex);
        return;
      }
    } else {
      try {
        expiry = timing().calculateNextRefreshTime(
          heapEntry, newValueOrException,
          lastRefreshTime);
        if (newValueOrException == null && heapCache.isRejectNullValues() && expiry != ExpiryTimeValues.NO_CACHE) {
          RuntimeException _ouch = heapCache.returnNullValueDetectedException();
          if (load) {
            decideForLoaderExceptionAfterExpiryCalculation(new ResiliencePolicyException(_ouch));
            return;
          } else {
            mutationAbort(_ouch);
            return;
          }
        }
        heapEntry.resetSuppressedLoadExceptionInformation();
      } catch (Throwable ex) {
        if (load) {
          decideForLoaderExceptionAfterExpiryCalculation(new ExpiryPolicyException(ex));
          return;
        }
        expiryCalculationException(ex);
        return;
      }
    }
    expiryCalculated();
  }

  /**
   * An exception happened during or after expiry calculation. We handle this identical to
   * the loader exception and let the resilience policy decide what to do with it.
   * Rationale: An exception cause by the expiry policy may have its root cause
   * in the loaded value and may be temporary.
   */
  @SuppressWarnings("unchecked")
  private void decideForLoaderExceptionAfterExpiryCalculation(RuntimeException _ouch) {
    newValueOrException = (V) new ExceptionWrapper<K>(key, _ouch, loadStartedTime, heapEntry);
    expiry = 0;
    mutationCalculateExpiry();
  }

  /**
   * We have two exception in this case: One from the loader or the expiry policy, one from
   * the resilience policy. Propagate exception from the resilience policy and suppress
   * the other, since this is a general configuration problem.
   */
  @SuppressWarnings("unchecked")
  private void resiliencePolicyException(RuntimeException _ouch) {
    newValueOrException = (V)
      new ExceptionWrapper<K>(key, _ouch, loadStartedTime, heapEntry);
    expiry = 0;
    expiryCalculated();
  }

  private void setUntil(final ExceptionWrapper _ew) {
    if (expiry < 0) {
      _ew.setUntil(-expiry);
    } else if (expiry >= Entry.EXPIRY_TIME_MIN) {
      _ew.setUntil(expiry);
    }
  }

  public void expiryCalculationException(Throwable t) {
    mutationAbort(new ExpiryPolicyException(t));
  }

  public void expiryCalculated() {
    heapEntry.nextProcessingStep(EXPIRY_COMPLETE);
    if (load) {
      if (loadAndRestart) {
        loadAndExpiryCalculatedExamineAgain();
        return;
      }
      checkKeepOrRemove();
      return;
    } else {
      if (expiry > 0 || expiry == -1 || (expiry < 0 && -expiry > loadStartedTime)) {
        if (heapEntry.isVirgin()) {
          metrics().putNewEntry();
        } else {
          metrics().putHit();
        }
      }
    }
    mutationMayCallWriter();
  }

  public void loadAndExpiryCalculatedExamineAgain() {
    load = loadAndRestart = false;
    successfulLoad = true;
    heapOrLoadedEntry = new LoadedEntry<K,V>() {
      @Override
      public K getKey() {
        return heapEntry.getKey();
      }

      @Override
      public V getValueOrException() {
        return newValueOrException;
      }

      @Override
      public long getRefreshTime() {
        return lastRefreshTime;
      }

    };
    examine();
  }

  public void updateDidNotTriggerDifferentMutationStoreLoadedValue() {
    checkKeepOrRemove();
  }

  /**
   * Entry mutation, call writer if needed or skip to {@link #mutationMayStore()}
   */
  public void mutationMayCallWriter() {
    CacheWriter<K, V> _writer = writer();
    if (_writer == null) {
      skipWritingNoWriter();
      return;
    }
    if (remove) {
      try {
        heapEntry.nextProcessingStep(WRITE);
        _writer.delete(key);
      } catch (Throwable t) {
        onWriteFailure(t);
        return;
      }
      onWriteSuccess();
      return;
    }
    if (newValueOrException instanceof ExceptionWrapper) {
      skipWritingForException();
      return;
    }
    heapEntry.nextProcessingStep(WRITE);
    try {
      _writer.write(key, newValueOrException);
    } catch (Throwable t) {
      onWriteFailure(t);
      return;
    }
    onWriteSuccess();
  }

  @Override
  public void onWriteSuccess() {
    heapEntry.nextProcessingStep(WRITE_COMPLETE);
    checkKeepOrRemove();
  }

  @Override
  public void onWriteFailure(Throwable t) {
    mutationAbort(new CacheWriterException(t));
  }

  public void skipWritingForException() {
    checkKeepOrRemove();
  }

  public void skipWritingNoWriter() {
    checkKeepOrRemove();
  }

  /**
   * In case we have an expiry of 0, this means that the entry should
   * not be cached. If there is a valid entry, we remove it if we do not
   * keep the data.
   */
  public void checkKeepOrRemove() {
    boolean _hasKeepAfterExpired = heapCache.isKeepAfterExpired();
    if (expiry != 0 || remove) {
      mutationUpdateHeap();
      return;
    }
    if (_hasKeepAfterExpired) {
      expiredImmediatelyKeepData();
      return;
    }
    expiredImmediatelyAndRemove();
  }



  public void expiredImmediatelyKeepData() {
    expiredImmediately = true;
    mutationUpdateHeap();
  }

  public void expiredImmediatelyAndRemove() {
    remove = true;
    expiredImmediately = true;
    mutationUpdateHeap();
  }

  /**
   * The final write in the entry is at {@link #mutationReleaseLockAndStartTimer()}
   */
  public void mutationUpdateHeap() {
    synchronized (heapEntry) {
      if (heapCache.isRecordRefreshTime()) {
        heapEntry.setRefreshTime(lastRefreshTime);
      }
      if (remove) {
        if (expiredImmediately) {
          heapEntry.setNextRefreshTime(Entry.EXPIRED);
          heapEntry.setValueOrException(newValueOrException);
        } else {
          if (!heapEntry.isVirgin()) {
            heapEntry.setNextRefreshTime(Entry.REMOVE_PENDING);
          }
        }
      } else {
        oldValueOrException = heapEntry.getValueOrException();
        heapEntry.setValueOrException(newValueOrException);
      }
    }
    heapCache.eviction.updateWeight(heapEntry);
    mutationMayStore();
  }

  /**
   * Entry mutation, call storage if needed
   */
  public void mutationMayStore() {
    skipStore();
  }

  public void skipStore() {
    callListeners();
  }

  public void callListeners() {
    if (!mightHaveListeners()) {
      mutationReleaseLockAndStartTimer();
      return;
    }
    CacheEntry<K,V> entryCopy = heapCache.returnCacheEntry(heapEntry);
    if (expiredImmediately) {
      if (storageDataValid || heapDataValid) {
        if (entryExpiredListeners() != null) {
          sendExpiryEvents(entryCopy);
        }
      }
    } else if (remove) {
      if (storageDataValid || heapDataValid) {
        if (entryRemovedListeners() != null) {
          for (CacheEntryRemovedListener<K,V> l : entryRemovedListeners()) {
            try {
              l.onEntryRemoved(userCache, entryCopy);
            } catch (Throwable t) {
              exceptionToPropagate = new ListenerException(t);
            }
          }
        }
      }
    } else {
      if (storageDataValid || heapDataValid) {
        if (entryUpdatedListeners() != null) {
          CacheEntry<K,V> _previousEntry =
            heapCache.returnCacheEntry(heapEntry.getKey(), oldValueOrException);
          for (CacheEntryUpdatedListener<K,V> l : entryUpdatedListeners()) {
            try {
              l.onEntryUpdated(userCache, _previousEntry, entryCopy);
            } catch (Throwable t) {
              exceptionToPropagate = new ListenerException(t);
            }
          }
        }
      } else {
        if (entryCreatedListeners() != null) {
          for (CacheEntryCreatedListener<K,V> l : entryCreatedListeners()) {
            try {
              l.onEntryCreated(userCache, entryCopy);
            } catch (Throwable t) {
              exceptionToPropagate = new ListenerException(t);
            }
          }
        }
      }
    }
    mutationReleaseLockAndStartTimer();
  }

  private void sendExpiryEvents(final CacheEntry<K, V> _entryCopy) {
    for (CacheEntryExpiredListener<K,V> l : entryExpiredListeners()) {
      try {
        l.onEntryExpired(userCache, _entryCopy);
      } catch (Throwable t) {
        exceptionToPropagate = new ListenerException(t);
      }
    }
  }

  /**
   * Mutate the entry and start timer for expiry.
   * Entry mutation and start of expiry has to be done atomically to avoid races.
   */
  public void mutationReleaseLockAndStartTimer() {
    checkLocked();
    if (load) {
      if (!remove ||
        !(heapEntry.getValueOrException() == null && heapCache.isRejectNullValues())) {
        operation.loaded(this, heapEntry);
      }
    }
    boolean justExpired = false;
    synchronized (heapEntry) {
      if (refresh) {
        heapCache.startRefreshProbationTimer(heapEntry, expiry);
      } else if (remove) {
        heapCache.removeEntry(heapEntry);
      } else {
        heapEntry.setNextRefreshTime(timing().stopStartTimer(expiry, heapEntry));
        if (!expiredImmediately && heapEntry.isExpiredState()) {
          justExpired = true;
        }
      }
      if (!justExpired) {
        heapEntry.processingDone(this);
        entryLocked = false;
      }
    }
    if (justExpired) {
      heapDataValid = true;
      heapEntry.nextProcessingStep(EXPIRE);
      expiry = 0;
      checkKeepOrRemove();
      return;
    }
    updateMutationStatistics();
    mutationDone();
  }

  public void updateMutationStatistics() {
    if (loadStartedTime > 0) {
      return;
    }
    if (expiredImmediately && !remove) {
      metrics().expiredKept();
    }
    updateOnlyReadStatistics();
  }

  public void updateOnlyReadStatistics() {
    if (countMiss) {
      if (heapHit) {
        metrics().peekHitNotFresh();
      }
      if (heapMiss) {
        metrics().peekMiss();
      }
    } else if (doNotCountAccess && heapHit) {
      metrics().heapHitButNoRead();
    }
  }

  /**
   * Failure call on Progress from Semantic.
   */
  @Override
  public void failure(RuntimeException t) {
    semanticCallback++;
    updateOnlyReadStatistics();
    mutationAbort(t);
  }

  public void examinationAbort(CustomizationException t) {
    exceptionToPropagate = t;
    if (entryLocked) {
      synchronized (heapEntry) {
        heapEntry.processingDone(this);
        entryLocked = false;
      }
    }
    completeProcessCallbacks();
  }

  public void mutationAbort(RuntimeException t) {
    exceptionToPropagate = t;
    if (entryLocked) {
      synchronized (heapEntry) {
        heapEntry.processingDone(this);
        entryLocked = false;
      }
    }
    completeProcessCallbacks();
  }

  /**
   *
   */
  public void mutationDone() {
    completeProcessCallbacks();
  }

  public void noMutationRequested() {
    if (entryLocked) {
      synchronized (heapEntry) {
        heapEntry.processingDone(this);
        if (heapEntry.isVirgin()) {
          heapCache.removeEntry(heapEntry);
        }
      }
      entryLocked = false;
    }
    updateOnlyReadStatistics();
    completeProcessCallbacks();
  }

  /**
   * Execute any callback or other actions waiting for this one to complete.
   * It is safe to access {@link #nextAction} here, also we don't hold the entry lock
   * since the entry does not point on this action any more.
   */
  public void completeProcessCallbacks() {
    completed = true;
    if (nextAction != null) {
      executor().execute(nextAction);
    }
    if (completedCallback != null) {
      completedCallback.entryActionCompleted(this);
    }
    ready();
  }

  public void ready() {
  }

  /**
   * If thread is a synchronous call, wait until operation is complete.
   * There is a little chance that the callback completes before we get
   * here as well as some other operation mutating the entry again.
   */
  private void asyncExecutionStartedWaitIfSynchronousCall() {
    if (syncThread == Thread.currentThread()) {
      synchronized (heapEntry) {
        heapEntry.waitForProcessing();
      }
    }
  }

  public static class StorageReadException extends CustomizationException {
    public StorageReadException(final Throwable cause) {
      super(cause);
    }
  }

  public static class StorageWriteException extends CustomizationException {
    public StorageWriteException(final Throwable cause) {
      super(cause);
    }
  }

  public static class ProcessingFailureException extends CustomizationException {
    public ProcessingFailureException(final Throwable cause) {
      super(cause);
    }
  }

  public static class ListenerException extends CustomizationException {
    public ListenerException(final Throwable cause) {
      super(cause);
    }
  }

  public interface CompletedCallback<K,V,R> {
    void entryActionCompleted(EntryAction<K,V,R> ea);
  }

}
