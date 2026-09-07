package com.sunmoon.platform.transport.http;

import com.sunmoon.platform.domain.order.CreateOrderRequest;
import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.domain.order.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody CreateOrderRequest request) {
        Order created = orderService.create(request.customerId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
