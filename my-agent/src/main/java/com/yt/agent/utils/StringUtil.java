package com.yt.agent.utils;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringUtil {
    private static final String CHAR_LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String NUMBER = "0123456789";
    private static final String DATA_FOR_RANDOM_STRING = CHAR_LOWER + NUMBER;
    private static final SecureRandom random = new SecureRandom();

    public static String generateRandomString(int length) {
        if (length < 1) throw new IllegalArgumentException();

        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            // 生成 0 到 DATA_FOR_RANDOM_STRING 长度之间的随机索引
            int rndCharAt = random.nextInt(DATA_FOR_RANDOM_STRING.length());
            char rndChar = DATA_FOR_RANDOM_STRING.charAt(rndCharAt);
            sb.append(rndChar);
        }
        return sb.toString();
    }

    // 银行卡Luhn校验算法
    private static boolean luhnBankCardVerify(String cardNumber) {
        int sum = 0;
        boolean alternate = false;
        for (int i = cardNumber.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(cardNumber.charAt(i));
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    public static String getUUID() {
        UUID uuid = UUID.randomUUID();
        return uuid.toString();
    }

    /**
     * 安全截断：不会把 UTF-16 代理对（emoji、部分生僻字）从中间劈开。
     *
     * <p>直接用 {@code substring(0, n)} 截断是很常见的写法，但当一个 emoji 正好跨在
     * 边界上时会产生非法字符，下游模型可能报 400 或输出乱码。这里向前回退一位修正。</p>
     */
    public static String truncateSafely(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "\n...[内容过长已截断]";
    }

    public static void main(String[] args) {

        System.out.println(getUUID());

        /*String name = "123 $ 456 %%% ^ ";
        System.out.println(removeSpecialChars(name));

        name = null;
        System.out.println(">>" + removeSpecialChars(name) + "<<");

        Map<String, String> patterns = new HashMap<>();
        patterns.put("(?:[^A-Za-z0-9_-]|^)password[^A-Za-z0-9_-]", "PASSWORD");
        patterns.put("(?:[^A-Za-z0-9_-]|^)asd[^A-Za-z0-9_-]", "ASD");

        String testContent = "asd 我的邮箱是test@example.com，身份证号是510104199001011234，手机号是13800138000，银行卡号是6226327514303272，哈哈password:::admin123 asd";
        String result = textDesensitization(testContent, patterns);
        System.out.println(result);*/
    }
}
