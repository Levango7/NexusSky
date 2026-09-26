"""无人机 API 封装，提供设备列表、详情、遥测、飞行命令等操作。"""

from .exceptions import SdkException


class DroneApi:
    """无人机 API 封装类。

    通过 ``client.drones`` 获取实例。
    """

    def __init__(self, client):
        """构造 DroneApi 实例。

        Args:
            client: NexusSkyClient 实例。
        """
        self._client = client

    def list(self):
        """获取所有无人机列表。

        Returns:
            list: 无人机摘要列表，每个元素包含 sysid、online、battery、mode 等字段。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._client._request("GET", "/drones")

    def get(self, sysid):
        """获取单个无人机的详细信息。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 无人机详情，包含最新告警信息。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._client._request("GET", f"/drones/{sysid}")

    def telemetry(self, sysid):
        """获取无人机遥测数据。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 当前遥测快照（位置、姿态、速度等）。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._client._request("GET", f"/drones/{sysid}/telemetry")

    def arm(self, sysid):
        """解锁无人机（arm 命令）。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 命令执行结果。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._send_command(sysid, "arm", 0)

    def takeoff(self, sysid, alt):
        """起飞命令。

        Args:
            sysid: 无人机系统 ID。
            alt: 目标高度（米）。

        Returns:
            dict: 命令执行结果。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._send_command(sysid, "takeoff", alt)

    def rtl(self, sysid):
        """返航命令（RTL）。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 命令执行结果。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._send_command(sysid, "rtl", 0)

    def start_mission(self, sysid):
        """开始任务命令。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 命令执行结果。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._send_command(sysid, "start_mission", 0)

    def _send_command(self, sysid, command_type, alt):
        """发送飞行命令的内部方法。"""
        body = {"type": command_type, "alt": alt}
        return self._client._request("POST", f"/drones/{sysid}/commands", json=body)