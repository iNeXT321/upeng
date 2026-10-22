package com.yt.agent.platform.guard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 护栏配置。
 *
 * <pre>
 * agent:
 *   guard:
 *     enabled: true
 *     max-input-chars: 8000
 *     block-on-injection: true
 *     injection-threshold: 1.0
 *     mask-pii: true
 *     mask-phone: false      # 手机号常作为查询条件，默认不改写
 *     scan-output: true
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "agent.guard")
public class GuardProperties {

    private boolean enabled = true;

    /** 单次输入最大字符数：过长输入既烧 token 也往往是粘贴攻击载荷 */
    private int maxInputChars = 8000;

    /** 命中提示词注入是否直接拦截（false 则只告警） */
    private boolean blockOnInjection = true;

    /** 注入判定阈值：多条弱特征累加达到阈值即拦截 */
    private double injectionThreshold = 1.0;

    /** 是否对身份证/银行卡/邮箱做脱敏 */
    private boolean maskPii = true;

    /** 是否对手机号脱敏（默认关闭：手机号经常是合法的查询条件） */
    private boolean maskPhone = false;

    /** 是否对输出做敏感信息扫描（防止模型回显密钥/口令） */
    private boolean scanOutput = true;
}
