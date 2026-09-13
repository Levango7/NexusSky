package io.aerofleet.cloud.mission;

/**
 * Client-side mission upload result.
 *
 * @param status     "ok" when the drone accepted the whole mission
 * @param uploaded   number of MISSION_ITEM_INT acknowledged by MISSION_ACK
 * @param ackResult  MAV_MISSION_RESULT carried by the final MISSION_ACK
 * @param error      human-readable failure reason when status != ok
 */
public record MissionUploadResult(String status, int uploaded, int ackResult, String error) {

    public static MissionUploadResult ok(int uploaded) {
        return new MissionUploadResult("ok", uploaded, 0, null);
    }

    public static MissionUploadResult failure(String error) {
        return new MissionUploadResult("error", 0, -1, error);
    }

    public static MissionUploadResult rejected(int ackResult, String error) {
        return new MissionUploadResult("error", 0, ackResult, error);
    }
}
