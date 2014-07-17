package org.cache2k.impl;

/*
 * #%L
 * cache2k core package
 * %%
 * Copyright (C) 2000 - 2014 headissue GmbH, Munich
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import org.cache2k.Cache;
import org.cache2k.CacheConfig;
import org.cache2k.ClosableIterator;
import org.cache2k.StorageConfiguration;
import org.cache2k.impl.threading.LimitedPooledExecutor;
import org.cache2k.impl.timer.TimerPayloadListener;
import org.cache2k.impl.timer.TimerService;
import org.cache2k.storage.CacheStorage;
import org.cache2k.storage.CacheStorageContext;
import org.cache2k.storage.ImageFileStorage;
import org.cache2k.storage.MarshallerFactory;
import org.cache2k.storage.Marshallers;
import org.cache2k.storage.StorageEntry;
import org.cache2k.impl.util.Log;
import org.cache2k.impl.util.TunableConstants;
import org.cache2k.impl.util.TunableFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Passes cache operation to the storage layer. Implements common
 * services for the storage layer. This class heavily interacts
 * with the base cache and contains mostly everything special
 * needed if a storage is defined. This means the design is not
 * perfectly layered, in some cases the
 * e.g. the get operation does interacts with the
 * underlying storage, wheres iterate
 *
 * @author Jens Wilke; created: 2014-05-08
 */
@SuppressWarnings({"unchecked", "SynchronizeOnNonFinalField"})
class PassingStorageAdapter extends StorageAdapter {

  private Tunable tunable = TunableFactory.get(Tunable.class);
  private BaseCache cache;
  CacheStorage storage;
  boolean passivation = false;
  long errorCount = 0;
  Set<Object> deletedKeys = null;
  StorageContext context;
  StorageConfiguration config;
  ExecutorService executor;
  TimerService.CancelHandle flushTimerHandle;
  boolean needsFlush;
  Future<Void> executingFlush;
  Log log;
  StorageAdapter.Parent parent;

  public PassingStorageAdapter(BaseCache _cache, CacheConfig _cacheConfig,
                               StorageConfiguration _storageConfig) {
    cache = _cache;
    parent = _cache;
    context = new StorageContext(_cache);
    context.keyType = _cacheConfig.getKeyType();
    context.valueType = _cacheConfig.getValueType();
    config = _storageConfig;
    if (tunable.useManagerThreadPool) {
      executor = new LimitedPooledExecutor(cache.manager.getThreadPool());
    } else {
      executor = Executors.newCachedThreadPool();
    }
    log = Log.getLog(Cache.class.getName() + ".storage/" + cache.getCompleteName());
  }

  /**
   * By default log lifecycle operations as info.
   */
  protected void logLifecycleOperation(String s) {
    log.info(s);
  }

  public void open() {
    try {
      ImageFileStorage s = new ImageFileStorage();
      s.open(context, config);
      storage = s;
      if (config.isPassivation()) {
      deletedKeys = new HashSet<>();
      passivation = true;
      }
      logLifecycleOperation("opened, state: " + storage);
    } catch (Exception ex) {
      if (config.isReliable()) {
        disableAndThrow("error initializing, disabled", ex);
      } else {
        disable("error initializing, disabled", ex);
      }
    }
  }

  /**
   * Store entry on cache put. Entry must be locked, since we use the
   * entry directly for handing it over to the storage, it is not
   * allowed to change.
   */
  public void put(BaseCache.Entry e) {
    if (passivation) {
      synchronized (deletedKeys) {
        deletedKeys.remove(e.getKey());
      }
      return;
    }
    doPut(e);
  }

  private void doPut(BaseCache.Entry e) {
    try {
      storage.put(e);
      checkStartFlushTimer();
    } catch (Exception ex) {
      if (config.isReliable()) {
        disableAndThrow("exception in storage.put()", ex);
      } else {
        errorCount++;
        try {
          if (!storage.contains(e.getKey())) {
            return;
          }
          storage.remove(e.getKey());
        } catch (Exception ex2) {
          ex.addSuppressed(ex2);
          disableAndThrow("exception in storage.put(), mitigation failed, entry state unknown", ex);
        }
      }
    }
  }

