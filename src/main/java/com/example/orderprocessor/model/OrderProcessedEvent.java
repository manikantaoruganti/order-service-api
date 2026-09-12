package com.example.orderprocessor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.Instant;

public record OrderProcessedEvent(
        @NotBlank(message = "orderId must be present")
        String orderId,

        @NotNull(message = "status must be present")
        OrderStatus status,

        @NotNull(message = "processedAt timestamp must be present")
        @PastOrPresent(message = "processedAt timestamp cannot be in the future")
        Instant processedAt
) {}
