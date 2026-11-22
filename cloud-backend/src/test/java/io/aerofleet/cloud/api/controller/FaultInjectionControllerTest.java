package io.aerofleet.cloud.api.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FaultInjectionController（CD4 演练用受控 5xx 端点）")
class FaultInjectionControllerTest {

    @Test
    @DisplayName("internalError 抛异常，由全局 handler 转成 500")
    void internalErrorThrows() {
        assertThatThrownBy(new FaultInjectionController()::internalError)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected 5xx");
    }
}
