package com.yt.agent.eval;

import com.yt.agent.nl2sql.SqlExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * NL2SQL 评测器 —— 回答"你怎么衡量问数 Agent 准不准"
 *
 * <p>为什么不能只看模型自评或人工抽检：NL2SQL 有客观标准答案（SQL 执行结果），
 * 必须用可量化的自动指标做回归。业界主流指标（本类全部实现）：</p>
 *
 * <ol>
 *   <li><b>Execution Accuracy (EX)</b>：主指标。比较「模型生成SQL的执行结果集」与
 *       「标准SQL的执行结果集」是否一致（忽略行序）。这是 Spider/BIRD 等权威榜单的核心指标，
 *       比"SQL文本相似度"可靠得多——两条写法不同的SQL只要结果对就算对；</li>
 *   <li><b>Exact Match (EM)</b>：SQL规范化后文本比对，作为辅助指标（过严，列序/别名不同即判错，仅参考）；</li>
 *   <li><b>Valid SQL Rate</b>：生成的SQL能否通过安全校验+成功执行（语法正确率），
 *       与EX区分开——能执行≠结果对；</li>
 *   <li><b>组件级诊断</b>：分别统计召回准确率(选对表)、SQL执行成功率，定位错误发生在链路哪一环。</li>
 * </ol>
 * </p>
 *
 * <p>结果集比对采用「行集合 + 单元格归一化」：数值按 double 容差比较（防 1.0 vs 1 误判），
 * 字符串 trim + 大小写归一，行顺序无关（用 multiset 语义，重复行计数敏感）。</p>
 */
@Slf4j
public class Nl2SqlEvaluator {

    private final SqlExecutor sqlExecutor;

    public Nl2SqlEvaluator(SqlExecutor sqlExecutor) {
        this.sqlExecutor = sqlExecutor;
    }

    /**
     * 评测单条样本：用标准SQL和模型SQL分别查库，比对结果集。
     *
     * @param goldSql   标准答案SQL
     * @param predSql   模型生成的SQL
     * @return 评测结果
     */
    public CaseResult evaluateCase(String goldSql, String predSql) {
        if (sqlExecutor == null || !sqlExecutor.isAvailable()) {
            return CaseResult.builder()
                    .executionMatch(false)
                    .error("评测需要可用的数据源（dry-run模式无法比对执行结果）")
                    .build();
        }

        SqlExecutor.ExecuteResult goldResult = sqlExecutor.execute(goldSql);
        SqlExecutor.ExecuteResult predResult = sqlExecutor.execute(predSql);

        CaseResult.CaseResultBuilder builder = CaseResult.builder()
                .goldSql(goldSql)
                .predSql(predSql)
                .goldExecutable(goldResult.isSuccess())
                .predExecutable(predResult.isSuccess());

        // 模型SQL执行失败：EX 直接判错
        if (!predResult.isSuccess()) {
            return builder.executionMatch(false)
                    .error("模型SQL执行失败: " + predResult.getErrorMessage())
                    .build();
        }
        // 标准SQL执行失败：样本本身有问题，标记为无效样本（不计入准确率分母）
        if (!goldResult.isSuccess()) {
            return builder.executionMatch(false)
                    .invalidSample(true)
                    .error("标准SQL执行失败(样本问题): " + goldResult.getErrorMessage())
                    .build();
        }

        boolean match = compareResultSets(goldResult.getRows(), predResult.getRows());
        return builder.executionMatch(match)
                .exactMatch(normalizeSql(goldSql).equals(normalizeSql(predSql)))
                .goldRowCount(goldResult.getRowCount())
                .predRowCount(predResult.getRowCount())
                .error(match ? null : "结果集不一致")
                .build();
    }

    /**
     * 结果集比对：行顺序无关的 multiset 比较，单元格做归一化。
     */
    boolean compareResultSets(List<Map<String, Object>> gold, List<Map<String, Object>> pred) {
        if (gold == null && pred == null) return true;
        if (gold == null || pred == null) return false;
        if (gold.size() != pred.size()) {
            log.debug("行数不一致: gold={}, pred={}", gold.size(), pred.size());
            return false;
        }
        // 每行归一化为可比较的字符串签名，用 multiset（计数）比较，忽略行序
        List<String> goldSigs = gold.stream().map(this::rowSignature).sorted().toList();
        List<String> predSigs = pred.stream().map(this::rowSignature).sorted().toList();
        return goldSigs.equals(predSigs);
    }

