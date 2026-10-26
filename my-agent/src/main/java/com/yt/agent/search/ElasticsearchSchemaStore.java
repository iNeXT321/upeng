package com.yt.agent.search;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch Schema 检索存储 —— 混合搜索的「关键词(BM25)」一路
 *
 * <p>为什么选 ES 做关键词检索：schema 元数据是中文注释+同义词，需要中文分词。
 * 官方内置 cjk 分析器（CJK bigram）零插件即可用，中文注释的分词质量足够
 * schema 召回（生产可平滑替换 IK）。BM25 的价值在于：精确词命中（如"退款率"、
 * "GMV"这类黑话/缩写）得分远高于语义相似但字面不匹配的结果——与向量召回正好互补。</p>
 *
 * <p>实现用 java.net.http + ES REST API（无官方客户端依赖）：
 * schema 检索只用到的能力（建索引/bulk/搜索）非常薄，不值得为此引入整个
 * elasticsearch-java 客户端及其传递依赖。</p>
 */
@Slf4j
public class ElasticsearchSchemaStore {

    private final HttpClient http;
    private final String baseUrl;
    private final String index;

    public ElasticsearchSchemaStore(String baseUrl, String index) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.index = index;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && index != null && !index.isBlank();
    }

    /** 检索单元：docId + 所属表 + BM25 得分 */
    public record EsHit(String docId, String tableName, String level, float score) {
    }

    /** 建索引（幂等）：cjk 分析器 + 单分片零副本（本地开发拓扑） */
    public void ensureIndex() {
        JSONObject mappings = new JSONObject();
        mappings.put("properties", mapOf(
                "docId", mapOf("type", "keyword"),
                "level", mapOf("type", "keyword"),
                "tableName", mapOf("type", "keyword"),
                "columnName", mapOf("type", "keyword"),
                "domain", mapOf("type", "keyword"),
                "keywordText", mapOf("type", "text", "analyzer", "cjk"),
                "embeddingText", mapOf("type", "text", "analyzer", "cjk")
        ));
        JSONObject body = new JSONObject();
        body.put("settings", mapOf("number_of_shards", 1, "number_of_replicas", 0));
        body.put("mappings", mappings);
        put("/" + index, body.toJSONString());
        log.info("[EsSchemaStore] 索引 {} 已就绪", index);
    }

    /** 全量重建：删除后重建（schema 元数据变更后的 reindex 入口） */
    public void recreateIndex() {
        delete("/" + index);
        ensureIndex();
    }

    /** 批量写入（/_bulk NDJSON） */
    public void bulkIndex(List<SchemaDoc> docs) {
        if (docs.isEmpty()) return;
        StringBuilder ndjson = new StringBuilder();
        for (SchemaDoc doc : docs) {
            ndjson.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"")
                    .append(escape(doc.getDocId())).append("\"}}\n");
            JSONObject d = new JSONObject();
            d.put("docId", doc.getDocId());
            d.put("level", doc.getLevel());
            d.put("tableName", doc.getTableName());
            if (doc.getColumnName() != null) d.put("columnName", doc.getColumnName());
            d.put("domain", doc.getDomain());
            d.put("keywordText", doc.getKeywordText());
            d.put("embeddingText", doc.getEmbeddingText());
            ndjson.append(d.toJSONString()).append("\n");
        }
        String resp = post("/_bulk", ndjson.toString(), "application/x-ndjson");
        if (resp.contains("\"errors\":true")) {
            log.warn("[EsSchemaStore] bulk 存在失败项: {}", resp.length() > 500 ? resp.substring(0, 500) : resp);
        }
        postNoBody("/" + index + "/_refresh");
        log.info("[EsSchemaStore] 已写入 {} 条 schema 文档", docs.size());
    }

    /** BM25 关键词检索：multi_match（同义词/注释加权） + 短语匹配加分 */
    public List<EsHit> search(String query, int topK) {
        JSONObject multiMatch = mapOf(
                "query", query,
                "type", "best_fields",
                "fields", List.of("embeddingText^2", "keywordText", "keywordText.cjk"),
                "analyzer", "cjk");
        JSONObject phrase = mapOf(
                "embeddingText", mapOf("query", query, "analyzer", "cjk", "boost", 1.5));
        JSONObject boolQuery = mapOf("should", List.of(mapOf("multi_match", multiMatch), mapOf("match_phrase", phrase)),
                "minimum_should_match", 1);
        JSONObject body = mapOf("query", mapOf("bool", boolQuery), "size", topK,
                "_source", List.of("docId", "tableName", "level"));
        String resp = post("/" + index + "/_search", body.toJSONString(), "application/json");
        List<EsHit> hits = new ArrayList<>();
        JSONObject json = JSON.parseObject(resp);
        JSONArray arr = json.getJSONObject("hits").getJSONArray("hits");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                JSONObject h = arr.getJSONObject(i);
                JSONObject src = h.getJSONObject("_source");
                hits.add(new EsHit(src.getString("docId"), src.getString("tableName"),
                        src.getString("level"), h.getFloatValue("_score")));
            }
        }
        return hits;
    }

    /** 索引文档数（健康检查/是否需要初始化判断） */
    public long countDocs() {
        String resp = get("/" + index + "/_count");
        try {
            return JSON.parseObject(resp).getLongValue("count");
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean ping() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/_cluster/health"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- HTTP 基础封装 ----------------

    /** POST 无 body（ES 8 的 _refresh 等端点不接受 body） */
    private void postNoBody(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("[EsSchemaStore] {} -> HTTP {}", path, resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("[EsSchemaStore] {} 失败（忽略）: {}", path, e.getMessage());
        }
    }

    private String post(String path, String body, String contentType) {        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("ES " + path + " -> HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body()));
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ES 请求失败 " + path + ": " + e.getMessage(), e);
        }
    }

    private void put(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300 && !resp.body().contains("resource_already_exists")) {
                throw new IllegalStateException("ES " + path + " -> HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body()));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ES 请求失败 " + path + ": " + e.getMessage(), e);
        }
    }

    private void delete(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30)).DELETE().build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[EsSchemaStore] delete {} 失败（忽略）: {}", path, e.getMessage());
        }
    }

    private String get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException("ES 请求失败 " + path + ": " + e.getMessage(), e);
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private JSONObject mapOf(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return o;
    }
}
