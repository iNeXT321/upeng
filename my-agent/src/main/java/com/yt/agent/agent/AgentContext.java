package com.yt.agent.agent;

//import com.jd.genie.agent.dto.File;
//import com.jd.genie.agent.printer.Printer;
//import com.jd.genie.agent.tool.ToolCollection;
//import lombok.AllArgsConstructor;
import com.yt.agent.printer.Printer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

@Data
@Builder
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {
    String requestId;
    String sessionId;
    String query;
    String task;
    Printer printer;
//    ToolCollection toolCollection;
    String dateInfo;
 //   List<File> productFiles;
    Boolean isStream;
    String streamMessageType;
    String sopPrompt;
    String basePrompt;
    Integer agentType;
//    List<File> taskProductFiles;

    ToolCallback[] availableTools;
    ChatClient chatClient;
}