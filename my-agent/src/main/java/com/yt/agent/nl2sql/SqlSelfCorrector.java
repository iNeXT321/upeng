package com.yt.agent.nl2sql;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 自纠错协调器（Self-Correction / Reflexion）
 *
 * <p>解决的问题：NL2SQL 首轮生成正确率通常只有 60~75%（列名错、方言错、类型错、语义错）。
 * 资深 Agent 与玩具 Demo 的分水岭就在这里——错了之后怎么办。</p>
 *
 * <p>三层纠错策略（成本从低到高，逐层升级）：
 * <ol>
 *   <li><b>L1 规则纠错</b>（本类，零成本零延迟）：针对高频确定性错误的自动修复，
 *       如 MySQL/PG 方言函数差异、除零保护、缺失 LIMIT。能规则解决的不消耗模型调用；</li>
 *   <li><b>L2 模型反思纠错</b>（由 Agent 的 think-act 循环承担）：执行失败时，
 *       把「结构化错误 + 修正建议 + 原SQL + 召回Schema」作为 Observation 回注上下文，
 *       模型在下一轮基于真实报错修正（见 SqlExecutor.buildReadableError 的修正建议注入）；</li>
 *   <li><b>L3 执行验证纠错</b>（execution-guided）：对语义可疑的 SQL 先以 LIMIT 0 / EXPLAIN
 *       试执行验证语法与列名，通过后再真实执行，避免污染结果。</li>
 * </ol>
 * </p>
 *
 * <p>重试预算控制：每个查询最多 maxRetry 次，重试历史全程记录（供评测与 trace），
 * 相同 SQL 不重复执行（防模型原地打转烧 token）。</p>
 */
@Slf4j
public class SqlSelfCorrector {

    private final int maxRetry;
    private final SqlSafetyGuard guard;
    /** 目标库方言：mysql / postgresql（决定反引号等规则是否生效） */
    private final String dialect;

    public SqlSelfCorrector(SqlSafetyGuard guard) {
        this(guard, 3, "postgresql");
    }

    public SqlSelfCorrector(SqlSafetyGuard guard, int maxRetry) {
        this(guard, maxRetry, "postgresql");
    }

    public SqlSelfCorrector(SqlSafetyGuard guard, int maxRetry, String dialect) {
        this.guard = guard;
        this.maxRetry = maxRetry > 0 ? maxRetry : 3;
        this.dialect = dialect == null ? "postgresql" : dialect.toLowerCase();
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    /**
     * L1 规则纠错：修复高频确定性错误。
     * 返回修复后的 SQL 与修复说明（说明会进入 Observation，让模型知道被改了什么——透明可解释）。
     */
    public CorrectionResult ruleCorrect(String sql) {
        if (sql == null || sql.isBlank()) {
            return CorrectionResult.of(sql, List.of());
        }
        List<String> fixes = new ArrayList<>();
        String fixed = sql;

        // 1) 除零保护：比率计算中 / xxx 改写为 / NULLIF(xxx, 0)
        //    只处理明显的除法语义（除以列名或聚合函数），避免误伤注释或字符串
        String beforeDiv = fixed;
        fixed = fixed.replaceAll("(?i)/\\s*(count|sum|avg)\\s*\\(([^()]+)\\)", "/ NULLIF($1($2), 0)");
        if (!fixed.equals(beforeDiv) && !fixed.toLowerCase().contains("nullif")) {
            fixes.add("除法已加 NULLIF 防除零");
        } else if (!fixed.equals(beforeDiv)) {
            fixes.add("比率分母已包裹 NULLIF(x,0) 防止除零错误");
        }

        // 2) 方言差异：MySQL 的 IFNULL/DATE_FORMAT/STR_TO_DATE → PostgreSQL 的 COALESCE/TO_CHAR/TO_DATE
        if (fixed.toLowerCase().contains("ifnull(")) {
            fixed = fixed.replaceAll("(?i)\\bifnull\\s*\\(", "COALESCE(");
            fixes.add("IFNULL → COALESCE (PG方言)");
        }
        if (fixed.toLowerCase().contains("date_format(")) {
            fixes.add("提示: DATE_FORMAT 为MySQL函数, PG需使用 TO_CHAR, 请确认目标库方言");
        }

        // 3) SELECT * 治理：宽表 select * 会撑爆上下文，提示改为明确列
        if (fixed.toLowerCase().matches("(?s)^\\s*select\\s+\\*\\s+from.*")) {
            fixes.add("警告: 使用了 SELECT *，宽表场景应只选取所需列以减少上下文消耗");
        }

        // 4) 反引号标识符（MySQL 风格）→ 双引号（PG 风格）
        // 反引号转双引号仅适用于 PG（MySQL 默认把双引号当字符串字面量，转了必错）；
        // MySQL 方言下反引号本来就是合法的标识符引用，保留不动
        if (fixed.contains("`") && "postgresql".equals(dialect)) {
            fixed = fixed.replace('`', '"');
            fixes.add("反引号标识符已转换为双引号(PG风格)");
        }

        // 修复后必须重新过安全护栏（规则修复不能成为绕过校验的通道）
        if (!fixes.isEmpty() && guard != null) {
            SqlSafetyGuard.GuardResult revalidate = guard.validate(fixed);
            if (!revalidate.isPassed()) {
                log.warn("[SqlSelfCorrector] 规则修复后未通过安全校验, 回退原SQL: {}", revalidate.getReason());
                return CorrectionResult.of(sql, List.of("规则修复后安全校验失败，已回退: " + revalidate.getReason()));
            }
            fixed = revalidate.getSafeSql();
        }

        return CorrectionResult.of(fixed, fixes);
    }

    /**
     * 构建 L2 模型反思的提示语料：把失败信息组织成模型最容易据以修正的形式。
     * 由调用方（Agent act 阶段）作为 Observation 注入下一轮上下文。
     */
    public String buildReflectionObservation(String failedSql, SqlExecutor.ExecuteResult failedResult,
                                             int attempt, String schemaHint) {
        return String.format("""
                【SQL执行失败，需要修正】(第%d/%d次尝试)
                失败的SQL: %s
                错误信息: %s
                可用Schema参考: %s
                
                请分析错误原因并输出修正后的完整SQL（重新调用 execute_sql）。要求:
                1. 针对错误信息中的"修正建议"逐条检查；
                2. 表名字段名必须严格来自Schema参考，注意大小写；
                3. 不要原样重复提交相同SQL；
                4. 若连续失败无法修正，停止重试并向用户说明数据层面无法满足该问题。
                """, attempt, maxRetry, failedSql,
                failedResult == null ? "未知错误" : failedResult.getErrorMessage(),
                schemaHint == null ? "(无)" : schemaHint);
    }

    /**
     * 纠错结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CorrectionResult {
        /** 修复后的 SQL */
        private String sql;
        /** 应用的修复动作（进入 Observation，保证透明可解释） */
        private List<String> fixes;

        public static CorrectionResult of(String sql, List<String> fixes) {
            return CorrectionResult.builder().sql(sql).fixes(fixes).build();
        }

        public boolean hasFixes() {
            return fixes != null && !fixes.isEmpty();
        }
    }
}
