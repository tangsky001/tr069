-- 初始化角色（管理员账号在应用首次启动时自动创建，密码使用 BCrypt 加密）
INSERT IGNORE INTO sys_role (id, role_code, role_name) VALUES (1, 'ADMIN', '系统管理员');
INSERT IGNORE INTO sys_role (id, role_code, role_name) VALUES (2, 'OPERATOR', '运维操作员');
INSERT IGNORE INTO sys_role (id, role_code, role_name) VALUES (3, 'VIEWER', '只读用户');
