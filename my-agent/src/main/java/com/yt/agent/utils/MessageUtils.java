package com.yt.agent.utils;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.stream.Collectors;

public class MessageUtils {

    public static String getMessageContent(Message message) {

        if (message instanceof ToolResponseMessage toolMessage){
            return toolMessage.getResponses().stream().map(response -> "工具 " + response.name() + " 响应结果：" + response.responseData())
                    .collect(Collectors.joining("/n"));
        }
        return message.getText();
    }
}
