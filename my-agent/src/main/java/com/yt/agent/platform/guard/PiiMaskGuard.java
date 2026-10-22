package com.yt.agent.platform.guard;

import java.util.regex.Pattern;

/**
 * PII 脱敏护栏。
 *
 * <p>合规要求：把用户输入原样发给第三方模型，意味着手机号、身份证、邮箱等个人信息出境。
 * 在金融/医疗/政企场景这是明确的合规风险，因此需要在"进入模型之前"做最小化处理。</p>
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>身份证/邮箱默认脱敏</b>：几乎不会作为查询条件，脱敏无损业务；</li>
 *   <li><b>手机号默认不脱敏</b>：问数场景中"查这个号码的订单"是合理需求，脱敏会直接改变语义，
 *       因此做成开关，由部署方按合规要求决定；</li>
 *   <li><b>不做银行卡脱敏</b>：16~19 位连续数字与订单号/流水号高度混淆，误伤会破坏查询，
 *       交给下游数据脱敏或列级权限处理更合适。</li>
 * </ul>
 * </p>
 */
public class PiiMaskGuard implements InputGuard {

    /** 18 位身份证：前 6 位地区码保留，中间出生日期打码，末 4 位保留 */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<!\\d)([1-9]\\d{5})((19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx])(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "([A-Za-z0-9._%+-]{1,3})[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");

    private final boolean enabled;
    private final boolean maskPhone;

    public PiiMaskGuard(boolean enabled, boolean maskPhone) {
        this.enabled = enabled;
        this.maskPhone = maskPhone;
    }

    @Override
    public String name() {
        return "pii";
    }

    @Override
    public GuardDecision inspect(String text, GuardContext context) {
        if (!enabled || text == null || text.isBlank()) {
            return GuardDecision.pass(text);
        }
        String masked = text;
        boolean changed = false;

        if (ID_CARD.matcher(masked).find()) {
            masked = ID_CARD.matcher(masked).replaceAll(m -> m.group(1) + "********" + m.group(2).substring(6));
            changed = true;
        }
        if (EMAIL.matcher(masked).find()) {
            masked = EMAIL.matcher(masked).replaceAll(m -> m.group(1) + "***@" + m.group(2));
            changed = true;
        }
        if (maskPhone && PHONE.matcher(masked).find()) {
            masked = PHONE.matcher(masked).replaceAll(m -> m.group(1) + "****" + m.group(3));
            changed = true;
        }
        if (changed) {
            return GuardDecision.modified(masked, "pii", "已对输入中的身份证/邮箱等个人信息做脱敏处理");
        }
        return GuardDecision.pass(text);
    }
}
