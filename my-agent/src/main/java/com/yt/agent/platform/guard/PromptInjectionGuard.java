package com.yt.agent.platform.guard;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 提示词注入（Prompt Injection）检测。
 *
 * <p>为什么 Agent 比 Chatbot 更危险：Agent 能调用工具、读文件、查数据库、发请求。
 * 一旦注入成功，攻击者不是"让模型说错话"，而是"让模型替他执行操作"——
 * 例如让模型调用 SQL 工具读取敏感表，或把系统提示词原样吐出来泄露内部逻辑。</p>
 *
 * <p>实现取舍：只做<b>规则检测</b>，不做"意图理解"。原因：
 * <ol>
 *   <li>用模型判断注入本身也会被注入（自举悖论），且成本高、延迟大；</li>
 *   <li>规则可解释、可审计、可单测，命中后能明确告知用户触发哪条规则；</li>
 *   <li>真正的最后防线不在检测，而在"最小权限 + 工具护栏 + 人工确认"（见 SqlSafetyGuard / ToolGovernance）。</li>
 * </ol>
 * 因此本类定位是"提高攻击成本 + 留下审计证据"，而不是宣称能拦下所有注入。</p>
 */
@Slf4j
public class PromptInjectionGuard implements InputGuard {

    /** 强特征：命中基本可判定为注入 */
    private static final Map<Pattern, Double> PATTERNS = new LinkedHashMap<>();

    static {
        // 指令覆盖。（所有)?(的)? 双修饰语都要容忍："忽略之前所有的指令" 是最常见口语写法
        add(1.0, "忽略\\s*(之前|以上|前面|先前|上述)\\s*(的)?\\s*(所有|全部)?\\s*(的)?\\s*(指令|指示|要求|规则|设定)");
        add(1.0, "(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)");
        add(1.0, "(?i)disregard\\s+(all\\s+)?(previous|prior)\\s+(instructions?|prompts?)");
        // 系统提示词窃取：正装（输出...系统提示）与倒装（把系统提示词输出给我）都要覆盖
        add(1.0, "(输出|展示|打印|复述|告诉我|泄露|贴出).{0,10}(系统提示|系统指令|system\\s*prompt|开发者指令|初始指令|你的设定)");
        add(1.0, "(系统提示|系统指令|system\\s*prompt|开发者指令|初始指令|你的设定).{0,14}(输出|展示|打印|复述|告诉我|泄露|贴出|发我|给我)");
        add(1.0, "(?i)(reveal|show|print|repeat|leak)\\s+(me\\s+)?(your\\s+)?(system\\s+)?(prompt|instructions)");
        add(1.0, "(?i)(what\\s+(is|are)\\s+your\\s+(system\\s+)?(prompt|instructions))");
        // 角色劫持 / 越狱
        add(1.0, "(?i)\\b(jailbreak|DAN\\s+mode|do\\s+anything\\s+now|developer\\s+mode\\s+enabled)\\b");
        add(1.0, "(?i)you\\s+are\\s+now\\s+.{0,30}(unrestricted|without\\s+rules|no\\s+restrictions|an?\\s+AI\\s+without)");
        add(0.8, "(从现在开始|接下来|之后).{0,12}(你|你要|你必须).{0,8}(扮演|充当|假装).{0,12}(不受限制|没有限制|无规则|没有约束)");
        // 伪角色标记（OpenAI/ChatML 风格 token 注入）
        add(1.0, "<\\|\\s*(im_start|im_end|system|assistant|user)\\s*\\|>");
        add(0.6, "(?i)^\\s*\\[\\s*system\\s*\\]");
        add(0.6, "(?i)<\\s*/?\\s*system\\s*>");
        // 诱导执行危险动作
        add(0.8, "(?i)(执行|运行|调用).{0,8}(shell|bash|cmd|命令|脚本).{0,12}(删除|清空|上传|下载|外发)");
        add(0.8, "(?i)(drop|truncate)\\s+table|delete\\s+from\\s+\\w+\\s*(;|$)");
    }

    private static void add(double weight, String regex) {
        PATTERNS.put(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), weight);
    }

    private final double threshold;
    private final boolean block;

    public PromptInjectionGuard(double threshold, boolean block) {
        this.threshold = threshold > 0 ? threshold : 1.0;
        this.block = block;
    }

    @Override
    public String name() {
        return "prompt_injection";
    }

    @Override
    public GuardDecision inspect(String text, GuardContext context) {
        if (text == null || text.isBlank()) {
            return GuardDecision.pass(text);
        }
        double score = 0.0;
        List<String> hits = new ArrayList<>();
        for (Map.Entry<Pattern, Double> entry : PATTERNS.entrySet()) {
            if (entry.getKey().matcher(text).find()) {
                score += entry.getValue();
                hits.add(entry.getKey().pattern());
            }
        }
        if (score >= threshold) {
            log.warn("[Guard] 疑似提示词注入 requestId={} score={} hits={}",
                    context == null ? "-" : context.getRequestId(), score, hits);
            if (block) {
                return GuardDecision.block("prompt_injection",
                        "检测到疑似提示词注入内容，已阻止本次请求。若这是正常的业务提问，请换一种表述方式。");
            }
        }
        return GuardDecision.pass(text);
    }
}
