# AI Gateway

基于 **Spring Cloud Gateway Server WebMVC + Java 21 虚拟线程** 构建的 AI 网关，
统一管理大模型 API 的转发、限流、鉴权与计费。

> 当前进度：**M1、M2 已完成**（含真实压测数据），M3 起开发中。

---

## 为什么选 MVC + 虚拟线程，而不是 WebFlux

WebFlux 的响应式编程模型性能好，但代码是"回调地狱"式的，调试和排查成本高。
Java 21 的虚拟线程让 **同步阻塞写法也能达到接近响应式的并发能力**——
写起来像普通 Servlet 代码，跑起来能扛高并发。

AI 网关是**典型的 IO 密集型**场景：请求转发给大模型后，线程就在**干等几秒**，CPU 完全空闲。
这正是虚拟线程的主战场。本项目在 M13 会做 WebFlux 双版本对比，用压测数据给出选型结论。

<!-- TODO: M13 完成双版本对比后补充压测数据与最终选型结论 -->

---

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | 21 | 虚拟线程（核心） |
| Spring Boot | 4.1.1 | |
| Spring Cloud | 2025.1.2 | |
| Spring Cloud Gateway | Server WebMVC 5.0.x | **非 WebFlux 版** |
| Redis | 7 (alpine) | 限流 / 配额存储 |
| MySQL | 8.0 | 持久化（M3/M4 启用，当前已停） |
| Docker Compose | v2 | 一条命令拉起依赖 |
| k6 | - | 压测工具 |

---

## 已完成模块

### M1 网关骨架跑通

- [x] 路由转发：本地网关 → DeepSeek API，拿到真实模型响应
- [x] 自定义 Filter 打印耗时：确认虚拟线程生效
- [x] Redis 连通
- [x] Docker Compose 拉起 Redis + MySQL

### M2 虚拟线程压测（已出真实数据）

详见下方 [压测数据](#压测数据m2)。

---

## 压测数据（M2）

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

实测 100.51，几乎分毫不差。**再加并发也上不去**，多出来的请求只能排队等线程释放。

**2. ON 组的 146.3 req/s 逼近"零排队"理论上限**

300 并发 ÷ 2.03 秒平均延迟 = 147.8 的理论上限，实测 146.3。
说明 300 个并发请求**几乎全部被同时招待**，没有因线程不足而干等。

**3. p95 = 4086ms 是最能说明问题的一个数**

mock 本身固定睡 2000ms，所以：

- ON：**2190ms** → 比 2000 只多 190ms，**几乎没排队**
- OFF：**4086ms** → 比 2000 多出整整 **2086ms ≈ 又一整个 2 秒**

多出来的这 2 秒就是**排队等线程**的时间。
类比银行：ON 是 300 人同时拿号办理；OFF 是只有 200 个柜台，
剩下 100 人干等第一批办完（2 秒）才轮到 → 总耗时 = 2 秒等待 + 2 秒办理 = **4 秒**。

### 验证方式

在网关 Filter 中打印 `Thread.currentThread()`：

```
虚拟线程 ON  : VirtualThread[#81,tomcat-handler-0]/runnable@ForkJoinPool-1-worker-1
虚拟线程 OFF : Thread[#42,http-nio-8080-exec-1,5,main]
```

看到 `VirtualThread` + `tomcat-handler-0` 即证明 Tomcat 请求处理线程已被虚拟线程替换。

---

## 快速开始

### 1. 拉起依赖（服务器）

```bash
cd /data/compose
docker compose up -d
docker compose ps
```

Compose 文件通过 `.env` 引用密码，不硬编码：

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

`application.yml` 中只保留占位符，不含明文：

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

`body.json` 需为 **UTF-8 无 BOM**，否则 DeepSeek 会报
`invalid unicode code point`。

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

## 踩坑记录

### 依赖选型

| 坑 | 现象 | 解法 |
|---|---|---|
| 引错 Gateway 版本 | 引了 `...gateway-server-webflux` | 必须是 `spring-cloud-starter-gateway-server-webmvc` |
| `<import>scope</import>` 写反 | 依赖全红拉不下来 | 正确写法是 `<scope>import</scope>`，写反导致整个 Spring Cloud BOM 没导入 |
| `docker-compose` 命令不存在 | apt 装的是已弃用的 v1.29 | 装官方 v2 二进制；注意文件名带 `-plugin` 或用独立二进制 |
| 插件装了仍报 unknown command | 二进制缺 `x` 权限 | `chmod +x`，并放到 `docker info` 显示的 cli-plugins 目录 |

### 代码 API

| 坑 | 现象 | 解法 |
|---|---|---|
| `HandlerFunctions.http("url")` | `Expected no arguments but found 1` | 5.0.x 移除了带参版本，改用 `http()` + `BeforeFilterFunctions.uri(...)` |
| `.before(new LoggingFilter())` 泛型标红 | 手写 `implements HandlerFilterFunction` 签名对不齐 | 改用 lambda 内联，让编译器推断泛型 |

### 环境 / 工具

| 坑 | 现象 | 解法 |
|---|---|---|
| PowerShell 里 `curl` 报 `无法绑定参数 Headers` | `curl` 是 `Invoke-WebRequest` 别名，`-H` 要求字典 | 用 `curl.exe`，或改用 Git Bash |
| 中文请求体 400 `invalid unicode code point` | Windows 命令行按 GBK 编码发送 | body 写入 UTF-8 **无 BOM** 文件，用 `--data-binary @body.json` |
| API Key 泄漏 | 报错信息里明文带出 `sk-` 开头密钥 | 立即去控制台吊销重建；贴日志前务必打码 |
| MySQL 改密码不生效 | `MYSQL_ROOT_PASSWORD` 仅在数据目录为空时生效 | 需删除 `/data/mysql` 重建，或进容器改 |

---

## 安全说明

- 所有密码走 `${环境变量}` 占位符 + `.env` 文件，`.env` 已加入 `.gitignore`
- 服务器防火墙**仅开放 6379**，3306 不开放公网（本地走 SSH 隧道）
- 代码中不含任何明文 IP、密码、API Key

---

## 开发路线

| 模块 | 内容 | 状态 |
|---|---|---|
| M1 | 网关骨架：路由转发 / 日志 Filter / Redis 连通 / Compose 拉起依赖 | ✅ 完成 |
| M2 | 虚拟线程压测（已出数据） | ✅ 完成 |
| M3 | 统一 API Key 管理 | 🔜 进行中 |
| M4 | 分布式限流 | ⬜ |
| M5 | 鉴权 | ⬜ |
| M6-M9 | 配额计费 / 多模型路由 / 日志审计 | ⬜ |
| M10 | 监控（Prometheus + Grafana） | ⬜ |
| M13 | WebFlux 双版本对比 | ⬜ |

---

## 一句话简历话术

> 基于 Spring Cloud Gateway MVC + Java 21 虚拟线程构建 AI 网关，通过 k6 压测验证：
> 在模拟 AI 响应（2 秒延迟）场景下，虚拟线程相比平台线程吞吐提升 45%
> （100.5 → 146.3 req/s），p95 延迟降低 46%（4086ms → 2190ms），
> 突破 Tomcat 平台线程 `maxThreads=200` 的并发瓶颈。
