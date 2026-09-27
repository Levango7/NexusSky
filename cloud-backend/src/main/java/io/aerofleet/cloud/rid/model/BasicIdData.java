package io.aerofleet.cloud.rid.model;

/**
 * Basic ID 数据，对应 ASTM F3411 Remote ID Basic ID Message。
 * <p>
 * 包含无人机的基本标识信息：
 * <ul>
 *   <li>{@code idType} — ID 类型（0=Serial Number, 1=CAA Registration ID, 2=UTM Assigned ID, 3=Specific Session ID）</li>
 *   <li>{@code uaType} — 无人机类型（0=None, 1=Aeroplane, 2=Helicopter/Multirotor, 3=Gyroplane, 4=VTOL, 5=Ornithopter, 6=Glider, 7=Kite, 8=Free Balloon, 9=Captive Balloon, 10=Airship, 11=Free Fall/Parachute, 12=Rocket, 13=Tethered Powered Aircraft, 14=Ground Obstacle, 15=Other）</li>
 *   <li>{@code uasId} — UAS 标识字符串</li>
 * </ul>
 *
 * @param idType  ID 类型
 * @param uaType  无人机类型
 * @param uasId   UAS 标识字符串
 */
public record BasicIdData(
        int idType,
        int uaType,
        String uasId
) {
}