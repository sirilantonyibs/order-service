package com.inventory.order_service.repository;

import com.inventory.order_service.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findByCustomerId(Long customerId);

    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    boolean existsByOrderNumber(String orderNumber);
}
