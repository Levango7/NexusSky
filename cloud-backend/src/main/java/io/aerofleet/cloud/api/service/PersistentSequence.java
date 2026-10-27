package io.aerofleet.cloud.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单调序列发号器（V35；§5 Q4/Q5 裁决：持久化续号，2026-10-27 拍板）。
 *
 * <p>语义：{@code next(name, base)} 返回**严格递增**的号；重启后从
 * {@code max(base, 库中最后发出值)} 续起——不复用、不倒退。
 * 内存缓存 {@link AtomicLong} + 逐发写穿（两个消费方——机巢命令号与编排计划号——
 * 都是低频：每命令/每编排一次，单行 upsert 量级完全可接受）。
 *
 * <p>降级：仓库缺失（纯单测/无库环境）⇒ 返回 {@code null} 让调用方走各自内存路径；
 * 落库失败 ⇒ WARN 后继续用内存值（同一进程内仍单调，跨重启的保证降级——
 * 这是显式取舍：发号不能因为库抖动而阻塞主链）。
 */
@Component
public class PersistentSequence {

    private static final Logger log = LoggerFactory.getLogger(PersistentSequence.class);

    @Autowired(required = false)
    private SequenceCounterRepository repository;

    /** name → 当前值缓存（懒初始化：首次 next 时从库播种）。 */
    private final ConcurrentHashMap<String, AtomicLong> cache = new ConcurrentHashMap<>();

    /** 当前值是否已从库播种（false 表示缓存里的值还没和库对过）。 */
    private final ConcurrentHashMap<String, Boolean> seeded = new ConcurrentHashMap<>();

    /**
     * 发下一个号。
     *
     * @param name 序列名（持久化身份）
     * @param base 基准值（首个号为 base+1；库中已有更大值则以库为准）
     * @return 严格递增的号；仓库不可用时返回 {@code null}（调用方走内存降级）
     */
    public Long next(String name, long base) {
        if (repository == null) {
            return null;
        }
        AtomicLong counter = cache.computeIfAbsent(name, k -> new AtomicLong(base));
        seedIfNeeded(name, counter, base);
        long value = counter.incrementAndGet();
        try {
            repository.save(new SequenceCounterEntity(name, value));
        } catch (Exception e) {
            log.warn("序列 {} 落库失败（本次仍发出 {}，跨重启单调性降级）：{}",
                    name, value, e.getMessage());
        }
        return value;
    }

    /** 首次发号前与库对齐：库里更大则采用库值（重启续号的关键一步）。 */
    private void seedIfNeeded(String name, AtomicLong counter, long base) {
        if (seeded.putIfAbsent(name, Boolean.TRUE) != null) {
            return;
        }
        try {
            repository.findById(name).ifPresent(row -> {
                if (row.getValue() > counter.get()) {
                    counter.set(row.getValue());
                }
            });
        } catch (Exception e) {
            log.warn("序列 {} 播种失败（按基准 {} 起步）：{}", name, base, e.getMessage());
        }
    }

    /** 测试/诊断：当前内存值（未播种时为 null）。 */
    Long peek(String name) {
        AtomicLong counter = cache.get(name);
        return counter == null ? null : counter.get();
    }
}
