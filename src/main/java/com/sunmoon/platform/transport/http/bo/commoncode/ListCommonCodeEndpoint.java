package com.sunmoon.platform.transport.http.bo.commoncode;

import com.sunmoon.platform.domain.commoncode.CommonCode;
import com.sunmoon.platform.domain.commoncode.CommonCodeRepository;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;

/** GET /bo/common-code — all rows, or one group via ?group=X. */
public final class ListCommonCodeEndpoint implements RestEndpoint {

    private final CommonCodeRepository repository;

    public ListCommonCodeEndpoint(CommonCodeRepository repository) {
        this.repository = repository;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        List<String> groupParam = new QueryStringDecoder(request.uri()).parameters().get("group");
        List<CommonCode> codes = (groupParam == null || groupParam.isEmpty())
                ? repository.findAll()
                : repository.findByGroup(groupParam.get(0));
        return JsonResponses.of(HttpResponseStatus.OK, codes);
    }
}
