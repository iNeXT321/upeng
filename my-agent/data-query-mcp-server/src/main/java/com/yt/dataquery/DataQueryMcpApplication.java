package com.yt.dataquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 问数 MCP Server 启动类
 *
 * <p>为什么把问数能力做成独立 MCP Server 而不是塞进 Agent 主进程：
 * <ol>
 *   <li><b>安全隔离</b>：数据库只读连接、安全护栏都收敛在独立进程，Agent 主进程不直接持有数据库凭证，
 *       即使 Agent 侧被 Prompt 注入攻破，也拿不到库的写权限（最小权限原则 + 进程级隔离）；</li>
 *   <li><b>协议标准化</b>：MCP 让"问数"成为可被任意 Agent 复用的标准能力——
 *       Claude Desktop、Cursor、其他 Spring AI 应用都能通过 MCP 接入，不绑定本项目；</li>
 *   <li><b>独立伸缩</b>：查询是 IO 密集型，可独立扩容，不影响 Agent 推理进程。</li>
 * </ol>
 * </p>
 *
 * <p>排除 DataSourceAutoConfiguration，数据源由 QueryToolConfig 按 MCP 场景手动装配（可选，缺省 dry-run）。</p>
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class DataQueryMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataQueryMcpApplication.class, args);
    }
}
