package com.yt.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.platform.llm.LlmGateway;
import com.yt.agent.platform.llm.ModelRole;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由型智能体（agentType=4）：先判断"这件事该用哪种执行形态"，再委派。
 *
 * <p>为什么需要 Router 而不是让用户自己选：用户不知道 ReAct 与 Plan-Solve 的区别，
 * 也不应该知道。让用户在"简单问答"上走 40 步规划，是又慢又贵；
 * 让"出一份竞品分析报告"走单轮 ReAct，则必然半途而废。</p>
 *
 * <p>实现是「规则优先 + 模型兜底」的双层决策：
 * <ol>
 *   <li><b>规则层</b>：明确的报告/多步/结构化产出诉求直接判为 Plan-Solve，
 *       明确的单点查询判为 ReAct。规则命中的好处是零延迟、零成本、可预测；</li>
 *   <li><b>模型层</b>：模糊请求交给小模型做二分类，只输出 JSON。
 *       失败（超时/格式错）一律降级为 ReAct——单轮失败的影响远小于误判成多步规划。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class RouterHandlerImpl implements AgentHandlerService {

    /** 多步/交付物特征：命中则倾向 Plan-Solve */
    private static final List<String> PLAN_HINTS = List.of(
            "报告", "ppt", "PPT", "方案", "调研", "分析报告", "竞品", "对比分析", "拆解",
            "然后", "接着", "分别", "多维度", "全面", "汇总", "整理成", "导出", "表格", "excel", "Excel");

    /** 单点查询特征：命中且问题较短，倾向 ReAct */
    private static final List<String> SIMPLE_HINTS = List.of(
            "是多少", "多少", "查一下", "查询", "统计", "排名", "top", "TOP", "有没有", "列出", "几");

    private final AgentHandlerFactory handlerFactory;
    private final LlmGateway llmGateway;

    public RouterHandlerImpl(AgentHandlerFactory handlerFactory, LlmGateway llmGateway) {
        this.handlerFactory = handlerFactory;
        this.llmGateway = llmGateway;
    }

    @Override
    public String handle(AgentContext context, AgentRequest request) {
        String query = context.getRewrittenQuery() != null && !context.getRewrittenQuery().isBlank()
                ? context.getRewrittenQuery() : request.getQuery();
        Decision decision = decide(query);
        log.info("{} [Router] query='{}' -> {} ({})",
                context.getRequestId(), abbreviate(query, 60), decision.target(), decision.reason());

        com.yt.agent.eval.AgentTraceCollector.record("route",
                decision.target() + ":" + decision.reason(), true);

        if (context.getIsStream() != null && context.getIsStream() && context.getPrinter() != null) {
            // 让用户知道系统选了哪条路径，避免"为什么这次这么慢"的困惑
            context.getPrinter().send("plan_thought",
                    "已识别任务类型：" + (decision.target() == AgentType.PLAN_SOLVE ? "多步规划执行" : "单轮推理执行")
                            + "（" + decision.reason() + "）");
        }
        return delegate(context, request, decision.target());
    }

    @Override
    public Boolean support(AgentContext context, AgentRequest request) {
        return AgentType.ROUTER.getValue().equals(request.getAgentType());
    }

    @Override
    public int order() {
        return 10;
    }

    /** 委派给具体执行形态的 handler */
    private String delegate(AgentContext context, AgentRequest request, AgentType target) {
        request.setAgentType(target.getValue());
        context.setAgentType(target.getValue());
        if (context.getPrinter() != null) {
            context.getPrinter().updateAgentType(target);
        }
        return handlerFactory.getHandler(context, request).handle(context, request);
    }

    Decision decide(String query) {
        if (query == null || query.isBlank()) {
            return new Decision(AgentType.REACT, "空问题，走单轮");
        }
        String lower = query.toLowerCase();
        boolean planHint = PLAN_HINTS.stream().anyMatch(h -> lower.contains(h.toLowerCase()));
        boolean simpleHint = SIMPLE_HINTS.stream().anyMatch(lower::contains);

        // 规则优先级：交付物/多步特征强于"多少"这类词（例如"分析各渠道销售额并出报告"同时含两者）
        if (planHint) {
            return new Decision(AgentType.PLAN_SOLVE, "命中多步/交付物特征");
        }
        if (simpleHint && query.length() <= 30) {
            return new Decision(AgentType.REACT, "短查询，单步可完成");
        }
        return llmClassify(query);
    }

    /**
     * 模型分类：只允许输出 JSON。任何异常都降级 ReAct——
     * 路由是"优化项"，不能成为请求失败的原因。
     */
    private Decision llmClassify(String query) {
        try {
            String raw = llmGateway.chat(ModelRole.ROUTER)
                    .prompt()
                    .system("""
                            你是任务路由器，只能输出 JSON，不要输出任何解释性文字。
                            判断用户请求应该用哪种执行方式：
                            - react：单步或很少几步即可完成，通常是查一次数据、问一个事实、做一个简单计算。
                            - plan_solve：需要拆成多个子任务、需要多轮检索后汇总、需要产出报告/PPT/表格等交付物。
                            输出格式：{"route":"react"} 或 {"route":"plan_solve"}
                            """)
                    .user(query)
                    .call()
                    .content();
            String route = extractRoute(raw);
            if ("plan_solve".equals(route)) {
                return new Decision(AgentType.PLAN_SOLVE, "模型判定需多步执行");
            }
            return new Decision(AgentType.REACT, "模型判定单轮执行");
        } catch (Exception e) {
            log.warn("[Router] 模型分类失败，降级 ReAct: {}", e.getMessage());
            return new Decision(AgentType.REACT, "分类失败降级");
        }
    }

    private String extractRoute(String raw) {
        if (raw == null) {
            return "react";
        }
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JSONObject json = JSON.parseObject(raw.substring(start, end + 1));
                String route = json.getString("route");
                return route == null ? "react" : route.trim().toLowerCase();
            }
        } catch (Exception ignored) {
            // 落到关键字兜底
        }
        return raw.toLowerCase().contains("plan_solve") ? "plan_solve" : "react";
    }

    private String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    record Decision(AgentType target, String reason) {
    }
}
