package com.yt.agent.platform.llm;

import lombok.Data;

/**
 * 单个角色的模型路由配置。
 *
 * <pre>
 * agent:
 *   llm:
 *     routes:
 *       planner: { provider: zhipuai, model: glm-4.5, temperature: 0.2, max-tokens: 4096 }
 *       summary: { provider: ollama,  model: qwen2.5:3b, temperature: 0.2 }
 * </pre>
 */
@Data
public class ModelRoute {

    /** 供应商标识，对应 Spring AI 的 ChatModel Bean（如 zhipuai → zhiPuAiChatModel，ollama → ollamaChatModel） */
    private String provider;

    /** 模型名；为空时使用该 ChatModel Bean 的默认模型 */
    private String model;

    /** 采样温度；为空时使用角色默认值 */
    private Double temperature;

    /** 单次响应最大 token 数 */
    private Integer maxTokens;

    /** 单次调用超时（毫秒），为空时使用全局配置 */
    private Long timeoutMs;

    /** 是否启用该路由 */
    private Boolean enabled = Boolean.TRUE;

    public boolean isUsable() {
        return !Boolean.FALSE.equals(enabled);
    }
}
