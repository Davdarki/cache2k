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

import java.io.Serializable;

/**
 * Marker for cache configuration beans. The marker is not mandatory.
 *
 * <p>Every bean that is used in a cache configuration should adhere to the Java Beans standard.
 * The objects need to be serializable since this is used to copy the default configuration.
 *
 * @author Jens Wilke
 */
public interface ConfigurationBean extends Serializable {
}
