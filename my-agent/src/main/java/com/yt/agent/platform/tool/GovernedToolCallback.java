package com.yt.agent.platform.tool;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.platform.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具治理装饰器：为任意 {@link ToolCallback}（MCP 远程工具或本地 @Tool）统一加上
 * 「白名单 + 超时 + 结果截断 + 重复抑制 + 审计 + 指标」。
 *
 * <p>为什么工具必须治理，而不能"注册进来就交给模型随便调"：</p>
 * <ol>
 *   <li><b>超时</b>：MCP Server 是一个独立进程/远端服务，它挂住会让整个 Agent 卡死；
 *       Spring AI 的 MCP 客户端超时是全局配置，做不到"按工具"治理；</li>
 *   <li><b>结果长度</b>：一个返回 5 万行 JSON 的工具能瞬间打爆上下文，
 *       导致后续推理全部失效——这比报错更隐蔽；</li>
 *   <li><b>重复调用</b>：模型陷入"调一次没看懂，原样再调一次"的循环是真实高频问题，
 *       靠提示词约束不可靠，必须有硬性抑制；</li>
 *   <li><b>审计</b>：谁在什么时候让 Agent 查了什么数据，是安全合规的必答题。</li>
 * </ol>
 *
 * <p>注意：这里不做"参数校验"——参数 schema 由工具定义（JSON Schema）约束，
 * 业务级校验应在工具实现内部完成，装饰器只做通用治理。</p>
 */
@Slf4j
public class GovernedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolPolicyProperties policy;
    private final AgentMetrics metrics;
    private final AgentContext context;
    private final ExecutorService executor;

    public GovernedToolCallback(ToolCallback delegate, ToolPolicyProperties policy,
                                AgentMetrics metrics, AgentContext context, ExecutorService executor) {
        this.delegate = delegate;
        this.policy = policy;
        this.metrics = metrics;
        this.context = context;
        this.executor = executor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return doCall(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doCall(toolInput, toolContext);
    }

    private String doCall(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();

        if (!permitted(name)) {
            metrics.toolCall(name, false, 0, false);
            log.warn("[ToolGovernance] 工具 {} 被策略拒绝 requestId={}", name,
                    context == null ? "-" : context.getRequestId());
            return "【工具不可用】工具 " + name + " 未在允许列表中，请改用其他工具或直接回答用户。";
        }

        // 重复调用抑制：入参归一化（去空白）后再比较，避免 JSON 格式化差异绕过检测
        String fingerprint = name + "|" + (toolInput == null ? "" : toolInput.replaceAll("\\s+", ""));
        int count = context == null ? 1
                : context.getToolCallCounter().merge(fingerprint, 1, Integer::sum);
        if (count > Math.max(1, policy.getMaxIdenticalCalls())) {
            metrics.toolCall(name, false, 0, false);
            log.warn("[ToolGovernance] 抑制重复调用 tool={} 次数={}", name, count);
            return "【重复调用已抑制】已用相同参数调用 " + name + " " + (count - 1)
                    + " 次，结果不会变化。请修改参数、换用其他工具，或基于已有结果继续推理。";
        }

        long start = System.currentTimeMillis();
        Future<String> future = null;
        try {
            future = executor.submit(() -> toolContext == null
                    ? delegate.call(toolInput)
                    : delegate.call(toolInput, toolContext));
            String raw = future.get(policy.getCallTimeoutMs(), TimeUnit.MILLISECONDS);
            long cost = System.currentTimeMillis() - start;
            boolean truncated = raw != null && raw.length() > policy.getMaxResultChars();
            String result = truncated ? truncate(raw) : raw;
            metrics.toolCall(name, true, cost, truncated);
            audit(name, toolInput, cost, raw == null ? 0 : raw.length(), true, null);
            return result;
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            long cost = System.currentTimeMillis() - start;
            metrics.toolCall(name, false, cost, false);
            audit(name, toolInput, cost, 0, false, "timeout");
            return "【工具超时】" + name + " 执行超过 " + policy.getCallTimeoutMs()
                    + "ms 未返回，已中止。请换用其他工具或缩小查询范围。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.toolCall(name, false, System.currentTimeMillis() - start, false);
            return "【工具调用被中断】" + name;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            long cost = System.currentTimeMillis() - start;
            metrics.toolCall(name, false, cost, false);
            audit(name, toolInput, cost, 0, false, cause.getMessage());
            log.warn("[ToolGovernance] 工具 {} 执行异常: {}", name, cause.getMessage());
            // 把异常转成结构化 Observation 返回给模型，而不是抛出中断整个任务：
            // 模型可以据此换参数/换工具重试，这是 Agent 自愈能力的基础
            return "【工具执行失败】" + name + ": " + safeMessage(cause)
                    + "\n请检查参数是否正确，或改用其他工具。不要原样重试。";
        }
    }

    private boolean permitted(String name) {
        if (name == null) {
            return false;
        }
        if (policy.getDeny() != null && policy.getDeny().stream().anyMatch(name::equalsIgnoreCase)) {
            return false;
        }
        return policy.getAllow() == null || policy.getAllow().isEmpty()
                || policy.getAllow().stream().anyMatch(name::equalsIgnoreCase);
    }

    /** 保留头部（结论/主数据）+ 尾部（总量/分页信息），中间标注截断 */
    private String truncate(String raw) {
        int max = policy.getMaxResultChars();
        int head = (int) (max * 0.7);
        int tail = max - head;
        return raw.substring(0, head)
                + "\n...[工具结果过长，中间 " + (raw.length() - max) + " 字符已截断；"
                + "如需完整数据请加过滤条件或分页查询]...\n"
                + raw.substring(raw.length() - tail);
    }

    private void audit(String tool, String input, long costMs, int resultChars, boolean success, String error) {
        if (!policy.isAudit()) {
            return;
        }
        log.info("[ToolAudit] requestId={} tool={} cost={}ms success={} resultChars={} input={} error={}",
                context == null ? "-" : context.getRequestId(), tool, costMs, success, resultChars,
                abbreviate(input, 300), error == null ? "-" : abbreviate(error, 200));
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : abbreviate(msg, 300);
    }

    private String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
