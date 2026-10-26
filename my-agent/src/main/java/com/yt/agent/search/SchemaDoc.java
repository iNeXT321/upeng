package com.yt.agent.search;

import com.yt.agent.nl2sql.TableSchema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema 检索文档 —— 混合搜索（ES 关键词 + Milvus 向量）的统一最小检索单元
 *
 * <p>为什么不按"整表一个文档"存：表注释只有一句话，包含的语义太少；用户问题
 * "退款率最高的商品" 命中的其实是 refund_reason 字段的语义。所以按
 * 「表级文档 + 字段级文档」两级建模，检索时字段命中可以向上传导到所属表
 * （见 {@link com.yt.agent.nl2sql.HybridSchemaLinker} 的 RRF 融合逻辑）。</p>
 *
 * <p>docId 规则（幂等 upsert 的主键）：
 * <ul>
 *   <li>表级：{@code table::fact_orders}</li>
 *   <li>字段级：{@code col::fact_orders::gmv}</li>
 * </ul></p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaDoc {

    /** 文档唯一ID，两套存储（ES _id / Milvus 主键）保持一致 */
    private String docId;
    /** 文档层级：table / column */
    private String level;
    /** 所属表名 */
    private String tableName;
    /** 字段名（表级文档为 null） */
    private String columnName;
    /** 业务域（交易/售后/用户/商品/营销） */
    private String domain;
    /** 用于 ES 关键词检索的文本（cjk 分词） */
    private String keywordText;
    /** 用于 Embedding 的语义文本（含表名/字段名/注释/同义词，信息密度更高） */
    private String embeddingText;

    /**
     * 从 Schema 元数据构建全量检索文档集（表级 + 字段级）。
     * 这是 schema 混合搜索与 MySQL 数仓保持一致的唯一入口。
     */
    public static List<SchemaDoc> buildDocs(List<TableSchema> schemas) {
        List<SchemaDoc> docs = new ArrayList<>();
        if (schemas == null) {
            return docs;
        }
        for (TableSchema schema : schemas) {
            String tableName = schema.getTableName();
            String tableComment = schema.getTableComment() == null ? "" : schema.getTableComment();
            String domain = schema.getDomain() == null ? "" : schema.getDomain();
            String tableSynonyms = schema.getSynonyms() == null ? "" : String.join(" ", schema.getSynonyms());

            // 表级文档：把字段清单摘要进语义文本，让"问字段也能召回表"
            StringBuilder colSummary = new StringBuilder();
            if (schema.getColumns() != null) {
                for (TableSchema.ColumnInfo col : schema.getColumns()) {
                    colSummary.append(col.getColumnName()).append("（")
                            .append(col.getComment() == null ? "" : col.getComment()).append("）");
                    if (col.getSynonyms() != null && !col.getSynonyms().isEmpty()) {
                        colSummary.append("/别名:").append(String.join(",", col.getSynonyms()));
                    }
                    colSummary.append("；");
                }
            }
            docs.add(SchemaDoc.builder()
                    .docId("table::" + tableName)
                    .level("table")
                    .tableName(tableName)
                    .domain(domain)
                    .keywordText(String.join("\n",
                            tableName, tableComment, domain, tableSynonyms))
                    .embeddingText(String.format(
                            "数据表 %s：%s。业务域：%s。同义词：%s。包含字段：%s",
                            tableName, tableComment, domain, tableSynonyms, colSummary))
                    .build());

            // 字段级文档
            if (schema.getColumns() != null) {
                for (TableSchema.ColumnInfo col : schema.getColumns()) {
                    String colComment = col.getComment() == null ? "" : col.getComment();
                    String colSynonyms = col.getSynonyms() == null ? "" : String.join(" ", col.getSynonyms());
                    String enums = col.getEnumValues() == null ? "" : String.join(" ", col.getEnumValues());
                    docs.add(SchemaDoc.builder()
                            .docId("col::" + tableName + "::" + col.getColumnName())
                            .level("column")
                            .tableName(tableName)
                            .columnName(col.getColumnName())
                            .domain(domain)
                            .keywordText(String.join("\n",
                                    tableName, col.getColumnName(), colComment, colSynonyms, enums))
                            .embeddingText(String.format(
                                    "数据表 %s 的字段 %s（%s）：%s。同义词：%s。取值：%s",
                                    tableName, col.getColumnName(),
                                    col.getDataType() == null ? "" : col.getDataType(),
                                    colComment, colSynonyms, enums))
                            .build());
                }
            }
        }
        return docs;
    }
}
