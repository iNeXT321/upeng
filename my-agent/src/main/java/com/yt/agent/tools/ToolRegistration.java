package com.yt.agent.tools;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * 集中的工具注册类
 */
@Slf4j
@Configuration
public class ToolRegistration {

    @Resource
    private ToolCallbackProvider toolCallbackProvider;
    @Bean
    public ToolCallback[] allTools() {

        ToolCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();

        TerminateTool terminateTool = new TerminateTool();
        ToolCallback[] localTools = ToolCallbacks.from(
                terminateTool
        );

        ToolCallback[] array = Stream.concat(
                Arrays.stream(mcpTools),
                Arrays.stream(localTools)
        ).toArray(ToolCallback[]::new);

        log.info("all tools size: {}", array.length);
        return array;
    }
}
