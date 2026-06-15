# wh-admin FX 后台只读分析与本地项目适配文档

## 1. 调研边界和安全结论

本次调研目标是分析 `https://wh-admin.lonbmau.vip/#/message` 所在 FX 交易后台管理系统，并评估如何适配当前仓库的 `fx-trading-platform/apps/web` 前端项目。

强制边界已执行：

- 未点击页面内任何 `确定`、`保存`、`提交`、`删除`、`审核通过`、`驳回` 等会修改数据的按钮。
- 未提交任何表单，未修改后台数据。
- 未在文档中记录用户提供的后台密码。
- 浏览器插件和 Windows UI 自动化通道不稳定，后续主要使用只读静态资源分析：读取 HTML、JS chunk、页面文本、字段配置、API 字符串。
- 登录页存在 `system/captcha` 与前端验证码校验；未尝试绕过验证码。

证据来源：

- HTML 入口：`GET https://wh-admin.lonbmau.vip/` 返回 Vite SPA。
- 入口资源：`/assets/index-2c28fe40.js`、`/assets/index-ab3b58b9.css`。
- 已验证可见页面：`#/message` 标题为 `消息中心 - 后台管理系统`。
- 静态包共发现 125 个 JS chunk，其中 95 个 `views/...vue` 组件映射。

## 2. 前端架构判断

后台是基于 Vue 3、Vite、Arco Design、MineAdmin 风格组件构建的 SPA。

核心实现特征：

- 路由：Hash Router，基础路由包含 `/dashboard`、`/usercenter`、`/message`、`/login`、`/mineDoc`。大量业务页面由后台权限菜单动态注入，前端通过 `views/...vue -> assets/...js` 映射加载。
- UI 框架：页面大量使用 `a-input`、`a-input-password`、`a-button`、`a-form`、`a-modal`、`a-tag`、`a-space`、`a-date-picker`、上传、富文本、用户选择器等。
- CRUD 框架：核心列表页使用 `ma-crud`，通过 `columns` 配置自动生成搜索表单、表格、添加/编辑弹窗、删除/回收站/恢复、导入导出等能力。
- 权限：按钮和接口用 `auth:["system:xxx:action"]` 控制，例如 `system:member:save`、`system:gd:export`。
- 数据字典：大量 select 选项来自 `dict:{name:"..."}` 或 `dict:{url:"..."}`，实际选项值由后台接口返回。静态包能确认字典 key，不能完整确认线上字典值。
- API：封装函数以 `system/...`、`setting/...` 作为资源路径，HTTP method 区分读写。`getList/index/list/read` 是读取；`save/update/delete/realDelete/recovery/changeStatus/numberOperation` 是变更。

## 3. 全局控件和按钮语义

所有 `ma-crud` 页面基本都有以下控件：

- 搜索区：由 `search: true` 字段生成输入框、下拉框、日期范围框、树选择器等。
- `搜索`：按搜索区条件请求列表接口，通常是 GET。
- `重置`：清空搜索条件并重新加载列表。
- `新增`：打开新增弹窗，最终调用 `save` POST。
- `编辑`：打开当前行编辑弹窗，最终调用 `update/:id` PUT。
- `删除`：软删除或删除，通常调用 DELETE。
- `真实删除`：彻底删除，风险最高。
- `回收站` / `恢复`：查看已删数据和恢复，恢复是 PUT。
- `导入` / `导出`：导入会上传文件并写入数据；导出会生成下载文件。
- `修改状态`：通常是 switch 或状态按钮，调用 `changeStatus` PUT。
- `排序/自增自减`：调用 `numberOperation` PUT。
- `查看/详情`：通常打开只读详情弹窗或 drawer。
- `取消`：关闭弹窗。
- `确定` / `保存` / `提交`：最终触发写接口，巡检时禁止点击。

## 4. 已验证的消息中心页面

路径：`#/message`，组件：`views/userCenter/message.vue`，chunk：`assets/message-7e4d7251.js`，服务：`assets/queueMessage-99e539f8.js`。

可见菜单与布局：

- 左侧主菜单可见：`首页`、`仪表盘`、`个人信息`、`消息中心`、`权限`、`产品管理`、`财务管理`、`用户管理`、`订单管理`、`验证码发送记录`、`请求日志`、`公告列表`、`新闻列表`、`系统设置`、`通知表`。
- 页面内标签：`收件箱`、`已发送`、`私信`、`抄送我的`、`待办`、`公告`、`通知`。
- 搜索输入：`请输入消息标题`。
- 日期范围：`请选择开始时间`、`请选择结束时间`。
- 状态单选：`全部`、`未读`、`已读`。
- 操作按钮：`搜索`、`重置`、`发私信`、`删除`。
- 表格列：`发送人`、`消息标题`、`消息类型`、`发送时间`、`操作`。

实现：

