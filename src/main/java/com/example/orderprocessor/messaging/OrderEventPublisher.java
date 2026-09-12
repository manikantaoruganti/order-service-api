package com.example.orderprocessor.messaging;

import com.example.orderprocessor.model.OrderProcessedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange.order-events}")
    private String orderEventsExchange;

    @Value("${rabbitmq.routing-key.order-processed}")
    private String orderProcessedRoutingKey;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishOrderProcessedEvent(OrderProcessedEvent event) {
        MDC.put("orderId", event.orderId());
        log.info("Publishing OrderProcessedEvent for orderId: {} to exchange {} with routing key {}",
                event.orderId(), orderEventsExchange, orderProcessedRoutingKey);
        try {
            rabbitTemplate.convertAndSend(orderEventsExchange, orderProcessedRoutingKey, event);
            log.debug("OrderProcessedEvent for orderId: {} published successfully.", event.orderId());
        } catch (Exception e) {
            log.error("Failed to publish OrderProcessedEvent for orderId: {}. Reason: {}", event.orderId(), e.getMessage(), e);
            // Depending on the transaction strategy, this might require more sophisticated handling
            // (e.g., outbox pattern). For this assignment, we log and let the transaction commit.
            // If the DB transaction committed, but message publishing failed, the order is PROCESSED in DB.
            // A subsequent duplicate OrderPlacedEvent would be handled idempotently.
            throw new RuntimeException("Failed to publish OrderProcessedEvent for orderId: " + event.orderId(), e);
        } finally {
            MDC.remove("orderId");
        }
    }
}
