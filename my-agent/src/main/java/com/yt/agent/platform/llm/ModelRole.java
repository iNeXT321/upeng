package com.yt.agent.platform.llm;

/**
 * 模型角色 —— Agent 内部"谁在思考"的抽象。
 *
 * <p>为什么需要角色而不是直接用模型名：一个成熟 Agent 系统里，不同环节对模型能力/成本/延迟的要求完全不同。
 * 规划（planner）需要强推理但调用次数少；执行（executor）调用次数多、需要稳定 JSON/工具调用；
 * 摘要（summary）只需要便宜模型；查询改写（rewriter）要求低延迟低温度。
 * 把"角色"作为路由键，就能在不改代码的前提下把 planner 换成更强模型、把 summary 换成更便宜的模型。</p>
 *
 * <p>这个枚举是「模型供应商无关」的：路由表在配置里声明 role → provider/model，
 * 换厂商（智谱/通义/OpenAI/本地 Ollama）不需要动任何业务代码。</p>
 */
public enum ModelRole {

    /** 意图路由：判断用户请求该走 ReAct 还是 Plan-Solve，要求低延迟、低温度 */
    ROUTER("router", 0.0),

    /** 任务规划：拆解子任务，要求强推理 */
    PLANNER("planner", 0.2),

    /** 任务执行：工具调用密集，要求函数调用稳定 */
    EXECUTOR("executor", 0.3),

    /** 通用 ReAct：单Agent循环 */
    REACT("react", 0.3),

    /** 结果摘要：调用频繁，优先成本 */
    SUMMARY("summary", 0.2),

    /** 查询改写/轻量结构化任务：低温度、低延迟 */
    REWRITER("rewriter", 0.0);

    private final String key;
    private final double defaultTemperature;

    ModelRole(String key, double defaultTemperature) {
        this.key = key;
        this.defaultTemperature = defaultTemperature;
    }

    public String getKey() {
        return key;
    }

    public double getDefaultTemperature() {
        return defaultTemperature;
    }

    public static ModelRole fromKey(String key) {
        if (key != null) {
            for (ModelRole role : values()) {
                if (role.key.equalsIgnoreCase(key) || role.name().equalsIgnoreCase(key)) {
                    return role;
                }
            }
        }
        return REACT;
    }
}
