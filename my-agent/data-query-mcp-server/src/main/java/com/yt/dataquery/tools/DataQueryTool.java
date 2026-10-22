package com.yt.dataquery.tools;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 问数 MCP 工具 —— 以 MCP 协议对外暴露的两个能力
 *
 * <p>本模块是<b>独立进程</b>，通过 MCP（Model Context Protocol）把「Schema 召回」和「只读 SQL 执行」
 * 暴露为标准工具。任何支持 MCP 的 Agent（本项目的 my-agent、Claude Desktop、Cursor 等）都能接入，
 * 不依赖具体框架实现——这正是 MCP 的价值：能力提供方与消费方解耦。</p>
 *
 * <p>安全设计（MCP Server 是数据库的直接守门人，必须自包含完整校验）：
 * 语句白名单 + 危险关键字/函数黑名单 + 系统表防护 + 强制 LIMIT + 只读连接 + 查询超时 + 行数截断。</p>
 */
@Slf4j
@Service
public class DataQueryTool {

    @Value("${dataquery.datasource.url:}")
    private String dbUrl;
    @Value("${dataquery.datasource.username:}")
    private String dbUser;
    @Value("${dataquery.datasource.password:}")
    private String dbPassword;
    @Value("${dataquery.executor.query-timeout-seconds:30}")
    private int queryTimeout;
    @Value("${dataquery.executor.max-rows:500}")
    private int maxRows;
    @Value("${dataquery.guard.default-row-limit:1000}")
    private int defaultRowLimit;

    private static final Set<String> ALLOWED_PREFIX =
            Set.of("select", "with", "show", "desc", "describe", "explain");

    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "\\b(insert|update|delete|drop|truncate|alter|create|rename|replace|merge|grant|revoke|"
                    + "commit|rollback|call|exec|execute|handler|load|lock|unlock|shutdown|kill|"
                    + "into\\s+outfile|into\\s+dumpfile|set\\s+global)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DANGEROUS_FUNCTIONS = Pattern.compile(
            "\\b(pg_sleep|sleep|benchmark|waitfor|load_file|lo_import|lo_export|pg_read_file|"
                    + "pg_read_binary_file|pg_ls_dir|dblink|copy|xp_cmdshell|sp_executesql)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "\\b(pg_catalog|pg_authid|pg_shadow|mysql\\.user|mysql\\.db|performance_schema|sys\\.|pg_settings)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MULTI_STATEMENT = Pattern.compile(";\\s*\\S");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(--|#)[^\\n]*");
    private static final Pattern HAS_LIMIT = Pattern.compile(
            "\\b(limit\\s+\\d+|fetch\\s+first\\s+\\d+\\s+rows?\\s+only|top\\s+\\d+)\\b", Pattern.CASE_INSENSITIVE);

    /** Schema 元数据缓存（启动加载一次） */
    private volatile List<Map<String, Object>> schemaMeta;

    @Tool(name = "list_tables",
            description = "列出数据仓库中所有可查询的业务表及其业务含义、所属数据域。问数前先调用本工具了解有哪些数据。")
    public String listTables() {
        List<Map<String, Object>> metas = loadSchema();
        if (metas.isEmpty()) {
            return "未配置Schema元数据(dataquery.schema.location)，无法列出表。";
        }
        StringBuilder sb = new StringBuilder("可查询的业务表：\n");
        for (Map<String, Object> t : metas) {
            sb.append("- ").append(t.get("tableName")).append(": ").append(t.getOrDefault("tableComment", ""))
                    .append(" [域: ").append(t.getOrDefault("domain", "-")).append("]\n");
        }
        sb.append("\n调用 describe_table 查看某张表的字段结构。");
        return sb.toString();
    }

    @Tool(name = "describe_table",
            description = "查看指定表的完整字段结构（字段名、类型、业务含义、枚举取值）。编写SQL前必须先了解字段。")
    public String describeTable(
            @ToolParam(description = "表名，如 orders 或 public.orders") String tableName) {
        if (tableName == null || tableName.isBlank()) return "表名不能为空";
        String target = tableName.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> t : loadSchema()) {
            String name = String.valueOf(t.get("tableName")).toLowerCase(Locale.ROOT);
            if (name.equals(target) || name.endsWith("." + target)) {
                return JSON.toJSONString(t);
            }
        }
        return "未找到表 " + tableName + "，请先调用 list_tables 查看可用表。";
    }

