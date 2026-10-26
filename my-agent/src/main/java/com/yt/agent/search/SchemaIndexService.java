package com.yt.agent.search;

import com.yt.agent.nl2sql.SchemaLinker;
import com.yt.agent.nl2sql.TableSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Schema 索引服务 —— 让 ES/Milvus 里的 schema 数据与项目 schema 元数据保持一致
 *
 * <p>存储逻辑（与项目架构对齐）：
 * <ul>
 *   <li>数据源：{@link SchemaLinker} 加载的 schema 元数据（nl2sql/schema-metadata.json，
 *       与 MySQL bi_warehouse 数仓 DDL 一一对应）</li>
 *   <li>ES：存「表级 + 字段级」文档，cjk 分词 + BM25，负责精确词/黑话/缩写召回</li>
 *   <li>Milvus：存同构文档的 embedding 向量，HNSW + COSINE，负责语义泛化召回
 *       （用户说"卖了多少钱"未必字面命中 GMV，但语义向量能召回）</li>
 *   <li>同步策略：启动时若任一存储为空则自动全量构建；schema 变更后可调
 *       reindex 全量重建（幂等，docId 主键 upsert 语义）</li>
 * </ul></p>
 */
@Slf4j
public class SchemaIndexService {

    private final SchemaLinker schemaLinker;
    private final ElasticsearchSchemaStore esStore;
    private final MilvusSchemaStore milvusStore;
    private final OpenAiCompatEmbeddingClient embeddingClient;

    /** 当前服务是否已就绪（ES+Milvus+Embedding 至少完成一轮成功构建） */
    private final AtomicBoolean indexed = new AtomicBoolean(false);

    public SchemaIndexService(SchemaLinker schemaLinker,
                              ElasticsearchSchemaStore esStore,
                              MilvusSchemaStore milvusStore,
                              OpenAiCompatEmbeddingClient embeddingClient) {
        this.schemaLinker = schemaLinker;
        this.esStore = esStore;
        this.milvusStore = milvusStore;
        this.embeddingClient = embeddingClient;
    }

    public boolean isIndexed() {
        return indexed.get();
    }

    /**
     * 惰性初始化（幂等）：只在存储为空时构建，首次问数前自动完成。
     *
     * @return 是否成功就绪
     */
    public synchronized boolean ensureIndexed() {
        if (indexed.get()) {
            return true;
        }
        try {
            if (esStore.countDocs() > 0 && milvusStore.countEntities() > 0) {
                indexed.set(true);
                log.info("[SchemaIndexService] ES/Milvus schema 索引均已存在，跳过构建");
                return true;
            }
            return rebuild();
        } catch (Exception e) {
            log.warn("[SchemaIndexService] schema 索引初始化失败（混合召回将降级）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 全量重建：schema-metadata.json → 文档化 → ES 索引 + Milvus 向量化写入。
     * 供管理端点 /ai/schema/reindex 调用（schema 变更后手动触发）。
     */
    public synchronized boolean rebuild() {
        List<TableSchema> schemas = schemaLinker.getAllSchemas();
        if (schemas.isEmpty()) {
            log.warn("[SchemaIndexService] schema 元数据为空，跳过索引构建");
            return false;
        }
        List<SchemaDoc> docs = SchemaDoc.buildDocs(schemas);
        log.info("[SchemaIndexService] 开始构建 schema 混合检索索引：{} 张表 -> {} 个文档",
                schemas.size(), docs.size());

        // 1. ES：BM25 关键词一路
        esStore.recreateIndex();
        esStore.bulkIndex(docs);

        // 2. Milvus：向量语义一路（embedding 不可用则只降级 ES 一路）
        try {
            milvusStore.recreateCollection();
            List<float[]> vectors = embeddingClient.embedAll(
                    docs.stream().map(SchemaDoc::getEmbeddingText).toList());
            milvusStore.insert(docs, vectors);
        } catch (Exception e) {
            log.error("[SchemaIndexService] Milvus 向量化失败（ES 关键词一路仍可用）: {}", e.getMessage());
            throw new IllegalStateException("Milvus 索引构建失败: " + e.getMessage(), e);
        }

        indexed.set(true);
        log.info("[SchemaIndexService] schema 混合检索索引构建完成");
        return true;
    }

    /** 健康状态摘要（供监控页/健康检查） */
    public String status() {
        return String.format("es=%s esDocs=%d milvus=%s milvusDocs=%d indexed=%s",
                esStore.ping(), esStore.countDocs(),
                milvusStore.ping(), milvusStore.countEntities(),
                indexed.get());
    }
}
