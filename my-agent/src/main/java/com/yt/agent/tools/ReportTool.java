package com.yt.agent.tools;

import com.alibaba.fastjson.JSONObject;
import com.yt.agent.agent.AgentContext;
import com.yt.agent.agent.dto.CodeInterpreterRequest;
import com.yt.agent.agent.dto.CodeInterpreterResponse;
import com.yt.agent.agent.dto.File;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.utils.SpringContextHolder;
import com.yt.agent.utils.StringUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class ReportTool{
    private AgentContext agentContext;

    public ReportTool(AgentContext agentContext) {
        this.agentContext = agentContext;
    }


    @Tool(name = "report_tool",
            description = """
         这是一个专业的Markdown、PPT和HTML的生成工具，可以用于输出 html 格式的网页报告、PPT 或者 markdown 格式的报告，
         生成报告或者需要输出 Markdown 或者 HTML 或者 PPT 格式时，一定使用此工具生成报告。（如果没有明确的格式要求默认生成
         Markdown 格式的），不要重复生成相同、类似的报告。这不是查数工具，严禁用此工具查询数据。
         不同入参之间都应该是跟任务强相关的，同时文件名称、文件描述和任务描述之间都应该是围绕完成任务目标生成的。
        """)
    public String reportTool(
            @ToolParam(description = "生成报告的文件描述，一定是跟用户的任务强相关的")
            String fileDescription,

            @ToolParam(description = "生成的文件名称，文件的前缀中文名称一定是跟用户的任务和文件内容强相关，如果是markdown，则文件名称后缀是 .md，如果是ppt、html文件，则是文件名称后缀是 .html，一定要包含文件后缀。文件名称不能使用特殊符号，不能使用、，？等符号，如果需要，可以使用下划线_。")
            String fileName,

            @ToolParam(description = "生成文件任务的具体要求及详细描述，以及需要在报告中体现的内容，例如，上下文中需要输出的数据细节。")
            String task,

            @ToolParam(description = "仅支持 markdown ppt 和 html 三种类型，如果指定了输出 html 或 网页版 格式，则是html，如果指定了输出 ppt、pptx 则是 ppt，否则使用 markdown 。")
            String fileType) {


        try {
            if (fileName.isEmpty()) {
                String errMessage = "文件名参数为空，无法生成报告。";
                log.error("{} {}", agentContext.getRequestId(), errMessage);
                return null;
            }


           // List<String> fileNames = agentContext.getProductFiles().stream().map(File::getFileName).collect(Collectors.toList());
            List<String> fileNames = new ArrayList<>();
            Map<String, Object> streamMode = new HashMap<>();
            streamMode.put("mode", "token");
            streamMode.put("token", 10);
            CodeInterpreterRequest request = CodeInterpreterRequest.builder()
                    .requestId(agentContext.getRequestId()) // 适配多轮对话
                    .query(agentContext.getQuery())
                    .task(task)
                    .fileNames(fileNames)
                    .fileName(fileName)
                    .fileDescription(fileDescription)
                    .stream(true)
                    .contentStream(agentContext.getIsStream())
                    .streamMode(streamMode)
                    .fileType(fileType)
                    .build();
            // 调用流式 API
            Future<String> future = callCodeAgentStream(request);
            return future.get();

        } catch (Exception e) {
            log.error("{} report_tool error", agentContext.getRequestId(), e);

        }
        return null;
    }

    /**
     * 调用 CodeAgent
     */
    public CompletableFuture<String> callCodeAgentStream(CodeInterpreterRequest codeRequest) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS) // 设置连接超时时间为 1 分钟
                    .readTimeout(600, TimeUnit.SECONDS)    // 设置读取超时时间为 10 分钟
                    .writeTimeout(600, TimeUnit.SECONDS)   // 设置写入超时时间为 10 分钟
                    .callTimeout(600, TimeUnit.SECONDS)    // 设置调用超时时间为 10 分钟
                    .build();

            ApplicationContext applicationContext = SpringContextHolder.getApplicationContext();
            AgentConfig agentConfig = applicationContext.getBean(AgentConfig.class);
            String url = agentConfig.getCodeInterpreterUrl() + "/v1/tool/report";
            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"),
                    JSONObject.toJSONString(codeRequest)
            );

            log.info("{} report_tool request {}", agentContext.getRequestId(), JSONObject.toJSONString(codeRequest));
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(body);
            Request request = requestBuilder.build();

            String[] interval = agentConfig.getMessageInterval().getOrDefault("report", "1,4").split(",");
            int firstInterval = Integer.parseInt(interval[0]);
            int sendInterval = Integer.parseInt(interval[1]);
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.error("{} report_tool on failure", agentContext.getRequestId(), e);
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(Call call, Response response) {

                    log.info("{} report_tool response {} {} {}", agentContext.getRequestId(), response, response.code(), response.body());
                    CodeInterpreterResponse codeResponse = CodeInterpreterResponse.builder()
                            .codeOutput("report_tool 执行失败") // 默认输出
                            .build();
                    try {
                        ResponseBody responseBody = response.body();
                        if (!response.isSuccessful() || responseBody == null) {
                            log.error("{} report_tool request error.", agentContext.getRequestId());
                            future.completeExceptionally(new IOException("Unexpected response code: " + response));
                            return;
                        }

                        int index = 1;
                        StringBuilder stringBuilderIncr = new StringBuilder();
                        String line;
                        String messageId = StringUtil.getUUID();
                        // 获取数字人名称
                     //   String digitalEmployee = agentContext.getToolCollection().getDigitalEmployee(getName());
                        String digitalEmployee = "数字人";
                        BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream()));
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6);
                                if (data.equals("[DONE]")) {
                                    break;
                                }
                                if (index == 1 || index % 100 == 0) {
                                    log.info("{} report_tool recv data: {}", agentContext.getRequestId(), data);
                                }
                                if (data.startsWith("heartbeat")) {
                                    continue;
                                }
                                codeResponse = JSONObject.parseObject(data, CodeInterpreterResponse.class);
                                if (codeResponse.getIsFinal()) {
                                    // report_tool 只会输出一个文件，使用模型输出的文件名和描述
                                    if (Objects.nonNull(codeResponse.getFileInfo())) {
                                        for (CodeInterpreterResponse.FileInfo fileInfo : codeResponse.getFileInfo()) {
                                            File file = File.builder()
                                                    .fileName(codeRequest.getFileName())
                                                    .fileSize(fileInfo.getFileSize())
                                                    .ossUrl(fileInfo.getOssUrl())
                                                    .domainUrl(fileInfo.getDomainUrl())
                                                    .description(codeRequest.getFileDescription())
                                                    .isInternalFile(false)
                                                    .build();
//                                            agentContext.getProductFiles().add(file);
//                                            agentContext.getTaskProductFiles().add(file);
                                        }
                                    }
                                    agentContext.getPrinter().send(messageId, codeRequest.getFileType(), codeResponse, digitalEmployee, true);
                                } else {
                                    stringBuilderIncr.append(codeResponse.getData());
                                    if (index == firstInterval || index % sendInterval == 0) {
                                        codeResponse.setData(stringBuilderIncr.toString());
                                        agentContext.getPrinter().send(messageId, codeRequest.getFileType(), codeResponse, digitalEmployee, false);
                                        stringBuilderIncr.setLength(0);
                                    }
                                }
                                index++;
                            }
                        }
                    } catch (Exception e) {
                        log.error("{} report_tool request error", agentContext.getRequestId(), e);
                        future.completeExceptionally(e);
                        return;
                    }
                    // 统一使用data字段，兼容历史codeOutput逻辑
                    String result = Objects.nonNull(codeResponse.getData()) && !codeResponse.getData().isEmpty() ? codeResponse.getData() : codeResponse.getCodeOutput();
                    future.complete(result);
                }
            });
        } catch (Exception e) {
            log.error("{} report_tool request error", agentContext.getRequestId(), e);
            future.completeExceptionally(e);
        }

        return future;
    }
}