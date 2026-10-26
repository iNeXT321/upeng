-- =====================================================================
-- 生产级账号治理：NL2SQL 执行 SQL 的账号必须只读（纵深防御最后一层，
-- 即使模型被诱导写出 DML，数据库层面也会拒绝）
-- =====================================================================
USE bi_warehouse;
-- 问数只读账号（应用使用）
CREATE USER IF NOT EXISTS 'nl2sql_ro'@'%' IDENTIFIED BY 'nl2sql_ro_2026';
GRANT SELECT ON bi_warehouse.* TO 'nl2sql_ro'@'%';
-- 刷新权限
FLUSH PRIVILEGES;
