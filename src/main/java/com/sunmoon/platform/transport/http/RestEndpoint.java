package com.sunmoon.platform.transport.http;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

@FunctionalInterface
public interface RestEndpoint {
    FullHttpResponse handle(FullHttpRequest request);
}