- 收件箱接口：`system/queueMessage/receiveList` GET。
- 已发送接口：`system/queueMessage/sendList` GET。
- 接收用户：`system/queueMessage/getReceiveUser` GET。
- 删除消息：`system/queueMessage/deletes` DELETE。
- 标记已读：`system/queueMessage/updateReadStatus` PUT。
- 发私信：`system/queueMessage/sendPrivateMessage` POST。

适配价值：

- 可作为本地交易前端的通知中心模型：站内信、系统公告、订单/资金提醒。
- 本地项目建议新增独立只读通知入口，不应把 `sendPrivateMessage`、`deletes` 暴露给普通交易用户。

## 5. 交易业务后台页面深度分析

### 5.1 产品分类

组件：`views/system/productCate/index.vue`，接口前缀：`system/productCate`。

字段和控件：

- `名称 name`：搜索输入、新增/编辑输入。
- `创建时间 created_at`、`更新时间 updated_at`、`删除时间 deleted_at`：日期显示或日期控件。
- 常规按钮：新增、编辑、删除、回收站、恢复、真实删除。

用途：

- 管理产品分组，例如外汇、贵金属、指数、数字货币等。
- 被产品列表的 `分类 cate` 字段通过 `system/productCate/list` 引用。

适配：

- 映射到本地 `TradingMarket.assetClass` 或市场分组。
- 本地 `MarketSidebar` 可用分类做分组筛选。

### 5.2 产品管理

组件：`views/system/product/index.vue`，chunk：`assets/index-c0341ddf.js`，服务：`assets/systemProduct-02f840fe.js`。

接口：

- `system/product/index` GET：产品列表。
- `system/product/save` POST：新增产品。
- `system/product/update/:id` PUT：更新产品。
- `system/product/delete` DELETE：删除。
- `system/product/recycle` GET、`recovery` PUT、`realDelete` DELETE：回收站链路。
- `system/product/set_kline` POST：设置 K 线涨跌。
- `system/product/set_fk` POST：设置风控价格。
- `system/product/changeStatus` PUT：改状态。
- `system/product/numberOperation` PUT：数值增减。

字段和控件：

- `分类 cate`：select，数据源 `system/productCate/list`。
- `产品名称 name`：input，搜索。
- `产品代码 code`、`产品代码2 code2`：input。
- `增量 fk_price`、`计数 fk_num`、`设置时间 fk_time`：风控相关输出字段。
- `是否设置 is_fk`：select，字典 `is_fk`。
- `价格精度 price_jd`：input。
- `最新价格 price`：input。
- `倍数 beishu`、`默认倍数 beishu_moren`：input。
- `每手`、`费率`、`状态`、`内容`：交易参数和展示内容。
- 行按钮：`风控`、`涨跌/K线设置`、`查看涨跌控制记录`。

风控弹窗：

- `风控设置`：打开/关闭、增/减量、计数、上次设置时间、显示作用、状态。
- 写接口：`system/product/set_fk`。

涨跌设置弹窗：

- `涨跌`、`开始时间`、`开始价格`、`结束时间`、`结束价格`。
- 写接口：`system/product/set_kline`。

适配：

- 产品列表应成为本地 `fetchMarketSymbols()` 的后端真实来源。
- `price_jd` 应映射到 `getPricePrecision()` 和价格输入格式化。
- `beishu` / `beishu_moren` 应映射到 `TradePanel` 杠杆选项，而不是写死 `[1,2,3,5,10]`。
- `fee/rate` 应映射到交易面板手续费展示。
- `status` 应控制产品是否可交易、是否只展示。

### 5.3 产品 K 线控制记录

组件：`views/system/proKline/index.vue`，接口：`system/proKline/index` GET。

字段：

- `开始时间`、`开始价格`、`截止时间`、`截止价格`。
- `产品ID`。
- `涨跌`：通常 `1涨2跌`。
- `状态`。
- `创建时间`、`更新时间`、`删除时间`。

用途：

- 记录或查询后台人为设置的产品涨跌/K线控制。

适配：

- 本地 `KLineChartPanel` 不应直接暴露后台涨跌控制。
- 若要兼容后台行情，可把它作为行情服务的一层输入，最终只输出标准 candles 到 `chartCandleData` / `tradingMarketApi`。

### 5.4 用户管理 / 交易会员

组件：`views/system/member/index.vue`，chunk：`assets/index-153f3351.js`，服务：`assets/systemMember-ca4c1683.js`。

接口：

- `system/member/index` GET。
- `system/member/save` POST。
- `system/member/update/:id` PUT。
- `system/member/delete` DELETE。
- `system/member/edit_pwd` POST。
- `system/member/fstz` POST：发信。
- `system/member/edit_realname` POST：实名审核。
- `system/member/edit_money` POST：修改余额。
- `system/member/yl` POST：一键控盈利。
- `system/member/ks` POST：一键控输。
- `system/member/xiaxian` POST：踢下线。
- `system/member/zc` POST：一键控正常。
- `system/member/ht_login` POST：后台代登录。
- `system/member/bz` POST：备注。

字段：

