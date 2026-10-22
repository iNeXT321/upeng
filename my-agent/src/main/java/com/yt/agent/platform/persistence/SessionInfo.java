package com.yt.agent.platform.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话摘要（列表页用，不含消息正文）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionInfo {
    private String sessionId;
    private String userId;
    private int turns;
    private Long updatedAt;
}
