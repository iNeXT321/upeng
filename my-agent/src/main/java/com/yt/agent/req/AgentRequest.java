package com.yt.agent.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Assistant请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {
    private String requestId;
    private String erp;
    /**
     * 会话 ID：多轮对话的唯一依据。为空表示单轮（无历史回放）。
     * 前端应在同一会话内保持不变，这样服务端才能回放历史、复用口径。
     */
    private String sessionId;
    private String query;
    private Integer agentType;
    private String basePrompt;
    private String sopPrompt;
    private Boolean isStream;
    private List<Message> messages;
    private String outputStyle; // 交付物产出格式：html(网页模式）， docs(文档模式）， table(表格模式）

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
        private String commandCode;
//        private List<FileInformation> uploadFile;
//        private List<FileInformation> files;

    }
}
