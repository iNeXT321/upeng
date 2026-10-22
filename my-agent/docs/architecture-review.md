# AI Agent 平台架构评审与整改报告

> 评审视角：资深 AI Agent 开发领域面试官，对既有架构做「自身不足 + 未实现能力」的完整盘点。
> 结论先行：原架构是一个**能跑 Demo 的原型**，不具备生产资格；本轮已将核心缺口全部补齐，
> 并按「接口先行、实现可替换」的原则为模型供应商与数据库预留了接入位。

## 一、原架构缺陷清单（按严重程度排序）

### P0 —— 会直接导致线上事故

| # | 缺陷 | 后果 | 整改 |
|---|------|------|------|
| 1 | **多轮对话历史从未回放**：Controller 从不注入 `request.messages` | 每次请求都是"失忆"的单轮对话，语义记忆/口径继承形同虚设 | `SessionService` + `AgentRuntime` 统一回放历史，`sessionId` 贯穿全链路 |
| 2 | **SSE 生命周期失控**：异常时不 complete、心跳线程泄漏、客户端断开不感知 | 连接句柄泄漏、Tomcat 线程耗尽 | `AgentController` 重写：心跳用 SSE comment、断连即取消、`finally` 必 complete |
| 3 | **无超时/无取消**：请求一旦进入 Agent 循环就不可控 | 模型 hang 死 = 用户永久转圈 | `CancellationToken` + 墙钟 deadline + `/ai/stop/{id}` 停止接口 |
| 4 | **线程池静默丢任务**：`ThreadUtil` 用无界队列+拒绝即吞 | 高并发下请求无声消失 | 改虚拟线程（`newVirtualThreadPerTaskExecutor`）+ 信号量准入控制 |
| 5 | **MCP 客户端启动强连外部服务**：任一 MCP Server 不可达 = 整个应用起不来 | 一次网络抖动 = 全站宕机 | 默认关闭、本地配置开启（`application.yml` 模板 + `application-local.yml`） |

### P1 —— 功能性错误

| # | 缺陷 | 整改 |
|---|------|------|
| 6 | `PlanningAgent` 计划硬编码 PPT 提示词，与问数/通用场景错配 | 规划提示词可配置，按 agentType 选择 |
| 7 | `ExecutorAgent` 误用 `plannerMaxSteps` 当执行步数上限 | 拆分 `plannerMaxSteps` / `executorMaxSteps` |
| 8 | 规划 think 失败直接 NPE | 异常归一化 + 失败事件 + 优雅降级 |
| 9 | `SummaryAgent` 失败时给用户返回误导性"成功"文案 | 失败回退到最后一轮 assistant 文本，并如实标注 |
| 10 | `substring` 截断 UTF-16 代理对（emoji/生僻字）会截出乱码 | `StringUtil.truncateSafely` 按 code point 截断 |
| 11 | 并行子任务无超时，一个子任务 hang 死拖死整个 Plan-Solve | `CountDownLatch.await(timeout)` + 隔离子上下文 + `finally` 必 countDown |
| 12 | 缺少 Router / 综合 / Workflow 三类 agentType 的实现，接口返回 null | 新增 `RouterHandlerImpl`（规则+LLM 分类）、兼容映射（1→ReAct，2→Plan-Solve） |
| 13 | `/ai/knowledge` 只有 POST 没有 GET；全局异常处理器把 404/405 吞成 500 | 补 GET 端点；异常处理器映射 404/405/400 到正确状态码 |

### P2 —— 工程质量缺口

| # | 缺陷 | 整改 |
|---|------|------|
| 14 | API Key 明文硬编码在 `application.yml` 与 `mcp-servers.json`（含 Windows 路径的跨平台错误） | 密钥外置到 gitignore 的 `application-local.yml` / `mcp-servers.local.json`，仓库内只留 `${ENV_VAR:}` 占位 |
| 15 | MCP 工具直接透传给模型，无白名单/超时/截断/重复调用抑制 | `ToolGovernance` + `GovernedToolCallback` 统一治理，MCP 与本地工具一视同仁 |
| 16 | 无任何输入/输出安全处理（注入、PII、密钥回显） | `GuardChain`：长度→密钥→注入→PII 四级有序链，三态判定（放行/改写/拦截），输出侧兜底打码 |
| 17 | 无会话持久化（重启即失忆） | `SessionStore` 接口 + InMemory / JDBC（H2/PG）双实现，失败自动降级内存 |
| 18 | 无知识库 | `KnowledgeStore` 接口 + BM25 内存实现 + `VectorKnowledgeStore`（Spring AI VectorStore 适配，预留 pgvector/Milvus），`search_knowledge` 工具自动注册 |
| 19 | 无可观测性 | `AgentMetrics`（请求/模型调用/token/工具/护栏/取消/SSE 错误）+ `/ai/stats` 快照 + `/ai/health` 深度健康检查 |
| 20 | 无 NL2SQL 端到端评测 | `Nl2SqlEvalService`：问题→Schema 召回→生成→护栏→执行→比对，`POST /ai/eval/nl2sql` 触发 |

## 二、分层架构（整改后）

