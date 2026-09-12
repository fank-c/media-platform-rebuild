-- auth-service 显式执行的初始建表脚本；重复执行不会修正已存在表的结构漂移。
-- 认证凭据、角色和状态由 auth-service 独占，其他服务不得直接读写本表。
-- id 由 MyBatis-Plus ASSIGN_UUID 生成 32 位 UUID 字符串，不使用数据库自增序列。
CREATE TABLE IF NOT EXISTS `auth_account` (
    `id` CHAR(32) NOT NULL,
    `email` VARCHAR(255) NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `role` VARCHAR(16) NOT NULL DEFAULT 'USER',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auth_account_email` (`email`),
    CONSTRAINT `ck_auth_account_role` CHECK (`role` IN ('USER', 'ADMIN')),
    CONSTRAINT `ck_auth_account_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `ck_auth_account_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='认证账户凭据、角色和状态仅由 auth-service 持有；password_hash 仅允许 BCrypt 哈希，禁止明文和 MD5。';
