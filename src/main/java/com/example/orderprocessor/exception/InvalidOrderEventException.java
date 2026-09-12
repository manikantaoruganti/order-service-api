package com.example.orderprocessor.exception;

import jakarta.validation.ConstraintViolation;
import java.util.Set;
import java.util.stream.Collectors;

public class InvalidOrderEventException extends RuntimeException {

    public InvalidOrderEventException(String message) {
        super(message);
    }

    public static InvalidOrderEventException forViolations(Set<? extends ConstraintViolation<?>> violations) {
        String errorMessage = violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        return new InvalidOrderEventException("Invalid OrderPlacedEvent data: " + errorMessage);
    }
}