  void checkStartFlushTimer() {
    needsFlush = true;
    if (config.getSyncInterval() <= 0) {
      return;
    }
    if (flushTimerHandle != null) {
      return;
    }
    synchronized (this) {
      if (flushTimerHandle != null) {
        return;
      }
      scheduleTimer();
    }
  }

  private void scheduleTimer() {
    if (flushTimerHandle != null) {
      flushTimerHandle.cancel();
    }
    TimerPayloadListener<Void> l = new TimerPayloadListener<Void>() {
      @Override
      public void fire(Void _payload, long _time) {
        flush();
      }
    };
    long _fireTime = System.currentTimeMillis() + config.getSyncInterval();
    flushTimerHandle = cache.timerService.add(l, null, _fireTime);
  }

  public StorageEntry get(Object k) {
    if (deletedKeys != null) {
      synchronized (deletedKeys) {
        if (deletedKeys.contains(k)) {
          return null;
        }
      }
    }
    try {
      return storage.get(k);
    } catch (Exception ex) {
      errorCount++;
      if (config.isReliable()) {
        throw new CacheStorageException("cache get", ex);
      }
      return null;
    }
  }

  /**
   * If passivation is not enabled, then we need to do nothing here since, the
   * entry was transferred to the storage on the {@link #put(org.cache2k.impl.BaseCache.Entry)}
   * operation. With passivation enabled, the entries need to be transferred when evicted from
   * the heap.
   */
  public void evict(BaseCache.Entry e) {
    if (passivation) {
      putEventually(e);
    }
  }

  /**
   * Entry is evicted from memory cache either because of an expiry.
   */
  public void expire(BaseCache.Entry e) {
    remove(e.getKey());
  }

  /**
   * Store it in the storage if needed, that is if it is dirty
   * or if the entry is not yet in the storage. When an off heap
   * and persistent storage is aggregated, evicted entries will
   * be put to the off heap storage, but not into the persistent
   * storage again.
   */
  private void putEventually(BaseCache.Entry e) {
    boolean f;
    try {
      f = e.isDirty() || !storage.contains(e.getKey());
    } catch (Exception ex) {
      errorCount++;
      disable("storage.contains(), unknown state", ex);
      throw new CacheStorageException("", ex);
    }
    if (f) {
      doPut(e);
    }
  }

  public void remove(Object key) {
    if (deletedKeys != null) {
      synchronized (deletedKeys) {
        deletedKeys.remove(key);
      }
      return;
    }
    try {
      storage.remove(key);
      checkStartFlushTimer();
    } catch (Exception ex) {
      disableAndThrow("storage.remove()", ex);
    }
  }

  @Override
  public ClosableIterator<BaseCache.Entry> iterateAll() {
    final CompleteIterator it = new CompleteIterator();
    if (tunable.iterationQueueCapacity > 0) {
      it.queue = new ArrayBlockingQueue<>(tunable.iterationQueueCapacity);
    } else {
      it.queue = new SynchronousQueue<>();
    }
    synchronized (cache.lock) {
      it.localIteration = cache.iterateAllLocalEntries();
      it.localIteration.setKeepIterated(true);
      it.keepHashCtrlForClearDetection = cache.mainHashCtrl;
      if (!passivation) {
        it.maximumEntriesToIterate = storage.getEntryCount();
      } else {
        it.maximumEntriesToIterate = Integer.MAX_VALUE;
      }

    }
    it.executorForStorageCall = executor;
    long now = System.currentTimeMillis();
    it.runnable = new StorageVisitCallable(now, it);
    return it;
  }

  public void expire() {
    long now = System.currentTimeMillis();
    boolean _unsupported = false;
    try {
      CacheStorage.ExpireContext ctx = new MyExpireContext();
      storage.expire(ctx, now);
    } catch (UnsupportedOperationException ex) {
      _unsupported = true;
    } catch (Exception ex) {
      disable("expire exception", ex);
    }
    if (_unsupported) {
      expireByVisit(now);
    }
  }

