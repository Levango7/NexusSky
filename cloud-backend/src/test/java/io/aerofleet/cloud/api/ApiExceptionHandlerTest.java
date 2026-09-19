package io.aerofleet.cloud.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiExceptionHandler 单测：统一错误响应体 {"error": "..."}。
 * <p>
 * 直接实例化（无 Spring 上下文），验证状态码与 error 字段内容。
 * devMode 是 @Value 注入的 private 字段，测试中用反射设置。
 */
@DisplayName("ApiExceptionHandler 统一错误响应")
class ApiExceptionHandlerTest {

    private ApiExceptionHandler newHandler() {
        return new ApiExceptionHandler();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<Object> resp) {
        assertThat(resp.getBody()).isInstanceOf(Map.class);
        return (Map<String, Object>) resp.getBody();
    }

    @Test
    @DisplayName("NotFoundException 返回 404 和 error 消息")
    void notFound_returns404() {
        ApiExceptionHandler handler = newHandler();
        ApiExceptionHandler.NotFoundException e =
                new ApiExceptionHandler.NotFoundException("unknown drone sysid 99");

        ResponseEntity<Object> resp = handler.notFound(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = body(resp);
        assertThat(body).containsKey("error");
        assertThat(body.get("error")).isEqualTo("unknown drone sysid 99");
    }

    @Test
    @DisplayName("BadRequestException 返回 400 和 error 消息")
    void badRequest_returns400() {
        ApiExceptionHandler handler = newHandler();
        ApiExceptionHandler.BadRequestException e =
                new ApiExceptionHandler.BadRequestException("invalid payload");

        ResponseEntity<Object> resp = handler.badRequest(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = body(resp);
        assertThat(body).containsKey("error");
        assertThat(body.get("error")).isEqualTo("invalid payload");
    }

    @Test
    @DisplayName("非 dev 模式下 generic Exception 返回 500 和通用消息")
    void internal_returns500_genericMessage() {
        ApiExceptionHandler handler = newHandler();
        // devMode 默认 false（new 出来未经过 Spring 注入）

        ResponseEntity<Object> resp = handler.internal(new RuntimeException("boom"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = body(resp);
        assertThat(body.get("error")).isEqualTo("internal server error");
    }

    @Test
    @DisplayName("NotFoundException 保留原始消息在 error 字段")
    void notFound_preservesMessage() {
        ApiExceptionHandler handler = newHandler();
        String original = "drone 42 not registered";
        ApiExceptionHandler.NotFoundException e =
                new ApiExceptionHandler.NotFoundException(original);

        ResponseEntity<Object> resp = handler.notFound(e);

        Map<String, Object> body = body(resp);
        assertThat(body.get("error")).isEqualTo(original);
    }

    @Test
    @DisplayName("BadRequestException 保留原始消息在 error 字段")
    void badRequest_preservesMessage() {
        ApiExceptionHandler handler = newHandler();
        String original = "joystick x must be in [-1000, 1000]";
        ApiExceptionHandler.BadRequestException e =
                new ApiExceptionHandler.BadRequestException(original);

        ResponseEntity<Object> resp = handler.badRequest(e);

        Map<String, Object> body = body(resp);
        assertThat(body.get("error")).isEqualTo(original);
    }

    @Test
    @DisplayName("dev 模式下 internal 返回详细错误消息")
    void internal_devMode_returnsDetail() throws Exception {
        ApiExceptionHandler handler = newHandler();
        // 用反射设置 devMode = true（模拟 @Value("${aerofleet.security.dev-mode:true}")）
        java.lang.reflect.Field f = ApiExceptionHandler.class.getDeclaredField("devMode");
        f.setAccessible(true);
        f.setBoolean(handler, true);

        String detail = "null pointer in flight plan";
        ResponseEntity<Object> resp = handler.internal(new RuntimeException(detail));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = body(resp);
        assertThat(body.get("error")).asString().contains(detail);
    }
}