package org.cache2k.jmx;

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
 * Bean representing a cache manager.
 *
 * @author Jens Wilke
 */
@SuppressWarnings("unused")
public interface CacheManagerMXBean {

  /**
   * "ok" if no issues are to report, otherwise it starts with "WARNING:" or
   * "FAILURE:" and a more descriptive text.
   */
  String getHealthStatus();

  /**
   * Clear all associated caches.
   */
  void clear();

  String getVersion();

  String getBuildNumber();

}
