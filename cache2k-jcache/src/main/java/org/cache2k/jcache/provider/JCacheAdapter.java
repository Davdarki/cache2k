package org.cache2k.jcache.provider;

/*
 * #%L
 * cache2k JCache provider
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

import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.cache2k.core.CacheClosedException;
import org.cache2k.jcache.provider.event.EventHandling;
import org.cache2k.processor.EntryProcessor;
import org.cache2k.integration.CacheWriterException;
import org.cache2k.processor.EntryProcessingResult;
import org.cache2k.CacheOperationCompletionListener;
import org.cache2k.processor.MutableCacheEntry;
import org.cache2k.core.EntryAction;
import org.cache2k.core.InternalCache;

import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableConfiguration;

import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;
import javax.cache.processor.MutableEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forward cache operations to cache2k cache implementation.
 *
 * @author Jens Wilke
 */
public class JCacheAdapter<K, V> implements javax.cache.Cache<K, V> {

  private final JCacheManagerAdapter manager;
  protected final InternalCache<K, V> cache;
  private final boolean storeByValue;
  private final boolean loaderConfigured;
  protected final boolean readThrough;
  private final Class<K> keyType;
  private final Class<V> valueType;
  private final EventHandling<K,V> eventHandling;
  protected final AtomicLong iterationHitCorrectionCounter = new AtomicLong();
  protected volatile boolean jmxStatisticsEnabled = false;
  protected volatile boolean jmxEnabled = false;

  public JCacheAdapter(JCacheManagerAdapter _manager, Cache<K, V> _cache,
                       Class<K> _keyType, Class<V> _valueType,
                       boolean _storeByValue, boolean _readThrough, boolean _loaderConfigured,
                       EventHandling<K,V> _eventHandling) {
    manager = _manager;
    cache = (InternalCache<K, V>) _cache;
    keyType = _keyType;
    valueType = _valueType;
    storeByValue = _storeByValue;
    readThrough = _readThrough;
    loaderConfigured = _loaderConfigured;
    eventHandling = _eventHandling;
  }

  @Override
  public V get(K k) {
    checkClosed();
    if (readThrough) {
      return cache.get(k);
    }
    return cache.peek(k);
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> _keys) {
    checkClosed();
    if (readThrough) {
      return cache.getAll(_keys);
    }
    return cache.peekAll(_keys);
  }

  @Override
  public boolean containsKey(K key) {
    checkClosed();
    return cache.containsKey(key);
  }

  @Override
  public void loadAll(final Set<? extends K> keys, final boolean replaceExistingValues, final CompletionListener completionListener) {
    checkClosed();
    if (!loaderConfigured) {
      if (completionListener != null) {
        completionListener.onCompletion();
      }
      return;
    }
    CacheOperationCompletionListener l = null;
    if (completionListener != null) {
      l = new CacheOperationCompletionListener() {
        @Override
        public void onCompleted() {
          try {
            for (K k : keys) {
              cache.peek(k);
            }
          } catch (CacheLoaderException ex) {
            completionListener.onException(ex);
            return;
          }
          completionListener.onCompletion();
        }

        @Override
        public void onException(Throwable _exception) {
          if (_exception instanceof Exception) {
            completionListener.onException((Exception) _exception);
          } else {
            completionListener.onException(new CacheLoaderException(_exception));
          }
        }
      };
    }
    if (replaceExistingValues) {
      cache.reloadAll(keys, l);
    } else {
      cache.loadAll(keys, l);
    }
  }

  @Override
  public void put(K k, V v) {
    checkClosed();
    checkNullValue(v);
    try {
      cache.put(k, v);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    }
  }

  @Override
  public V getAndPut(K key, V _value) {
    checkClosed();
    checkNullValue(_value);
    try {
      return cache.peekAndPut(key, _value);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    }
  }

  private static void checkNullValue(Object _value) {
    if (_value == null) {
      throw new NullPointerException("null value not supported");
    }
  }

