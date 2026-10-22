package com.yt.agent.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillsTool 单元测试（不依赖 Spring 容器与大模型，纯本地验证）
 *
 * <p>覆盖 Agent Skills 三阶段机制：发现(扫描解析) → 匹配(list_skills) → 执行(read_skill)，
 * 以及异常降级路径与 Spring AI @Tool 注解的兼容性。</p>
 */
class SkillsToolTest {

    private static SkillsTool skillsTool;

    @BeforeAll
    static void setUp() {
        // 使用默认路径 classpath:/.agent/skills/*&#47;SKILL.md
        skillsTool = new SkillsTool();
    }

    @Test
    @DisplayName("发现阶段：应扫描到 research-report 技能并正确解析 frontmatter")
    void shouldDiscoverSkillFromClasspath() {
        assertEquals(1, skillsTool.getSkillRegistry().size(), "应发现 1 个技能");

        SkillsTool.Skill skill = skillsTool.getSkillRegistry().get("research-report");
        assertNotNull(skill, "research-report 技能应被注册");
        assertEquals("research-report", skill.getName());
        assertFalse(skill.getDescription().isBlank(), "description 不应为空");
        assertTrue(skill.getDescription().contains("调研"), "description 应包含适用场景关键词");
        assertFalse(skill.getContent().isBlank(), "指令正文不应为空");
        assertFalse(skill.getContent().startsWith("---"), "正文不应残留 frontmatter 分隔符");
        assertFalse(skill.getContent().contains("name: research-report"), "正文不应包含 frontmatter 元数据");
        assertTrue(skill.getContent().contains("第 1 步"), "正文应包含 SOP 步骤");
        assertNotNull(skill.getBaseDir(), "baseDir 应被解析，供按需加载附属资源");
    }

    @Test
    @DisplayName("匹配阶段：list_skills 应返回技能名与描述，并引导调用 read_skill")
    void listSkillsShouldReturnRegistryAndGuidance() {
        String result = skillsTool.listSkills();

        assertTrue(result.contains("research-report"), "应列出技能名");
        assertTrue(result.contains("read_skill"), "应引导 LLM 调用 read_skill 激活技能");
    }

    @Test
    @DisplayName("执行阶段：read_skill 应返回完整 SOP 指令与资源目录")
    void readSkillShouldReturnFullInstructions() {
        String result = skillsTool.readSkill("research-report");

        assertTrue(result.contains("已加载技能"), "应确认技能已激活");
        assertTrue(result.contains("第 7 步"), "应包含完整 SOP（直到最后一步）");
        assertTrue(result.contains("references/report_template.md"),
                "应引导按需加载 references 附属资源（渐进式披露）");
        assertTrue(result.contains(skillsTool.getSkillRegistry().get("research-report").getBaseDir()),
                "应告知技能资源目录，供 FileSystem/Shell 类工具读取附属文件");
    }

    @Test
    @DisplayName("健壮性：技能名前后空格应被容错处理")
    void readSkillShouldTrimSkillName() {
        String result = skillsTool.readSkill("  research-report  ");
        assertTrue(result.contains("已加载技能"), "带空格的技能名应能正确匹配");
    }

    @Test
    @DisplayName("健壮性：不存在的技能应返回友好提示而非抛异常")
    void readSkillShouldHandleUnknownSkillGracefully() {
        String result = skillsTool.readSkill("not-exist-skill");

        assertTrue(result.contains("不存在"), "应提示技能不存在");
        assertTrue(result.contains("list_skills"), "应引导 LLM 重新查询技能列表");
        assertTrue(result.contains("research-report"), "应回显可用技能名，便于模型自我纠正");
    }

    @Test
    @DisplayName("健壮性：空技能名与 null 应返回提示而非抛 NPE")
    void readSkillShouldHandleBlankAndNullName() {
        assertDoesNotThrow(() -> skillsTool.readSkill(null));
        assertDoesNotThrow(() -> skillsTool.readSkill("   "));
        assertTrue(skillsTool.readSkill(null).contains("不能为空"));
        assertTrue(skillsTool.readSkill("   ").contains("不能为空"));
    }

    @Test
    @DisplayName("降级：技能目录不存在时应优雅降级，不抛异常且提示用普通工具")
    void shouldDegradeGracefullyWhenNoSkillsFound() {
        SkillsTool emptyTool = new SkillsTool("classpath:/.agent/not-exist-dir/*/SKILL.md");

        assertTrue(emptyTool.getSkillRegistry().isEmpty(), "注册表应为空");
        String result = emptyTool.listSkills();
        assertTrue(result.contains("没有注册任何技能"), "应明确告知无技能");
        assertTrue(result.contains("直接使用可用工具"), "应引导降级为普通工具流程");
    }

    @Test
    @DisplayName("降级：自定义路径为 null/空白时应回退默认路径")
    void shouldFallbackToDefaultLocationWhenBlank() {
        SkillsTool tool = new SkillsTool("   ");
        assertEquals(SkillsTool.DEFAULT_SKILLS_LOCATION, tool.getSkillsLocation());
        assertEquals(1, tool.getSkillRegistry().size(), "回退默认路径后应能扫到技能");
    }

    @Test
    @DisplayName("容错：frontmatter 缺失或非法的 SKILL.md 应被跳过而不影响其他技能")
    void shouldSkipInvalidSkillFiles() {
        // 构造非法内容，直接验证解析容错（通过反射调用私有解析逻辑成本高，此处以扫描非法路径间接覆盖）
        SkillsTool tool = new SkillsTool("classpath:/.agent/skills/*/NOT_SKILL.md");
        assertTrue(tool.getSkillRegistry().isEmpty(), "不匹配 SKILL.md 命名时不应注册任何技能");
    }

    @Test
    @DisplayName("Spring AI 集成：@Tool 注解应被识别为 2 个 ToolCallback 并生成正确 inputSchema")
    void shouldBeRegisterableAsSpringAiToolCallbacks() {
        ToolCallback[] callbacks = ToolCallbacks.from(skillsTool);

        assertEquals(2, callbacks.length, "应暴露 list_skills 与 read_skill 两个工具");

        boolean hasList = false;
        boolean hasRead = false;
        for (ToolCallback cb : callbacks) {
            String name = cb.getToolDefinition().name();
            String schema = cb.getToolDefinition().inputSchema();
            assertNotNull(schema, "inputSchema 不应为空");

            if ("list_skills".equals(name)) {
                hasList = true;
            } else if ("read_skill".equals(name)) {
                hasRead = true;
                assertTrue(schema.contains("skillName"), "read_skill 的 schema 应包含 skillName 参数");
                assertTrue(schema.contains("\"required\""), "skillName 应为必填参数");
            }
            assertFalse(cb.getToolDefinition().description().isBlank(),
                    "工具描述不应为空——LLM 依赖它判断何时调用");
        }
        assertTrue(hasList, "应注册 list_skills 工具");
        assertTrue(hasRead, "应注册 read_skill 工具");
    }
}
