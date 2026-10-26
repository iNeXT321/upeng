package com.yt.agent.controller;

import com.yt.agent.nl2sql.HybridSchemaLinker;
import com.yt.agent.nl2sql.SchemaLinker;
import com.yt.agent.search.SchemaIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Schema 检索索引管理端点（需登录鉴权）
 *
 * <p>场景：MySQL 数仓 DDL / schema-metadata.json 变更后，需要把新元数据
 * 重新灌入 ES（BM25）与 Milvus（向量）。生产上这类动作应走运维脚本/发布流程，
 * 这里提供手动触发入口便于联调与演示。</p>
 */
@Slf4j
@RestController
@RequestMapping("/ai/schema")
public class SchemaAdminController {

    private final SchemaLinker schemaLinker;

    public SchemaAdminController(SchemaLinker schemaLinker) {
        this.schemaLinker = schemaLinker;
    }

    /** 混合检索索引健康状态 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (!(schemaLinker instanceof HybridSchemaLinker hybrid)) {
            return ResponseEntity.ok(Map.of(
                    "hybridEnabled", false,
                    "message", "schema 混合召回未启用（nl2sql.hybrid.enabled=false），当前为纯规则召回"));
        }
        SchemaIndexService svc = hybrid.getIndexService();
        return ResponseEntity.ok(Map.of(
                "hybridEnabled", true,
                "indexed", svc.isIndexed(),
                "detail", svc.status()));
    }

    /** 全量重建 ES + Milvus schema 索引（schema 变更后调用，幂等） */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        if (!(schemaLinker instanceof HybridSchemaLinker hybrid)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "schema 混合召回未启用，无索引可重建"));
        }
        try {
            boolean ok = hybrid.getIndexService().rebuild();
            return ResponseEntity.ok(Map.of("success", ok,
                    "detail", hybrid.getIndexService().status()));
        } catch (Exception e) {
            log.error("[SchemaAdminController] reindex 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", String.valueOf(e.getMessage())));
        }
    }
}
