# AI Agent 平台 · 面试 Q&A 手册

> 每题给出：**高频指数**、**标准答案要点**、**项目内对应实现**（可直接展示代码的位置）。
> 项目路径约定：后端 `src/main/java/com/yt/agent/`，前端 `agent-frontend/src/`。

---

## 一、整体架构

### Q1. 介绍一下你这个项目的整体架构？（★★★★★）

**答：** 这是一个多智能体（Multi-Agent）平台，整体分五层：

1. **接入层**：Spring Boot 3 + SSE 流式网关（`controller/AgentController`），SseHub 支持断线重连续传；
2. **平台层**（`platform/`）：模型网关（多供应商路由/降级/超时计量）、运行时（并发准入/取消令牌/超时）、安全护栏链、工具治理、会话持久化、知识库、认证（Spring Security + JWT）、限流（Resilience4j）；
3. **Agent 层**：三种执行范式——ReAct（思考→行动→观察循环）、Plan-Solve（任务规划→并行子任务→汇总）、Router（规则+LLM 分类智能路由）；
4. **模型层**：Spring AI 统一接口，当前接 DeepSeek（OpenAI 兼容协议），可零代码切换其他供应商；
5. **前端**：Vue3 + Pinia + Element Plus，SSE 打字机渲染、Markdown 排版、执行过程可视化。

**亮点话术**：所有外部依赖（模型、向量库、业务库、用户存储）都定义了接口并留了适配位，换供应商只改配置不改代码。

### Q2. 为什么选择自研 Agent 框架而不是直接用 LangChain？（★★★★）

**答：** 三个理由：
1. **可控性**：Agent 的 think-act 循环、上下文裁剪、错误恢复策略是核心业务逻辑，框架黑盒会在排查"模型为什么这么走"时卡住；自研后每一步都有 trace（`eval/AgentTraceCollector`）；
2. **深度集成**：工具治理（白名单/超时/截断/重复调用抑制）、护栏链、取消令牌需要织入执行循环的每个环节，用框架的 callback 体系做这些非常别扭；
3. **Java 生态**：Spring AI 提供了模型抽象层（ChatModel/ToolCallback），我们复用它做供应商适配，只在编排层自研——相当于"模型 IO 用 Spring AI，Agent 编排自研"。

---

## 二、Agent 设计

### Q3. 详细讲讲 ReAct 的实现？（★★★★★）

**答：** ReAct = Reasoning + Acting。实现（`agent/ReActImpAgent.java`）是一个受控循环：

```
while (step < maxSteps && !cancelled):
    1. 组装上下文：system prompt + 历史 + 工具列表 + 已有观察
    2. 调 LLM（结构化输出），拿到 思考文本 + 工具调用
    3. 思考 → 推送 tool_thought 事件（前端折叠展示）
    4. 无工具调用 → 认为完成，返回
    5. 有工具调用 → 经工具治理层执行 → 观察结果截断后回填上下文
    6. checkInterrupt()：每步检查取消令牌与墙钟超时
```

关键细节：
- **相同入参重复调用抑制**：治理层记录 (toolName+args) 哈希，重复调用直接返回缓存观察，防止模型死循环烧钱（`platform/tool/GovernedToolCallback`）；
- **观察截断**：单条工具结果超长会挤爆上下文，按 UTF-16 安全方式截断（`StringUtil.truncateSafely`）；
- **每步检查取消/超时**：而不是只在循环外检查，保证"点了停止"最多一个工具调用周期内生效。

### Q4. Plan-Solve 和 ReAct 怎么选？Router 是怎么实现的？（★★★★）

**答：**
- **ReAct** 适合工具密集、路径不确定的任务（边走边看）；**Plan-Solve** 适合目标明确、可拆解的复杂任务（先规划再并行执行子任务，`service/impl/PlanSolveHandlerImpl`）。
- **Router**（`RouterHandlerImpl`，agentType=4）：两级分类——先用规则（关键词/长度/是否含文件引用等）快速分流能确定意图的请求（零模型开销），不确定的再用一个低温度 LLM 分类调用（ModelRole.ROUTER，temperature=0）。规则优先是因为 LLM 分类本身有 300ms+ 延迟和费用，能省则省。

### Q5. 多轮对话的上下文是怎么管理的？（★★★★★）

**答：** 三层机制：
1. **会话持久化**（`platform/persistence/`）：`SessionStore` 接口 + InMemory/JDBC（H2/PostgreSQL）双实现；每轮 User/Assistant/Tool 消息落库，Tool 消息保留 `tool_call_id` 配对关系（否则下次回放模型会报错）；
2. **回放注入**（`BaseAgent`）：请求带 sessionId，运行时查出历史转成 Spring AI Message 列表注入，且**只注入一次**（防重复注入的幂等标志）；
3. **软压缩**：NL2SQL 场景有软 token 阈值（24000），超限后保留最近 K 条无损、更早的做摘要压缩（`nl2sql/语义记忆`）。

