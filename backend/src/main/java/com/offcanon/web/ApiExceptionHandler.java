package com.offcanon.web;

import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import com.offcanon.shared.web.UnauthorizedException;
import com.offcanon.shared.web.ForbiddenException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final int MAX_DETAIL_CHARS = 500;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?\\b(?:api[_ -]?key|authorization|bearer|password|secret|credential|private[_ -]?key|ciphertext|token)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?\\s*(?:bearer\\s+)?)([^\\s,;\\\"'}]+)");

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException error) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", error.getMessage(), Map.of());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail unauthorized(UnauthorizedException error) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", error.getMessage(), Map.of());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail forbidden(ForbiddenException error) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", error.getMessage(), Map.of());
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

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "The HTTP method is not supported for this endpoint", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail mediaTypeNotSupported(HttpMediaTypeNotSupportedException error) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "The request content type is not supported", Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail malformedRequest(Exception error) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request could not be parsed", Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidArgument(IllegalArgumentException error) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", error.getMessage(), Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail resourceNotFound(NoResourceFoundException error) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found: " + error.getResourcePath(), Map.of());
    }

    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public void responseStreamEnded() {
        // The response stream is already unusable, so no error body can be written.
    }

    @ExceptionHandler(IOException.class)
    public ProblemDetail io(IOException error, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        return unexpected(error);
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
                safeDetail(detail, status.getReasonPhrase()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        problem.setProperty("details", details);
        return problem;
    }

    /** Keep provider/command diagnostics useful without reflecting secrets or control characters. */
    private String safeDetail(String detail, String fallback) {
        if (detail == null || detail.isBlank()) return fallback;
        String normalized = detail.replaceAll("[\\r\\n\\t]+", " ").trim();
        normalized = SECRET_ASSIGNMENT.matcher(normalized).replaceAll("$1[REDACTED]");
        if (normalized.length() > MAX_DETAIL_CHARS) {
            normalized = normalized.substring(0, MAX_DETAIL_CHARS) + "...";
        }
        return normalized.isBlank() ? fallback : normalized;
    }
}