  void expireByVisit(final long now) {
    CacheStorage.EntryFilter f = new CacheStorage.EntryFilter() {
      @Override
      public boolean shouldInclude(Object _key) {
        return true;
      }
    };
    CacheStorage.VisitContext ctx = new BaseVisitContext() {
      @Override
      public boolean needMetaData() {
        return true;
      }

      @Override
      public boolean needValue() {
        return false;
      }
    };
    final AtomicBoolean _modified = new AtomicBoolean();
    CacheStorage.EntryVisitor v = new CacheStorage.EntryVisitor() {
      @Override
      public void visit(StorageEntry e) throws Exception {
        if (e.getExpiryTime() < now) {
          storage.remove(e.getKey());
          remove(e.getKey());
          _modified.set(true);
        }
      }
    };
    try {
      storage.visit(ctx, f, v);
    } catch (Exception ex) {
      disable("visit exception", ex);
    }
    if (_modified.get()) {
      checkStartFlushTimer();
    }
  }

  abstract class BaseVisitContext extends MyMultiThreadContext implements CacheStorage.VisitContext {

  }

  class MyExpireContext extends MyMultiThreadContext implements CacheStorage.ExpireContext  {

  }

  class StorageVisitCallable implements LimitedPooledExecutor.NeverRunInCallingTask<Void> {

    long now;
    CompleteIterator it;

    StorageVisitCallable(long now, CompleteIterator it) {
      this.now = now;
      this.it = it;
    }

    @Override
    public Void call() {
      final BlockingQueue<StorageEntry> _queue = it.queue;
      CacheStorage.EntryVisitor v = new CacheStorage.EntryVisitor() {
        @Override
        public void visit(StorageEntry se) throws InterruptedException {
          if (se.getExpiryTime() != 0 && se.getExpiryTime() <= now) { return; }
          _queue.put(se);
        }
      };
      CacheStorage.EntryFilter f = new CacheStorage.EntryFilter() {
        @Override
        public boolean shouldInclude(Object _key) {
          return !BaseCache.Hash.contains(it.keysIterated, _key, cache.modifiedHash(_key.hashCode()));
        }
      };
      try {
        storage.visit(it, f, v);
      } catch (Exception ex) {
        it.abortOnException(ex);
        _queue.clear();
      } finally {
        try {
          it.awaitTermination();
        } catch (InterruptedException ex) {
        }
        for (;;) {
          try {
            _queue.put(LAST_ENTRY);
            break;
          } catch (InterruptedException ex) {
          }
        }
      }
      return null;
    }

  }

  static final BaseCache.Entry LAST_ENTRY = new BaseCache.Entry();

  class MyMultiThreadContext implements CacheStorage.MultiThreadedContext {

    ExecutorService executorForVisitThread;
    boolean abortFlag;
    Throwable abortException;

    @Override
    public ExecutorService getExecutorService() {
      if (executorForVisitThread == null) {
        if (tunable.useManagerThreadPool) {
          LimitedPooledExecutor ex = new LimitedPooledExecutor(cache.manager.getThreadPool());
          ex.setExceptionListener(new LimitedPooledExecutor.ExceptionListener() {
            @Override
            public void exceptionWasThrown(Throwable ex) {
              abortOnException(ex);
            }
          });
          executorForVisitThread = ex;
        } else {
          executorForVisitThread = createOperationExecutor();
        }
      }
      return executorForVisitThread;
    }

    @Override
    public void awaitTermination() throws InterruptedException {
      if (executorForVisitThread != null) {
        if (!executorForVisitThread.isTerminated()) {
          if (shouldStop()) {
            executorForVisitThread.shutdownNow();
          } else {
            executorForVisitThread.shutdown();
          }
          executorForVisitThread.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        }
      }
    }

    @Override
    public void abortOnException(Throwable ex) {
      if (abortException == null) {
        abortException = ex;
      }
      abortFlag = true;
    }

    @Override
    public boolean shouldStop() {
      return abortFlag;
    }

  }