**面试追问"为什么 tool_call_id 配对重要"**：OpenAI 协议要求 assistant 的 tool_calls 与后续 tool 角色消息一一对应，回放时顺序错乱/丢失会直接 400。

### Q6. Agent 的幻觉/失控（死循环工具调用）怎么防？（★★★）

**答：** 五道防线：
1. maxSteps 硬上限（每角色独立配置，修复过 executor 误用 planner 配置的 bug）；
2. 相同入参重复调用抑制；
3. 工具结果截断（防上下文爆炸）；
4. 模型调用硬超时 + 多供应商降级；
5. Prompt 层约束（系统提示明确"严禁使用相同入参重复调用"）。

---

## 三、模型网关

### Q7. 多模型接入是怎么设计的？怎么做到换供应商不改代码？（★★★★★）

**答：** 核心是**角色路由 + Bean 发现 + 装饰器链**（`platform/llm/`）：

1. **ModelRole 枚举**：ROUTER/PLANNER/EXECUTOR/REACT/SUMMARY/REWRITER 六个角色，各角色可配不同供应商/模型/温度（`application-platform.yml` 的 `agent.llm.routes`）；
2. **RoutingLlmGateway**：按 `provider: openai` 从 Spring 容器按 Bean 名（`openAiChatModel`）发现 ChatModel——所以接 DeepSeek 只需引入 spring-ai-starter-model-openai + 改配置，代码零改动；
3. **装饰器链**：`MeteredChatModel`（虚拟线程 + Future.get 硬超时 + token 计量 + 异常归一化为 retryable 标记）→ `ResilientChatModel`（多目标故障转移，重写 Prompt 的 withModel 保证路由到的模型真正生效）。

**追问"为什么超时用 Future.get 而不是 WebClient timeout"**：Spring AI 的 ChatModel 是同步阻塞 API，阻塞的是调用线程；虚拟线程便宜，把调用扔进虚拟线程后主线程 Future.get(timeout) 即可拿到硬超时，超时后还能 interrupt。

### Q8. 模型调用失败怎么办？（★★★★）

**答：** 分级处理（`ModelCallException` 带 retryable 标志）：
- **可重试错误**（429/5xx/超时）：指数退避重试 max-attempts 次；
- **不可重试**（401 鉴权失败）：立即失败，不浪费重试；
- **降级**：主模型连续失败 → 按配置的 fallback-providers 顺序切换（如 Ollama 本地兜底）；
- **兜底**：全部失败时 Agent 返回结构化错误事件而非崩溃，SSE 正常收尾，前端展示原因；
- 所有失败被 Micrometer 计量（`platform/llm/AgentMetrics`），`/ai/stats` 可看到 calls/errors 比率。

---

## 四、流式与网络（本项目最大亮点）

### Q9. 为什么用 SSE 而不是 WebSocket？（★★★★★）

**答：** 通信是**单向**的（服务端→客户端推流），SSE 更合适：
1. 基于 HTTP，天然过网关/代理/CDN，不需要协议升级；WebSocket 需要基础设施显式支持；
2. 自带重连语义（Last-Event-ID），本项目还利用它做了事件续传；
3. 客户端→服务端的控制指令（停止生成）用普通 POST 即可（`/ai/stop/{requestId}`），没必要为一条指令维持双工长连接的复杂度；
4. 技术实现上用 SseEmitter（Spring MVC），未用 WebFlux——用虚拟线程扛阻塞 IO。

### Q10. SSE 断线重连是怎么实现的？（★★★★★ 自研亮点）

**答：** 完整的**事件序号 + 回放缓存 + 宽限取消**方案（`platform/stream/SseHub`）：

1. **每条事件分配递增 seq**，同时写入 SSE 的 `id:` 字段（EventSource 协议原生支持）和 JSON payload；
2. **服务端事件日志**：每个 requestId 的 hub 保留最近 1 万条事件（内存环）；
3. **重连**：客户端带 `Last-Event-ID` 头重新 POST 同一 requestId → SseHubRegistry 幂等找到已有 hub → 只回放 seq > lastEventId 的事件 → **Agent 不重跑、不重复计费**；
4. **宽限取消**：连接全断后不立即取消 Agent（网络抖动 ≠ 用户放弃），等 30s（可配 `disconnect-grace-ms`），期内重连撤销取消，超时才取消——平衡体验与模型费用。

