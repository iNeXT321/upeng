# Trace 复盘（2026-10-25）

数据源：`logs/agent.log`（145 行，2026-10-24 三次启动 + 一次登录 + 一次失败请求）。

## Trace 1（唯一一条请求级失败）

```
13:27:23.692 WARN [http-nio-8124-exec-2] [3ee38dc1f81441d7][admin]
  c.y.a.c.DataBrowserController - [DataBrowser] MySQL 查询失败: Before start of result set
```

**证据链：**

| 时间 | 事件 |
|---|---|
| 13:21 | `/private/tmp/test-app.jar` 构建（96MB） |
| 13:21:24 | 第一次启动失败：`openAiAudioSpeechModel` 缺 API key（Spring AI OpenAI starter 默认装配了 audio/speech/image 全套模型，项目只用 chat） |
| 13:21:49 | 二次启动成功 |
| 13:27:23 | 登录成功后 0.5s，DataBrowser MySQL 查询抛 `Before start of result set` |
| 13:32:08 | `DataBrowserController.java` 落盘修改（mtime），随后代码里两个 consumer 都已正确调用 `rs.next()` |

**根因判定：** 失败发生时 jar 里的旧版代码在 `rs.next()` 之前读取了 ResultSet（JDBC 游标停在 first row 之前）。磁盘上 13:32 的当前源码已经是修复后的样子（`mysqlTables` / `mysqlRows` 均先 `while (rs.next())` 再取值），且该文件仍是 **untracked**、jar 也未重建。

**关键缺口：修复未验证。** 旧 jar 还在 /tmp，修复代码没重编译、没有回归测试、没有重放。当前状态是「改了但不知道对不对」。

**验证标准（下一步）：**
1. 重新打包 → 重放 `/ai/datasource/mysql/tables` 与 `/mysql/tables/{t}/rows`（含空表 case）；
2. 写一个先红后绿的测试：对空结果集执行取值路径，旧代码红、新代码绿；
3. 把该文件纳入 git。

> **2026-10-25 验证结果：✅ 已完成。** 新增 `DataBrowserControllerTest`（H2 内存库 MySQL 模式，
> 覆盖空表与正常数据两条路径），`DataBrowserController.java` 与测试均已纳入 git，
> 全量测试套件 35/35 通过（BUILD SUCCESS）。遗留：生产 jar 尚未重新打包部署，
> 重放 `/private/tmp/test-app.jar` 所对应服务需在下次部署时完成。

## Trace 2（启动失败，同样有证据）

`openAiAudioSpeechModel` bean 创建失败拖垮整个应用。根因是 Spring AI 的 openai starter 按模型族自动装配，项目未声明 `spring.ai.model.audio=none`（及 image/moderation 等不用项），导致「只想用 chat」却被迫提供全量 API key。属于依赖收窄问题，与业务代码无关。

> **2026-10-25 修复：✅ 已完成。** `application.yml` 新增 `spring.ai.model.audio.speech /
> audio.transcription / image / moderation: none`（开关名逐个从 spring-ai-autoconfigure-model-openai
> 1.0.0 字节码的 @ConditionalOnProperty 确认，缺省即启用）。chat 保留（DeepSeek 走它）；
> embedding 未动（保守起见，pgvector/advisor 相关装配可能引用）。

## 可观测性断点（结构性发现）

- 整个日志 **0 条 `[Trace]`** 记录：`AgentTraceCollector.start/finish` 只在 `BaseAgent` 中埋点，DataBrowser / Auth / ES / Milvus 等平台路径完全没覆盖——本次唯一失败请求恰恰发生在无 trace 的路径上，只剩一条裸 WARN。
- `AgentTraceCollector.step()` 只从 ThreadLocal 取 trace；注释里自述用 CompletableFuture 并行执行，异步线程中 `CURRENT.get()` 为 null 时会**静默降级为 NOOP**，步骤丢失且无告警。双写 Map 的设计意图（跨线程汇总）与实际埋点方式（仅 ThreadLocal）不一致。

## 其他待办线索（按证据强度排序）

1. `JwtService` WARN：未配置 `agent.auth.secret`，重启后所有 token 失效；
2. `KnowledgeService`：docs=0, chunks=0，知识库为空，RAG 链路实际未被验证过；
3. `SchemaLinker` 每次启动加载两次（26.041 / 26.774），疑似重复装配两个实例；
4. 缺 Jakarta Bean Validation provider，`@Valid` 校验实际不生效；
5. `SimpleMeterRegistry`：指标无真实后端。

## 结论

本轮只打一个根因：**确认 DataBrowser 修复的正确性**（重建 + 重放 + 红绿测试 + 入库）。其余线索留作后续轮次的 trace 候选。
