package com.yt.agent.controller;

import com.yt.agent.agent.YtManus;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


@RestController
@RequestMapping("/ai")
public class AgentController {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel openAiChatModel;

    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        YtManus ytManus = new YtManus(allTools, openAiChatModel);
        return ytManus.runStream(message);
    }

}
