"""NexusSky 平台 API 客户端。

封装了无人机管理、遥测查询、命令下发、编队操作等核心 API，
内部使用 requests 库，自动添加 X-API-Key 认证头。

使用示例::

    from aerofleet_sdk import NexusSkyClient

    client = NexusSkyClient("https://cloud.example.com", "your-api-key")
    drones = client.get_drones()
    telemetry = client.get_telemetry(1)
    client.send_command(1, "arm")
"""

import requests

from .exceptions import (
    SdkException,
    AuthenticationError,
    NotFoundError,
    BadRequestError,
    ServerError,
)

_API_PREFIX = "/api/v1"


class NexusSkyClient:
    """NexusSky 平台 API 客户端。

    .. warning::
        NexusSkyClient 实例不可跨线程共享，每个线程应创建独立实例。
        requests.Session 不是线程安全的，共享实例可能导致请求异常或数据错乱。
    """

    def __init__(self, base_url, api_key, timeout=30, allow_insecure_http=False):
        """
        构造一个 NexusSky API 客户端。

        Args:
            base_url: 后端服务基础地址（如 "https://cloud.example.com"）。
            api_key: API 密钥，用于 X-API-Key 认证头。
            timeout: 请求超时时间（秒），默认 30。
            allow_insecure_http: 是否允许使用 HTTP（非 HTTPS）协议，默认 False。

        Raises:
            ValueError: 如果 base_url 或 api_key 为 None，或 base_url 未使用 HTTPS 协议。
        """
        if base_url is None:
            raise ValueError("base_url must not be None")
        if api_key is None:
            raise ValueError("api_key must not be None")
        if not allow_insecure_http and not base_url.startswith("https://"):
            raise ValueError("base_url must use HTTPS protocol")

        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self._session = requests.Session()
        self._session.headers.update({
            "X-API-Key": api_key,
            "Accept": "application/json",
        })
        self._drones = None
        self._missions = None
        self._flight_logs = None

    # ===================================================================
    # API 模块属性（lazy init）
    # ===================================================================

    @property
    def drones(self):
        """获取无人机 API 模块。

        Returns:
            DroneApi: 无人机 API 实例。
        """
        if self._drones is None:
            from .drones import DroneApi
            self._drones = DroneApi(self)
        return self._drones

    @property
    def missions(self):
        """获取任务 API 模块。

        Returns:
            MissionApi: 任务 API 实例。
        """
        if self._missions is None:
            from .mission import MissionApi
            self._missions = MissionApi(self)
        return self._missions

    @property
    def flight_logs(self):
        """获取飞行日志 API 模块。

        Returns:
            FlightLogApi: 飞行日志 API 实例。
        """
        if self._flight_logs is None:
            from .flightlog import FlightLogApi
            self._flight_logs = FlightLogApi(self)
        return self._flight_logs

    # ===================================================================
    # 无人机 API
    # ===================================================================

    def get_drones(self):
        """获取所有无人机列表。

        Returns:
            list: 无人机摘要列表，每个元素包含 sysid、online、battery、mode 等字段。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        return self._request("GET", "/drones")

    def get_drone(self, sysid):
        """获取单个无人机的详细信息。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 无人机详情，包含最新告警信息。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        return self._request("GET", f"/drones/{sysid}")

    def get_telemetry(self, sysid):
        """获取无人机遥测数据。

        Args:
            sysid: 无人机系统 ID。

        Returns:
            dict: 当前遥测快照（位置、姿态、速度等）。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        return self._request("GET", f"/drones/{sysid}/telemetry")

    def send_command(self, sysid, command, alt=0):
        """向无人机发送飞行命令。

        Args:
            sysid: 无人机系统 ID。
            command: 命令类型（arm / disarm / start_mission / rtl / takeoff）。
            alt: 高度参数（仅 takeoff 命令使用，单位：米），默认 0。

        Returns:
            dict: 命令执行结果。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        body = {"type": command, "alt": alt}
        return self._request("POST", f"/drones/{sysid}/commands", json=body)

    # ===================================================================
    # 编队 API
    # ===================================================================

    def get_formations(self):
        """获取所有编队列表。

        Returns:
            list: 编队列表，每个元素包含 formationId、state、shape 等字段。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        return self._request("GET", "/formation")

    def get_formation(self, formation_id):
        """查询单个编队状态。

        Args:
            formation_id: 编队 ID。

        Returns:
            dict: 编队详情，包含队形、成员、位置等信息。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        return self._request("GET", f"/formation/{formation_id}")

    def create_formation(self, members, shape, spacing=10, heading=0,
                         ref_lat=0, ref_lon=0, ref_alt=0):
        """创建编队。

        Args:
            members: 成员无人机 sysid 列表。
            shape: 队形名称（如 LINE / GRID / CIRCLE / VEE）。
            spacing: 间距（米），默认 10。
            heading: 航向角（度），默认 0。
            ref_lat: 参考点纬度，默认 0。
            ref_lon: 参考点经度，默认 0。
            ref_alt: 参考点高度（米），默认 0。

        Returns:
            dict: 创建结果，包含 formationId、assignments、state、leader。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        body = {
            "members": members,
            "shape": shape,
            "spacing": spacing,
            "heading": heading,
            "refLat": ref_lat,
            "refLon": ref_lon,
            "refAlt": ref_alt,
        }
        return self._request("POST", "/formation", json=body)

    def command_formation(self, formation_id, command_type, alt=0):
        """向编队下发命令。

        Args:
            formation_id: 编队 ID。
            command_type: 命令类型（TAKEOFF / TRANSITION / LIGHTS / RTL / DISSOLVE）。
            alt: 高度参数（仅 TAKEOFF 使用），默认 0。

        Returns:
            dict: 命令执行结果。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        body = {"type": command_type, "alt": alt}
        return self._request("POST", f"/formation/{formation_id}/command", json=body)

    def transition_formation(self, formation_id, new_shape, steps):
        """队形平滑变换。

        Args:
            formation_id: 编队 ID。
            new_shape: 新队形名称。
            steps: 插值步数（>= 1）。

        Returns:
            dict: 变换结果，包含各成员航点。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        body = {"newShape": new_shape, "steps": steps}
        return self._request("POST", f"/formation/{formation_id}/transition", json=body)

    def dissolve_formation(self, formation_id):
        """解散编队。

        Args:
            formation_id: 编队 ID。

        Returns:
            dict: 解散结果。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        return self._request("POST", f"/formation/{formation_id}/dissolve")

    # ===================================================================
    # 内部请求方法
    # ===================================================================

    def _request(self, method, path, json=None):
        """发送 HTTP 请求并解析响应。

        Args:
            method: HTTP 方法（GET / POST）。
            path: API 路径（不含 base_url 和 /api/v1 前缀）。
            json: 请求体 JSON 数据（POST 方法时使用）。

        Returns:
            解析后的 JSON 响应（dict 或 list）。

        Raises:
            SdkException: 如果请求失败或响应解析错误。
        """
        url = self.base_url + _API_PREFIX + path

        try:
            resp = self._session.request(
                method=method,
                url=url,
                json=json,
                timeout=self.timeout,
            )
        except requests.RequestException as e:
            raise SdkException(f"请求失败: {path}", cause=e)

        self._check_status(resp, path)

        if not resp.text:
            raise SdkException("空响应体: " + path, status_code=resp.status_code)

        try:
            return resp.json()
        except ValueError as e:
            raise SdkException(f"响应解析失败: {path}", status_code=resp.status_code, cause=e)

    @staticmethod
    def _check_status(resp, path):
        """检查 HTTP 响应状态码，失败时抛出对应异常。

        Args:
            resp: requests.Response 对象。
            path: API 路径（用于错误消息）。

        Raises:
            BadRequestError: HTTP 400。
            AuthenticationError: HTTP 401/403。
            NotFoundError: HTTP 404。
            ServerError: HTTP 5xx。
            SdkException: 其他 HTTP 错误。
        """
        if resp.status_code < 400:
            return

        try:
            err_body = resp.json()
            error_msg = err_body.get("error", f"HTTP {resp.status_code}")
        except ValueError:
            error_msg = f"HTTP {resp.status_code}"

        if resp.status_code == 400:
            raise BadRequestError(error_msg, status_code=resp.status_code)
        elif resp.status_code in (401, 403):
            raise AuthenticationError(error_msg, status_code=resp.status_code)
        elif resp.status_code == 404:
            raise NotFoundError(error_msg, status_code=resp.status_code)
        elif resp.status_code >= 500:
            raise ServerError(error_msg, status_code=resp.status_code)
        else:
            raise SdkException(error_msg, status_code=resp.status_code)