package com.pico.web;

import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
        problem.setProperty("code", "NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
        problem.setProperty("code", error.code());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("fields", error.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        field -> field.getField(),
                        field -> field.getDefaultMessage() == null ? "invalid" : field.getDefaultMessage(),
                        (left, right) -> left)));
        return problem;
    }
}
