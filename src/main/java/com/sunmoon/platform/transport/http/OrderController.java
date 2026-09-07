package com.sunmoon.platform.transport.http;

import com.sunmoon.platform.domain.order.CreateOrderRequest;
import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.domain.order.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Order creation")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(
            summary = "Create an order",
            description = "Persists an order and publishes an order-created event "
                    + "(via Resilience4j circuit breaker + retry around the publish call).")
    @ApiResponse(responseCode = "201", description = "Order created")
    @ApiResponse(responseCode = "400", description = "Validation failed (blank customerId, non-positive amount)")
    public ResponseEntity<Order> create(@Valid @RequestBody CreateOrderRequest request) {
        Order created = orderService.create(request.customerId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