- `控制 kong`：select，字典 `kong`，搜索。
- `UID id`：搜索。
- `备注 describe`：input，搜索。
- `账号类型 is_ty`：select，字典 `is_ty`，搜索。
- `用户账号 username`、`客服 kf_url`、`手机号码 phone`、`年收入`、`用户密码`、`用户昵称 nickname`、`头像`。
- `排序权重`、`余额`、`邀请码`、`上级ID`、`状态`、`交易`。
- `登录IP`、`登录时间`、`登录次数`、`注册IP`。
- `安全密码`、`登陆失败次数`。
- `实名状态`、`真实姓名`、`证件号码`、`正面图片`、`反面图片`。
- `库存资金`、`冻结金额`、`邮箱`、`删除时间`。

行按钮和弹窗：

- `一键控盈利`、`一键控输`、`一键控正常`：批量控制用户交易结果，强写操作。
- `踢下线`：强制用户退出。
- `银行卡`：查看用户银行卡/钱包。
- `密码`：重置登录密码弹窗。
- `实名`：实名审核弹窗，状态含 `未提交`、`待审核`、`通过`、`拒绝`。
- `登陆`：后台代登录，接口返回 URL 并 `window.open`。
- `发信`：给用户发送内容。
- `备注`：填写用户备注。
- `修改余额`：金额输入，正数增加、负数减少。

适配：

- 本地交易前端只需要消费用户账户摘要和权限状态，不应暴露这些后台控制按钮。
- `余额`、`冻结金额`、`库存资金` 可映射到 `AccountSummary.balance/freeMargin/usedMargin`。
- `实名状态`、`状态`、`交易` 可映射到交易可用性 gating。
- `edit_money`、`yl/ks/zc`、`ht_login` 必须只保留在后台管理，不进入用户端。

### 5.5 用户银行卡 / 钱包

组件：`views/system/memberBank/index.vue`，接口前缀：`system/memberBank`。

字段：

- `姓名/网络 name`。
- `银行卡号/钱包地址 cradnum`。
- `用户ID uid`。
- `银行名称/网络 bank_name`。
- `分行名称/网络 bank_branch`。
- `代码/网络 code`。
- `钱包类型 wallet_type`：字典 `wallet_type`。
- `货币 currency`：字典 `currency`。
- `创建时间`、`更新时间`、`删除时间`。

适配：

- 可对应用户端资金页的收款方式管理。
- 用户端如果实现，应通过独立安全 API 做脱敏显示，不直接复用后台 CRUD。

### 5.6 充值订单

组件：`views/system/memberRecharge/index.vue`，接口前缀：`system/memberRecharge`。

字段：

- `用户uid uid`、`订单号`。
- `数量`、`实到`、`费率`、`手续费`。
- `状态 examine_status`：字典 `examine_status`。
- `凭证 pic`：upload，返回 URL。
- `货币 currency`。
- `充值类型 type`。
- `钱包地址`、`地址网络`。
- `审核时间`、`审核用户ID`、`创建人`。
- `备注 msg`。

按钮：

- 常规 CRUD。
- `审核`：打开审核弹窗，状态选项包含 `待审核`、`通过`、`驳回`。
- `导出`：`system/memberRecharge/export`。

适配：

- 映射到用户端 `DEPOSIT` 资金流水。
- 用户端可查看充值记录和状态，但审核按钮只属于后台。

### 5.7 提现订单

组件：`views/system/memberWithdrawal/index.vue`，接口前缀：`system/memberWithdrawal`。

字段：

- `用户ID uid`、`订单号`。
- `数量`、`到账`、`手续费率`、`手续费`。
- `收款类型 type`：银行卡或数字货币。
- `姓名/网络`、`银行名称/网络`、`分行名称/网络`、`银行代码/网络`、`银行卡号/钱包地址`。
- `收款信息ID bank_id`。
- `审核备注 remark`、`审核状态 examine_status`、`审核用户ID`、`审核时间`。
- `用户提现备注`。

审核弹窗：

- `提现订单审核`。
- 显示提现金额、实际到账、手续费、钱包地址/银行账户、姓名/网络、状态、备注。
- 驳回时必须填写备注。

适配：

- 映射到用户端 `WITHDRAWAL` 流水和提现申请状态。
- 用户端只能提交申请和查看状态；审核入口保留后台。

### 5.8 订单管理 / 挂单和成交

组件：`views/system/gd/index.vue`，接口前缀：`system/gd`。

接口：

- `system/gd/index` GET。
- `system/gd/save` POST。
- `system/gd/update/:id` PUT。
- `system/gd/cj` POST：成交。
- `system/gd/pc` POST：平仓。
- `system/gd/cd` POST：撤单。
- `system/gd/export` POST。
- `system/gd/changeStatus` PUT。

字段：

- `UID uid`。
- `产品 code`。
- `手数 shoushu`。
- `倍数 beishu`。
- `开仓价格 price`。
- `保证金 bzj`。
- `手续费 sxf`。
- `方向 fx`：字典 `fx`。
- `状态 status`：字典 `gd`。
- `开仓时间 created_at`、`完成时间 wc_time`。
- `盈亏 yk_num`。
- `止盈 zy`。
- `类型 type`。

