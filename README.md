# AI Gateway

基于 **Spring Cloud Gateway Server WebMVC + Java 21 虚拟线程** 构建的 AI 网关，
统一管理大模型 API 的转发、限流、熔断、计量与鉴权。

> 当前进度：**M1 完成 / M2 部分完成 / M3 基本完成（requestId 渲染待修复）**
> 已完成模块均附带**真实压测数据**与**踩坑记录**。

---

## 为什么选 MVC + 虚拟线程，而不是 WebFlux

WebFlux 性能好，但响应式编程是"回调地狱"式的，调试与排查成本高。
Java 21 虚拟线程让**同步阻塞写法也能达到接近响应式的并发能力**——
写起来像普通 Servlet 代码，跑起来能扛高并发。

AI 网关是典型的 **IO 密集型**场景：请求转发给大模型后，线程在**干等几秒**，
CPU 完全空闲。这正是虚拟线程的主战场。

本项目在 **M13** 会做 WebFlux 双版本改造与压测对比，用数据给出最终选型结论。

<!-- TODO: M13 完成后补充双版本对比数据与选型结论 -->

---

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | 21 | 虚拟线程（核心） |
| Spring Boot | 4.1.1 | |
| Spring Cloud | 2025.1.2 | |
| Spring Cloud Gateway | Server WebMVC 5.0.x | **非 WebFlux 版** |
| Redis | 7 (alpine) | 限流 / 配额存储 |
| MySQL | 8.0 | 持久化（M8 启用，当前已停） |
| Docker Compose | v2 | 一条命令拉起依赖 |
| k6 | - | 压测工具 |
| Lombok | - | `@Slf4j` 日志 |

---

## 请求处理架构（M3）

采用**两层过滤器结构**：全局层负责跨路由通用能力，路由层负责与具体路由绑定的业务能力。

**执行流程：**

1. **【全局层】GlobalTraceFilter**（Servlet Filter，`@Order 0`）
    - 所有请求必经（含 404、actuator 等未匹配路由）
    - 生成/透传 `requestId` → 写入 MDC（全链路日志可追踪）
    - 打印「→ 进入」，记录请求开始时间
    - 调用 `chain.doFilter()` 进入路由层

2. **【路由匹配】** 按 `@Order` 顺序匹配 `RouterFunction`（值越小优先级越高）

3. **【路由级 filter 链】按代码添加顺序正序执行（已实测确认）**
    - ① `BeforeFilterFunctions.uri(目标地址)` —— **必须最先**，设置上游 URI
    - ② 业务过滤器（当前：探针 / LoggingFilter；将来 M4 限流、M8 计量）

4. **【转发】** `HandlerFunctions.http()` 转发到上游

5. **【after 语义】** `next.handle()` 之后的代码，按调用栈**逆序回溯**（环绕通知语义）

6. **【全局层】finally**
    - 打印「← 完成」+ 总耗时 + 状态码
    - `MDC.clear()` 清理线程上下文

### 为什么分两层（设计价值）

| 维度 | 全局层 | 路由层 |
|---|---|---|
| 覆盖范围 | 所有请求 | 仅特定路由 |
| 计时区间 | 进网关 → 出网关**总耗时** | 仅**转发上游**耗时 |
| 不可替代性 | 404 请求的唯一可观测手段 | 可省略，但会丢转发数据 |

**两者的耗时差值 = 网关自身开销**（路由匹配 + 过滤器链 + MDC 操作）。
实测：全局 2303ms − 转发 2220ms = **83ms**。
M4 加限流、M8 加计量后过滤器增多，此差值会变大，是 **M14 JVM 调优的重点观测指标**。

**只有一层就拿不到这个数**——这就是两层结构的核心价值。

另外，发一个不存在的路径（如 `/not-exist`）时，**只有全局层会打日志**。
若只有路由级过滤器，404 请求就成了"黑洞"，排查线上问题时致命。

### MDC 与虚拟线程复用风险

