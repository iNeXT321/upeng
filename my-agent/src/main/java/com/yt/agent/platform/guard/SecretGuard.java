package com.yt.agent.platform.guard;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 敏感信息护栏：阻止密钥、口令等机密被送进模型上下文。
 *
 * <p>为什么这是高危项：Agent 的输入会被完整拼进 Prompt，并发送给第三方模型服务；
 * 如果用户（或调用方系统）在问题里粘贴了 API Key、数据库口令，
 * 这些内容不仅会出境，还可能被模型原样复述在回答里，形成二次泄露。</p>
 *
 * <p>本类直接<b>拦截</b>而不是脱敏：密钥一旦进入模型就无法回收，
 * 提示用户"请勿在对话中粘贴密钥"比"悄悄打码后继续"更安全也更诚实。</p>
 */
@Slf4j
public class SecretGuard implements InputGuard {

    /** 常见密钥格式（OpenAI/智谱 sk-、AWS AKIA、GitHub ghp_） */
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)\\b(sk-[A-Za-z0-9_\\-]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9\\-]{10,})");

    /** 形如 password=xxx / api_key: xxx 的口令赋值 */
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?key|secret[_-]?key|token|password|passwd|pwd)\\s*[:=]\\s*[^\\s,;]{6,}");

    /** 私钥块 */
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

    /** 业务自定义敏感规则（来自 autobots.autoagent.sensitive_patterns） */
    private final Map<String, Pattern> customPatterns = new LinkedHashMap<>();

    public SecretGuard(Map<String, String> sensitivePatterns) {
        if (sensitivePatterns != null) {
            sensitivePatterns.forEach((name, regex) -> {
                if (regex != null && !regex.isBlank()) {
                    try {
                        customPatterns.put(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
                    } catch (Exception e) {
                        log.warn("[SecretGuard] 敏感规则 {} 正则非法，已忽略: {}", name, e.getMessage());
                    }
                }
            });
        }
    }

    @Override
    public String name() {
        return "secret";
    }

    @Override
    public GuardDecision inspect(String text, GuardContext context) {
        if (text == null || text.isBlank()) {
            return GuardDecision.pass(text);
        }
        if (API_KEY.matcher(text).find()) {
            return GuardDecision.block("secret_api_key",
                    "检测到疑似 API Key，已阻止发送。请勿在对话中粘贴密钥，改用环境变量或配置中心注入。");
        }
        if (PRIVATE_KEY.matcher(text).find()) {
            return GuardDecision.block("secret_private_key",
                    "检测到私钥内容，已阻止发送。私钥不应出现在对话或日志中。");
        }
        if (CREDENTIAL.matcher(text).find()) {
            return GuardDecision.block("secret_credential",
                    "检测到疑似口令/令牌赋值，已阻止发送。请先移除敏感凭据后重试。");
        }
        for (Map.Entry<String, Pattern> entry : customPatterns.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                return GuardDecision.block("secret_" + entry.getKey(),
                        "命中敏感信息规则「" + entry.getKey() + "」，已阻止本次请求。");
            }
        }
        return GuardDecision.pass(text);
    }
}
