package com.yt.agent.eval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yt.agent.memory.SemanticMemoryStore;
import com.yt.agent.nl2sql.SchemaLinker;
import com.yt.agent.nl2sql.SqlExecutor;
import com.yt.agent.nl2sql.SqlSafetyGuard;
import com.yt.agent.platform.llm.LlmGateway;
import com.yt.agent.platform.llm.ModelRole;
import com.yt.agent.platform.llm.ModelUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NL2SQL 端到端评测服务。
 *
 * <p>为什么要做这个：{@link Nl2SqlEvaluator} 只负责"给两条 SQL 比对结果集"，
 * 但真实问题是"模型看了 Schema 之后能不能写对"。中间缺了"生成 SQL"这一步，
 * 导致评测器实际上没法跑——这就是典型的"骨架有了、闭环没通"。</p>
 *
 * <p>本类补齐闭环：<b>问题 → Schema 召回 → 模型生成 SQL → 安全护栏 → 执行 → 与标准 SQL 比对结果集</b>。
 * 输出 Execution Accuracy（主指标）、Valid SQL Rate、Exact Match，并保留每条样本的生成 SQL，
 * 便于人工归因（是召回错、还是 SQL 写错、还是口径理解错）。</p>
 *
 * <p>工程约束：
 * <ul>
 *   <li>评测必须有可用数据源，否则 EX 无从谈起（dry-run 下直接拒绝并说明原因）；</li>
 *   <li>每条样本独立失败，不因单条异常中断整轮评测；</li>
 *   <li>评测是重操作（N 次模型调用 + 2N 次查询），因此限制样本数并串行执行，避免打爆库。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class Nl2SqlEvalService {

    private static final String GOLDEN_SET = "nl2sql/eval-golden-set.json";
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:sql)?\\s*(.+?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_START = Pattern.compile("(?is)\\b(select|with)\\b.+");

    private final SchemaLinker schemaLinker;
    private final SqlExecutor sqlExecutor;
    private final SqlSafetyGuard sqlSafetyGuard;
    private final SemanticMemoryStore semanticMemoryStore;
    private final LlmGateway llmGateway;
    private final Nl2SqlEvaluator evaluator;

    public Nl2SqlEvalService(SchemaLinker schemaLinker, SqlExecutor sqlExecutor,
                             SqlSafetyGuard sqlSafetyGuard, SemanticMemoryStore semanticMemoryStore,
                             LlmGateway llmGateway) {
        this.schemaLinker = schemaLinker;
        this.sqlExecutor = sqlExecutor;
        this.sqlSafetyGuard = sqlSafetyGuard;
        this.semanticMemoryStore = semanticMemoryStore;
        this.llmGateway = llmGateway;
        this.evaluator = new Nl2SqlEvaluator(sqlExecutor);
    }

    /**
     * 跑一轮评测。
     *
     * @param maxCases 最多评测多少条（<=0 表示全量）
     */
    public Map<String, Object> run(int maxCases) {
        if (!sqlExecutor.isAvailable()) {
            throw new IllegalStateException(
                    "评测需要可用的问数数据源。请配置 nl2sql.datasource.url（只读账号）后重试；"
                    + "当前为 dry-run 模式，无法比对执行结果。");
        }
        List<GoldenCase> golden = loadGoldenSet();
        if (golden.isEmpty()) {
            throw new IllegalStateException("未找到评测集 " + GOLDEN_SET + " 或内容为空");
        }
        int limit = maxCases > 0 ? Math.min(maxCases, golden.size()) : golden.size();
        long start = System.currentTimeMillis();

        List<Nl2SqlEvaluator.EvalCase> cases = new ArrayList<>(limit);
        List<Map<String, Object>> details = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            GoldenCase item = golden.get(i);
            String predSql = null;
            String error = null;
            long caseStart = System.currentTimeMillis();
            try {
                String raw = generateSql(item.question());
                SqlSafetyGuard.GuardResult guard = sqlSafetyGuard.validate(raw);
                if (!guard.isPassed()) {
                    error = "生成的SQL未通过安全校验: " + guard.getReason();
                    predSql = raw;
                } else {
                    predSql = guard.getSafeSql();
                }
            } catch (ModelUnavailableException e) {
                throw e;
            } catch (Exception e) {
                error = "SQL生成失败: " + e.getMessage();
                predSql = "SELECT 1";
            }
            cases.add(Nl2SqlEvaluator.EvalCase.builder()
                    .question(item.question())
                    .category(item.category())
                    .goldSql(item.goldSql())
                    .predSql(predSql == null ? "SELECT 1" : predSql)
                    .build());

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("question", item.question());
            detail.put("category", item.category());
            detail.put("goldSql", item.goldSql());
            detail.put("predSql", predSql);
            detail.put("generationError", error);
            detail.put("costMs", System.currentTimeMillis() - caseStart);
            details.add(detail);
        }

        Nl2SqlEvaluator.EvalReport report = evaluator.evaluateAll(cases);
        // 把生成阶段的错误合并进结果，便于一眼看出是"没生成出来"还是"生成错"
        for (int i = 0; i < report.getCaseResults().size() && i < details.size(); i++) {
            Object generationError = details.get(i).get("generationError");
            if (generationError != null) {
                report.getCaseResults().get(i).setError(String.valueOf(generationError));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", llmGateway.describe(ModelRole.REWRITER));
        out.put("durationMs", System.currentTimeMillis() - start);
        out.put("summary", report.summary());
        out.put("totalCases", report.getTotalCases());
        out.put("validCases", report.getValidCases());
        out.put("executionAccuracy", report.getExecutionAccuracy());
        out.put("validSqlRate", report.getValidSqlRate());
        out.put("exactMatchRate", report.getExactMatchRate());
        out.put("details", details);
        out.put("caseResults", report.getCaseResults());
        log.info("[Nl2SqlEval] 完成: EX={} valid={} 耗时={}ms",
                report.getExecutionAccuracy(), report.getValidSqlRate(), out.get("durationMs"));
        return out;
    }

    /**
     * 生成 SQL：给模型"召回后的精简 Schema + 业务口径"，要求只输出 SQL。
     *
     * <p>为什么用 REWRITER 角色而不是 EXECUTOR：评测追求可复现与低方差，
     * 低温度、低成本的小模型更合适；生产链路可以换更强的模型，
     * 但评测基线应保持稳定，否则指标波动无法归因。</p>
     */
    private String generateSql(String question) {
        List<SchemaLinker.ScoredSchema> linked = schemaLinker.link(question);
        StringBuilder schemaText = new StringBuilder();
        for (SchemaLinker.ScoredSchema scored : linked) {
            schemaText.append(scored.getSchema().toPromptText(false)).append("\n");
        }
        String memory = semanticMemoryStore.retrieveForPrompt(question);
        String system = """
                你是资深数据分析师，精通 PostgreSQL。
                根据给定的表结构，把用户问题翻译为一条可直接执行的 SELECT 语句。
                要求：
                1. 只能使用给定 Schema 中存在的表名与字段名，注意 schema 前缀（如 public.orders）；
                2. 只输出 SQL，不要解释、不要 Markdown 代码块以外的内容；
                3. 时间条件使用 CURRENT_DATE / date_trunc 表达；
                4. 比率类计算必须用 NULLIF 防止除零。
                """ + (memory.isBlank() ? "" : "\n" + memory);
        String user = "【表结构】\n" + schemaText + "\n【问题】\n" + question;

        String content = llmGateway.chat(ModelRole.REWRITER)
                .prompt()
                .system(system)
                .user(user)
                .call()
                .content();
        return extractSql(content);
    }

    /** 从模型输出中提取 SQL：优先代码块，其次第一个 SELECT/WITH 起始的片段 */
    String extractSql(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型未返回内容");
        }
        Matcher fence = CODE_FENCE.matcher(content);
        if (fence.find()) {
            return fence.group(1).trim();
        }
        Matcher m = SQL_START.matcher(content);
        if (m.find()) {
            return m.group().trim();
        }
        return content.trim();
    }

    private List<GoldenCase> loadGoldenSet() {
        List<GoldenCase> cases = new ArrayList<>();
        try {
            ClassPathResource resource = new ClassPathResource(GOLDEN_SET);
            if (!resource.exists()) {
                return cases;
            }
            try (InputStream in = resource.getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JSONArray array = JSON.parseArray(json);
                for (int i = 0; i < array.size(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    cases.add(new GoldenCase(
                            item.getString("question"),
                            item.getString("category"),
                            item.getString("goldSql")));
                }
            }
        } catch (Exception e) {
            log.error("[Nl2SqlEval] 读取评测集失败: {}", e.getMessage());
        }
        return cases;
    }

    public record GoldenCase(String question, String category, String goldSql) {
    }
}
