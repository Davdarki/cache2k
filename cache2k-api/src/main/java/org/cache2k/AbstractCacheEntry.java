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

/**
 * Abstract class for cache entry providing suitable defaults.
 *
 * @author Jens Wilke
 */
public abstract class AbstractCacheEntry<K,V> implements CacheEntry<K,V> {

  @SuppressWarnings("deprecation")
  @Override
  public long getLastModification() {
    throw new UnsupportedOperationException();
  }
}
