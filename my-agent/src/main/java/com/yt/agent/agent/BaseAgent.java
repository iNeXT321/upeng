package com.yt.agent.agent;

import cn.hutool.core.util.StrUtil;
import com.yt.agent.agent.dto.SseResult;
import com.yt.agent.agent.enums.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Data
@Slf4j
public abstract class BaseAgent {

    //核心属性
    private String name;
    private String description;
    private String systemPrompt;
    private String nextStepPrompt;

//    public ToolCollection availableTools = new ToolCollection();
//    private Memory memory = new Memory();
//    protected LLM llm;
    protected AgentContext context;

    // 执行控制
    private AgentState state = AgentState.IDLE;
    private int maxSteps = 10;
    private int currentStep = 0;
    private int duplicateThreshold = 2;


    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();


    public String run(String query){
        setState(AgentState.RUNNING);

        if(!query.isEmpty()){
            // 存储query
        }

        List<String> results = new ArrayList<>();
        try {

            while (currentStep < maxSteps && state != AgentState.FINISHED) {
                currentStep++;
                log.info("{} {} Executing step {}/{}", context.getRequestId(), getName(), currentStep, maxSteps);
                String stepResult = step();
                results.add(stepResult);

            }

            if (currentStep >= maxSteps) {
                currentStep = 0;
                setState(AgentState.FINISHED);
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
        }catch (Exception e){
            setState(AgentState.ERROR);
            throw e;
        }
        return results.isEmpty() ? "No steps executed" : results.get(results.size() - 1);
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(300000L); // 5 分钟超时
        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            // 1、基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
            }
            // 2、执行，更改状态
            this.state = AgentState.RUNNING;
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            try {
                // 执行循环
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    // 单步执行
                    String stepResult = step();
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);
                    // 输出当前每一步的结果到 SSE
                    send(messageList, sseEmitter);
                }
                // 检查是否超出步骤限制
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                // 正常完成
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                // 3、清理资源
                this.cleanup();
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        // 设置完成回调
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        return sseEmitter;
    }


    // 执行一个步骤,由子类实现
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }

    private void send(List<Message> messages, SseEmitter sseEmitter) throws IOException {
        int size = messages.size();
        Message message = messages.get(size - 1);
        if (message instanceof AssistantMessage assistantMessage) {
            SseResult msg = new SseResult(assistantMessage.getMessageType().getValue(), assistantMessage.getText(), assistantMessage.getToolCalls());
            sseEmitter.send(msg);
        } else {
            AssistantMessage assistantMessage = (AssistantMessage) messages.get(size - 2);
            SseResult msg = new SseResult(assistantMessage.getMessageType().getValue(), assistantMessage.getText(), assistantMessage.getToolCalls());
            sseEmitter.send(msg);

            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) messages.get(size - 1);
            String results = toolResponseMessage.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining("\n"));
            SseResult toolMsg = new SseResult(toolResponseMessage.getMessageType().getValue(), results, null);
            sseEmitter.send(toolMsg);
        }
    }
}
