package com.yt.agent.service.impl;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.ReActImpAgent;
import com.yt.agent.agent.SummaryAgent;
import com.yt.agent.agent.dto.TaskSummaryResult;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 处理器：单 Agent 的 think-act 循环 + 结果摘要。
 *
 * <p>摘要输入改为"本次运行的消息流水"（context.transcript）：原实现把 Agent 的整个 Memory
 * 交给摘要模型，其中包含回放的历史轮次，导致摘要模型会把上一轮的结论也当成本轮结果复述。
 * 只摘要本轮新增内容，答案才干净。</p>
 */
@Component
public class ReactHandlerImpl implements AgentHandlerService {

    @Override
    public String handle(AgentContext context, AgentRequest request) {
        ReActImpAgent reActImpAgent = new ReActImpAgent(context);

        SummaryAgent summary = new SummaryAgent(context);
        summary.setSystemPrompt(summary.getSystemPrompt().replace("{{query}}", request.getQuery()));

        // 优先使用改写后的自包含问题（多轮指代消解结果），无改写时退回原始问题
        String effectiveQuery = context.getRewrittenQuery() != null && !context.getRewrittenQuery().isBlank()
                ? context.getRewrittenQuery() : request.getQuery();
        reActImpAgent.run(effectiveQuery);

        List<Message> transcript = context.transcriptSnapshot();
        // 流式请求下逐 token 推送 agent_stream（打字机效果）；内部会自动降级为一次性生成
        TaskSummaryResult result = summary.streamSummaryTaskResult(
                transcript.isEmpty() ? reActImpAgent.getMemory().getMessages() : transcript,
                request.getQuery());

        Map<String, Object> taskResult = new HashMap<>();
        taskResult.put("taskSummary", result.getTaskSummary());
        context.getPrinter().send("result", taskResult);

        return "";
    }

    @Override
    public Boolean support(AgentContext context, AgentRequest request) {
        return AgentType.REACT.getValue().equals(request.getAgentType());
    }

    @Override
    public int order() {
        return 20;
    }
}
