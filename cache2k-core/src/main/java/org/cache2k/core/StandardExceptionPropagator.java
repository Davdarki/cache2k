package org.cache2k.core;

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
import org.cache2k.integration.ExceptionInformation;
import org.cache2k.integration.ExceptionPropagator;

import java.sql.Timestamp;

/**
 * Standard behavior of the exception propagator.
 *
 * @author Jens Wilke
 */
public final class StandardExceptionPropagator implements ExceptionPropagator {

  @Override
  public RuntimeException propagateException(Object key, final ExceptionInformation exceptionInformation) {
    long _expiry = exceptionInformation.getUntil();
    String txt = "";
    if (_expiry > 0) {
      if (_expiry == ExpiryTimeValues.ETERNAL) {
        txt = "expiry=ETERNAL, cause: ";
      } else {
        txt = "expiry=" + formatMillis(_expiry) + ", cause: ";
      }
    }
    return new CacheLoaderException(txt + exceptionInformation.getException(), exceptionInformation.getException());
  }

  /**
   * Use the SQL timestamp for a compact time output. The time is formatted in the default timezone.
   */
  private String formatMillis(long t) {
    return new Timestamp(t).toString();
  }

}
