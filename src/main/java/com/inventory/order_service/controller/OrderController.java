package com.inventory.order_service.controller;

import com.inventory.order_service.dto.OrderRequest;
import com.inventory.order_service.dto.OrderResponse;
import com.inventory.order_service.security.JwtUtil;
import com.inventory.order_service.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Order Management", description = "APIs for managing customer orders")
@SecurityRequirement(name = "Bearer Authentication")
public class OrderController {

    private final OrderService orderService;
    private final JwtUtil jwtUtil;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Place order", description = "Place a new order (CUSTOMER only)")
    public ResponseEntity<OrderResponse> placeOrder(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.substring(7);
        Long customerId = jwtUtil.extractUserId(token);
        String username = jwtUtil.extractUsername(token);
        
        // For now, use username as email. In production, you'd fetch from User Service
        String customerEmail = username + "@example.com";
        
        OrderResponse response = orderService.placeOrder(request, customerId, customerEmail);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('ADMIN')")
    @Operation(summary = "Get order by ID", description = "Retrieve order details by ID")
    public ResponseEntity<OrderResponse> getOrderById(
            @PathVariable Long orderId,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.substring(7);
        Long customerId = jwtUtil.extractUserId(token);
        
        OrderResponse response = orderService.getOrderById(orderId, customerId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my-orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get my orders", description = "Retrieve all orders for logged-in customer")
    public ResponseEntity<List<OrderResponse>> getMyOrders(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.substring(7);
        Long customerId = jwtUtil.extractUserId(token);
        
        List<OrderResponse> orders = orderService.getCustomerOrders(customerId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all orders", description = "Retrieve all orders (ADMIN only)")
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<OrderResponse> orders = orderService.getAllOrders();
        return ResponseEntity.ok(orders);
    }

    @PutMapping("/{orderId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('ADMIN')")
    @Operation(summary = "Cancel order", description = "Cancel an existing order")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable Long orderId,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.substring(7);
        Long customerId = jwtUtil.extractUserId(token);
        
        OrderResponse response = orderService.cancelOrder(orderId, customerId);
        return ResponseEntity.ok(response);
    }
}
