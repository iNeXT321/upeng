package com.yt.agent.agent;

import com.yt.agent.agent.dto.Memory;
import com.yt.agent.agent.enums.AgentState;
import com.yt.agent.eval.AgentTraceCollector;
import com.yt.agent.platform.runtime.AgentCancelledException;
import com.yt.agent.platform.runtime.AgentTimeoutException;
import com.yt.agent.platform.runtime.CancellationToken;
import com.yt.agent.printer.Printer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 基类：负责"循环控制"这一件事，具体的 think/act 交给子类。
 *
 * <p>本类承载了运行时治理的三条硬约束（原实现全部缺失）：
 * <ol>
 *   <li><b>步数复位</b>：每次 run 重置 currentStep。原实现复用一个 Agent 实例跑多轮时，
 *       第二步起步数不清零，直接命中 maxSteps 提前退出；</li>
 *   <li><b>取消检查</b>：每一步开始前检查取消令牌，让"停止生成"真正生效；</li>
 *   <li><b>截止时间</b>：整轮任务有 wall-clock 预算，避免"每步都成功但整体跑了一小时"。</li>
 * </ol>
 * </p>
 */
@Data
@Slf4j
public abstract class BaseAgent {

    //核心属性
    private String name;
    private String description;
    private String systemPrompt;
    private String nextStepPrompt;

    private Memory memory = new Memory();
    protected AgentContext context;

    // 执行控制
    private AgentState state = AgentState.IDLE;
    private int maxSteps = 10;
    private int currentStep = 0;
    private int duplicateThreshold = 2;

    // emitter
    Printer printer;

    // LLM 大模型
    private ChatClient chatClient;

    public String run(String query) {
        setState(AgentState.IDLE);
        currentStep = 0;

        // 1) 多轮历史注入：只在首次 run 且 Memory 为空时注入，避免同一次请求内多 Agent 重复叠加历史
        if (context != null) {
            List<Message> history = context.getHistory();
            if (getMemory().isEmpty() && history != null && !history.isEmpty()) {
                getMemory().addMessages(history);
                log.info("{} {} 注入历史消息 {} 条", context.getRequestId(), getName(), history.size());
            }
            // 2) 挂载流水监听器：本次新增消息会同步到 context.transcript，供请求结束后落库
            getMemory().setAppendListener(context::recordMessage);
        }

        if (query != null && !query.isEmpty()) {
            updateMemory(MessageType.USER, query, null, null);
        }

        List<String> results = new ArrayList<>();
        // 开启链路追踪（若上层已开启则复用同一 requestId 的 trace）
        String requestId = context != null ? context.getRequestId() : null;
        boolean traceOwner = false;
        if (requestId != null && AgentTraceCollector.get(requestId) == null) {
            AgentTraceCollector.start(requestId, query);
            traceOwner = true;
        }
        try {
            while (currentStep < maxSteps && state != AgentState.FINISHED) {
                checkInterrupt();
                currentStep++;
                log.info("{} {} Executing step {}/{}", requestId, getName(), currentStep, maxSteps);
                // 每一步用 StepSpan 包裹，自动计时并记录成败（可观测性）
                String stepResult;
                try (AgentTraceCollector.StepSpan span =
                             AgentTraceCollector.step(getName() + "-step" + currentStep, "第" + currentStep + "步 think-act")) {
                    stepResult = step();
                    span.result(stepResult, state != AgentState.ERROR);
                }
                results.add(stepResult);

            }

            if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                setState(AgentState.IDLE);
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
                AgentTraceCollector.record("max_steps_reached", "达到最大步数上限 " + maxSteps, false);
                log.warn("{} {} 达到最大步数 {}，强制终止", requestId, getName(), maxSteps);
            }
        } catch (AgentCancelledException e) {
            setState(AgentState.ERROR);
            AgentTraceCollector.record("cancelled", e.getMessage(), false);
            log.info("{} {} 已取消: {}", requestId, getName(), e.getMessage());
            throw e;
        } catch (AgentTimeoutException e) {
            setState(AgentState.ERROR);
            AgentTraceCollector.record("timeout", e.getMessage(), false);
            log.warn("{} {} 超时终止: {}", requestId, getName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            setState(AgentState.ERROR);
            AgentTraceCollector.record("error", "执行异常: " + e.getMessage(), false);
            throw e;
        } finally {
            if (traceOwner && requestId != null) {
                AgentTraceCollector.Trace trace = AgentTraceCollector.finish(requestId);
                if (trace != null) {
                    log.info("\n{}", trace.prettyPrint());
                }
            }
        }
        return results.isEmpty() ? "No steps executed" : results.get(results.size() - 1);
    }

    /**
     * 中断检查点：取消优先于超时（用户主动停止应立即生效，不需要再判断预算）。
     */
    protected void checkInterrupt() {
        if (context == null) {
            return;
        }
        CancellationToken token = context.getCancellation();
        if (token != null) {
            token.throwIfCancelled();
        }
        if (context.isExpired()) {
            throw new AgentTimeoutException(Math.max(0, context.getDeadlineEpochMs() - System.currentTimeMillis()));
        }
    }

    // 执行一个步骤,由子类实现
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    /**
     * 更新代理记忆
     */
    public void updateMemory(MessageType role, String content, String base64Image, List<ToolResponseMessage.ToolResponse> toolResponses) {
        Message message;
        switch (role) {
            case USER:
                message = new UserMessage(content);
                break;
            case SYSTEM:
                message = new SystemMessage(content);
                break;
            case ASSISTANT:
                message = new AssistantMessage(content);
                break;
            case TOOL:
                message = new ToolResponseMessage(toolResponses);
                break;
            default:
                throw new IllegalArgumentException("Unsupported role type: " + role);
        }
        memory.addMessage(message);
    }
}
