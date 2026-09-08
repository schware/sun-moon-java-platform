package com.sunmoon.platform.transport.http.bo.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.device.DeviceRepository;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RequestValidation;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.validation.constraints.NotBlank;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** DELETE /bo/devices — deviceId in the body, since there's no path-variable routing (see RestRequestRouter). */
public final class DeleteDeviceEndpoint implements RestEndpoint {

    private record DeleteRequest(@NotBlank String deviceId) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeviceRepository repository;

    public DeleteDeviceEndpoint(DeviceRepository repository) {
        this.repository = repository;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        DeleteRequest body;
        try {
            body = JSON.readValue(request.content().toString(StandardCharsets.UTF_8), DeleteRequest.class);
        } catch (Exception e) {
            return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "invalid JSON body"));
        }

        var violation = RequestValidation.firstViolationMessage(body);
        if (violation.isPresent()) {
            return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", violation.get()));
        }

        repository.delete(body.deviceId());
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
    }
}
