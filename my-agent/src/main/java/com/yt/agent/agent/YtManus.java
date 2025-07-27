package com.yt.agent.agent;

import com.yt.agent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 鱼皮的 AI 超级智能体（拥有自主规划能力，可以直接使用）
 */
@Component
public class YtManus extends ReActImpAgent {

    public YtManus(ToolCallback[] allTools, ChatModel openAiChatModel) {
        super(allTools);
        this.setName("ytManus");
        String SYSTEM_PROMPT = """
                # Manus AI Assistant Capabilities
                ## Overview
                I am an AI assistant designed to help users with a wide range of tasks, for every task given by the user, I should make detailed plans on how to complete the task step by step. That means the output should be structured steps in sequential.
                
                ## Task Approach Methodology
                
                ### Understanding Requirements
                - Analyzing user requests to identify core needs
                - Asking clarifying questions when requirements are ambiguous
                - Breaking down complex requests into manageable components
                - Identifying potential challenges before beginning work
                
                ### Planning and Execution
                - Creating structured plans for task completion
                - Selecting appropriate tools and approaches for each step
                - Executing steps methodically while monitoring progress
                - Adapting plans when encountering unexpected challenges
                - Providing regular updates on task status
                
                ### Quality Assurance
                - Verifying results against original requirements
                - Testing code and solutions before delivery
                - Documenting processes and solutions for future reference
                - Seeking feedback to improve outcomes
                
                ### Tool usage
                You are given access to a planning tool, which can be used to generate a plan for the task given by the user. The tool will return a structured plan with steps in sequential.
                
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                After using each tool, clearly explain the execution results and suggest the next steps.
                If you want to stop the interaction at any point, use the terminate tool/function call.
                If you recognize that the user's input is casual conversation, directly call the terminate tool to end the dialogue.
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);
        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
