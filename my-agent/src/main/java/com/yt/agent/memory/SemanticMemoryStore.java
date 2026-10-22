package com.yt.agent.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 语义记忆库（Semantic Memory）—— 问数场景的长期记忆
 *
 * <p>与短期记忆（Memory 会话消息列表）的分工：
 * <ul>
 *   <li>短期记忆：本次会话的对话与工具轨迹，会话结束即弃；</li>
 *   <li>语义记忆：跨会话沉淀的<b>业务知识</b>——指标口径、业务黑话映射、用户偏好。
 *       问数场景中"营收到底含不含退款""活跃用户的定义是登录还是下单"这类口径问题，
 *       模型猜错的代价远大于查不到数，必须用确定性的记忆库钉死。</li>
 * </ul>
 * </p>
 *
 * <p>三类记忆条目：
 * <ol>
 *   <li><b>METRIC</b> 指标口径：指标名 → 精确定义 + 计算SQL片段 + 注意事项；</li>
 *   <li><b>TERM</b> 业务术语：黑话/简称 → 标准词映射（"大盘"→全渠道GMV）；</li>
 *   <li><b>PREFERENCE</b> 用户偏好：某用户/部门习惯的默认维度、格式、时间粒度。</li>
 * </ol>
 * </p>
 *
 * <p>检索方式：当前为关键词匹配（问题中包含指标名/术语即命中，拼入 System Prompt）。
 * 演进方向：条目向量化入 PGVector 做语义检索（项目已有 pgvector 依赖），
 * 并支持「查询成功后回写」——把用户确认过的 问题→SQL 对沉淀为新记忆，形成数据飞轮。</p>
 */
@Slf4j
public class SemanticMemoryStore {

    private static final String DEFAULT_MEMORY_CONFIG = "nl2sql/semantic-memory.json";

    /** 记忆条目：type -> entries */
    private final Map<String, List<MemoryEntry>> memoryIndex = new ConcurrentHashMap<>();

    public SemanticMemoryStore() {
        this(DEFAULT_MEMORY_CONFIG);
    }

    public SemanticMemoryStore(String configLocation) {
        load(configLocation);
    }

    private void load(String configLocation) {
        try {
            ClassPathResource resource = new ClassPathResource(configLocation);
            if (!resource.exists()) {
                log.warn("[SemanticMemory] 配置 {} 不存在，语义记忆为空", configLocation);
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                List<MemoryEntry> entries = JSON.parseObject(json, new TypeReference<List<MemoryEntry>>() {
                });
                if (entries != null) {
                    for (MemoryEntry entry : entries) {
                        memoryIndex.computeIfAbsent(entry.getType(), k -> new ArrayList<>()).add(entry);
                    }
                }
            }
            log.info("[SemanticMemory] 加载完成: {}", memoryIndex.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().size() + "条")
                    .collect(Collectors.joining(", ")));
        } catch (Exception e) {
            log.error("[SemanticMemory] 加载失败: {}", e.getMessage());
        }
    }

    /**
     * 根据用户问题检索相关记忆，拼装为可注入 System Prompt 的文本。
     * 命中逻辑：问题包含条目的 name 或任一 alias（不区分大小写）。
     *
     * @param query 用户问题（应为改写后的自包含问题）
     * @return 注入文本；无命中时返回空串
     */
    public String retrieveForPrompt(String query) {
        if (query == null || query.isBlank() || memoryIndex.isEmpty()) {
            return "";
        }
        String q = query.toLowerCase();
        List<MemoryEntry> hits = new ArrayList<>();
        for (List<MemoryEntry> entries : memoryIndex.values()) {
            for (MemoryEntry e : entries) {
                if (match(e, q)) {
                    hits.add(e);
                }
            }
        }
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## 业务口径与术语（必须严格遵守，优先级高于你的常识推断）\n");
        for (MemoryEntry e : hits) {
            sb.append("- 【").append(e.getType()).append("】").append(e.getName()).append(": ")
                    .append(e.getDefinition());
            if (e.getSqlFragment() != null && !e.getSqlFragment().isBlank()) {
                sb.append(" 参考SQL: ").append(e.getSqlFragment());
            }
            if (e.getNote() != null && !e.getNote().isBlank()) {
                sb.append(" 注意: ").append(e.getNote());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private boolean match(MemoryEntry e, String lowerQuery) {
        if (e.getName() != null && lowerQuery.contains(e.getName().toLowerCase())) return true;
        if (e.getAliases() != null) {
            for (String alias : e.getAliases()) {
                if (alias != null && !alias.isBlank() && lowerQuery.contains(alias.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 运行时回写记忆（数据飞轮）：查询成功后沉淀「问题→SQL」对，供后续相似问题复用。
     * 当前存内存（重启丢失）；生产应落库并向量化，检索时按相似度召回历史成功SQL作为few-shot。
     */
    public void remember(String type, String name, String definition, String sqlFragment) {
        MemoryEntry entry = MemoryEntry.builder()
                .type(type).name(name).definition(definition).sqlFragment(sqlFragment)
                .build();
        memoryIndex.computeIfAbsent(type, k -> new ArrayList<>()).add(entry);
        log.info("[SemanticMemory] 回写记忆: [{}] {} ", type, name);
    }

    public Map<String, List<MemoryEntry>> getAll() {
        return Map.copyOf(memoryIndex);
    }

    /**
     * 记忆条目
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryEntry {
        /** METRIC=指标口径 / TERM=业务术语 / PREFERENCE=用户偏好 */
        private String type;
        /** 标准名称，如 "月活跃用户数" */
        private String name;
        /** 别名/黑话，如 ["MAU","月活"] */
        private List<String> aliases;
        /** 精确定义 */
        private String definition;
        /** 计算口径对应的 SQL 片段（few-shot 锚点） */
        private String sqlFragment;
        /** 易错点提醒 */
        private String note;
    }
}
