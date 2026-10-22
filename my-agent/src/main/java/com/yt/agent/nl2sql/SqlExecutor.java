package com.yt.agent.nl2sql;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行器（问数链路"最后一公里"）
 *
 * <p>安全设计（与 SqlSafetyGuard 构成纵深防御）：
 * <ol>
 *   <li><b>只读连接</b>：connection.setReadOnly(true)，数据库驱动层面拒绝写操作</li>
 *   <li><b>查询超时</b>：statement.setQueryTimeout，防慢 SQL 拖垮库与 Agent 主循环</li>
 *   <li><b>行数截断</b>：maxRows 双保险（护栏已强制 LIMIT，这里防驱动不生效）</li>
 *   <li><b>结果压缩</b>：列值超长截断、总大小上限，防止一个大字段打爆 LLM 上下文</li>
 *   <li><b>错误结构化</b>：SQLException 转成带 errorCode 的失败结果返回，
 *       供 Self-Correction 环节把错误信息喂回模型修正 SQL（而不是抛异常中断任务）</li>
 * </ol>
 * </p>
 *
 * <p>生产建议：数据源应使用<b>独立的只读账号</b>（仅 SELECT 权限 + 行级安全策略），
 * 而不是依赖应用层的 setReadOnly——数据库账号权限才是最终防线，应用层校验可能被绕过。</p>
 */
@Slf4j
public class SqlExecutor {

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;
    private final int maxRows;
    /** 单个字段值的最大长度（超长截断，如大文本字段） */
    private final int maxCellLength;

    public SqlExecutor(DataSource dataSource) {
        this(dataSource, 30, 500, 2000);
    }

