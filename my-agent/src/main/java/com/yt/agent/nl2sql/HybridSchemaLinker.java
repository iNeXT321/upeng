package com.yt.agent.nl2sql;

import com.yt.agent.search.ElasticsearchSchemaStore;
import com.yt.agent.search.MilvusSchemaStore;
import com.yt.agent.search.OpenAiCompatEmbeddingClient;
import com.yt.agent.search.SchemaIndexService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 混合 Schema 召回（Hybrid Search Schema Linking）
 *
 * <p>架构：在 {@link SchemaLinker} 规则打分的基础上，叠加两条独立召回通道并做 RRF 融合：
 * <ol>
 *   <li><b>ES BM25 通道</b>（{@code ElasticsearchSchemaStore}）：cjk 分词 + BM25，
 *       擅长精确命中——业务黑话（GMV）、缩写（SKU）、枚举值（已支付）；</li>
 *   <li><b>Milvus 向量通道</b>（{@code MilvusSchemaStore}）：schema 注释 embedding +
 *       查询向量 ANN 检索，擅长语义泛化——"卖了多少钱"字面命中不了 gmv，向量可以；</li>
 *   <li><b>RRF 融合</b>（Reciprocal Rank Fusion，score = Σ 1/(k+rank)，k=60）：
 *       不需要把两路异构分数（BM25 分 vs 余弦相似度）归一化，只按排名融合，
 *       这是 Elasticsearch 官方 RRF 检索采用的同一思路；</li>
 *   <li><b>规则重排兜底</b>：融合只决定"召回哪些表"，表内字段裁剪仍复用父类
 *       规则打分（可解释、可单测）；两路都不可用时整体回退父类纯规则模式。</li>
 * </ol></p>
 *
 * <p>与 Milvus/ES 的存储逻辑约定见 {@link com.yt.agent.search.SchemaIndexService}：
 * 两库同构存储「表级 + 字段级」文档，docId 一致（table::t / col::t::c），
 * 字段命中沿 docId 归属表向上传导。</p>
 */
@Slf4j
public class HybridSchemaLinker extends SchemaLinker {

    /** RRF 融合常数（与 ES/业界默认一致） */
    private static final int RRF_K = 60;
    /** 每路召回条数 */
    private final int recallK;
    /** 召回表数上限（与父类配置一致） */
    private final int topTables;

    private final ElasticsearchSchemaStore esStore;
    private final MilvusSchemaStore milvusStore;
    private final OpenAiCompatEmbeddingClient embeddingClient;
    private final SchemaIndexService indexService;

    public HybridSchemaLinker(String configLocation, int topTables, int topColumnsPerTable,
                              ElasticsearchSchemaStore esStore, MilvusSchemaStore milvusStore,
                              OpenAiCompatEmbeddingClient embeddingClient,
                              SchemaIndexService indexService) {
        super(configLocation, topTables, topColumnsPerTable, 0.01);
        this.topTables = topTables;
        this.esStore = esStore;
        this.milvusStore = milvusStore;
        this.embeddingClient = embeddingClient;
        this.indexService = indexService;
        this.recallK = Math.max(20, topTables * 6);
    }

    @Override
    public List<ScoredSchema> link(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // 0. 惰性索引构建（幂等；ES/Milvus 未就绪时这里失败则走降级）
        boolean hybridReady = indexService.ensureIndexed();

        Map<String, Double> rrf = new HashMap<>();
        // docId -> 所属表（表级 doc 表即自身）
        Map<String, String> docTable = new HashMap<>();
        // 每张表被召回的字段（col::t::c 文档命中时记录）
        Map<String, Set<String>> vectorColumnHits = new HashMap<>();
        boolean esOk = false;
        boolean milvusOk = false;

        // 1. ES BM25 通道
        try {
            List<ElasticsearchSchemaStore.EsHit> hits = esStore.search(query, recallK);
            fuse(rrf, docTable, vectorColumnHits, hits.stream()
                    .map(h -> new String[]{h.docId(), h.tableName(), h.level()})
                    .collect(Collectors.toList()));
            esOk = true;
        } catch (Exception e) {
            log.warn("[HybridSchemaLinker] ES 召回失败（降级）: {}", e.getMessage());
        }

        // 2. Milvus 向量通道
        if (hybridReady) {
            try {
                float[] qv = embeddingClient.embedOne(query);
                List<MilvusSchemaStore.MilvusHit> hits = milvusStore.search(qv, recallK);
                fuse(rrf, docTable, vectorColumnHits, hits.stream()
                        .map(h -> new String[]{h.docId(), h.tableName(), h.level()})
                        .collect(Collectors.toList()));
                milvusOk = true;
            } catch (Exception e) {
                log.warn("[HybridSchemaLinker] Milvus 向量召回失败（降级）: {}", e.getMessage());
            }
        }

        // 3. 双路全挂 -> 整体回退父类纯规则召回
        if (!esOk && !milvusOk) {
            log.warn("[HybridSchemaLinker] ES/Milvus 均不可用，回退纯规则召回");
            return super.link(query);
        }

        // 4. RRF 表级聚合：表分 = 其下所有命中文档的 RRF 之和
        Map<String, Double> tableRrf = new HashMap<>();
        rrf.forEach((docId, score) -> tableRrf.merge(docTable.get(docId), score, Double::sum));
        List<String> topTablesByRrf = tableRrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topTables)
                .map(Map.Entry::getKey)
                .toList();

