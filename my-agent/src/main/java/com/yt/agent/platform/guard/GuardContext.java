package com.yt.agent.platform.guard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 护栏上下文：让规则可以按用户/租户/场景做差异化策略（例如内部管理员放宽、外部租户收紧）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardContext {

    private String requestId;
    private String userId;
    private Integer agentType;

    public static GuardContext of(String requestId, String userId, Integer agentType) {
        return GuardContext.builder().requestId(requestId).userId(userId).agentType(agentType).build();
    }
}
