"""sdk-python 测试（2026-10-13 补）。

此前这个 SDK 有 723 行、覆盖 5 个模块、**零测试**——而后端契约一变
（路径改名、字段改名、错误码语义调整），SDK 只在客户调用时才炸。

测试策略：不引新依赖（requests_mock 之类），用 unittest.mock 在
`requests.Session.request` 层打桩，覆盖四类行为：
  ① URL 拼装（base_url + /api/v1 + path，含尾斜杠归一）
  ② 状态码 → 异常类型映射（400/401/403/404/5xx/其他）
  ③ 成功路径的 JSON 解析与返回形状
  ④ 三个子资源（drones/mission/flightlog）的路径与参数
"""

import json

import pytest

from aerofleet_sdk import (
    NexusSkyClient,
    SdkException,
    AuthenticationError,
    NotFoundError,
    BadRequestError,
    ServerError,
)
from aerofleet_sdk.drones import DroneApi
from aerofleet_sdk.mission import MissionApi, Mission, Waypoint
from aerofleet_sdk.flightlog import FlightLogApi


def make_client(base_url="https://cloud.example.com", api_key="test-key"):
    return NexusSkyClient(base_url, api_key)


class FakeResponse:
    """最小 requests.Response 替身：只有 SDK 真正读的字段。"""

    def __init__(self, status_code=200, body=None, text=None, json_error=False):
        self.status_code = status_code
        self._body = body
        self._json_error = json_error
        if text is not None:
            self.text = text
        elif body is None:
            self.text = ""
        else:
            self.text = json.dumps(body)

    def json(self):
        if self._json_error:
            raise ValueError("not json")
        return self._body


@pytest.fixture
def stub_request(monkeypatch):
    """把 Session.request 换成可编程桩，返回本次捕获到的调用参数。"""
    captured = {}

    def install(client, response):
        def fake_request(self, method, url, json=None, timeout=None):
            captured["method"] = method
            captured["url"] = url
            captured["json"] = json
            captured["timeout"] = timeout
            captured["headers"] = self.headers.get("X-API-Key")
            return response

        monkeypatch.setattr("requests.Session.request", fake_request)

    captured["install"] = install
    return captured


# ========== URL 拼装 ==========

class TestUrlBuilding:
    def test_prefix_is_api_v1(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, {"ok": True}))
        client.get_drones()
        assert stub_request["url"] == "https://cloud.example.com/api/v1/drones"

    def test_trailing_slash_in_base_url_is_normalized(self, stub_request):
        client = make_client(base_url="https://cloud.example.com/")
        stub_request["install"](client, FakeResponse(200, []))
        client.get_drones()
        assert stub_request["url"] == "https://cloud.example.com/api/v1/drones"

    def test_path_gets_interpolated(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, {"sysid": 7}))
        client.get_drone(7)
        assert stub_request["url"] == "https://cloud.example.com/api/v1/drones/7"

    def test_api_key_header_is_sent(self, stub_request):
        client = make_client(api_key="secret-key")
        stub_request["install"](client, FakeResponse(200, []))
        client.get_drones()
        assert stub_request["headers"] == "secret-key"

    def test_timeout_is_passed_through(self, stub_request):
        client = NexusSkyClient("https://cloud.example.com", "k", timeout=5)
        stub_request["install"](client, FakeResponse(200, []))
        client.get_drones()
        assert stub_request["timeout"] == 5


# ========== 状态码 → 异常映射 ==========

