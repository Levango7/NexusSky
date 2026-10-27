package io.aerofleet.cloud.api.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 单调序列计数器实体（V35；§5 Q4/Q5 裁决：持久化续号）。
 * <p>value = **最后发出值**（"已发出"才是必须不重来的事实）。
 */
@Entity
@Table(name = "sequence_counter")
public class SequenceCounterEntity {

    @Id
    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "seq_value", nullable = false)
    private long value;

    protected SequenceCounterEntity() {
        // JPA 用
    }

    public SequenceCounterEntity(String name, long value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }
}
