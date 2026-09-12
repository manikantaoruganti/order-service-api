package com.example.orderprocessor.messaging;

import com.example.orderprocessor.exception.InvalidOrderEventException;
import com.example.orderprocessor.exception.OrderAlreadyProcessedException;
import com.example.orderprocessor.model.OrderPlacedEvent;
import com.example.orderprocessor.service.OrderProcessingService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private OrderProcessingService orderProcessingService;

    @Mock
    private Channel channel;

    @InjectMocks
    private OrderEventConsumer orderEventConsumer;

    private OrderPlacedEvent validEvent;
    private long deliveryTag = 1L;

    @BeforeEach
    void setUp() {
        validEvent = new OrderPlacedEvent("order123", "prodA", 2, "custX", Instant.now());
    }

    @Test
    @DisplayName("Should ACK message on successful processing")
    void shouldAckOnSuccessfulProcessing() throws IOException {
        // Given
        doNothing().when(orderProcessingService).processOrderPlacedEvent(validEvent);

        // When
        orderEventConsumer.listenOrderPlacedEvent(validEvent, channel, deliveryTag);

        // Then
        verify(orderProcessingService, times(1)).processOrderPlacedEvent(validEvent);
        verify(channel, times(1)).basicAck(deliveryTag, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Should ACK message if order is already processed (idempotent success)")
    void shouldAckOnOrderAlreadyProcessed() throws IOException {
        // Given
        doThrow(new OrderAlreadyProcessedException("Order already processed")).when(orderProcessingService).processOrderPlacedEvent(validEvent);

        // When
        orderEventConsumer.listenOrderPlacedEvent(validEvent, channel, deliveryTag);

        // Then
        verify(orderProcessingService, times(1)).processOrderPlacedEvent(validEvent);
        verify(channel, times(1)).basicAck(deliveryTag, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Should NACK without requeue for InvalidOrderEventException (permanent failure)")
    void shouldNackWithoutRequeueForInvalidOrderEventException() throws IOException {
        // Given
        doThrow(new InvalidOrderEventException("Invalid data")).when(orderProcessingService).processOrderPlacedEvent(validEvent);

        // When
        orderEventConsumer.listenOrderPlacedEvent(validEvent, channel, deliveryTag);

        // Then
        verify(orderProcessingService, times(1)).processOrderPlacedEvent(validEvent);
        verify(channel, times(1)).basicNack(deliveryTag, false, false); // NACK, not multiple, do NOT requeue
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Should throw AmqpRejectAndDontRequeueException for other RuntimeException (transient failure for retry)")
    void shouldThrowAmqpRejectAndDontRequeueExceptionForTransientFailure() throws IOException {
        // Given
        doThrow(new RuntimeException("DB connection lost")).when(orderProcessingService).processOrderPlacedEvent(validEvent);

        // When / Then
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> orderEventConsumer.listenOrderPlacedEvent(validEvent, channel, deliveryTag));

        verify(orderProcessingService, times(1)).processOrderPlacedEvent(validEvent);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // The ConditionalRejectingErrorHandler will handle the NACK with requeue=true based on this exception
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Should handle IOException from basicAck gracefully")
    void shouldHandleIOExceptionFromBasicAck() throws IOException {
        // Given
        doNothing().when(orderProcessingService).processOrderPlacedEvent(validEvent);
        doThrow(new IOException("Channel closed")).when(channel).basicAck(deliveryTag, false);

        // When / Then
        // The IOException from basicAck will propagate, but the service logic itself is considered successful.
        assertThrows(IOException.class, () -> orderEventConsumer.listenOrderPlacedEvent(validEvent, channel, deliveryTag));

        verify(orderProcessingService, times(1)).processOrderPlacedEvent(validEvent);
        verify(channel, times(1)).basicAck(deliveryTag, false);
    }
}