  void checkNullKey(K key) {
    if (key == null) {
      throw new NullPointerException("null key not supported");
    }
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    checkClosed();
    if (map == null) {
      throw new NullPointerException("null map parameter");
    }
    if (map.containsKey(null)) {
      throw new NullPointerException("null key not allowed");
    }
    for (Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
      checkNullValue(e.getValue());
    }
    try {
      cache.putAll(map);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public boolean putIfAbsent(K key, V _value) {
    checkClosed();
    checkNullValue(_value);
    try {
      return cache.putIfAbsent(key, _value);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public boolean remove(K key) {
    checkClosed();
    try {
      return cache.containsAndRemove(key);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public boolean remove(K key, V _oldValue) {
    checkClosed();
    checkNullValue(_oldValue);
    try {
      return cache.removeIfEquals(key, _oldValue);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public V getAndRemove(K key) {
    checkClosed();
    try {
      return cache.peekAndRemove(key);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public boolean replace(K key, V _oldValue, V _newValue) {
    checkClosed();
    checkNullValue(_oldValue);
    checkNullValue(_newValue);
    try {
      return cache.replaceIfEquals(key, _oldValue, _newValue);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public boolean replace(K key, V _value) {
    checkClosed();
    checkNullValue(_value);
    try {
      return cache.replace(key, _value);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public V getAndReplace(K key, V _value) {
    checkClosed();
    checkNullValue(_value);
    try {
      return cache.peekAndReplace(key, _value);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public void removeAll(Set<? extends K> keys) {
    checkClosed();
    try {
      cache.removeAll(keys);
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public void removeAll() {
    checkClosed();
    try {
      cache.removeAll();
    } catch (EntryAction.ListenerException ex) {
      throw new javax.cache.event.CacheEntryListenerException(ex);
    } catch (CacheWriterException ex) {
      throw new javax.cache.integration.CacheWriterException(ex);
    }
  }

  @Override
  public void clear() {
    cache.clear();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <C extends Configuration<K, V>> C getConfiguration(Class<C> _class) {
    if (CompleteConfiguration.class.isAssignableFrom(_class)) {
      MutableConfiguration<K, V> cfg = new MutableConfiguration<K, V>();
      cfg.setTypes(keyType, valueType);
      cfg.setStatisticsEnabled(jmxStatisticsEnabled);
      cfg.setManagementEnabled(jmxEnabled);
      cfg.setStoreByValue(storeByValue);
      Collection<CacheEntryListenerConfiguration<K,V>> _listenerConfigurations = eventHandling.getAllListenerConfigurations();
      for (CacheEntryListenerConfiguration<K,V> _listenerConfig : _listenerConfigurations) {
        cfg.addCacheEntryListenerConfiguration(_listenerConfig);
      }
      return (C) cfg;
    }
    return (C) new Configuration<K, V>() {
      @Override
      public Class<K> getKeyType() {
        return keyType;
      }

      @Override
      public Class<V> getValueType() {
        return valueType;
      }

      @Override
      public boolean isStoreByValue() {
        return storeByValue;
      }
    };
  }

  @Override
  public <T> T invoke(K key, javax.cache.processor.EntryProcessor<K,V,T> entryProcessor, Object... arguments) throws EntryProcessorException {
    checkClosed();
    checkNullKey(key);
    Map<K, EntryProcessorResult<T>> m = invokeAll(Collections.singleton(key), entryProcessor, arguments);
    return !m.isEmpty() ? m.values().iterator().next().get() : null;
  }

  @Override
  public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys, final javax.cache.processor.EntryProcessor<K,V,T> entryProcessor, final Object... arguments) {
    checkClosed();
    if (entryProcessor == null) {
      throw new NullPointerException("processor is null");
    }
    EntryProcessor<K, V, T> p = new EntryProcessor<K, V, T>() {
      @Override
      public T process(final MutableCacheEntry<K, V> e) {
        MutableEntryAdapter me = new MutableEntryAdapter(e);
        T _result = entryProcessor.process(me, arguments);
        return _result;
      }
    };
    Map<K, EntryProcessingResult<T>> _result = cache.invokeAll(keys, p);
    Map<K, EntryProcessorResult<T>> _mappedResult = new HashMap<K, EntryProcessorResult<T>>();
    for (Map.Entry<K, EntryProcessingResult<T>> e : _result.entrySet()) {
      final EntryProcessingResult<T> pr = e.getValue();
      EntryProcessorResult<T> epr = new EntryProcessorResult<T>() {
        @Override
        public T get() throws EntryProcessorException {
          Throwable t = pr.getException();
          if (t != null) {
            throw new EntryProcessorException(t);
          }
          return pr.getResult();
        }
      };
      _mappedResult.put(e.getKey(), epr);
    }
    return _mappedResult;
  }

  @Override
  public String getName() {
    return cache.getName();
  }

  @Override
  public CacheManager getCacheManager() {
    return manager;
  }

  @Override
  public void close() {
    cache.close();
  }

  @Override
  public boolean isClosed() {
    return cache.isClosed();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T unwrap(Class<T> clazz) {
    if (Cache.class.equals(clazz)) {
      return (T) cache;
    }
    throw new IllegalArgumentException("requested class unknown");
  }

  @Override
  public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> cfg) {
    eventHandling.registerListener(cfg);
  }

  @Override
  public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> cfg) {
    if (cfg == null) {
      throw new NullPointerException();
    }
    eventHandling.deregisterListener(cfg);
  }

  /**
   * Iterate with the help of cache2k key iterator.
   */
  @Override
  public Iterator<Entry<K,V>> iterator() {
    checkClosed();
    final Iterator<K> _keyIterator = cache.keys().iterator();
    return new Iterator<Entry<K, V>>() {

      CacheEntry<K, V> entry;

      @Override
      public boolean hasNext() {
        while(_keyIterator.hasNext()) {
          entry = cache.getEntry(_keyIterator.next());
          if (entry.getException() == null) {
            return true;
          }
        }
        entry = null;
        return false;
      }

      @Override
      public Entry<K, V> next() {
        if (entry == null && !hasNext()) {
          throw new NoSuchElementException();
        }
        return new Entry<K, V>() {
          @Override
          public K getKey() {
            return entry.getKey();
          }

          @Override
          public V getValue() {
            return entry.getValue();
          }

          @SuppressWarnings("unchecked")
          @Override
          public <T> T unwrap(Class<T> _class) {
            if (CacheEntry.class.equals(_class)) {
              return (T) entry;
            }
            return null;
          }
        };
      }

      @Override
      public void remove() {
        if (entry == null) {
          throw new IllegalStateException("hasNext() / next() not called or end of iteration reached");
        }
        cache.remove(entry.getKey());
      }
    };
  }

  /**
   * The TCK checks that cache closed exception is triggered before the exceptions about the
   * illegal argument, so first screen whether cache is closed.
   */
  void checkClosed() {
    if (cache.isClosed()) {
      throw new CacheClosedException(cache);
    }
  }

  private class MutableEntryAdapter implements MutableEntry<K, V> {

    private final MutableCacheEntry<K, V> entry;

    MutableEntryAdapter(MutableCacheEntry<K, V> e) {
      entry = e;
    }

    @Override
    public boolean exists() {
      return entry.exists();
    }

    @Override
    public void remove() {
      entry.remove();
    }

    @Override
    public void setValue(V value) {
      checkNullValue(value);
      entry.setValue(value);
    }

    @Override
    public K getKey() {
      return entry.getKey();
    }

    @Override
    public V getValue() {
      if (!readThrough && !exists()) {
        return null;
      }
      return entry.getValue();
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
      return null;
    }
  }

  public String toString() {
    return getClass().getSimpleName() + "!" + cache;
  }

}