class TestStatusMapping:
    @pytest.mark.parametrize("status,exc", [
        (400, BadRequestError),
        (401, AuthenticationError),
        (403, AuthenticationError),
        (404, NotFoundError),
        (500, ServerError),
        (503, ServerError),
    ])
    def test_known_codes(self, stub_request, status, exc):
        client = make_client()
        stub_request["install"](client, FakeResponse(status, {"error": "boom"}))
        with pytest.raises(exc):
            client.get_drones()

    def test_server_error_message_from_body(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(500, {"error": "内部错误"}))
        with pytest.raises(ServerError) as ei:
            client.get_drones()
        assert "内部错误" in str(ei.value)

    def test_error_body_not_json_falls_back_to_status(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(500, json_error=True))
        with pytest.raises(ServerError) as ei:
            client.get_drones()
        assert "500" in str(ei.value)

    def test_unmapped_4xx_becomes_base_sdk_exception(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(409, {"error": "conflict"}))
        with pytest.raises(SdkException) as ei:
            client.get_drones()
        # 必须不是更具体的子类（否则调用方会误判类型）
        assert type(ei.value) is SdkException

    def test_status_carried_on_exception(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(404, {"error": "nope"}))
        with pytest.raises(NotFoundError) as ei:
            client.get_drone(42)
        assert ei.value.status_code == 404


# ========== 成功路径 ==========

class TestSuccessPaths:
    def test_get_drones_returns_parsed_list(self, stub_request):
        client = make_client()
        payload = [{"sysid": 1}, {"sysid": 2}]
        stub_request["install"](client, FakeResponse(200, payload))
        assert client.get_drones() == payload

    def test_post_sends_json_body(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, {"ok": True}))
        client.send_command(1, "arm")
        assert stub_request["method"] == "POST"
        # 后端契约：命令字段名是 type（不是 command）
        assert stub_request["json"] == {"type": "arm", "alt": 0}

    def test_command_alt_is_forwarded(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, {"ok": True}))
        client.send_command(1, "takeoff", alt=60)
        assert stub_request["json"]["alt"] == 60

    def test_empty_body_raises(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, text=""))
        with pytest.raises(SdkException) as ei:
            client.get_drones()
        assert "空响应体" in str(ei.value)

    def test_malformed_json_raises(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, json_error=True, text="{oops"))
        with pytest.raises(SdkException) as ei:
            client.get_drones()
        assert "响应解析失败" in str(ei.value)


# ========== 网络层失败 ==========

class TestNetworkFailure:
    def test_request_exception_is_wrapped(self, monkeypatch):
        import requests

        def boom(self, method, url, json=None, timeout=None):
            raise requests.ConnectionError("refused")

        monkeypatch.setattr("requests.Session.request", boom)
        client = make_client()
        with pytest.raises(SdkException) as ei:
            client.get_drones()
        assert "请求失败" in str(ei.value)
        assert ei.value.cause is not None


# ========== 三个子资源 ==========

class TestSubResources:
    def test_drones_resource_uses_same_client(self):
        client = make_client()
        assert client.drones()._client is client

    def test_missions_resource_uses_same_client(self):
        client = make_client()
        assert client.missions()._client is client

    def test_flight_logs_resource_uses_same_client(self):
        client = make_client()
        assert client.flight_logs()._client is client

    def test_all_sub_resources_are_methods_not_properties(self):
        """三个子资源必须是方法（与 Java SDK 及彼此一致）。

        2026-10-13 之前 missions/flight_logs 是 property 而 drones 是方法——
        同一份 SDK 两种用法，且 property 上再调用会 TypeError。这个用例钉住收口。
        """
        client = make_client()
        for name in ("drones", "missions", "flight_logs"):
            attr = getattr(type(client), name)
            assert callable(attr), name + " 必须是方法"
            assert not isinstance(attr, property), name + " 不许回退成 property"

    def test_mission_resource_delegates_to_client_request(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, {"ok": True}))
        client.missions().upload(3, [{"seq": 0}])
        assert stub_request["method"] == "POST"
        assert stub_request["url"].endswith("/api/v1/drones/3/mission")

    def test_flight_log_resource_hits_flightlog_path(self, stub_request):
        client = make_client()
        stub_request["install"](client, FakeResponse(200, []))
        client.flight_logs().list()
        assert stub_request["url"].endswith("/api/v1/flightlog")
        assert stub_request["method"] == "GET"
