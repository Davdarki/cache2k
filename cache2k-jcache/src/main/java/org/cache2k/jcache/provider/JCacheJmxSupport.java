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
import org.cache2k.configuration.Cache2kConfiguration;
import org.cache2k.core.spi.CacheLifeCycleListener;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * @author Jens Wilke; created: 2015-04-29
 */
@SuppressWarnings("WeakerAccess")
public class JCacheJmxSupport implements CacheLifeCycleListener {

  private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

  @Override
  public void cacheCreated(Cache c, final Cache2kConfiguration cfg) {
  }

  @Override
  public void cacheDestroyed(Cache c) {
    disableStatistics(c);
    disableJmx(c);
  }

  public void enableStatistics(JCacheAdapter c) {
    MBeanServer mbs = mBeanServer;
    String _name = createStatisticsObjectName(c.cache);
    try {
       mbs.registerMBean(
         new JCacheJmxStatisticsMXBean(c),
         new ObjectName(_name));
    } catch (Exception e) {
      throw new IllegalStateException("Error registering JMX bean, name='" + _name + "'", e);
    }
  }

  public void disableStatistics(Cache c) {
    MBeanServer mbs = mBeanServer;
    String _name = createStatisticsObjectName(c);
    try {
      mbs.unregisterMBean(new ObjectName(_name));
    } catch (InstanceNotFoundException ignore) {
    } catch (Exception e) {
      throw new IllegalStateException("Error unregister JMX bean, name='" + _name + "'", e);
    }
  }

  public void enableJmx(Cache c, javax.cache.Cache ca) {
    MBeanServer mbs = mBeanServer;
    String _name = createJmxObjectName(c);
    try {
       mbs.registerMBean(new JCacheJmxCacheMXBean(ca), new ObjectName(_name));
    } catch (Exception e) {
      throw new IllegalStateException("Error register JMX bean, name='" + _name + "'", e);
    }
  }

  public void disableJmx(Cache c) {
    MBeanServer mbs = mBeanServer;
    String _name = createJmxObjectName(c);
    try {
      mbs.unregisterMBean(new ObjectName(_name));
    } catch (InstanceNotFoundException ignore) {
    } catch (Exception e) {
      throw new IllegalStateException("Error unregister JMX bean, name='" + _name + "'", e);
    }
  }

  public String createStatisticsObjectName(Cache cache) {
    return "javax.cache:type=CacheStatistics," +
          "CacheManager=" + sanitizeName(cache.getCacheManager().getName()) +
          ",Cache=" + sanitizeName(cache.getName());
  }

  public String createJmxObjectName(Cache cache) {
    return "javax.cache:type=CacheConfiguration," +
          "CacheManager=" + sanitizeName(cache.getCacheManager().getName()) +
          ",Cache=" + sanitizeName(cache.getName());
  }

  /**
   * Filter illegal chars, same rule as in TCK or RI?
   */
  public static String sanitizeName(String string) {
    return string == null ? "" : string.replaceAll(":|=|\n|,", ".");
  }

}
