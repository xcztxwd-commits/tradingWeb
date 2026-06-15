# 真实交易闭环与用户核心页面迭代计划

## 目标

把当前交易终端从“可演示下单”推进到“真实模拟盘 v1”。第一阶段先闭合订单、成交、持仓、账本、撤单、改单、TP/SL 的业务链路；第二阶段再把用户主导航里的占位页改成真实 API 页面。

本计划不接真实入金、链上提现、真实 broker/FIX/LP，也不做期权、跟单、交易机器人、组合保证金。这些属于后续商业化阶段。

## 第一阶段：真实交易闭环

### 后端任务

1. 用户订单事件查询
   - 新增 `GET /api/trading/orders/{orderId}/events`。
   - 只允许订单所属用户读取。
   - 返回订单状态流转、拒单原因、撤单/改单/成交事件。
   - 验证：非本人订单返回权限错误；本人订单按时间倒序或正序稳定返回。

2. 用户撤单
   - 新增 `POST /api/trading/orders/{orderId}/cancel`。
   - 仅允许 `PENDING` 订单撤销。
   - 设置 `status=CANCELED`、`canceledAt`，写入 `ORDER_CANCELED` 事件。
   - 如果 pending 订单已经冻结保证金，撤单释放对应金额并写 `ORDER_RELEASE` 账本。
   - 验证：撤单后用户订单列表和后台订单列表状态一致；已成交订单不能撤。

3. 用户改单
   - 新增 `PATCH /api/trading/orders/{orderId}`。
   - 仅允许 `PENDING` 订单修改价格、数量、SL、TP。
   - 修改价格或数量后重新风控，更新冻结保证金差额。
   - 写入 `ORDER_MODIFIED` 事件，保留原始订单 ID 和 clientOrderId。
   - 验证：改价后触发价按新价格执行；保证金不足时拒绝修改且订单保持原状态。

4. Pending order 执行链路
   - demo/dev 环境打开 pending 扫描，生产默认仍由环境变量决定。
   - 限价/止损挂单触价后复用统一成交写入路径。
   - 成交时不能重复冻结保证金：如果订单已有 holdAmount，则转为持仓保证金；如果没有，按成交时风控金额占用。
   - 验证：限价单从 `PENDING` 变 `FILLED`，生成 trade/position/ledger。

5. TP/SL 执行链路
   - demo/dev 环境打开 protective 扫描，生产默认仍由环境变量决定。
   - TP/SL 触发时复用 `PositionService` 平仓。
   - 后续可补系统平仓事件；本阶段先保证持仓、余额、保证金、流水一致。
   - 验证：触发 TP/SL 后 position 关闭，释放保证金，写 PnL 流水。

6. 费用、滑点、拒单、部分成交模型
   - 扩展 `ExecutionResult`，至少包含 `filledQuantity`、`remainingQuantity`、`avgFillPrice`、`fee`、`slippage`、`rejectCode`、`rejectMessage`。
   - 模拟执行器先用确定性规则：小单全成，大单可部分成交，报价缺失或风控失败给拒单原因。
   - 订单表增加 fee/slippage 字段，响应返回这些字段。
   - 账本增加 `TRADE_FEE` 记录。
   - 验证：市价单可以出现全成、拒单、部分成交三种状态；前端能展示原因。

### 前端任务

1. 登录态只允许真实后端订单
   - 登录用户点击下单只调用 `/api/trading/orders`。
   - 移除登录态下的本地 mock submit fallback。
   - 未登录用户保留行情浏览和登录提示，不创建假订单。
   - 验证：后端不可用时登录态下单显示错误，不再生成本地假 order。

2. 订单操作入口
   - 订单列表或交易面板支持撤销 pending order。
   - 改单先做最小可用弹层或表单，只改 pending 单的价格/数量/TP/SL。
   - 订单详情展示事件时间线。
   - 验证：用户撤单/改单后列表状态和后台一致。

3. 成交与风险字段展示
   - 订单行展示手续费、滑点、拒单原因、部分成交数量。
   - 提交按钮状态覆盖 loading/error/success。
   - 验证：真实 API 返回字段能直接渲染，不依赖 mock 数据。

## 第二阶段：用户核心页面

### `/dashboard`

- 展示账户净值、余额、可用保证金、已用保证金、浮动盈亏、今日成交数、最近订单、最近持仓。
- 全部数据来自真实 API，可先用已有 account/orders/positions/ledger 聚合。
- 状态要求：loading、error、empty。

### `/markets`

- 展示真实 symbol 列表、报价、涨跌、spread、状态、分类筛选。
- 点击市场进入 `/trading?symbol=...`。
- 状态要求：搜索、分类筛选、loading、error、empty。

### `/orders`

- 展示用户订单分页、状态筛选、symbol 筛选、时间筛选。
- 支持 pending 订单撤单、改单、查看事件。
- 状态要求：loading、error、empty、filter、pagination。

### `/positions`

- 展示开仓持仓、浮动盈亏、保证金、当前价、SL/TP。
- 支持用户平仓，后续支持修改持仓 TP/SL。
- 状态要求：loading、error、empty、filter、pagination。

### `/wallet`

- 先做 demo 资金账本和资金申请，不接支付或链。
- 展示余额、流水、demo 充值申请、提现申请、审核状态。
- 后台审核继续走已有 finance/admin 能力。
- 状态要求：loading、error、empty、filter、pagination。

## 推荐执行顺序

1. 后端订单事件查询、撤单、改单测试与实现。
2. pending order 冻结保证金和成交不重复占用。
3. demo/dev 打开 pending 与 TP/SL 扫描，并补验收脚本。
4. 扩展执行模型的费用、滑点、拒单、部分成交。
5. 前端移除登录态 mock submit fallback。
6. `/orders` 和 `/positions` 先落地，因为它们直接服务第一阶段验收。
7. `/wallet` 落地 demo 账本和申请状态。
8. `/dashboard` 和 `/markets` 完成主导航占位页清零。

## 本次执行切片

先完成第一阶段的最小真实闭环：

1. 后端补订单事件查询、撤单、改单。
2. pending 订单创建时冻结保证金，撤单释放，成交时不重复冻结。
3. 前端登录态下单不再走本地 mock fallback。
4. 补对应后端/前端测试。

完整费用、滑点、拒单、部分成交模型和五个用户核心页面进入后续切片，避免一次改动过大导致验证失真。
