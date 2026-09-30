package io.aerofleet.cloud.vision;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;

/**
 * CV 评测指标 API（F1，spec E3/E4）。
 * <p>
 * 三指标（识别率/误检比/处理时间）聚合查询与评测窗口重置。
 * 纳入既有 JWT 体系（/api/v1/** 默认保护）。
 */
@RestController
@RequestMapping("/api/v1/cv-eval")
@RequireRole(Role.OBSERVER)
public class CvEvalController {

    private final CvEvalService eval;

    public CvEvalController(CvEvalService eval) {
        this.eval = eval;
    }

    /**
     * 三指标聚合查询。
     *
     * @param source 检测源过滤（truth / vision-source / pixels / external，空 = 全部）
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics(
            @RequestParam(value = "source", required = false) String source) {
        return eval.metrics(source);
    }

    /** 清空评测窗口。 */
    @PostMapping("/reset")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> reset() {
        eval.reset();
        return Map.of("ok", true);
    }
}
