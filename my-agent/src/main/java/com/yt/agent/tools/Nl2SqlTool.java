package com.yt.agent.tools;

import com.yt.agent.nl2sql.SchemaLinker;
import com.yt.agent.nl2sql.SqlExecutor;
import com.yt.agent.nl2sql.SqlSafetyGuard;
import com.yt.agent.nl2sql.SqlSelfCorrector;
import com.yt.agent.nl2sql.TableSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 问数（NL2SQL）工具集 —— 暴露给 LLM 的两个 Function Calling 工具
 *
 * <p>工具拆分设计：为什么是「召回 Schema」和「执行 SQL」两个工具，而不是一个 nl2sql 大工具？
 * <ol>
 *   <li><b>SQL 由模型生成而非工具内部生成</b>：工具只提供 Schema 上下文和执行能力，
 *       SQL 生成留在模型的推理中，这样模型的思考链（为什么这样写 SQL）可见、可干预、可评测；</li>
 *   <li><b>失败反馈闭环</b>：execute_sql 失败时返回结构化错误，模型在下一轮 think 中
 *       基于错误信息修正 SQL 重试（Self-Correction），这是 Agent 相比固定 Pipeline 的核心优势；</li>
 *   <li><b>职责单一</b>：召回错了模型可以换关键词重新召回，SQL 错了只需重生成 SQL，
 *       错误定位粒度更细，重试成本更低。</li>
 * </ol>
 * </p>
 *
 * <p>标准问数流程：retrieve_schema(问题) → 模型基于召回的 Schema 写 SQL →
 * execute_sql(SQL) → 失败则根据错误修正重试（最多N次）→ 成功后基于数据回答。</p>
 */
@Slf4j
public class Nl2SqlTool {

    private final SchemaLinker schemaLinker;
    private final SqlSafetyGuard sqlSafetyGuard;
    private final SqlExecutor sqlExecutor;
    private final SqlSelfCorrector selfCorrector;

    /** LLM 上下文中的结果预览行数 */
    private static final int PREVIEW_ROWS = 20;

    public Nl2SqlTool(SchemaLinker schemaLinker, SqlSafetyGuard sqlSafetyGuard,
                      SqlExecutor sqlExecutor, SqlSelfCorrector selfCorrector) {
        this.schemaLinker = schemaLinker;
        this.sqlSafetyGuard = sqlSafetyGuard;
        this.sqlExecutor = sqlExecutor;
        this.selfCorrector = selfCorrector;
    }

