package com.sunmoon.platform.transport.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Dispatches REST requests by (method, path) — the path only, query string
 * stripped — to a registered {@link RestEndpoint}. No path-variable
 * routing yet; a target row's key goes in the request body/query instead
 * (see the Common Code endpoints).
 *
 * <p><b>Endpoints do not run on the event loop.</b> {@code handle()} is
 * dispatched to a bounded worker pool, because endpoints call repositories
 * and JDBC blocks — a query executed inline would hold an event-loop
 * thread and stall every other connection it serves (docs/adr/0010).
 * {@code ctx.writeAndFlush} is thread-safe and hands the response back to
 * the event loop itself, so no explicit hop is needed on the way out.
 */
public final class RestRequestRouter extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(RestRequestRouter.class);

    private final Map<RouteKey, RestEndpoint> routes;
    private final Executor blockingWorkExecutor;

    public RestRequestRouter(Map<RouteKey, RestEndpoint> routes, Executor blockingWorkExecutor) {
        this.routes = routes;
        this.blockingWorkExecutor = blockingWorkExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = new QueryStringDecoder(request.uri()).path();
        RestEndpoint endpoint = routes.get(new RouteKey(request.method(), path));
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        if (endpoint == null) {
            respond(ctx, notFound(), keepAlive);
            return;
        }

        // channelRead0 releases the request once it returns, so the worker needs its own reference.
        FullHttpRequest retained = request.retain();
        try {
            blockingWorkExecutor.execute(() -> {
                FullHttpResponse response;
                try {
                    response = endpoint.handle(retained);
                } catch (Exception e) {
                    log.error("Endpoint {} {} failed", retained.method(), path, e);
                    response = serverError();
                } finally {
                    retained.release();
                }
                respond(ctx, response, keepAlive);
            });
        } catch (RejectedExecutionException saturated) {
            retained.release();
            log.warn("Worker pool saturated, rejecting {} {}", request.method(), path);
            respond(ctx, serviceUnavailable(), keepAlive);
        }
    }

    private static void respond(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static FullHttpResponse notFound() {
        return empty(HttpResponseStatus.NOT_FOUND);
    }

    private static FullHttpResponse serverError() {
        return empty(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    private static FullHttpResponse serviceUnavailable() {
        return empty(HttpResponseStatus.SERVICE_UNAVAILABLE);
    }

    private static FullHttpResponse empty(HttpResponseStatus status) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
    }
}
