package com.sunmoon.platform.transport.http;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.util.Optional;
import java.util.Set;

public final class RequestValidation {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    public static <T> Optional<String> firstViolationMessage(T request) {
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(request);
        return violations.isEmpty() ? Optional.empty() : Optional.of(violations.iterator().next().getMessage());
    }

    private RequestValidation() {
    }
}
