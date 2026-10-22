package com.yt.agent.platform.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 模型网关 —— 业务代码与模型供应商之间唯一的接缝。
 *
 * <p>所有 Agent 都只依赖本接口，不依赖任何具体厂商的 ChatModel。
 * 这样做的直接收益：
 * <ol>
 *   <li>换模型/换厂商只改配置，不改代码；</li>
 *   <li>可以按角色分配不同模型（成本与能力的精细权衡）；</li>
 *   <li>可以在测试里注入 Mock 实现，做确定性测试；</li>
 *   <li>模型调用治理（超时/重试/降级/计量）集中在一处，不会散落在各个 Agent 里。</li>
 * </ol>
 * </p>
 */
public interface LlmGateway {

    /**
     * 获取指定角色使用的 ChatClient（已绑定模型、温度、日志 Advisor）。
     * 同一角色返回的是缓存实例，可放心在构造器中调用。
     */
    ChatClient chat(ModelRole role);

    /**
     * 获取指定角色使用的 ChatModel（已包装超时、重试、降级、计量）。
     * 需要直接操作 Prompt 或做流式输出时使用。
     */
    ChatModel model(ModelRole role);

    /**
     * 构建该角色的工具调用选项。
     *
     * <p>之所以由网关提供，而不是各 Agent 自己 {@code ToolCallingChatOptions.builder()}：
     * 模型名必须与路由一致，否则会出现"路由到 A 模型、实际请求 B 模型"的隐蔽错配。
     * 同时统一关闭 Spring AI 内置工具执行（由 Agent 自己维护 think-act 循环），
     * 保证工具调用过程可观测、可中断、可评测。</p>
     */
    ToolCallingChatOptions toolOptions(ModelRole role);

    /** 该角色实际生效的 "provider:model" 描述，用于日志与健康检查 */
    String describe(ModelRole role);

    /** 该角色当前是否可用（存在可用 ChatModel 且路由未禁用） */
    boolean available(ModelRole role);
}
