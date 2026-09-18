# AI Gateway

基于 **Spring Cloud Gateway Server WebMVC + Java 21 虚拟线程** 构建的 AI 网关，
统一管理大模型 API 的转发、限流、熔断、计量与鉴权。

> 当前进度：**M1 完成 / M2 部分完成 / M3 完成 / M4 完成**
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
| Java | 21 | 虚拟线程（核心）、`record` |
| Spring Boot | 4.1.1 | |
| Spring Cloud | 2025.1.2 | |
| Spring Cloud Gateway | Server WebMVC 5.0.x | **非 WebFlux 版** |
| Redis | 7 (alpine) | 限流 / 配额存储 |
| MySQL | 8.0 | 持久化（M8 启用，当前已停） |
| Docker Compose | v2 | 一条命令拉起依赖 |
| k6 | - | 压测工具 |
| Lombok | - | `@Slf4j` 日志 |

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

> ⚠️ 本组数据为 M2 阶段的**验证性压测**，用于确认虚拟线程生效与量化收益。
> 正式压测与 JVM 调优报告在 **M14** 产出。

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
    - ② 业务过滤器（M4 限流 → 后续 M8 计量）

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

### 过滤器执行顺序（实测结论）

在 `mock-ai-route` 上依次注册探针 A、探针 B，实测输出顺序为：

```
[顺序] 探针 A 执行
[顺序] 探针 B 执行
├ 转发开始 GET http://localhost:8080/mock/ai
└ 转发完成 | 耗时 2220ms | 状态 200 OK
```

**结论：`.filter()` 注册链按代码添加顺序正序执行**，
`next.handle()` 之后的代码按调用栈逆序回溯（环绕通知语义）。

### MDC 与虚拟线程复用风险

MDC（Mapped Diagnostic Context）是 SLF4J 提供的线程级诊断上下文，底层为
`ThreadLocal<Map>`，实现 requestId 全链路串联。

⚠️ **虚拟线程场景下线程会被复用**：若不清理 MDC，请求 B 会打上请求 A 的 requestId（**串号**）。
因此必须在 `finally` 中调用 `MDC.clear()`——异常路径也要保证清理。

> 注意：`@Slf4j` 只生成 `log` 字段，`MDC` 需单独引入，两者是 SLF4J 的不同 API。

---

## 分布式限流（M4 完成）

### 算法选型：令牌桶（而非漏桶）

| 算法 | 特性 | 适用性 |
|---|---|---|
| **令牌桶**（选用） | 允许桶容量内的突发，长期速率恒定 | AI 场景常有批量请求突发，更友好 |
| 漏桶 | 强制匀速输出，突发被强制排队 | 对突发不友好 |

### 为什么必须用 Redis + Lua

令牌的「读-算-写」必须在 Redis 内**一次性完成**。若拆成三步从 Java 调用，
并发下多个请求会读到同一个旧值，导致**超发**（限流形同虚设）。

Lua 脚本在 Redis 中单线程原子执行，中间不会被插队。脚本设计要点：

| 设计点 | 说明 |
|---|---|
| 时间戳由应用传入 | 不依赖 Redis 服务器时钟，避免多实例时钟不一致算错令牌 |
| `math.max(0, now - ts)` | 防时钟回拨把令牌扣成负数 |
| Hash 存 `tokens` + `ts` | 两字段保存桶状态 |
| `PEXPIRE` 自动过期 | 避免冷 key 永久占用内存 |

### 降级策略：fail-open

Redis 故障时**放行而非拒绝**——限流是保护手段，不应成为单点故障。
生产环境可按场景切换 fail-close（安全优先），但需明确取舍。

### 多维度限流设计

一个请求可同时受多个规则约束，**取最严者**（木桶短板）：

| 维度 | Redis key 格式 | 解决的问题 |
|---|---|---|
| IP | `ratelimit:ip:{ip}` | 防单 IP 刷量 |
| API Key | `ratelimit:key:{keyId}` | 按客户配额隔离 |
| 模型 | `ratelimit:model:{model}` | 保护昂贵模型额度 |
| 全局 | `ratelimit:global` | 保护上游总额度 |

**关键：一次 Lua 判多桶，而非循环调用多次单桶脚本。**

| 做法 | 并发安全 | 问题 |
|---|---|---|
| **一次 Lua 判多桶**（选用） | ✅ | 全部通过才扣减，无令牌浪费 |
| Java 循环调 N 次单桶脚本 | ❌ | N 次调用间有网络往返间隙，会被插队 |

