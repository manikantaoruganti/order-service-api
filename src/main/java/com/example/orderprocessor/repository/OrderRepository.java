package com.example.orderprocessor.repository;

import com.example.orderprocessor.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    // JpaRepository provides findById, save, etc.
    // The 'id' field in Order entity is mapped to orderId, which is a String.
}
