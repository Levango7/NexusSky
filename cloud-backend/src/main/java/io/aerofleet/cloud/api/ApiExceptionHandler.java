package io.aerofleet.cloud.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Uniform error body {"error": "..."} for all REST failures.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** Bad mission bodies (unsupported cmd, missing fields). */
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Object> badRequest(BadRequestException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Object> malformed(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "malformed request: " + rootMessage(e));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> noRoute(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "no such endpoint");
    }

    /** @RequestBody @Valid 校验失败（字段约束）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> validationFailed(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "validation failed: " + detail);
    }

    /** 方法级 @Validated 校验失败（ConstraintViolationException）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> constraintViolated(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "validation failed: " + detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> internal(Exception e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                "internal error: " + rootMessage(e));
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + " " + fe.getDefaultMessage();
    }

    private ResponseEntity<Object> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(java.util.Map.of("error", message));
    }

    private static String rootMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
