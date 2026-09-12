package com.example.orderprocessor.service;

import com.example.orderprocessor.exception.InvalidOrderEventException;
import com.example.orderprocessor.exception.OrderAlreadyProcessedException;
import com.example.orderprocessor.model.Order;
import com.example.orderprocessor.model.OrderPlacedEvent;
import com.example.orderprocessor.model.OrderProcessedEvent;
import com.example.orderprocessor.model.OrderStatus;
import com.example.orderprocessor.repository.OrderRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderProcessingServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private Clock clock; // Mock Clock for deterministic timestamps

    private Validator validator;

    @InjectMocks
    private OrderProcessingService orderProcessingService;

    private final Instant fixedInstant = Instant.parse("2023-10-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        // Inject the mock validator and clock into the service
        orderProcessingService = new OrderProcessingService(orderRepository, orderEventPublisher, validator, clock);

        // Configure mock clock to return a fixed instant
        when(clock.instant()).thenReturn(fixedInstant);
    }

    @Test
    @DisplayName("Should successfully process a new OrderPlacedEvent")
    void shouldProcessNewOrderPlacedEventSuccessfully() {
        // Given
        OrderPlacedEvent event = new OrderPlacedEvent("order123", "prodA", 2, "custX", fixedInstant);
        when(orderRepository.findById(event.orderId())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order savedOrder = invocation.getArgument(0);
            if (savedOrder.getCreatedAt() == null) savedOrder.setCreatedAt(fixedInstant);
            savedOrder.setUpdatedAt(fixedInstant);
            return savedOrder;
        });

        // When
        OrderProcessedEvent result = orderProcessingService.processOrderPlacedEvent(event);

        // Then
        assertNotNull(result);
        assertEquals(event.orderId(), result.orderId());
        assertEquals(OrderStatus.PROCESSED, result.status());
        assertEquals(fixedInstant, result.processedAt());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture()); // One for PROCESSING, one for PROCESSED

        Order processingOrder = orderCaptor.getAllValues().get(0);
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertEquals(event.orderId(), processingOrder.getId());
        assertEquals(event.productId(), processingOrder.getProductId());
        assertEquals(event.customerId(), processingOrder.getCustomerId());
        assertEquals(event.quantity(), processingOrder.getQuantity());

        Order processedOrder = orderCaptor.getAllValues().get(1);
        assertEquals(OrderStatus.PROCESSED, processedOrder.getStatus());

        verify(orderEventPublisher, times(1)).publishOrderProcessedEvent(any(OrderProcessedEvent.class));
    }

    @Test
    @DisplayName("Should throw InvalidOrderEventException for invalid OrderPlacedEvent (missing orderId)")
    void shouldThrowInvalidOrderEventExceptionForMissingOrderId() {
        // Given
        OrderPlacedEvent event = new OrderPlacedEvent(null, "prodA", 2, "custX", fixedInstant);

        // When / Then
        assertThrows(InvalidOrderEventException.class, () -> orderProcessingService.processOrderPlacedEvent(event));
        verify(orderRepository, never()).findById(anyString());
        verify(orderRepository, never()).save(any(Order.class));
        verify(orderEventPublisher, never()).publishOrderProcessedEvent(any(OrderProcessedEvent.class));
    }

    @Test
    @DisplayName("Should throw InvalidOrderEventException for invalid OrderPlacedEvent (negative quantity)")
    void shouldThrowInvalidOrderEventExceptionForNegativeQuantity() {
        // Given
        OrderPlacedEvent event = new OrderPlacedEvent("order123", "prodA", 0, "custX", fixedInstant);

        // When / Then
        assertThrows(InvalidOrderEventException.class, () -> orderProcessingService.processOrderPlacedEvent(event));
        verify(orderRepository, never()).findById(anyString());
        verify(orderRepository, never()).save(any(Order.class));
        verify(orderEventPublisher, never()).publishOrderProcessedEvent(any(OrderProcessedEvent.class));
    }

    @Test
    @DisplayName("Should handle duplicate OrderPlacedEvent for an already PROCESSED order idempotently")
    void shouldHandleDuplicateProcessedOrderIdempotently() {
        // Given
        OrderPlacedEvent event = new OrderPlacedEvent("order123", "prodA", 2, "custX", fixedInstant);
        Order existingOrder = new Order("order123", "prodA", "custX", 2, OrderStatus.PROCESSED, fixedInstant, fixedInstant);
        when(orderRepository.findById(event.orderId())).thenReturn(Optional.of(existingOrder));

        // When / Then
        assertThrows(OrderAlreadyProcessedException.class, () -> orderProcessingService.processOrderPlacedEvent(event));

        verify(orderRepository, times(1)).findById(event.orderId());
        verify(orderRepository, never()).save(any(Order.class)); // No save should happen
        verify(orderEventPublisher, never()).publishOrderProcessedEvent(any(OrderProcessedEvent.class)); // No new event published
    }

    @Test
    @DisplayName("Should handle duplicate OrderPlacedEvent for an already PROCESSING order idempotently")
    void shouldHandleDuplicateProcessingOrderIdempotently() {
        // Given
        OrderPlacedEvent event = new OrderPlacedEvent("order123", "prodA", 2, "custX", fixedInstant);
        Order existingOrder = new Order("order123", "prodA", "custX", 2, OrderStatus.PROCESSING, fixedInstant, fixedInstant);
        when(orderRepository.findById(event.orderId())).thenReturn(Optional.of(existingOrder));

        // When / Then
        assertThrows(OrderAlreadyProcessedException.class, () -> orderProcessingService.processOrderPlacedEvent(event));

        verify(orderRepository, times(1)).findById(event.orderId());
        verify(orderRepository, never()).save(any(Order.class)); // No save should happen
        verify(orderEventPublisher, never()).publishOrderProcessedEvent(any(OrderProcessedEvent.class)); // No new event published
    }

    @Test
    @DisplayName("Should update an existing PENDING order to PROCESSED")
    void shouldUpdateExistingPendingOrder() {
        // Given
        OrderPlacedEvent event = new OrderPlacedEvent("order123", "prodA", 2, "custX", fixedInstant);
        Order existingOrder = new Order("order123", "prodA", "custX", 1, OrderStatus.PENDING, fixedInstant.minusSeconds(3600), fixedInstant.minusSeconds(3600));
        when(orderRepository.findById(event.orderId())).thenReturn(Optional.of(existingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order savedOrder = invocation.getArgument(0);
            savedOrder.setUpdatedAt(fixedInstant);
            return savedOrder;
        });

        // When
        OrderProcessedEvent result = orderProcessingService.processOrderPlacedEvent(event);

        // Then
        assertNotNull(result);
        assertEquals(event.orderId(), result.orderId());
        assertEquals(OrderStatus.PROCESSED, result.status());
        assertEquals(fixedInstant, result.processedAt());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());

        Order processingOrder = orderCaptor.getAllValues().get(0);
        assertEquals(OrderStatus.PROCESSING, processingOrder.getStatus());
        assertEquals(event.productId(), processingOrder.getProductId()); // Ensure fields are updated
        assertEquals(event.quantity(), processingOrder.getQuantity());

        Order processedOrder = orderCaptor.getAllValues().get(1);
        assertEquals(OrderStatus.PROCESSED, processedOrder.getStatus());

        verify(orderEventPublisher, times(1)).publishOrderProcessedEvent(any(OrderProcessedEvent.class));
    }

    @Test
    @DisplayName("Should throw RuntimeException if OrderEventPublisher fails")
    void shouldThrowRuntimeExceptionIfPublisherFails() {
        // Given
        OrderPlacedEvent event = new OrderPlacedEvent("order123", "prodA", 2, "custX", fixedInstant);
        when(orderRepository.findById(event.orderId())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order savedOrder = invocation.getArgument(0);
            if (savedOrder.getCreatedAt() == null) savedOrder.setCreatedAt(fixedInstant);
            savedOrder.setUpdatedAt(fixedInstant);
            return savedOrder;
        });
        doThrow(new RuntimeException("Publisher error")).when(orderEventPublisher).publishOrderProcessedEvent(any(OrderProcessedEvent.class));

        // When / Then
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> orderProcessingService.processOrderPlacedEvent(event));
        assertTrue(thrown.getMessage().contains("Failed to publish OrderProcessedEvent"));

        verify(orderRepository, times(2)).save(any(Order.class)); // DB updates should still happen
        verify(orderEventPublisher, times(1)).publishOrderProcessedEvent(any(OrderProcessedEvent.class));
    }
}
