# InstrumentRulesEngine 实现记录

日期：2026-06-17

## 已实现

- 新增后端统一规则模型与服务：
  - `InstrumentRules`
  - `InstrumentRulesEngine`
  - 从 `SymbolEntity`、provider binding raw rules、`RiskConfigEntity`、`TradingInstrumentClassifier` 合并输出 symbol 交易规则。
- 新增后端规则 API：
  - `GET /api/market/symbols/{symbol}/rules`
  - `GET /api/market/symbol-rules?symbols=BTCUSDT,ETHUSDT`
- `RiskCheckService.checkOrder` 已在下单前调用规则引擎：
  - symbol enabled/tradable/order capability
  - market order quote capability
  - quantity min/max
  - `stepSize`
  - limit price `tickSize`
  - `maxLeverage`
  - `minNotional` / `maxNotional`
- 前端已接入统一 rules 数据：
  - market API adapter 支持 rules endpoint 和 response 数值映射。
  - `TradingPage` 在 selected symbol 变化时拉取 rules，并合并到 selected market。
  - 桌面和移动 `TradePanel` 都接收同一份 rules。
  - `TradePanel` 使用 rules 推导 `minNotional`、`minQty/minLot`、`tickSize/stepSize` 对应的输入精度、`contractSize`、`maxLeverage/defaultLeverage`、`enabled/tradable/orderEnabled` 禁用状态。
- 增加回归测试：
  - `InstrumentRulesEngineTest`
  - `RiskCheckServiceTest` 的规则引擎委托校验
  - `MarketControllerTest` 的单查/批量 rules endpoint
  - 前端 adapter、`TradingPage`、`TradePanel`、`tradePanelMarket` rules 链路测试

## 未实现 / 保留项

- `OrderService.modifyOrder` 改单复验尚未接入。
- `PendingOrderExecutionService` 触发前复验 symbol tradable 尚未接入。
- `ProtectiveOrderExecutionService` 触发 TP/SL 前复验尚未接入。
- `AdminMarketSymbolService` 修改规则后的缓存刷新尚未实现；本轮没有新增缓存层。
- risk tier 目前来自 `RiskConfigEntity` 的启用配置摘要，不是完整的交易所分层保证金/杠杆阶梯。
- KYC 要求和用户风控等级限制目前是规则输出字段，尚未接入真实用户等级/认证状态判定。
- trading session 目前是规则输出字段，尚未接入交易所日历或休市窗口。
- 前端尚未实现输入框 blur 时自动把用户文本改写到最近 `tickSize/stepSize`，本轮实现的是规则驱动精度、最小金额、可交易状态和后端最终拒单。

## 验证结果

- 通过：`backend` `mvn -q clean -Dtest=InstrumentRulesEngineTest,RiskCheckServiceTest,MarketControllerTest test`
- 通过：`backend` `mvn -q -DskipTests compile`
- 通过：`apps/web` `node --test src/features/market/tradingMarketApi.test.ts src/features/trading/components/tradePanelMarket.test.ts src/features/trading/components/TradePanel.test.ts src/pages/trading/TradingPage.test.ts`
- 通过：`apps/web` `npm run build`

## 备注

- 仓库在本轮开始前已有大量未提交变更；本轮只围绕 InstrumentRulesEngine、规则 API、下单前规则校验和前端交易面板规则消费做增量修改。
- 后端仍是最终规则校验源，前端 rules 校验只用于体验级提示和禁用。
