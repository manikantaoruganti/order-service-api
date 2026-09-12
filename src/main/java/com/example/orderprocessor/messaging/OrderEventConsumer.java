package com.example.orderprocessor.messaging;

import com.example.orderprocessor.exception.InvalidOrderEventException;
import com.example.orderprocessor.exception.OrderAlreadyProcessedException;
import com.example.orderprocessor.model.OrderPlacedEvent;
import com.example.orderprocessor.service.OrderProcessingService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final OrderProcessingService orderProcessingService;

    public OrderEventConsumer(OrderProcessingService orderProcessingService) {
        this.orderProcessingService = orderProcessingService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.order-placed}", containerFactory = "rabbitListenerContainerFactory")
    public void listenOrderPlacedEvent(OrderPlacedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        MDC.put("orderId", event.orderId());
        log.info("Received OrderPlacedEvent for orderId: {} (Delivery Tag: {})", event.orderId(), deliveryTag);

        try {
            orderProcessingService.processOrderPlacedEvent(event);
            channel.basicAck(deliveryTag, false); // Acknowledge message
            log.info("OrderPlacedEvent for orderId: {} successfully processed and ACKed.", event.orderId());
        } catch (OrderAlreadyProcessedException e) {
            // This is an idempotent success case where the order was already processed.
            // We ACK the message to remove it from the queue.
            channel.basicAck(deliveryTag, false);
            log.info("OrderPlacedEvent for orderId: {} was already processed or is currently processing. ACKed message. Reason: {}", event.orderId(), e.getMessage());
        } catch (InvalidOrderEventException e) {
            // Permanent failure: malformed event, invalid data. Do NOT requeue.
            channel.basicNack(deliveryTag, false, false); // NACK, not multiple, do NOT requeue
            log.error("Permanent failure processing OrderPlacedEvent for orderId: {}. Routing to DLQ. Reason: {}", event.orderId(), e.getMessage(), e);
        } catch (Exception e) {
            // Transient failure: database connection issues, external service timeouts, etc.
            // Requeue for retry. Spring AMQP's default error handler will handle retries
            // and eventually route to DLQ if max retries are exhausted.
            // We throw AmqpRejectAndDontRequeueException if we want to explicitly send to DLQ
            // without retries, but for general exceptions, we let Spring AMQP's retry mechanism
            // (configured via SimpleRabbitListenerContainerFactory's ErrorHandler) handle it.
            // By throwing a generic exception, the ConditionalRejectingErrorHandler will
            // typically NACK with requeue=true, triggering retries.
            log.error("Transient failure processing OrderPlacedEvent for orderId: {}. Requeuing for retry. Reason: {}", event.orderId(), e.getMessage(), e);
            // Throwing an exception here will cause the ConditionalRejectingErrorHandler to NACK with requeue=true
            // if it's not a fatal exception.
            throw new AmqpRejectAndDontRequeueException("Transient error, will retry: " + e.getMessage(), e);
        } finally {
            MDC.remove("orderId");
        }
    }
}
