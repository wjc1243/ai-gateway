# AI Gateway

基于 Spring Cloud Gateway Server WebMVC + Java 21 虚拟线程构建的 AI 网关，
统一管理大模型 API 的转发、限流、鉴权与计费。

## 技术栈
- Java 21（虚拟线程）
- Spring Boot 4.1.1 + Spring Cloud Gateway Server WebMVC 5.0.x
- Redis 7（限流 / 配额）
- MySQL 8（持久化）
- Docker Compose

## 为什么选 MVC + 虚拟线程，而不是 WebFlux
> TODO：M13 做双版本对比后补上压测数据和选型结论

## 当前进度
- [x] M1：网关骨架跑通 —— 路由转发、自定义 Filter 打印耗时、Redis 连通
- [x] M1：Docker Compose 拉起 Redis + MySQL
- [ ] M2：虚拟线程压测
- [ ] M3：统一 API Key 管理
- [ ] M4：分布式限流
  ...

## 快速开始
1. `docker compose up -d` 拉起 Redis + MySQL
2. 配置环境变量 `REDIS_HOST` / `REDIS_PASSWORD`
3. 启动 `GatewayAiMvcApplication`
4. 测试：