多桶脚本采用**先算后扣**策略：

1. **阶段一**：计算所有维度的当前令牌数，**不写回**
2. **阶段二**：全部维度都满足才继续，任一不足则**直接返回拒绝**
3. **阶段三**：统一扣减并写回

**这样避免了"IP 维度扣了、Key 维度被拒"造成的令牌浪费**——被拒时一个令牌都不扣。

### 配置化：yml + record 绑定

限流参数从 yml 读取，不同维度可配不同阈值，新增维度无需改 Java 代码：

| Java 类型 | yml 路径 |
|---|---|
| `Boolean enabled` | `gateway.ratelimit.enabled` |
| `Map<String, Dimension> dimensions` | `gateway.ratelimit.dimensions.{ip\|key\|model}` |
| `Dimension(Double rate, Integer capacity)` | `.rate` / `.capacity` |

用 `Map` 承接维度的好处：**加一个维度只改 yml，Java 一行不动**。

> ⚠️ **record 上不能加 `@Component`**——record 走构造器绑定，与 `@Component` 的
> setter 绑定路径互斥，会报 `@ConstructorBinding but defined as Spring component`。
> 正确做法：启动类加 `@ConfigurationPropertiesScan`。

### 实测数据

| 配置 | 场景 | 结果 |
|---|---|---|
| RATE=5/s, CAPACITY=10 | 串行请求（间隔 2 秒） | **全部 200**，限流未触发 |
| RATE=10/分钟, CAPACITY=3 | 串行请求 ×10 | `200 200 200 200 429 429 429 429 429 429` |

**第一组全部 200 的原因（重要踩坑）**：mock 延迟 2 秒，而每秒补 5 个令牌 →
每个请求间隔内补充 10 个 = 桶容量，桶每次都是满的，**限流永远触发不了**。

**结论：AI 网关的限流速率必须按分钟/小时粒度设计**，
因为单次 AI 请求耗时本身就是 2-10 秒级。按"每秒"配速率会完全失效。

### 令牌桶状态验证（Redis 实测证据）

执行 `HGETALL ratelimit:ip:127.0.0.1`，得到：

```
tokens = 0.7175000000000002
ts     = 1789636126229
```

**令牌数是小数**，证明补充逻辑是**连续时间计算**（`elapsed × rate`），
而非固定窗口计数器的整秒跳变。这是令牌桶相比固定窗口的核心优势：
不会出现"窗口切换瞬间放行双倍流量"的临界突刺问题。

### 踩坑：限流 key 的 TTL 过短导致限流失效

Lua 中 TTL 设为「桶填满时间 × 2」，当 rate 很低（0.167/秒）时仅 **36 秒**。

**后果**：用户每分钟请求一次，但 key 在 36 秒时已过期被删除，
下次请求时桶被重置为满 → **限流形同虚设**。

该问题由 `TTL` 返回 `-2`（key 不存在）暴露。

**修复**：TTL 加保底下限

```
ttl = max(桶填满时间 × 2, 3600000)   -- 至少 1 小时
```

> 一个 Hash 仅几十字节，百万 key 约几十 MB，用可控内存换取限流正确性。

### 429 快速失败（限流的真正价值）

被限流拒绝的请求**在转发前就被拦截**，不消耗上游资源：

- 放行请求：需等待 mock 2 秒
- 429 请求：响应耗时接近 0（未转发）

这带来一个**反直觉现象**：429 返回太快，导致后续请求密集打来，
令牌来不及补充，于是**连续被拒**——这正是第二组数据后 6 个全是 429 的原因。

**限流不只是"拦住超额请求"，更关键的是让超额请求快速失败，保护上游。**

### M4 已知限制（待后续模块解决）

以下问题当前**已知但未处理**，按影响程度排序：