    /**
     * @param dataSource 允许为 null（未配置问数数据源），此时 isAvailable()=false，
     *                   execute() 返回 DRY_RUN 结果，应用照常启动——Null Object 模式，
     *                   避免 @Bean 返回 null 导致的注入失败
     */
    public SqlExecutor(DataSource dataSource, int queryTimeoutSeconds, int maxRows, int maxCellLength) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = queryTimeoutSeconds > 0 ? queryTimeoutSeconds : 30;
        this.maxRows = maxRows > 0 ? maxRows : 500;
        this.maxCellLength = maxCellLength > 0 ? maxCellLength : 2000;
    }

    /** 数据源是否可用（false 时为 dry-run 模式） */
    public boolean isAvailable() {
        return dataSource != null;
    }

    /**
     * 执行已通过安全校验的 SQL。
     * 调用方必须先经过 SqlSafetyGuard.validate()，本方法不重复做语法级校验，
     * 但依然使用只读连接与超时做执行期兜底。
     *
     * @param safeSql 护栏校验后的安全 SQL
     * @return 结构化执行结果（成功含列名与数据行，失败含错误码与可读信息）
     */
    public ExecuteResult execute(String safeSql) {
        long start = System.currentTimeMillis();
        // dry-run 降级：未配置数据源时不执行，返回明确的降级结果
        if (dataSource == null) {
            return ExecuteResult.builder()
                    .success(false)
                    .dryRun(true)
                    .errorCode("DRY_RUN")
                    .errorMessage("当前环境未配置问数数据源(nl2sql.datasource.url)，SQL已通过安全校验但未实际执行")
                    .sql(safeSql)
                    .costMs(System.currentTimeMillis() - start)
                    .build();
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);   // 驱动层只读兜底
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(queryTimeoutSeconds);
                stmt.setMaxRows(maxRows);

                boolean hasResultSet = stmt.execute(safeSql);
                long cost = System.currentTimeMillis() - start;

                if (!hasResultSet) {
                    return ExecuteResult.builder()
                            .success(false)
                            .errorCode("NO_RESULT_SET")
                            .errorMessage("语句未返回结果集，问数场景只允许 SELECT 查询")
                            .sql(safeSql)
                            .costMs(cost)
                            .build();
                }

                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();

                    List<String> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }

                    List<Map<String, Object>> rows = new ArrayList<>();
                    int rowCount = 0;
                    while (rs.next() && rowCount < maxRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = rs.getObject(i);
                            row.put(columns.get(i - 1), truncateValue(value));
                        }
                        rows.add(row);
                        rowCount++;
                    }
                    boolean truncatedByMaxRows = rs.next();  // 还有下一行说明被 maxRows 截断

                    return ExecuteResult.builder()
                            .success(true)
                            .sql(safeSql)
                            .columns(columns)
                            .rows(rows)
                            .rowCount(rows.size())
                            .truncated(truncatedByMaxRows)
                            .costMs(cost)
                            .build();
                }
            }
        } catch (SQLException e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[SqlExecutor] SQL 执行失败, errorCode={}, sqlState={}, msg={}, sql={}",
                    e.getErrorCode(), e.getSQLState(), e.getMessage(), safeSql);
            // 错误信息结构化返回：这是 Self-Correction 的输入，模型靠它定位是列名错、类型错还是权限错
            return ExecuteResult.builder()
                    .success(false)
                    .errorCode("SQL_ERROR")
                    .sqlState(e.getSQLState())
                    .errorMessage(buildReadableError(e))
                    .sql(safeSql)
                    .costMs(cost)
                    .build();
        } catch (Exception e) {
            log.error("[SqlExecutor] SQL 执行异常: {}", e.getMessage(), e);
            return ExecuteResult.builder()
                    .success(false)
                    .errorCode("EXECUTOR_ERROR")
                    .errorMessage("执行器异常: " + e.getMessage())
                    .sql(safeSql)
                    .costMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    /**
     * 把 SQLException 翻译成对 LLM 友好、可指导修正的错误描述。
     * 模型拿到的 Observation 越结构化，自纠错成功率越高。
     */
    private String buildReadableError(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        StringBuilder sb = new StringBuilder();
        sb.append("SQL执行失败[").append(state).append("]: ").append(msg);

        String lower = msg.toLowerCase();
        if (lower.contains("does not exist") || lower.contains("unknown column") || lower.contains("无效")) {
            sb.append(" | 修正建议: 表名或列名不存在，请核对召回的Schema中的准确名称，注意大小写与schema前缀");
        } else if (lower.contains("syntax")) {
            sb.append(" | 修正建议: SQL语法错误，请检查方言差异(如日期函数、字符串拼接、LIMIT语法)");
        } else if (lower.contains("timeout") || lower.contains("canceling statement")) {
            sb.append(" | 修正建议: 查询超时，请缩小时间范围、增加过滤条件或减少关联表");
        } else if (lower.contains("permission") || lower.contains("denied") || lower.contains("read-only")) {
            sb.append(" | 修正建议: 权限不足或试图执行写操作，只允许SELECT查询");
        } else if (lower.contains("ambiguous")) {
            sb.append(" | 修正建议: 列名存在歧义，多表关联时请使用 表名.列名 限定");
        } else if (lower.contains("division by zero") || lower.contains("divide")) {
            sb.append(" | 修正建议: 除零错误，请使用 NULLIF(分母,0) 保护");
        } else if (lower.contains("type") || lower.contains("cast") || lower.contains("invalid input syntax")) {
            sb.append(" | 修正建议: 数据类型不匹配，请显式CAST或检查比较值的类型(如字符串日期 vs date类型)");
        }
        return sb.toString();
    }

    /** 单元格值截断，防大字段撑爆上下文 */
    private Object truncateValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s && s.length() > maxCellLength) {
            return s.substring(0, maxCellLength) + "...[已截断]";
        }
        return value;
    }

    /**
     * 渲染成注入 LLM 上下文的结果文本。
     * 采用 Markdown 表格（模型最易读），行多时只给前 N 行 + 统计摘要。
     */
    public String renderForLlm(ExecuteResult result, int previewRows) {
        if (Boolean.TRUE.equals(result.getDryRun())) {
            return "【dry-run模式】SQL已通过安全校验（当前环境未配置问数数据源，不实际执行）：\n" + result.getSql()
                    + "\n请基于该SQL的语义向用户说明查询逻辑，并注明当前为演示模式未返回真实数据。";
        }
        if (!result.isSuccess()) {
            return String.format("【查询失败】%s\n原始SQL: %s", result.getErrorMessage(), result.getSql());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【查询成功】返回 %d 行%s, 耗时 %dms\n",
                result.getRowCount(),
                Boolean.TRUE.equals(result.getTruncated()) ? "(已截断，实际更多)" : "",
                result.getCostMs()));
        sb.append("执行的SQL: ").append(result.getSql()).append("\n");

        List<String> cols = result.getColumns();
        sb.append("| ").append(String.join(" | ", cols)).append(" |\n");
        sb.append("|").append(" --- |".repeat(cols.size())).append("\n");

        int preview = Math.min(previewRows, result.getRows().size());
        for (int i = 0; i < preview; i++) {
            Map<String, Object> row = result.getRows().get(i);
            sb.append("| ");
            for (String c : cols) {
                Object v = row.get(c);
                sb.append(v == null ? "NULL" : String.valueOf(v).replace("|", "\\|").replace("\n", " ")).append(" | ");
            }
            sb.append("\n");
        }
        if (result.getRows().size() > preview) {
            sb.append(String.format("...(其余 %d 行省略，如需完整数据请缩小查询范围或使用聚合)\n",
                    result.getRows().size() - preview));
        }
        return sb.toString();
    }

    /**
     * SQL 执行结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecuteResult {
        private boolean success;
        private String sql;
        /** 列名（保持查询顺序） */
        private List<String> columns;
        /** 数据行（LinkedHashMap 保持列序） */
        private List<Map<String, Object>> rows;
        private Integer rowCount;
        /** 是否被 maxRows 截断 */
        private Boolean truncated;
        private String errorCode;
        private String sqlState;
        private String errorMessage;
        private Long costMs;
        /** dry-run 模式标记：SQL 已通过安全校验但未实际执行 */
        private Boolean dryRun;

        public String toJson() {
            return JSON.toJSONString(this);
        }
    }
}
