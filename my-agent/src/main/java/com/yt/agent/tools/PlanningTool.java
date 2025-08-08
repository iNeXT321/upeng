package com.yt.agent.tools;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.dto.Plan;
import lombok.Data;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.*;

/**
 * 计划工具类
 */
@Data
public class PlanningTool {

    private AgentContext agentContext;
    private Plan plan;

    @Tool(name = "create_plan",
            description = "这是一个计划工具，可让代理创建和管理用于解决复杂任务的计划。\n该工具提供创建计划、更新计划步骤和跟踪进度的功能。\n使用中文回答")
    public String createPlan(@ToolParam(description = "任务的标题 必填") String title,
                             @ToolParam(description = """
                            任务列表的格式如下：["执行顺序 + 编号、执行任务简称：执行任务的细节描述"]。
                            不同的子任务之间不能重复、也不能交叠，可以收集多个方面的信息，收集信息、查询数据等此类多次工具调用，是可以并行的任务。
                            具体的格式示例如下：
                            - 任务列表示例1: ["执行顺序1. 执行任务简称（不超过6个字）：执行任务的细节描述（不超过50个字）", "执行顺序2. xxx（不超过6个字）：xxx（不超过50个字）", ...]
                            """) List<String> steps) {

        if (title == null || steps == null) {
            throw new IllegalArgumentException("title and steps are required for create command");
        }

        if (plan != null) {
            throw new IllegalStateException("A plan already exists. Delete the current plan first.");
        }

        plan = Plan.create(title, steps);
        return "我已创建plan";
    }

    @Tool(name = "update_plan",
            description = "更新现有计划的标题和步骤")
    public String updatePlan(@ToolParam(description = "任务的标题（可选）") String title,
                             @ToolParam(description = """
                            任务列表的格式如下：["执行顺序 + 编号、执行任务简称：执行任务的细节描述"]。
                            具体的格式示例如下：
                            - 任务列表示例1: ["执行顺序1. 执行任务简称（不超过6个字）：执行任务的细节描述（不超过50个字）", "执行顺序2. xxx（不超过6个字）：xxx（不超过50个字）", ...]
                            """) List<String> steps) {

        if (plan == null) {
            throw new IllegalStateException("No plan exists. Create a plan first.");
        }

        plan.update(title, steps);
        return "我已更新plan";
    }

    @Tool(name = "mark_step",
            description = "标记计划中某个步骤的状态和备注")
    public String markStep(@ToolParam(description = "步骤的索引（从0开始）") Integer stepIndex,
                           @ToolParam(description = "步骤状态：not_started, in_progress, completed, blocked") String stepStatus,
                           @ToolParam(description = "步骤备注（可选）") String stepNotes) {

        if (plan == null) {
            throw new IllegalStateException("No plan exists. Create a plan first.");
        }

        if (stepIndex == null) {
            throw new IllegalArgumentException("step_index is required for mark_step command");
        }

        plan.updateStepStatus(stepIndex, stepStatus, stepNotes);

        return String.format("我已标记plan %d 为 %s", stepIndex, stepStatus);
    }

    @Tool(name = "finish_plan",
            description = "完成当前计划，将所有步骤标记为已完成")
    public String finishPlan() {
        if (Objects.isNull(plan)) {
            plan = new Plan();
        } else {
            for (int stepIndex = 0; stepIndex < plan.getSteps().size(); stepIndex++) {
                plan.updateStepStatus(stepIndex, "completed", "");
            }
        }
        return "我已更新plan为完成状态";
    }

    public void stepPlan() {
        plan.stepPlan();
    }

    public String getFormatPlan() {
        if (plan == null) {
            return "目前还没有Plan";
        }
        return plan.format();
    }
}