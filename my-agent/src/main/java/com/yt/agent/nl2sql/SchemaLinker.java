package com.yt.agent.nl2sql;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Schema Linking（模式链接）——问数准确率的第一决定因素
 *
 * <p>问题背景：生产库动辄上千张表、数万字段，全量 Schema 塞进上下文既超 token 又严重稀释注意力
 * （模型会被无关表干扰而选错表，这是 NL2SQL 最主要的错误来源）。所以必须先用用户问题召回
 * 少量高相关的表和字段，只把这部分 Schema 交给模型。</p>
 *
 * <p>本实现采用「多信号加权打分」的轻量召回，无需向量库即可跑通，信号包括：
 * <ol>
 *   <li>同义词命中（权重最高）：用户说"营收"命中 column.synonyms=["营收","收入"]</li>
 *   <li>业务含义/注释命中：comment 与表注释的关键词匹配</li>
 *   <li>表名与字段名的字面命中</li>
 *   <li>枚举值命中：用户说"已支付"命中 status 的 enumValues=["1=已支付"]</li>
 *   <li>业务域(domain)命中</li>
 * </ol>
 * 命中的字段所在表整表加分，保证不会只召回字段却丢了表。</p>
 *
 * <p>演进方向（面试可讲）：把字面匹配换成 Embedding 向量召回 + 重排(rerank)，
 * 即用 {@code EmbeddingModel} 把"表注释+字段注释"向量化入 PGVector，query 向量做 top-k 检索，
 * 再用 cross-encoder 重排。本项目已依赖 spring-ai-pgvector-store，具备升级条件；
 * 这里先用零依赖的打分实现，是为了让召回逻辑可解释、可单测、可离线跑通。</p>
 */
@Slf4j
public class SchemaLinker {

    /** Schema 元数据配置文件（classpath） */
    private static final String DEFAULT_SCHEMA_CONFIG = "nl2sql/schema-metadata.json";

    /** 召回表数量上限 */
    private final int topTables;
    /** 每张表召回字段数量上限 */
    private final int topColumnsPerTable;
    /** 最低分阈值，低于此分不召回 */
    private final double minScore;

    /** 全量 Schema 元数据（启动时加载一次，常驻内存） */
    private final List<TableSchema> allSchemas = new ArrayList<>();

    public SchemaLinker() {
        this(DEFAULT_SCHEMA_CONFIG, 5, 20, 0.01);
    }

    public SchemaLinker(String configLocation, int topTables, int topColumnsPerTable, double minScore) {
        this.topTables = topTables > 0 ? topTables : 5;
        this.topColumnsPerTable = topColumnsPerTable > 0 ? topColumnsPerTable : 20;
        this.minScore = minScore;
        loadSchemas(configLocation);
    }

    /** 供测试或外部注入直接构造 */
    public SchemaLinker(List<TableSchema> schemas, int topTables, int topColumnsPerTable) {
        this.topTables = topTables;
        this.topColumnsPerTable = topColumnsPerTable;
        this.minScore = 0.0;
        if (schemas != null) {
            this.allSchemas.addAll(schemas);
        }
        log.info("[SchemaLinker] 外部注入 Schema {} 张表", allSchemas.size());
    }

    private void loadSchemas(String configLocation) {
        try {
            ClassPathResource resource = new ClassPathResource(configLocation);
            if (!resource.exists()) {
                log.warn("[SchemaLinker] Schema 配置 {} 不存在，问数能力将降级（仅返回提示）", configLocation);
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                List<TableSchema> loaded = JSON.parseArray(json, TableSchema.class);
                if (loaded != null) {
                    allSchemas.addAll(loaded);
                }
            }
            log.info("[SchemaLinker] 加载 Schema 元数据完成, 共 {} 张表: {}",
                    allSchemas.size(),
                    allSchemas.stream().map(TableSchema::getTableName).collect(Collectors.joining(", ")));
        } catch (Exception e) {
            log.error("[SchemaLinker] Schema 元数据加载失败: {}", e.getMessage(), e);
        }
    }

