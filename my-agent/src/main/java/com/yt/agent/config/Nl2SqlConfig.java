package com.yt.agent.config;

import com.yt.agent.memory.ContextCompressor;
import com.yt.agent.memory.SemanticMemoryStore;
import com.yt.agent.nl2sql.HybridSchemaLinker;
import com.yt.agent.nl2sql.QueryRewriter;
import com.yt.agent.nl2sql.SchemaLinker;
import com.yt.agent.nl2sql.SqlExecutor;
import com.yt.agent.nl2sql.SqlSafetyGuard;
import com.yt.agent.nl2sql.SqlSelfCorrector;
import com.yt.agent.search.ElasticsearchSchemaStore;
import com.yt.agent.search.MilvusSchemaStore;
import com.yt.agent.search.OpenAiCompatEmbeddingClient;
import com.yt.agent.search.SchemaIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Driver;

/**
 * NL2SQL 问数能力装配
 *
 * <p>注意：主应用排除了 DataSourceAutoConfiguration（见 AgentApplication），
 * 所以这里为问数场景装配<b>独立的只读数据源</b>，通过 nl2sql.datasource.* 配置：
 * <pre>
 * nl2sql:
 *   datasource:
 *     url: jdbc:mysql://127.0.0.1:3306/bi_warehouse
 *     username: nl2sql_ro          # 生产必须用只有 SELECT 权限的只读账号
 *     password: xxx
 *     driver-class-name: com.mysql.cj.jdbc.Driver
 * </pre>
 * 未配置 url 时，SqlExecutor 为 null，工具自动降级为「仅生成 SQL 不执行」模式，
 * 应用照常启动——问数是增强能力，不能绑死主流程。</p>
 *
 * <p>schema 混合召回装配（nl2sql.hybrid.enabled=true 时启用）：
 * ES（BM25 关键词）+ Milvus（语义向量）双通道召回 + RRF 融合，封装在
 * {@link HybridSchemaLinker} 中；任一外部依赖不可用时自动降级为纯规则召回，
 * 外部检索基础设施是增强能力，同样不能绑死主流程。</p>
 */
@Slf4j
@Configuration
public class Nl2SqlConfig {

    @Value("${nl2sql.datasource.url:}")
    private String url;

    @Value("${nl2sql.datasource.username:}")
    private String username;

    @Value("${nl2sql.datasource.password:}")
    private String password;

