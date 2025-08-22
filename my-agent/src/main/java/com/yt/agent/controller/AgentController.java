package com.yt.agent.controller;


import com.alibaba.fastjson.JSON;
import com.yt.agent.advisor.MyLoggerAdvisor;
import com.yt.agent.agent.AgentContext;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.printer.Printer;
import com.yt.agent.printer.SSEPrinter;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import com.yt.agent.service.impl.AgentHandlerFactory;
import com.yt.agent.tools.ToolRegistration;
import com.yt.agent.utils.ThreadUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AgentController {

//    @Resource
//    private ToolCallback[] allTools;

    @Resource
    private ChatModel zhiPuAiChatModel;

    @Resource
    private AgentHandlerFactory agentHandlerFactory;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private ToolRegistration toolRegistration;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);
    private static final long HEARTBEAT_INTERVAL = 10_000L; // 10秒心跳间隔



    @PostMapping("/autoAgent")
    public SseEmitter autoAgent(@RequestBody AgentRequest request) {
        log.info("{} auto agent request: {}", request.getRequestId(), JSON.toJSONString(request));
//        Long AUTO_AGENT_SSE_TIMEOUT = 60 * 60 * 1000L;
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        // SSE心跳
        ScheduledFuture<?> heartbeatFuture = startHeartbeat(emitter, request.getRequestId());
        // 监听SSE事件
        registerSSEMonitor(emitter, request.getRequestId(), heartbeatFuture);

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
                   // .availableTools(allTools)
                    .agentType(request.getAgentType())
                    .sopPrompt(request.getAgentType() == 3 ? agentConfig.getUnicornSopPrompt(): "")
                    .basePrompt(request.getAgentType() == 5 ? agentConfig.getUnicornBasePrompt() : "")
                    .chatClient(ChatClient.builder(zhiPuAiChatModel)
                            .defaultAdvisors(new MyLoggerAdvisor())

                            .build())
                    .build();

            //构建工具列表
            context.setAvailableTools(toolRegistration.allTools(context));

            // 根据数据类型获取对应的处理器
            AgentHandlerService handler = agentHandlerFactory.getHandler(context, request);
            // 执行处理逻辑
            handler.handle(context, request);
            // 关闭连接
            emitter.complete();
            log.info("{} auto agent request complete", request.getRequestId());
        });
        return emitter ;
    }


    /**
     * 开启SSE心跳
     * @param emitter
     * @param requestId
     * @return
     */
    private ScheduledFuture<?> startHeartbeat(SseEmitter emitter, String requestId) {
        return executor.scheduleAtFixedRate(() -> {
            try {
                // 发送心跳消息
                log.info("{} send heartbeat", requestId);
                emitter.send("heartbeat");
            } catch (Exception e) {
                // 发送心跳失败，关闭连接
                log.error("{} heartbeat failed, closing connection", requestId, e);
                emitter.completeWithError(e);
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * 注册SSE事件
     * @param emitter
     * @param requestId
     * @param heartbeatFuture
     */
    private void registerSSEMonitor(SseEmitter emitter, String requestId, ScheduledFuture<?> heartbeatFuture) {
        // 监听SSE异常事件
        emitter.onCompletion(() -> {
            log.info("{} SSE connection completed normally", requestId);
            heartbeatFuture.cancel(true);
        });

        // 监听连接超时事件
        emitter.onTimeout(() -> {
            log.info("{} SSE connection timed out", requestId);
            heartbeatFuture.cancel(true);
            emitter.complete();
        });

        // 监听连接错误事件
        emitter.onError((ex) -> {
            log.info("{} SSE connection error: ", requestId, ex);
            heartbeatFuture.cancel(true);
            emitter.completeWithError(ex);
        });
    }

}
