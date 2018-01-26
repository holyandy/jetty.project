//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.Map;
import java.util.function.BiConsumer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WSURI;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class LocalServer extends ContainerLifeCycle implements LocalFuzzer.Provider
{
    private static final Logger LOG = Log.getLogger(LocalServer.class);
    private final ByteBufferPool bufferPool = new MappedByteBufferPool();
    private final WebSocketPolicy serverPolicy = WebSocketPolicy.newServerPolicy();
    private Server server;
    private ServerConnector connector;
    private LocalConnector localConnector;
    private ServletContextHandler servletContextHandler;
    private URI serverUri;
    private URI wsUri;
    private boolean ssl = false;
    private SslContextFactory sslContextFactory;

    public void enableSsl(boolean ssl)
    {
        this.ssl = ssl;
    }
    
    public LocalConnector getLocalConnector()
    {
        return localConnector;
    }
    
    public WebSocketPolicy getServerDefaultPolicy()
    {
        return serverPolicy;
    }
    
    public URI getServerUri()
    {
        return serverUri;
    }
    
    public ServletContextHandler getServletContextHandler()
    {
        return servletContextHandler;
    }
    
    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public URI getWsUri()
    {
        return wsUri;
    }

    public boolean isSslEnabled()
    {
        return ssl;
    }
    
    @Override
    public Parser newClientParser(Parser.Handler parserHandler)
    {
        return new Parser(WebSocketPolicy.newClientPolicy(), bufferPool, parserHandler);
    }
    
    @Override
    public LocalConnector.LocalEndPoint newLocalConnection()
    {
        return getLocalConnector().connect();
    }

    public Fuzzer newNetworkFuzzer() throws Exception
    {
        return new NetworkFuzzer(this);
    }
    
    public Fuzzer newLocalFuzzer() throws Exception
    {
        return new LocalFuzzer(this);
    }
    
    public Fuzzer newLocalFuzzer(CharSequence requestPath) throws Exception
    {
        return new LocalFuzzer(this, requestPath);
    }

    public Fuzzer newLocalFuzzer(CharSequence requestPath, Map<String,String> upgradeRequest) throws Exception
    {
        return new LocalFuzzer(this, requestPath, upgradeRequest);
    }
    
    protected Handler createRootHandler(Server server) throws Exception
    {
        servletContextHandler = new ServletContextHandler(server, "/", true, false);
        servletContextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(servletContextHandler);
        configureServletContextHandler(servletContextHandler);
        return servletContextHandler;
    }
    
    protected void configureServletContextHandler(ServletContextHandler context) throws Exception
    {
        /* override to change context handler */
    }
    
    @Override
    protected void doStart() throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("qtp-LocalServer");
        
        // Configure Server
        server = new Server(threadPool);
        if (ssl)
        {
            // HTTP Configuration
            HttpConfiguration http_config = new HttpConfiguration();
            http_config.setSecureScheme("https");
            http_config.setSecurePort(0);
            http_config.setOutputBufferSize(32768);
            http_config.setRequestHeaderSize(8192);
            http_config.setResponseHeaderSize(8192);
            http_config.setSendServerVersion(true);
            http_config.setSendDateHeader(false);
            
            sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
            sslContextFactory.setExcludeCipherSuites("SSL_RSA_WITH_DES_CBC_SHA", "SSL_DHE_RSA_WITH_DES_CBC_SHA", "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                    "SSL_RSA_EXPORT_WITH_RC4_40_MD5", "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA", "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");
            
            // SSL HTTP Configuration
            HttpConfiguration https_config = new HttpConfiguration(http_config);
            https_config.addCustomizer(new SecureRequestCustomizer());
            
            // SSL Connector
            connector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(https_config));
            connector.setPort(0);
        }
        else
        {
            // Basic HTTP connector
            connector = new ServerConnector(server);
            connector.setPort(0);
        }
        // Add network connector
        server.addConnector(connector);
        
        // Add Local Connector
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);
        
        Handler rootHandler = createRootHandler(server);
        server.setHandler(rootHandler);
        
        // Start Server
        addBean(server);
        
        super.doStart();
        
        // Establish the Server URI
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("%s://%s:%d/", ssl ? "https" : "http", host, port));
        wsUri = WSURI.toWebsocket(serverUri);
        
        // Some debugging
        if (LOG.isDebugEnabled())
        {
            LOG.debug(server.dump());
        }
    }

    public void registerHttpService(String urlPattern, BiConsumer<HttpServletRequest, HttpServletResponse> serviceConsumer)
    {
        ServletHolder holder = new ServletHolder(new BiConsumerServiceServlet(serviceConsumer));
        servletContextHandler.addServlet(holder, urlPattern);
    }

    public void registerWebSocket(String urlPattern, WebSocketCreator creator)
    {
        ServletHolder holder = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.setCreator(creator);
            }
        });
        servletContextHandler.addServlet(holder, urlPattern);
    }
}