**追问"如果服务重启事件日志丢了"**：当前是单机内存实现，分布式部署时把事件日志放 Redis（已有 SessionStore 的抽象先例），hub 变为无状态。

### Q11. SSE 的连接生命周期管理，你踩过什么坑？（★★★★）

**答：** 四个真实踩过的坑（`controller/AgentController`）：
1. **异常时未 complete** → 连接悬挂直到网关超时（现在 finally 保证 hub.complete()）；
2. **心跳任务未取消** → 每个请求泄漏一个调度任务（心跳随 hub 生命周期管理）；
3. **心跳发业务消息** → 前端可能误渲染，改用 SSE comment（`: keep-alive`，EventSource 不交给 onmessage）；
4. **nginx 层缓冲** → 流式输出被攒成一坨，必须 `proxy_buffering off`（`agent-frontend/nginx.conf`）。

### Q12. 前端怎么消费 SSE？为什么不用 EventSource？（★★★★）

**答：** `agent-frontend/src/api/sse.js` 用 **fetch + ReadableStream** 手动解析：
1. EventSource 只支持 GET 且不能带 Authorization 头（JWT 鉴权需要 POST + header）；
2. axios 基于 XHR 拿不到增量流；
3. 实现细节：TextDecoder stream 模式解码 → 按行切分 → 最后一行可能不完整留到下一轮 → 忽略注释行/空行 → 解析 `data:` JSON；
4. 中断用 AbortController，同时调用后端 `/ai/stop` 双保险。

---

## 五、安全

### Q13. 认证方案是怎么设计的？（★★★★★）

**答：** Spring Security 6 + JWT 无状态认证（`platform/auth/`）：
1. **登录**：`/auth/login` 校验 BCrypt 哈希 → 签发 HS256 JWT（subject+roles+exp，`JwtService`）；
2. **鉴权**：`JwtAuthFilter`（OncePerRequestFilter）解析 Bearer token → 写 SecurityContext，同时把用户名写入 MDC（日志审计）；
3. **无状态**：STATELESS 会话策略，服务端不存 session，水平扩容只共享密钥；
4. **最小放行**：只放行登录/注册/健康检查/OpenAPI 文档，其余一律 authenticated，401/403 统一 JSON 格式；
5. **安全细节**：登录失败文案统一（防账号枚举）、密钥环境变量注入（仓库无密钥）、CSRF 关闭的合理性论证（无 cookie 会话）、token 放 localStorage 的 XSS 风险用"前端渲染层禁 HTML 透传"对冲。

**追问"JWT 注销怎么做"**：纯 JWT 无法服务端作废，方案是短有效期+刷新令牌，或 Redis 黑名单（当前实现 12h 有效期，黑名单留了 CancellationRegistry 类似的扩展位）。

### Q14. Prompt 注入攻击怎么防？（★★★★★ Agent 特色题）

**答：** 输入输出双侧护栏链（`platform/guard/`，责任链模式）：
- **输入侧**：长度护栏 → 密钥泄漏检测（sk-/AKIA 等模式）→ Prompt 注入检测（正则规则库：指令覆盖/系统提示词窃取/角色扮演越狱，中英文双语）→ PII 打码（手机号/身份证/邮箱）；
- **三态决策**（`GuardDecision`）：PASS / MODIFIED（打码后放行）/ BLOCK（拦截并告知用户）；
- **输出侧**：`sanitizeOutput` 对输出中的密钥整体打码；
- **测试驱动加固**：单测发现"忽略之前**所有的**指令"（多个"的"字）和倒装句"把系统提示词**输出给我**"能绕过 → 补了双修饰语与倒装模式（`GuardChainTest`）。
- 另外 NL2SQL 有独立的 **SQL 护栏**（`nl2sql/SqlSafetyGuard`）：只读校验、危险关键词、强制 LIMIT、表白名单、CTE 别名识别（修过把 WITH 别名当物理表误杀的缺陷）。

### Q15. 密钥管理怎么做的？（★★★）

**答：** 四条纪律：① 仓库内只留 `${ENV_VAR:}` 占位；② 本地开发密钥放 `application-local.yml` + `mcp-servers.local.json`（均 gitignore，推送前做过全量扫描）；③ JWT 密钥不配置时启动生成临时随机密钥（重启全员掉线也比固定弱密钥安全）；④ BCrypt 存密码哈希，任何路径不允许明文落库。

---

## 六、稳定性与治理

### Q16. 限流怎么做的？用什么算法？（★★★★）