MDC（Mapped Diagnostic Context）是 SLF4J 提供的线程级诊断上下文，底层为
`ThreadLocal<Map>`，实现 requestId 全链路串联。

⚠️ **虚拟线程场景下线程会被复用**：若不清理 MDC，请求 B 会打上请求 A 的 requestId（**串号**）。
因此必须在 `finally` 中调用 `MDC.clear()`——异常路径也要保证清理。

---

## 压测数据（M2 验证性压测）

**场景**：本地 mock 服务模拟 AI 响应（固定 **2 秒** 延迟），**并发 300，持续 30 秒**
**工具**：k6 + Node mock 服务（9000 端口）
**变量**：`spring.threads.virtual.enabled` = `true` / `false`

| 指标 | 虚拟线程 ON | 平台线程 OFF | 提升 |
|---|---|---|---|
| **吞吐** | **146.3 req/s** | 100.51 req/s | **+45%** |
| **p95 延迟** | **2190 ms** | 4086 ms | **-46%** |
| avg 延迟 | 2031 ms | 2854 ms | -29% |
| 总请求数 | 4538 | 3253 | +39% |
| 失败数 | 0 | 0 | — |

### 数据解读

**1. OFF 组的 100.51 req/s 不是巧合，是物理天花板**

Tomcat 默认 `maxThreads=200`，每个请求占着线程等 2 秒：

```
200 线程 ÷ 2 秒延迟 = 100 req/s  ← 死锁上限
```

实测 100.51，几乎分毫不差。**再加并发也上不去**，多出的请求只能排队等线程释放。

**2. ON 组 146.3 req/s 逼近"零排队"理论上限**

300 并发 ÷ 2.03 秒平均延迟 = 147.8 的理论上限，实测 146.3。
说明 300 个并发请求**几乎全部被同时招待**，没有因线程不足而干等。

**3. p95 = 4086ms 最能说明问题**

mock 本身固定睡 2000ms：

- ON：**2190ms** → 比 2000 只多 190ms，**几乎没排队**
- OFF：**4086ms** → 比 2000 多出整整 **2086ms ≈ 又一整个 2 秒**

多出的这 2 秒就是**排队等线程**的时间。
银行类比：ON 是 300 人同时拿号办理；OFF 只有 200 个柜台，
剩余 100 人干等第一批办完（2 秒）才轮到 → 2 秒等待 + 2 秒办理 = **4 秒**。

### 验证方式

在网关过滤器中打印 `Thread.currentThread()`：

```
虚拟线程 ON  : VirtualThread[#2298,tomcat-handler-343]/runnable@ForkJoinPool-1-worker-5
虚拟线程 OFF : Thread[#42,http-nio-8080-exec-1,5,main]
```

看到 `VirtualThread` + `tomcat-handler-0` 即证明 Tomcat 请求处理线程已被虚拟线程替换。

> ⚠️ 本组数据为 M2 阶段的**验证性压测**，用于确认虚拟线程生效与量化收益。
> 正式压测与 JVM 调优报告在 **M14** 产出。

---

## 已完成内容

### M1 新技术栈迁移 + 骨架跑通第一条链路 ✅

- [x] 路由转发：本地网关 → DeepSeek API，拿到真实模型响应
- [x] 自定义过滤器打印耗时
- [x] Redis 连通
- [x] Docker Compose 拉起 Redis + MySQL

### M2 虚拟线程深入 + 项目异步化改造（部分完成）

- [x] 虚拟线程生效验证（`Thread.currentThread()` 打印）
- [x] 压测对比：吞吐 +45%，p95 -46%（数据见上）
- [ ] **项目异步化改造** —— 延后至 **M8**
    - 理由：当前网关只有单条转发链路，无多余阻塞点，
      强行改 `CompletableFuture` 不产生实际收益（虚拟线程已承担 IO 等待）；
      待 M8 Token 计量引入"落库不阻塞主链路"的真实阻塞点后再改造，届时附对比数据。

