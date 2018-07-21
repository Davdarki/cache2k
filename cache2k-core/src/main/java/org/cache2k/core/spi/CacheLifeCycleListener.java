package org.cache2k.core.spi;

/*
 * #%L
 * cache2k implementation
 * %%
 * Copyright (C) 2000 - 2018 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
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

/**
 * Interface to implement additional service providers that get notified
 * on a lifecycle change of the cache. Used for JMX support.
 *
 * @author Jens Wilke; created: 2013-07-01
 */
public interface CacheLifeCycleListener {

  void cacheCreated(Cache c);

  void cacheDestroyed(Cache c);

}