/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.server;

import com.networknt.config.Config;
import com.networknt.handler.MiddlewareHandler;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import java.util.ServiceLoader;


public class Server {

    static final Logger logger = LoggerFactory.getLogger(Server.class);

    static protected boolean shutdownRequested = false;
    static Undertow server = null;
    static String configName = "server";

    public static void main(final String[] args) {
        logger.info("server starts");
        start();
    }

    static public void start() {

        // add shutdown hook here.
        addDaemonShutdownHook();

        // add startup hooks here.
        final ServiceLoader<StartupHookProvider> startupLoaders = ServiceLoader.load(StartupHookProvider.class);
        for (final StartupHookProvider provider : startupLoaders) {
            provider.onStartup();
        }

        ServerConfig config = (ServerConfig) Config.getInstance().getJsonObjectConfig(configName, ServerConfig.class);

        HttpHandler handler = null;

        // API routing handler or others handler implemented by application developer.
        final ServiceLoader<HandlerProvider> handlerLoaders = ServiceLoader.load(HandlerProvider.class);
        for (final HandlerProvider provider : handlerLoaders) {
            if (provider.getHandler() != null) {
                handler = provider.getHandler();
                break;
            }
        }
        if (handler == null) {
            logger.warn("No route handler provider available in the classpath");
            return;
        }

        // Middleware Handlers plugged into the handler chain.
        final ServiceLoader<MiddlewareHandler> middlewareLoaders = ServiceLoader.load(MiddlewareHandler.class);
        logger.debug("found middlewareLoaders", middlewareLoaders);
        for (final MiddlewareHandler middlewareHandler : middlewareLoaders) {
            logger.info("Plugin: " + middlewareHandler.getClass().getName());
            if(middlewareHandler.isEnabled()) {
                handler = middlewareHandler.setNext(handler);
                middlewareHandler.register();
            }
        }

        server = Undertow.builder()
                .addHttpListener(
                        config.getPort(),
                        config.getIp())
                .setBufferSize(1024 * 16)
                .setIoThreads(Runtime.getRuntime().availableProcessors() * 2) //this seems slightly faster in some configurations
                .setSocketOption(Options.BACKLOG, 10000)
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, false) //don't send a keep-alive header for HTTP/1.1 requests, as it is not required
                .setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
                .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false)
                .setHandler(Handlers.header(handler,
                        Headers.SERVER_STRING, "Light"))
                .setWorkerThreads(200)
                .build();
        server.start();
    }

    static public void stop() {
        if (server != null) server.stop();
    }

    // implement shutdown hook here.
    static public void shutdown() {
        final ServiceLoader<ShutdownHookProvider> shutdownLoaders = ServiceLoader.load(ShutdownHookProvider.class);
        for (final ShutdownHookProvider provider : shutdownLoaders) {
            provider.onShutdown();
        }
        stop();
        logger.info("Cleaning up before server shutdown");
    }

    static protected void addDaemonShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                Server.shutdown();
            }
        });
    }
}