    public List<TableSchema> getAllSchemas() {
        return List.copyOf(allSchemas);
    }

    /** 所有已授权表名（小写），供 SqlSafetyGuard 做表级白名单校验 */
    public Set<String> getAuthorizedTableNames() {
        return allSchemas.stream()
                .map(s -> s.getTableName().toLowerCase())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * 核心：根据用户问题召回最相关的 Schema。
     *
     * @param query 用户自然语言问题
     * @return 打分排序并裁剪字段后的 Schema 列表
     */
    public List<ScoredSchema> link(String query) {
        if (query == null || query.isBlank() || allSchemas.isEmpty()) {
            return List.of();
        }
        String q = query.toLowerCase().trim();
        // 分词：中文按 2~4 字滑窗 + 英文数字按词，兼顾"华东区""销售额"这类中文词组
        List<String> tokens = tokenize(q);

        List<ScoredSchema> scored = new ArrayList<>();
        for (TableSchema schema : allSchemas) {
            ScoredSchema ss = scoreSchema(schema, q, tokens);
            if (ss.getScore() >= minScore && !ss.getMatchedColumns().isEmpty()) {
                scored.add(ss);
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredSchema::getScore).reversed());
        List<ScoredSchema> top = scored.size() > topTables ? scored.subList(0, topTables) : scored;

        // 字段裁剪：只保留命中的字段 + 主键 + 时间分区字段，避免宽表几百列撑爆上下文
        top.forEach(ss -> ss.setSchema(pruneColumns(ss.getSchema(), ss.getMatchedColumns())));
        return top;
    }

    protected ScoredSchema scoreSchema(TableSchema schema, String q, List<String> tokens) {
        double score = 0.0;
        Map<String, Double> matchedColumns = new LinkedHashMap<>();

        // 1) 表名与表注释
        String tableName = schema.getTableName() == null ? "" : schema.getTableName().toLowerCase();
        String bareTable = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        if (q.contains(bareTable) && !bareTable.isBlank()) {
            score += 3.0;
        }
        score += countHits(schema.getTableComment(), tokens) * 1.5;
        score += countHits(schema.getDomain(), tokens) * 1.0;
        if (schema.getSynonyms() != null) {
            for (String syn : schema.getSynonyms()) {
                if (syn != null && !syn.isBlank() && q.contains(syn.toLowerCase())) {
                    score += 4.0;   // 表级同义词命中权重最高
                }
            }
        }

        // 2) 字段级打分
        if (schema.getColumns() != null) {
            for (TableSchema.ColumnInfo col : schema.getColumns()) {
                double colScore = 0.0;
                String colName = col.getColumnName() == null ? "" : col.getColumnName().toLowerCase();
                // 字段名字面命中（含下划线拆词，order_amount 可被 "amount" 命中）
                if (!colName.isBlank()) {
                    if (q.contains(colName)) colScore += 2.0;
                    for (String part : colName.split("_")) {
                        if (part.length() > 2 && q.contains(part)) colScore += 0.8;
                    }
                }
                // 业务含义命中
                colScore += countHits(col.getComment(), tokens) * 2.0;
                // 同义词命中（权重最高，这是 NL2SQL 对齐业务黑话的关键）
                if (col.getSynonyms() != null) {
                    for (String syn : col.getSynonyms()) {
                        if (syn != null && !syn.isBlank() && q.contains(syn.toLowerCase())) {
                            colScore += 4.0;
                        }
                    }
                }
                // 枚举值命中（用户说"已支付"应命中 status 字段）
                if (col.getEnumValues() != null) {
                    for (String ev : col.getEnumValues()) {
                        if (ev != null) {
                            for (String token : tokens) {
                                if (token.length() >= 2 && ev.toLowerCase().contains(token)) {
                                    colScore += 2.5;
                                    break;
                                }
                            }
                        }
                    }
                }
                // 时间分区字段在有时间词时加分（"上个月""近30天"）
                if (col.isTimePartition() && containsTimeHint(q)) {
                    colScore += 2.0;
                }

                if (colScore > 0) {
                    matchedColumns.put(col.getColumnName(), colScore);
                    score += colScore;
                }
            }
        }

        return ScoredSchema.builder()
                .schema(schema)
                .score(score)
                .matchedColumns(matchedColumns)
                .build();
    }

    /** 字段裁剪：保留命中字段 + 主键 + 时间分区字段 */
    protected TableSchema pruneColumns(TableSchema origin, Map<String, Double> matched) {
        if (origin.getColumns() == null) return origin;
        Set<String> keep = matched.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topColumnsPerTable)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (origin.getPrimaryKeys() != null) keep.addAll(origin.getPrimaryKeys());
        origin.getColumns().stream()
                .filter(TableSchema.ColumnInfo::isTimePartition)
                .forEach(c -> keep.add(c.getColumnName()));

        List<TableSchema.ColumnInfo> pruned = origin.getColumns().stream()
                .filter(c -> keep.contains(c.getColumnName()))
                .toList();
        return TableSchema.builder()
                .tableName(origin.getTableName())
                .tableComment(origin.getTableComment())
                .domain(origin.getDomain())
                .columns(pruned)
                .primaryKeys(origin.getPrimaryKeys())
                .synonyms(origin.getSynonyms())
                .rowCount(origin.getRowCount())
                .build();
    }

