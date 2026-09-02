package com.inventory.order_service.service;

import com.inventory.order_service.dto.*;
import com.inventory.order_service.entity.Order;
import com.inventory.order_service.event.OrderEvent;
import com.inventory.order_service.exception.InsufficientStockException;
import com.inventory.order_service.exception.OrderNotFoundException;
import com.inventory.order_service.exception.ProductNotFoundException;
import com.inventory.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    @Value("${product.service.url}")
    private String productServiceUrl;

    @Value("${inventory.service.url}")
    private String inventoryServiceUrl;

    /**
     * Place a new order - Complete orchestration
     */
    @Transactional
    public OrderResponse placeOrder(OrderRequest request, Long customerId, String customerEmail) {
        log.info("Placing order for customer ID: {}, product ID: {}, quantity: {}", 
                customerId, request.getProductId(), request.getQuantity());

        try {
            // Step 1: Get product details from Product Service
            ProductResponse product = getProductFromProductService(request.getProductId());
            if (product == null) {
                throw new ProductNotFoundException("Product not found with ID: " + request.getProductId());
            }

            // Step 2: Reserve stock from Inventory Service
            StockOperationRequest stockRequest = new StockOperationRequest(request.getProductId(), request.getQuantity());
            StockOperationResponse stockResponse = reserveStockFromInventory(stockRequest);
            
            if (!stockResponse.isSuccess()) {
                throw new InsufficientStockException(stockResponse.getMessage());
            }

            // Step 3: Create order
            Order order = new Order();
            order.setOrderNumber(generateOrderNumber());
            order.setCustomerId(customerId);
            order.setProductId(product.getId());
            order.setProductCode(product.getProductCode());
            order.setProductName(product.getName());
            order.setQuantity(request.getQuantity());
            order.setUnitPrice(product.getPrice());
            order.setTotalPrice(product.getPrice().multiply(BigDecimal.valueOf(request.getQuantity())));
            order.setStatus(Order.OrderStatus.CREATED);

            Order savedOrder = orderRepository.save(order);

            // Step 4: Confirm reservation in Inventory Service
            confirmReservationInInventory(stockRequest);

            // Step 5: Send Kafka event
            sendOrderCreatedEvent(savedOrder, customerEmail);

            log.info("Order placed successfully: {}", savedOrder.getOrderNumber());
            return mapToResponse(savedOrder);

        } catch (Exception e) {
            log.error("Error placing order: {}", e.getMessage());
            // Restore stock if order creation fails
            try {
                StockOperationRequest restoreRequest = new StockOperationRequest(request.getProductId(), request.getQuantity());
                restoreStockInInventory(restoreRequest);
            } catch (Exception restoreException) {
                log.error("Error restoring stock: {}", restoreException.getMessage());
            }
            throw e;
        }
    }

    /**
     * Cancel order
     */
    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long customerId) {
        log.info("Cancelling order ID: {} for customer: {}", orderId, customerId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with ID: " + orderId));

        // Verify customer owns the order
        if (!order.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("Order does not belong to customer");
        }

        if (order.getStatus() == Order.OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("Order is already cancelled");
        }

        // Restore stock in Inventory Service
        StockOperationRequest restoreRequest = new StockOperationRequest(order.getProductId(), order.getQuantity());
        restoreStockInInventory(restoreRequest);

        // Update order status
        order.setStatus(Order.OrderStatus.CANCELLED);
        Order cancelledOrder = orderRepository.save(order);

        // Send Kafka event
        sendOrderCancelledEvent(cancelledOrder);

        log.info("Order cancelled successfully: {}", order.getOrderNumber());
        return mapToResponse(cancelledOrder);
    }

    /**
     * Get order by ID
     */
    public OrderResponse getOrderById(Long orderId, Long customerId) {
        log.info("Fetching order ID: {} for customer: {}", orderId, customerId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with ID: " + orderId));

        // Verify customer owns the order
        if (!order.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("Order does not belong to customer");
        }

        return mapToResponse(order);
    }

    /**
     * Get all orders for a customer
     */
    public List<OrderResponse> getCustomerOrders(Long customerId) {
        log.info("Fetching orders for customer ID: {}", customerId);

        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all orders (Admin only)
     */
    public List<OrderResponse> getAllOrders() {
        log.info("Fetching all orders");

        return orderRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // Private helper methods

    private ProductResponse getProductFromProductService(Long productId) {
        String url = productServiceUrl + "/api/products/" + productId;
        log.info("Calling Product Service: {}", url);
        
        try {
            String token = getJwtTokenFromContext();
            if (token != null) {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.set("Authorization", "Bearer " + token);
                org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
                return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, ProductResponse.class).getBody();
            }
            return restTemplate.getForObject(url, ProductResponse.class);
        } catch (Exception e) {
            log.error("Error calling Product Service: {}", e.getMessage());
            throw new ProductNotFoundException("Product not found with ID: " + productId);
        }
    }

    private StockOperationResponse reserveStockFromInventory(StockOperationRequest request) {
        String url = inventoryServiceUrl + "/api/inventory/reserve";
        log.info("Reserving stock in Inventory Service: {}", url);
        
        try {
            String token = getJwtTokenFromContext();
            if (token != null) {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.set("Authorization", "Bearer " + token);
                org.springframework.http.HttpEntity<StockOperationRequest> entity = new org.springframework.http.HttpEntity<>(request, headers);
                return restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, entity, StockOperationResponse.class).getBody();
            }
            return restTemplate.postForObject(url, request, StockOperationResponse.class);
        } catch (Exception e) {
            log.error("Error reserving stock: {}", e.getMessage());
            throw new InsufficientStockException("Unable to reserve stock: " + e.getMessage());
        }
    }

    private void confirmReservationInInventory(StockOperationRequest request) {
        String url = inventoryServiceUrl + "/api/inventory/confirm-reservation";
        log.info("Confirming reservation in Inventory Service: {}", url);
        
        try {
            String token = getJwtTokenFromContext();
            if (token != null) {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.set("Authorization", "Bearer " + token);
                org.springframework.http.HttpEntity<StockOperationRequest> entity = new org.springframework.http.HttpEntity<>(request, headers);
                restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, entity, StockOperationResponse.class);
            } else {
                restTemplate.postForObject(url, request, StockOperationResponse.class);
            }
        } catch (Exception e) {
            log.error("Error confirming reservation: {}", e.getMessage());
        }
    }

    private void restoreStockInInventory(StockOperationRequest request) {
        String url = inventoryServiceUrl + "/api/inventory/restore";
        log.info("Restoring stock in Inventory Service: {}", url);
        
        try {
            String token = getJwtTokenFromContext();
            if (token != null) {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.set("Authorization", "Bearer " + token);
                org.springframework.http.HttpEntity<StockOperationRequest> entity = new org.springframework.http.HttpEntity<>(request, headers);
                restTemplate.exchange(url, org.springframework.http.HttpMethod.POST, entity, StockOperationResponse.class);
            } else {
                restTemplate.postForObject(url, request, StockOperationResponse.class);
            }
        } catch (Exception e) {
            log.error("Error restoring stock: {}", e.getMessage());
        }
    }
    
    private String getJwtTokenFromContext() {
        try {
            org.springframework.web.context.request.RequestAttributes requestAttributes = 
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            
            if (requestAttributes instanceof org.springframework.web.context.request.ServletRequestAttributes) {
                jakarta.servlet.http.HttpServletRequest request = 
                    ((org.springframework.web.context.request.ServletRequestAttributes) requestAttributes).getRequest();
                
                String authHeader = request.getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    return authHeader.substring(7);
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract JWT token from context: {}", e.getMessage());
        }
        return null;
    }

    private void sendOrderCreatedEvent(Order order, String customerEmail) {
        OrderEvent event = new OrderEvent();
        event.setOrderNumber(order.getOrderNumber());
        event.setCustomerId(order.getCustomerId());
        event.setCustomerEmail(customerEmail);
        event.setProductId(order.getProductId());
        event.setProductCode(order.getProductCode());
        event.setProductName(order.getProductName());
        event.setQuantity(order.getQuantity());
        event.setUnitPrice(order.getUnitPrice());
        event.setTotalPrice(order.getTotalPrice());
        event.setStatus(order.getStatus().name());
        event.setTimestamp(LocalDateTime.now());
        event.setEventType("ORDER_CREATED");

        kafkaTemplate.send("order.created", event);
        log.info("Order created event sent to Kafka: {}", order.getOrderNumber());
    }

    private void sendOrderCancelledEvent(Order order) {
        OrderEvent event = new OrderEvent();
        event.setOrderNumber(order.getOrderNumber());
        event.setCustomerId(order.getCustomerId());
        event.setProductId(order.getProductId());
        event.setProductCode(order.getProductCode());
        event.setProductName(order.getProductName());
        event.setQuantity(order.getQuantity());
        event.setStatus(order.getStatus().name());
        event.setTimestamp(LocalDateTime.now());
        event.setEventType("ORDER_CANCELLED");

        kafkaTemplate.send("order.cancelled", event);
        log.info("Order cancelled event sent to Kafka: {}", order.getOrderNumber());
    }

    private String generateOrderNumber() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "ORD-" + timestamp;
    }

    private OrderResponse mapToResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setCustomerId(order.getCustomerId());
        response.setProductId(order.getProductId());
        response.setProductCode(order.getProductCode());
        response.setProductName(order.getProductName());
        response.setQuantity(order.getQuantity());
        response.setUnitPrice(order.getUnitPrice());
        response.setTotalPrice(order.getTotalPrice());
        response.setStatus(order.getStatus());
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
        return response;
    }
}