行按钮和弹窗：

- `平仓`：弹窗含待审核/通过/驳回、开仓价格、平仓价格。
- `挂单成交`：弹窗含待审核/通过/驳回、挂单价格、成交价格。
- `撤销`：弹窗含待审核/通过/驳回、挂单价格。

适配：

- 后台 `gd` 可以映射本地 `OrderResponse` 和 `PositionResponse`。
- `status` 需要建立映射：后台中文/数字状态 -> 本地 `NEW`、`PARTIALLY_FILLED`、`FILLED`、`CANCELED`、`REJECTED`、`OPEN`、`CLOSED`。
- `pc/cj/cd` 属于后台强制操作，用户端只保留合法的下单/平仓/撤单能力。

### 5.9 持仓 / 仓位列表

组件：`views/system/position/index.vue`，接口前缀：`system/position`。

字段：

- `用户ID uid`、`订单号 order_sn`。
- `产品ID pid`、`产品名称 p_name`。
- `方向`。
- `手数 hand_count`。
- `下单价 buy_price`、`当前价 new_price`。
- `止盈 stop_surplus`、`止损 stop_loss`。
- `手续费 sxf`、`手续费率 sxf_rate`。
- `倍数 multiple`。
- `收益 sy`、`盈亏`。
- `挂单ID gd_id`。
- `平仓价格 closing_price`、`平仓时间 closing_time`。
- `状态`。

支持：

- CRUD、状态变更、数值操作、导入、下载模板、导出。

适配：

- 比 `system/gd` 更接近本地 `positions` 数据。
- 本地 `BottomAccountPanel` 的 `当前仓位`、`历史仓位` 应优先从该资源或由后端 BFF 聚合得到。

### 5.10 余额日志 / 资金流水

组件：`views/system/balanceLog/index.vue`。

字段：

- `用户ID`。
- `变化数量`、`变化前数量`、`变化后数量`。
- `收/支`。
- `交易类型`。
- `标题`、`备注`。
- `创建时间`、`更新时间`、`删除时间`。

适配：

- 直接映射本地 `LedgerEntry`：`entryType`、`amount`、`balanceAfter`、`description`、`createdAt`。
- 当前本地 `LedgerGrid` 已支持 `Type/Amount/Balance/Currency/Time`，缺少中文交易类型映射。

### 5.11 验证码发送记录

组件：`views/system/sendCode/index.vue`，接口：`system/sendCode/index` GET。

字段：

- `验证码`。
- `账号`。
- `验证码内容`。
- `创建时间`、`更新时间`、`删除时间`。

适配：

- 管理后台审计页，不建议进入用户交易界面。
- 可用于后台排查登录/提现/实名验证问题。

### 5.12 会员消息、会员通知、会员持仓控制

会员消息：`views/system/memberMsg/index.vue`，接口前缀 `system/memberMsg`。

- 字段：`用户ID`、`内容`（editor）、`创建人`、`创建时间`、`更新时间`。
- 功能：给指定会员维护消息内容。

会员通知：`views/system/memberNotice/index.vue`，接口前缀 `system/memberNotice`。

- 字段：`排序`、`内容`、`标题`、`状态`、`创建时间`、`更新时间`。
- 功能：会员端通知公告配置。

会员持仓控制：`views/system/memberPosition/index.vue`，接口前缀 `system/memberPosition`。

- 字段：`用户ID`、`产品ID`、`买入价格`、`平仓时间`、`卖出价格`、`买入数量`、`委托方式`、`方向`、`单控`。
- 批量按钮：`一键控输`、`一键控盈利`、`一键控正常`。
- 这些是高风险后台风控/控制功能，不应进入用户端。

## 6. 财务、公告、新闻和支付配置

### 6.1 收款配置 / 支付方式

组件：`views/system/pays/index.vue`，接口前缀：`system/pays`。

字段：

- `名称`、`排序`、`状态`、`类型`。
- `卡号/钱包地址`。
- `swift`。
- `收款人姓名`。
- `银行地址`。
- `网络/货币`。

用途：

- 后台配置平台收款账户或钱包地址。

适配：

- 用户端充值页可以只读展示当前可用收款方式。
- 不应暴露新增/编辑/删除。

### 6.2 公告列表

组件：`views/system/notice/index.vue`，接口前缀：`system/notice`。

字段：

- `公告标题`。
- `公告类型`。
- `接收用户 users`：用户选择器；不选择则默认所有用户。
- `公告内容`。
- `备注`。
- `创建时间`。

适配：

- 映射到用户端公告中心、交易页通知栏或系统消息。
- 用户端只读消费 `notice/index` 的已发布内容。

### 6.3 新闻列表

组件：`views/system/article/index.vue`，接口前缀：`system/article`。

字段：

- `标题`、`简介`、`图片`、`链接`、`排序`、`状态`、`创建时间`、`更新时间`。

适配：

