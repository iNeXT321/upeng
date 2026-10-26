package com.yt.agent.search;

import com.alibaba.fastjson.JSON;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Milvus Schema 检索存储 —— 混合搜索的「向量(语义)」一路
 *
 * <p>为什么用 Milvus 而不是 PGVector（项目此前依赖）：schema 元数据量小但
 * 语义召回质量取决于模型，独立向量库的意义在于——(1) 检索链路与业务库解耦，
 * 问数库故障不影响召回；(2) 生产中 schema 元数据会扩展到几百表*几十字段的
 * 语义描述 + 历史问答对，量级增长后 Milvus 的 HNSW + 标量过滤更合适；
 * (3) 与 ES 组成教科书式混合检索（BM25 + ANN），这正是本项目要演示的架构。</p>
 *
 * <p>集合设计：docId(varchar 主键, 幂等 upsert) / tableName / level /
 * embedding_text(varchar) / embedding(float_vector, COSINE + HNSW)。</p>
 */
@Slf4j
public class MilvusSchemaStore {

    /** 检索单元：docId + 所属表 + 相似度得分(COSINE 距离，越大越相似) */
    public record MilvusHit(String docId, String tableName, String level, float score) {
    }

    private final MilvusClientV2 client;
    private final String collection;
    private final int dim;

    public MilvusSchemaStore(String host, int port, String collection, int dim) {
        this.collection = collection;
        this.dim = dim;
        this.client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .connectTimeoutMs(5000)
                .build());
    }

    public boolean ping() {
        try {
            client.listCollections();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 建集合（幂等）+ HNSW 索引 + load */
    public void ensureCollection() {
        Boolean exists = client.hasCollection(
                HasCollectionReq.builder().collectionName(collection).build());
        if (Boolean.TRUE.equals(exists)) {
            log.info("[MilvusSchemaStore] 集合 {} 已存在", collection);
            return;
        }
        CreateCollectionReq.FieldSchema id = CreateCollectionReq.FieldSchema.builder()
                .name("docId").dataType(DataType.VarChar).maxLength(256)
                .isPrimaryKey(true).autoID(false).build();
        CreateCollectionReq.FieldSchema tableName = CreateCollectionReq.FieldSchema.builder()
                .name("tableName").dataType(DataType.VarChar).maxLength(256).build();
        CreateCollectionReq.FieldSchema level = CreateCollectionReq.FieldSchema.builder()
                .name("level").dataType(DataType.VarChar).maxLength(16).build();
        CreateCollectionReq.FieldSchema text = CreateCollectionReq.FieldSchema.builder()
                .name("embeddingText").dataType(DataType.VarChar).maxLength(8192).build();
        CreateCollectionReq.FieldSchema vector = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dim).build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(List.of(id, tableName, level, text, vector))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", 16, "efConstruction", 128))
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .collectionSchema(schema)
                .indexParams(List.of(indexParam))
                .build());
        log.info("[MilvusSchemaStore] 集合 {} 创建完成 (dim={})", collection, dim);
    }

    /** 全量重建（schema 变更后调用） */
    public void recreateCollection() {
        try {
            client.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq
                    .builder().collectionName(collection).build());
        } catch (Exception e) {
            log.warn("[MilvusSchemaStore] drop 失败（忽略）: {}", e.getMessage());
        }
        ensureCollection();
    }

    /** 批量插入 */
    public void insert(List<SchemaDoc> docs, List<float[]> vectors) {
        if (docs.size() != vectors.size()) {
            throw new IllegalArgumentException("docs/vectors 数量不一致");
        }
        List<JsonObject> rows = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            SchemaDoc doc = docs.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("docId", doc.getDocId());
            row.addProperty("tableName", doc.getTableName());
            row.addProperty("level", doc.getLevel());
            String text = doc.getEmbeddingText();
            row.addProperty("embeddingText", text.length() > 8000 ? text.substring(0, 8000) : text);
            com.google.gson.JsonArray vec = new com.google.gson.JsonArray(vectors.get(i).length);
            for (float v : vectors.get(i)) {
                vec.add(v);
            }
            row.add("embedding", vec);
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(collection).data(rows).build());
        log.info("[MilvusSchemaStore] 已写入 {} 条 schema 向量", docs.size());
    }

    /** ANN 检索 topK */
    public List<MilvusHit> search(float[] queryVector, int topK) {
        SearchResp resp = client.search(SearchReq.builder()
                .collectionName(collection)
                .annsField("embedding")
                .data(List.of(new FloatVec(queryVector)))
                .topK(topK)
                .outputFields(List.of("docId", "tableName", "level"))
                .build());
        List<MilvusHit> hits = new ArrayList<>();
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results != null && !results.isEmpty()) {
            for (SearchResp.SearchResult r : results.get(0)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entity = (Map<String, Object>) r.getEntity();
                if (entity == null) {
                    continue;
                }
                hits.add(new MilvusHit(
                        String.valueOf(entity.get("docId")),
                        String.valueOf(entity.get("tableName")),
                        entity.get("level") == null ? "column" : String.valueOf(entity.get("level")),
                        r.getScore()));
            }
        }
        return hits;
    }

    /** 集合内向量条数（是否需要初始化的判断依据） */
    public long countEntities() {
        try {
            GetCollectionStatsResp stats = client.getCollectionStats(
                    GetCollectionStatsReq.builder().collectionName(collection).build());
            return stats.getNumOfEntities();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 供审计/排查：以 JSON 形式返回集合基本信息 */
    public String describe() {
        return JSON.toJSONString(Map.of("collection", collection, "dim", dim));
    }
}