**答：** Resilience4j 令牌桶（`RateLimiterRegistry`，程序化准入）：
- 配置 30 次/10s、**timeout=0 不排队立即拒绝**——LLM 网关排队没有意义，只会让所有请求一起变慢，快速失败 + 429 + Retry-After 是更好的降级；
- 选**令牌桶**而非漏桶：允许突发（模型 API 本身有 RPM 配额特性）、平滑补充；
- 入口限流放在参数校验之后（空请求不消耗配额）、Agent 执行之前；
- 与并发准入（信号量 64 并发上限）是两层：限流管速率，信号量管同时在跑的任务数。

### Q17. 请求取消是怎么实现的？（★★★★）

**答：** 协作式取消（`platform/runtime/CancellationToken + CancellationRegistry`）：
1. 每个请求注册 token（requestId → token）；
2. 用户停止：POST `/ai/stop/{id}` → token.cancel()；前端同时 abort 断流（双保险）；
3. Agent 每步、每个工具调用前 `checkInterrupt()`：取消或墙钟超时 → 抛出专用异常 → 正常走收尾（落库已完成部分）；
4. 协作式而非 Thread.stop()（已废弃且不安全），取消是"优雅停机"：已完成内容保留、SSE 正常关闭、指标记录 cancelled 次数。

### Q18. 工具调用治理做了什么？（★★★★）

**答：** `platform/tool/GovernedToolCallback` 装饰器，五个职责：白名单校验、单次调用超时、结果截断（防上下文爆炸）、相同入参抑制（防死循环）、审计日志。设计上是**装饰器模式**包住原始 ToolCallback，对 Agent 层透明——治理策略变了不动业务代码。

### Q19. 虚拟线程用在哪？为什么？（★★★★）

**答：** JDK21 虚拟线程（`Executors.newVirtualThreadPerTaskExecutor`）用于两类阻塞场景：
1. **Agent 工作线程**：Agent 执行 90% 时间阻塞在模型 HTTP 调用上，虚拟线程让阻塞近乎零成本，平台线程不再被 IO 等待占用；
2. **模型调用超时包装**：MeteredChatModel 里调用扔虚拟线程 + 主线程 Future.get 超时。
关键认知：**虚拟线程不提升 CPU 并行度，提升的是阻塞等待的承载量**——线程数不再受限，但下游（模型 API 配额）才是真瓶颈，所以要配合信号量准入。

---

## 七、数据与知识

### Q20. 知识库是怎么实现的？（★★★★）

**答：** 双后端（`platform/knowledge/`）：
1. **BM25 内存实现**（默认）：段落分块 + BM25 打分，零外部依赖；
2. **向量检索**（`VectorKnowledgeStore`）：适配 Spring AI VectorStore 接口，容器里有 Bean（pgvector/Milvus）时自动切换，没有时优雅降级回 BM25；
3. 入口 `KnowledgeService` 做后端选择 + 文档自动索引（classpath/file 两类位置）；
4. 通过 `search_knowledge` 工具暴露给 Agent，模型可以自主决定何时检索（RAG 按需触发而非每轮强制）。

**追问"BM25 和向量检索的区别"**：BM25 词频统计、精确关键词强、可解释、零成本；向量语义泛化好（同义改写）、需要 embedding 模型+向量库。生产常用混合检索 + RRF 融合，本项目已留好组合的口子。

### Q21. NL2SQL 这条链路怎么保证正确性和安全？（★★★★★）

**答：** 流水线（`nl2sql/`）：
1. **SchemaLinker**：把用户问题与表/字段做关联召回（只给相关 schema，不是全库）；
2. **LLM 生成 SQL**（Rewriter 角色，低温度）；
3. **SqlSafetyGuard**：只读校验（非 SELECT 拒绝）、危险关键词黑名单、无 LIMIT 强制追加、CTE 别名正确识别（有单测）；
4. **SqlExecutor**：只读账号连接 + 查询超时 + max-rows 上限；未配置数据源时 dry-run（只校验不执行）；
5. **SqlSelfCorrector**：执行报错把错误信息回喂模型修正重试（有限次）；
6. **评测**：`eval/Nl2SqlEvalService` 端到端跑样例集对比期望结果。

---

## 八、可观测性

### Q22. 排障时怎么把一次请求的日志串起来？（★★★★）