| 优先级 | 问题 | 计划 |
|---|---|---|
| ⚠️ 高 | API Key 取前 8 位作 keyId，存在**碰撞风险**（不同 key 前 8 位相同会被合并限流） | M9 鉴权时用真实 keyId 替换 |
| ⚠️ 高 | `X-Forwarded-For` **可被伪造**，生产环境应只信任可信代理层 | 上生产前加固 |
| ⚠️ 高 | **Lettuce 连接池可能成为新瓶颈**：限流让每请求多一次 Redis 调用，默认池上限偏低 | M14 压测时必须验证 |
| 🔶 中 | 多实例机器**时钟若不同步**，令牌计算仍会偏差（时间戳由应用传入） | 生产环境配 NTP |
| 🔶 中 | `remaining` 只返回各维度最小值，调用方无法看到各维度明细 | M11 可观测性扩展 |
| 🔶 中 | 429 响应体为空，无结构化 JSON 错误提示 | 可加 `contentType(JSON).body(...)` |
| 📌 低 | Redis **Cluster 模式下多 key 需同 slot**，否则报 `CROSSSLOT`；解法是用 hash tag 让同一用户的所有维度落同 slot | 当前单机无影响，需知晓 |
| 📌 低 | 尚未在高并发压测下验证限流稳定性 | 建议 `k6 run --vus 100 --duration 30s` 观察 429 比例 |

---

## 模块进度

### M1 新技术栈迁移 + 骨架跑通第一条链路 ✅

- [x] 路由转发：本地网关 → DeepSeek API，拿到真实模型响应
- [x] 自定义过滤器打印耗时
- [x] Redis 连通
- [x] Docker Compose 拉起 Redis + MySQL

### M2 虚拟线程深入 + 项目异步化改造（部分完成）

- [x] 虚拟线程生效验证
- [x] 压测对比：吞吐 +45%，p95 -46%
- [ ] **项目异步化改造** —— 延后至 **M8**
    - 理由：当前网关只有单条转发链路，无多余阻塞点，
      强行改 `CompletableFuture` 不产生实际收益（虚拟线程已承担 IO 等待）；
      待 M8 Token 计量引入"落库不阻塞主链路"的真实阻塞点后再改造，届时附对比数据。

### M3 Gateway 路由与过滤器链设计 ✅

- [x] 全局层 `GlobalTraceFilter`（requestId 透传、MDC、总耗时）
- [x] 多路由组织与 `@Order` 优先级
- [x] 过滤器执行顺序实测确认：`.filter()` 按添加顺序正序执行
- [x] 全局层 / 路由层职责分离与耗时差值观测（83ms）
- [x] requestId 全链路日志渲染

### M4 分布式限流（多维度 + 降级）✅

- [x] Redis + Lua 令牌桶（原子性）
- [x] 按 IP 维度限流并实测生效
- [x] **多维度限流**：一次 Lua 判多桶，先算后扣，无令牌浪费
- [x] **配置化**：rate / capacity 从 yml 读取，用 `record` 绑定
- [x] fail-open 降级容错
- [x] Redis 状态验证与 TTL 过期机制（含 1 小时保底）
- [x] 429 快速失败，被拒请求不转发上游

---

## 快速开始

### 1. 拉起依赖（服务器）

Compose 通过 `.env` 引用密码，不硬编码。在服务器 `/data/compose` 目录执行
`docker compose up -d` 即可拉起 Redis 与 MySQL。

### 2. 配置环境变量（本地）

`application.yml` 只保留占位符，不含明文：`${REDIS_HOST}` / `${REDIS_PASSWORD}` 等。
IDEA：**Run → Edit Configurations → Environment variables** 填入真实值。

| 配置项 | 值 | 说明 |
|---|---|---|
| `spring.threads.virtual.enabled` | `true` | 虚拟线程开关 |
| `logging.pattern.console` | 含 `%X{requestId}` | 让日志显示 requestId |
| `spring.data.redis.*` | 服务器地址 | 注意默认值是 `127.0.0.1` |

### 3. 测试入口

| 目标 | 请求 | 预期 |
|---|---|---|
| DeepSeek 真实转发 | `POST /v1/chat/completions` | 返回模型响应 |
| mock 压测链路 | `GET /mock/ai` | **2 秒后**返回 JSON |
| 限流验证 | 串行请求 `/mock/ai` ×10 | 前 4 个 200，之后 429 |
| 多维度验证 | 带 `Authorization: Bearer xxx` 请求后查 `KEYS ratelimit:*` | 出现 ip 与 key 两个独立 key |
| 404 可观测性 | `GET /not-exist` | 仅全局层打日志 |

请求体需为 **UTF-8 无 BOM**，否则 DeepSeek 会报 `invalid unicode code point`。

---

## 踩坑记录

### 依赖选型

