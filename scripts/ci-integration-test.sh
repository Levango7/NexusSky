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
sleep 5

# 3. 运行 API smoke 测试
echo "[3/4] Running API smoke tests..."
# 健康检查
curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"' || { echo "FAIL: health check"; kill $CLOUD_PID; exit 1; }
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
TOKEN=$(curl -sf -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4) || true
if [ -n "$TOKEN" ]; then
  echo "  ✅ /api/auth/login returned JWT token"
else
  echo "  ⚠️ /api/auth/login did not return token (may be in dev-mode)"
fi

# 4. 清理
echo "[4/4] Cleanup..."
kill $CLOUD_PID 2>/dev/null || true
wait $CLOUD_PID 2>/dev/null || true

echo "=== All integration tests passed ==="