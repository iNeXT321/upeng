package com.yt.agent.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 数据源浏览端点（需登录鉴权）：让用户在界面里直接查看
 * MySQL 数仓 / Elasticsearch 索引 / Milvus 集合里到底存了什么。
 *
 * <p>定位是「只读体检窗口」：所有查询强制只读 + 行数上限，不做任何写操作。
 * MySQL 复用问数只读账号（nl2sql_ro，只有 SELECT 权限），天然兜底；
 * ES/Milvus 只开放列表与分页浏览。</p>
 */
@Slf4j
@RestController
@RequestMapping("/ai/datasource")
public class DataBrowserController {

    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_\\-]+$");
    private static final int MAX_PAGE_SIZE = 200;

    @Value("${nl2sql.datasource.url:}")
    private String dbUrl;
    @Value("${nl2sql.datasource.username:}")
    private String dbUser;
    @Value("${nl2sql.datasource.password:}")
    private String dbPassword;

    @Value("${nl2sql.hybrid.es.base-url:}")
    private String esBaseUrl;
    @Value("${nl2sql.hybrid.es.index:nl2sql_schema}")
    private String esSchemaIndex;

    @Value("${nl2sql.hybrid.milvus.host:127.0.0.1}")
    private String milvusHost;
    @Value("${nl2sql.hybrid.milvus.port:19530}")
    private int milvusPort;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    // ==================== MySQL ====================

    /** 表清单：表名 / 估算行数 / 注释 / 体积(MB) */
    @GetMapping("/mysql/tables")
    public Map<String, Object> mysqlTables() {
        String sql = "SELECT table_name, table_rows, table_comment, "
                + "ROUND((data_length + index_length) / 1024 / 1024, 2) AS size_mb "
                + "FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "ORDER BY table_rows DESC";
        List<Map<String, Object>> tables = new ArrayList<>();
        jdbcQuery(sql, rs -> {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString(1));
                row.put("rows", rs.getLong(2));
                row.put("comment", rs.getString(3));
                row.put("sizeMb", rs.getBigDecimal(4));
                tables.add(row);
            }
        });
        return Map.of("tables", tables);
    }

    /** 表数据分页预览：列元数据 + 当页行 + 总行数 */
    @GetMapping("/mysql/tables/{table}/rows")
    public Map<String, Object> mysqlRows(@PathVariable String table,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        requireSafeName(table);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;

        String countSql = "SELECT COUNT(*) FROM `" + table + "`";
        long total = 0;
        List<Map<String, Object>> countRows = new ArrayList<>();
        jdbcQuery(countSql, rs -> {
            while (rs.next()) {
                countRows.add(Map.of("c", rs.getLong(1)));
            }
        });
        if (!countRows.isEmpty()) {
            total = (long) countRows.get(0).get("c");
        }

        String dataSql = "SELECT * FROM `" + table + "` LIMIT " + safeSize + " OFFSET " + offset;
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        jdbcQueryMeta(dataSql, (meta, rs) -> {
            int n = meta.getColumnCount();
            for (int i = 1; i <= n; i++) columns.add(meta.getColumnLabel(i));
            while (rs.next()) {
                List<Object> row = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    row.add(v instanceof byte[] ? new String((byte[]) v) : v);
                }
                rows.add(row);
            }
        });
        return Map.of("table", table, "total", total, "page", safePage,
                "size", safeSize, "columns", columns, "rows", rows);
    }

    // ==================== Elasticsearch ====================

    /** 索引清单：名称 / 文档数 / 体积 / 状态 */
    @GetMapping("/es/indices")
    public Map<String, Object> esIndices() {
        String body = esRequest("GET", "/_cat/indices?format=json&bytes=b", null);
        JSONArray arr = JSON.parseArray(body);
        List<Map<String, Object>> indices = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject idx = arr.getJSONObject(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", idx.getString("index"));
            row.put("docs", idx.getLong("docs.count"));
            row.put("sizeBytes", idx.getLong("store.size"));
            row.put("health", idx.getString("health"));
            row.put("isSchemaIndex", esSchemaIndex.equals(idx.getString("index")));
            indices.add(row);
        }
        indices.sort((a, b) -> Long.compare(
                b.get("docs") == null ? 0 : (long) b.get("docs"),
                a.get("docs") == null ? 0 : (long) a.get("docs")));
        return Map.of("indices", indices);
    }

    /** 索引文档分页浏览：q 为空全量翻页，否则 query_string 检索 */
    @GetMapping("/es/indices/{index}/docs")
    public Map<String, Object> esDocs(@PathVariable String index,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String q) {
        requireSafeName(index);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int from = (Math.max(1, page) - 1) * safeSize;

        JSONObject body = new JSONObject();
        if (q != null && !q.isBlank()) {
            body.put("query", Map.of("query_string",
                    Map.of("query", q.trim(), "lenient", true)));
        } else {
            body.put("query", Map.of("match_all", Map.of()));
        }
        body.put("from", from);
        body.put("size", safeSize);
        body.put("track_total_hits", true);

        String resp = esRequest("GET", "/" + index + "/_search", body.toJSONString());
        JSONObject json = JSON.parseObject(resp);
        JSONObject hits = json.getJSONObject("hits");
        long total = hits.getJSONObject("total").getLongValue("value");
        List<Map<String, Object>> docs = new ArrayList<>();
        JSONArray arr = hits.getJSONArray("hits");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                JSONObject h = arr.getJSONObject(i);
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("_id", h.getString("_id"));
                doc.put("_score", h.getFloat("_score"));
                doc.put("_source", h.get("_source"));
                docs.add(doc);
            }
        }
        return Map.of("index", index, "total", total, "page", page,
                "size", safeSize, "docs", docs);
    }

    // ==================== Milvus ====================

    /** 集合清单：名称 / 实体数 / 字段 schema */
    @GetMapping("/milvus/collections")
    public Map<String, Object> milvusCollections() {
        MilvusClientV2 client = milvusClient();
        try {
            List<String> names = client.listCollections()
                    .getCollectionNames();
            List<Map<String, Object>> collections = new ArrayList<>();
            for (String name : names) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                try {
                    GetCollectionStatsResp stats = client.getCollectionStats(
                            GetCollectionStatsReq.builder().collectionName(name).build());
                    row.put("entities", stats.getNumOfEntities());
                } catch (Exception e) {
                    row.put("entities", -1);
                }
                try {
                    DescribeCollectionResp desc = client.describeCollection(
                            DescribeCollectionReq.builder().collectionName(name).build());
                    List<Map<String, Object>> fields = new ArrayList<>();
                    for (var f : desc.getCollectionSchema().getFieldSchemaList()) {
                        Map<String, Object> field = new LinkedHashMap<>();
                        field.put("name", f.getName());
                        field.put("type", String.valueOf(f.getDataType()));
                        field.put("primaryKey", Boolean.TRUE.equals(f.getIsPrimaryKey()));
                        fields.add(field);
                    }
                    row.put("fields", fields);
                } catch (Exception e) {
                    row.put("fields", List.of());
                }
                collections.add(row);
            }
            return Map.of("collections", collections);
        } finally {
            client.close();
        }
    }

    /** 集合实体浏览：Milvus query 需要 filter 表达式，用主键非空兜底实现全量翻页 */
    @GetMapping("/milvus/collections/{name}/rows")
    public Map<String, Object> milvusRows(@PathVariable String name,
                                          @RequestParam(defaultValue = "50") int limit) {
        requireSafeName(name);
        int safeLimit = Math.min(Math.max(1, limit), 500);
        MilvusClientV2 client = milvusClient();
        try {
            DescribeCollectionResp desc = client.describeCollection(
                    DescribeCollectionReq.builder().collectionName(name).build());
            String pk = null;
            List<Map<String, Object>> fields = new ArrayList<>();
            for (var f : desc.getCollectionSchema().getFieldSchemaList()) {
                if (Boolean.TRUE.equals(f.getIsPrimaryKey())) pk = f.getName();
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", f.getName());
                field.put("type", String.valueOf(f.getDataType()));
                fields.add(field);
            }
            String filter = pk + " != ''";

            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(name)
                    .filter(filter)
                    .limit(safeLimit)
                    .outputFields(List.of("*"))
                    .build());
            List<List<Object>> rows = new ArrayList<>();
            List<String> columns = fields.stream().map(f -> (String) f.get("name")).toList();
            if (resp.getQueryResults() != null) {
                for (QueryResp.QueryResult r : resp.getQueryResults()) {
                    Map<String, Object> entity = r.getEntity();
                    List<Object> row = new ArrayList<>(columns.size());
                    for (String col : columns) {
                        Object v = entity.get(col);
                        // 向量字段压成前几个分量展示，避免刷屏
                        if (v instanceof List<?> vec && vec.size() > 6) {
                            row.add("[" + vec.get(0) + ", " + vec.get(1) + ", " + vec.get(2)
                                    + ", …] (dim=" + vec.size() + ")");
                        } else {
                            row.add(v);
                        }
                    }
                    rows.add(row);
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("collection", name);
            result.put("limit", safeLimit);
            result.put("columns", columns);
            result.put("fields", fields);
            result.put("rows", rows);
            return result;
        } finally {
            client.close();
        }
    }

    // ==================== 内部工具 ====================

    private void requireSafeName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法名称: " + name);
        }
    }

    private MilvusClientV2 milvusClient() {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .connectTimeoutMs(5000)
                .build());
    }

    private String esRequest(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            esBaseUrl.replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(15));
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("ES " + path + " -> HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ES 请求失败: " + e.getMessage(), e);
        }
    }

    private interface RowConsumer {
        void accept(ResultSet rs) throws Exception;
    }

    private interface MetaRowConsumer {
        void accept(ResultSetMetaData meta, ResultSet rs) throws Exception;
    }

    private void jdbcQuery(String sql, RowConsumer consumer) {
        jdbcQueryMeta(sql, (meta, rs) -> consumer.accept(rs));
    }

    private void jdbcQueryMeta(String sql, MetaRowConsumer consumer) {
        Properties props = new Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPassword);
        props.setProperty("characterEncoding", "utf8");
        props.setProperty("connectionCollation", "utf8mb4_unicode_ci");
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "15000");
        props.setProperty("useSSL", "false");
        props.setProperty("allowPublicKeyRetrieval", "true");
        try (Connection conn = DriverManager.getConnection(dbUrl, props);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            consumer.accept(ps.getMetaData(), rs);
        } catch (Exception e) {
            log.warn("[DataBrowser] MySQL 查询失败: {}", e.getMessage());
            throw new IllegalStateException("MySQL 查询失败: " + e.getMessage(), e);
        }
    }
}
