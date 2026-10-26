package com.yt.agent.service.impl;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.ExecutorAgent;
import com.yt.agent.agent.PlanningAgent;
import com.yt.agent.agent.SummaryAgent;
import com.yt.agent.agent.dto.TaskSummaryResult;
import com.yt.agent.agent.enums.AgentState;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.platform.runtime.UnsupportedAgentTypeException;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import com.yt.agent.utils.ThreadUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Plan-Solve 处理器：先规划，再逐步执行，最后汇总。
 *
 * <p>本次修复的四个生产级缺陷（都是原实现里真实存在的问题）：</p>
 * <ol>
 *   <li><b>并行子任务无超时</b>：{@code CountDownLatch.await()} 无参调用会永久阻塞，
 *       任一子任务卡死（模型不返回、工具挂住）就会让整条请求线程泄漏。改为带超时等待 +
 *       超时后取消子任务的令牌；</li>
 *   <li><b>子任务共享可变上下文</b>：多个 Executor 共用同一个 AgentContext，
 *       导致工具重复调用计数互相干扰（A 任务调用过的工具，B 任务同参数会被误判为重复）。
 *       改为每个子任务派生独立上下文，只共享只读字段与 printer/transcript；</li>
 *   <li><b>结果被重复记录</b>：子任务消息既被 Memory 监听器写入 transcript，
 *       又被手动复制回主 Executor 的 Memory，落库时会出现成对的重复消息。改为统一以 transcript 为准；</li>
 *   <li><b>异常无收口</b>：任一子任务抛异常会让 latch 永远不归零。改为 finally 中 countDown，
 *       并把异常转成该子任务的结果文本，保证整体流程可继续。</li>
 * </ol>
 *
 * <p>为什么并行执行是必要的：Plan-Solve 的价值在于把"收集 A / 收集 B / 收集 C"这类
 * 相互独立的子任务并发化。串行执行会让时延随子任务数线性增长，
 * 而 LLM 调用本身就是长 I/O，并发收益非常直接。</p>
 */
@Slf4j
@Component
public class PlanSolveHandlerImpl implements AgentHandlerService {

    @Resource
    private AgentConfig agentConfig;

    /** 子任务并行等待上限：超过则认为子任务失控，主动放弃并汇总已有结果 */
    private static final long SUBTASK_TIMEOUT_MS = 10 * 60 * 1000L;

    @Override
    public String handle(AgentContext agentContext, AgentRequest request) {
        PlanningAgent planning = new PlanningAgent(agentContext);
        ExecutorAgent executor = new ExecutorAgent(agentContext);
        SummaryAgent summary = new SummaryAgent(agentContext);
        summary.setSystemPrompt(summary.getSystemPrompt().replace("{{query}}", request.getQuery()));

        // 优先使用改写后的自包含问题（多轮指代消解结果）
        String planningQuery = agentContext.getRewrittenQuery() != null && !agentContext.getRewrittenQuery().isBlank()
                ? agentContext.getRewrittenQuery() : agentContext.getQuery();

        String planningResult;
        try {
            planningResult = planning.run(planningQuery);
        } catch (RuntimeException e) {
            if (e instanceof com.yt.agent.platform.runtime.AgentCancelledException
                    || e instanceof com.yt.agent.platform.runtime.AgentTimeoutException) {
                throw e;
            }
            log.error("{} 规划阶段失败", agentContext.getRequestId(), e);
            agentContext.getPrinter().send("error", Map.of(
                    "message", "任务规划失败：" + e.getMessage(),
                    "stage", "planning"));
            return "";
        }
        log.info("handle planningResult: {}", planningResult);

        int stepIdx = 0;
        int maxStepNum = agentConfig.getPlannerMaxSteps();

        while (stepIdx <= maxStepNum) {
            agentContext.getCancellation();
            if (agentContext.isCancelled()) {
                throw new com.yt.agent.platform.runtime.AgentCancelledException("用户终止");
            }
            if (planningResult == null || planningResult.isBlank() || "finish".equals(planningResult)) {
                break;
            }

            List<String> planningResults = Arrays.stream(planningResult.split("<sep>"))
                    .map(String::trim)
                    .filter(task -> !task.isEmpty())
                    .map(task -> "你的任务是：" + task)
                    .toList();
            if (planningResults.isEmpty()) {
                break;
            }

            String executorResult = planningResults.size() == 1
                    ? executor.run(planningResults.get(0))
                    : runParallel(agentContext, planningResults);

            log.info("step:{} handle executorResult: {}", stepIdx, executorResult);
            planningResult = planning.run(executorResult);
            log.info("step:{} handle planningResult: {}", stepIdx, planningResult);

            if ("finish".equals(planningResult)) {
                // 流式请求下逐 token 推送 agent_stream（打字机效果）；内部会自动降级
                TaskSummaryResult result = summary.streamSummaryTaskResult(snapshotForSummary(agentContext, executor), request.getQuery());
                Map<String, Object> taskResult = new HashMap<>();
                taskResult.put("taskSummary", result.getTaskSummary());
                agentContext.getPrinter().send("result", taskResult);
                return "";
            }
            if (planning.getState() == AgentState.IDLE || executor.getState() == AgentState.IDLE) {
                agentContext.getPrinter().send("result", "达到最大迭代次数，任务终止。");
                return "";
            }
            if (planning.getState() == AgentState.ERROR || executor.getState() == AgentState.ERROR) {
                agentContext.getPrinter().send("result", "任务执行异常，任务终止。");
                return "";
            }
            stepIdx++;
        }

        // 循环因预算/异常退出时也要给用户一个结论，而不是静默结束
        TaskSummaryResult result = summary.streamSummaryTaskResult(snapshotForSummary(agentContext, executor), request.getQuery());
        Map<String, Object> taskResult = new HashMap<>();
        taskResult.put("taskSummary", result.getTaskSummary());
        agentContext.getPrinter().send("result", taskResult);
        return "";
    }

