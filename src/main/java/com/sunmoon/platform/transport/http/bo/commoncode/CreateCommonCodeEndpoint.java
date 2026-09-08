package com.sunmoon.platform.transport.http.bo.commoncode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.commoncode.CommonCode;
import com.sunmoon.platform.domain.commoncode.CommonCodeRepository;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RequestValidation;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** POST /bo/common-code — 신규(Create). Fails with 409 if the (groupCode, code) pair already exists. */
public final class CreateCommonCodeEndpoint implements RestEndpoint {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CommonCodeRepository repository;

    public CreateCommonCodeEndpoint(CommonCodeRepository repository) {
        this.repository = repository;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        CommonCodeRequest body;
        try {
            body = JSON.readValue(request.content().toString(StandardCharsets.UTF_8), CommonCodeRequest.class);
        } catch (Exception e) {
            return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "invalid JSON body"));
        }

        var violation = RequestValidation.firstViolationMessage(body);
        if (violation.isPresent()) {
            return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", violation.get()));
        }

        CommonCode commonCode = new CommonCode(body.groupCode(), body.code(), body.name(), body.sortOrder(), body.active());
        try {
            repository.create(commonCode);
        } catch (IllegalStateException alreadyExists) {
            return JsonResponses.of(HttpResponseStatus.CONFLICT, Map.of("error", alreadyExists.getMessage()));
        }
        return JsonResponses.of(HttpResponseStatus.CREATED, commonCode);
    }
}
