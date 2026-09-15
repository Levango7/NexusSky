package io.aerofleet.cloud.mission;

import java.util.Set;

/**
 * 编队创建请求 DTO（FR-14，数据约束 6.1）。
 *
 * 承载编队创建参数：成员列表、队形类型、机间距、方向、参考点经纬高、可选 Leader。
 * 由 REST 端点反序列化，委托 {@code FormationService.create()} 校验后创建编队。
 */
public final class FormationCreateRequest {

    /** 成员 sysid 集合（>= 2）。 */
    public Set<Integer> members;
    /** 队形类型。 */
    public FormationGeometry.Shape shape;
    /** 机间距（>= 2m）。 */
    public double spacing;
    /** 队形方向（0-359°）。 */
    public double heading;
    /** 参考点纬度。 */
    public double refLat;
    /** 参考点经度。 */
    public double refLon;
    /** 参考点高度（m，>= 0）。 */
    public double refAlt;
    /** 可选：指定 Leader sysid（<= 0 表示自动选举）。 */
    public int leaderSysid = 0;

    public FormationCreateRequest() {
    }

    public FormationCreateRequest(Set<Integer> members, FormationGeometry.Shape shape,
                                  double spacing, double heading,
                                  double refLat, double refLon, double refAlt,
                                  int leaderSysid) {
        this.members = members;
        this.shape = shape;
        this.spacing = spacing;
        this.heading = heading;
        this.refLat = refLat;
        this.refLon = refLon;
        this.refAlt = refAlt;
        this.leaderSysid = leaderSysid;
    }
}