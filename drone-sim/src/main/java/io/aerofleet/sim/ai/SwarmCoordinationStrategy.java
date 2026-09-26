package io.aerofleet.sim.ai;

import io.aerofleet.sim.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SwarmCoordinationStrategy {

    private static final Logger log = LoggerFactory.getLogger(SwarmCoordinationStrategy.class);

    private static final double CONFLICT_DISTANCE = 50.0;
    private static final double FORMATION_SPACING = 20.0;
    private static final double TASK_BID_BASE = 100.0;
    private static final double BATTERY_WEIGHT = 0.3;
    private static final double DISTANCE_WEIGHT = 0.4;
    private static final double CAPABILITY_WEIGHT = 0.3;

    public volatile int selfSysid = 0;
    public volatile List<DroneInfo> swarmMembers = Collections.emptyList();
    public volatile List<TaskInfo> availableTasks = Collections.emptyList();
    public volatile String formationType = "line";
    public volatile double formationCenterLat = Double.NaN;
    public volatile double formationCenterLon = Double.NaN;
    public volatile double formationCenterAlt = 100.0;
    public volatile double formationHeading = 0.0;
    public volatile double selfLat = Double.NaN;
    public volatile double selfLon = Double.NaN;
    public volatile double selfAlt = Double.NaN;
    public volatile double selfBattery = 100.0;
    public volatile double selfCapability = 1.0;

    public static final class DroneInfo {
        public volatile int sysid;
        public volatile double lat;
        public volatile double lon;
        public volatile double alt;
        public volatile double battery;
        public volatile double capability;
        public volatile double heading;
        public volatile double speed;
        public volatile String assignedTaskId;

        public DroneInfo(int sysid, double lat, double lon, double alt,
                         double battery, double capability, double heading, double speed) {
            this.sysid = sysid;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.battery = battery;
            this.capability = capability;
            this.heading = heading;
            this.speed = speed;
            this.assignedTaskId = null;
        }
    }

    public static final class TaskInfo {
        public volatile String taskId;
        public volatile double targetLat;
        public volatile double targetLon;
        public volatile double targetAlt;
        public volatile double priority;
        public volatile double requiredCapability;

        public TaskInfo(String taskId, double targetLat, double targetLon, double targetAlt,
                        double priority, double requiredCapability) {
            this.taskId = taskId;
            this.targetLat = targetLat;
            this.targetLon = targetLon;
            this.targetAlt = targetAlt;
            this.priority = priority;
            this.requiredCapability = requiredCapability;
        }
    }

    public static final class SwarmResult {
        public final String assignedTaskId;
        public final double bidValue;
        public final List<FormationCommand> formationCommands;
        public final String coordinationType;
        public final double confidence;
        public final Map<Integer, String> taskAssignments;

        public SwarmResult(String assignedTaskId, double bidValue,
                           List<FormationCommand> formationCommands,
                           String coordinationType, double confidence,
                           Map<Integer, String> taskAssignments) {
            this.assignedTaskId = assignedTaskId;
            this.bidValue = bidValue;
            this.formationCommands = formationCommands;
            this.coordinationType = coordinationType;
            this.confidence = confidence;
            this.taskAssignments = taskAssignments;
        }
    }

    public static final class FormationCommand {
        public final int sysid;
        public final double targetLat;
        public final double targetLon;
        public final double targetAlt;
        public final String command;

        public FormationCommand(int sysid, double targetLat, double targetLon,
                                double targetAlt, String command) {
            this.sysid = sysid;
            this.targetLat = targetLat;
            this.targetLon = targetLon;
            this.targetAlt = targetAlt;
            this.command = command;
        }
    }

    public boolean shouldTrigger() {
        if (availableTasks != null && !availableTasks.isEmpty()) return true;
        if (detectConflict()) return true;
        if (formationNeedsAdjustment()) return true;
        return false;
    }

    public SwarmResult evaluate() {
        if (!shouldTrigger()) return null;

        String assignedTask = null;
        double bidValue = 0.0;
        Map<Integer, String> taskAssignments = new HashMap<>();
        String coordType = "none";

        if (availableTasks != null && !availableTasks.isEmpty()) {
            SwarmResult taskResult = computeTaskAllocation();
            assignedTask = taskResult.assignedTaskId;
            bidValue = taskResult.bidValue;
            taskAssignments = taskResult.taskAssignments;
            coordType = "task_allocation";
        }

        if (detectConflict()) {
            coordType = "conflict_resolution";
        }

        List<FormationCommand> formationCmds = Collections.emptyList();
        if (formationNeedsAdjustment()) {
            formationCmds = computeFormationAdjustment();
            if ("none".equals(coordType)) coordType = "formation_adjustment";
        }

        double confidence = computeConfidence(coordType);

        log.debug("[SwarmCoordination] triggered: type={} assignedTask={} bid={} formationCmds={} confidence={}",
                coordType, assignedTask, String.format("%.1f", bidValue), formationCmds.size(), confidence);

        return new SwarmResult(assignedTask, bidValue, formationCmds, coordType,
                confidence, taskAssignments);
    }

    public DecisionResult evaluateAsDecisionResult() {
        SwarmResult result = evaluate();
        if (result == null) return null;
        return new DecisionResult("SWARM", result.coordinationType, 0.0, result.confidence);
    }

    private SwarmResult computeTaskAllocation() {
        List<TaskBid> bids = new ArrayList<>();

        for (TaskInfo task : availableTasks) {
            double selfBid = computeBid(task, selfBattery, selfCapability, selfLat, selfLon);
            bids.add(new TaskBid(selfSysid, task.taskId, selfBid, task.priority));

            if (swarmMembers != null) {
                for (DroneInfo drone : swarmMembers) {
                    if (drone.sysid == selfSysid) continue;
                    double droneBid = computeBid(task, drone.battery, drone.capability, drone.lat, drone.lon);
                    bids.add(new TaskBid(drone.sysid, task.taskId, droneBid, task.priority));
                }
            }
        }

        bids.sort(Comparator.comparingDouble((TaskBid b) -> b.bid).reversed());

        Map<Integer, String> assignments = new HashMap<>();
        List<Integer> assignedDrones = new ArrayList<>();
        List<String> assignedTasks = new ArrayList<>();

        for (TaskBid bid : bids) {
            if (assignedDrones.contains(bid.sysid) || assignedTasks.contains(bid.taskId)) continue;
            assignments.put(bid.sysid, bid.taskId);
            assignedDrones.add(bid.sysid);
            assignedTasks.add(bid.taskId);
        }

        String myTask = assignments.get(selfSysid);
        double myBid = 0.0;
        for (TaskBid bid : bids) {
            if (bid.sysid == selfSysid && bid.taskId.equals(myTask)) {
                myBid = bid.bid;
                break;
            }
        }

        return new SwarmResult(myTask, myBid, Collections.emptyList(),
                "task_allocation", 0.8, assignments);
    }

    private double computeBid(TaskInfo task, double battery, double capability,
                              double droneLat, double droneLon) {
        double distance = horizontalDistance(droneLat, droneLon, task.targetLat, task.targetLon);
        double distanceScore = Math.max(0, TASK_BID_BASE - distance * 0.01);
        double batteryScore = battery * 0.5;
        double capabilityScore = capability * TASK_BID_BASE;
        double priorityBonus = task.priority * 50.0;

        double bid = DISTANCE_WEIGHT * distanceScore
                + BATTERY_WEIGHT * batteryScore
                + CAPABILITY_WEIGHT * capabilityScore
                + priorityBonus;

        if (capability < task.requiredCapability) {
            bid *= 0.5;
        }

        return bid;
    }

    private boolean detectConflict() {
        if (swarmMembers == null || swarmMembers.isEmpty()) return false;

        for (DroneInfo drone : swarmMembers) {
            if (drone.sysid == selfSysid) continue;
            double dist = horizontalDistance(selfLat, selfLon, drone.lat, drone.lon);
            if (dist < CONFLICT_DISTANCE) return true;
        }
        return false;
    }

    private List<FormationCommand> computeFormationAdjustment() {
        List<FormationCommand> commands = new ArrayList<>();
        int memberCount = (swarmMembers != null ? swarmMembers.size() : 0) + 1;

        double[][] positions = computeFormationPositions(memberCount, formationType,
                formationCenterLat, formationCenterLon, formationCenterAlt, formationHeading);

        int idx = 0;
        commands.add(new FormationCommand(selfSysid, positions[idx][0], positions[idx][1],
                positions[idx][2], "hold position"));

        if (swarmMembers != null) {
            for (DroneInfo drone : swarmMembers) {
                idx++;
                if (idx >= positions.length) break;
                commands.add(new FormationCommand(drone.sysid, positions[idx][0],
                        positions[idx][1], positions[idx][2], "hold position"));
            }
        }

        return commands;
    }

    private double[][] computeFormationPositions(int count, String type,
                                                  double centerLat, double centerLon,
                                                  double centerAlt, double heading) {
        double[][] positions = new double[count][3];
        double hdgRad = Math.toRadians(heading);

        switch (type) {
            case "line": {
                double spacing = FORMATION_SPACING;
                double totalWidth = (count - 1) * spacing;
                for (int i = 0; i < count; i++) {
                    double offset = -totalWidth / 2.0 + i * spacing;
                    double north = offset * Math.cos(hdgRad + Math.PI / 2);
                    double east = offset * Math.sin(hdgRad + Math.PI / 2);
                    positions[i][0] = GeoUtil.latOf(centerLat, centerLon, north, east);
                    positions[i][1] = GeoUtil.lonOf(centerLat, centerLon, north, east);
                    positions[i][2] = centerAlt;
                }
                break;
            }
            case "v": {
                for (int i = 0; i < count; i++) {
                    int row = i / 2;
                    int side = i % 2 == 0 ? -1 : 1;
                    double forward = row * FORMATION_SPACING;
                    double lateral = side * row * FORMATION_SPACING * 0.7;
                    double north = forward * Math.cos(hdgRad) + lateral * Math.cos(hdgRad + Math.PI / 2);
                    double east = forward * Math.sin(hdgRad) + lateral * Math.sin(hdgRad + Math.PI / 2);
                    positions[i][0] = GeoUtil.latOf(centerLat, centerLon, north, east);
                    positions[i][1] = GeoUtil.lonOf(centerLat, centerLon, north, east);
                    positions[i][2] = centerAlt;
                }
                break;
            }
            case "circle": {
                double radius = FORMATION_SPACING * Math.max(1, count / (2 * Math.PI));
                for (int i = 0; i < count; i++) {
                    double angle = 2.0 * Math.PI * i / count;
                    double north = radius * Math.cos(angle);
                    double east = radius * Math.sin(angle);
                    positions[i][0] = GeoUtil.latOf(centerLat, centerLon, north, east);
                    positions[i][1] = GeoUtil.lonOf(centerLat, centerLon, north, east);
                    positions[i][2] = centerAlt;
                }
                break;
            }
            default: {
                for (int i = 0; i < count; i++) {
                    positions[i][0] = centerLat;
                    positions[i][1] = centerLon;
                    positions[i][2] = centerAlt;
                }
            }
        }

        return positions;
    }

    private boolean formationNeedsAdjustment() {
        if (swarmMembers == null || swarmMembers.isEmpty()) return false;
        if (Double.isNaN(formationCenterLat) || Double.isNaN(formationCenterLon)) return false;

        int memberCount = swarmMembers.size() + 1;
        double[][] expectedPositions = computeFormationPositions(memberCount, formationType,
                formationCenterLat, formationCenterLon, formationCenterAlt, formationHeading);

        double selfError = horizontalDistance(selfLat, selfLon,
                expectedPositions[0][0], expectedPositions[0][1]);
        if (selfError > FORMATION_SPACING * 0.5) return true;

        for (int i = 0; i < swarmMembers.size() && i + 1 < expectedPositions.length; i++) {
            DroneInfo drone = swarmMembers.get(i);
            double error = horizontalDistance(drone.lat, drone.lon,
                    expectedPositions[i + 1][0], expectedPositions[i + 1][1]);
            if (error > FORMATION_SPACING * 0.5) return true;
        }

        return false;
    }

    private double computeConfidence(String coordType) {
        switch (coordType) {
            case "task_allocation":
                return 0.85;
            case "conflict_resolution":
                return 0.9;
            case "formation_adjustment":
                return 0.75;
            default:
                return 0.6;
        }
    }

    private double horizontalDistance(double lat1, double lon1, double lat2, double lon2) {
        double dn = GeoUtil.north(lat1, lon1, lat2, lon2);
        double de = GeoUtil.east(lat1, lon1, lat2, lon2);
        return Math.sqrt(dn * dn + de * de);
    }

    private static final class TaskBid {
        final int sysid;
        final String taskId;
        final double bid;
        final double priority;

        TaskBid(int sysid, String taskId, double bid, double priority) {
            this.sysid = sysid;
            this.taskId = taskId;
            this.bid = bid;
            this.priority = priority;
        }
    }
}