# 外部视觉推理接入（External Vision Inference）

> 适用范围：`aerofleet.vision.source=external`。平台通过 HTTP 将渲染帧 JPEG 推送到外部推理服务，并接收像素坐标检测框；模型可运行在 ONNX Runtime Server、Triton、TorchServe 或自建服务中。**NexusSky 主工程不内嵌 onnxruntime/tensorflow/opencv 等 ML 运行时**，推理进程与平台解耦。

## 1. 启用方式

在 `cloud-backend` 配置中启用外部推理源并指定端点：

```properties
aerofleet.vision.source=external
aerofleet.vision.external.endpoint=http://127.0.0.1:9001/infer
aerofleet.vision.external.timeout-ms=3000
```

- `source=external` 时条件装配 `ExternalVisionSource`；`endpoint` 为空会 WARN 并在检测时返回空结果（fail-open，不阻塞主链路）。
- 仅 **JPEG 像素帧路径** 会调用外部推理（`detect(shot, pose, jpeg)`）；无像素的元数据调用返回空列表。
- 推理/解析失败、非 200、超时、非法响应：按实现记录 WARN 并丢弃该帧/非法条目，不向上抛异常。

## 2. HTTP 契约

### 请求
- **Method/URL**：`POST {aerofleet.vision.external.endpoint}`
- **Headers**：`Content-Type: image/jpeg`
- **Body**：原始 JPEG 字节（当前相机渲染帧，典型 640×360）

### 响应
- **Status**：`200 OK`
- **Content-Type**：`application/json`（推荐）
- **Body**：JSON 数组，每个元素表示一个检测框（像素坐标系，原点在图像左上角，`u` 向右、`v` 向下，单位 px，相对该 JPEG 尺寸）：

```json
[
  {"kind": "insulator", "confidence": 0.93, "u": 312.4, "v": 148.0},
  {"kind": "tower", "confidence": 0.88, "u": 420.1, "v": 201.7}
]
```

字段约束（与 `ExternalVisionSource` 解析一致）：
| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `kind` | string | 是 | 目标类别标签（非空）；下游按 kind 聚合识别率/误检比等评测指标 |
| `confidence` | number | 是 | 置信度，`[0.0, 1.0]`；越界条目丢弃 |
| `u` | number | 是 | 检测框中心/定位点 x 像素坐标 |
| `v` | number | 是 | 检测框中心/定位点 y 像素坐标 |

- 非数组响应：整帧视为无检测。
- 单条缺字段或 `confidence` 越界：该条丢弃，其余合法条目保留。
- 空数组 `[]`：合法，表示本帧无检出。

## 3. 与评测面板的关系

外部推理检出结果会进入既有视觉定位与 CV 评测链路（按 kind 的识别率/误检比/处理时间等指标归账，见 `/api/v1/cv-eval/metrics?source=external` 与 GCS 评测面板）。为获得可解释指标：

- `kind` 应与评测/标注口径一致且稳定；
- `confidence` 建议返回模型真实置信度（不要恒为 1.0，除非确为确定性结果）；
- 服务端处理延迟会反映在端到端处理时间指标中；可在服务侧记录模型推理耗时用于离线分析。

## 4. ONNX Runtime / Triton 等服务对接说明

平台侧契约固定为“POST JPEG → 上述 JSON 数组”。当推理后端原生协议不同（如 Triton/ONNX Runtime Server 的 tensor I/O）时，在推理服务侧做轻量协议适配：

- **输入**：解码 JPEG → 按模型要求预处理（resize/normalize/NCHW 等）。
- **推理**：调用 ONNX 模型（或其他后端）得到 boxes/scores/classes。
- **输出**：做后处理（NMS/阈值/坐标还原到原图像素）并映射为 `{kind, confidence, u, v}` 数组返回。

仓库提供参考脚本 `scripts/vision-inference-server.py`：默认以 **mock** 模式返回契约合法响应（用于联调与回归）；若环境已安装 `onnxruntime`（及可选 `numpy`/`Pillow`）且提供兼容模型，可按脚本参数扩展为真实 ONNX 推理适配。该脚本为可选工具，不纳入主工程 Maven 依赖。

## 5. 边界与诚实声明

- 本契约覆盖**外部推理服务接入**；**进程内嵌入 onnxruntime Java/原生库**不在当前主工程范围内（避免引入平台相关原生依赖与分发复杂度）。
- 检测质量（识别率/误检比）取决于所接模型与数据；平台提供接入与评测度量面，不内置训练好的行业模型。
- 真实设备/真实场景精度需在目标环境按业务数据集验收（非 CI/非模拟器保证项）。