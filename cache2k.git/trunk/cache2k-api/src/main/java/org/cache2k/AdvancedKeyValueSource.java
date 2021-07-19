package org.cache2k;

/*
 * #%L
 * cache2k API
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

import java.util.Map;

/**
 * {@link KeyValueSource} with bulk {@link #get(Object) get} and prefetching.
 *
 * @author Jens Wilke
 * @since 1.0
 */
public interface AdvancedKeyValueSource<K,V> extends KeyValueSource<K,V> {

  /**
   * Retrieves all values for the given keys. For a more detailed description see
   * the cache interface.
   *
   * @see Cache#getAll(Iterable)
   */
  Map<K, V> getAll(Iterable<? extends K> keys);

  /**
   * Notify the cache about the intention to retrieve the value for this key in the
   * near future. For a more detailed description see the cache interface.
   *
   * @see Cache#prefetch(Object)
   */
  void prefetch(K key);

  /**
   * Notify the cache about the intention to retrieve the value for the keys in the
   * near future. For a more detailed description see the cache interface.
   *
   * @see Cache#prefetchAll(Iterable, CacheOperationCompletionListener)
   */
  void prefetchAll(Iterable<? extends K> keys, CacheOperationCompletionListener listener);

}
