package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerminalRegistry 单测（M6 移动基站载荷抽象，FR-TERM-01/04/07）。
 */
@DisplayName("TerminalRegistry (FR-TERM-01/04/07)")
class TerminalRegistryTest {

    private GroundTerminal makeTerminal(int id) {
        return GroundTerminal.fromRegister(id, TerminalType.PHONE, 100, 200, 1000L);
    }

    @Test
    @DisplayName("add 新终端成功并递增 size")
    void addNewTerminal() {
        TerminalRegistry registry = new TerminalRegistry();
        assertThat(registry.add(makeTerminal(1))).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.contains(1)).isTrue();
    }

    @Test
    @DisplayName("add 重复终端返回 false（FR-TERM-07 唯一性）")
    void addDuplicateReturnsFalse() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.add(makeTerminal(1));
        assertThat(registry.add(makeTerminal(1))).isFalse();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove 移除终端")
    void removeTerminal() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.add(makeTerminal(1));
        assertThat(registry.remove(1)).isTrue();
        assertThat(registry.contains(1)).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("updateLastSeen 更新心跳时间")
    void updateLastSeen() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.add(makeTerminal(1));
        assertThat(registry.updateLastSeen(1, 5000L)).isTrue();
        assertThat(registry.get(1).lastSeenMs).isEqualTo(5000L);
    }

    @Test
    @DisplayName("findExpired 扫描超时终端")
    void findExpired() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.add(makeTerminal(1));
        registry.add(makeTerminal(2));
        registry.updateLastSeen(1, 8000L);

        List<Integer> expired = registry.findExpired(10000L, 3000L);
        assertThat(expired).containsExactly(2);
    }

    @Test
    @DisplayName("容量超限时 add 返回 false")
    void capacityLimit() {
        TerminalRegistry registry = new TerminalRegistry(2);
        assertThat(registry.add(makeTerminal(1))).isTrue();
        assertThat(registry.add(makeTerminal(2))).isTrue();
        assertThat(registry.add(makeTerminal(3))).isFalse();
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("clear 清空所有终端")
    void clearAll() {
        TerminalRegistry registry = new TerminalRegistry();
        registry.add(makeTerminal(1));
        registry.add(makeTerminal(2));
        registry.clear();
        assertThat(registry.size()).isZero();
    }
}