    @Tool(name = "execute_readonly_sql",
            description = """
                    执行只读SELECT查询并返回数据。仅允许单条SELECT/WITH查询，自动做安全校验(禁止DDL/DML/多语句/系统表)并强制LIMIT。
                    执行失败会返回具体错误，请据错误信息修正SQL后重试，不要原样重复提交。
                    """)
    public String executeReadonlySql(
            @ToolParam(description = "要执行的SELECT查询SQL") String sql) {

        // 安全护栏校验
        String check = validate(sql);
        if (check != null) {
            log.warn("[DataQueryTool] SQL 被拦截: {}, sql={}", check, sql);
            return "【SQL安全校验未通过】" + check + "\n只允许单条SELECT查询，禁止写操作、系统表访问与危险函数。";
        }
        String safeSql = enforceLimit(cleanSql(sql));

        // dry-run 降级
        if (dbUrl == null || dbUrl.isBlank()) {
            return "【dry-run模式】SQL已通过安全校验（未配置数据源，不实际执行）：\n" + safeSql;
        }

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            conn.setReadOnly(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(queryTimeout);
                stmt.setMaxRows(maxRows);
                boolean has = stmt.execute(safeSql);
                long cost = System.currentTimeMillis() - start;
                if (!has) return "【执行完成】语句未返回结果集，耗时 " + cost + "ms";
                try (ResultSet rs = stmt.getResultSet()) {
                    return renderResultSet(rs, cost);
                }
            }
        } catch (SQLException e) {
            log.warn("[DataQueryTool] SQL执行失败: {}, sql={}", e.getMessage(), safeSql);
            return "【查询失败】" + e.getMessage() + "\nSQL: " + safeSql
                    + "\n请根据错误修正后重试（常见：列名错误、方言差异、类型不匹配、除零）。";
        }
    }

    private String renderResultSet(ResultSet rs, long cost) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) cols.add(meta.getColumnLabel(i));

        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        while (rs.next() && n < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) row.put(cols.get(i - 1), rs.getObject(i));
            rows.add(row);
            n++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【查询成功】返回 ").append(rows.size()).append(" 行, 耗时 ").append(cost).append("ms\n");
        sb.append("| ").append(String.join(" | ", cols)).append(" |\n");
        sb.append("|").append(" --- |".repeat(cols.size())).append("\n");
        int preview = Math.min(20, rows.size());
        for (int i = 0; i < preview; i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("| ");
            for (String c : cols) {
                Object v = row.get(c);
                sb.append(v == null ? "NULL" : String.valueOf(v).replace("|", "/").replace("\n", " ")).append(" | ");
            }
            sb.append("\n");
        }
        if (rows.size() > preview) sb.append("...(其余 ").append(rows.size() - preview).append(" 行省略)\n");
        return sb.toString();
    }

    /** 校验返回 null 表示通过，否则返回拒绝原因 */
    private String validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) return "SQL为空";
        String sql = cleanSql(rawSql);
        if (sql.isBlank()) return "剥离注释后SQL为空，疑似注入";
        sql = sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
        if (MULTI_STATEMENT.matcher(sql).find()) return "检测到多语句(堆叠注入)，只允许单条SELECT";
        String lower = sql.toLowerCase(Locale.ROOT);
        String prefix = lower.trim().replaceAll("^[^a-z]*", "").replaceAll("[^a-z].*$", "");
        if (!ALLOWED_PREFIX.contains(prefix)) return "语句类型'" + prefix + "'不在白名单，仅允许 " + ALLOWED_PREFIX;
        Matcher kw = DANGEROUS_KEYWORDS.matcher(sql);
        if (kw.find()) return "检测到高危关键字: " + kw.group(1);
        Matcher fn = DANGEROUS_FUNCTIONS.matcher(sql);
        if (fn.find()) return "检测到危险函数: " + fn.group(1);
        Matcher sys = SYSTEM_SCHEMA.matcher(sql);
        if (sys.find()) return "禁止访问系统表: " + sys.group(1);
        return null;
    }

    private String cleanSql(String sql) {
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        // 提取 ```sql ... ``` 代码块
        Matcher m = Pattern.compile("```(?:sql)?\\s*(.+?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) s = m.group(1);
        return s.replaceAll("\\s+", " ").trim();
    }

    private String enforceLimit(String sql) {
        return HAS_LIMIT.matcher(sql).find() ? sql : sql + " LIMIT " + defaultRowLimit;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadSchema() {
        if (schemaMeta != null) return schemaMeta;
        synchronized (this) {
            if (schemaMeta != null) return schemaMeta;
            List<Map<String, Object>> metas = new ArrayList<>();
            try {
                ClassPathResource res = new ClassPathResource("schema-metadata.json");
                if (res.exists()) {
                    try (InputStream in = res.getInputStream()) {
                        String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        metas = JSON.parseObject(json, List.class);
                    }
                } else {
                    log.warn("[DataQueryTool] schema-metadata.json 不存在");
                }
            } catch (Exception e) {
                log.error("[DataQueryTool] Schema加载失败: {}", e.getMessage());
            }
            schemaMeta = metas;
            log.info("[DataQueryTool] 加载Schema {} 张表", metas.size());
            return schemaMeta;
        }
    }
}
