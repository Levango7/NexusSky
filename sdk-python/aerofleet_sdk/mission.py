"""任务 API 封装，提供航点任务的上传、下载、清除操作。"""

from dataclasses import dataclass, field
from typing import List

from .exceptions import SdkException


@dataclass
class Waypoint:
    """航点数据对象。

    Args:
        lat: 纬度，有效范围 [-90, 90]。
        lon: 经度，有效范围 [-180, 180]。
        alt: 高度（米），必须 >= 0。

    Raises:
        ValueError: 如果 lat/lon/alt 超出有效范围。
    """

    lat: float
    lon: float
    alt: float

    def __post_init__(self):
        if not (-90 <= self.lat <= 90):
            raise ValueError(f"lat must be in [-90, 90], got: {self.lat}")
        if not (-180 <= self.lon <= 180):
            raise ValueError(f"lon must be in [-180, 180], got: {self.lon}")
        if self.alt < 0:
            raise ValueError(f"alt must be >= 0, got: {self.alt}")

    def to_dict(self):
        """转换为字典格式。"""
        return {"lat": self.lat, "lon": self.lon, "alt": self.alt}


@dataclass
class Mission:
    """任务对象，包含航点列表。"""

    waypoints: List[Waypoint] = field(default_factory=list)

    def to_dict(self):
        """转换为字典格式。"""
        return {"waypoints": [wp.to_dict() for wp in self.waypoints]}

    @classmethod
    def from_dict(cls, data):
        """从字典构造 Mission 对象。"""
        waypoints = []
        for wp_data in data.get("waypoints", []):
            waypoints.append(Waypoint(
                lat=wp_data.get("lat", 0),
                lon=wp_data.get("lon", 0),
                alt=wp_data.get("alt", 0),
            ))
        return cls(waypoints=waypoints)


class MissionApi:
    """任务 API 封装类。

    通过 ``client.missions`` 获取实例。
    """

    def __init__(self, client):
        """构造 MissionApi 实例。

        Args:
            client: NexusSkyClient 实例。
        """
        self._client = client

    def upload(self, sysid, mission):
        """上传航点任务到指定无人机。

        Args:
            sysid: 无人机系统 ID。
            mission: Mission 对象或 Waypoint 列表。

        Returns:
            dict: 上传结果。

        Raises:
            SdkException: 如果请求失败。
        """
        if isinstance(mission, Mission):
            body = mission.to_dict()
        elif isinstance(mission, list):
            body = {"waypoints": [
                wp.to_dict() if isinstance(wp, Waypoint) else wp
                for wp in mission
            ]}

        else:
            raise SdkException("mission must be Mission object or list of waypoints")

        return self._client._request("POST", f"/drones/{sysid}/mission", json=body)

    def download(self, sysid):
        """下载指定无人机的航点任务。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 航点任务数据。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._client._request("GET", f"/drones/{sysid}/mission")

    def clear(self, sysid):
        """清除指定无人机的航点任务。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 清除结果。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._client._request("POST", f"/drones/{sysid}/mission/clear")