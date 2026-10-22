package com.yt.agent.nl2sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL 安全护栏单元测试（不依赖数据库与模型）。
 *
 * <p>NL2SQL 的 SQL 是模型生成的「不可信输入」，护栏必须默认拒绝。
 * 这里逐层验证：语句白名单、危险关键字、多语句、表级授权、系统表防护、强制 LIMIT、注释剥离。</p>
 */
class SqlSafetyGuardTest {

    private final SqlSafetyGuard guard = new SqlSafetyGuard(1000, Set.of("orders", "users"));

    @Test
    @DisplayName("白名单内表的普通 SELECT 应放行，并强制追加 LIMIT")
    void shouldAcceptPlainSelectAndForceLimit() {
        SqlSafetyGuard.GuardResult result = guard.validate("SELECT * FROM orders");
        assertTrue(result.isPassed(), "普通查询应放行: " + result.getReason());
        assertTrue(result.getSafeSql().toUpperCase().contains("LIMIT 1000"), "无 LIMIT 时应强制追加 1000");
        assertTrue(result.getReferencedTables().contains("orders"));
    }

    @Test
    @DisplayName("已有 LIMIT 的查询不应重复追加")
    void shouldNotDuplicateLimit() {
        SqlSafetyGuard.GuardResult result = guard.validate("SELECT id FROM orders LIMIT 10");
        assertTrue(result.isPassed());
        assertEquals(1,
                result.getSafeSql().toUpperCase().split("LIMIT").length - 1,
                "LIMIT 只应出现一次");
    }

    @Test
    @DisplayName("DDL/DML 关键字必须拒绝")
    void shouldRejectDdlDml() {
        assertFalse(guard.validate("DROP TABLE orders").isPassed());
        assertFalse(guard.validate("DELETE FROM orders").isPassed());
        assertFalse(guard.validate("INSERT INTO orders VALUES (1)").isPassed());
        assertFalse(guard.validate("TRUNCATE TABLE orders").isPassed());
    }

    @Test
    @DisplayName("列名含关键字子串不应误伤（如 update_time、delete_flag）")
    void shouldNotRejectColumnNamesContainingKeywords() {
        SqlSafetyGuard.GuardResult result = guard.validate(
                "SELECT update_time, delete_flag FROM orders WHERE create_time > '2026-01-01'");
        assertTrue(result.isPassed(), "合法列名不应被关键字误伤: " + result.getReason());
    }

    @Test
    @DisplayName("多语句必须拒绝")
    void shouldRejectMultiStatement() {
        assertFalse(guard.validate("SELECT 1; DROP TABLE users").isPassed());
    }

    @Test
    @DisplayName("未授权表必须拒绝（表级数据权限）")
    void shouldRejectUnauthorizedTable() {
        SqlSafetyGuard.GuardResult result = guard.validate("SELECT * FROM secret_table");
        assertFalse(result.isPassed(), "白名单之外的表应默认拒绝");
    }

    @Test
    @DisplayName("系统表/敏感元数据必须拒绝")
    void shouldRejectSystemSchema() {
        assertFalse(guard.validate("SELECT * FROM pg_catalog.pg_shadow").isPassed());
        assertFalse(guard.validate("SELECT usename FROM mysql.user").isPassed());
    }

    @Test
    @DisplayName("危险函数（时间盲注/文件读写）必须拒绝")
    void shouldRejectDangerousFunctions() {
        assertFalse(guard.validate("SELECT pg_sleep(10)").isPassed());
        assertFalse(guard.validate("SELECT load_file('/etc/passwd')").isPassed());
    }

    @Test
    @DisplayName("注释中藏关键字也应被识别（先剥注释再校验）")
    void shouldStripCommentsBeforeValidation() {
        // 注释里塞 DROP，剥离后是合法查询 → 放行但 SQL 中不应再含注释
        SqlSafetyGuard.GuardResult result = guard.validate(
                "SELECT * FROM orders /* DROP TABLE users */ WHERE id = 1");
        assertTrue(result.isPassed());
        assertFalse(result.getSafeSql().contains("DROP"), "注释应被剥离");
        // 反过来：注释只是伪装，真正的多语句仍要拦
        assertFalse(guard.validate("SELECT 1 /* x */; DROP TABLE users").isPassed());
    }

    @Test
    @DisplayName("WITH ... SELECT 作为合法前缀应放行")
    void shouldAcceptWithSelect() {
        SqlSafetyGuard.GuardResult result = guard.validate(
                "WITH t AS (SELECT id FROM orders) SELECT * FROM t");
        assertTrue(result.isPassed(), "CTE 查询应放行: " + result.getReason());
    }
}
