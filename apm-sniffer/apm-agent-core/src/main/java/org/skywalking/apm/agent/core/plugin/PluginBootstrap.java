/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
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
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.agent.core.plugin;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.skywalking.apm.agent.core.plugin.loader.AgentClassLoader;
import org.skywalking.apm.agent.core.logging.api.ILog;
import org.skywalking.apm.agent.core.logging.api.LogManager;

/**
 * Plugins finder.
 * Use {@link PluginResourcesResolver} to find all plugins,
 * and ask {@link PluginCfg} to load all plugin definitions.
 *
 * @author wusheng
 */
public class PluginBootstrap {
    private static final ILog logger = LogManager.getLogger(PluginBootstrap.class);

    /**
     * load all plugins.
     *
     * @return plugin definition list.
     */
    public List<AbstractClassEnhancePluginDefine> loadPlugins() throws AgentPackageNotFoundException {
        // 初始化AgentClassLoader
        AgentClassLoader.initDefaultLoader();
        // 使用PluginResourcesResolver获取插件资源的URL列表
        PluginResourcesResolver resolver = new PluginResourcesResolver();
        List<URL> resources = resolver.getResources();

        if (resources == null || resources.size() == 0) {
            logger.info("no plugin files (skywalking-plugin.def) found, continue to start application.");
            return new ArrayList<AbstractClassEnhancePluginDefine>();
        }
        // 逐个读取每个插件文件并解析配置
        for (URL pluginUrl : resources) {
            try {
                PluginCfg.INSTANCE.load(pluginUrl.openStream());
            } catch (Throwable t) {
                logger.error(t, "plugin file [{}] init failure.", pluginUrl);
            }
        }
        // 获取所有已解析的插件类配置
        List<PluginDefine> pluginClassList = PluginCfg.INSTANCE.getPluginClassList();
        // 遍历这些配置，通过Class.forName加载对应的插件类实例，并添加到列表中
        List<AbstractClassEnhancePluginDefine> plugins = new ArrayList<AbstractClassEnhancePluginDefine>();
        for (PluginDefine pluginDefine : pluginClassList) {
            try {
                logger.debug("loading plugin class {}.", pluginDefine.getDefineClass());
                AbstractClassEnhancePluginDefine plugin =
                    (AbstractClassEnhancePluginDefine)Class.forName(pluginDefine.getDefineClass(),
                        true,
                        AgentClassLoader.getDefault())
                        .newInstance();
                plugins.add(plugin);
            } catch (Throwable t) {
                logger.error(t, "load plugin [{}] failure.", pluginDefine.getDefineClass());
            }
        }

        return plugins;

    }

}