  class CompleteIterator
    extends MyMultiThreadContext
    implements ClosableIterator<BaseCache.Entry>, CacheStorage.VisitContext {

    BaseCache.Hash keepHashCtrlForClearDetection;
    BaseCache.Entry[] keysIterated;
    ClosableConcurrentHashEntryIterator localIteration;
    int maximumEntriesToIterate;
    StorageEntry entry;
    BlockingQueue<StorageEntry> queue;
    Callable<Void> runnable;
    Future<Void> futureToCheckAbnormalTermination;
    ExecutorService executorForStorageCall;

    @Override
    public boolean needMetaData() {
      return true;
    }

    @Override
    public boolean needValue() {
      return true;
    }

    @Override
    public boolean hasNext() {
      if (localIteration != null) {
        boolean b = localIteration.hasNext();
        if (b) {
          BaseCache.Entry e;
          entry = e = localIteration.next();
          return true;
        }
        if (localIteration.iteratedCtl.size >= maximumEntriesToIterate) {
          queue = null;
        } else {
          keysIterated = localIteration.iterated;
          futureToCheckAbnormalTermination =
            executorForStorageCall.submit(runnable);
        }
        localIteration = null;
      }
      if (queue != null) {
        if (abortException != null) {
          queue = null;
          throw new StorageIterationException(abortException);
        }
        if (cache.shutdownInitiated) {
          throw new CacheClosedException();
        }
        if (keepHashCtrlForClearDetection.isCleared()) {
          close();
          return false;
        }
        try {
          for (;;) {
            entry = queue.poll(1234, TimeUnit.MILLISECONDS);
            if (entry == null) {
              if (!futureToCheckAbnormalTermination.isDone()) {
                continue;
              }
              futureToCheckAbnormalTermination.get();
            }
            break;
          }
          if (entry != LAST_ENTRY) {
            return true;
          }
        } catch (InterruptedException _ignore) {
        } catch (ExecutionException ex) {
          if (abortException == null) {
            abortException = ex;
          }
        }
        queue = null;
        if (abortException != null) {
          throw new CacheStorageException(abortException);
        }
      }
      return false;
    }

    /**
     * {@link BaseCache#insertEntryFromStorage(org.cache2k.storage.StorageEntry, boolean)}
     * could be executed here or within the separate read thread. Since the eviction cannot run
     * in parallel it is slightly better to do it here. This way the operation takes place on
     * the same thread and cache trashing on the CPUs will be reduced.
     */
    @Override
    public BaseCache.Entry next() {
      return cache.insertEntryFromStorage(entry, false);
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      if (localIteration != null) {
        localIteration.close();
        localIteration = null;
      }
      if (executorForStorageCall != null) {
        executorForStorageCall.shutdownNow();
        executorForStorageCall = null;
        queue = null;
      }
    }

  }

  static class StorageIterationException extends CacheStorageException {

    StorageIterationException(Throwable cause) {
      super(cause);
    }

  }

  class MyFlushContext
    extends MyMultiThreadContext
    implements CacheStorage.FlushContext {

  }

  public Future<Void> flush() {
    synchronized (this) {
      final Future<Void> _previousFlush = executingFlush;
        Callable<Void> c = new Callable<Void>() {
          @Override
          public Void call() throws Exception {
          if (_previousFlush != null) {
            _previousFlush.get();
          }
          CacheStorage.FlushContext ctx = new MyFlushContext();
          storage.flush(ctx, System.currentTimeMillis());
          log.debug("flushed, state: " + storage);
          executingFlush = null;
          synchronized (this) {
            if (needsFlush) {
              scheduleTimer();
            } else {
              if (flushTimerHandle != null) {
                flushTimerHandle.cancel();
                flushTimerHandle = null;
              }
            }
          }
          ctx.awaitTermination();
          return null;
          }
        };
      return executingFlush = executor.submit(c);
    }
  }

