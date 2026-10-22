package com.yt.dataquery.config;

import com.yt.dataquery.tools.DataQueryTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 DataQueryTool 中带 @Tool 注解的方法注册为 MCP 工具。
 * Spring AI MCP Server 会自动把这些 ToolCallback 通过 MCP 协议（SSE/stdio）暴露给客户端。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider dataQueryTools(DataQueryTool dataQueryTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(dataQueryTool)
                .build();
    }
}
