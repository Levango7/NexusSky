package io.aerofleet.cloud.mission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编队数据模型（FR-14/FR-16，数据约束 6.1/6.2/6.4，DFX 4.2 并发安全）。
 *
 * 持有编队标识、成员表、队形参数、参考点、目标位置分配、Leader、版本号、状态机。
 * 并发安全字段标注：
 *   - {@code state} volatile：REST 线程写、Keeper 写、Pusher 读
 *   - {@code lastLightCommand} volatile：REST 线程写、Pusher 读
 *   - {@code members} ConcurrentHashMap.keySet：REST 线程写、Keeper/Pusher 读
 *   - {@code targetPositions} ConcurrentHashMap：REST 线程写、Keeper 读
 *   - {@code version} AtomicInteger：REST/Keeper 写、Pusher 读（版本号单调递增）
 *
 * 生命周期：create() → FORMING → STABLE → TRANSITIONING → STABLE → DISSOLVED
 */
public class Formation {

    /** 编队状态机（FR-16）。 */
    public enum FormationState {
        FORMING,        // 形成中：成员未全部到位
        STABLE,         // 稳定：所有在线成员在目标位置容差内
        TRANSITIONING,  // 变换中：队形变换进行中
        DISSOLVED       // 解散：编队终止
    }

    /** 经纬高坐标（不可变值对象）。 */
    public record GeoPos(double lat, double lon, double alt) {}

    public final int formationId;

    /** 成员 sysid 集合（ConcurrentHashMap.keySet，线程安全）。 */
    public final Set<Integer> members = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public volatile FormationGeometry.Shape shape;
    public final double spacing;
    public final double heading;
    public final double refLat;
    public final double refLon;
    public final double refAlt;

    /** 各机目标位置（sysid → GeoPos，ConcurrentHashMap 线程安全）。 */
    public final java.util.concurrent.ConcurrentHashMap<Integer, GeoPos> targetPositions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public final int leaderSysid;

    /** 状态机当前状态（volatile 保证可见性）。 */
    public volatile FormationState state = FormationState.FORMING;

    /** 最近一次灯光命令（volatile 引用，不可变对象）。 */
    public volatile LedControlCommand lastLightCommand = null;

    /** 版本号（单调递增，每次状态变更时 incrementAndGet）。 */
    public final AtomicInteger version = new AtomicInteger(0);

    public Formation(int formationId, Set<Integer> members,
                     FormationGeometry.Shape shape, double spacing, double heading,
                     double refLat, double refLon, double refAlt,
                     java.util.Map<Integer, GeoPos> targetPositions, int leaderSysid) {
        this.formationId = formationId;
        this.members.addAll(members);
        this.shape = shape;
        this.spacing = spacing;
        this.heading = heading;
        this.refLat = refLat;
        this.refLon = refLon;
        this.refAlt = refAlt;
        this.targetPositions.putAll(targetPositions);
        this.leaderSysid = leaderSysid;
    }

    /**
     * 返回 sysid 升序列表（供分配/扇出/推送一致排序，FR-03）。
     * 快照语义：返回新列表，调用方修改不影响内部状态。
     */
    public List<Integer> sortedMembers() {
        List<Integer> sorted = new ArrayList<>(members);
        Collections.sort(sorted);
        return sorted;
    }
}