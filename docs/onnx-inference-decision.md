# 进程内 ONNX 推理接入决策（P1-6 剩余项）

> 决策 spike：**只产出决策结论与前置判据，不实施接入**。本批不改 `pom.xml`、不引入 onnxruntime 依赖、不引入模型文件。
> 取数基线：`origin/master` `ea977f5f`（2026-11-26）。实测命令见 §7。

## 1. 决策问题

P1-6 在需求清单中仍为未勾，剩余两项：

1. **进程内嵌入 onnxruntime**（Java 原生绑定）。
2. **内置行业模型**（随平台分发的训练好模型）。

当前主线已交付的是**外部推理服务契约 + 评测层**，并非进程内推理。

## 2. 现状（实测）

- 主工程 **零 ML 运行时依赖**：全仓 `pom.xml` 无 `onnxruntime`/`tensorflow`/ONNX 任何 ML 依赖；仓库内无 `.onnx`/`.pt`/`.pth`/`.tflite` 模型文件。
- 视觉源抽象 `VisionSource` 已有四实现：`ProjectionVisionSource`（投影简化）、`SimulatedVisionSource`（模拟）、`BlobDetector`（`source=pixels` 绕过接口）、`ExternalVisionSource`（外部 HTTP）。默认 `aerofleet.vision.source=truth`。
- 外部契约固定为「POST JPEG → `[{kind, confidence, u, v}]`」（640×360 像素坐标），兼容 ONNX Runtime Server / Triton / TorchServe / 自建服务。失败语义为非 200/超时/非法响应 → WARN 丢弃、返回空列表（fail-open，不抛异常）。
- 评测层已就位：`frames` / `recall` / `falseDetectionRatio` / `latencyAvgMs` / `latencyP95Ms`，参考线 `recall=0.85`、`falseDetectionRatio=0.15`，按 kind 拆分归账（`/api/v1/cv-eval/metrics?source=external`）。
- 既定边界（多处文档一致）：**主工程不内嵌 onnxruntime/tensorflow/opencv**；模型选型与精度验收依赖领域标注数据，不由平台工程决定。

## 3. 选项

| 方案 | 描述 | 端到端延迟 | 运维/分发 | 信创适配 | 许可证合规 | 验收 | 与既定边界 |
|---|---|---|---|---|---|---|---|
| **甲 维持外部服务** | 平台零 ML 运行时，推理走独立服务进程 | 多一次网络跳（本机回路可忽略） | 推理服务独立部署/扩展/升级，平台不变 | 推理服务单独适配，平台不受累 | 模型许可在服务侧管理，平台不接触模型 | 用既有评测层 + 真实数据集 | ✅ 一致 |
| **乙 进程内 onnxruntime** | Java 原生绑定，模型随平台进程内推理 | 最低（无网络跳） | 原生库随平台分发，平台相关构建 | 需按信创栈重新编译/验证原生库 | 模型 + onnxruntime 许可都要合规 | 需新建进程内源 + 评测对照 | ❌ 冲突（需改边界） |
| **丙 外部服务 + 内置模型** | 模型随平台分发，推理仍在外部服务进程 | 同甲 | 平台多一份模型资产与版本管理 | 模型文件本身跨平台 | 模型许可要合规 | 同甲 + 模型资产验收 | ⚠️ 部分一致（不引运行时，只分发模型） |

## 4. 推荐

**甲：维持外部推理服务为默认。**

理由：
1. 现有契约已把推理与平台解耦，评测层按 kind 归账，**不需要进程内运行时即可验收识别率/误检比/延迟**。
2. 进程内嵌入引入平台相关原生依赖与分发复杂度，与多处既定边界冲突，且**爆炸半径大**（JVM 堆外内存、原生库崩溃会拖垮平台进程、CI/信创构建矩阵膨胀）。
3. 无真实业务数据集与模型 artifact 时，进程内接入无法验收，属于「先把依赖引进来再找数据」的反模式。

**乙（进程内 onnxruntime）仅在以下前置判据全部满足时才立项**，任一不成立即停手：

- P1 存在**单机离线/低延迟硬需求**，且外部服务方案在目标环境被证明不可行（实测数据，非推测）。
- P2 有**真实业务数据集 + 标注口径**，能对 recall / falseDetectionRatio / latencyP95 设定验收线。
- P3 模型 artifact **来源与许可明确**（自训/采购/开源，可商用），且 onnxruntime 许可兼容。
- P4 目标环境**信创栈**已明确，且原生库可在其上编译/运行并实测通过。
- P5 保留 `external` 源开关，进程内源 fail-open，可回退到甲。

**丙（内置模型）** 作为甲的增强可选：平台提供参考模型 + 适配脚本（沿用 `scripts/vision-inference-server.py` 的 `onnx` 挂载点），**不改主工程依赖**；模型资产版本管理需另立。

## 5. 范围外（本批不做）

- 不改 `pom.xml`、不引入 onnxruntime/任何 ML 运行时。
- 不引入模型文件、不做模型训练/选型。
- 不实施进程内 `VisionSource` 实现。
- 模型选型与精度验收属产品/领域决策，不在工程 spike 范围。

## 6. 结论

- 默认方案：**甲（维持外部服务）**，现状即满足 P1-6 的「评测层 + 接入契约」部分。
- 进程内 onnxruntime：**不立项**，除非 §4 的 P1–P5 全部成立。
- 内置行业模型：**待模型来源/许可 + 真实数据集**两个前置明确后再议（丙）。

## 7. 实测命令（可复算）

```bash
# 零 ML 依赖
grep -rn "onnx\|onnxruntime\|tensorflow" --include=pom.xml .   # 无命中
# 无模型文件
find . -path ./node_modules -prune -o \( -iname "*.onnx" -o -iname "*.pt" -o -iname "*.pth" -o -iname "*.tflite" \) -print
# 评测层指标
grep -n "recall\|falseDetectionRatio\|latencyP95" cloud-backend/src/main/java/io/aerofleet/cloud/vision/CvEvalService.java
# 状态性门禁（与本批无关，同仓基线）
python3 scripts/check-statefulness.py
```

> 数字漂移提示：§2/§4 的「零依赖」「四实现」「默认 truth」均取自 `origin/master` `ea977f5f`；若 master 前进，请按 §7 重算后再引用。
