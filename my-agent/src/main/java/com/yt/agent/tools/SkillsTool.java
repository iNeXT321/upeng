package com.yt.agent.tools;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent Skills 工具（遵循 agentskills.io 开放规范，与 Claude Code Skills / spring-ai-agent-utils 的 SkillsTool 同构实现）
 *
 * <p>什么是 Skill：一个包含 SKILL.md 的文件夹，用 YAML frontmatter 描述元数据(name/description)，
 * 正文是给 Agent 的自然语言指令（SOP）。Skill 提供"程序性记忆"——不是新增能力，而是指导 Agent
 * 如何组合已有工具完成特定领域任务。</p>
 *
 * <p>核心机制：渐进式披露（Progressive Disclosure），分三阶段，避免技能膨胀撑爆上下文：
 * <ol>
 *   <li>发现 Discovery：启动时扫描 skills 目录，只解析 frontmatter 的 name/description 建立轻量注册表，
 *       完整指令正文不进上下文；</li>
 *   <li>语义匹配 Matching：LLM 调用 {@code list_skills} 看到"技能名 + 描述"，自行判断当前任务是否命中某技能；</li>
 *   <li>执行 Execution：命中后 LLM 调用 {@code read_skill} 按需加载完整 SKILL.md 指令，再按指令编排其他工具。</li>
 * </ol>
 * </p>
 *
 * <p>技能目录结构（默认 classpath:/.agent/skills/）：
 * <pre>
 * .agent/skills/
 * └── research-report/
 *     ├── SKILL.md        # 必需：YAML frontmatter + Markdown 指令正文
 *     ├── references/     # 可选：补充参考文档
 *     ├── scripts/        # 可选：辅助脚本（需配合 Shell 类工具执行）
 *     └── assets/         # 可选：模板等资源
 * </pre>
 * </p>
 *
 * <p>与 Spring AI 的集成方式：本类不依赖任何 Spring AI 2.0 / spring-ai-agent-utils 特性，
 * 仅使用 Spring AI 1.0 已有的 {@code @Tool} / {@code @ToolParam} 注解，
 * 通过 {@code ToolCallbacks.from(skillsTool)} 注册为两个普通工具即可生效，无需改造现有 Agent 架构。</p>
 */
@Slf4j
@Data
public class SkillsTool {

    /**
     * 默认技能扫描路径（classpath 通配符）
     */
    public static final String DEFAULT_SKILLS_LOCATION = "classpath:/.agent/skills/*/SKILL.md";

    /**
     * 技能扫描路径，可由外部配置注入覆盖
     */
    private final String skillsLocation;

    /**
     * 技能注册表：name -> Skill。使用 LinkedHashMap 保持扫描顺序稳定，便于日志排查
     */
    private final Map<String, Skill> skillRegistry = new LinkedHashMap<>();

    public SkillsTool() {
        this(DEFAULT_SKILLS_LOCATION);
    }

    public SkillsTool(String skillsLocation) {
        this.skillsLocation = (skillsLocation == null || skillsLocation.isBlank())
                ? DEFAULT_SKILLS_LOCATION : skillsLocation;
        discoverSkills();
    }

