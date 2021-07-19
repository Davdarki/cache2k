package org.cache2k.impl.xmlConfiguration;

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

import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.cache2k.event.CacheEntryRemovedListener;

/**
 * @author Jens Wilke
 */
public class BaseDummyListener<K,V> implements CacheEntryRemovedListener<K,V> {

  @Override
  public void onEntryRemoved(final Cache<K, V> cache, final CacheEntry<K, V> entry) {

  }

}
