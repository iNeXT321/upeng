package com.yt.agent.printer;

import com.alibaba.fastjson.JSON;


import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.response.AgentResponse;
import com.yt.agent.utils.StringUtil;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.yt.agent.platform.stream.SseHub;

@Slf4j
@Setter
public class SSEPrinter implements Printer {
    /** 事件经由 SseHub 广播：支持多连接、seq 序号、断线回放 */
    private SseHub hub;
    private AgentRequest request;
    private Integer agentType;

    public SSEPrinter(SseHub hub, AgentRequest request, Integer agentType) {
        this.hub = hub;
        this.request = request;
        this.agentType = agentType;
    }

    @Override
    public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
        try {
            if (Objects.isNull(messageId)) {
                messageId = StringUtil.getUUID();
            }
            log.info("{} sse send {} {} ", request.getRequestId(), messageType, message);
            boolean finish = "result".equals(messageType);
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("agentType", agentType);
            AgentResponse response = AgentResponse.builder()
                    .requestId(request.getRequestId())
                    .messageId(messageId)
                    .messageType(messageType)
                    .messageTime(String.valueOf(System.currentTimeMillis()))
                    .resultMap(resultMap)
                    .finish(finish)
                    .isFinal(isFinal)
                    .build();
            if (!StringUtils.isEmpty(digitalEmployee)) {
                response.setDigitalEmployee(digitalEmployee);
            }
            switch (messageType) {
                case "tool_thought":
                    response.setToolThought((String) message);
                    break;
                case "task":
                    response.setTask(((String) message).replaceAll("^执行顺序(\\d+)\\.\\s?", ""));
                    break;
                case "task_summary":
                    if (message instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> taskSummary = (Map<String, Object>) message;
                        Object summary = taskSummary.get("taskSummary");
                        response.setResultMap(taskSummary);
                        response.setTaskSummary(summary != null ? summary.toString() : null);
                    } else {
                        log.error("ssePrinter task_summary format is illegal");
                    }
                    break;
                case "plan_thought":
                    response.setPlanThought((String) message);
                    break;
                case "plan":
                    AgentResponse.Plan plan = new AgentResponse.Plan();
                    BeanUtils.copyProperties(message, plan);
                    response.setPlan(AgentResponse.formatSteps(plan));
                    break;
                case "tool_result":
                    response.setToolResult((AgentResponse.ToolResult) message);
                    break;
                case "browser":
                case "code":
                case "html":
                case "markdown":
                case "ppt":
                case "file":
                case "knowledge":
                case "deep_search":
                    response.setResultMap(JSON.parseObject(JSON.toJSONString(message)));
                    response.getResultMap().put("agentType", agentType);
                    break;
                case "agent_stream":
                    response.setResult((String) message);
                    break;
                case "error":
                    // 统一错误事件：前端据此结束 loading 并展示原因，而不是一直等到超时
                    if (message instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> errorMap = (Map<String, Object>) message;
                        response.setResultMap(errorMap);
                        Object msg = errorMap.get("message");
                        response.setResult(msg == null ? "执行失败" : msg.toString());
                    } else {
                        response.setResult(String.valueOf(message));
                    }
                    response.setFinish(true);
                    break;
                case "result":
                    if (message instanceof String) {
                        response.setResult((String) message);
                    } else if (message instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> taskResult = (Map<String, Object>) message;
                        Object summary = taskResult.get("taskSummary");
                        response.setResultMap(taskResult);
                        response.setResult(summary != null ? summary.toString() : null);
                    } else {
                        Map<String, Object> taskResult = JSON.parseObject(JSON.toJSONString(message));
                        response.setResultMap(taskResult);
                        response.setResult(taskResult.get("taskSummary").toString());
                    }
                    response.getResultMap().put("agentType", agentType);
                    break;
                default:
                    break;
            }

            hub.publish(response);

        } catch (Exception e) {
            log.error("sse send error ", e);
        }
    }

    @Override
    public void send(String messageType, Object message, String digitalEmployee) {
        send(null, messageType, message, digitalEmployee, true);
    }

    @Override
    public void send(String messageType, Object message) {
        send(null, messageType, message, null, true);
    }

    @Override
    public void send(String messageId, String messageType, Object message, Boolean isFinal) {
        send(messageId, messageType, message, null, isFinal);
    }

    @Override
    public void close() {
        hub.complete();
    }

    @Override
    public void updateAgentType(AgentType agentType) {
        this.agentType = agentType.getValue();
    }
}