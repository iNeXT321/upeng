package com.yt.agent.agent.dto;


import org.springframework.ai.chat.messages.Message;
import lombok.Data;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 记忆类 - 管理代理的消息历史
 */
@Data
public class Memory {
    private List<Message> messages = new ArrayList<>();

    /**
     * 追加监听器：消息进入 Memory 时同步推送到请求级流水（用于落库）。
     *
     * <p>为什么挂在 Memory 上而不是让各 Agent 手动上报：Agent 内部大量直接调用
     * {@code getMemory().addMessage(...)}（think/act/工具回包），逐个改造既易漏又侵入业务逻辑。
     * 在唯一的写入口做拦截，是保证"不丢消息"的最稳妥做法。</p>
     */
    private transient Consumer<Message> appendListener;

    /**
     * 添加消息
     */
    public void addMessage(Message message) {
        messages.add(message);
        fire(message);
    }

    /**
     * 添加多条消息
     */
    public void addMessages(List<Message> newMessages) {
        messages.addAll(newMessages);
        if (newMessages != null) {
            newMessages.forEach(this::fire);
        }
    }

    private void fire(Message message) {
        Consumer<Message> listener = this.appendListener;
        if (listener != null && message != null) {
            try {
                listener.accept(message);
            } catch (Exception ignored) {
                // 监听器异常绝不能影响推理主链路
            }
        }
    }

    /**
     * 获取最后一条消息
     */
    public Message getLastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /**
     * 清空记忆
     */
    public void clear() {
        messages.clear();
    }

    /**
     * 清空工具执行历史
     */
    public void clearToolContext() {
        Iterator<Message> iterator = messages.iterator();
        while (iterator.hasNext()) {
            Message message = iterator.next();
            if (message.getMessageType() == MessageType.TOOL) {
                iterator.remove();
            }
//            if (message.getMessageType() == MessageType.ASSISTANT && Objects.nonNull(message.getToolCalls()) && !message.getToolCalls().isEmpty()) {
//                iterator.remove();
//            }
//            if (Objects.nonNull(message.getContent()) && message.getContent().startsWith("根据当前状态和可用工具，确定下一步行动")) {
//                iterator.remove();
//            }
        }
    }

    /**
     * 格式化Message
     */
    public String getFormatMessage() {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append(String.format("role:%s content:%s\n", message.getMessageType(), message.getText()));
        }
        return sb.toString();
    }


    /**
     * 获取消息数量
     */
    public int size() {
        return messages.size();
    }

    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }


    public Message get(int index) {
        return messages.get(index);
    }
}