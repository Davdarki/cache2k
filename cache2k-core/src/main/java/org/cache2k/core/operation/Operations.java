package org.cache2k.core.operation;

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

import org.cache2k.expiry.ExpiryTimeValues;
import org.cache2k.integration.CacheLoaderException;
import org.cache2k.processor.EntryProcessingException;
import org.cache2k.processor.EntryProcessor;
import org.cache2k.processor.RestartException;

import java.util.concurrent.Callable;

/**
 * Semantics of all cache operations on entries.
 *
 * @author Jens Wilke
 */
@SuppressWarnings({"unchecked", "UnusedParameters"})
public class Operations<K, V> {

  public final static Operations SINGLETON = new Operations();

  public Semantic<K, V, V> peek(K key) {
    return PEEK;
  }
  static final Semantic PEEK = new Semantic.Read() {
    @Override
    public void examine(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.result(e.getValueOrException());
      }
      c.noMutation();
    }
  };

  public Semantic<K, V, V> get(K key) {
    return GET;
  }

  public static final Semantic GET = new Semantic.MightUpdate() {

    @Override
    public void examine(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.result(e.getValueOrException());
        c.noMutation();
      } else {
        if (c.isLoaderPresent()) {
          c.wantMutation();
        } else {
          c.noMutation();
        }
      }
    }

    @Override
    public void mutate(Progress c, ExaminationEntry e) {
      c.load();
    }
  };

  public final Semantic UNCONDITIONAL_LOAD = new Semantic.InsertOrUpdate() {
    @Override
    public void mutate(final Progress c, final ExaminationEntry e) {
      c.load();
    }
  };

  public final Semantic<K,V,Void> REFRESH = new Semantic.Update<K,V,Void>() {
    @Override
    public void mutate(final Progress<K,V,Void> c, final ExaminationEntry<K,V> e) {
      c.refresh();
    }
  };

  public Semantic<K, V, ResultEntry<K,V>> getEntry(K key) {
    return GET_ENTRY;
  }

  static final Semantic GET_ENTRY = new Semantic.MightUpdate() {

    @Override
    public void examine(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.entryResult(e);
        c.noMutation();
      } else {
        if (c.isLoaderPresent()) {
          c.wantMutation();
        } else {
          c.noMutation();
        }
      }
    }

    @Override
    public void mutate(final Progress c, final ExaminationEntry e) {
      c.load();
    }

    @Override
    public void loaded(final Progress c, final ExaminationEntry e) {
      c.entryResult(e);
    }
  };

  public Semantic<K, V, ResultEntry<K,V>> peekEntry(K key) {
    return PEEK_ENTRY;
  }

  static final Semantic PEEK_ENTRY = new Semantic.Read() {

    @Override
    public void examine(final Progress c, final ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.entryResult(e);
      }
      c.noMutation();
    }

  };

  public Semantic<K, V, V> remove(K key) {
    return REMOVE;
  }

  static final Semantic REMOVE = new Semantic.InsertOrUpdate() {

    @Override
    public void mutate(Progress c, ExaminationEntry e) {
      c.remove();
    }
  };

  public Semantic<K, V, Boolean> containsAndRemove(K key) {
    return CONTAINS_REMOVE;
  }

  static final Semantic CONTAINS_REMOVE = new Semantic.Update() {

    @Override
    public void mutate(Progress c, ExaminationEntry e) {
      if (c.isPresent()) {
        c.result(true);
        c.remove();
        return;
      }
      c.result(false);
      c.remove();
    }
  };

  public Semantic<K, V, Boolean> contains(K key) {
    return CONTAINS;
  }

  static final Semantic CONTAINS = new Semantic.Read() {

    @Override
    public void examine(Progress c, ExaminationEntry e) {
      c.result(c.isPresent());
      c.noMutation();
    }

  };

  public Semantic<K, V, V> peekAndRemove(K key) {
    return PEEK_REMOVE;
  }

  static final Semantic PEEK_REMOVE = new Semantic.Update() {

    @Override
    public void mutate(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.result(e.getValueOrException());
      }
      c.remove();
    }
  };

  public Semantic<K, V, V> peekAndReplace(final K key, final V value) {
    return new Semantic.MightUpdate<K, V, V>() {

      @Override
      public void examine(final Progress<K, V, V> c, final ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          c.result(e.getValueOrException());
          c.wantMutation();
          return;
        }
        c.noMutation();
      }

      @Override
      public void mutate(Progress<K, V, V> c, ExaminationEntry<K, V> e) {
        c.put(value);
      }

    };
  }

  public Semantic<K, V, V> peekAndPut(final K key, final V value) {
    return new Semantic.Update<K, V, V>() {

      @Override
      public void mutate(Progress<K, V, V> c, ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          c.result(e.getValueOrException());
        }
        c.put(value);
      }

    };
  }

  public Semantic<K, V, V> computeIfAbsent(final K key, final Callable<V> _function) {
    return new Semantic.MightUpdate<K, V, V>() {

      @Override
      public void examine(final Progress<K, V, V> c, final ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          c.result(e.getValueOrException());
          c.noMutation();
        } else {
          c.wantMutation();
        }
      }

      @Override
      public void mutate(Progress<K, V, V> c, ExaminationEntry<K, V> e) {
        try {
          V _value = _function.call();
          c.result(_value);
          c.put(_value);
        } catch (RuntimeException ex) {
          c.failure(ex);
        } catch (Exception ex) {
          c.failure(new CacheLoaderException(ex));
        }
      }

    };
  }

  public Semantic<K, V, V> put(final K key, final V value) {
    return new Semantic.InsertOrUpdate<K, V, V>() {

      @Override
      public void mutate(Progress<K, V, V> c, ExaminationEntry<K, V> e) {
        c.put(value);
      }

    };
  }

  /**
   * Updates intentionally hit and miss counter to adjust with JSR107.
   *
   * @see org.cache2k.Cache#putIfAbsent(Object, Object)
   */
  public Semantic<K, V, Boolean> putIfAbsent(final K key, final V value) {
    return new Semantic.MightUpdate<K, V, Boolean>() {

      @Override
      public void examine(final Progress<K, V, Boolean> c, final ExaminationEntry<K, V> e) {
        if (!c.isPresentOrMiss()) {
          c.result(true);
          c.wantMutation();
        } else {
          c.result(false);
          c.noMutation();
        }
      }

      @Override
      public void mutate(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        c.put(value);
      }

    };
  }

  /**
   * Updates intentionally hit and miss counter to adjust with JSR107.
   *
   * @see org.cache2k.Cache#replace(Object, Object)
   */
  public Semantic<K, V, Boolean> replace(final K key, final V value) {
    return new Semantic.MightUpdate<K, V, Boolean>() {

      @Override
      public void examine(final Progress<K, V, Boolean> c, final ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          c.result(true);
          c.wantMutation();
        } else {
          c.result(false);
          c.noMutation();
        }
      }

      @Override
      public void mutate(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        c.put(value);
      }

    };
  }

  public Semantic<K, V, Boolean> replace(final K key, final V value, final V newValue) {
    return new Semantic.MightUpdate<K, V, Boolean>() {

      @Override
      public void examine(final Progress<K, V, Boolean> c, final ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss() &&
          ( (value ==  e.getValueOrException()) ||
            (value != null && value.equals(e.getValueOrException()))) ) {
          c.result(true);
          c.wantMutation();
        } else {
          c.result(false);
          c.noMutation();
        }
      }

      @Override
      public void mutate(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        c.put(newValue);
      }

    };
  }

  public Semantic<K, V, Boolean> remove(final K key, final V value) {
    return new Semantic.MightUpdate<K, V, Boolean>() {

      @Override
      public void examine(final Progress<K, V, Boolean> c, final ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss() &&
          ( (value == null && e.getValueOrException() == null) ||
            value.equals(e.getValueOrException())) ) {
          c.result(true);
          c.wantMutation();
        } else {
          c.result(false);
          c.noMutation();
        }
      }

      @Override
      public void mutate(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        c.remove();
      }

    };
  }

  public <R> Semantic<K, V, R> invoke(final K key, final EntryProcessor<K, V, R> processor) {
    return new Semantic.Base<K, V, R>() {

      private MutableEntryOnProgress<K, V> mutableEntryOnProgress;
      private boolean needsLoad;

      @Override
      public void start(final Progress<K, V, R> c) {
        mutableEntryOnProgress = new MutableEntryOnProgress<K, V>(key, c, null);
        try {
          R _result = processor.process(mutableEntryOnProgress);
          c.result(_result);
        } catch (WantsDataRestartException rs) {
          c.wantData();
          return;
        } catch (Throwable t) {
          c.failure(new EntryProcessingException(t));
          return;
        }
        if (mutableEntryOnProgress.isMutationNeeded()) {
          c.wantMutation();
        } else {
          c.noMutation();
        }
      }

      @Override
      public void examine(final Progress<K, V, R> c, final ExaminationEntry<K, V> e) {
        mutableEntryOnProgress = new MutableEntryOnProgress<K, V>(key, c, e);
        try {
          R _result = processor.process(mutableEntryOnProgress);
          c.result(_result);
        } catch (NeedsLoadRestartException rs) {
          needsLoad = true;
          c.wantMutation();
          return;
        } catch (Throwable t) {
          c.failure(new EntryProcessingException(t));
          return;
        }
        if (mutableEntryOnProgress.isMutationNeeded()) {
          c.wantMutation();
        } else {
          c.noMutation();
        }
      }

      @Override
      public void mutate(final Progress<K, V, R> c, final ExaminationEntry<K, V> e) {
        if (needsLoad) {
          needsLoad = false;
          c.loadAndRestart();
          return;
        }
        mutableEntryOnProgress.sendMutationCommand();
      }

      /** No operation, result is set by the entry processor */
      @Override
      public void loaded(final Progress<K, V, R> c, final ExaminationEntry<K, V> e) { }
    };
  }

  public static class WantsDataRestartException extends RestartException { }

  public static class NeedsLoadRestartException extends RestartException { }

  public Semantic<K, V, Void> expire(K key, final long t) {
    return new Semantic.MightUpdate<K, V, Void>() {

      @Override
      public void examine(final Progress<K, V, Void> c, final ExaminationEntry<K, V> e) {
        if (t == ExpiryTimeValues.NO_CACHE ||
          t == ExpiryTimeValues.REFRESH) {
          if (c.isPresentOrInRefreshProbation()) {
            c.wantMutation();
          } else {
            c.noMutation();
          }
        } else if (c.isPresent()) {
          c.wantMutation();
        } else {
          c.noMutation();
        }
      }

      @Override
      public void mutate(Progress c, ExaminationEntry e) {
        c.expire(t);
      }
    };
  }

  public final Semantic<K,V, Void> EXPIRE_EVENT = new Semantic.MightUpdate<K, V, Void>() {

    @Override
    public void examine(final Progress<K, V, Void> c, final ExaminationEntry<K, V> e) {
      if (c.isExpiryTimeReachedOrInRefreshProbation()) {
        c.wantMutation();
        return;
      }
      c.noMutation();
    }

    @Override
    public void mutate(final Progress<K, V, Void> c, final ExaminationEntry<K, V> e) {
      c.expire(ExpiryTimeValues.NO_CACHE);
    }
  };

}
