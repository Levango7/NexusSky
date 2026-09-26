"""NexusSky SDK for Python.

导出 NexusSkyClient 和异常类，供外部使用。

使用示例::

    from aerofleet_sdk import NexusSkyClient, SdkException

    client = NexusSkyClient("https://cloud.example.com", "your-api-key")
    try:
        drones = client.get_drones()
    except SdkException as e:
        print(f"API 调用失败: {e}")
"""

from .client import NexusSkyClient
from .drones import DroneApi
from .mission import MissionApi, Mission, Waypoint
from .flightlog import FlightLogApi
from .exceptions import (
    SdkException,
    AuthenticationError,
    NotFoundError,
    BadRequestError,
    ServerError,
)

__all__ = [
    "NexusSkyClient",
    "DroneApi",
    "MissionApi",
    "Mission",
    "Waypoint",
    "FlightLogApi",
    "SdkException",
    "AuthenticationError",
    "NotFoundError",
    "BadRequestError",
    "ServerError",
]

__version__ = "1.0.0"