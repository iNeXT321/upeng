package com.yt.agent.controller;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataBrowserController 的 JDBC 读取路径回归测试。
 *
 * <p>背景：2026-10-24 线上 trace（requestId=3ee38dc1f81441d7）暴露了旧版代码在
 * rs.next() 之前读取 ResultSet 导致的 "Before start of result set" 失败。
 * 本测试用 H2 内存库（MySQL 兼容模式）复现两条最容易踩坑的路径：
 * ① 空表（COUNT 与 SELECT * 结果集为空，游标从未前进）；② 正常数据行读取。
 * 修复前旧代码在 ① 上必红，修复后两种路径都必须绿。</p>
 *
 * <p>不起 Spring 容器：@Value 字段用反射注入，直连 H2，保证毫秒级可跑。</p>
 */
class DataBrowserControllerTest {

    private static DataBrowserController controller;

    @BeforeAll
    static void setUp() throws Exception {
        Class.forName("org.h2.Driver");
        String url = "jdbc:h2:mem:dbbrowsertest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t_empty (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE t_data (id INT PRIMARY KEY, name VARCHAR(64))");
            st.execute("INSERT INTO t_data VALUES (1, '订单'), (2, '退款')");
        }

        controller = new DataBrowserController();
        inject("dbUrl", url);
        inject("dbUser", "sa");
        inject("dbPassword", "");
    }

    private static void inject(String field, String value) throws Exception {
        Field f = DataBrowserController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    @Test
    @DisplayName("空表分页预览：total=0、columns 齐全、rows 为空，且不抛 Before start of result set")
    void shouldHandleEmptyTableWithoutCursorError() {
        Map<String, Object> resp = controller.mysqlRows("t_empty", 1, 20);

        assertEquals("t_empty", resp.get("table"));
        assertEquals(0L, resp.get("total"));
        assertEquals(List.of("id", "name"), resp.get("columns"));
        assertTrue(((List<?>) resp.get("rows")).isEmpty(),
                "空表应返回空 rows，而不是游标错误");
    }

    @Test
    @DisplayName("有数据表分页预览：行数、列名、单元格值逐项正确")
    void shouldReadRowsCorrectly() {
        Map<String, Object> resp = controller.mysqlRows("t_data", 1, 20);

        assertEquals(2L, resp.get("total"));
        assertEquals(List.of("id", "name"), resp.get("columns"));
        List<List<Object>> rows = (List<List<Object>>) resp.get("rows");
        assertEquals(2, rows.size());
        assertEquals(List.of(1, "订单"), rows.get(0));
        assertEquals(List.of(2, "退款"), rows.get(1));
    }

    // 注：mysqlTables() 的 SQL 依赖 MySQL 专属列（information_schema.tables.table_rows 等），
    // 无法在 H2 上验证，游标回归已由上面两个用例覆盖同样的 jdbcQuery/jdbcQueryMeta 路径。
}