```
Controller 层    AgentController(SSE) / PlatformController(运维) / ApiExceptionHandler(统一异常)
   │
Runtime 层       AgentRuntime：准入(信号量) → 输入护栏 → 历史回放 → 执行 → 持久化 → 指标
                 CancellationToken / CancellationRegistry / 墙钟 deadline
   │
Handler 层       Router(分类) → ReAct / Plan-Solve / Workflow 适配
   │
Agent 层         Planner / Executor / Summary（按 ModelRole 各取所需模型）
   │
Platform 层（全部接口先行）
   ├── llm/         LlmGateway：角色路由(ROUTER/PLANNER/EXECUTOR/REACT/SUMMARY/REWRITER)
   │                MeteredChatModel(超时+计量) → ResilientChatModel(多供应商故障转移)
   ├── guard/       GuardChain：Length → Secret → Injection → PII；输出侧 sanitizeOutput
   ├── tool/        ToolGovernance：白名单/超时/截断/重复抑制/审计
   ├── persistence/ SessionStore：InMemory / JDBC(H2·PG，失败降级)
   ├── knowledge/   KnowledgeStore：BM25 内存 / VectorStore 适配(预留 pgvector·Milvus)
   └── metrics/     AgentMetrics(Micrometer) → /ai/stats
```

## 三、留给用户接入的接口位

| 接口位 | 位置 | 接入方式 |
|--------|------|---------|
| 模型供应商 | `LlmGateway` / `application-platform.yml` 的 `agent.llm.routes` | 按 role 配 `provider:model`，多个 fallback 目标逗号分隔；新增供应商只需容器里有对应 `ChatModel` Bean |
| 向量数据库 | `VectorKnowledgeStore`（Spring AI `VectorStore` 适配器） | 引入 pgvector/Milvus 依赖 + 去掉 `DataSourceAutoConfiguration` 排除即可自动启用 |
| 业务数据库 | `nl2sql.datasource.*` | 配只读账号连接串即可；不配则问数自动 dry-run（只生成 SQL 不执行）——已修复 @Bean 返回 null 导致的启动失败 |
| 会话持久化 | `agent.persistence.*` | `jdbc` 模式配 H2（默认）/PostgreSQL 连接串；PostgreSQL 生产推荐 |
| MCP 服务 | `spring.ai.mcp.client.*` | 默认关闭；`application-local.yml` 开启，stdio 配置文件 `mcp-servers.local.json`（gitignore） |
| 智谱 API Key | `application-local.yml` 或环境变量 `ZHIPU_API_KEY` | **当前 local 里的 key 已 401 失效，需更换** |

## 四、验证结果

- **编译**：112 个源文件全量编译通过。
- **启动**：`Started AgentApplication in 1.9s`，MCP 不可达不再阻塞。
- **端点冒烟**：`/ai/health`（模型路由/存储/知识库/护栏全绿）、`/ai/stats`（指标埋点生效）、
  `/ai/tools`（5 个受治理工具）、`/ai/sessions`、`/ai/knowledge` 正常；
  404/405 正确返回 `NOT_FOUND` / `METHOD_NOT_ALLOWED`。
- **端到端冒烟**：SSE 请求 → 运行时 → 模型网关 → 智谱 API 链路全通；401 时重试 2 次、
  错误归一化、会话不崩溃、SSE 正常收尾、指标如实记录（calls=4, errors=4）。
- **单元测试**：23 个用例全部通过
  - `SqlSafetyGuardTest`（10）：语句白名单/关键字/多语句/表授权/系统表/危险函数/注释剥离/CTE/强制 LIMIT；
  - `GuardChainTest`（8）：注入拦截（中英）、密钥拦截、PII 脱敏放行、超长拦截、输出打码、总开关；
  - `SessionPersistenceTest`（5）：消息往返无损、toolCallId 配对、连续 tool 聚合、脏数据容错、存储语义。

## 五、测试驱动出的真实缺陷（本轮修复）

单测不是摆设——首轮运行即暴露 3 个护栏实现缺陷：

1. **`SqlSafetyGuard` 把 CTE 别名当物理表**：`WITH t AS (...) SELECT * FROM t` 中别名 `t`
   被提取为"未授权表"，导致所有合法 CTE 查询被拒。已增加 CTE 别名识别与剔除。
2. **`PromptInjectionGuard` 漏检最常见口语注入**："忽略之前**所有的**指令" 因多一个"的"字绕过正则；
   倒装句式"把系统提示词**输出**给我"未覆盖。已补双修饰语容忍与倒装模式。
3. **异常处理器状态码错误**：404/405 被兜底成 500，误导前端排障。已按语义映射。

## 六、已知遗留（明确不做假完成）

| 项 | 现状 | 建议 |
|----|------|------|
| 鉴权 | 平台管理接口无 Spring Security，可查询内部配置 | 生产部署置于内网/网关鉴权之后；或引入 Security + API Token |
| 分布式 | 会话存储/知识库为单机实现（JDBC 除外），取消注册表为进程内 | 多实例部署时接 Redis/PostgreSQL 与分布式取消 |
| 评测数据集 | `Nl2SqlEvalService` 骨架就绪但用例集待业务沉淀 | 按真实问数case 逐步积累 golden set |
| Workflow agent | 已有类型位与适配入口，编排 DSL 未实现 | 待明确业务编排需求后按 Plan-Solve 模式扩展 |
| Windows 路径 | MCP stdio 配置中的 `D:\mcp\...` 已随密钥外置，本地文件需按本机路径修改 | 按需更新 `mcp-servers.local.json` |
