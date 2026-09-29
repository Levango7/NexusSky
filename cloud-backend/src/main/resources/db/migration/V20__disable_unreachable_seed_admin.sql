-- V20__disable_unreachable_seed_admin.sql: 停用无法登录的种子 admin（P0-2 引导链路）
-- V6:28 写入的哈希经 bcrypt 复算对任何常见口令都不匹配（其注释却称「密码 admin」），
-- 该账号事实上不可登录；而创建用户端点已要求 ADMIN（UserController:114）→ 没有引导路径。
-- 处置：仅当哈希仍是那个不可用的内置值时停用该行；真实管理员改由
-- aerofleet.security.bootstrap-admin-password 在启动时引导（AdminBootstrapRunner）。
-- 条件写死哈希，故对已改密的既有部署是空操作（幂等，且不会停用合法账号）。

UPDATE app_user SET enabled = FALSE
 WHERE username = 'admin'
   AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
