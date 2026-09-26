"""飞行日志 API 封装，提供日志列表查询和详情获取。"""

from .exceptions import SdkException


class FlightLogApi:
    """飞行日志 API 封装类。

    通过 ``client.flight_logs`` 获取实例。
    """

    def __init__(self, client):
        """构造 FlightLogApi 实例。

        Args:
            client: NexusSkyClient 实例。
        """
        self._client = client

    def list(self):
        """查询飞行日志列表。

        Returns:
            list: 飞行日志列表。

        Raises:
            SdkException: 如果请求失败。
        """
        return self._client._request("GET", "/flightlog")

    def get(self, log_id):
        """获取指定飞行日志的详情。

        Args:
            log_id: 日志 ID。

        Returns:
            dict: 日志详情。

        Raises:
            SdkException: 如果请求失败或 log_id 为 None。
        """
        if log_id is None:
            raise SdkException("log_id must not be None")
        return self._client._request("GET", f"/flightlog/{log_id}")