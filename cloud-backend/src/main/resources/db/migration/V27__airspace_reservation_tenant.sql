-- V27__airspace_reservation_tenant.sql: 预约归属租户列（可空——纯内存/早期数据无归属）
--
-- 语义：归属记录（审计面"谁的调度占了这块空域"），**不参与冲突判定**——
-- 空域是物理的，跨租户同样冲突，检测始终全局。

ALTER TABLE airspace_reservations ADD COLUMN tenant_id INTEGER;
