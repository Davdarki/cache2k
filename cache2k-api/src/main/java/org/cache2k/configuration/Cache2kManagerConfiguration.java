package org.cache2k.configuration;

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
 * Configuration options for a cache manager. The options can only be changed if a
 * XML file is provided. This bean is in the API artifact for documentation purposes.
 *
 * @author Jens Wilke
 */
public class Cache2kManagerConfiguration implements ConfigurationBean {

  private String version = null;
  private String defaultManagerName = null;
  private boolean ignoreMissingCacheConfiguration = false;
  private boolean skipCheckOnStartup = false;
  private boolean ignoreAnonymousCache = false;

  public boolean isIgnoreMissingCacheConfiguration() {
    return ignoreMissingCacheConfiguration;
  }

  /**
   * Configure a cache with default parameters if configuration has no specific section for it.
   */
  public void setIgnoreMissingCacheConfiguration(final boolean f) {
    ignoreMissingCacheConfiguration = f;
  }

  public String getDefaultManagerName() {
    return defaultManagerName;
  }

  /**
   * Replace the default name of the default cache manager.
   */
  public void setDefaultManagerName(final String v) {
    defaultManagerName = v;
  }

  public String getVersion() {
    return version;
  }

  /**
   * Version of the configuration. Mandatory in every cache configuration. The version affects
   * how the configuration XML file is interpreted.
   */
  public void setVersion(final String v) {
    version = v;
  }

  public boolean isSkipCheckOnStartup() {
    return skipCheckOnStartup;
  }

  /**
   * The configuration for each cache is parsed and checked as soon as the cache manager is created.
   */
  public void setSkipCheckOnStartup(final boolean f) {
    skipCheckOnStartup = f;
  }

  public boolean isIgnoreAnonymousCache() {
    return ignoreAnonymousCache;
  }

  /**
   * When a configuration is present, every cache needs a cache name so that the configuration
   * can be applied.
   */
  public void setIgnoreAnonymousCache(final boolean f) {
    ignoreAnonymousCache = f;
  }

}