| 坑 | 现象 | 解法 |
|---|---|---|
| 引错 Gateway 版本 | 引了 `...gateway-server-webflux` | 必须是 `spring-cloud-starter-gateway-server-webmvc` |
| `<import>scope</import>` 写反 | 依赖全红拉不下来 | 正确写法是 `<scope>import</scope>` |
| `docker-compose` 命令不存在 | apt 装的是已弃用的 v1.29 | 装官方 v2 二进制 |
| 插件装了仍报 unknown command | 二进制缺 `x` 权限 | `chmod +x`，放到 cli-plugins 目录 |
| MySQL 改密码不生效 | `MYSQL_ROOT_PASSWORD` 仅在数据目录为空时生效 | 删除数据目录重建，或进容器用 SQL 改 |

### 代码 API

| 坑 | 现象 | 解法 |
|---|---|---|
| `HandlerFunctions.http("url")` | `Expected no arguments but found 1` | 5.0.x 移除带参版本，改用 `http()` + `uri(...)` |
| `.before(new XxxFilter())` 泛型标红 | 手写 `implements HandlerFilterFunction` 签名对不齐 | 改用 lambda 内联，让编译器推断泛型 |
| 过滤器泛型参数填错 | `ServerRequest is not within its bound` | `HandlerFilterFunction` **两个参数都是响应类型**，应为 `<ServerResponse, ServerResponse>` |
| 过滤器日志不打印 | 请求被 yml 中另一条路由接走 | yml 与 Java DSL 二选一，严禁重复注册同一路径 |
| `@Slf4j` 与 MDC 混淆 | 以为 `@Slf4j` 能替代 MDC | `@Slf4j` 只生成 `log` 字段；`MDC` 需单独引入 |
| `RedisScript<List>` 泛型警告 | 无法写 `List<Object>.class` | Java 泛型擦除限制，加 `@SuppressWarnings("rawtypes")` |
| Lua 返回值强转异常 | 反序列化可能是 Integer 或 Long | 用 `(Number)` 中转再 `.longValue()` |

### 依赖注入

| 坑 | 现象 | 解法 |
|---|---|---|
| `redis` 为 null（NPE） | 构造器注入未发生 | 字段必须加 `final`（`@RequiredArgsConstructor` 只处理 final 字段）；或手写构造器排除 Lombok 影响 |
| 手动 `new` 导致注入失效 | 字段始终为 null | 过滤器/服务必须通过 Spring 容器获取，用方法参数注入 |
| 配置类未注册 | `Not registered via @EnableConfigurationProperties` | 启动类加 `@ConfigurationPropertiesScan` |
| record + `@Component` 冲突 | `Annotated with @ConstructorBinding but defined as Spring component` | **record 上删掉 `@Component`**，改由 `@ConfigurationPropertiesScan` 注册 |

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

### 限流专项

| 坑 | 现象 | 解法 |
|---|---|---|
| 速率按秒配，限流永不触发 | 串行请求全部 200 | AI 请求耗时 2-10 秒，应按分钟/小时配速率 |
| fail-open 掩盖真实错误 | 脚本报错但全放行，难排查 | 调试期临时改为抛异常，定位后改回降级放行 |
| TTL 过短导致限流失效 | `TTL` 返回 -2，桶被重置 | TTL 加 1 小时保底 |
| Lua 文件位置错误 | 脚本加载失败 | 必须在 `src/main/resources/lua/` 下，改完需 Rebuild |

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
| M3 | Gateway 路由与过滤器链设计 | ~15h | ✅ 完成 |
| **【核心功能：流量治理】** | | | |
| M4 | 分布式限流（多维度 + 降级） | ~18h | ✅ 完成 |
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

> 基于 Spring Cloud Gateway MVC + Java 21 虚拟线程构建 AI 网关：
> ① 通过 k6 压测验证，在模拟 AI 响应（2 秒延迟）场景下，虚拟线程相比平台线程
> 吞吐提升 45%（100.5 → 146.3 req/s），p95 延迟降低 46%（4086ms → 2190ms），
> 突破 Tomcat 平台线程 `maxThreads=200` 的并发瓶颈；
> ② 设计全局层与路由级两层过滤器链，实现 requestId 全链路追踪与
> 网关自身开销可观测（实测 83ms）；
> ③ 基于 Redis + Lua 实现分布式令牌桶限流，支持 IP / API Key 多维度，
> 一次 Lua 判多桶保证原子性并避免部分扣减造成的令牌浪费，
> 采用 fail-open 降级避免 Redis 成为单点故障。


