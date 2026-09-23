#!/bin/bash
set -euo pipefail

echo "=== NexusSky CI Integration Test ==="

# 1. 构建所有模块
echo "[1/4] Building all modules..."
mvn -B -DskipTests package

# 2. 启动 cloud-backend（后台）
echo "[2/4] Starting cloud-backend..."
java -jar cloud-backend/target/aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar &
CLOUD_PID=$!

# 2026-09-22 修复：原为固定 `sleep 5` 后**单次** curl 健康检查 ——
# 在 CI 上稳定失败（FAIL: health check）。日志实证：curl 失败于 10:07:59.746，
# 而 Flyway 迁移（8 次）与 Hibernate 初始化在其**之后**（10:07:59.820 起）才开始，
# 即应用此刻根本还没起来。5 秒固定等待对 Spring Boot + Flyway 迁移**远不够**，
# 且单次检查没有重试，启动稍慢即判失败。
# 改为**有界就绪轮询**：最多等 120s，每 2s 探测一次 /actuator/health。
# 这不会掩盖真正的启动失败 —— 超时仍会带着诊断信息退出。
echo "[2.5/4] 等待 cloud-backend 就绪（最多 120s）..."
READY=0
for i in $(seq 1 60); do
    if curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        READY=1
        echo "  ✅ 就绪（第 ${i} 次探测，约 $((i * 2))s）"
        break
    fi
    # 进程已退出则无需继续等待
    if ! kill -0 "$CLOUD_PID" 2>/dev/null; then
        echo "  ❌ cloud-backend 进程已退出（PID $CLOUD_PID）"
        break
    fi
    sleep 2
done

# 3. 运行 API smoke 测试
echo "[3/4] Running API smoke tests..."
# 健康检查（就绪判定）
if [ "$READY" -ne 1 ]; then
    echo "FAIL: health check —— cloud-backend 在 120s 内未就绪"
    echo "  --- /actuator/health 实际响应 ---"
    curl -s -m 5 http://localhost:8080/actuator/health || echo "  (无响应)"
    kill "$CLOUD_PID" 2>/dev/null || true
    exit 1
fi
echo "  ✅ /actuator/health UP"

# OpenAPI 文档可用
curl -sf http://localhost:8080/v3/api-docs > /dev/null || { echo "FAIL: api-docs"; kill $CLOUD_PID; exit 1; }
echo "  ✅ /v3/api-docs available"

# Swagger UI 可用
curl -sf http://localhost:8080/swagger-ui.html > /dev/null || { echo "FAIL: swagger-ui"; kill $CLOUD_PID; exit 1; }
echo "  ✅ /swagger-ui.html available"

# Prometheus 指标端点
curl -sf http://localhost:8080/actuator/prometheus > /dev/null || { echo "FAIL: prometheus"; kill $CLOUD_PID; exit 1; }
echo "  ✅ /actuator/prometheus available"

# 认证端点
TOKEN=$(curl -sf -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4) || true
if [ -n "$TOKEN" ]; then
  echo "  ✅ /api/v1/auth/login returned JWT token"
else
  echo "  ⚠️ /api/v1/auth/login did not return token (may be in dev-mode)"
fi

# 4. 清理
echo "[4/4] Cleanup..."
kill $CLOUD_PID 2>/dev/null || true
wait $CLOUD_PID 2>/dev/null || true

echo "=== All integration tests passed ==="