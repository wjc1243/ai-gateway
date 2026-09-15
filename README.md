# AI Gateway

基于 **Spring Cloud Gateway Server WebMVC + Java 21 虚拟线程** 构建的 AI 网关，
统一管理大模型 API 的转发、限流、熔断、计量与鉴权。

> 当前进度：**M1 完成 / M2 部分完成 / M3 进行中**
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

在网关 Filter 中打印 `Thread.currentThread()`：

```
虚拟线程 ON  : VirtualThread[#81,tomcat-handler-0]/runnable@ForkJoinPool-1-worker-1
虚拟线程 OFF : Thread[#42,http-nio-8080-exec-1,5,main]
```

看到 `VirtualThread` + `tomcat-handler-0` 即证明 Tomcat 请求处理线程已被虚拟线程替换。

> ⚠️ 本组数据为 M2 阶段的**验证性压测**，用于确认虚拟线程生效与量化收益。
> 正式压测与 JVM 调优报告在 **M14** 产出。

---

## 已完成内容

### M1 新技术栈迁移 + 骨架跑通第一条链路 ✅

- [x] 路由转发：本地网关 → DeepSeek API，拿到真实模型响应
- [x] 自定义 Filter 打印耗时
- [x] Redis 连通
- [x] Docker Compose 拉起 Redis + MySQL

### M2 虚拟线程深入 + 项目异步化改造（部分完成）

- [x] 虚拟线程生效验证（`Thread.currentThread()` 打印）
- [x] 压测对比：吞吐 +45%，p95 -46%（数据见上）
- [ ] **项目异步化改造**（`CompletableFuture` 编排、异步日志/审计落库）——待做

### M3 Gateway 路由与过滤器链设计（进行中）

- [x] Java DSL 路由定义（`GatewayRouterFunctions`）
- [x] 路由级日志过滤器（`HandlerFilterFunction`）
- [ ] 多路由组织与优先级
- [ ] 全局过滤器 vs 路由级过滤器的取舍
- [ ] yml 配置化路由 vs Java DSL 的适用场景划分

---

## 核心代码

### 路由 + 日志过滤器（Java DSL）

```java
@Configuration
public class GatewayRoutesConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutesConfig.class);

    @Bean
    public RouterFunction<ServerResponse> deepseekRoute() {
        return GatewayRouterFunctions.route("deepseek-route")
                .POST("/v1/chat/completions", HandlerFunctions.http())   // 5.0.x 为无参
                .before(BeforeFilterFunctions.uri("https://api.deepseek.com"))
                .before((request, next) -> {
                    long start = System.currentTimeMillis();
                    log.info("请求进入: {} {}", request.method(), request.uri());
                    log.info("当前线程: {}", Thread.currentThread());

                    ServerResponse response = next.handle(request);

                    log.info("请求完成: {} {} | 耗时 {}ms | 状态码 {}",
                            request.method(), request.uri(),
                            System.currentTimeMillis() - start,
                            response.statusCode());
                    return response;
                })
                .build();
    }
}
```

> `HandlerFunctions.http()` 在 Gateway 5.0.x **不接受 URI 参数**，
> 目标地址必须用 `.before(BeforeFilterFunctions.uri(...))` 指定。

---

## 快速开始

### 1. 拉起依赖（服务器）

```bash
cd /data/compose
docker compose up -d
docker compose ps
```

Compose 通过 `.env` 引用密码，不硬编码：

```yaml
services:
  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD} --appendonly yes
    ports: ["6379:6379"]
    volumes: ["/data/redis:/data"]

  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ai_gateway
    ports: ["3306:3306"]
    volumes: ["/data/mysql:/var/lib/mysql"]
```

### 2. 配置环境变量（本地）

`application.yml` 只保留占位符，不含明文：

```yaml
spring:
  threads:
    virtual:
      enabled: true          # 虚拟线程开关
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

IDEA：**Run → Edit Configurations → Environment variables** 填入
`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`。

### 3. 启动 & 测试

```bash
# 直连 DeepSeek
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_KEY" \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @body.json

# mock 路由（压测用，2 秒延迟不花钱）
curl http://localhost:8080/mock/ai
```

`body.json` 需为 **UTF-8 无 BOM**，否则 DeepSeek 会报 `invalid unicode code point`。

---

## 踩坑记录

### 依赖选型

| 坑 | 现象 | 解法 |
|---|---|---|
| 引错 Gateway 版本 | 引了 `...gateway-server-webflux` | 必须是 `spring-cloud-starter-gateway-server-webmvc` |
| `<import>scope</import>` 写反 | 依赖全红拉不下来 | 正确写法是 `<scope>import</scope>`，写反导致整个 Spring Cloud BOM 未导入 |
| `docker-compose` 命令不存在 | apt 装的是已弃用的 v1.29 | 装官方 v2 二进制；注意包名带 `-plugin` 或用独立二进制 |
| 插件装了仍报 unknown command | 二进制缺 `x` 权限 | `chmod +x`，并放到 `docker info` 显示的 cli-plugins 目录 |
| MySQL 改密码不生效 | `MYSQL_ROOT_PASSWORD` 仅在数据目录为空时生效 | 删除 `/data/mysql` 重建，或进容器用 SQL 改 |

### 代码 API

| 坑 | 现象 | 解法 |
|---|---|---|
| `HandlerFunctions.http("url")` | `Expected no arguments but found 1` | 5.0.x 移除带参版本，改用 `http()` + `BeforeFilterFunctions.uri(...)` |
| `.before(new LoggingFilter())` 泛型标红 | 手写 `implements HandlerFilterFunction` 签名对不齐 | 改用 lambda 内联，让编译器推断泛型 |
| 过滤器日志不打印 | 请求被 yml 中另一条路由接走 | yml 与 Java DSL 二选一，避免重复注册路由 |

### 环境 / 工具

| 坑 | 现象 | 解法 |
|---|---|---|
| PowerShell 里 `curl` 报 `无法绑定参数 Headers` | `curl` 是 `Invoke-WebRequest` 别名，`-H` 要求字典 | 用 `curl.exe`，或改用 Git Bash |
| 中文请求体 400 `invalid unicode code point` | Windows 命令行按 GBK 编码发送 | body 写入 UTF-8 **无 BOM** 文件，用 `--data-binary @body.json` |
| `docker compose -version` 报 `unknown shorthand flag: 'e'` | compose 子命令不加短横线 | 正确的是 `docker compose version` |
| k6 报 `no exported functions in script` | 脚本放错目录 / 文件为空 | 放项目根目录，用 `cat >` 直接写入并 `cat` 确认 |
| k6 读取 `p(99)` 报 KeyError | 不同版本导出的分位数键名不同 | 用兼容取值，或看 `p(95)` 与 `avg` |
| 压测报 `connection refused` | 目标服务未启动 | 确认 mock(9000) 与网关(8080) 同时在运行 |
| API Key 泄漏 | 报错信息明文带出 `sk-` 密钥 | 立即去控制台吊销重建；贴日志前打码 |

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
| M2 | 虚拟线程深入 + 项目异步化改造 | ~20h | 🔶 部分完成 |
| M3 | Gateway 路由与过滤器链设计 | ~15h | 🔜 进行中 |
| **【核心功能：流量治理】** | | | |
| M4 | 分布式限流（多维度 + 降级） | ~18h | ⬜ |
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
> 突破 Tomcat 平台线程 `maxThreads=200` 的并发瓶颈。
