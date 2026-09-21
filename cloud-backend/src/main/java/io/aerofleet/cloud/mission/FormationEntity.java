package io.aerofleet.cloud.mission;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * 编队持久化实体（JPA）。
 *
 * 仅保存编队核心配置数据和状态，不保存运行时数据（members、targetPositions、
 * version、lastLightCommand）。运行时并发逻辑仍由 {@link Formation} 承载，
 * 本实体用于重启后恢复编队配置。
 */
@Entity
@Table(name = "formation")
public class FormationEntity {

    @Id
    private int formationId;

    @Enumerated(EnumType.STRING)
    private FormationGeometry.Shape shape;

    private double spacing;
    private double heading;
    private double refLat;
    private double refLon;
    private double refAlt;
    private int leaderSysid;

    @ElementCollection
    @CollectionTable(name = "formation_members", joinColumns = @JoinColumn(name = "formation_id"))
    @Column(name = "member_sysid")
    private List<Integer> members = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private Formation.FormationState state;

    /** JPA 无参构造器（必需）。 */
    public FormationEntity() {
    }

    /** 全参构造器。 */
    public FormationEntity(int formationId, FormationGeometry.Shape shape,
                           double spacing, double heading,
                           double refLat, double refLon, double refAlt,
                           int leaderSysid, Formation.FormationState state,
                           List<Integer> members) {
        this.formationId = formationId;
        this.shape = shape;
        this.spacing = spacing;
        this.heading = heading;
        this.refLat = refLat;
        this.refLon = refLon;
        this.refAlt = refAlt;
        this.leaderSysid = leaderSysid;
        this.state = state;
        this.members = new ArrayList<>(members);
    }

    public int getFormationId() {
        return formationId;
    }

    public void setFormationId(int formationId) {
        this.formationId = formationId;
    }

    public FormationGeometry.Shape getShape() {
        return shape;
    }

    public void setShape(FormationGeometry.Shape shape) {
        this.shape = shape;
    }

    public double getSpacing() {
        return spacing;
    }

    public void setSpacing(double spacing) {
        this.spacing = spacing;
    }

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public double getRefLat() {
        return refLat;
    }

    public void setRefLat(double refLat) {
        this.refLat = refLat;
    }

    public double getRefLon() {
        return refLon;
    }

    public void setRefLon(double refLon) {
        this.refLon = refLon;
    }

    public double getRefAlt() {
        return refAlt;
    }

    public void setRefAlt(double refAlt) {
        this.refAlt = refAlt;
    }

    public int getLeaderSysid() {
        return leaderSysid;
    }

    public void setLeaderSysid(int leaderSysid) {
        this.leaderSysid = leaderSysid;
    }

    public List<Integer> getMembers() { return members; }
    public void setMembers(List<Integer> members) { this.members = new ArrayList<>(members); }

    public Formation.FormationState getState() {
        return state;
    }

    public void setState(Formation.FormationState state) {
        this.state = state;
    }
}