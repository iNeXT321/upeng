package com.yt.agent.agent.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class SseResult {

    private String type;

    private Object msg;

    List<AssistantMessage.ToolCall> toolCalls;

    @Override
    public String toString() {
        return "SseResult{" +
                "type='" + type + '\'' +
                ", msg=" + msg +
                ", toolCalls=" + toolCalls +
                '}';
    }
}
