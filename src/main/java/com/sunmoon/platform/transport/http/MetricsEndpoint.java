package com.sunmoon.platform.transport.http;

import com.sunmoon.platform.observability.MetricsRegistry;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

/** GET /metrics — Prometheus text-format scrape of the process-wide Micrometer registry. */
public final class MetricsEndpoint implements RestEndpoint {

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        String scrape = MetricsRegistry.get().scrape();
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(scrape.getBytes(StandardCharsets.UTF_8)));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
        return response;
    }
}
