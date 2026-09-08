package com.sunmoon.platform.transport.http.bo.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.device.Device;
import com.sunmoon.platform.domain.device.DeviceRepository;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RequestValidation;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** POST /bo/devices — 신규(Create). 409 if the deviceId is already registered. */
public final class CreateDeviceEndpoint implements RestEndpoint {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeviceRepository repository;

    public CreateDeviceEndpoint(DeviceRepository repository) {
        this.repository = repository;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        DeviceRequest body;
        try {
            body = JSON.readValue(request.content().toString(StandardCharsets.UTF_8), DeviceRequest.class);
        } catch (Exception e) {
            return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", "invalid JSON body"));
        }

        var violation = RequestValidation.firstViolationMessage(body);
        if (violation.isPresent()) {
            return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", violation.get()));
        }

        Device device = new Device(body.deviceId(), body.name(), body.deviceType(), body.location(), body.active());
        try {
            repository.create(device);
        } catch (IllegalStateException alreadyExists) {
            return JsonResponses.of(HttpResponseStatus.CONFLICT, Map.of("error", alreadyExists.getMessage()));
        }
        return JsonResponses.of(HttpResponseStatus.CREATED, device);
    }
}
