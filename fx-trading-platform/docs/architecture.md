# 架构说明

`fx-trading-platform` 是独立于根目录 KLineCharts 源码的交易平台子项目，按后端、交易端、管理端、基础设施和验证脚本分层。

当前完整前后端架构和数据库设计请先看：

- [FX Trading Platform 前后端架构与数据库设计总览](./architecture-and-database-design-cn.md)

## 分层边界

- `backend/`: Spring Boot 后端，统一承载认证、账户、行情、交易、风控、执行、资金流水、后台和审计。
- `apps/web/`: PC/H5 交易端，只调用平台 API，不直接访问 Massive 或数据库。
- `apps/admin/`: 后台管理端，只访问 `/api/admin/**` 和登录接口，不复用交易端状态。
- `infra/`: 本地 PostgreSQL、Redis 和服务编排。
- `scripts/`: 本地架构约束和端到端 smoke 验证。

## 关键规则

- 行情源适配通过 `market/provider` 的 `ProviderResolver` / `MarketDataRouter` 路由到 Massive、Binance、OKX 等 provider adapter，出站统一为平台 `QuoteResponse`、`CandleResponse`、盘口和近期成交 DTO。
- 下单必须经过 `RiskCheckService`，成交写入统一走 `OrderFillService`。
- 市价单、挂单、止盈止损可以有不同触发入口，但订单、仓位、保证金和资金流水必须复用同一套服务。
- 前端展示异步交易状态时通过后端 API 刷新，不能自行推断订单已成交。

## 阶段文档

- [交易下单/持仓链路分层改造说明](./trading-order-position-refactor.md)
