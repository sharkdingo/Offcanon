package com.pico.web;

import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException error) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", error.getMessage(), Map.of());
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException error) {
        return problem(HttpStatus.CONFLICT, error.code(), error.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException error) {
        Map<String, String> fields = error.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        field -> field.getField(),
                        field -> field.getDefaultMessage() == null ? "invalid" : field.getDefaultMessage(),
                        (left, right) -> left));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                Map.of("fields", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidArgument(IllegalArgumentException error) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", error.getMessage(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception error) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled API error traceId={}", traceId, error);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected server error occurred");
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("traceId", traceId);
        problem.setProperty("details", Map.of());
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, Map<String, ?> details) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                detail == null || detail.isBlank() ? status.getReasonPhrase() : detail);
        problem.setProperty("code", code);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        problem.setProperty("details", details);
        return problem;
    }
}
