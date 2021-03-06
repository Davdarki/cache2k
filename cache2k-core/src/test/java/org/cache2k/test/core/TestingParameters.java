package org.cache2k.test.core;

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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 */
public class TestingParameters {

  public static final Log RESULT_LOG = LogFactory.getLog("results");

  /**
   * Maximum time in millis we wait for an event to finish. Might need to be increased in loaded environments.
   */
  public static final long MAX_FINISH_WAIT_MILLIS = calculateMaxFinishWait();

  /**
   * Minimum amount of time that we expect to pass in waiting for an event.
   */
  public static final long MINIMAL_TICK_MILLIS = 3;

  /**
   * We cannot use this at the moment since the constant {@link #MAX_FINISH_WAIT_MILLIS}
   * is used inside test annotations.
   */
  static long calculateMaxFinishWait() {
    if (System.getProperty("org.cache2k.test.timeout") != null) {
      return Long.parseLong(System.getProperty("org.cache2k.test.timeout"));
    } else {
      return 60000;
    }
  }

}
