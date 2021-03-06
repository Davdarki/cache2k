package org.cache2k.jcache.provider.event;

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

import javax.cache.Cache;
import javax.cache.event.EventType;

/**
 * Entry event with original value for update events.
 *
 * @author Jens Wilke
 */
public class EntryEventWithOldValue<K, V> extends EntryEvent<K, V> {

  private V oldValue;

  public EntryEventWithOldValue(final Cache source, final EventType eventType, final K _key, final V _value, final V _oldValue) {
    super(source, eventType, _key, _value);
    oldValue = _oldValue;
  }

  @Override
  public V getOldValue() {
    return oldValue;
  }

  @Override
  public boolean isOldValueAvailable() {
    return true;
  }

}
