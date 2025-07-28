package com.yt.agent.controller;


import com.alibaba.fastjson.JSON;
import com.yt.agent.advisor.MyLoggerAdvisor;
import com.yt.agent.agent.AgentContext;
import com.yt.agent.printer.Printer;
import com.yt.agent.printer.SSEPrinter;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import com.yt.agent.service.impl.AgentHandlerFactory;
import com.yt.agent.utils.ThreadUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AgentController {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel openAiChatModel;

    @Resource
    private AgentHandlerFactory agentHandlerFactory;



    @PostMapping("/autoAgent")
    public SseEmitter autoAgent(@RequestBody AgentRequest request) {
        log.info("{} auto agent request: {}", request.getRequestId(), JSON.toJSONString(request));
        Long AUTO_AGENT_SSE_TIMEOUT = 60 * 60 * 1000L;
        SseEmitter emitter = new SseEmitter(AUTO_AGENT_SSE_TIMEOUT);

        ThreadUtil.execute(() -> {
            Printer printer = new SSEPrinter(emitter, request, request.getAgentType());

            AgentContext context = AgentContext.builder()
                    .requestId(request.getRequestId())
                    // .sessionId(request.getSessionId())
                    .query(request.getQuery())
                    .printer(printer)
                    //.task(request.getTask())
                    //.dateInfo(request.getDateInfo())
                    .isStream(request.getIsStream())
                    // .streamMessageType(request.getStreamMessageType())
                    .availableTools(allTools)
                    .agentType(request.getAgentType())
                    .sopPrompt(request.getSopPrompt())
                    .basePrompt(request.getBasePrompt())
                    .chatClient(ChatClient.builder(openAiChatModel)
                            .defaultAdvisors(new MyLoggerAdvisor())
                            .build())
                    .build();

            // 根据数据类型获取对应的处理器
            AgentHandlerService handler = agentHandlerFactory.getHandler(context, request);
            // 执行处理逻辑
            handler.handle(context, request);
            // 关闭连接
            emitter.complete();
        });
        return emitter ;
    }

}
