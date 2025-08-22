package com.yt.agent.utils;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import java.util.List;

public class TokenCounter {

    // 平均每个汉字约1.5-2个token，每个英文单词约0.75个token
    private static final double CHINESE_CHAR_RATIO = 1.8;
    private static final double ENGLISH_WORD_RATIO = 0.8;

    /**
     * 计算Message列表中所有消息的总token数
     * @param messages 消息列表
     * @return 总token数
     */
    public static int calculateTotalTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int totalTokens = 0;
        for (Message message : messages) {
            totalTokens += calculateMessageTokens(message);
        }

        return totalTokens;
    }

    /**
     * 计算单个Message的token数
     * @param message 消息对象
     * @return token数
     */
    private static int calculateMessageTokens(Message message) {
        String content = MessageUtils.getMessageContent(message);
        if (content == null) {
            return 0;
        }

        // 使用Spring AI的TokenCountUtils计算token数
        int contentTokens = estimateContentTokens(content);

        // 考虑消息类型和角色的额外token开销
        int roleTokens = estimateRoleTokens(message);

        return contentTokens + roleTokens;
    }

    /**
     * 估算消息角色带来的额外token开销
     * @param message 消息对象
     * @return 角色相关的token数
     */
    private static int estimateRoleTokens(Message message) {
        // 根据不同消息类型估算角色标记的token数
        // 这些值可能需要根据实际使用的模型进行调整
        if (message instanceof UserMessage) {
            return 5; // 估算"user:"或类似标记的token数
        } else if (message instanceof AssistantMessage) {
            return 5; // 估算"assistant:"或类似标记的token数
        } else if (message instanceof SystemMessage) {
            return 5; // 估算"system:"或类似标记的token数
        }
        return 3; // 默认值
    }

    private static int estimateContentTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        // 简单估算：中文字符和英文字符分别计算
        int chineseChars = countChineseCharacters(content);
        int englishChars = content.length() - chineseChars;

        // 估算token数
        int estimatedTokens = (int) (chineseChars * CHINESE_CHAR_RATIO +
                englishChars * ENGLISH_WORD_RATIO / 5); // 假设平均单词长度5字符

        return Math.max(1, estimatedTokens);
    }

    private static int countChineseCharacters(String text) {
        if (text == null) {
            return 0;
        }

        int count = 0;
        for (char c : text.toCharArray()) {
            if (isChineseCharacter(c)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isChineseCharacter(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}