  public void shutdown() {
    if (storage == null) {
      return;
    }
    try {
      if (passivation) {
        Iterator<BaseCache.Entry> it;
        synchronized (cache.lock) {
          it = cache.iterateAllLocalEntries();
        }
        while (it.hasNext()) {
          BaseCache.Entry e = it.next();
          synchronized (e) {
            putEventually(e);
          }
        }
        if (deletedKeys != null) {
          for (Object k : deletedKeys) {
            storage.remove(k);
          }
        }
      }
      final CacheStorage _storage = storage;
      synchronized (this) {
        if (_storage == null) {
          return;
        }
        if  (storage instanceof ClearStorageBuffer) {
          throw new CacheInternalError("Clear is supposed to be in shutdown wait task queue");
        }
        storage = null;
      }
      final Future<Void> _previousFlush = executingFlush;
      Callable<Void> c = new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          if (_previousFlush != null) {
            try {
              _previousFlush.cancel(true);
              _previousFlush.get();
            } catch (Exception ex) {
            }
          }
          CacheStorage.FlushContext ctx = new MyFlushContext();
          _storage.flush(ctx, System.currentTimeMillis());
          ctx.awaitTermination();
          logLifecycleOperation("about to close, state: " + _storage);
          _storage.close();
          return null;
        }
      };
      Future<Void> f = executor.submit(c);
      f.get();
    } catch (Exception ex) {
      errorCount++;
    }
  }

  /**
   * True means actually no operations started on the storage again, yet
   */
  public boolean checkStorageStillDisconnectedForClear() {
    if (storage instanceof ClearStorageBuffer) {
      ClearStorageBuffer _buffer = (ClearStorageBuffer) storage;
      if (!_buffer.isTransferringToStorage()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Disconnect storage so cache can wait for all entry operations to finish.
   */
  public void disconnectStorageForClear() {
    synchronized (this) {
      ClearStorageBuffer _buffer = new ClearStorageBuffer();
      _buffer.nextStorage = storage;
      storage = _buffer;
      if (_buffer.nextStorage instanceof ClearStorageBuffer) {
        ClearStorageBuffer _ongoingClear = (ClearStorageBuffer) _buffer.nextStorage;
        if (_ongoingClear.clearThreadFuture != null) {
          _ongoingClear.shouldStop = true;
        }
      }
    }
  }

  /**
   * Called in a (maybe) separate thread after disconnect. Cache
   * already is doing operations meanwhile and the storage operations
   * are buffered. Here we have multiple race conditions. A clear() exists
   * immediately but the storage is still working on the first clear.
   * All previous clear processes will be cancelled and the last one may
   * will. However, this method is not necessarily executed in the order
   * the clear or the disconnect took place. This is checked also.
   */
  public Future<Void> startClearingAndReconnection() {
    synchronized (this) {
      final ClearStorageBuffer _buffer = (ClearStorageBuffer) storage;
      if (_buffer.clearThreadFuture != null) {
        return _buffer.clearThreadFuture;
      }
      ClearStorageBuffer _previousBuffer = null;
      if (_buffer.getNextStorage() instanceof ClearStorageBuffer) {
        _previousBuffer = (ClearStorageBuffer) _buffer.getNextStorage();
        _buffer.nextStorage = _buffer.getOriginalStorage();
      }
      final ClearStorageBuffer _waitingBufferStack = _previousBuffer;
      Callable<Void> c = new LimitedPooledExecutor.NeverRunInCallingTask<Void>() {
        @Override
        public Void call() throws Exception {
          try {
            if (_waitingBufferStack != null) {
              _waitingBufferStack.waitForAll();
            }
          } catch (Exception ex) {
            disable("exception during waiting for previous clear", ex);
            throw new CacheStorageException(ex);
          }
          synchronized (this) {
            if (_buffer.shouldStop) {
              return null;
            }
          }
          try {
            _buffer.getOriginalStorage().clear();
          } catch (Exception ex) {
            disable("exception during clear", ex);
            throw new CacheStorageException(ex);
          }
          synchronized (cache.lock) {
            _buffer.startTransfer();
          }
          try {
            _buffer.transfer();
          } catch (Exception ex) {
            disable("exception during clear, operations replay", ex);
            throw new CacheStorageException(ex);
          }
          synchronized (this) {
            if (_buffer.shouldStop) { return null; }
            storage = _buffer.getOriginalStorage();
          }
          return null;
        }
      };
      _buffer.clearThreadFuture = executor.submit(c);
      return _buffer.clearThreadFuture;
    }
  }

  public void disableAndThrow(String _logMessage, Throwable ex) {
    errorCount++;
    disable(_logMessage, ex);
    throw new CacheStorageException(_logMessage, ex);
  }

  public void disable(String _logMessage, Throwable ex) {
    log.warn(_logMessage, ex);
    disable(ex);
  }

  public void disable(Throwable ex) {
    if (storage == null) { return; }
    synchronized (cache.lock) {
      synchronized (this) {
        if (storage == null) { return; }
        CacheStorage _storage = storage;
        if (_storage instanceof ClearStorageBuffer) {
          ClearStorageBuffer _buffer = (ClearStorageBuffer) _storage;
          _buffer.disableOnFailure(ex);
        }
        try {
          _storage.close();
        } catch (Exception _ignore) {
        }

        storage = null;
        parent.resetStorage(this, new NoopStorageAdapter(cache));
      }
    }
  }

  /**
   * orange alert level if buffer is active, so we get alerted if storage
   * clear isn't finished.
   */
  @Override
  public int getAlert() {
    if (errorCount > 0) {
      return 1;
    }
    if (storage instanceof ClearStorageBuffer) {
      return 1;
    }
    return 0;
  }

  /**
   * Calculates the cache size, depending on the persistence configuration
   */
  @Override
  public int getTotalEntryCount() {
    if (!passivation) {
      return storage.getEntryCount();
    }
    return storage.getEntryCount() + cache.getLocalSize();
  }

  static class StorageContext implements CacheStorageContext {

    BaseCache cache;
    Class<?> keyType;
    Class<?> valueType;

    StorageContext(BaseCache cache) {
      this.cache = cache;
    }

    @Override
    public Properties getProperties() {
      return null;
    }

    @Override
    public String getManagerName() {
      return cache.manager.getName();
    }

    @Override
    public String getCacheName() {
      return cache.getName();
    }

    @Override
    public Class<?> getKeyType() {
      return keyType;
    }

    @Override
    public Class<?> getValueType() {
      return valueType;
    }

    @Override
    public MarshallerFactory getMarshallerFactory() {
      return Marshallers.getInstance();
    }

    @Override
    public void requestMaintenanceCall(int _intervalMillis) {
    }

    @Override
    public void notifyEvicted(StorageEntry e) {
    }

    @Override
    public void notifyExpired(StorageEntry e) {
    }

  }

  ExecutorService createOperationExecutor() {
    return
      new ThreadPoolExecutor(
        0, Runtime.getRuntime().availableProcessors() * 123 / 100,
        21, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>(),
        THREAD_FACTORY,
        new ThreadPoolExecutor.CallerRunsPolicy());
  }

  static final ThreadFactory THREAD_FACTORY = new MyThreadFactory();

  @SuppressWarnings("NullableProblems")
  static class MyThreadFactory implements ThreadFactory {

    AtomicInteger count = new AtomicInteger();

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "cache2k-storage#" + count.incrementAndGet());
      if (t.isDaemon()) {
        t.setDaemon(false);
      }
      if (t.getPriority() != Thread.NORM_PRIORITY) {
        t.setPriority(Thread.NORM_PRIORITY);
      }
      return t;
    }

  }

  public static class Tunable extends TunableConstants {

    /**
     * If the iteration client needs more time then the read threads,
     * the queue fills up. When the capacity is reached the reading
     * threads block until the client is requesting the next entry.
     *
     * <p>A low number makes sense here just to make sure that the read threads are
     * not waiting if the iterator client is doing some processing. We should
     * never put a large number here, to keep overall memory capacity control
     * within the cache and don't introduce additional buffers.
     *
     * <p>When the value is 0 a {@link java.util.concurrent.SynchronousQueue}
     * is used.
     */
    public int iterationQueueCapacity = 3;

    public boolean useManagerThreadPool = true;

  }

}