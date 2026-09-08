package com.sunmoon.platform.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public final class JsonResponses {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static FullHttpResponse of(HttpResponseStatus status, Object body) {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        return response;
    }

    private JsonResponses() {
    }
}
