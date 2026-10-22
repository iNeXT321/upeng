package com.yt.agent.eval;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 执行链路追踪（Observability / Tracing）
 *
 * <p>解决的问题：Agent 是多步非确定性系统，一次请求可能经历「规划→召回Schema→生成SQL→执行失败→
 * 反思重试→再执行→汇总」十几步。出问题时（答错、超时、烧token）如果只有最终结果，根本无法定位是哪一步坏的。
 * 可观测性是 Agent 从 Demo 走向生产的必备能力。</p>
 *
 * <p>每个 requestId 一条 Trace，记录每一步 Step：类型、输入摘要、输出摘要、耗时、token 消耗、成败。
 * 用途：① 线上问题回溯（复现某次错误回答的完整决策链）；② 性能分析（哪一步最慢/最烧token）；
 * ③ 评测归因（答错是召回错、SQL错还是解读错）；④ 可对接 SkyWalking/OpenTelemetry 做分布式追踪。</p>
 *
 * <p>设计：用 ThreadLocal + ConcurrentHashMap 双写。ThreadLocal 便于业务代码无侵入埋点（start/end 配对），
 * Map 便于跨线程（本项目 Executor 用 CompletableFuture 并行）按 requestId 汇总。生产环境应异步落库/上报，
 * 不阻塞主链路——这里用内存实现保证零依赖可跑通，落地时替换 report() 即可。</p>
 */
@Slf4j
public class AgentTraceCollector {

    /** 全局 trace 存储：requestId -> Trace */
    private static final Map<String, Trace> TRACE_STORE = new ConcurrentHashMap<>();

    /** 当前线程正在记录的 trace，便于无侵入埋点 */
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    /** 单条 trace 最大步骤数，防止死循环撑爆内存 */
    private static final int MAX_STEPS = 200;

    /**
     * 开启一次请求的追踪
     */
    public static Trace start(String requestId, String query) {
        Trace trace = new Trace();
        trace.setRequestId(requestId);
        trace.setQuery(query);
        trace.setStartTime(System.currentTimeMillis());
        TRACE_STORE.put(requestId, trace);
        CURRENT.set(trace);
        return trace;
    }

    /**
     * 记录一个步骤的开始，返回 StepSpan（用 try-with-resources 或手动 end 闭合）
     */
    public static StepSpan step(String type, String detail) {
        Trace trace = CURRENT.get();
        if (trace == null) {
            return StepSpan.NOOP;   // 未开启追踪时返回空操作，业务代码无需判空
        }
        return new StepSpan(trace, type, detail);
    }

    /** 便捷方法：记录一个瞬时事件步骤 */
    public static void record(String type, String detail, boolean success) {
        Trace trace = CURRENT.get();
        if (trace == null || trace.getSteps().size() >= MAX_STEPS) return;
        Step s = new Step();
        s.setType(type);
        s.setDetail(detail);
        s.setSuccess(success);
        s.setCostMs(0L);
        trace.getSteps().add(s);
    }

    /** 累加 token 消耗（从模型响应的 usage 累加） */
    public static void addTokens(int promptTokens, int completionTokens) {
        Trace trace = CURRENT.get();
        if (trace == null) return;
        trace.setTotalPromptTokens(trace.getTotalPromptTokens() + promptTokens);
        trace.setTotalCompletionTokens(trace.getTotalCompletionTokens() + completionTokens);
    }

    /**
     * 结束追踪，计算总耗时，返回完整 Trace（供日志/上报/接口返回）
     */
    public static Trace finish(String requestId) {
        Trace trace = TRACE_STORE.get(requestId);
        if (trace != null) {
            trace.setEndTime(System.currentTimeMillis());
            trace.setTotalCostMs(trace.getEndTime() - trace.getStartTime());
            log.info("[Trace] {} 完成: {}步, 耗时{}ms, tokens={}/{}",
                    requestId, trace.getSteps().size(), trace.getTotalCostMs(),
                    trace.getTotalPromptTokens(), trace.getTotalCompletionTokens());
        }
        CURRENT.remove();
        return trace;
    }

    public static Trace get(String requestId) {
        return TRACE_STORE.get(requestId);
    }

    /** 清理已完成的 trace，防内存泄漏（生产应定时清理或用带过期的缓存） */
    public static void evict(String requestId) {
        TRACE_STORE.remove(requestId);
    }

    /** 一个可闭合的步骤区间，自动计时 */
    public static class StepSpan implements AutoCloseable {
        static final StepSpan NOOP = new StepSpan();

        private final Trace trace;
        private final Step step;
        private final long start;
        private final boolean noop;

        private StepSpan() {
            this.trace = null; this.step = null; this.start = 0; this.noop = true;
        }

        StepSpan(Trace trace, String type, String detail) {
            this.trace = trace;
            this.start = System.currentTimeMillis();
            this.step = new Step();
            this.step.setType(type);
            this.step.setDetail(detail);
            this.noop = false;
        }

        /** 标记步骤输出与成败 */
        public StepSpan result(String output, boolean success) {
            if (!noop) {
                step.setOutput(output);
                step.setSuccess(success);
            }
            return this;
        }

        @Override
        public void close() {
            if (noop) return;
            step.setCostMs(System.currentTimeMillis() - start);
            if (trace.getSteps().size() < MAX_STEPS) {
                trace.getSteps().add(step);
            }
        }
    }

    @Data
    public static class Trace {
        private String requestId;
        private String query;
        private long startTime;
        private long endTime;
        private long totalCostMs;
        private int totalPromptTokens;
        private int totalCompletionTokens;
        private List<Step> steps = new ArrayList<>();

        /** 渲染为可读的链路文本，用于日志与调试接口 */
        public String prettyPrint() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Agent Trace [%s] ===%n", requestId));
            sb.append(String.format("问题: %s%n", query));
            sb.append(String.format("总耗时: %dms | tokens(prompt/completion): %d/%d | 步骤数: %d%n",
                    totalCostMs, totalPromptTokens, totalCompletionTokens, steps.size()));
            int i = 1;
            for (Step s : steps) {
                sb.append(String.format("  %2d. [%s] %s | %s | %dms%n",
                        i++, s.getType(),
                        s.isSuccess() ? "OK" : "FAIL",
                        clip(s.getDetail(), 60), s.getCostMs()));
            }
            return sb.toString();
        }

        private String clip(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }

    @Data
    public static class Step {
        private String type;      // plan/schema_recall/sql_gen/sql_exec/correct/summary...
        private String detail;    // 输入摘要
        private String output;    // 输出摘要
        private boolean success;
        private long costMs;
    }
}
