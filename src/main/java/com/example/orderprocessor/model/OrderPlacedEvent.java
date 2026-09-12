package com.example.orderprocessor.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.Instant;

public record OrderPlacedEvent(
        @NotBlank(message = "orderId must be present")
        String orderId,

        @NotBlank(message = "productId must be present")
        String productId,

        @Min(value = 1, message = "quantity must be positive")
        int quantity,

        @NotBlank(message = "customerId must be present")
        String customerId,

        @NotNull(message = "timestamp must be present")
        @PastOrPresent(message = "timestamp cannot be in the future")
        Instant timestamp
) {}
