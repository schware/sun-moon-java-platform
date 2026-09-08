package com.sunmoon.platform.transport.http.bo.device;

import com.sunmoon.platform.domain.device.Device;
import com.sunmoon.platform.domain.device.DeviceRepository;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;

/** GET /bo/devices — all devices, or one type via ?type=X. Master data only; connection state isn't BO's concern (docs/adr/0004). */
public final class ListDeviceEndpoint implements RestEndpoint {

    private final DeviceRepository repository;

    public ListDeviceEndpoint(DeviceRepository repository) {
        this.repository = repository;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        List<String> typeParam = new QueryStringDecoder(request.uri()).parameters().get("type");
        List<Device> devices = (typeParam == null || typeParam.isEmpty())
                ? repository.findAll()
                : repository.findByType(typeParam.get(0));
        return JsonResponses.of(HttpResponseStatus.OK, devices);
    }
}
