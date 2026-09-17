package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.pos.Notice;
import com.sunmoon.platform.domain.pos.NoticeRepository;
import com.sunmoon.platform.domain.pos.NoticeService;
import com.sunmoon.platform.domain.pos.ProgramDeployService;
import com.sunmoon.platform.domain.pos.ProgramInstall;
import com.sunmoon.platform.domain.pos.ProgramPlan;
import com.sunmoon.platform.domain.pos.ProgramRelease;
import com.sunmoon.platform.transport.http.JsonResponses;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로그램 배포와 공지·긴급 메시지 (업무 정의서 §3.5).
 *
 * <p>둘이 한 클래스인 이유: 둘 다 <b>서버가 장비에 거는 일</b>이고,
 * 둘 다 "내려줬다"와 "장비가 받았다"가 따로입니다. 그 구분이 없으면
 * 어느 쪽도 운영이 안 됩니다 — 안 받은 장비를 못 찾으니까요.
 */
public final class PosSystemEndpoints {

    private final ProgramDeployService programs;
    private final NoticeService notices;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PosSystemEndpoints(ProgramDeployService programs, NoticeService notices) {
        this.programs = programs;
        this.notices = notices;
    }

    // ---- 프로그램 배포 ------------------------------------------------

    /**
     * {@code GET /pos/programs?storeId=&deviceType=&deviceNo=[&issue=true]}
     *
     * <p>{@code issue} 없이는 읽기만 합니다 — 관제 화면이 볼 때마다
     * 전달 기록이 찍히면 안 됩니다. 마스터 배포와 같은 규칙입니다.
     */
    public RestEndpoint programPlan() {
        return request -> {
            String storeId = param(request, "storeId");
            String deviceType = param(request, "deviceType");
            String deviceNo = param(request, "deviceNo");
            if (storeId == null || deviceType == null || deviceNo == null) {
                return badRequest("storeId, deviceType, deviceNo are required");
            }
            boolean issue = "true".equalsIgnoreCase(param(request, "issue"));

            List<ProgramPlan> plans = issue
                    ? programs.issue(storeId, deviceType, deviceNo)
                    : programs.plan(storeId, deviceType, deviceNo);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProgramPlan plan : plans) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("program", plan.program());
                row.put("targetVersion", plan.targetVersion());
                row.put("installedVersion", plan.installedVersion());
                row.put("upToDate", plan.upToDate());
                row.put("packageUrl", plan.packageUrl());
                row.put("checksum", plan.checksum());
                row.put("mandatory", plan.mandatory());
                row.put("status", plan.status().name());
                row.put("lastError", plan.lastError());
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "deviceType", deviceType, "deviceNo", deviceNo,
                    "issued", issue, "programs", rows));
        };
    }

    /** {@code POST /pos/programs/release} — 무엇을 깔게 할지 등록합니다. */
    public RestEndpoint publishRelease() {
        return request -> {
            JsonNode body = parse(request);
            if (body == null) {
                return badRequest("본문이 JSON이 아닙니다");
            }
            try {
                ProgramRelease release = new ProgramRelease(
                        null,
                        // 빈 문자열도 전 매장으로 읽습니다 — 화면의 빈
                        // 입력칸이 null 로 오지 않는 일이 흔합니다.
                        blankToNull(text(body, "storeId")),
                        text(body, "deviceType"), text(body, "program"), text(body, "version"),
                        text(body, "packageUrl"), text(body, "checksum"),
                        body.hasNonNull("mandatory") && body.get("mandatory").asBoolean(),
                        text(body, "note"), Instant.now());
                long id = programs.publish(release);
                return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                        "id", id, "program", release.program(), "version", release.version(),
                        "scope", release.global() ? "ALL_STORES" : release.storeId()));
            } catch (RuntimeException badShape) {
                return badRequest(badShape.getMessage());
            }
        };
    }

    /**
     * {@code POST /pos/programs/ack} — 장비가 답합니다.
     *
     * <p>{@code status} 가 넷입니다: OFFERED / DOWNLOADED / INSTALLED /
     * FAILED. 받다 실패한 장비와 깔다 실패한 장비를 갈라 보려면 중간
     * 상태를 장비가 말해 줄 수 있어야 합니다.
     */
    public RestEndpoint programAck() {
        return request -> {
            JsonNode body = parse(request);
            if (body == null) {
                return badRequest("본문이 JSON이 아닙니다");
            }
            String storeId = text(body, "storeId");
            String deviceType = text(body, "deviceType");
            String deviceNo = text(body, "deviceNo");
            String program = text(body, "program");
            String version = text(body, "version");
            if (storeId == null || deviceType == null || deviceNo == null
                    || program == null || version == null) {
                return badRequest("storeId, deviceType, deviceNo, program, version are required");
            }
            try {
                ProgramInstall.Status status = ProgramInstall.Status.of(text(body, "status"));
                programs.ack(storeId, deviceType, deviceNo, program, version, status, text(body, "message"));
                return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                        "storeId", storeId, "deviceNo", deviceNo,
                        "program", program, "status", status.name()));
            } catch (IllegalArgumentException badStatus) {
                return badRequest(badStatus.getMessage());
            }
        };
    }

    /** {@code GET /pos/programs/status?storeId=[&pending=true]} — 장비별 설치 현황. */
    public RestEndpoint programStatus() {
        return request -> {
            String storeId = param(request, "storeId");
            if (storeId == null) {
                return badRequest("storeId is required");
            }
            boolean pendingOnly = "true".equalsIgnoreCase(param(request, "pending"));
            List<ProgramInstall> installs = pendingOnly
                    ? programs.unfinished(storeId) : programs.installsOf(storeId);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProgramInstall install : installs) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("deviceType", install.deviceType());
                row.put("deviceNo", install.deviceNo());
                row.put("program", install.program());
                row.put("installedVersion", install.installedVersion());
                row.put("targetVersion", install.targetVersion());
                row.put("status", install.status().name());
                row.put("attempts", install.attempts());
                row.put("lastError", install.lastError());
                row.put("offeredAt", install.offeredAt());
                row.put("installedAt", install.installedAt());
                rows.add(row);
            }

            List<Map<String, Object>> releases = new ArrayList<>();
            for (ProgramRelease release : programs.releases(storeId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", release.id());
                row.put("scope", release.global() ? "ALL_STORES" : release.storeId());
                row.put("deviceType", release.deviceType());
                row.put("program", release.program());
                row.put("version", release.version());
                row.put("mandatory", release.mandatory());
                row.put("note", release.note());
                row.put("releasedAt", release.releasedAt());
                releases.add(row);
            }

            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "installs", rows, "releases", releases));
        };
    }

    // ---- 공지·긴급 메시지 ---------------------------------------------

    /**
     * {@code GET /pos/notices?storeId=&deviceType=&deviceNo=} — 지금 그 장비에 걸린 것.
     *
     * <p>{@code deviceNo} 를 주면 <b>그 장비가 이미 확인했는지</b>를 줄마다
     * 표시합니다. 이게 없던 동안 화면은 확인을 눌러도 다음 조회에 같은
     * 긴급 메시지를 또 받았습니다 — 서버는 확인 기록을 갖고 있었는데
     * 조회에 장비 번호가 아예 없었습니다.
     *
     * <p>확인한 것을 <b>목록에서 빼지는 않습니다</b>. 한 번 읽고 사라지는
     * 글이면 내용을 되짚을 수가 없습니다. 화면을 가로막는 것은 안 본
     * 것만이고, 목록에는 본 것도 남습니다.
     */
    public RestEndpoint activeNotices() {
        return request -> {
            String storeId = param(request, "storeId");
            if (storeId == null) {
                return badRequest("storeId is required");
            }
            String deviceType = param(request, "deviceType");
            String deviceNo = param(request, "deviceNo");
            java.util.Set<Long> seen = notices.acknowledgedBy(storeId, deviceType, deviceNo);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Notice notice : notices.activeFor(storeId, deviceType)) {
                Map<String, Object> row = view(notice);
                row.put("acknowledged", seen.contains(notice.id()));
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "storeId", storeId, "notices", rows));
        };
    }

    /** {@code GET /pos/notices/all?storeId=} — 지난 것까지, 확인한 장비 수와 함께 (BO용). */
    public RestEndpoint allNotices() {
        return request -> {
            String storeId = param(request, "storeId");
            List<Map<String, Object>> rows = new ArrayList<>();
            Instant now = notices.now();
            for (Notice notice : notices.all(storeId, 100)) {
                Map<String, Object> row = view(notice);
                row.put("active", notice.activeAt(now));
                // 긴급만 확인을 셉니다. 일반 공지는 아무도 확인하지 않고,
                // 0이라는 숫자가 화면에 붙으면 문제처럼 보입니다.
                if (notice.urgent()) {
                    List<NoticeRepository.Receipt> receipts = notices.receiptsOf(notice.id());
                    row.put("acknowledged", receipts.size());
                    row.put("acknowledgedBy", receipts.stream()
                            .map(r -> r.storeId() + "/" + r.deviceType() + "/" + r.deviceNo())
                            .toList());
                }
                rows.add(row);
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of("notices", rows));
        };
    }

    /**
     * {@code POST /pos/notices} — 공지 또는 긴급 메시지를 겁니다.
     *
     * <p>답의 {@code pushed} 가 <b>실제로 밀어 넣은 단말 수</b>입니다.
     * 0이라고 실패가 아닙니다 — 붙어 있는 단말이 없을 뿐이고, 켜지면
     * 조회로 봅니다. 그래도 숫자를 내는 이유는 긴급을 보낸 사람이 그
     * 자리에서 "지금 몇 대가 봤나"를 알아야 하기 때문입니다.
     */
    public RestEndpoint publishNotice() {
        return request -> {
            JsonNode body = parse(request);
            if (body == null) {
                return badRequest("본문이 JSON이 아닙니다");
            }
            try {
                Notice notice = new Notice(
                        null,
                        blankToNull(text(body, "storeId")),
                        blankToNull(text(body, "deviceType")),
                        text(body, "title"), text(body, "body"),
                        body.hasNonNull("urgent") && body.get("urgent").asBoolean(),
                        body.hasNonNull("startsAt") ? Instant.parse(body.get("startsAt").asText()) : null,
                        body.hasNonNull("endsAt") ? Instant.parse(body.get("endsAt").asText()) : null,
                        text(body, "createdBy"), Instant.now());
                NoticeService.Published published = notices.publish(notice);
                return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                        "id", published.id(),
                        "urgent", notice.urgent(),
                        "pushed", published.pushed()));
            } catch (RuntimeException badShape) {
                return badRequest(badShape.getMessage());
            }
        };
    }

    /** {@code POST /pos/notices/ack} — 그 장비가 봤습니다. */
    public RestEndpoint acknowledgeNotice() {
        return request -> {
            JsonNode body = parse(request);
            if (body == null) {
                return badRequest("본문이 JSON이 아닙니다");
            }
            if (!body.hasNonNull("noticeId")) {
                return badRequest("noticeId is required");
            }
            String storeId = text(body, "storeId");
            String deviceType = text(body, "deviceType");
            String deviceNo = text(body, "deviceNo");
            if (storeId == null || deviceType == null || deviceNo == null) {
                return badRequest("storeId, deviceType, deviceNo are required");
            }
            long noticeId = body.get("noticeId").asLong();
            notices.acknowledge(noticeId, storeId, deviceType, deviceNo);
            return JsonResponses.of(HttpResponseStatus.OK, Map.of(
                    "noticeId", noticeId, "deviceNo", deviceNo, "acknowledged", true));
        };
    }

    private static Map<String, Object> view(Notice notice) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", notice.id());
        row.put("scope", notice.storeId() == null ? "ALL_STORES" : notice.storeId());
        row.put("deviceType", notice.deviceType());
        row.put("title", notice.title());
        row.put("body", notice.body());
        row.put("urgent", notice.urgent());
        row.put("startsAt", notice.startsAt());
        row.put("endsAt", notice.endsAt());
        row.put("createdBy", notice.createdBy());
        return row;
    }

    private JsonNode parse(FullHttpRequest request) {
        try {
            return objectMapper.readTree(request.content().toString(StandardCharsets.UTF_8));
        } catch (Exception notJson) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String param(FullHttpRequest request, String name) {
        List<String> values = new QueryStringDecoder(request.uri()).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static FullHttpResponse badRequest(String message) {
        return JsonResponses.of(HttpResponseStatus.BAD_REQUEST, Map.of("error", String.valueOf(message)));
    }
}
