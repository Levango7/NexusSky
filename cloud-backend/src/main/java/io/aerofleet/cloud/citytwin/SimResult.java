package io.aerofleet.cloud.citytwin;

import java.util.List;

/**
 * 灾害模拟推演结果，包含影响范围、人口、损失评估、疏散路线和时间线。
 */
public class SimResult {

    private double affectedAreaKm2;
    private int affectedPopulation;
    private double estimatedDamage;
    private List<Route> evacuationRoutes;
    private List<SimFrame> timeline;

    public SimResult() {
    }

    public SimResult(double affectedAreaKm2, int affectedPopulation, double estimatedDamage,
                     List<Route> evacuationRoutes, List<SimFrame> timeline) {
        this.affectedAreaKm2 = affectedAreaKm2;
        this.affectedPopulation = affectedPopulation;
        this.estimatedDamage = estimatedDamage;
        this.evacuationRoutes = evacuationRoutes;
        this.timeline = timeline;
    }

    public double getAffectedAreaKm2() {
        return affectedAreaKm2;
    }

    public void setAffectedAreaKm2(double affectedAreaKm2) {
        this.affectedAreaKm2 = affectedAreaKm2;
    }

    public int getAffectedPopulation() {
        return affectedPopulation;
    }

    public void setAffectedPopulation(int affectedPopulation) {
        this.affectedPopulation = affectedPopulation;
    }

    public double getEstimatedDamage() {
        return estimatedDamage;
    }

    public void setEstimatedDamage(double estimatedDamage) {
        this.estimatedDamage = estimatedDamage;
    }

    public List<Route> getEvacuationRoutes() {
        return evacuationRoutes;
    }

    public void setEvacuationRoutes(List<Route> evacuationRoutes) {
        this.evacuationRoutes = evacuationRoutes;
    }

    public List<SimFrame> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<SimFrame> timeline) {
        this.timeline = timeline;
    }
}