### M3 Gateway 路由与过滤器链设计（基本完成）

- [x] 全局层 `GlobalTraceFilter`（requestId 透传、MDC、总耗时）
- [x] 多路由组织与 `@Order` 优先级
- [x] 过滤器执行顺序**实测确认**：`.filter()` 按添加顺序正序执行
- [x] 全局层 / 路由层职责分离与耗时差值观测
- [ ] **requestId 日志渲染**（当前显示为空 `[]`，定位中）
- [ ] yml 配置化路由 vs Java DSL 的适用场景最终结论

---

## 快速开始

### 1. 拉起依赖（服务器）

Compose 通过 `.env` 引用密码，不硬编码。在服务器 `/data/compose` 目录执行
`docker compose up -d` 即可拉起 Redis 与 MySQL。

### 2. 配置环境变量（本地）

`application.yml` 只保留占位符，不含明文：`${REDIS_HOST}` / `${REDIS_PASSWORD}` 等。
IDEA：**Run → Edit Configurations → Environment variables** 填入真实值。

关键配置：

| 配置项 | 值 | 说明 |
|---|---|---|
| `spring.threads.virtual.enabled` | `true` | 虚拟线程开关 |
| `logging.pattern.console` | 含 `%X{requestId}` | 让日志显示 requestId |

### 3. 启动 & 测试

| 目标 | 请求 | 预期 |
|---|---|---|
| DeepSeek 真实转发 | `POST /v1/chat/completions` | 返回模型响应 |
| mock 压测链路 | `GET /mock/ai` | **2 秒后**返回 JSON |
| 404 可观测性验证 | `GET /not-exist` | 仅全局层打日志 |

请求体需为 **UTF-8 无 BOM**，否则 DeepSeek 会报 `invalid unicode code point`。

---

## 踩坑记录

### 依赖选型

| 坑 | 现象 | 解法 |
|---|---|---|
| 引错 Gateway 版本 | 引了 `...gateway-server-webflux` | 必须是 `spring-cloud-starter-gateway-server-webmvc` |
| `<import>scope</import>` 写反 | 依赖全红拉不下来 | 正确写法是 `<scope>import</scope>` |
| `docker-compose` 命令不存在 | apt 装的是已弃用的 v1.29 | 装官方 v2 二进制 |
| 插件装了仍报 unknown command | 二进制缺 `x` 权限 | `chmod +x`，放到 `docker info` 显示的 cli-plugins 目录 |
| MySQL 改密码不生效 | `MYSQL_ROOT_PASSWORD` 仅在数据目录为空时生效 | 删除数据目录重建，或进容器用 SQL 改 |

### 代码 API

| 坑 | 现象 | 解法 |
|---|---|---|
| `HandlerFunctions.http("url")` | `Expected no arguments but found 1` | 5.0.x 移除带参版本，改用 `http()` + `uri(...)` |
| `.before(new XxxFilter())` 泛型标红 | 手写 `implements HandlerFilterFunction` 签名对不齐 | 改用 lambda 内联，让编译器推断泛型 |
| 过滤器日志不打印 | 请求被 yml 中另一条路由接走 | yml 与 Java DSL 二选一，严禁重复注册同一路径 |
| `@Slf4j` 与 MDC 混淆 | 以为 `@Slf4j` 能替代 MDC 的 import | `@Slf4j` 只生成 `log` 字段；`MDC` 需单独 `import org.slf4j.MDC` |

### 环境 / 工具

| 坑 | 现象 | 解法 |
|---|---|---|
| PowerShell `curl` 报 `无法绑定参数 Headers` | `curl` 是 `Invoke-WebRequest` 别名 | 用 `curl.exe` 或改用 Git Bash |
| 中文请求体 400 | Windows 命令行按 GBK 编码发送 | UTF-8 无 BOM 文件 + `--data-binary @body.json` |
| `docker compose -version` 报 `unknown shorthand flag` | compose 子命令不加短横线 | 正确的是 `docker compose version` |
| k6 报 `no exported functions in script` | 脚本放错目录 / 文件为空 | 放项目根目录，用 `cat >` 写入并确认 |
| k6 读 `p(99)` 报 KeyError | 不同版本分位数键名不同 | 兼容取值，或看 `p(95)` 与 `avg` |
| 压测 `connection refused` | 目标服务未启动 | 确认 mock(9000) 与网关(8080) 同时运行 |
| API Key 泄漏 | 报错信息明文带出 `sk-` 密钥 | 立即去控制台吊销重建 |