- 可作为本地交易端资讯流或公告栏。
- 需要后端过滤状态、排序、语言。

## 7. 权限和系统管理页面

### 7.1 后台用户

组件：`views/system/user/index.vue`，服务：`assets/user-f8d597d4.js`。

字段和功能：

- `账户`、`所属部门`、`密码`、`昵称`、`角色`、`手机`、`岗位`、`邮箱`、`状态`、`备注`、`注册时间`。
- 部门搜索、更新缓存、设置首页、重置密码、更多操作。
- 接口含 `system/user/index/save/update/delete/recovery/realDelete/changeStatus/clearCache/setHomePage/initUserPassword/updateInfo/modifyPassword`。

用途：

- 管理后台操作员，不是交易会员。

### 7.2 角色

组件：`views/system/role/index.vue`，服务：`assets/role-a0160132.js`。

字段：

- `角色名称`、`角色标识`、`排序`、`状态`、`备注`、`创建时间`。

子功能：

- `菜单权限`：配置角色可访问菜单。
- `数据权限`：全部、自定义、本部门、本部门及以下、本人等数据边界。

特殊规则：

- 超级管理员角色不能禁用、不能修改、不能删除。

### 7.3 菜单

组件：`views/system/menu/index.vue`，服务：`assets/menu-f0b333a8.js`。

字段：

- `菜单类型`：菜单、按钮、外链。
- `上级菜单`、`菜单名称`、`图标`、`菜单标识`、`路由地址`、`视图组件`、`重定向`、`排序`、`隐藏`、`状态`、`生成按钮`、`备注`。

用途：

- 控制左侧菜单和权限按钮生成。

### 7.4 部门和岗位

部门：`views/system/dept/index.vue`。

- 字段：`上级部门`、`部门名称`、`负责人`、`手机`、`排序`、`状态`、`备注`、`创建时间`。
- 子页：部门领导列表，可新增领导。

岗位：`views/system/post/index.vue`。

- 字段：`岗位名称`、`岗位标识`、`排序`、`状态`、`备注`、`创建时间`。

### 7.5 字典

字典：`views/system/dict/index.vue`。

- 字段：`字典名称`、`字典标识`、`状态`、`备注`、`创建时间`。

字典数据：`views/system/dict/dataList.vue`。

- 字段：`字典标签`、`字典键值`、`排序`、`状态`、`备注`、`创建时间`。

适配影响：

- 交易状态、方向、钱包类型、货币、风控状态等选项都应由字典驱动。
- 本地项目不应硬编码后台状态文案；需要一层状态映射表。

## 8. 系统设置、监控和开发工具

系统配置：`views/setting/config/index.vue`。

- 配置组：组名称、组标识、备注。
- 配置项：所属组、配置标题、配置标识、配置值、排序、输入组件、配置说明、选择/默认数据。
- 输入组件类型：文本框、文本域、下拉选择框、单选框、复选框、开关、图片上传、键值对、富文本编辑器。

定时任务：`views/setting/crontab/index.vue`。

- 字段：任务名称、任务类型、定时规则、调用目标、任务参数、单次执行、状态、备注、创建时间。
- 操作：立即执行一次、日志。

数据源：`views/setting/datasource/index.vue`。

- 字段：数据源名称、DSN、数据库地址、数据库名称、数据库用户、数据库密码、备注。
- 操作：测试连接。

代码生成：`views/setting/code/index.vue`。

- 功能：装载数据表、预览、同步、生成代码、编辑生成信息。
- 支持组件类型很多：输入框、密码框、文本域、数字输入框、标签输入框、开关、滑块、下拉、树下拉、单选、复选、日期、时间、评分、级联、上传、资源选择器、富文本、代码编辑器等。

监控：

- 缓存：缓存键名、内存占用、版本、连接数、端口、key 统计、查看/删除/清空。
- 在线用户：用户账户、昵称、登录 IP、登录时间、强制退出。
- 服务器：CPU、内存、OS、版本、物理路径、启动时间、磁盘。

日志：

- 操作日志：请求路由、操作用户、请求方法、响应代码、业务名称、IP、地点、请求/响应数据。
- 登录日志：用户账户、昵称、IP、登录时间。
- 接口日志/API 日志：URL、method、IP、参数、响应、用户。
- 队列日志：交换机、路由、队列、延迟、生产/消费状态、队列数据。

接口文档：`/mineDoc`。

- 登录文档，APP ID / APP SECRET。
- 接口列表、代码释义、签名算法。
- 全局 Query、Body、Header 参数。
- 模拟请求和服务器响应。

## 9. 发现的视图组件清单

以下是静态包中发现的所有 `views/...vue` 页面或子页面映射：

