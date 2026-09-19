package io.aerofleet.cloud.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>
 * 内部异常（500）在非 dev 模式下只返回通用错误消息，不泄露内部细节，
 * 避免向客户端暴露堆栈/类名等敏感信息。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** dev 模式下返回详细内部错误信息便于调试；生产模式下只返回通用消息。 */
    @Value("${aerofleet.security.dev-mode:false}")
    private boolean devMode;

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
        // 记录完整异常到服务端日志（含堆栈），便于排查
        log.error("Unhandled internal error", e);
        // dev 模式返回详细消息便于调试；生产模式只返回通用消息，不泄露内部细节
        String message = devMode
                ? "internal error: " + rootMessage(e)
                : "internal server error";
        return body(HttpStatus.INTERNAL_SERVER_ERROR, message);
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
