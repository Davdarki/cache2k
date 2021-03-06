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

import org.cache2k.expiry.ExpiryPolicy;

/**
 * Wraps an exception thrown from a cache customization such as the
 * {@link ExpiryPolicy} or {@link org.cache2k.integration.CacheLoader}.
 *
 * @author Jens Wilke
 */
public class CustomizationException extends CacheException {

  public CustomizationException(final String message) {
    super(message);
  }

  public CustomizationException(final Throwable cause) {
    super(cause);
  }

  public CustomizationException(String message, Throwable cause) {
    super(message, cause);
  }

}
