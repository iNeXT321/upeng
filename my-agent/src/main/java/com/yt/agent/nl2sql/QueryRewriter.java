package com.yt.agent.nl2sql;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询改写器（Query Rewriting）—— 多轮问数的指代消解与意图补全
 *
 * <p>解决的问题：问数对话天然是多轮的，用户第二轮往往说"那华南呢？""按月份拆开看看""刚才的口径改成含退款"。
 * 这类省略句直接送去 Schema 召回 / SQL 生成必然失败——缺少主语（指标）、缺少上下文（上一轮的维度/过滤条件）。</p>
 *
 * <p>两级改写策略：
 * <ol>
 *   <li><b>L1 规则改写</b>（本类，零成本）：指代词检测 + 上一轮查询要素回填。
 *       识别"那/它/这个/同样/也"等指代与省略模式，把上一轮的指标、时间范围、过滤条件合并进当前问题；</li>
 *   <li><b>L2 模型改写</b>（生产推荐）：用小模型（qwen-turbo 级）做改写，
 *       输入 = 最近 N 轮对话 + 当前问题，输出 = 自包含的完整问题。
 *       本项目 Agent 主循环已具备该能力（think 阶段模型可见完整 Memory），
 *       规则改写主要作为独立调用 SchemaLinker 时的兜底。</li>
 * </ol>
 * </p>
 *
 * <p>为什么需要独立成组件：Schema 召回与 SQL 生成都是「单轮」接口，
 * 多轮上下文的融合必须发生在它们之前，这就是 QueryRewriter 的位置——问数链路的第 0 步。</p>
 */
@Slf4j
public class QueryRewriter {

    /** 指代/省略模式：命中则认为当前问题依赖上文 */
    private static final Pattern ANAPHORA = Pattern.compile(
            "(那|它|他|她|这个|那个|这些|同样|一样|也|还是|刚才|上面|之前|换(成|个)|改(成|为)|再看看|拆开|细分|按(.{1,6})(看|拆|分))");

    /** 纯省略问句：非常短且无指标词，如"华南呢？""按月呢？" */
    private static final Pattern ELLIPSIS_TAIL = Pattern.compile(
            "^.{0,12}(呢|吗|的|如何|怎么样|怎样)[?？]?$");

    /**
     * 改写入口：把依赖上下文的省略问题重写为自包含问题。
     *
     * @param currentQuery 当前用户问题
     * @param lastQuery    上一轮的（已改写）问题，可为 null
     * @param lastSql      上一轮成功执行的 SQL，可为 null（用于提取指标/过滤条件线索）
     * @return 改写结果
     */
    public RewriteResult rewrite(String currentQuery, String lastQuery, String lastSql) {
        if (currentQuery == null || currentQuery.isBlank()) {
            return new RewriteResult(currentQuery, false, "空问题");
        }
        // 无上下文可依赖，直接返回
        if ((lastQuery == null || lastQuery.isBlank())) {
            return new RewriteResult(currentQuery, false, "首轮问题，无需改写");
        }

        String q = currentQuery.trim();
        boolean hasAnaphora = ANAPHORA.matcher(q).find();
        boolean isEllipsis = ELLIPSIS_TAIL.matcher(q).find() && q.length() <= 14;

        if (!hasAnaphora && !isEllipsis) {
            // 问题自包含（含完整指标+维度表述），不改写——避免过度改写引入噪音
            return new RewriteResult(q, false, "问题自包含，无需改写");
        }

        StringBuilder rewritten = new StringBuilder();
        List<String> actions = new ArrayList<>();

        // 提取上一轮的核心要素：指标（来自 lastSql 的聚合函数列）与基础语义（来自 lastQuery）
        String lastMetrics = extractMetricsFromSql(lastSql);

        if (isEllipsis && !hasAnaphora) {
            // 纯省略句："华南呢？" → "在上一轮问题基础上，把区域条件换成华南：{lastQuery}（将区域替换为华南）"
            rewritten.append("基于上一轮问题【").append(lastQuery).append("】，")
                    .append("将其中变化的条件替换为当前问题所述：【").append(q).append("】");
            actions.add("省略句补全：回填上一轮问题主干");
        } else {
            // 指代句："那按月份拆开看看" → 上一轮问题 + 当前追加要求
            rewritten.append("上一轮问题：【").append(lastQuery).append("】。")
                    .append("本轮在其基础上的新要求：【").append(q).append("】。")
                    .append("请将两轮合并为一个完整、自包含的查询问题。");
            actions.add("指代消解：合并上一轮上下文");
        }

        if (lastMetrics != null && !lastMetrics.isBlank()) {
            rewritten.append("（上一轮使用的指标口径：").append(lastMetrics).append("，除非本轮明确要求变更，沿用该口径）");
            actions.add("口径继承：沿用上一轮指标");
        }

        String result = rewritten.toString();
        log.info("[QueryRewriter] 改写: '{}' -> '{}'", q, result);
        return new RewriteResult(result, true, String.join("; ", actions));
    }

    /**
     * 从上一轮 SQL 中提取指标口径线索（聚合表达式），供口径继承。
     * 轻量正则实现；生产可用 Calcite 解析 AST 提取 select 项。
     */
    String extractMetricsFromSql(String sql) {
        if (sql == null || sql.isBlank()) return null;
        Matcher m = Pattern.compile("(?i)\\b(sum|count|avg|max|min)\\s*\\([^()]*\\)").matcher(sql);
        List<String> metrics = new ArrayList<>();
        while (m.find() && metrics.size() < 5) {
            metrics.add(m.group());
        }
        // WHERE 中的时间条件也属于口径的一部分
        Matcher w = Pattern.compile("(?i)where\\s+(.+?)(group\\s+by|order\\s+by|limit|$)").matcher(sql);
        if (w.find()) {
            String cond = w.group(1).trim();
            if (cond.length() < 200) metrics.add("过滤条件: " + cond);
        }
        return metrics.isEmpty() ? null : String.join(", ", metrics);
    }

    /**
     * 改写结果
     */
    public record RewriteResult(String query, boolean rewritten, String reason) {
    }
}