    /**
     * 发现阶段：扫描技能目录，解析每个 SKILL.md 的 frontmatter 建立注册表。
     * 扫描失败不抛异常——技能是增强能力，缺失时 Agent 应能用普通工具继续工作（优雅降级）。
     */
    public void discoverSkills() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(skillsLocation);
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Skill skill = parseSkillMd(raw, resource);
                    if (skill != null) {
                        skillRegistry.put(skill.getName(), skill);
                        log.info("[SkillsTool] 发现技能: {} - {}", skill.getName(), skill.getDescription());
                    }
                } catch (Exception e) {
                    log.warn("[SkillsTool] 解析技能文件失败: {}, 原因: {}", resource.getDescription(), e.getMessage());
                }
            }
            log.info("[SkillsTool] 技能扫描完成, 共注册 {} 个技能: {}", skillRegistry.size(), skillRegistry.keySet());
        } catch (Exception e) {
            log.warn("[SkillsTool] 技能目录扫描失败({}), 当前无可用技能: {}", skillsLocation, e.getMessage());
        }
    }

    /**
     * 解析 SKILL.md：拆分 YAML frontmatter 与 Markdown 正文。
     * frontmatter 至少需要 name 与 description 两个字段（Agent Skills 规范要求）。
     */
    private Skill parseSkillMd(String raw, Resource resource) {
        String normalized = raw.replace("\r\n", "\n").trim();
        if (!normalized.startsWith("---")) {
            log.warn("[SkillsTool] {} 缺少 YAML frontmatter, 已跳过", resource.getDescription());
            return null;
        }
        // 定位 frontmatter 结束分隔符
        int end = normalized.indexOf("\n---", 3);
        if (end < 0) {
            log.warn("[SkillsTool] {} frontmatter 未正确闭合, 已跳过", resource.getDescription());
            return null;
        }
        String frontmatter = normalized.substring(3, end).trim();
        String body = normalized.substring(normalized.indexOf('\n', end + 1) + 1).trim();

        Map<String, Object> meta;
        try {
            meta = new Yaml().load(frontmatter);
        } catch (Exception e) {
            log.warn("[SkillsTool] {} frontmatter YAML 解析失败: {}", resource.getDescription(), e.getMessage());
            return null;
        }
        if (meta == null || meta.get("name") == null || meta.get("description") == null) {
            log.warn("[SkillsTool] {} frontmatter 缺少必填字段 name/description, 已跳过", resource.getDescription());
            return null;
        }

        Skill skill = new Skill();
        skill.setName(String.valueOf(meta.get("name")).trim());
        skill.setDescription(String.valueOf(meta.get("description")).trim());
        skill.setContent(body);
        // baseDir 供后续 FileSystem / Shell 类工具按需读取 references、scripts 等资源
        skill.setBaseDir(resolveBaseDir(resource));
        return skill;
    }

    /**
     * 推导技能所在目录（用于按需加载 references/scripts 等附属资源）
     */
    private String resolveBaseDir(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int idx = url.lastIndexOf('/');
            return idx > 0 ? url.substring(0, idx) : url;
        } catch (Exception e) {
            return resource.getDescription();
        }
    }

    @Tool(name = "list_skills",
            description = """
                    列出当前所有可用的技能(Skill)及其用途描述。
                    在开始处理一个任务时，如果任务可能属于某个专业领域（如深度调研并产出报告、数据分析、文档生成等），
                    应优先调用此工具查看是否有匹配的技能。技能封装了该领域的标准作业流程(SOP)，
                    能显著提升任务完成质量。若返回无技能，则直接使用普通工具完成任务。
                    """)
    public String listSkills() {
        if (skillRegistry.isEmpty()) {
            return "当前没有注册任何技能(Skill)，请直接使用可用工具完成任务。";
        }
        String list = skillRegistry.values().stream()
                .map(s -> String.format("- 技能名: %s\n  适用场景: %s", s.getName(), s.getDescription()))
                .collect(Collectors.joining("\n"));
        return "可用技能列表：\n" + list
                + "\n\n如果某个技能的适用场景与当前任务匹配，请调用 read_skill 并传入该技能名，加载完整执行指令。";
    }

    @Tool(name = "read_skill",
            description = """
                    按技能名加载指定技能的完整执行指令(SOP)。技能名必须来自 list_skills 的返回结果。
                    加载后必须严格按照返回的指令步骤执行任务，不要自行跳过或简化步骤。
                    """)
    public String readSkill(
            @ToolParam(description = "技能的准确名称，例如 'research-report'，必须与 list_skills 返回的技能名完全一致")
            String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return "技能名不能为空，请先调用 list_skills 查看可用技能名称。";
        }
        Skill skill = skillRegistry.get(skillName.trim());
        if (skill == null) {
            return String.format("技能 '%s' 不存在。请先调用 list_skills 获取准确的技能名称。可用技能: %s",
                    skillName, skillRegistry.keySet());
        }
        log.info("[SkillsTool] 激活技能: {}", skill.getName());
        return String.format("""
                已加载技能 [%s]。请严格按照以下指令执行任务，完成后再调用其他工具或结束任务。
                技能资源目录(baseDir): %s
                
                ===== 技能指令开始 =====
                %s
                ===== 技能指令结束 =====
                """, skill.getName(), skill.getBaseDir(), skill.getContent());
    }

    /**
     * 技能实体：对应磁盘上的一个 SKILL.md 文件
     */
    @Data
    public static class Skill {
        /** 技能名，frontmatter.name，唯一标识，LLM 通过它激活技能 */
        private String name;
        /** 技能描述，frontmatter.description，供 LLM 做语义匹配，决定"何时该用这个技能" */
        private String description;
        /** Markdown 指令正文，仅在技能被激活时才注入上下文（渐进式披露的关键） */
        private String content;
        /** 技能所在目录，供按需读取 references / scripts / assets 等附属资源 */
        private String baseDir;
    }
}