- `views/dashboard/components/components/st-announced.vue` -> `assets/st-announced-749ea00e.js`
- `views/dashboard/components/components/st-count.vue` -> `assets/st-count-3bf0cde5.js`
- `views/dashboard/components/components/st-loginChart.vue` -> `assets/st-loginChart-b9e4802f.js`
- `views/dashboard/components/components/st-mineadmin.vue` -> `assets/st-mineadmin-b3ddc49e.js`
- `views/dashboard/components/components/st-welcome.vue` -> `assets/st-welcome-f70a0805.js`
- `views/dashboard/components/statistics.vue` -> `assets/statistics-1ee9d932.js`
- `views/dashboard/components/work-panel.vue` -> `assets/work-panel-5ebab4b0.js`
- `views/dashboard/index.vue` -> `assets/index-a5543298.js`
- `views/login.vue` -> `assets/login-6e6977c9.js`
- `views/mineDoc/components/auth.vue` -> `assets/auth-084fa1b5.js`
- `views/mineDoc/components/docMain.vue` -> `assets/docMain-4c115f8b.js`
- `views/mineDoc/index.vue` -> `assets/index-5b5ce7d4.js`
- `views/mineDoc/page/components/globalParams.vue` -> `assets/globalParams-2efc76e0.js`
- `views/mineDoc/page/components/simRequest.vue` -> `assets/simRequest-d90dc9e7.js`
- `views/mineDoc/page/components/sliderDrawer.vue` -> `assets/sliderDrawer-a9a4ca16.js`
- `views/mineDoc/page/interfaceCode.vue` -> `assets/interfaceCode-e4d68ded.js`
- `views/mineDoc/page/interfaceList.vue` -> `assets/interfaceList-8c7f8dab.js`
- `views/mineDoc/page/signature.vue` -> `assets/signature-50c2c1c0.js`
- `views/setting/code/components/editInfo.vue` -> `assets/editInfo-99d7ccef.js`
- `views/setting/code/components/loadTable.vue` -> `assets/loadTable-b04b985b.js`
- `views/setting/code/components/preview.vue` -> `assets/preview-d80c2274.js`
- `views/setting/code/components/settingComponent.vue` -> `assets/settingComponent-091124aa.js`
- `views/setting/code/index.vue` -> `assets/index-d7b5b2f7.js`
- `views/setting/config/components/addConfig.vue` -> `assets/addConfig-cfebdbc4.js`
- `views/setting/config/components/addGroup.vue` -> `assets/addGroup-cbaa817a.js`
- `views/setting/config/components/manageConfig.vue` -> `assets/manageConfig-40b086b2.js`
- `views/setting/config/index.vue` -> `assets/index-e03e0fef.js`
- `views/setting/crontab/index.vue` -> `assets/index-36d8a74e.js`
- `views/setting/crontab/logList.vue` -> `assets/logList-f1d0870a.js`
- `views/setting/datasource/index.vue` -> `assets/index-61fc4faf.js`
- `views/setting/module/index.vue` -> `assets/index-718a42af.js`
- `views/setting/systemInterface/index.vue` -> `assets/index-96f79f8f.js`
- `views/system/api/index.vue` -> `assets/index-abc3c064.js`
- `views/system/api/paramsList.vue` -> `assets/paramsList-df33be81.js`
- `views/system/apiGroup/index.vue` -> `assets/index-44803825.js`
- `views/system/apiLogs/index.vue` -> `assets/index-dc429daf.js`
- `views/system/app/bind.vue` -> `assets/bind-5ddc5500.js`
- `views/system/app/index.vue` -> `assets/index-8e7588bc.js`
- `views/system/appGroup/index.vue` -> `assets/index-69cfccd9.js`
- `views/system/article/index.vue` -> `assets/index-60f3a688.js`
- `views/system/attachment/index.vue` -> `assets/index-48bbec3a.js`
- `views/system/balanceLog/index.vue` -> `assets/index-4038bc95.js`
- `views/system/dataMaintain/index.vue` -> `assets/index-8c9d70f1.js`
- `views/system/dept/index.vue` -> `assets/index-0309bce1.js`
- `views/system/dept/leader.vue` -> `assets/leader-0d737b4a.js`
- `views/system/dict/dataList.vue` -> `assets/dataList-c284b73d.js`
- `views/system/dict/index.vue` -> `assets/index-104a160e.js`
- `views/system/gd/cd.vue` -> `assets/cd-314e13fc.js`
- `views/system/gd/cj.vue` -> `assets/cj-9f9ee3d3.js`
- `views/system/gd/index.vue` -> `assets/index-98b1574e.js`
- `views/system/gd/pc.vue` -> `assets/pc-4ad09a17.js`
- `views/system/logs/apiLog.vue` -> `assets/apiLog-11f3223e.js`
- `views/system/logs/loginLog.vue` -> `assets/loginLog-656ca036.js`
- `views/system/logs/operLog.vue` -> `assets/operLog-aee55bea.js`
- `views/system/logs/queueLog.vue` -> `assets/queueLog-a95dd1ef.js`
- `views/system/member/bank.vue` -> `assets/bank-8ad0b34e.js`
- `views/system/member/bz.vue` -> `assets/bz-62e46c24.js`
- `views/system/member/edit_money.vue` -> `assets/edit_money-08351392.js`
- `views/system/member/edit_pwd.vue` -> `assets/edit_pwd-37cd1af6.js`
- `views/system/member/fstz.vue` -> `assets/fstz-19a5106d.js`
- `views/system/member/index copy.vue` -> `assets/index copy-9face7de.js`
- `views/system/member/index.vue` -> `assets/index-153f3351.js`
- `views/system/member/realname.vue` -> `assets/realname-4dfa0f81.js`
- `views/system/memberBank/index.vue` -> `assets/index-57085137.js`
- `views/system/memberMsg/index.vue` -> `assets/index-d1f01cd4.js`
- `views/system/memberNotice/index.vue` -> `assets/index-94ba2bea.js`
- `views/system/memberPosition/index.vue` -> `assets/index-9ccba439.js`
- `views/system/memberRecharge/edit_statuss.vue` -> `assets/edit_statuss-c3da3ed1.js`
- `views/system/memberRecharge/index.vue` -> `assets/index-98fc92b3.js`
- `views/system/memberRecharge/shenhe.vue` -> `assets/shenhe-d92e78d2.js`
- `views/system/memberWithdrawal/edit_statuss.vue` -> `assets/edit_statuss-534e74b2.js`
- `views/system/memberWithdrawal/index.vue` -> `assets/index-39f580f0.js`
- `views/system/menu/index.vue` -> `assets/index-88b91e57.js`
- `views/system/monitor/cache/index.vue` -> `assets/index-02bcabd7.js`
- `views/system/monitor/onlineUser/index.vue` -> `assets/index-f6b0adda.js`
- `views/system/monitor/server/index.vue` -> `assets/index-5324d9c4.js`
- `views/system/notice/index.vue` -> `assets/index-fb849697.js`
- `views/system/pays/index.vue` -> `assets/index-4409c284.js`
- `views/system/position/index.vue` -> `assets/index-4dcd754d.js`
- `views/system/post/index.vue` -> `assets/index-ab6d171f.js`
- `views/system/proKline/index.vue` -> `assets/index-ac0997ab.js`
- `views/system/product/edit_fk.vue` -> `assets/edit_fk-2f0b12dd.js`
- `views/system/product/edit_kline.vue` -> `assets/edit_kline-245f93d2.js`
- `views/system/product/index.vue` -> `assets/index-c0341ddf.js`
- `views/system/product/kline_list.vue` -> `assets/kline_list-d2d53604.js`
- `views/system/productCate/index.vue` -> `assets/index-4e271798.js`
- `views/system/role/components/dataPermission.vue` -> `assets/dataPermission-e3bd202a.js`
- `views/system/role/components/menuPermission.vue` -> `assets/menuPermission-dcbf7679.js`
- `views/system/role/index.vue` -> `assets/index-35e4b877.js`
- `views/system/sendCode/index.vue` -> `assets/index-8bd3520e.js`
- `views/system/user/index.vue` -> `assets/index-db676477.js`
- `views/userCenter/components/modifyPassword.vue` -> `assets/modifyPassword-fc069cee.js`
- `views/userCenter/components/userInfomation.vue` -> `assets/userInfomation-163f74a6.js`
- `views/userCenter/index.vue` -> `assets/index-91f02394.js`
- `views/userCenter/message.vue` -> `assets/message-7e4d7251.js`

