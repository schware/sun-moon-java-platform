# REST API 문서화 (springdoc-openapi / Swagger UI)

이 프로젝트는 [springdoc-openapi](https://springdoc.org/)로 REST API 문서를 **코드에서 자동 생성**합니다. `build.gradle.kts`에 의존성 하나(`springdoc-openapi-starter-webmvc-ui:2.6.0`)만 추가돼 있고, 컨트롤러에 붙인 애너테이션에서 문서가 만들어집니다. 버전을 2.6.0으로 고정한 이유는 아래 "버전 고정 이유" 참고.

## 확인 경로

배포 환경(Jetty, context path `/sun-moon-java-platform`) 기준:

- Swagger UI (브라우저에서 바로 테스트): `/sun-moon-java-platform/swagger-ui/index.html`
- OpenAPI 원본 JSON: `/sun-moon-java-platform/v3/api-docs`

로컬 `./gradlew bootRun`으로 띄우면 context path 없이 `/swagger-ui/index.html`, `/v3/api-docs`입니다.

## 이미 자동으로 반영되는 것

Jakarta Bean Validation 애너테이션은 **추가 작업 없이** 문서 스키마에 반영됩니다:

```java
public record CreateOrderRequest(
        @NotBlank String customerId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
```

→ Swagger UI에서 `customerId`는 필수(required), `amount`는 0보다 커야 한다는 제약까지 자동으로 보입니다.

## 새 endpoint를 추가할 때 — 최소 체크리스트

컨트롤러 클래스에 `@Tag`, 각 메서드에 `@Operation` + `@ApiResponse`를 붙이세요. 예시(`OrderController` 실제 코드):

```java
@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Order creation")
public class OrderController {

    @PostMapping
    @Operation(
            summary = "Create an order",
            description = "Persists an order and publishes an order-created event "
                    + "(via Resilience4j circuit breaker + retry around the publish call).")
    @ApiResponse(responseCode = "201", description = "Order created")
    @ApiResponse(responseCode = "400", description = "Validation failed (blank customerId, non-positive amount)")
    public ResponseEntity<Order> create(@Valid @RequestBody CreateOrderRequest request) {
        ...
    }
}
```

- **`@Tag`** (클래스 레벨) — Swagger UI에서 endpoint들을 묶는 그룹 이름. 도메인 하나당 하나씩.
- **`@Operation`** (메서드 레벨) — `summary`는 목록에서 보이는 한 줄, `description`은 펼쳤을 때 나오는 상세 설명.
- **`@ApiResponse`** (메서드 레벨, 여러 개 가능) — 실제로 나는 응답 상태 코드를 다 적어주세요. 안 적으면 Swagger UI가 200만 있는 것처럼 보여서 오해를 줍니다.

## 필드에 설명/예시를 더 넣고 싶으면 `@Schema`

지금은 안 쓰고 있지만, 필드 하나하나에 설명을 붙이고 싶으면:

```java
public record CreateOrderRequest(
        @NotBlank
        @Schema(description = "고객 ID", example = "cust-1")
        String customerId,

        @NotNull @DecimalMin(value = "0.0", inclusive = false)
        @Schema(description = "주문 금액 (0보다 커야 함)", example = "42.50")
        BigDecimal amount) {
}
```

## 도메인이 늘어나면 — `@GroupedOpenApi`로 분리

컨트롤러가 많아지면 (예: `Orders`, `Admin`, `Internal`) 아래처럼 그룹을 나눠서 Swagger UI 상단 드롭다운으로 전환할 수 있습니다. 지금 규모에선 불필요하지만, 참고용으로 남겨둡니다:

```java
@Bean
public GroupedOpenApi ordersApi() {
    return GroupedOpenApi.builder()
            .group("orders")
            .pathsToMatch("/orders/**")
            .build();
}
```

## 버전 고정 이유

`springdoc-openapi-starter-webmvc-ui`는 반드시 **2.6.0**을 써야 합니다 (`build.gradle.kts`에 이유 주석 있음). 최신 2.9.x는 Spring Boot 3.5.x 대상이라 이 프로젝트의 Spring Boot 3.3.4(Spring Framework 6.1.13)와 안 맞아서 배포가 깨집니다. springdoc을 업그레이드하려면, 그때 프로젝트의 Spring Boot 버전도 같이 올릴 계획인지부터 확인하세요.
