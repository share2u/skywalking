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

package org.skywalking.apm.agent;

import java.lang.instrument.Instrumentation;
import java.util.List;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.skywalking.apm.agent.core.boot.ServiceManager;
import org.skywalking.apm.agent.core.conf.SnifferConfigInitializer;
import org.skywalking.apm.agent.core.logging.api.ILog;
import org.skywalking.apm.agent.core.logging.api.LogManager;
import org.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.skywalking.apm.agent.core.plugin.EnhanceContext;
import org.skywalking.apm.agent.core.plugin.PluginBootstrap;
import org.skywalking.apm.agent.core.plugin.PluginException;
import org.skywalking.apm.agent.core.plugin.PluginFinder;

/**
 * The main entrance of sky-waking agent,
 * based on javaagent mechanism.
 *
 * @author wusheng
 */
public class SkyWalkingAgent {
    private static final ILog logger = LogManager.getLogger(SkyWalkingAgent.class);

    /**
     * Main entrance.
     * Use byte-buddy transform to enhance all classes, which define in plugins.
     *
     * @param agentArgs
     * @param instrumentation
     * @throws PluginException
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) throws PluginException {
        final PluginFinder pluginFinder;
        try {
            // 初始化 Agent 配置
            SnifferConfigInitializer.initialize();
            // 加载 Agent 插件们。而后，创建 PluginFinder
            pluginFinder = new PluginFinder(new PluginBootstrap().loadPlugins());
            // 初始化 Agent 服务管理。在这过程中，Agent 服务会被初始化
            ServiceManager.INSTANCE.boot();
        } catch (Exception e) {
            logger.error(e, "Skywalking agent initialized failure. Shutting down.");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                ServiceManager.INSTANCE.shutdown();
            }
        }, "skywalking service shutdown thread"));
        // 基于 byte-buddy ，初始化 Instrumentation 的 java.lang.instrument.ClassFileTransformer
        new AgentBuilder.Default()
                // 匹配所有需要增强的类
                .type(pluginFinder.buildMatch())
                // 定义对匹配的类的增强逻辑
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
                                                            ClassLoader classLoader, JavaModule module) {
                        // 根据匹配到的类型查找增强插件
                        List<AbstractClassEnhancePluginDefine> pluginDefines = pluginFinder.find(typeDescription, classLoader);
                        // 遍历插件并应用增强逻辑到类的字节码上
                        if (pluginDefines.size() > 0) {
                            DynamicType.Builder<?> newBuilder = builder;
                            EnhanceContext context = new EnhanceContext();
                            for (AbstractClassEnhancePluginDefine define : pluginDefines) {
                                DynamicType.Builder<?> possibleNewBuilder = define.define(typeDescription.getTypeName(), newBuilder, classLoader, context);
                                if (possibleNewBuilder != null) {
                                    newBuilder = possibleNewBuilder;
                                }
                            }
                            if (context.isEnhanced()) {
                                logger.debug("Finish the prepare stage for {}.", typeDescription.getName());
                            }

                            return newBuilder;
                        }

                        logger.debug("Matched class {}, but ignore by finding mechanism.", typeDescription.getTypeName());
                        return builder;
                    }
                })
                // 监控增强过程中的事件
                .with(new AgentBuilder.Listener() {
                    @Override
                    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {

                    }

                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                                 boolean loaded, DynamicType dynamicType) {
                        if (logger.isDebugEnable()) {
                            logger.debug("On Transformation class {}.", typeDescription.getName());
                        }

                        InstrumentDebuggingClass.INSTANCE.log(typeDescription, dynamicType);
                    }

                    @Override
                    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                          boolean loaded) {

                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                                        Throwable throwable) {
                        logger.error("Enhance class " + typeName + " error.", throwable);
                    }

                    @Override
                    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                    }
                })
                .installOn(instrumentation);
    }
}