## 10. 与本地 `fx-trading-platform/apps/web` 的适配建议

当前本地项目关键文件：

- `src/pages/trading/TradingPage.tsx`：新的专业交易页集成点。
- `src/pages/trading/tradingMarketApi.ts`：市场列表接口。
- `src/pages/trading/tradingMarketAdapters.ts`：市场数据适配。
- `src/pages/trading/components/KLineChartPanel.tsx`：KLineCharts 图表。
- `src/features/trading/components/TradePanel.tsx`：右侧交易面板。
- `src/features/trading/types/order.ts`：交易表单模型。
- `src/features/trading/services/orderAdapter.ts`：表单到后端订单载荷映射。
- `src/features/trading-session/tradingSession.ts`：账户、订单、持仓、流水聚合。
- `src/pages/trading/components/BottomAccountPanel.tsx`：底部委托/仓位/资产/策略标签。
- `src/services/tradingApi.ts`：旧 `/api/trading/orders`、`/api/trading/positions` 交易接口。

### 10.1 不建议直接接后台 `system/...` 到用户端

原因：

- 后台接口包含大量高风险能力：改余额、控输赢、代登录、审核充值提现、强制平仓、强制成交、撤单、改 K 线、风控价格。
- 用户端如果直接调用这些接口，权限边界会非常危险。
- 后台状态和字段命名偏管理端，需要后端 BFF 做清洗、脱敏、状态归一。

推荐架构：

- 后台系统继续作为管理端。
- 本地交易前端只调用本地或自建 BFF 的 `/api/...`。
- BFF 内部只读或受控调用后台 `system/...`，输出用户端专用 DTO。

### 10.2 产品和行情适配

后台来源：

- `system/product/index`
- `system/productCate/list`
- `system/proKline/index`

