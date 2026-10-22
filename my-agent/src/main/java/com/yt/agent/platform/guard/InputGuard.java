package com.yt.agent.platform.guard;

/**
 * 输入护栏扩展点。
 *
 * <p>实现方只需关注"一条规则"：长度、注入、PII、敏感信息……由 {@link GuardChain} 负责编排与短路。
 * 这样新增一条规则不需要改动任何既有代码（开闭原则），也便于单独测试。</p>
 */
public interface InputGuard {

    /** 规则名，用于日志与指标 tag（低基数） */
    String name();

    /** 检查并可能改写文本 */
    GuardDecision inspect(String text, GuardContext context);
}
