package com.yt.agent.platform.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具治理配置。
 *
 * <pre>
 * agent:
 *   tools:
 *     deny: [shell_exec, delete_file]
 *     max-result-chars: 12000
 *     call-timeout-ms: 120000
 *     max-identical-calls: 2
 *     audit: true
 * </pre>
 *
 * <p>默认策略是"放行所有已注册工具"，通过 {@code deny} 做减法、{@code allow} 做加法。
 * 为什么不默认全拒绝：MCP 工具是动态发现的，默认全拒会导致新接入的工具"莫名其妙不生效"，
 * 排查成本高。真正的权限边界在数据层（只读账号、表白名单），工具治理是第二道防线。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent.tools")
public class ToolPolicyProperties {

    /** 仅允许这些工具（为空表示不启用白名单） */
    private List<String> allow = new ArrayList<>();

    /** 禁止这些工具（优先级高于 allow） */
    private List<String> deny = new ArrayList<>();

    /** 单次工具返回结果的最大字符数：超长结果会污染上下文、推高成本 */
    private int maxResultChars = 12_000;

    /** 工具调用超时（毫秒） */
    private long callTimeoutMs = 120_000L;

    /**
     * 同一工具 + 相同入参的最大调用次数。
     * 超过后返回"重复调用已抑制"，而不是继续执行——这是防止模型陷入死循环的硬约束。
     */
    private int maxIdenticalCalls = 2;

    /** 是否打印审计日志（工具名、入参摘要、耗时、结果长度） */
    private boolean audit = true;
}
