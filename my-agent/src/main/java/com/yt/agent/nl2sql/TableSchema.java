package com.yt.agent.nl2sql;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据集元数据模型（问数的"知识底座"）
 *
 * <p>NL2SQL 准确率的第一决定因素不是模型，而是喂给模型的 Schema 质量。
 * 生产环境里一个库有几千张表，绝不可能全量塞进上下文，必须做 Schema Linking（模式链接）：
 * 根据用户问题召回最相关的少量表与字段。</p>
 *
 * <p>字段说明里的 {@code synonyms} 与 {@code enumValues} 是提升准确率的关键：
 * 用户说"营收"要能对上 revenue 列，用户说"已完成"要能对上 status=2 而不是 '已完成'。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableSchema {

    /** 表名（可含 schema 前缀，如 public.orders） */
    private String tableName;

    /** 表的业务含义描述，供召回时语义匹配 */
    private String tableComment;

    /** 业务域标签，如 交易/用户/营销，用于粗粒度过滤 */
    private String domain;

    /** 字段列表 */
    private List<ColumnInfo> columns;

    /** 主键字段名 */
    private List<String> primaryKeys;

    /** 表的同义词/别名，用户口语常与表名不一致 */
    private List<String> synonyms;

    /** 预估行数，供模型判断是否需要强制分区裁剪 */
    private Long rowCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnInfo {
        /** 字段名 */
        private String columnName;
        /** 数据类型 */
        private String dataType;
        /** 业务含义（中文），这是模型能否写对 SQL 的关键 */
        private String comment;
        /** 同义词：如 revenue -> ["营收","收入","销售额"] */
        private List<String> synonyms;
        /** 枚举取值与含义：如 status -> ["0=待支付","1=已支付","2=已退款"] */
        private List<String> enumValues;
        /** 是否为分区/时间字段（用于强制时间过滤，防全表扫描） */
        private boolean timePartition;
        /** 是否敏感字段（脱敏或禁止查询，如手机号、身份证） */
        private boolean sensitive;
    }

    /**
     * 渲染成注入 Prompt 的紧凑 Schema 文本。
     * 采用 Markdown 表格 + 枚举内联的形式，比 DDL 更省 token 且模型更易读。
     */
    public String toPromptText(boolean includeSensitive) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 表: ").append(tableName);
        if (tableComment != null && !tableComment.isBlank()) {
            sb.append("  —— ").append(tableComment);
        }
        if (rowCount != null && rowCount > 0) {
            sb.append(" (约 ").append(formatRowCount(rowCount)).append(" 行)");
        }
        sb.append("\n");

        sb.append("| 字段 | 类型 | 业务含义 | 取值说明 |\n|---|---|---|---|\n");
        for (ColumnInfo c : columns) {
            if (c.isSensitive() && !includeSensitive) {
                sb.append("| ").append(c.getColumnName()).append(" | ").append(c.getDataType())
                        .append(" | [敏感字段，已屏蔽，禁止查询] | - |\n");
                continue;
            }
            sb.append("| ").append(c.getColumnName())
                    .append(" | ").append(c.getDataType() == null ? "-" : c.getDataType())
                    .append(" | ").append(c.getComment() == null ? "-" : c.getComment());
            if (c.isTimePartition()) sb.append(" [时间分区字段]");
            sb.append(" | ");
            if (c.getEnumValues() != null && !c.getEnumValues().isEmpty()) {
                sb.append(String.join("; ", c.getEnumValues()));
            } else if (c.getSynonyms() != null && !c.getSynonyms().isEmpty()) {
                sb.append("别名: ").append(String.join("/", c.getSynonyms()));
            } else {
                sb.append("-");
            }
            sb.append(" |\n");
        }

        if (primaryKeys != null && !primaryKeys.isEmpty()) {
            sb.append("主键: ").append(String.join(", ", primaryKeys)).append("\n");
        }
        return sb.toString();
    }

    private String formatRowCount(long n) {
        if (n >= 100_000_000) return String.format("%.1f亿", n / 100_000_000.0);
        if (n >= 10_000) return String.format("%.1f万", n / 10_000.0);
        return String.valueOf(n);
    }
}