本地落点：

- `fetchMarketSymbols()` 从后台产品列表转换 `TradingMarket[]`。
- `tradingMarketAdapters.ts` 把 `name/code/code2/price/price_jd/beishu/beishu_moren/rate/status` 映射到本地 market。
- `KLineChartPanel` 只吃标准 candle，不直接感知后台 `set_kline`。

建议 DTO：

```ts
type BackofficeProductMarket = {
  id: number
  name: string
  code: string
  code2?: string
  categoryId?: number
  price: string
  pricePrecision: number
  leverageOptions: number[]
  defaultLeverage: number
  feeRate: string
  status: 'enabled' | 'disabled'
}
```

### 10.3 下单、委托和持仓适配

后台来源：

- `system/gd/index`：挂单/订单管理。
- `system/position/index`：持仓/平仓数据。

本地落点：

- `OrderResponse[]`：当前委托、历史委托。
- `PositionResponse[]`：当前仓位、历史仓位。
- `BottomAccountPanel` 已有四个对应标签，可直接承接。

需要新增状态映射：

- 后台方向字典 `fx` -> `BUY` / `SELL`。
- 后台订单状态字典 `gd` -> `NEW` / `PARTIALLY_FILLED` / `FILLED` / `CANCELED` / `REJECTED`。
- 后台持仓状态 -> `OPEN` / `CLOSED`。

需要注意：

- 后台 `pc/cj/cd` 是管理端强制平仓/成交/撤单，不等同用户端下单。
- 用户端 `TradePanel` 应继续走 `onSubmitOrder -> /api/trading/orders`，由 BFF 决定如何调用真实交易后端。

### 10.4 资金适配

后台来源：

- `system/memberRecharge/index`
- `system/memberWithdrawal/index`
- `system/balanceLog/index`
- `system/memberBank/index`
- `system/pays/index`

本地落点：

- `LedgerEntry[]`：充值、提现、盈亏、手续费、余额调整。
- `AccountSummary`：余额、权益、已用保证金、可用保证金、杠杆。
- 未来资金页：银行卡/钱包、充值方式、充值记录、提现记录。

用户端只读/有限写入原则：

- 用户可以发起充值/提现申请，但不能审核。
- 用户可以维护自己的钱包/银行卡，但应走脱敏、实名、二次验证流程。
- 后台 `edit_money`、审核接口、平台收款配置不进入用户端。

### 10.5 消息、公告、新闻适配

后台来源：

- `system/queueMessage/receiveList`
- `system/notice/index`
- `system/article/index`
- `system/memberNotice/index`
- `system/memberMsg/index`

本地落点：

- 交易页顶部通知、消息中心、公告弹窗、资讯栏。
- 消息状态可以支持未读/已读，但删除、发送私信需谨慎。

### 10.6 用户账户适配

后台来源：

- `system/member/index`
- `system/member/edit_realname`
- `system/memberBank/index`

本地落点：

- 登录后的账户状态、实名状态、交易可用性、余额展示。

禁止进入用户端：

- `yl/ks/zc` 控输赢。
- `ht_login` 后台代登录。
- `edit_money` 改余额。
- `xiaxian` 踢下线。
- 管理员备注、风控字段直接展示。

## 11. 建议实施顺序

1. 先保留当前本地 `/api/...` 接口，不直接替换为后台 `system/...`。
2. 在后端或 BFF 层新增只读适配器：
   - `GET /api/market/symbols` -> 后台 `system/product/index`
   - `GET /api/market/candles` -> 行情源或后台 K 线聚合
   - `GET /api/trading/orders` -> 后台 `system/gd/index`
   - `GET /api/trading/positions` -> 后台 `system/position/index`
   - `GET /api/ledger` -> 后台充值、提现、余额日志聚合
3. 在前端新增状态映射测试：
   - 后台方向/状态/审核状态 -> 本地枚举。
   - 金额和价格精度格式化。
4. 更新 `tradingMarketApi.ts`、`tradingMarketAdapters.ts`，让产品列表驱动 `MarketSidebar`、`SymbolHeader` 和 `TradePanel`。
5. 更新 `tradingSession.ts` 聚合订单、持仓、流水。
6. 最后才考虑用户端资金页和通知中心。

## 12. 仍需人工或受控登录验证的缺口

以下信息静态包不能完全确认：

- 后台动态菜单真实路由 path 与用户角色权限差异。
- 字典真实选项值，例如 `fx`、`gd`、`wallet_type`、`currency`、`examine_status`、`kong`、`is_ty`。
- 各列表接口返回数据结构、分页字段、状态数字含义。
- 某些空 chunk 或无中文 chunk 的页面，如部分模块管理、系统接口页面，需登录后进入页面确认。
- 后台是否有 CORS、鉴权 token、租户或签名限制。

要补齐这些缺口，推荐下一轮只做读取接口验证：登录后仅调用 GET/list/read 类接口，导出菜单 JSON 和字典 JSON，不触发任何 POST/PUT/DELETE。
