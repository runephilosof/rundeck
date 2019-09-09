/*
 * Copyright 2019 Rundeck, Inc. (http://rundeck.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtolabs.rundeck.core.plugins;

import java.util.Collection;

/**
 * Config set associated with a service type name
 */
public interface PluginConfigSet
        extends PluginProviderConfigSet
{
    /**
     * @return the service name
     */
    String getService();

    static PluginConfigSet with(String service, Collection<PluginProviderConfiguration> configSet) {
        return new PluginConfigSet() {
            @Override
            public String getService() {
                return service;
            }

            @Override
            public Collection<PluginProviderConfiguration> getPluginProviderConfigs() {
                return configSet;
            }
        };
    }
}
