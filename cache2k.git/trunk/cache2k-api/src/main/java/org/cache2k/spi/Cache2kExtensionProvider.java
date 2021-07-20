package org.cache2k.spi;

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
 * Interface for a generic cache2k extension. The extension
 * {@link #registerCache2kExtension()} gets called before the first cache manager
 * is constructed.
 *
 * @author Jens Wilke
 */
public interface Cache2kExtensionProvider {

  void registerCache2kExtension();

}