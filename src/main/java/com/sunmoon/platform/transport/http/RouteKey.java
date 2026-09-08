package com.sunmoon.platform.transport.http;

import io.netty.handler.codec.http.HttpMethod;

/** (method, path) — the path is query-string-stripped, so `?foo=bar` doesn't fragment routing (see RestRequestRouter). */
public record RouteKey(HttpMethod method, String path) {
}