    /** 行签名：按值（而非列名）排序拼接，忽略列顺序与列名差异，只看数据 */
    private String rowSignature(Map<String, Object> row) {
        List<String> cells = new ArrayList<>();
        for (Object v : row.values()) {
            cells.add(normalizeCell(v));
        }
        cells.sort(String::compareTo);   // 列序无关
        return String.join("\u0001", cells);
    }

    /** 单元格归一化：数值按 double 容差、字符串 trim+小写、null 统一 */
    private String normalizeCell(Object v) {
        if (v == null) return "<NULL>";
        if (v instanceof Number n) {
            // 数值统一为 double 表示，消除 1 vs 1.0 vs BigDecimal 差异
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.format("%.6f", d);
        }
        if (v instanceof Boolean b) return b ? "true" : "false";
        return v.toString().trim().toLowerCase();
    }

    /** SQL 规范化：去注释、压空白、转小写、去尾分号（用于 Exact Match 辅助判断） */
    String normalizeSql(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("--[^\\n]*", " ")
                .replaceAll("\\s+", " ")
                .trim().toLowerCase()
                .replaceAll(";$", "");
    }

    /**
     * 批量评测，汇总各项指标。
     */
    public EvalReport evaluateAll(List<EvalCase> cases) {
        List<CaseResult> results = new ArrayList<>();
        int validCount = 0, exHit = 0, executable = 0, exactMatch = 0;

        for (EvalCase c : cases) {
            CaseResult r = evaluateCase(c.getGoldSql(), c.getPredSql());
            r.setQuestion(c.getQuestion());
            r.setCategory(c.getCategory());
            results.add(r);

            if (Boolean.TRUE.equals(r.getInvalidSample())) {
                continue;  // 无效样本不计入分母
            }
            validCount++;
            if (r.isExecutionMatch()) exHit++;
            if (Boolean.TRUE.equals(r.getPredExecutable())) executable++;
            if (Boolean.TRUE.equals(r.getExactMatch())) exactMatch++;
        }

        return EvalReport.builder()
                .totalCases(cases.size())
                .validCases(validCount)
                .executionAccuracy(validCount == 0 ? 0.0 : (double) exHit / validCount)
                .validSqlRate(validCount == 0 ? 0.0 : (double) executable / validCount)
                .exactMatchRate(validCount == 0 ? 0.0 : (double) exactMatch / validCount)
                .exHitCount(exHit)
                .caseResults(results)
                .build();
    }

    /** 评测样本 */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EvalCase {
        private String question;     // 自然语言问题
        private String goldSql;      // 标准SQL
        private String predSql;      // 模型生成SQL
        private String category;     // 分类：聚合/关联/时间/排名 等，用于分维度统计
    }

    /** 单条评测结果 */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CaseResult {
        private String question;
        private String category;
        private String goldSql;
        private String predSql;
        private boolean executionMatch;      // EX 命中
        private Boolean exactMatch;          // SQL文本规范化后一致
        private Boolean goldExecutable;
        private Boolean predExecutable;
        private Integer goldRowCount;
        private Integer predRowCount;
        private Boolean invalidSample;       // 标准SQL本身跑不通，样本无效
        private String error;
    }

    /** 评测报告 */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EvalReport {
        private int totalCases;
        private int validCases;
        private int exHitCount;
        private double executionAccuracy;   // 主指标
        private double validSqlRate;
        private double exactMatchRate;
        private List<CaseResult> caseResults;

        public String summary() {
            return String.format("""
                    ===== NL2SQL 评测报告 =====
                    样本总数: %d (有效 %d)
                    Execution Accuracy (EX): %.1f%% (%d/%d)  ← 主指标
                    Valid SQL Rate:          %.1f%%
                    Exact Match Rate:        %.1f%% (辅助参考)
                    """, totalCases, validCases,
                    executionAccuracy * 100, exHitCount, validCases,
                    validSqlRate * 100, exactMatchRate * 100);
        }
    }
}
