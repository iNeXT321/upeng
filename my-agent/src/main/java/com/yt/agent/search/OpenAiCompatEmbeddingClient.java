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
 * Embedding 客户端 —— 走 OpenAI 兼容 /v1/embeddings 协议
 *
 * <p>为什么自己写 HTTP 而不用 Spring AI 的 OpenAiEmbeddingModel：主应用的
 * spring.ai.openai 已被 DeepSeek 聊天模型占用（DeepSeek 没有 embedding 端点），
 * embedding 需要独立的 base-url/model/key。OpenAI 兼容协议是事实标准——
 * 本地 Ollama（http://127.0.0.1:11434/v1）、硅基流动、DashScope 兼容模式
 * 都可以直接切换，只需改配置，代码零改动。</p>
 *
 * <p>降级约定：调用失败抛 {@link EmbeddingException}，由上层决定降级
 * （HybridSchemaLinker 回退到纯 ES 关键词 + 规则打分）。</p>
 */
@Slf4j
public class OpenAiCompatEmbeddingClient {

    /** embedding 调用失败统一异常：上层据此做降级决策 */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public OpenAiCompatEmbeddingClient(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && model != null && !model.isBlank();
    }

    public int dimensions() {
        return 0; // 由配置 dim 指定，Milvus 建集合时以配置为准
    }

    /** 批量向量化：schema 文档量小（几十~几百条），逐条调用即可，避免批大小限制差异 */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(text));
        }
        return vectors;
    }

    public float[] embedOne(String text) {
        if (!isConfigured()) {
            throw new EmbeddingException("embedding 未配置 base-url/model", null);
        }
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EmbeddingException("embedding HTTP " + resp.statusCode() + ": "
                        + truncate(resp.body()), null);
            }
            JSONObject json = JSON.parseObject(resp.body());
            JSONArray data = json.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                throw new EmbeddingException("embedding 返回为空: " + truncate(resp.body()), null);
            }
            JSONArray arr = data.getJSONObject(0).getJSONArray("embedding");
            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                vec[i] = arr.getFloatValue(i);
            }
            return vec;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("embedding 调用失败: " + e.getMessage(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
