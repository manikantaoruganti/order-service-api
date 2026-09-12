package com.example.orderprocessor.service;

import com.example.orderprocessor.exception.OrderAlreadyProcessedException;
import com.example.orderprocessor.model.Order;
import com.example.orderprocessor.model.OrderPlacedEvent;
import com.example.orderprocessor.model.OrderProcessedEvent;
import com.example.orderprocessor.model.OrderStatus;
import com.example.orderprocessor.repository.OrderRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static com.example.orderprocessor.exception.InvalidOrderEventException.forViolations;

@Service
public class OrderProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final Validator validator;
    private final Clock clock;

    public OrderProcessingService(OrderRepository orderRepository,
                                  OrderEventPublisher orderEventPublisher,
                                  Validator validator,
                                  Clock clock) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public OrderProcessedEvent processOrderPlacedEvent(OrderPlacedEvent event) {
        MDC.put("orderId", event.orderId());
        log.info("Processing OrderPlacedEvent for orderId: {}", event.orderId());

        // 1. Validate event data
        validateEvent(event);

        // 2. Idempotency Check & State Transition
        Optional<Order> existingOrderOptional = orderRepository.findById(event.orderId());

        Order order;
        if (existingOrderOptional.isPresent()) {
            order = existingOrderOptional.get();
            log.debug("Existing order found for orderId: {}. Current status: {}", event.orderId(), order.getStatus());

            if (order.getStatus() == OrderStatus.PROCESSED) {
                log.warn("Duplicate OrderPlacedEvent for already PROCESSED orderId: {}. Skipping re-processing.", event.orderId());
                // For idempotency, if already processed, we acknowledge the message without re-processing.
                // The consumer will ACK this message.
                throw new OrderAlreadyProcessedException("Order " + event.orderId() + " already processed.");
            } else if (order.getStatus() == OrderStatus.PROCESSING) {
                log.warn("Duplicate OrderPlacedEvent for orderId: {} which is currently PROCESSING. This might indicate a race condition or retry. Skipping re-processing.", event.orderId());
                // In a real-world scenario, you might want to wait, or have a more sophisticated
                // mechanism to handle concurrent processing of the same order.
                // For this assignment, we'll treat it as a duplicate and acknowledge.
                throw new OrderAlreadyProcessedException("Order " + event.orderId() + " is currently processing.");
            } else { // PENDING or FAILED (if we allow retries for FAILED)
                log.info("Updating existing order {} from status {} to PROCESSING.", event.orderId(), order.getStatus());
                order.setProductId(event.productId()); // Update fields in case of data correction on retry
                order.setCustomerId(event.customerId());
                order.setQuantity(event.quantity());
                order.setStatus(OrderStatus.PROCESSING);
            }
        } else {
            log.info("Creating new order for orderId: {}", event.orderId());
            order = new Order(
                    event.orderId(),
                    event.productId(),
                    event.customerId(),
                    event.quantity(),
                    OrderStatus.PROCESSING // Immediately set to PROCESSING
            );
        }

        // 3. Save/Update order in database
        order = orderRepository.save(order);
        log.info("Order {} status updated to {}.", order.getId(), order.getStatus());

        // Simulate business processing time
        // try {
        //     Thread.sleep(100);
        // } catch (InterruptedException e) {
        //     Thread.currentThread().interrupt();
        //     log.error("Order processing interrupted for orderId: {}", event.orderId(), e);
        //     throw new RuntimeException("Order processing interrupted", e);
        // }

        // 4. Final state transition to PROCESSED
        order.setStatus(OrderStatus.PROCESSED);
        order = orderRepository.save(order);
        log.info("Order {} status updated to {}.", order.getId(), order.getStatus());

        // 5. Publish OrderProcessedEvent
        OrderProcessedEvent processedEvent = new OrderProcessedEvent(
                order.getId(),
                OrderStatus.PROCESSED,
                clock.instant()
        );
        orderEventPublisher.publishOrderProcessedEvent(processedEvent);
        log.info("OrderProcessedEvent published for orderId: {}", order.getId());
        MDC.remove("orderId");
        return processedEvent;
    }

    private void validateEvent(OrderPlacedEvent event) {
        Set<ConstraintViolation<OrderPlacedEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            log.warn("Validation failed for OrderPlacedEvent for orderId: {}. Violations: {}", event.orderId(), violations);
            throw forViolations(violations);
        }
        log.debug("OrderPlacedEvent for orderId: {} passed validation.", event.orderId());
    }
}
