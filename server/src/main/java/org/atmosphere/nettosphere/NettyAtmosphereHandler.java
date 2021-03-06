/*
 * Copyright 2012 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.nettosphere;

import org.atmosphere.container.NettyCometSupport;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereMappingException;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.util.FakeHttpSession;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.handler.codec.http.Cookie;
import org.jboss.netty.handler.codec.http.CookieDecoder;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.HeaderConfig.SSE_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;
import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Bridge the Atmosphere Framework with Netty.
 */
public class NettyAtmosphereHandler extends HttpStaticFileServerHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyAtmosphereHandler.class);
    private final AtmosphereFramework framework;
    private final Config config;
    private final ScheduledExecutorService suspendTimer;
    private final ConcurrentHashMap<String, HttpSession> sessions = new ConcurrentHashMap<String, HttpSession>();
    private final AtomicBoolean isShutdown = new AtomicBoolean();
    private final WebSocketProcessor webSocketProcessor;
    private final ChannelGroup httpChannels = new DefaultChannelGroup("http");
    private final ChannelGroup websoketChannels = new DefaultChannelGroup("ws");

    public NettyAtmosphereHandler(final Config config) {
        super(config.path());
        this.config = config;
        framework = new AtmosphereFramework();

        if (config.broadcaster() != null) {
            framework.setDefaultBroadcasterClassName(config.broadcaster().getName());
        }

        framework.setAtmosphereDotXmlPath(config.configFile());
        framework.setAsyncSupport(new NettyCometSupport(framework.getAtmosphereConfig()) {
            public Action suspended(AtmosphereRequest request, AtmosphereResponse response) throws IOException, ServletException {
                Action a = super.suspended(request, response);
                if (framework.getAtmosphereConfig().isSupportSession()) {
                    AtmosphereResource r = request.resource();
                    HttpSession s = request.getSession(true);
                    if (s != null) {
                        sessions.put(r.uuid(), request.getSession(true));
                    }
                }
                return a;
            }
        });
        try {
            if (config.broadcasterFactory() != null) {
                framework.setBroadcasterFactory(config.broadcasterFactory());
            }
        } catch (Throwable t) {
            logger.trace("", t);
        }

        if (config.broadcasterCache() != null) {
            try {
                framework.setBroadcasterCacheClassName(config.broadcasterCache().getName());
            } catch (Throwable t) {
                logger.trace("", t);
            }
        }

        Map<String, AtmosphereHandler> handlersMap = config.handlersMap();
        for (Map.Entry<String, AtmosphereHandler> e : handlersMap.entrySet()) {
            framework.addAtmosphereHandler(e.getKey(), e.getValue());
        }

        if (config.webSocketProtocol() != null) {
            framework.setWebSocketProtocolClassName(config.webSocketProtocol().getName());
        }

        for (AtmosphereInterceptor i : config.interceptors()) {
            framework.interceptor(i);
        }

        if (!config.scanPackages().isEmpty()) {
            for (Class<?> s : config.scanPackages()) {
                framework.addAnnotationPackage(s);
            }
        }

        final Context context = new Context.Builder().contextPath(config.mappingPath()).basePath(config.path()).build();
        ServletContext ctx = (ServletContext) Proxy.newProxyInstance(NettyAtmosphereHandler.class.getClassLoader(), new Class[]{ServletContext.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        Method stub = Context.class.getMethod(method.getName(), method.getParameterTypes());
                        if (stub != null) {
                            return stub.invoke(context, args);
                        } else {
                            logger.trace("Method {} not supported", method.getName());
                            return null;
                        }
                    }
                });

        try {
            framework.init(new NettyServletConfig(config.initParams(), ctx));
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
        suspendTimer = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());
        webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent messageEvent) throws URISyntaxException, IOException {
        Object msg = messageEvent.getMessage();

        if (isShutdown.get()) {
            ctx.getChannel().close().addListener(ChannelFutureListener.CLOSE);
            return;
        }

        if (msg instanceof HttpRequest) {
            HttpRequest r = HttpRequest.class.cast(msg);
            // Netty fail to decode headers separated by a ','
            List<String> c = r.getHeaders("Connection");
            String u = r.getHeader("Upgrade");
            boolean webSocket = false;
            if (u != null && u.equalsIgnoreCase("websocket")) {
                webSocket = true;
            }

            for (String connection : c) {
                if (connection != null && connection.toLowerCase().equalsIgnoreCase("upgrade")) {
                    webSocket = true;
                }
            }

            logger.trace("Handling request {}", r);
            if (webSocket) {
                handleWebSocketHandshake(ctx, messageEvent);
            } else {
                handleHttp(ctx, messageEvent);
            }
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, messageEvent);
        }
    }

    private void handleWebSocketHandshake(ChannelHandlerContext ctx, MessageEvent messageEvent) throws IOException, URISyntaxException {
        final HttpRequest request = (HttpRequest) messageEvent.getMessage();

        // Allow only GET methods.
        if (request.getMethod() != GET) {
            sendHttpResponse(ctx, request, new DefaultHttpResponse(HTTP_1_1, FORBIDDEN));
            return;
        }

        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(request), null, false);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(request);

        if (handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel());
        } else {
            handshaker.handshake(ctx.getChannel(), request);
        }
        websoketChannels.add(ctx.getChannel());

        AtmosphereRequest r = createAtmosphereRequest(ctx, request);
        WebSocket webSocket = new NettyWebSocket(ctx.getChannel(), framework.getAtmosphereConfig());

        ctx.setAttachment(webSocket);
        webSocketProcessor.open(webSocket, r, AtmosphereResponse.newInstance(framework.getAtmosphereConfig(), r, webSocket));
    }

    private void handleWebSocketFrame(final ChannelHandlerContext ctx, final MessageEvent messageEvent) throws URISyntaxException, IOException {
        WebSocketFrame frame = (WebSocketFrame) messageEvent.getMessage();

        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            ctx.getChannel().write(frame).addListener(ChannelFutureListener.CLOSE);
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
        } else if (frame instanceof BinaryWebSocketFrame) {
            ChannelBuffer binaryData = frame.getBinaryData();
            webSocketProcessor.invokeWebSocketProtocol((WebSocket) ctx.getAttachment(), binaryData.array(), binaryData.arrayOffset(), binaryData.readableBytes());
        } else if (frame instanceof TextWebSocketFrame) {
            webSocketProcessor.invokeWebSocketProtocol((WebSocket) ctx.getAttachment(), ((TextWebSocketFrame) frame).getText());
        } else {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }
    }

    private AtmosphereRequest createAtmosphereRequest(final ChannelHandlerContext ctx, HttpRequest request) throws URISyntaxException, UnsupportedEncodingException, MalformedURLException {
        final String base = getBaseUri(request);
        final URI requestUri = new URI(base.substring(0, base.length() - 1) + request.getUri());
        String ct = request.getHeaders("Content-Type").size() > 0 ? request.getHeaders("Content-Type").get(0) : "text/plain";
        long cl = HttpHeaders.getContentLength(request);
        String method = request.getMethod().getName();

        String queryString = requestUri.getQuery();
        Map<String, String[]> qs = new HashMap<String, String[]>();
        if (queryString != null) {
            parseQueryString(qs, queryString);
        }

        if (ct.equalsIgnoreCase("application/x-www-form-urlencoded")) {
            parseQueryString(qs, new String(request.getContent().array(), "UTF-8"));
        }

        String u = requestUri.toURL().toString();
        int last = u.indexOf("?") == -1 ? u.length() : u.indexOf("?");
        String url = u.substring(0, last);
        int l;

        if (url.contains(config.mappingPath())) {
            l = requestUri.getAuthority().length() + requestUri.getScheme().length() + 3 + config.mappingPath().length();
        } else {
            l = requestUri.getAuthority().length() + requestUri.getScheme().length() + 3;
        }

        HttpSession session = null;
        if (framework.getAtmosphereConfig().isSupportSession()) {
            String[] transport = qs.get(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
            if (transport != null && transport.length > 0) {
                String[] uuid = qs.get(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
                if (uuid != null && uuid.length > 0) {
                    // TODO: Session is only supported until an unsubscribe is received.
                    if (transport[0].equalsIgnoreCase(HeaderConfig.DISCONNECT)) {
                        sessions.remove(uuid[0]);
                    } else {
                        session = sessions.get(uuid[0]);

                        if (session == null) {
                            session = new FakeHttpSession("-1", null, System.currentTimeMillis(), -1);
                        }
                    }
                }
            }
        }

        final Map<String, Object> attributes = new HashMap<String, Object>();
        AtmosphereRequest.Builder requestBuilder = new AtmosphereRequest.Builder();
        AtmosphereRequest r = requestBuilder.requestURI(url.substring(l))
                .requestURL(url)
                .pathInfo(url.substring(l))
                .headers(getHeaders(request))
                .method(method)
                .contentType(ct)
                .contentLength(cl)
                .destroyable(false)
                .attributes(attributes)
                .servletPath(config.mappingPath())
                .session(session)
                .cookies(getCookies(request))
                .queryStrings(qs)
                .remotePort(((InetSocketAddress) ctx.getChannel().getRemoteAddress()).getPort())
                .remoteAddr(((InetSocketAddress) ctx.getChannel().getRemoteAddress()).getAddress().getHostAddress())
                .remoteHost(((InetSocketAddress) ctx.getChannel().getRemoteAddress()).getHostName())
                .localPort(((InetSocketAddress) ctx.getChannel().getLocalAddress()).getPort())
                .localAddr(((InetSocketAddress) ctx.getChannel().getLocalAddress()).getAddress().getHostAddress())
                .localName(((InetSocketAddress) ctx.getChannel().getLocalAddress()).getHostName())
                .inputStream(new ChannelBufferInputStream(request.getContent()))
                .build();

        return r;
    }

    private void handleHttp(final ChannelHandlerContext ctx, final MessageEvent messageEvent) throws URISyntaxException, IOException {
        final HttpRequest request = (HttpRequest) messageEvent.getMessage();
        final ChannelAsyncIOWriter w = new ChannelAsyncIOWriter(ctx.getChannel(), true, HttpHeaders.isKeepAlive(request));
        boolean resumeOnBroadcast = false;
        boolean keptOpen = false;
        String method = request.getMethod().getName();

        // First let's try to see if it's a static resources
        try {
            request.addHeader(STATIC_MAPPING, "true");
            super.messageReceived(ctx, messageEvent);

            if (request.getHeader(SERVICED) != null) {
                return;
            }
        } catch (Exception e) {
            logger.debug("", e);
        } finally {
            request.addHeader(STATIC_MAPPING, "false");
        }

        boolean skipClose = false;
        try {
            AtmosphereRequest r = createAtmosphereRequest(ctx, request);

            AtmosphereResponse response = new AtmosphereResponse.Builder()
                    .writeHeader(true)
                    .asyncIOWriter(w)
                    .writeHeader(false)
                    .header("Connection", "Keep-Alive")
                    .header("Transfer-Encoding", "chunked")
                    .header("Server", "Nettosphere/2.0")
                    .request(r).build();

            r.setAttribute(NettyCometSupport.CHANNEL, w);

            Action a = framework.doCometSupport(r, response);

            final AsynchronousProcessor.AsynchronousProcessorHook hook = (AsynchronousProcessor.AsynchronousProcessorHook) r.getAttribute(FrameworkConfig.ASYNCHRONOUS_HOOK);

            String transport = (String) r.getAttribute(FrameworkConfig.TRANSPORT_IN_USE);
            if (transport == null) {
                transport = r.getHeader(X_ATMOSPHERE_TRANSPORT);
            }

            if (a.type() == Action.TYPE.SUSPEND) {
                if (transport != null && (transport.equalsIgnoreCase(HeaderConfig.STREAMING_TRANSPORT)
                        || transport.equalsIgnoreCase(SSE_TRANSPORT))) {
                    keptOpen = true;
                } else if (transport != null && transport.equalsIgnoreCase(HeaderConfig.LONG_POLLING_TRANSPORT)) {
                    resumeOnBroadcast = true;
                }
            }

            final Action action = (Action) r.getAttribute(NettyCometSupport.SUSPEND);
            if (action != null && action.type() == Action.TYPE.SUSPEND) {
                ctx.setAttachment(hook);

                if (action.timeout() != -1) {
                    final AtomicReference<Future<?>> f = new AtomicReference();
                    f.set(suspendTimer.scheduleAtFixedRate(new Runnable() {
                        @Override
                        public void run() {
                            if (!w.isClosed() && (System.currentTimeMillis() - w.lastTick()) > action.timeout()) {
                                hook.timedOut();
                                f.get().cancel(true);
                            }
                        }
                    }, action.timeout(), action.timeout(), TimeUnit.MILLISECONDS));
                }
            } else if (action != null && action.type() == Action.TYPE.RESUME) {
                resumeOnBroadcast = false;
            }
        } catch (AtmosphereMappingException ex) {
            if (method.equalsIgnoreCase("GET")) {
                logger.trace("Unable to map the request {}, trying static file", messageEvent.getMessage());
                try {
                    skipClose = true;
                    super.messageReceived(ctx, messageEvent);
                } catch (Exception e) {
                    logger.error("Unable to process request", e);
                    throw new IOException(e);
                }
            }
        } catch (Throwable e) {
            logger.error("Unable to process request", e);
            throw new IOException(e);
        } finally {
            if (w != null && !resumeOnBroadcast && !keptOpen) {
                if (!w.byteWritten()) {
                    w.writeError(null, 200, "OK");
                }
                if (!skipClose) {
                    w.close(null);
                } else {
                    httpChannels.add(ctx.getChannel());
                }
            }
        }
    }

    @Override
    protected void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, MessageEvent e) {
        logger.debug("{}", e);
        // For websocket, we can't send an error
        if (websoketChannels.contains(ctx.getChannel())) {
            ctx.getChannel().close().addListener(ChannelFutureListener.CLOSE);
        } else {
            if (e != null) {
                final HttpRequest request = (HttpRequest) e.getMessage();
                if (request.getHeader(STATIC_MAPPING) == null || request.getHeader(STATIC_MAPPING).equalsIgnoreCase("false")) {
                    super.sendError(ctx, status, e);
                }
            } else {
                super.sendError(ctx, status, e);
            }
        }
    }

    public void destroy() {
        isShutdown.set(true);
        httpChannels.close();
        websoketChannels.write(new CloseWebSocketFrame());

        if (framework != null) framework.destroy();
        suspendTimer.shutdown();
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        super.channelClosed(ctx, e);
        Object o = ctx.getAttachment();

        if (o == null) return;

        if (WebSocket.class.isAssignableFrom(o.getClass())) {
            WebSocket webSocket = WebSocket.class.cast(o);
            if (webSocket == null) return;

            webSocketProcessor.close(webSocket, 1005);
        } else if (AsynchronousProcessor.AsynchronousProcessorHook.class.isAssignableFrom(o.getClass())) {
            AsynchronousProcessor.AsynchronousProcessorHook.class.cast(o).closed();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
            throws Exception {
        if (e.getCause() != null && e.getCause().getClass().getName().equals(ClosedChannelException.class.getName())) {
            logger.trace("Exception", e.getCause());
        } else {
            logger.debug("Exception", e.getCause());
        }
        super.exceptionCaught(ctx, e);
    }

    private Map<String, String> getHeaders(final HttpRequest request) {
        final Map<String, String> headers = new HashMap<String, String>();

        for (String name : request.getHeaderNames()) {
            // TODO: Add support for multi header
            headers.put(name, request.getHeaders(name).get(0));
        }

        return headers;
    }

    private String getBaseUri(final HttpRequest request) {
        return "http://" + request.getHeader(HttpHeaders.Names.HOST) + "/";

    }

    private void parseQueryString(Map<String, String[]> qs, String queryString) {
        if (queryString != null) {
            String[] s = queryString.split("&");
            for (String a : s) {
                String[] q = a.split("=");
                String[] z = new String[]{q.length > 1 ? q[1] : ""};
                qs.put(q[0], z);
            }
        }
    }

    private Set<javax.servlet.http.Cookie> getCookies(final HttpRequest request) {
        Set<javax.servlet.http.Cookie> result = new HashSet<javax.servlet.http.Cookie>();
        String cookieHeader = request.getHeader("Cookie");
        if (cookieHeader != null) {
            Set<Cookie> cookies = new CookieDecoder().decode(cookieHeader);
            for (Cookie cookie : cookies) {
                javax.servlet.http.Cookie c = new javax.servlet.http.Cookie(cookie.getName(), cookie.getValue());
                if (cookie.getComment() != null) {
                    c.setComment(cookie.getComment());
                }

                if (cookie.getDomain() != null) {
                    c.setDomain(cookie.getDomain());
                }

                c.setHttpOnly(cookie.isHttpOnly());
                c.setMaxAge(cookie.getMaxAge());

                if (cookie.getPath() != null) {
                    c.setPath(cookie.getPath());
                }

                c.setSecure(cookie.isSecure());
                c.setVersion(cookie.getVersion());
                result.add(c);

            }
        }
        return result;
    }

    Config config() {
        return config;
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        // Generate an error page if response status code is not OK (200).
        if (res.getStatus().getCode() != 200) {
            res.setContent(ChannelBuffers.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8));
            setContentLength(res, res.getContent().readableBytes());
        }

        // Send the response and close the connection if necessary.
        ChannelFuture f = ctx.getChannel().write(res);
        if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private String getWebSocketLocation(HttpRequest req) {
        return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + req.getUri();
    }

    private final static class NettyServletConfig implements ServletConfig {

        private final Map<String, String> initParams;
        private final ServletContext ctx;

        public NettyServletConfig(Map<String, String> initParams, ServletContext ctx) {
            this.initParams = initParams;
            this.ctx = ctx;
        }

        @Override
        public String getServletName() {
            return "Netty";
        }

        @Override
        public ServletContext getServletContext() {
            return ctx;
        }

        @Override
        public String getInitParameter(String name) {
            return initParams.get(name);
        }

        @Override
        public Enumeration getInitParameterNames() {
            return Collections.enumeration(initParams.keySet());
        }
    }


}