        // 5. 规则打分做字段确认与裁剪（父类能力复用：可解释、有同义词/枚举信号）
        String q = query.toLowerCase().trim();
        List<String> tokens = tokenize(q);
        Map<String, TableSchema> schemaByName = getAllSchemas().stream()
                .collect(Collectors.toMap(TableSchema::getTableName, s -> s, (a, b) -> a));

        List<ScoredSchema> result = new ArrayList<>();
        for (String tableName : topTablesByRrf) {
            TableSchema schema = schemaByName.get(tableName);
            if (schema == null) {
                continue; // ES/Milvus 里可能有脏数据，防御性跳过
            }
            ScoredSchema ss = scoreSchema(schema, q, tokens);
            // 规则没命中字段（纯语义召回的表）时，用向量召回的字段兜底
            if (ss.getMatchedColumns().isEmpty()) {
                Set<String> vecCols = vectorColumnHits.getOrDefault(tableName, Set.of());
                if (!vecCols.isEmpty()) {
                    Map<String, Double> fallback = new LinkedHashMap<>();
                    vecCols.forEach(c -> fallback.put(c, 1.0));
                    ss.setMatchedColumns(fallback);
                }
            }
            if (ss.getMatchedColumns().isEmpty()) {
                continue;
            }
            // 最终得分 = RRF 混合分（主序） + 微量规则分（同分时规则强者靠前）
            ss.setScore(tableRrf.get(tableName) + ss.getScore() * 0.001);
            ss.setSchema(pruneColumns(schema, ss.getMatchedColumns()));
            result.add(ss);
        }

        // 6. 兜底：混合召回表数不足时，用规则召回补齐（保证行为不劣于纯规则基线）
        if (result.size() < topTables) {
            Set<String> chosen = result.stream().map(ScoredSchema::getTableName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (TableSchema schema : getAllSchemas()) {
                if (result.size() >= topTables) break;
                if (chosen.contains(schema.getTableName())) continue;
                ScoredSchema ss = scoreSchema(schema, q, tokens);
                if (ss.getScore() >= 0.01 && !ss.getMatchedColumns().isEmpty()) {
                    ss.setScore(ss.getScore() * 0.001); // 补位表排在实际召回之后
                    ss.setSchema(pruneColumns(schema, ss.getMatchedColumns()));
                    result.add(ss);
                }
            }
        }

        result.sort(Comparator.comparingDouble(ScoredSchema::getScore).reversed());
        log.info("[HybridSchemaLinker] 混合召回完成: es={}, milvus={}, tables={}",
                esOk, milvusOk,
                result.stream().map(ScoredSchema::getTableName).collect(Collectors.joining(",")));
        return result;
    }

    /** 管理端点使用：暴露共享的索引服务（reindex/健康状态） */
    public SchemaIndexService getIndexService() {
        return indexService;
    }

    /**
     * 把一路召回结果融入 RRF 分数。同时记录文档归属（表/字段）。
     *
     * @param ranked 有序命中：[docId, tableName, level]
     */
    private void fuse(Map<String, Double> rrf, Map<String, String> docTable,
                      Map<String, Set<String>> vectorColumnHits,
                      List<String[]> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            String[] hit = ranked.get(i);
            String docId = hit[0], tableName = hit[1], level = hit[2];
            rrf.merge(docId, 1.0 / (RRF_K + i + 1), Double::sum);
            docTable.putIfAbsent(docId, tableName);
            if ("column".equals(level) && hit[0].startsWith("col::")) {
                String col = docId.substring(("col::" + tableName + "::").length());
                vectorColumnHits.computeIfAbsent(tableName, k -> new LinkedHashSet<>()).add(col);
            }
        }
    }
}
