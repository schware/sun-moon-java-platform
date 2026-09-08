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

/** PUT /bo/devices — 저장(Save/update). 404 if the deviceId isn't registered yet. */
public final class SaveDeviceEndpoint implements RestEndpoint {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeviceRepository repository;

    public SaveDeviceEndpoint(DeviceRepository repository) {
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
            repository.save(device);
        } catch (IllegalStateException notFound) {
            return JsonResponses.of(HttpResponseStatus.NOT_FOUND, Map.of("error", notFound.getMessage()));
        }
        return JsonResponses.of(HttpResponseStatus.OK, device);
    }
}