    /**
     * 并行执行一组独立子任务。
     * 每个子任务拥有独立的 Memory 与工具调用计数，避免相互污染；
     * printer / transcript / 取消令牌共享，保证输出连续、取消能穿透。
     */
    private String runParallel(AgentContext parent, List<String> tasks) {
        Map<String, String> taskResults = new ConcurrentHashMap<>();
        CountDownLatch latch = ThreadUtil.getCountDownLatch(tasks.size());
        List<ExecutorAgent> slaves = new ArrayList<>(tasks.size());

        for (String task : tasks) {
            AgentContext child = deriveContext(parent);
            ExecutorAgent slave = new ExecutorAgent(child);
            slaves.add(slave);
            ThreadUtil.execute(() -> {
                try {
                    taskResults.put(task, slave.run(task));
                } catch (com.yt.agent.platform.runtime.AgentCancelledException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("[PlanSolve] 子任务失败 task={}: {}", task, e.getMessage());
                    taskResults.put(task, "子任务执行失败：" + e.getMessage());
                } finally {
                    // 必须放在 finally：否则异常会导致 latch 永不归零，主线程永久阻塞
                    latch.countDown();
                }
            });
        }

        boolean completed = false;
        try {
            completed = latch.await(SUBTASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!completed) {
            log.warn("[PlanSolve] 并行子任务等待超时({}ms)，已汇总部分结果", SUBTASK_TIMEOUT_MS);
            if (parent.getCancellation() != null) {
                parent.getCancellation().cancel("subtask_timeout");
            }
        }

        // 按任务顺序拼接，保证结果稳定（ConcurrentHashMap 的遍历顺序不可依赖）
        StringBuilder sb = new StringBuilder();
        for (String task : tasks) {
            String value = taskResults.get(task);
            sb.append("【子任务】").append(task).append('\n')
                    .append(value == null ? "（超时未返回结果）" : value).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 派生只读共享 + 可写隔离的子上下文。
     * 注意：history 只读共享，transcript 共享（用于统一落库），toolCallCounter 必须独立。
     */
    private AgentContext deriveContext(AgentContext parent) {
        return AgentContext.builder()
                .requestId(parent.getRequestId())
                .sessionId(parent.getSessionId())
                .userId(parent.getUserId())
                .query(parent.getQuery())
                .task(parent.getTask())
                .printer(parent.getPrinter())
                .dateInfo(parent.getDateInfo())
                .isStream(parent.getIsStream())
                .sopPrompt(parent.getSopPrompt())
                .basePrompt(parent.getBasePrompt())
                .agentType(parent.getAgentType())
                .availableTools(parent.getAvailableTools())
                .chatClient(parent.getChatClient())
                .llm(parent.getLlm())
                .semanticMemoryPrompt(parent.getSemanticMemoryPrompt())
                .rewrittenQuery(parent.getRewrittenQuery())
                .history(parent.getHistory())
                .transcript(parent.getTranscript())
                .cancellation(parent.getCancellation())
                .deadlineEpochMs(parent.getDeadlineEpochMs())
                .toolCallCounter(new ConcurrentHashMap<>())
                .build();
    }

    /**
     * 汇总输入：优先使用本次运行的消息流水（覆盖 planner/executor/子任务）；
     * 若流水为空（例如异常路径），退回主 Executor 的 Memory。
     */
    private List<org.springframework.ai.chat.messages.Message> snapshotForSummary(AgentContext context, ExecutorAgent executor) {
        List<org.springframework.ai.chat.messages.Message> transcript = context.transcriptSnapshot();
        return transcript.isEmpty() ? executor.getMemory().getMessages() : transcript;
    }

    @Override
    public Boolean support(AgentContext agentContext, AgentRequest request) {
        return AgentType.PLAN_SOLVE.getValue().equals(request.getAgentType());
    }

    @Override
    public int order() {
        return 20;
    }
}
