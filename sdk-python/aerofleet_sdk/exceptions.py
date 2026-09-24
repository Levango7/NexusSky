"""SDK 异常类，封装所有 API 调用中可能出现的错误。"""


class SdkException(Exception):
    """SDK 异常基类。"""

    def __init__(self, message, status_code=-1, cause=None):
        """
        构造一个 SDK 异常。

        Args:
            message: 错误描述。
            status_code: HTTP 响应状态码，-1 表示非 HTTP 错误。
            cause: 底层异常。
        """
        super().__init__(message)
        self.status_code = status_code
        self.cause = cause


class AuthenticationError(SdkException):
    """认证失败异常（HTTP 401/403）。"""

    def __init__(self, message, status_code=401, cause=None):
        super().__init__(message, status_code, cause)


class NotFoundError(SdkException):
    """资源不存在异常（HTTP 404）。"""

    def __init__(self, message, status_code=404, cause=None):
        super().__init__(message, status_code, cause)


class BadRequestError(SdkException):
    """请求参数错误异常（HTTP 400）。"""

    def __init__(self, message, status_code=400, cause=None):
        super().__init__(message, status_code, cause)


class ServerError(SdkException):
    """服务器内部错误异常（HTTP 5xx）。"""

    def __init__(self, message, status_code=500, cause=None):
        super().__init__(message, status_code, cause)