**答：** MDC traceId 方案（`platform/observability/TraceIdFilter`）：
1. 过滤器（最高优先级）生成/继承 `X-Trace-Id` 写 MDC，响应头回写（前端报障可直接提供）；
2. logback 模式含 `[%X{traceId}][%X{uid}]`——一次请求的所有日志行带同一个 traceId 和操作人；
3. **跨线程传递**：Agent 在虚拟线程池执行，MDC 是 ThreadLocal 不自动继承 → Controller 捕获 `getCopyOfContextMap()` 快照，worker 线程 setContextMap 恢复，finally clear（踩过 clear 顺序的坑）；
4. 另有 `/ai/trace/{requestId}` 输出 Agent 执行链路（每步思考/工具/观察），和 Micrometer 指标 `/ai/stats`。

---

## 九、前端

### Q23. Vue3 相比 Vue2 的核心变化，结合项目说？（★★★★）

**答：** ① **Proxy 响应式**：聊天气泡内容频繁变更，Proxy 能侦测新增属性（打字机缓冲字段 `pending` 动态工作）且性能更好；② **Composition API**：`ChatView` 的逻辑（滚动、文件读取、发送）按关注点组织而非选项分散；③ **`<script setup>`** 更少的样板代码；④ **Pinia 替代 Vuex**：TS 友好、无 mutation。

### Q24. 打字机效果是怎么实现的？（★★★）

**答：** SSE 到达速度 ≠ 展示速度，直接拼接会"啪"地跳出一大段。方案（`stores/chat.js`）：
- 事件文本先进 `pending` 缓冲区；
- 定时器每 24ms 搬运 `ceil(pending.length/25)` 个字到 `content`——**自适应**：积压越大敲得越快，保证不落后于模型；
- 流结束≠显示结束：缓冲清空后才真正收尾（恢复输入框）；
- 停止时缓冲一次性落出（防丢字）；正文尾部加 CSS 闪烁光标。

### Q25. 前端状态管理怎么划分的？（★★★）

**答：** 三个 Pinia store 按领域划分：`chat`（对话状态机：多轮/流式/打字机/重试/历史回放）、`auth`（token/用户）、`platform`（健康指标轮询）。原则：**跨组件共享 + 异步副作用**进 store，纯 UI 状态留组件内（如输入框值）。SSR 无需求，token 放 localStorage 并在渲染层防 XSS（markdown-it 关闭 html）。

### Q26. 前端路由守卫和 401 处理？（★★★）

**答：** 双层：`router.beforeEach` 检查 token 决定重定向（`router/index.js`）；axios 响应拦截器统一处理 401（清 storage → 跳 `/login?expired=1`，SSE 的 fetch 客户端单独同步处理）。登录后 redirect 回原目标页。

---

## 十、工程化

### Q27. 怎么部署的？容器化做了什么优化？（★★★）

**答：** 多阶段构建（`Dockerfile`）：依赖层与源码层分离（改源码不重拉依赖）、运行时用精简 JRE + 非 root 用户 + ZGC + MaxRAMPercentage；前端 node 构建 → nginx 托管，nginx 层做了 SSE 关键配置（proxy_buffering off）、gzip、SPA 回退、静态资源 immutable 缓存；`docker-compose.yml` 编排前后端 + 可选 PostgreSQL profile，健康检查用无鉴权的 `/ai/health`。

### Q28. 测试策略？（★★★）

**答：** 分层：纯逻辑组件（SQL 护栏/护栏链/会话持久化）单测 23 个（JUnit5），其中护栏测试真实驱动出 3 个缺陷并修复；评测层 `Nl2SqlEvalService` 做端到端样例回归；外部依赖（模型/工具服务）通过接口抽象可 mock。面试话术：**LLM 应用测试的难点是非确定性输出，所以测试重心放在确定性组件（护栏/路由/解析）+ 规则化评测（SQL 执行结果比对）**。

---

## 附：一句话亮点清单（自我介绍用）

- 自研多智能体编排（ReAct/Plan-Solve/Router），模型网关实现**角色路由+故障转移+硬超时**，换供应商零代码改动
- SSE 流式网关达到生产级：**事件序号 + Last-Event-ID 断线续传 + 断线宽限取消**，Agent 不重跑不重复计费
- 完整安全体系：**JWT 无状态认证 + Prompt 注入护栏链（三态决策）+ SQL 护栏 + 工具治理四件套 + 密钥零落仓**
- 稳定性：**令牌桶限流（快速拒绝）+ 信号量并发准入 + 协作式取消 + 虚拟线程承载阻塞 IO**
- 可观测：**MDC traceId 跨线程贯穿（含异步恢复）+ Agent 执行链路 trace + Micrometer 指标**
- 前端：Vue3 + Pinia 对话状态机，**自适应打字机、断线指数退避重连（seq 去重续传）、执行过程可视化**
- 测试驱动出的真实缺陷修复 3+ 处（CTE 误杀/注入漏检/异常映射），23 个单测全绿