    @Tool(name = "retrieve_schema",
            description = """
                    问数第一步：根据用户的自然语言问题，从数据仓库中召回相关的表和字段结构(Schema)。
                    返回最相关的表结构，包含表名、字段名、字段类型、业务含义、枚举取值说明。
                    任何需要查询数据库数据的问题（如统计、汇总、排名、趋势、明细查询），都必须先调用本工具获取Schema，
                    再基于返回的准确表名字段名编写SQL，严禁凭空猜测表名或字段名。
                    如果召回结果与问题不相关，可以用更精确的业务关键词重新调用。
                    """)
    public String retrieveSchema(
            @ToolParam(description = "用户的原始问题或提炼出的查询意图关键词，如'上个月华东区各渠道销售额'") String query) {

        try (com.yt.agent.eval.AgentTraceCollector.StepSpan span =
                     com.yt.agent.eval.AgentTraceCollector.step("schema_recall", "召回query: " + query)) {
            List<SchemaLinker.ScoredSchema> linked = schemaLinker.link(query);
            if (linked.isEmpty()) {
                span.result("未召回到相关表", false);
                return "未召回到与问题相关的数据表。可能原因：问题与现有数据集无关，或关键词不匹配。"
                        + "建议：换用更贴近业务的关键词（如指标名、维度名）重试；若仍无结果，请告知用户当前数据集不支持该问题。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("召回 ").append(linked.size()).append(" 张相关表，请基于以下真实Schema编写SQL（严禁使用Schema之外的表名/字段名）：\n\n");
            for (SchemaLinker.ScoredSchema ss : linked) {
                sb.append(ss.getSchema().toPromptText(false)).append("\n");
                sb.append("匹配依据: ").append(ss.getMatchedColumns().keySet()).append("\n\n");
            }
            sb.append("【SQL编写要求】1) 只能写单条SELECT语句；2) 必须带WHERE时间/业务过滤条件；")
                    .append("3) 聚合查询需正确使用GROUP BY；4) 涉及比率用NULLIF防除零；5) 结果默认最多返回1000行。");
            span.result("召回表: " + linked.stream().map(SchemaLinker.ScoredSchema::getTableName)
                    .collect(Collectors.joining(",")), true);
            return sb.toString();
        }
    }

    @Tool(name = "execute_sql",
            description = """
                    问数第二步：执行基于召回Schema编写的SELECT查询SQL，返回查询结果数据。
                    只允许单条SELECT/WITH查询语句，禁止任何DDL/DML操作，系统会自动做安全校验并强制LIMIT。
                    执行失败时会返回具体的错误原因和修正建议，请根据错误信息修正SQL后重试（最多重试3次），
                    不要原样重复执行失败的SQL。
                    """)
    public String executeSql(
            @ToolParam(description = "要执行的SELECT查询SQL语句，表名字段名必须来自retrieve_schema的返回结果") String sql) {

        try (com.yt.agent.eval.AgentTraceCollector.StepSpan span =
                     com.yt.agent.eval.AgentTraceCollector.step("sql_exec", sql)) {

            // 1. 安全护栏校验（白名单 + 黑名单 + 表级权限 + 强制LIMIT）
            SqlSafetyGuard.GuardResult guard = sqlSafetyGuard.validate(sql);
            if (!guard.isPassed()) {
                log.warn("[Nl2SqlTool] SQL 被安全护栏拦截: {}, sql={}", guard.getReason(), sql);
                com.yt.agent.eval.AgentTraceCollector.record("guard_reject", guard.getReason(), false);
                span.result("安全拦截: " + guard.getReason(), false);
                return "【SQL安全校验未通过】" + guard.getReason()
                        + "\n请修正SQL后重试：只能使用单条SELECT语句，只能访问已召回的授权表，禁止任何写操作、系统表访问与危险函数。";
            }

            String safeSql = guard.getSafeSql();

            // 2. 规则级自纠错（L1）：方言函数、除零保护、标识符引号等确定性修复
            SqlSelfCorrector.CorrectionResult correction = selfCorrector.ruleCorrect(safeSql);
            if (correction.hasFixes()) {
                log.info("[Nl2SqlTool] 规则纠错生效: {}", correction.getFixes());
                com.yt.agent.eval.AgentTraceCollector.record("sql_correct", String.join(";", correction.getFixes()), true);
                safeSql = correction.getSql();
            }

            // 3. 执行（未配置数据源时 executor 内部自动 dry-run 降级）
            SqlExecutor.ExecuteResult result = sqlExecutor.execute(safeSql);
            String rendered = sqlExecutor.renderForLlm(result, PREVIEW_ROWS);
            if (correction.hasFixes()) {
                rendered = "【自动修正】" + String.join("; ", correction.getFixes()) + "\n" + rendered;
            }
            span.result(result.isSuccess() || Boolean.TRUE.equals(result.getDryRun())
                    ? ("rows=" + result.getRowCount()) : result.getErrorMessage(), result.isSuccess());
            return rendered;
        }
    }

    /** 供 Skill / Prompt 组装用的 Schema 概览（轻量，只含表名和注释） */
    public String getSchemaOverview() {
        return schemaLinker.getAllSchemas().stream()
                .map(s -> "- " + s.getTableName() + ": " + (s.getTableComment() == null ? "" : s.getTableComment()))
                .collect(Collectors.joining("\n"));
    }
}