    @Value("${nl2sql.datasource.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    /** 召回表数上限 */
    @Value("${nl2sql.schema-link.top-tables:5}")
    private int topTables;

    /** 每表召回字段上限 */
    @Value("${nl2sql.schema-link.top-columns:20}")
    private int topColumns;

    /** 查询超时（秒） */
    @Value("${nl2sql.executor.query-timeout-seconds:30}")
    private int queryTimeout;

    /** 最大返回行数 */
    @Value("${nl2sql.executor.max-rows:500}")
    private int maxRows;

    /** 无 LIMIT 时强制追加的行数上限 */
    @Value("${nl2sql.guard.default-row-limit:1000}")
    private int defaultRowLimit;

    // ---------- 混合召回配置 ----------
    @Value("${nl2sql.hybrid.enabled:false}")
    private boolean hybridEnabled;

    @Value("${nl2sql.hybrid.es.base-url:}")
    private String esBaseUrl;

    @Value("${nl2sql.hybrid.es.index:nl2sql_schema}")
    private String esIndex;

    @Value("${nl2sql.hybrid.milvus.host:127.0.0.1}")
    private String milvusHost;

    @Value("${nl2sql.hybrid.milvus.port:19530}")
    private int milvusPort;

    @Value("${nl2sql.hybrid.milvus.collection:nl2sql_schema}")
    private String milvusCollection;

    @Value("${nl2sql.hybrid.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${nl2sql.hybrid.embedding.model:}")
    private String embeddingModel;

    @Value("${nl2sql.hybrid.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${nl2sql.hybrid.embedding.dim:768}")
    private int embeddingDim;

    @Bean
    public SchemaLinker schemaLinker() {
        SchemaLinker base = new SchemaLinker("nl2sql/schema-metadata.json", topTables, topColumns, 0.01);
        if (!hybridEnabled) {
            log.info("[Nl2SqlConfig] schema 混合召回未启用，使用纯规则召回");
            return base;
        }
        try {
            ElasticsearchSchemaStore esStore = new ElasticsearchSchemaStore(esBaseUrl, esIndex);
            MilvusSchemaStore milvusStore = new MilvusSchemaStore(
                    milvusHost, milvusPort, milvusCollection, embeddingDim);
            OpenAiCompatEmbeddingClient embeddingClient = new OpenAiCompatEmbeddingClient(
                    embeddingBaseUrl, embeddingModel, embeddingApiKey);
            SchemaIndexService indexService = new SchemaIndexService(base, esStore, milvusStore, embeddingClient);
            HybridSchemaLinker hybrid = new HybridSchemaLinker(
                    "nl2sql/schema-metadata.json", topTables, topColumns,
                    esStore, milvusStore, embeddingClient, indexService);
            log.info("[Nl2SqlConfig] schema 混合召回装配完成: es={}, milvus={}:{} collection={}, embedding={}/{}",
                    esBaseUrl, milvusHost, milvusPort, milvusCollection, embeddingBaseUrl, embeddingModel);
            return hybrid;
        } catch (Exception e) {
            log.error("[Nl2SqlConfig] 混合召回装配失败，降级为纯规则召回: {}", e.getMessage());
            return base;
        }
    }

    // schema 混合检索索引服务不注册为独立 Bean：未启用混合召回时它不存在
    //（"返回 null 的 @Bean"会污染按类型注入），管理端点直接从 SchemaLinker
    // instanceof HybridSchemaLinker 拿共享实例。

    /**
     * SQL 安全护栏。授权表白名单来自 SchemaLinker 加载的元数据——
     * 模型只能查"它看得见"的表，Schema 召回同时兼任了数据权限的授权清单。
     */
    @Bean
    public SqlSafetyGuard sqlSafetyGuard(SchemaLinker schemaLinker) {
        return new SqlSafetyGuard(defaultRowLimit, schemaLinker.getAuthorizedTableNames());
    }

    /**
     * 问数专用只读数据源（内部构建，<b>不</b>注册为容器 Bean）。
     *
     * <p>为什么不暴露成 {@code @Bean}：未配置 url 时这里返回 null，而 Spring 会把
     * "返回 null 的 @Bean 方法"记为 null bean，随后任何按类型注入 DataSource 的地方
     * 都会抛 {@code NoSuchBeanDefinitionException}——这正是之前启动失败的根因。
     * 只在 {@link #sqlExecutor()} 内部按需构建，容器里就不存在"半个 DataSource"。</p>
     */
    private DataSource buildReadOnlyDataSource() {
        if (url == null || url.isBlank()) {
            log.warn("[Nl2SqlConfig] 未配置 nl2sql.datasource.url，SQL 执行能力降级为 dry-run（仅生成不执行）");
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Driver> driverClass = (Class<? extends Driver>) Class.forName(driverClassName);
            SimpleDriverDataSource ds = new SimpleDriverDataSource();
            ds.setDriverClass(driverClass);
            ds.setUrl(url);
            ds.setUsername(username);
            ds.setPassword(password);
            log.info("[Nl2SqlConfig] 问数只读数据源装配完成: {}", url);
            return ds;
        } catch (ClassNotFoundException e) {
            log.error("[Nl2SqlConfig] 驱动加载失败 {}，SQL 执行能力降级为 dry-run", driverClassName);
            return null;
        }
    }

    /**
     * SqlExecutor 永远装配：数据源可为 null，此时内部自动走 dry-run（只产出 SQL 不执行），
     * 保证"问数是增强能力，不能绑死主流程"的降级承诺真的成立。
     */
    @Bean
    public SqlExecutor sqlExecutor() {
        return new SqlExecutor(buildReadOnlyDataSource(), queryTimeout, maxRows, 2000);
    }

    /** 语义记忆：指标口径/业务术语/用户偏好 */
    @Bean
    public SemanticMemoryStore semanticMemoryStore() {
        return new SemanticMemoryStore();
    }

    /** 多轮指代消解与口径继承 */
    @Bean
    public QueryRewriter queryRewriter() {
        return new QueryRewriter();
    }

    /** SQL 自纠错（L1 规则纠错；L2 模型反思由 Agent 循环承担）；方言跟随问数数据源 */
    @Bean
    public SqlSelfCorrector sqlSelfCorrector(SqlSafetyGuard sqlSafetyGuard) {
        String dialect = driverClassName != null && driverClassName.contains("mysql") ? "mysql" : "postgresql";
        return new SqlSelfCorrector(sqlSafetyGuard, 3, dialect);
    }

    /** 上下文压缩器 */
    @Bean
    public ContextCompressor contextCompressor(
            @Value("${nl2sql.memory.soft-token-limit:24000}") int softTokenLimit,
            @Value("${nl2sql.memory.keep-recent-messages:6}") int keepRecent,
            @Value("${nl2sql.memory.max-tool-message-chars:2000}") int maxToolChars) {
        return new ContextCompressor(softTokenLimit, keepRecent, maxToolChars);
    }
}