    private static final List<String> TIME_HINTS = List.of(
            "今天", "昨天", "本周", "上周", "本月", "上月", "上个月", "今年", "去年", "近", "最近",
            "季度", "月", "日", "年", "同比", "环比", "至今", "以来");

    private boolean containsTimeHint(String q) {
        return TIME_HINTS.stream().anyMatch(q::contains);
    }

    /** 统计 text 中命中的 token 数 */
    private int countHits(String text, List<String> tokens) {
        if (text == null || text.isBlank()) return 0;
        String lower = text.toLowerCase();
        int hits = 0;
        for (String t : tokens) {
            if (t.length() >= 2 && lower.contains(t)) hits++;
        }
        return hits;
    }

    /**
     * 简易分词：英文数字按非字母数字切分；中文按 2~4 字滑动窗口切分。
     * 不引入分词器依赖（生产可替换为 IK/jieba 或直接走 Embedding）。
     */
    List<String> tokenize(String q) {
        List<String> tokens = new ArrayList<>();
        // 英文与数字词
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z0-9_]+").matcher(q);
        while (m.find()) {
            String w = m.group();
            tokens.add(w);
            for (String part : w.split("_")) {
                if (part.length() > 1) tokens.add(part);
            }
        }
        // 中文滑窗（2~4 字）
        StringBuilder cn = new StringBuilder();
        for (char c : q.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cn.append(c);
            } else {
                addChineseWindows(cn.toString(), tokens);
                cn.setLength(0);
            }
        }
        addChineseWindows(cn.toString(), tokens);
        return tokens.stream().distinct().collect(Collectors.toList());
    }

    private void addChineseWindows(String seg, List<String> tokens) {
        if (seg.length() < 2) {
            if (!seg.isEmpty()) tokens.add(seg);
            return;
        }
        for (int win = 2; win <= 4; win++) {
            for (int i = 0; i + win <= seg.length(); i++) {
                tokens.add(seg.substring(i, i + win));
            }
        }
    }

    /**
     * 召回结果：Schema + 得分 + 命中字段明细（便于解释"为什么召回这张表"，可观测性关键）
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ScoredSchema {
        private TableSchema schema;
        private double score;
        private Map<String, Double> matchedColumns;

        public String getTableName() {
            return schema == null ? null : schema.getTableName();
        }
    }
}