---

## 安全说明

- 所有密码走 `${环境变量}` 占位符 + `.env` 文件，`.env` 已加入 `.gitignore`
- 服务器防火墙**仅开放 6379**，3306 不开放公网（本地走 SSH 隧道）
- 代码中不含任何明文 IP、密码、API Key

---

## 开发路线（M1–M17）

| 模块 | 内容 | 工时 | 状态 |
|---|---|---|---|
| **【起步】** | | | |
| M1 | 新技术栈迁移 + 骨架跑通第一条链路 | ~15h | ✅ 完成 |
| **【核心能力：新技术栈】** | | | |
| M2 | 虚拟线程深入 + 项目异步化改造 | ~20h | 🔶 部分完成（异步化延后至 M8） |
| M3 | Gateway 路由与过滤器链设计 | ~15h | 🔶 基本完成 |
| **【核心功能：流量治理】** | | | |
| M4 | 分布式限流（多维度 + 降级） | ~18h | 🔜 下一步 |
| M5 | 熔断降级与多模型 failover | ~15h | ⬜ |
| M6 | 模型适配层与协议标准化 | ~15h | ⬜ |
| **【AI 特色功能】** | | | |
| M7 | SSE 流式处理与流式计量 | ~25h | ⬜ |
| M8 | Token 计量、配额扣减与幂等 | ~15h | ⬜ |
| **【企业级能力】** | | | |
| M9 | 多租户隔离与 OAuth 2.1 鉴权 | ~20h | ⬜ |
| M10 | 审计、护栏与 Prompt 注入检测 | ~15h | ⬜ |
| M11 | 可观测性（含 AI 专属指标） | ~20h | ⬜ |
| M12 | MCP Client 接入 | ~15h | ⬜ |
| **【差异化：双版本对比】** | | | |
| M13 | WebFlux 改造与双版本压测对比 | ~30h | ⬜ |
| **【产出】** | | | |
| M14 | 压测与 JVM 调优实战（出报告） | ~35h | ⬜ |
| M15 | 容器化：Docker Compose → K8s + Helm | ~18h | ⬜ |
| M16 | 交付物料与 GitHub 门面 | ~18h | ⬜ |
| M17 | 简历重构 + 面试准备 | ~25h | ⬜ |

**合计约 334 小时**（按每天 3.5 小时有效投入约 95 天）

### 优先级建议

**必做（撑得住面试追问）**：M1–M6 → M8 → M13 → M14 → M16 → M17

**强烈建议做**：**M7 SSE 流式** —— "流式响应如何转发、如何计量"是面试官分辨
真伪 AI 网关项目的试金石，做过的讲得出细节，没做的一问就露。

**可缓 / 讲思路即可**：M9（OAuth 2.1 偏通用后端）、M10（有概念 + 简单实现）、
M12（新但非必需）、M15（有 Compose 打底，讲清差异即可）。

---

## 一句话简历话术

> 基于 Spring Cloud Gateway MVC + Java 21 虚拟线程构建 AI 网关，通过 k6 压测验证：
> 在模拟 AI 响应（2 秒延迟）场景下，虚拟线程相比平台线程吞吐提升 45%
> （100.5 → 146.3 req/s），p95 延迟降低 46%（4086ms → 2190ms），
> 突破 Tomcat 平台线程 `maxThreads=200` 的并发瓶颈；
> 设计全局层与路由级两层过滤器链，实现 requestId 全链路追踪与网关自身开销可观测。
