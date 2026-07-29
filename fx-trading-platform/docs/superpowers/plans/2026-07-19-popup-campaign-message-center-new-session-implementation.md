# 定向弹窗活动与消息中心：新会话实施流程

> 面向新的 Codex 会话。本文件既是冻结后的产品规格，也是按顺序执行的实施计划。新会话应从 Task 0 开始持续执行到严格完成条件全部满足，不重新发起需求访谈，不只生成另一份计划。

**状态：** 产品决策已确认，可实施
**日期：** 2026-07-19
**工作目录：** C:\workspace\tradingWeb
**业务主体：** fx-trading-platform
**技术栈：** Java 21、Spring Boot、MyBatis-Plus、Flyway、PostgreSQL、Redis、React 19、TypeScript、Vite

## 新会话启动指令

把下面整段作为新会话的第一条任务指令：

~~~text
在 C:\workspace\tradingWeb 工作，完整实施“定向弹窗活动与消息中心”目标，持续执行到严格完成条件全部满足，不要只重新分析或生成计划。

开始前必须完整读取：
1. C:\workspace\tradingWeb\AGENTS.md
2. C:\workspace\tradingWeb\fx-trading-platform\docs\superpowers\plans\2026-07-19-popup-campaign-message-center-new-session-implementation.md

当前 worktree 可能包含大量其他任务的未提交改动。它们都是执行基线，不得 reset、checkout、stash、覆盖或删除。先执行本计划的 Worktree Reconciliation Protocol；遇到重叠文件时先读 diff、调用链和测试，再在当前内容上做语义合并。不要因为 worktree dirty 而停止，也不要把无关改动纳入本任务。

严格按 Task 0 到 Task 12 顺序执行。每个 Task 必须先写能复现缺口的失败测试并观察 RED，再做最小完整实现，最后运行该 Task 的验证命令。API 变更必须同时检查 backend、apps/admin、apps/web、packages/frontend-core 和生成合同。数据库只使用 Flyway 与 MyBatis-Plus，不引入 JPA。所有 scheduler Bean 在默认和测试配置中必须关闭，仅由明确生产配置开启。所有 /api/admin/** 接口必须同时保留 ADMIN 身份检查与细分权限检查。

不要连接真实 broker、FIX、LP 或其他生产交易基础设施。不要删除测试、放宽断言或伪造成功结果。不要自动 push 或创建 PR；除非用户在新会话中明确要求，也不要创建分支或提交。每完成一个 Task 就更新本文档 checkbox，并继续下一个 Task，不等待人工确认。只有遇到无法从仓库发现、且会实质改变已冻结产品合同的外部决策时才询问用户。

最终必须输出：完成的 Task、主要文件、数据库迁移、API 合同、所有实际测试命令与结果、视觉验收产物、仍存在的范围外用户改动。所有严格完成条件未满足前不得宣称完成。
~~~

## 目标结果

一次性交付完整闭环：

1. 后台创建、编辑、预览、测试、立即或定时发布弹窗活动。
2. 支持全部业务用户，或搜索并多选指定业务用户。
3. 支持有效期、活动时区、总次数、每日次数、最小间隔、页面、终端、优先级和连续弹窗上限。
4. 用户端 PC、移动端均能领取、展示、关闭、不再提醒、点击业务按钮。
5. 多活动按确定性顺序连续展示，普通关闭继续下一条，业务按钮终止本次队列。
6. 支持弹窗内容同步到完整消息中心。
7. 后台普通消息支持全部或多选用户、立即或定时发送，但不自动弹窗。
8. PC 提供通知铃铛和最近消息，PC/移动端均提供独立消息中心页面。
9. 支持已读、未读、全部已读、个人隐藏、全局逻辑删除、恢复和实时未读提醒。
10. 支持汇总统计和受权限控制的用户级投放明细，暂不提供导出。
11. 所有内容修改、投放、读取、关闭、点击、删除和高权限查看均可审计。

## 全局约束

- 当前 worktree 是基线；保留所有用户改动和其他任务改动。
- 不得运行 git reset、git checkout、git clean、git stash 或等价破坏性命令。
- 不得删除现有 content.messages 数据来简化迁移。
- 不得将弹窗活动继续塞入现有单行 target_user_id 模型。
- 不得把 richtext 字段继续渲染成 textarea 并称为富文本编辑器。
- 不得在前端信任后台 HTML；必须由后端白名单清洗，前端仍执行安全渲染。
- 不得让客户端提交或覆盖 userId；用户身份只能来自认证 Principal。
- WebSocket 只发送“数据可能有变化”的唤醒事件，不携带正文或作为权威状态。
- 数据库和 HTTP 查询是最终权威；实时通知失败不能破坏正确性。
- 定时任务默认关闭；即使调度器未运行，领取和消息查询也必须通过时间条件保证不会提前或过期展示。
- 所有写操作必须幂等或具备明确的防重复语义。
- API 改动必须更新 OpenAPI/共享类型，并同时验证 apps/admin 与 apps/web。
- 生产富文本不得允许 script、iframe、内联事件、任意 CSS、任意 HTML、Base64 图片或外部图片 URL。
- 业务按钮只允许一个，并且只允许内部 routeKey 与经过校验的参数。
- 不实现本计划“明确不在范围”中的能力。

## Worktree Reconciliation Protocol

Task 0 和每次会话恢复时都执行：

- [x] 运行 git status --short。
- [x] 运行 git diff --name-status 与 git diff --cached --name-status。
- [x] 运行 git log -20 --oneline，判断是否已有本计划的部分工作。
- [x] 对本计划会修改且当前已有 diff 的文件逐个执行 git diff -- 文件路径。
- [x] 用 rg 检查调用方、测试和生成合同，不能只阅读单个文件。
- [x] 如果已有实现满足某个步骤，运行该步骤的测试证明后勾选，不重复重写。
- [x] 如果已有实现不完整，从第一个失败验收点继续。
- [x] 只修改本任务需要的 hunk；不得格式化或重写无关区域。
- [x] 不暂存、提交或删除范围外文件。

重点重叠风险文件当前可能已被其他任务修改：

- backend/src/main/java/com/fxplatform/admin/service/AdminPermissionCatalog.java
- backend/src/main/java/com/fxplatform/common/security/**
- backend/src/main/resources/application.yml
- backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java
- apps/web/src/app/AppShell.tsx
- apps/web/src/app/App.test.ts
- apps/web/src/styles.css
- packages/frontend-core/**
- packages/shared-types/src/generated/openapi.ts
- scripts/verify-architecture.mjs
- package.json

## 冻结的产品合同

### 活动与受众

- PopupCampaign 是独立活动，不是普通消息的一种样式。
- audienceType 仅支持 ALL 与 SELECTED。
- ALL 包含所有 role=USER 的业务用户，排除 role=ADMIN；活动有效期内注册的新用户也属于目标范围。
- SELECTED 通过用户 ID、邮箱或手机号搜索并多选，允许选择 ACTIVE、FROZEN、DISABLED 任意状态业务用户。
- SELECTED 名单在首次发布时冻结。
- 已发布活动不能修改受众类型或名单。
- 暂不支持 CSV、粘贴名单和动态人群规则。
- 普通后台消息同样支持 ALL 与 SELECTED；定时普通消息在实际发送时冻结收件人语义。

### 时间与生命周期

- 活动支持立即开始与定时开始。
- 活动必须有 endAt；默认时长 7 天。
- 活动配置 timeZone，默认 Asia/Shanghai。
- 每日次数按活动时区自然日计算。
- 暂停不顺延 endAt；恢复不重置频控。
- 普通消息支持 DRAFT、SCHEDULED、SENT、DELETED。
- 活动支持 DRAFT、SCHEDULED、ACTIVE、PAUSED、ENDED、DELETED。
- 已发布活动允许修改当前内容、优先级、频控、页面、终端和 endAt。
- 已发布活动不能修改受众或 syncToInbox。
- 删除为数据库逻辑删除；恢复后进入 PAUSED，不自动重新投放。

### 频控

- 每个活动必须配置 maxTotalImpressions、maxDailyImpressions、minInterval、startAt、endAt。
- 不允许无限展示。
- 默认：累计 3 次、每天 1 次、最小间隔 4 小时、持续 7 天。
- maxDailyImpressions 必须小于或等于 maxTotalImpressions。
- 资格判断同时满足总次数、每日次数和最小间隔；跨自然日不会绕过最小间隔。
- 编辑内容不重置任何投放状态。
- 管理员可显式重置次数与最近展示时间，但不能清除用户的 optedOutAt。
- PC 与移动端共享同一用户频控。

### 页面、终端与队列

- displayScope 支持 ALL_BUSINESS_PAGES 与 SELECTED_PAGES。
- 页面只能从固定 pageKey 枚举选择，不能手填 URL。
- 登录、注册、找回密码等认证页不展示。
- deviceScope 支持 ALL、PC、MOBILE。
- 同一时刻仅展示一个活动。
- 排序固定为 priority 降序、publishedAt 升序、campaignId 升序。
- 用户关闭第一条后继续展示第二条。
- 全局 maxSequentialPopups 默认 3，由后台设置调整；单个活动不能覆盖。
- 点击业务 CTA 后终止本次队列，剩余活动等待下一次自然触发。
- 交易确认、风控确认等关键业务弹窗优先于活动弹窗。

### 关闭与展示计数

- 右上角关闭、底部关闭、Esc、点击遮罩均是普通关闭。
- 普通关闭记录一次关闭，并继续领取下一条。
- “不再提醒”只影响当前活动，并继续领取下一条。
- 只有前端弹窗实际挂载并确认可见后才计一次 impression。
- 查询到活动、渲染失败、凭证过期、重复确认均不计数。
- 用户立即关闭仍算一次 impression。
- 同一 deliveryToken 只能确认一次。
- 多标签页、多设备并发时只能有一个有效的活动弹窗领取。

### 内容与按钮

- 第一版只有一份 zh-CN 内容，不提供多语言编辑器。
- 使用真正的受限 WYSIWYG 编辑器。
- 支持标题、段落、粗体、斜体、列表、文本颜色、对齐、安全链接、平台图片、撤销和重做。
- 内容保存编辑器 JSON 与后端白名单清洗后的 HTML。
- 不允许后台输入任意 HTML、JavaScript、iframe 或 CSS。
- 封面和正文图片只允许平台上传的 JPG、PNG、WebP 资源。
- 不允许 Base64 图片和任意外部图片 URL。
- 采用固定响应式模板，支持 SMALL、MEDIUM、LARGE 与可选封面图。
- 每个活动最多一个业务按钮。
- CTA 只能选择内部 routeKey 与受校验参数。
- 正在展示的旧修订不热更新；下一次展示才使用最新修订。

### 修改、同步消息与删除

- 后台业务上可覆盖已发布活动内容。
- 底层每次保存都产生不可变 ContentRevision，用于审计。
- PopupCampaign 与同步 MessagePublication 引用同一个 ContentItem。
- 修改当前修订后，后续弹窗和用户历史消息均显示新内容。
- 已经打开的弹窗继续显示领取时的 revisionId。
- 内容修改不重置投放状态和 MessageReceipt.readAt。
- syncToInbox 在首次发布后冻结。
- 活动首次发布或定时生效时建立关联 MessagePublication。
- 弹窗 shown 确认同时将关联消息标记为已读，readSource=POPUP。
- 用户选择“不再提醒”不删除消息。
- 活动正常结束后消息长期保留，不自动过期。
- 管理员逻辑删除活动时，关联消息对所有用户隐藏。
- 暂停或删除活动时，已打开弹窗收到失效唤醒后自动关闭；已发生的 impression 不回滚。
- 恢复活动后消息仍隐藏，重新发布后才重新可见。

### 消息中心

- PC 顶部铃铛显示未读数并预览最近消息。
- PC 与移动端都有独立消息中心页面；移动端点击铃铛直接进入消息页面。
- 支持全部、未读筛选以及分页。
- 支持标记已读、标记未读、全部已读、个人隐藏。
- 用户隐藏是个人级状态，不物理删除，也不等于弹窗“不再提醒”。
- 管理员全局删除是 MessagePublication 逻辑删除，所有用户均不可见。
- 普通后台消息不会自动弹窗，只产生消息中心未读和实时红点。
- 普通消息支持立即发送和定时发送。
- 已发送普通消息可修改内容，用户看到最新内容，但已读状态不重置。
- 已发送普通消息不能修改受众。
- 用户消息不自动过期。

### 发布、权限与统计

- 暂不需要双人审核。
- 暂不需要发布二次输入确认。
- 只有具备 publish 权限的管理员可以发布。
- 支持向当前管理员发送测试弹窗；测试不计频控、统计或消息，不受管理员不属于受众的限制，并显示 PREVIEW 标识。
- 后台提供汇总统计和用户级投放明细。
- 用户级明细查看必须有独立权限并记录审计。
- 汇总与用户最终状态长期保留。
- PopupDelivery 原始明细默认保留 365 天，可由后台全局设置调整。
- 暂不提供 CSV 或其他导出。

## 明确不在范围

- 多语言内容版本。
- CSV、Excel、粘贴名单等批量导入。
- 动态用户分群。
- 外部 URL 或外链图片。
- 一个活动多个业务 CTA。
- 统计或用户明细导出。
- 双人审核和发布二次输入确认。
- 自动接入订单、资金、钱包、KYC、交易和风控业务事件。
- 邮件、短信、Push 等外部通知渠道。

## 仓库现状与迁移起点

执行前必须重新核实以下事实，不能假设文件未被其他任务修改：

- backend/src/main/resources/db/migration/V16__admin_content_config.sql 创建了 content.messages，只有单个 target_user_id。
- backend/src/main/java/com/fxplatform/content/entity/ContentMessageEntity.java 是旧消息实体。
- backend/src/main/java/com/fxplatform/admin/controller/AdminContentController.java 提供旧消息 CRUD。
- backend/src/main/java/com/fxplatform/admin/service/AdminContentCommandService.java 允许已发布消息直接更新和物理删除。
- apps/admin/src/services/adminApi.ts 为 member-notices 直接调用旧消息接口。
- apps/admin/src/pages/FeatureCrudPage.tsx 将 richtext 渲染为 textarea。
- apps/web/src/app/AppShell.tsx 是登录态和全局展示的稳定 seam。
- packages/ui/src/dialog/Dialog.tsx 已有基础 Dialog，但必须验证并补齐焦点陷阱、滚动锁和层级协调。
- backend/src/main/java/com/fxplatform/common/websocket/WebSocketJwtChannelInterceptor.java 当前对用户队列目的地有严格白名单。
- backend/src/main/java/com/fxplatform/common/websocket/MarketWebSocketConfig.java 已配置 STOMP simple broker。
- packages/frontend-core/src/market/marketStream.ts 已有共享 STOMP 客户端，可复用连接，但不能把 engagement 逻辑塞进 market 命名空间。

## 目标 Module 与 seam

建立一个深的 User Engagement Module，建议后端根包：

~~~text
com.fxplatform.engagement
  admin
  application
  domain
  persistence
  web
  websocket
~~~

content.articles 继续留在现有 content Module。旧 content.messages 迁移到新的 MessagePublication 模型后进入兼容只读期。

外部 Interface 应保持小而深：

~~~java
PopupClaim claimNextPopup(
    UUID userId,
    PopupSurface surface,
    UUID queueSessionId
);

PopupOutcomeResult recordPopupOutcome(
    UUID userId,
    String deliveryToken,
    PopupOutcome outcome
);

MessagePage listMessages(UUID userId, MessageQuery query);

void mutateMessageReceipt(
    UUID userId,
    UUID publicationId,
    MessageReceiptCommand command
);
~~~

复杂的受众判断、时间、频控、并发、排序、消息同步和审计全部隐藏在 Module 内部。Controller 不得复制资格判断；前端不得自己计算是否应该展示。

WebSocket 是唤醒 Adapter，不是领域 Interface。测试使用直接调用或内存唤醒 Adapter；生产使用 STOMP Adapter。

## 目标数据模型

实际 Flyway 版本号必须在执行时用 rg 检查下一个未占用版本，不得硬编码覆盖已有迁移。

### content.content_items

- id UUID PK
- current_revision_id UUID
- content_kind VARCHAR
- created_at、updated_at

### content.content_revisions

- id UUID PK
- content_item_id UUID FK
- revision_no INTEGER
- title VARCHAR(200)
- body_document JSONB
- sanitized_html TEXT
- cover_asset_id UUID NULL
- cta_label VARCHAR NULL
- cta_route_key VARCHAR NULL
- cta_params JSONB NULL
- created_by UUID
- created_at
- UNIQUE(content_item_id, revision_no)

### content.content_assets

- id UUID PK
- storage_key、mime_type、byte_size、width、height、sha256
- status
- created_by、created_at、deleted_at

资产只允许在没有任何修订引用后进入物理清理流程。

### content.popup_campaigns

- id UUID PK
- name、content_item_id
- lifecycle_status
- audience_type
- sync_to_inbox
- priority
- display_scope、page_keys JSONB
- device_scope
- template_size
- time_zone
- start_at、end_at
- max_total_impressions
- max_daily_impressions
- min_interval_seconds
- first_published_at、last_published_at
- created_by、updated_by、created_at、updated_at
- paused_at、ended_at、deleted_at

### content.popup_campaign_targets

- campaign_id、user_id
- created_at
- PRIMARY KEY(campaign_id, user_id)

### content.popup_campaign_user_states

- campaign_id、user_id
- total_impressions
- daily_bucket
- daily_impressions
- last_impression_at
- opted_out_at
- last_clicked_at
- active_delivery_id、active_delivery_expires_at
- version
- PRIMARY KEY(campaign_id, user_id)

每日 bucket 必须使用 campaign.timeZone 计算。更新状态时使用数据库锁或 CAS，不能先读后写导致并发超量。

### content.popup_queue_sessions

- id UUID PK
- user_id
- trigger_type
- surface_page_key、device_class
- max_items
- issued_count
- terminated_reason
- created_at、expires_at、terminated_at

max_items 在队列创建时读取全局配置形成快照。CTA 将 session 终止；普通关闭继续领取。

### content.popup_deliveries

- id UUID PK
- queue_session_id
- campaign_id、user_id、revision_id
- token_hash
- status
- page_key、device_class
- issued_at、expires_at
- shown_at、closed_at、close_reason
- clicked_at、invalidated_at
- UNIQUE(token_hash)

明文 token 只返回一次，数据库只保存 hash。shown 确认与用户状态次数更新必须处于同一事务。

### content.message_publications

- id UUID PK
- content_item_id
- source_type MANUAL 或 CAMPAIGN
- source_campaign_id NULL
- audience_type
- lifecycle_status
- category
- scheduled_at、sent_at
- audience_cutoff_at
- created_by、updated_by、created_at、updated_at、deleted_at
- source_campaign_id 非空时保持一对一唯一关系

### content.message_targets

- publication_id、user_id
- PRIMARY KEY(publication_id, user_id)

### content.message_receipts

- publication_id、user_id
- delivered_at
- read_at、read_source
- hidden_at
- updated_at
- PRIMARY KEY(publication_id, user_id)

对于 ALL：

- 活动同步消息的动态受众截止时间为 campaign.endAt。
- 普通手动消息的动态受众截止时间为实际 sentAt。
- 查询时结合 auth.users.created_at 与 audience_cutoff_at，避免活动结束后注册的用户收到旧活动消息。
- receipt 可以按用户首次看到、读或隐藏时懒创建，不能在全站发布事务中同步插入所有用户记录。

### content.engagement_outbox

- id UUID PK
- aggregate_type、aggregate_id
- event_type、payload JSONB
- created_at、published_at、attempt_count、last_error

发布、更新、暂停、删除、恢复、定时生效和普通消息发送都先与领域写入同事务落 Outbox，再由 Adapter 唤醒客户端。

### 旧 content.messages 迁移

- 新建表后回填，首个发布版本不得直接 DROP 旧表。
- 每条旧记录生成 ContentItem、ContentRevision 和 MessagePublication。
- target_user_id 非空映射为 SELECTED 与一条 MessageTarget。
- target_user_id 为空映射为 ALL，audience_cutoff_at 使用原 published_at 或 created_at，防止新注册用户收到历史旧消息。
- DRAFT 映射为 DRAFT；PUBLISHED 映射为 SENT。
- 保留 legacy_message_id 关联或复用稳定 ID，确保后台链接和审计可追踪。
- 切换读写完成后，旧表进入兼容只读；物理移除必须是未来独立迁移。

## 权限目录

新增并由后端强制检查：

~~~text
content:campaign:read
content:campaign:edit
content:campaign:publish
content:campaign:delete
content:campaign:stats
content:campaign:user-detail
content:popup-policy:update
content:message:read
content:message:edit
content:message:send
content:message:delete
~~~

所有权限既要进入 AdminPermissionCatalog/RBAC seed，也要在 Controller 或命令 Interface 上使用 @PreAuthorize。前端隐藏按钮只能改善体验，不能作为安全控制。

## HTTP 合同草案

精确 DTO 以实现时的 OpenAPI 为准，但不得偏离以下语义。

### Admin 活动

~~~text
GET    /api/admin/engagement/campaigns
POST   /api/admin/engagement/campaigns
GET    /api/admin/engagement/campaigns/{id}
PUT    /api/admin/engagement/campaigns/{id}
POST   /api/admin/engagement/campaigns/{id}/publish
POST   /api/admin/engagement/campaigns/{id}/pause
POST   /api/admin/engagement/campaigns/{id}/resume
POST   /api/admin/engagement/campaigns/{id}/end
DELETE /api/admin/engagement/campaigns/{id}
POST   /api/admin/engagement/campaigns/{id}/restore
POST   /api/admin/engagement/campaigns/{id}/reset-delivery
POST   /api/admin/engagement/campaigns/{id}/test-popup
GET    /api/admin/engagement/campaigns/{id}/stats
GET    /api/admin/engagement/campaigns/{id}/users
~~~

reset-delivery 只能重置次数、daily bucket 和 lastImpressionAt，不能清除 optedOutAt。

### Admin 普通消息

~~~text
GET    /api/admin/engagement/messages
POST   /api/admin/engagement/messages
GET    /api/admin/engagement/messages/{id}
PUT    /api/admin/engagement/messages/{id}
POST   /api/admin/engagement/messages/{id}/send
POST   /api/admin/engagement/messages/{id}/cancel-schedule
DELETE /api/admin/engagement/messages/{id}
POST   /api/admin/engagement/messages/{id}/restore
~~~

### Admin 用户搜索与全局策略

~~~text
GET /api/admin/users/search?q=&page=&size=
GET /api/admin/engagement/popup-policy
PUT /api/admin/engagement/popup-policy
~~~

用户搜索响应只返回多选所需字段，不返回密码、安全凭证或非必要敏感资料。

### 用户弹窗

~~~text
POST /api/me/engagement/popup-queues
POST /api/me/engagement/popup-queues/{sessionId}/next
POST /api/me/engagement/popup-deliveries/{deliveryToken}/shown
POST /api/me/engagement/popup-deliveries/{deliveryToken}/close
POST /api/me/engagement/popup-deliveries/{deliveryToken}/opt-out
POST /api/me/engagement/popup-deliveries/{deliveryToken}/click
~~~

所有接口从 Principal 取 userId。shown、close、opt-out、click 必须幂等；token 必须绑定用户、活动和过期时间。

### 用户消息中心

~~~text
GET  /api/me/messages
GET  /api/me/messages/unread-count
POST /api/me/messages/{publicationId}/read
POST /api/me/messages/{publicationId}/unread
POST /api/me/messages/read-all
POST /api/me/messages/{publicationId}/hide
~~~

消息列表默认 created/sent 时间倒序，过滤逻辑删除、用户隐藏和未到发送时间的消息。

## WebSocket 合同

建议目的地：

~~~text
/topic/engagement/updates
/user/queue/engagement-updates
~~~

- ALL 活动或消息使用仅认证用户可订阅的 topic 唤醒。
- SELECTED 使用用户队列。
- payload 只包含 updateType、aggregateId、occurredAt，不含正文。
- WebSocketJwtChannelInterceptor 必须显式允许经过认证的 engagement 目的地。
- 客户端收到事件后重新调用领取、未读数或消息列表接口。
- 暂停、删除和内容更新均发送 invalidation/update 唤醒。
- WebSocket 丢失时，登录、路由变化、窗口重新获得焦点和消息页面刷新仍能恢复正确状态。

## Task 0：建立基线、冻结合同测试和文件所有权

- [x] 执行 Worktree Reconciliation Protocol。
- [x] 用 rg 找出所有 Flyway 版本，确定下一个未使用版本。
- [x] 运行当前 backend、admin、web、ui、frontend-core 与 architecture 基线测试并保存实际结果。
- [x] 新增计划级静态合同测试，证明下列事实尚未实现：
  - 没有用户消息中心 API。
  - richtext 仍是 textarea。
  - 没有 PopupCampaign 模型。
  - WebSocket 没有 engagement 目的地。
- [x] 记录所有基线失败；只允许与本任务无关且已有证据的失败继续存在，不能把新失败归因于 dirty tree。

基线命令：

~~~powershell
cd C:\workspace\tradingWeb\fx-trading-platform\backend
mvn test

cd C:\workspace\tradingWeb
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/ui run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/frontend-core run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
~~~

Task 0 完成条件：

- [x] 已保存基线结果。
- [x] 已识别所有重叠文件。
- [x] 没有修改或删除无关工作。

### Task 0 执行记录（2026-07-19）

- Worktree 基线：`git status --short` 439 项（171 M、11 D、257 ??）；`git diff --name-status` 176 项；`git diff --cached --name-status` 0 项。最近 20 个提交及全历史检索均无 popup/engagement/message-center 实现。
- Flyway 基线：当前 worktree 的 `V1..V62` 连续、无重复、无缺号；`V53..V62` 属于现有未跟踪改动，因此本计划下一个版本为 `V63`。
- 高风险重叠：计划列出的文件中有 13 个已跟踪 diff，并另有未跟踪 `apps/web/src/app/ApplicationSurfaces.module.css`；后续必须在现有内容上语义合并。
- `mvn test`：退出码 1，2372 tests、1 failure、0 errors、0 skipped；既有失败为 `ArchitectureRulesTest.mybatisPlusOwnsEntityAuditFieldFilling`（expected 62, actual 61）。
- `apps/admin run test`：退出码 0，76/76 通过。
- `apps/web run test`：退出码 1，407/412 通过；5 个既有失败均位于 mobile trading/chart toolbar 合同。
- `packages/ui run test`：退出码 1，37/38 通过；既有 `StateSurface.test.ts` 因已迁移路径 `apps/web/src/components/user-page/PageState.tsx` 不存在而失败。
- `packages/frontend-core run test`：退出码 0，189/189 通过。
- `verify:architecture`：退出码 0，`Architecture verification passed.`。
- 新增 `scripts/engagement-contract.test.mjs`；`node --test scripts/engagement-contract.test.mjs` 退出码 1，0/4 通过，四个预期 RED 分别证明用户消息 API、独立富文本编辑器、PopupCampaign 模型和 engagement WebSocket 目的地尚未实现。

## Task 1：Flyway、实体和旧消息安全回填

- [x] 先写空数据库迁移集成测试和旧 V16 数据回填测试。
- [x] 观察 RED：目标表和约束不存在。
- [x] 创建下一个未占用的 Flyway migration。
- [x] 创建目标表、外键、唯一约束与索引。
- [x] 为 user state、delivery token、message receipt 和受众查询建立必要索引。
- [x] 回填旧 content.messages，不删除旧表。
- [x] 添加 MyBatis-Plus entity/repository；不得创建 JPA repository。
- [x] 添加数据库枚举解析失败关闭测试，未知状态不得静默回退。

必要数据库测试：

- 新空库能从 V1 完整迁移到最新版本。
- 已有 V16 messages 的库能回填且重复执行安全。
- 同一用户/活动 user state 唯一。
- 同一 delivery token 唯一。
- 同一消息/用户 receipt 唯一。
- 同一活动只能有一个同步 MessagePublication。
- 逻辑删除不级联物理删除 revision、delivery、receipt 或 audit。

验证：

~~~powershell
cd fx-trading-platform/backend
mvn "-Dtest=*Engagement*Migration*,*ContentMessage*Migration*" test
~~~

### Task 1 执行记录（2026-07-19）

- 初始 RED：真实 PostgreSQL 16 从 V1/V16 迁移到既有 V62 后，两个 migration IT 均因缺少 V63 失败（2 tests、2 failures、0 skipped）。
- 约束 RED：旧消息回填测试汇总出 7 个跨表归属/审计时间缺口；schema invariant 测试汇总出 revision 可变、ACTIVE 发布时间、token 大小写与 delivery revision 归属等 5 个缺口；空正文测试证明旧 `body=''` 会生成非法空 text node；最终父表改绑测试单独证明 campaign 可破坏既有 delivery 归属。上述 RED 均在真实 PostgreSQL 上观察后才修复。
- 新增 `V63__engagement_schema.sql`：12 张表、26 个 FK、48 个 CHECK、21 个显式索引、3 个约束函数与 3 个触发器；无 `IF NOT EXISTS`、无 `ON DELETE CASCADE`，旧 `content.messages` 保留。
- V16 回填复用旧 UUID，安全转义 legacy body；空正文映射为无空 text node 的 paragraph；最终 revision 使用 `message.updated_at`，postcondition 精确校验正文 JSON、sanitized HTML、时间、受众截止、删除状态、actor 与 target cardinality。
- 新增 12 个 MyBatis-Plus entity、12 个 repository 与 12 个严格 enum；4 个组合主键 mapper 使用显式 SQL，没有 JPA repository。ContentRevision、campaign content item 与 delivery revision 归属由数据库 fail closed。
- `mvn -DskipTests test-compile`：退出码 0，`BUILD SUCCESS`。
- `mvn "-Dtest=*Engagement*Migration*,*ContentMessage*Migration*" ... test`（显式 Docker Desktop/Testcontainers 参数）：最终退出码 0，4 tests、0 failures、0 errors、0 skipped，`BUILD SUCCESS`，15:52。
- `mvn -Dtest=EngagementEnumMappingTest test`：退出码 0，1/1 通过，未知数据库枚举值抛出 `IllegalArgumentException`。
- `ArchitectureRulesTest#mybatisPlusOwnsEntityAuditFieldFilling`：仍仅有 Task 0 已记录的同一个范围外差值；基线 expected 62 / actual 61，当前 expected 70 / actual 69，证明本任务新增 8 个审计字段全部符合 MyBatis-Plus fill 规则。

## Task 2：内容、活动生命周期、受众与资格计算

- [x] 先写 PopupCampaign 生命周期失败测试。
- [x] 先写活动时区、自然日、总次数、每日次数和最小间隔失败测试。
- [x] 先写 ALL 动态新用户与 SELECTED 冻结名单测试。
- [x] 先写已发布后受众和 syncToInbox 不可修改测试。
- [x] 先写暂停不顺延、结束不展示、恢复为 PAUSED 测试。
- [x] 实现 ContentItem/ContentRevision 与后端 HTML sanitizer。
- [x] 实现活动命令和查询 Interface。
- [x] 使用注入 Clock；测试不得依赖系统当前时间。
- [x] 更新内容时原子切换 currentRevisionId，不重置 user state 或 receipt。

必须覆盖边界：

- Asia/Shanghai 23:59 到次日 00:00。
- DST 时区虽然第一版默认上海，也必须由 ZoneId 正确处理。
- daily 上限已重置但 minInterval 尚未满足。
- maxDaily 大于 maxTotal 被拒绝。
- startAt 等于 endAt 或 endAt 早于 startAt 被拒绝。
- disabled/frozen 目标用户在活动有效期内恢复登录。
- 活动结束后注册的用户不属于历史 ALL 活动。

验证：

~~~powershell
mvn "-Dtest=PopupCampaignServiceTest,PopupEligibilityPolicyTest,PopupAudiencePolicyTest,ContentRevisionServiceTest" test
~~~

### Task 2 执行记录（2026-07-19）

- 初始 RED：生命周期、资格、受众和内容修订目标测试均先因对应领域/应用合同不存在而在 `testCompile` 失败；补充 RED 又分别证明 campaign 缺少 canonical 行锁与持久化命令/查询、名单替换信任陈旧 aggregate、revision 并发递增缺少 `FOR UPDATE`、平台图片状态未校验，以及生产构造器未注入 `Clock`。
- 生命周期补强 RED 证明未发布草稿可经 `DELETED -> PAUSED -> ACTIVE` 绕过首次发布，并且已删除活动恢复后无法重新发布；修复后未发布恢复活动必须走 `publish`，历史活动重新发布保留 `firstPublishedAt` 并推进 `lastPublishedAt`。
- 新增唯一事务型 `PopupCampaignService`：按 ID 查询；所有命令先 `SELECT ... FOR UPDATE` 读取 canonical row、fail-closed rehydrate、执行领域约束并单次更新；发布后冻结 audience/sync，同时允许修改 endAt 与频控，写冲突失败关闭。
- `PopupAudienceService` 改为数据库权威入口：与发布共用 campaign 行锁，只允许未首次发布的 SELECTED 活动替换名单；批量验证全部 role=USER 后才删除旧名单并写入去重 UUID，ACTIVE/FROZEN/DISABLED 均保留受众资格。
- 新增结构化 JSON 白名单 sanitizer 与不可变 ContentRevision 服务；拒绝 raw HTML、script/iframe/event/style、Base64/外链图片和任意 route，正文与封面只接受存在且 ACTIVE 的 JPG/PNG/WebP 平台资产。修订时锁定 ContentItem、只 INSERT 新 revision，并在同一事务原子切换 `currentRevisionId`，不访问 user state 或 receipt。
- 新增唯一 UTC `Clock` Spring bean；campaign 与 content 服务均通过构造器注入，全部时间测试使用 fixed Clock。资格测试覆盖上海午夜、DST、跨日仍受 minInterval、总/日上限、opt-out、暂停/结束与 endAt 排除边界；受众测试覆盖动态 ALL cutoff、ADMIN 排除、SELECTED 冻结及账户状态恢复。
- 计划标准命令：退出码 0，42 tests、0 failures、0 errors、0 skipped，`BUILD SUCCESS`。
- 扩展组合命令（额外包含 `PopupAudienceServiceTest,EngagementClockConfigurationTest`）：退出码 0，53 tests、0 failures、0 errors、0 skipped，`BUILD SUCCESS`。
- `mvn -DskipTests test-compile`：退出码 0，`BUILD SUCCESS`。
- `mvn -Dtest=ArchitectureRulesTest test`：仍仅有 Task 0 已记录的同一个范围外失败（expected 70 / actual 69）；其余 24 项通过，本任务未新增架构回归。

## Task 3：原子领取、连续队列与投放结果

- [x] 先写两个标签页同时领取只能成功一次的 PostgreSQL 并发测试。
- [x] 先写顺序测试：priority DESC、publishedAt ASC、id ASC。
- [x] 先写全局 maxSequentialPopups=3 与后台调整策略测试。
- [x] 先写普通关闭继续下一条、CTA 终止、opt-out 继续测试。
- [x] 先写 shown 前不计数、重复 shown 不重复计数、过期 token 不计数测试。
- [x] 先写管理员 reset 不能清除 optedOutAt 测试。
- [x] 实现 queue session、delivery lease 和 user state 原子更新。
- [x] token 只返回明文一次，数据库保存 hash。
- [x] delivery 必须保存实际 revisionId，保证正在展示内容不热更新。

领取 Interface 必须是事务权威；Controller 不得先查询再分别更新。

验证：

~~~powershell
mvn "-Dtest=PopupClaimServiceTest,PopupClaimConcurrencyTest,PopupQueuePolicyTest,PopupOutcomeServiceTest" test
~~~

### Task 3 执行记录（2026-07-20）

- 严格 TDD RED：QueuePolicy 首先因领域类型不存在出现 4 个 `testCompile` 错误；claim 测试首先因 `PopupClaimService` 不存在出现 2 个 `testCompile` 错误；outcome 矩阵首先因 `PopupOutcomeService`/repository seam 不存在失败。初次 GREEN 后追加暂停活动的重复 `SHOWN` 回归，真实观察到 `accepted=true` 的 1 个失败，再移动幂等判断到权威有效性检查之后修复。
- 新增唯一事务型 `PopupClaimService`：以 `auth.users FOR UPDATE` 作为跨标签页/设备的用户互斥锁，锁定并校验既有 queue session；新 session 仅在找到候选后落库，未知外部 session ID fail closed。候选由 PostgreSQL 同时执行活动、用户、受众、页面、终端、频控、active lease 与同队列去重过滤，并固定 `priority DESC, first_published_at ASC, id ASC`。
- queue 创建时快照 `engagement.popup.maxSequentialPopups`，缺失使用 3，持久化非法值 fail closed；达到上限或过期原子写入 termination，CLOSE/OPT_OUT 继续，CTA_CLICK 原子终止本 session。
- delivery 使用 32-byte Base64URL 随机明文凭证，响应 `toString` 脱敏；数据库只存带 `popup-delivery:` domain separation 的 SHA-256 hash。领取时固定实际 `revisionId`，5 分钟 lease 同时受 session 与 campaign 结束时间约束。
- 新增唯一事务型 `PopupOutcomeService`：token 绑定 caller user；统一按 user、session、campaign、delivery、state 锁序执行。仅首次有效 `ISSUED -> SHOWN` 递增 total/daily/last impression，daily bucket 使用 campaign `ZoneId`；重复 SHOWN 零写，过期/暂停/删除 fail closed 并清理精确 pointer。终态精确重放幂等，不同终态重放不得补写 opt-out、click 或 session termination。
- campaign-wide reset 只清 total/daily bucket/daily/lastImpression 并递增 version，SQL 明确保留 optedOutAt、lastClickedAt 与 active lease。
- PostgreSQL 首轮先揭示测试夹具把 `Instant` 交给 `JdbcTemplate` 时类型无法推断；改为显式 `Timestamp` 后，真实 PostgreSQL 16 并发/排序/revision pin 3 tests、0 failures、0 errors、0 skipped，`BUILD SUCCESS`。测试上下文显式在容器停止前关闭，避免既有 scheduler teardown 干扰。
- 计划联合命令在当前 Docker Desktop 29.6.1 上使用仓库既有兼容参数 `-Dapi.version=1.44`：69 tests、0 failures、0 errors、0 skipped，`BUILD SUCCESS`；其中 Claim 14、PostgreSQL 3、Outcome 36、QueuePolicy 16。
- `mvn -DskipTests test-compile`：退出码 0，`BUILD SUCCESS`（342 test sources）。
- `mvn -Dtest=ArchitectureRulesTest test`：仍仅有 Task 0 已记录的同一个范围外失败（expected 70 / actual 69）；其余 24 项通过，本任务未新增架构回归。

## Task 4：完整消息中心后端与活动同步

- [x] 先写普通消息 DRAFT、SCHEDULED、SENT、DELETED 生命周期测试。
- [x] 先写 ALL 和 SELECTED 收件人测试。
- [x] 先写活动同步消息与 ContentItem 共享测试。
- [x] 先写弹窗 shown 自动 readSource=POPUP 测试。
- [x] 先写内容修改同步更新消息且不重置 readAt 测试。
- [x] 先写用户 read、unread、read-all、hide 测试。
- [x] 先写管理员删除隐藏所有用户消息、用户隐藏只影响本人测试。
- [x] 先写活动结束消息仍保留、活动删除消息隐藏测试。
- [x] 实现用户消息 Controller，所有 userId 来自 Principal。
- [x] 实现用户列表分页和未读数，不做全站 N 用户同步 fan-out。
- [x] 实现普通消息 Admin Interface；发送后受众冻结。

验证：

~~~powershell
mvn "-Dtest=MessagePublicationServiceTest,MessageReceiptServiceTest,CampaignMessageSyncTest,UserMessageControllerTest" test
~~~

### Task 4 执行记录（2026-07-20）

- RED：普通消息纵切先以缺失 `MessagePublicationService`/命令类型在 `testCompile` 失败；消息回执纵切以缺失 service、query DTO、`PopupShownHandler` 和 Controller 的 9 个编译错误失败。空 `SELECTED` 定时发送与已定时消息清空受众又分别产生 1 个目标行为失败，修复后 `MessagePublicationServiceTest` 为 17/17。
- 实现普通消息 DRAFT/SCHEDULED/SENT/DELETED 生命周期、ALL/SELECTED 受众、单次 Clock 边界、定时派发、内容修订、删除/恢复和发送后受众冻结。活动同步消息复用同一 `ContentItem`，SELECTED 使用单条 `INSERT SELECT` 冻结目标；暂停/结束保留消息，删除隐藏，恢复后保持隐藏，重新发布复用原 publication 与 receipt。
- 用户消息列表和未读数以 PostgreSQL 查询作为唯一可见性权威；GET 不写 receipt。read、unread、read-all、hide 使用用户行锁和 CTE/UPSERT，弹窗首次 SHOWN 在 delivery/state 写成功后同步写 `readSource=POPUP`，任一 handler 失败回滚整笔 outcome。
- V64 保留旧 `content.messages` 可读，同时以稳定 SQLSTATE `55000` 拒绝 INSERT/UPDATE/DELETE；旧 Admin message 命令统一失败为 `LEGACY_MESSAGE_READ_ONLY`，article 命令保持不变。
- 真实 PostgreSQL RED：MyBatis-Plus 默认忽略 null 更新，3 个生命周期用例出现 1 failure/2 errors，证明 `scheduled_at`/`deleted_at` 未清且恢复违反 check constraint。对 7 个 nullable 生命周期字段使用 `FieldStrategy.ALWAYS` 后，同一测试 3/3 GREEN、0 skipped。
- PostgreSQL receipt smoke 首轮仅暴露测试方法间 ALL 消息污染（read-all expected 1 / actual 2）；增加每方法事务回滚后，4/4 GREEN，真实执行复杂 CTE、record/enum/Instant 映射和 receipt UPSERT，0 skipped。
- 计划原验证命令：48 tests，0 failures/errors/skipped，BUILD SUCCESS。补充验证：`PopupOutcomeServiceTest` 38/38；`AdminContentCommandServiceTest` 5/5；V64 migration 方法 1/1；`mvn -DskipTests test-compile` GREEN；`ArchitectureRulesTest` 25/25 GREEN。

## Task 5：权限、审计和 Admin API

- [x] 先写权限目录和 MVC 授权矩阵失败测试。
- [x] 将权限加入 AdminPermissionCatalog、RBAC seed/bootstrap 与 Admin authorities。
- [x] 为活动、普通消息、用户明细和全局策略建立 Admin Controller。
- [x] 所有命令写审计：创建、编辑、发布、定时、暂停、恢复、结束、删除、重置、测试、查看用户明细、修改全局策略。
- [x] 审计内容记录 revisionId、受众类型、目标数量、before/after 和 reason，但不得记录正文中的敏感信息或任何凭证。
- [x] 实现用户搜索 API，仅返回必要字段。
- [x] 证明普通 ADMIN 无细分权限时收到 403。

重点权限：

- edit 不能隐式获得 publish。
- stats 不能隐式获得 user-detail。
- campaign delete 与 message delete 分离。
- popup-policy:update 不由普通 publish 自动获得。

验证：

~~~powershell
mvn "-Dtest=EngagementAuthorizationTest,EngagementAdminControllerTest,EngagementAuditServiceTest,AdminUserSearchTest" test
~~~

Task 5 执行记录：

- 新增 11 项细分权限、V65 RBAC/策略 seed、活动 14 路由、普通消息 8 路由、用户搜索和 popup policy Interface；所有 `/api/admin/**` 新入口逐方法同时校验 `ROLE_ADMIN` 与精确 authority，旧 message 入口也补齐独立权限。
- V65 只把两组菜单及 button authorities 授给可信、system-managed 的固定 `SUPER_ADMIN` role；保留 authority/稳定 ID 冲突时整笔迁移失败关闭，不给普通 ADMIN 自动授权，已有 setting 值使用 `ON CONFLICT DO NOTHING` 保留。
- 新增事务内 typed `EngagementAuditService`；活动、消息、测试预览、投放重置、用户明细和策略修改均在领域写成功后恰好写一条审计，失败零审计。metadata 固定为 revisionId、audienceType、targetCount、before、after、reason，不传 title/bodyDocument/sanitizedHtml/CTA/token/hash/password。
- 普通消息 Admin 仅管理 MANUAL source；SENT 受众冻结但允许只修订内容，审计保留旧/新 revision 轨迹。ALL 目标数在发送前按当前 USER、发送后按 audienceCutoffAt 计算；SELECTED 同时校验原始 target 与 role=USER 数量。
- 用户搜索支持 UUID 精确查询以及 email/phone 参数化 `ILIKE`，使用 `!` 转义 `!/%/_`，仅返回 id/email/phone/status，限制 role=USER、三种业务状态、size<=100，并按 email/id 稳定排序。popup policy 使用固定顺序 `FOR UPDATE`、1..100/1..3650 边界和单行写校验；通用 setting API 无法绕过专用 policy 权限。
- 真实 RED 包括：权限目录/旧入口/通用配置/audit seam 共 4 failures + 1 error；V65 文件缺失；message/search/policy 类型缺失；Campaign ALL create 的无谓 target delete 与 user-detail `WHERE ... LEFT JOIN` 非法 SQL。最小修复后均转 GREEN。
- 计划原验证命令：46/46，0 failures/errors/skipped。补充验证：Campaign 联合 55/55；Message/Search/Policy 联合 20/20；Campaign Admin PostgreSQL 16 query smoke 2/2；V65 PostgreSQL 16 migration 2/2、0 skipped；Admin authority/bootstrap/config/legacy 23/23；`ArchitectureRulesTest` 25/25；`mvn -DskipTests test-compile` GREEN。

## Task 6：定时生效、Outbox 与 WebSocket 唤醒

- [x] 先写 scheduler 默认 Bean 不存在测试。
- [x] 先写显式 enable 后才创建 dispatcher 的测试。
- [x] 先写时间到达前不可领取/不可见，时间到达后可领取/可见测试。
- [x] 先写 Outbox 与领域事务原子性测试。
- [x] 先写重复消费 Outbox 不重复产生领域结果测试。
- [x] 扩展 WebSocketJwtChannelInterceptor，仅允许认证 engagement 订阅。
- [x] 实现 ALL topic 与 SELECTED user queue 唤醒。
- [x] 暂停、删除时发送 invalidation；客户端未收到时后续 HTTP 仍拒绝。
- [x] 普通消息发送后只实时更新未读，不自动弹窗。

建议配置：

~~~yaml
app:
  engagement:
    scheduler:
      enabled: false
    outbox:
      enabled: false
~~~

生产 profile 必须显式开启；测试通过直接调用 dispatcher 和 Clock 推进。

验证：

~~~powershell
mvn "-Dtest=EngagementSchedulerWiringTest,EngagementSchedulingTest,EngagementOutboxTest,EngagementWebSocketAuthorizationTest" test
~~~

Task 6 执行记录：

- Scheduler 由单一条件 Bean 驱动；base/dev 默认关闭，prod 显式默认开启。`Clock` 直接推进验证 due 前保持 `SCHEDULED` 且不可见、边界时活动转 `ACTIVE`/消息转 `SENT`、过期活动转 `ENDED`，活动与消息查询均使用包含边界的有序 `FOR UPDATE SKIP LOCKED`。
- 复用 V63 `content.engagement_outbox`，没有新增迁移或消息框架。领域命令事务内以 `MANDATORY` append；活动发布/更新/暂停/结束/删除/恢复/定时生效与已发送普通消息发送/修订/删除/恢复均生成安全事件。payload 只持久化 updateType、aggregateId、occurredAt、audienceType，SELECTED 用户只在消费时解析。
- Dispatcher 默认关闭、prod 显式开启；成功写 publishedAt，失败只累计 attemptCount 与异常类型，已发布行不会再次领取。真实 PostgreSQL 16 `REQUIRES_NEW` 回滚探针证明活动状态写与 outbox append 同时回滚。
- WebSocket inbound 只放行认证用户订阅精确 `/topic/engagement/updates` 与 `/user/queue/engagement-updates`；ALL 使用 topic，SELECTED 使用逐用户 queue。wire payload 精确为 updateType、aggregateId、occurredAt，普通消息只发 `MESSAGE_UPDATED` 唤醒，不产生 popup 指令。
- Pause/delete 发送 `CAMPAIGN_INVALIDATED`；即使客户端漏事件，claim SQL 仍只选 `ACTIVE`，outcome 也会在暂停、删除或过期后将 delivery 失效。完整 Spring PostgreSQL 启动同时暴露并修复了 `AdminUserSearchRepository` 不在 MapperScan 包下的生产 wiring 缺陷。
- 真实 RED 包括 Outbox 12 个缺失类型、scheduler 唯一缺失 `activateDue()`、ISO 时间序列化合同 1 failure，以及完整 Spring context 的 Mapper wiring error；均修根因后转 GREEN。计划原命令 17/17；scheduler 6/6；WebSocket/既有安全 13/13；Outbox/producer 40/40；PostgreSQL 原子性 2/2；架构、搜索和 popup 权威回归 81/81；全部 0 skipped。`mvn -DskipTests test-compile` GREEN。

## Task 7：平台图片资源与受限富文本

- [x] 检查现有上传配置与资产能力，优先复用真正存在的存储 Adapter。
- [x] 如果不存在，建立 content asset Interface 与本地开发 Adapter；生产未配置时必须失败关闭，不能假装上传成功。
- [x] 先写 MIME 伪造、超限文件、外链、Base64、SVG/script 和路径穿越失败测试。
- [x] 选择兼容 React 19 的维护中 WYSIWYG 库；查官方文档后锁定版本，不手写 HTML parser。
- [x] 后端使用白名单 sanitizer，允许的标签和属性必须有测试。
- [x] 正文只引用 assetId；响应层生成受控资源 URL。
- [x] CTA routeKey 与 params 使用后端白名单校验。
- [x] 加入 PC/Mobile 内容预览模型。

允许格式至少包括段落、粗体、斜体、列表、颜色、对齐、安全链接和平台图片。原始 HTML 输入不进入公开 Interface。

验证：

~~~powershell
mvn "-Dtest=ContentSanitizerTest,ContentAssetServiceTest,InternalRoutePolicyTest" test
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"
~~~

### Task 7 执行记录（2026-07-20）

- 仓库中不存在可复用的真实上传/对象存储 Adapter；新增最小 `ContentAssetStorage` seam、本地 dev Adapter 与始终失败关闭的 base/prod Adapter。上传只接受经 magic、声明 MIME、尺寸、像素数和 SHA-256 校验的 JPG/PNG/WebP；UUID storage key 不信任原文件名，本地 Adapter 拒绝路径穿越和符号链接。
- 严格 TDD 覆盖 MIME 伪造、5 MiB 超限、SVG/script、外链/Base64、路径穿越、存储/事务失败清理和读取完整性。交叉安全复核追加的 RED 精确证明 VP8X canvas 可与 VP8/VP8L 巨帧不一致，以及 multipart 超限缺少 handler；修复后拒绝重复/不一致 WebP frame，并由真实 DispatcherServlet resolver 返回 HTTP 413、稳定错误码且 service 零调用。
- 依据 Tiptap 官方 React 19 peer dependency、发布版本、持久化和扩展文档，精确锁定六个 MIT core 包为 `3.23.6`；未引入 Cloud/Pro/UI/image 包。编辑器只输出 `editor.getJSON()`，禁用原始 HTML/任意图片 URL，平台图片 JSON 仅含 `assetId/alt`，内部链接仅含 `routeKey/params`，正文展示时生成固定 `/api/public/engagement/assets/{assetId}` URL。
- 后端白名单 sanitizer 的真实 Tiptap fixture 覆盖 paragraph、h1-h3、bold、italic、bullet/ordered list、`start`、hard break、固定颜色、left/center/right、内部链接和平台图片；CTA route/params 继续由后端白名单作最终校验。
- `frontend-core` 新增 PC/Mobile × SMALL/MEDIUM/LARGE 只读预览模型。模型只接受后端 `coverAssetId` 并内部生成 canonical URL，拒绝可注入的 `coverAsset/url/src/external`、外链和 Base64；不重新信任或解析原始 HTML。
- 真实 RED 包括后端四个缺失资产类型、编辑器实现缺失、预览模型模块缺失、受控 URL 旧输入合同 3 个失败，以及安全复核的 2 failures + 1 error。最终计划原命令：backend 32/32、admin 84/84，全部 0 skipped；补充 asset/security 96/96、resolver 13/13、preview 5/5、ArchitectureRules 25/25、admin production build 与 frontend-core typecheck 均 GREEN。

## Task 8：后台弹窗活动全页面编辑器

不得继续用 FeatureCrudPage 的通用小弹窗承载该功能。

- [x] 新增“弹窗活动”菜单、列表路由和独立编辑路由。
- [x] 活动编辑分四步：
  1. 基本信息与富文本。
  2. 全部或搜索多选用户、页面、终端。
  3. 时间、时区、优先级、总次数、每日次数、最小间隔。
  4. PC/Mobile 预览、保存、测试、立即或定时发布。
- [x] 列表支持状态、名称、受众、同步、有效期筛选。
- [x] 列表操作支持编辑、暂停、恢复、结束、逻辑删除、恢复、统计。
- [x] 已发布活动 UI 禁止修改受众和 syncToInbox。
- [x] 测试弹窗明确显示 PREVIEW，不产生正式数据。
- [x] 全局策略页支持修改 maxSequentialPopups 和原始明细保留天数。
- [x] 用户级统计页面受权限控制。
- [x] 不添加导出按钮或伪导出入口。

必须写 Admin 测试证明：

- richtext 不再由 textarea 分支处理。
- 受众搜索多选保存真实 UUID。
- 已发布不可修改字段在 UI 与后端都被拒绝。
- 定时发布校验正确。
- 权限不足时按钮不可见且直接调用 API 仍返回 403。

验证：

~~~powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
~~~

### Task 8 执行记录（2026-07-20）

- RED：后端筛选/首次发布字段合同最初产生 12 个 testCompile errors；Admin 模型/API 模块最初缺失；四步页面合同最初 10 项失败。渲染与交叉审查又先后复现了 user-detail 越权请求 stats、跨活动旧用户明细竞态、无原因步骤的无效保存、publish-only 被迫 PUT、页面选项遗漏和只读受众仍可本地修改等失败合同。
- GREEN：`mvn "-Dtest=CampaignAdminServiceTest,EngagementAuthorizationTest,EngagementAdminControllerTest" test` 47/47；`mvn "-Dapi.version=1.44" "-Dtest=CampaignAdminQueryPersistenceTest" test` 在 PostgreSQL 16 上 3/3、0 skipped、Flyway 65。
- GREEN：`cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"` 111/111；`cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"` 成功，Vite 转换 1704 modules。
- RENDER：Browser plugin 不可用，按前端测试工作流回退本地 Playwright；1536×1024 与 390×844 均无文档横向溢出、框架错误层、console error/warn、pageerror 或失败请求。实测筛选参数、UUID 多选、后端清洗 PREVIEW、publish-only 零 PUT 发布以及 user-detail-only 零 stats 请求。
- Ponytail：复用现有 14 条后端路由、typed API client、frontend-core 预览模型和权限矩阵；未新增导出能力、通用 CRUD 分支或额外状态库。

## Task 9：后台普通消息管理

- [x] 将旧 member-notices 迁移为专用普通消息页面，不再走通用假 richtext 表单。
- [x] 支持草稿、立即发送、定时发送、取消定时。
- [x] 支持 ALL 与搜索多选 SELECTED。
- [x] 发送后锁定受众，允许修改内容。
- [x] 修改已发送内容不重置 receipt。
- [x] 支持逻辑删除与恢复。
- [x] 明确显示普通消息不会自动弹窗。
- [x] 对旧 messages 回填数据保持可读。

验证：

~~~powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
~~~

### Task 9 执行记录（2026-07-20）

- RED：专用页面合同最初 9/9 失败；普通消息 model/API 首轮因模块和 exports 缺失失败。交叉审查进一步复现 A→B 或 A→new 路由切换时旧实体可能留在新 URL 下的误更新风险，补充 fail-closed 回归后先红再修。
- 直接复用既有 8 条 `/api/admin/engagement/messages` 路由、用户搜索、平台资产与受限 Tiptap 编辑器；未新增后端接口、数据库迁移、依赖、状态库或通用 CRUD 分支。`/content/messages` 只重定向到专用 `/content/member-notices`，不再保留第二套陈旧页面。
- 专用列表支持 status/title 筛选、分页、权限与生命周期动作；专用编辑器支持 DRAFT、立即/定时发送、取消定时、ALL/SELECTED UUID 搜索多选、平台图片与白名单内部链接。SENT 更新请求省略 audience 字段且不包含 receipt 控制，正文仍可修订；DELETED 只读并从列表恢复。
- V63 回填的 MANUAL publication 由同一新查询读取，不增加 `excludeLegacy` 等分叉。普通消息页面持续显眼声明“不自动弹窗”，所有写操作收集必填审计 reason；列表动作使用同步 guard 防止并发重复提交。
- GREEN：`cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"` 为 130/130、0 skipped；`cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"` 成功，Vite 转换 1707 modules；`git diff --check -- fx-trading-platform/apps/admin/src` 通过。
- RENDER：Browser plugin 不可用，按前端测试工作流回退本地 Playwright。1536×1024 与 390×844 实测筛选参数、UUID 多选、未来定时发送、SENT 受众冻结/内容可改、send-only 零 PUT、失败路由 fail closed；文档宽度 390/390，无错误 overlay、console error/warn、pageerror 或失败请求。产物位于 `fx-trading-platform/test-results/task9-member-notices/`。
- Ponytail：沿用现有深后端接口与共享编辑器，只新增普通消息薄 model、typed API wrapper、列表/编辑两页和局部 CSS；没有复制旧 legacy CRUD，也没有为并行测试命名差异增加 alias。

## Task 10：frontend-core 客户端、队列 Controller 与共享合同

- [x] 先写纯 Controller 测试，不依赖具体 PC/Mobile DOM。
- [x] 在 packages/frontend-core 建立 engagement API client、消息模型和 popup queue Controller。
- [x] 不把 engagement 代码放进 market 或 trading 命名空间。
- [x] 复用底层 STOMP 连接能力，但建立独立 engagement subscription Interface。
- [x] Controller 处理登录、路由变化、实时唤醒、窗口重新获得焦点和关键业务弹窗解除。
- [x] 普通关闭请求 next；CTA 终止；无效/删除活动跳过并继续。
- [x] shown 成功后才在本地进入已展示状态。
- [x] API 401/403、离线、超时、重复事件和 reconnect 均有测试。
- [x] 更新 OpenAPI 生成共享类型；不得手工伪造 generated/openapi.ts。

验证：

~~~powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/frontend-core run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/frontend-core run typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:export"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:generate"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:check"
~~~

### Task 10 执行记录（2026-07-20）

- RED：纯队列 Controller 首轮因实现缺失失败；独立 engagement stream/API/model 与 package export 均先由合同测试证明缺失。交叉审查再复现 CTA 收到 `INVALIDATED + CONTINUE` 却错误终止、持有弹窗后的 403 未 fail closed、observer 抛错产生未处理拒绝、重复 STOMP callback lease 提前释放，以及 delivery token 被全局 request log 明文持久化等问题，均先补失败回归。
- 后端新增 Principal-only 的 6 条用户弹窗路由，claim 在同一事务固定 revision 并返回受限展示内容；未知/他人 queue session 与 delivery credential 使用统一 400 业务错误，匿名 401、ADMIN 403、USER 200 由真实 MockMvc + Method Security 验证。四条 outcome URL 在 request log 落库边界将一次性 token 段替换为 `<redacted>`，冻结 API 不变。
- frontend-core 新增独立 `engagement` namespace、generated-schema 锚定 API/model、纯 popup queue Controller 与对象式 subscription adapter。Controller 保留已签发 claim 的原 queue surface；close/opt-out/失效 click 按服务端 directive 继续，成功 CTA 终止；shown 成功后才置本地状态；401/403 先清空 claim/session/subscription，observer 只能旁观。底层 STOMP 以 window + token 隔离并与 market 共用，同 callback 的每个 lease 独立释放。
- OPENAPI：启动 scheduler/outbox 默认关闭的 dev 后端，从 `http://127.0.0.1:18081/v3/api-docs` 导出；冻结 engagement 合同实测 32 paths / 39 operations。`contract:check` 在生成前按预期失败，随后仅通过 `contract:generate` 更新 `packages/shared-types/src/generated/openapi.ts`，再次 `contract:check` 通过。
- GREEN：frontend-core 全量 231/231、0 skipped，typecheck 通过；后端相关 `UserPopupControllerTest,PopupClaimServiceTest,PopupOutcomeServiceTest,RequestLogFilterTest,UserMessageControllerTest,EngagementAuthorizationTest,EngagementWebSocketAuthorizationTest` 为 93/93、0 skipped；生成合同 current，选定文件 `git diff --check` 无 whitespace error。
- Ponytail：沿用既有 ApiClient、content preview、STOMP、业务异常和全局安全链，只增加一个纯状态机、一个窄 adapter 与 token path 脱敏；没有引入状态库、第二条 WebSocket 技术栈、客户端 userId、手写 generated 类型或可选框架层。

## Task 11：用户端 PC/Mobile 弹窗与消息中心

- [x] 在 AppShell seam 接入常驻 Engagement Controller，不让每个页面重复查询。
- [x] 建立活动弹窗 shared widget；PC/Mobile 只负责布局差异，行为由同一 Controller 管理。
- [x] 扩展 packages/ui Dialog：
  - 完整 focus trap。
  - 初始焦点与关闭后焦点恢复。
  - Esc、遮罩关闭。
  - body scroll lock。
  - 多 overlay 层级与关键业务弹窗优先。
- [x] 支持 SMALL、MEDIUM、LARGE、封面图、滚动正文和一个 CTA。
- [x] 普通关闭继续下一条；CTA 导航后终止队列。
- [x] PC 顶部铃铛显示未读数和最近消息。
- [x] 移动端铃铛直接进入消息中心。
- [x] 新增 /messages 或仓库命名规范下的等价 canonical route。
- [x] 消息页面支持全部/未读、分页、已读、未读、全部已读和隐藏。
- [x] 活动 shown 后同步消息立即从未读数移除。
- [x] 后台暂停/删除唤醒后自动关闭当前弹窗并继续有效队列。
- [x] 登录、注册、忘记密码页面不展示。
- [x] 关键交易/风控确认弹窗打开时活动排队。

必须测试：

- PC 与 Mobile 共用同一个队列 Controller。
- resize 不重复领取。
- route change、WebSocket reconnect 不重复 shown。
- Esc、遮罩、按钮关闭都调用普通 close。
- 不再提醒调用 opt-out。
- CTA 只导航白名单内部路由。
- 消息 read/unread/hide 对未读数实时生效。
- 删除消息不会因旧缓存重新出现。

验证：

~~~powershell
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/ui run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/ui run typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"
~~~

### Task 11 执行记录（2026-07-20）

- RED：先由 AppShell/runtime、消息 Controller、canonical route、铃铛和活动 widget 合同测试证明常驻接入缺失；纯队列测试复现 CTA directive、shown 重试和 critical pending-claim 问题。独立复审继续复现同一 commit 内关键 Dialog 注册晚于父 render 快照导致活动先 shown，以及 AppShell `.mainRegion` 为 panel 兄弟节点时未被锁滚动，均先加入失败回归。
- AppShell 只创建一个 Engagement runtime，以独立 auth、route、device、focus、reconnect 和 overlay 信号驱动同一 popup/message Controller；StrictMode 式 release/reacquire 不重复登录或订阅，auth 页面和未知 route fail closed。关键 overlay 在 layout 阶段读取实时 store，popup passive effect 再做实时 guard，关闭同帧 shown 窗口。
- `packages/ui` Dialog 补齐 focus trap、初始/恢复焦点、顶层 Esc/遮罩、standard/critical 层级、pending 关闭保护和引用计数 scroll lock；锁定 body、panel 祖先及 panel 外部的实际滚动容器，同时保留所有 Dialog 正文滚动。7 个交易、持仓、杠杆和资金确认弹窗统一为 critical。
- 活动 widget 共用一套响应式行为，支持三种尺寸、封面、滚动安全 HTML、普通关闭、opt-out 和一个白名单 CTA；shown 失败可在 focus/reconnect 或终止动作前重试，暂停/删除更新会失效当前 claim 并继续队列，危险 HTML、外部 URL 和原型键导航均拒绝。
- PC 铃铛显示权威未读数与最近消息，Mobile 直接进入 `/messages`；消息中心共用 AppShell runtime，支持 ALL/UNREAD、服务端分页、read/unread/read-all/hide、loading/error/empty/pending 与未登录可操作状态。shown、update、reconnect、focus 和 mutation 后均刷新权威 inbox，hidden tombstone 与 generation 防止旧响应复活数据。
- GREEN：`packages/ui` 全量 58/58、0 skipped，typecheck 通过；`packages/frontend-core` 全量 235/235、0 skipped，typecheck 通过；`apps/web` 全量 460/460、0 skipped，`tsc -b && vite build` 通过（仅保留既有 500 kB chunk warning）。最终独立定向复审 29/29 通过，未发现剩余 HIGH/MEDIUM；相关文件 `git diff --check` 无 whitespace error。
- Ponytail：沿用一个 AppShell runtime、一个纯队列 Controller、共享 Dialog、既有 STOMP 与 URL 路由，不引入状态库、第二套队列、PC/Mobile 重复业务组件或客户端自造消息真相。

## Task 12：端到端验收、视觉 QA、数据保留和清理

- [x] 新增独立 smoke 脚本，不复用或破坏交易 smoke 的业务数据。
- [x] 使用 demo 用户和 demo Admin，不连接真实外部交易基础设施。
- [x] 验证定时消息和活动；测试结束后清理测试活动或使用专用前缀逻辑删除。
- [x] 验证 365 天原始 delivery 清理只删除明细，不删除汇总、user state、receipt、revision 或 audit。
- [x] 验证全部用户活动包含有效期内的新用户。
- [x] 验证 FROZEN/DISABLED 被选中后恢复登录时的行为。
- [x] 验证两个浏览器标签页、PC/Mobile、刷新和重连防重复。
- [x] 验证连续三个活动、第四个延迟到下一自然触发。
- [x] 验证内容覆盖同时更新消息、已打开弹窗保持旧 revision。
- [x] 验证逻辑删除关闭在线弹窗、隐藏消息，恢复后保持 PAUSED。
- [x] 验证普通消息只实时更新红点，不自动弹窗。
- [x] 验证 XSS、外链图片和任意 URL 被拒绝。
- [x] 完成 PC 1440x900 与 Mobile 390x844 视觉矩阵。
- [x] 检查键盘操作、焦点顺序、屏幕阅读器 label、长内容滚动和 reduced motion。

最终静态门禁：

~~~powershell
cd C:\workspace\tradingWeb

docker compose -f fx-trading-platform/infra/docker-compose.yml up -d

cd fx-trading-platform/backend
mvn test

cd C:\workspace\tradingWeb
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/ui run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/ui run typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/frontend-core run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/packages/frontend-core run typecheck"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/admin run build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run test"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform/apps/web run build"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run contract:ci"
cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run verify:architecture"
~~~

若仓库已有 frontend:check 且包含上述门禁，也要运行；不能用它替代 backend 全量测试和 engagement smoke。

### Task 12 执行记录（2026-07-20）

- RED/GREEN：真实 PostgreSQL 保留测试先复现过期 delivery 删除后 `active_delivery_id` 悬空；实现锁定旧 delivery、原子清空过期 state 指针并仅删除无引用明细后，`PopupDeliveryRetentionPostgresIT` 3/3、`PopupClaimConcurrencyTest` 6/6、`MessageAdminQueryPostgresIT` 1/1，全部 0 skipped。保留 scheduler 默认关闭，dev/test 不创建跑批 Bean；保留天数沿用动态系统设置，默认 365、上限 3650。
- RED/GREEN：全量样式门禁先复现 `EngagementPopup.module.css` 的跨模块 `:global(.align-*)`；新增失败合同后改为本地 `[class~="align-*"]` 白名单选择器，focused 1/1、web 全量 461/461、`verify:frontend-styles` 通过。交易 smoke 又复现“Spot 持仓已进入统一 positions API 却被误判为杠杆仓位”，将断言收紧为仅拒绝非 SPOT、非 CASH 或有 leverage 的仓位；随后 focused smoke 与 `contract:ci` 均通过。
- E2E：`smoke-engagement` 专用运行 `20260720-2210-full` 为 PASS，14/14 场景、22 张 PC/Mobile 截图、0 cleanup failure、0 敏感凭证模式；覆盖立即/定时、ALL/SELECTED、新用户、FROZEN/DISABLED、频控与连续队列、双标签/刷新/同文档真实 WebSocket 重连、revision 快照、消息 receipt、在线暂停/删除、三尺寸/双图/长正文、键盘/focus/reduced-motion 合同。报告位于 `test-results/engagement-smoke-20260720-2210-full/engagement-smoke-20260720-2210-full-report.json`。
- 独立只读复核逐张检查 22/22 产物且复跑 smoke contract 12/12：HIGH 0、MEDIUM 0；两个 LOW 仅为 reduced-motion 计算样式和三个按钮精确 Tab 顺序可增加动态证据，组件合同已有覆盖。
- 前端与合同：`npm run frontend:check` exit 0；ui 58/58、frontend-core 235/235、web 461/461、admin 131/131，typecheck/build、视觉合同 17/17、boundary/style/architecture、bundle budget、large-file audit 全绿；`npm run test:engagement-smoke-contract` 12/12；独立 `npm run verify:architecture` exit 0；专用 dev/demo 后端上的 `npm run contract:ci` exit 0，OpenAPI/生成类型 current，demo wallet、asset ledger、account summary 与 Spot 下单/撤单 smoke 通过。仅保留 Vite 既有 500 kB chunk warning，预算门禁仍通过。
- 后端全量（首次收口）：`mvn test` 真实运行 2804 tests、2 failures、0 errors、17 skipped；三组 engagement PostgreSQL 门禁均全绿，但两个失败稳定属于 Task 0 已记录的 V60–V62 范围外、完全 untracked 的 TradingLab：late trace secret 未回扫旧 buffer，以及朴素 canary scan 在硬编码 5 秒内超时。当时未跳过、放宽或修改这些范围外测试，并保持“backend 全绿”严格条件未勾选。
- 清理：烟测先逻辑清理 48 个 campaign、6 个 message、2 个 owned user，58 项无失败；最终停止专用后端并确认 18080 释放，删除专用数据库 `engagement_smoke_20260720_2045` 及两份 owned 图片资产，复核数据库计数 0、资产目录不存在。没有连接真实 broker、FIX、LP 或外部实时行情。
- Ponytail：复用现有 demo 认证、Admin 权限、AppShell runtime、共享 Dialog、STOMP 唤醒、平台资产与交易 smoke；只增加独立 engagement smoke/保留服务和必要合同，不引入浏览器框架、第二套队列、状态库或通用导出层。

### Task 12 最终收口（2026-07-22）

- RED/GREEN：未修改断言、timeout 或删除测试；最小组合 3/3、完整 TradingLab targeted 123/123。credential sanitizer 保留 UTF-16 语义，根节点继续使用直接索引，仅在非根节点出度达到 16 时使用 JDK `Map`，15 MiB 高出度对抗用例在 5 秒预算内通过。
- 写入安全闭环：overflow、late-secret、跨 append/batch canary 与最终输出 canary 命中后均先封闭写会话、清除 durable evidence，并只允许固定泛化 terminal。fresh writer 在首次动作前执行 fenced `discardEvidence` 探测；commit 结果不确定后发生 fence takeover 时，新 fence 先原子清除整份 report evidence并永久 taint，后续即使继续写入也不能伪装为完整报告。
- 重放与最终字节边界：各 section 仅保留有界 `acceptedTail`、`durableTail` 和 durable source-sequence high-water；event replay 仍交由持久层校验幂等，但不替换真实 durable tail。最终报告用 immutable streaming matcher 扫描实际输出字节，覆盖 JSON framing、section key、冒号、括号、逗号、默认字段和转义后的 `modelVersion`，不物化完整报告。
- 存储与实库验证：新增窄、fenced、事务型 `discardEvidence` store seam，只删除 chunk/append ledger 并重置 report 状态与计数，没有新增 schema 或依赖。真实 PostgreSQL `TradingLabReportStorePostgresIT` 23/23，覆盖 ledger REPLAY/no-chunk/acknowledge/后续真实 tail 拒绝及 purge，0 failures、0 errors、0 skipped。
- 独立只读安全复核：核心实现 PASS、剩余 HIGH 0；确认 uncertain takeover、event replay high-water、最终实际字节流扫描均 fail closed。已知 MEDIUM 可用性取舍是动态低熵 secret 若等于固定 JSON framing，会安全拒绝 finalize 并保留 active writer 槽位；当前状态模型下直接驱逐会丢失仅存于内存的动态 secret 而转为 fail open，安全驱逐需另行引入 durable、不可下载 tombstone，因此本轮不做不安全的单纯 evict。
- 后端全量：`mvn "-Dapi.version=1.40" test` 真实运行 2843 tests、0 failures、0 errors、0 skipped，exit 0；没有跳过或删除测试。
- 最终前端与合同门禁：`frontend:check` exit 0（ui 58/58、frontend-core 235/235、web 461/461、视觉合同 17/17，typecheck/build、boundary/style/architecture、bundle budget、large-file audit 全绿）；admin 131/131 且 build 通过；engagement smoke contract 12/12；`contract:check` 与专用 dev/demo 后端上的 `contract:ci` 均 exit 0。OpenAPI/生成类型 current，demo wallet、asset ledger、account summary 与 BTCUSDT Spot 市价成交/限价撤单 smoke 通过。
- E2E 证据继续有效：`test-results/engagement-smoke-20260720-2210-full/engagement-smoke-20260720-2210-full-report.json` 为 PASS，14/14 场景、22 张 PC/Mobile 截图、58 项 cleanup；Task 9 的 5 张视觉产物位于 `test-results/task9-member-notices/`。
- Worktree 审计：最终 `git status --short` 482 项、unstaged 169、cached 175，与本会话开始时完全一致；本次只在既有业务内容上语义合并，没有 reset、checkout、删除、stage、commit 或 push 用户改动，也没有连接真实 broker、FIX、LP。
- Ponytail：复用现有 scanner、writer 和 MyBatis store，只增加有界 tail/high-water、最终输出流扫描、非根索引及窄 fenced purge seam；未新增依赖、schema、第二套报告管线或通用抽象。

## 浏览器验收矩阵

至少覆盖：

| 场景 | PC | Mobile |
|---|---:|---:|
| 指定用户立即活动 | 通过 | 通过 |
| 全部用户定时活动 | 通过 | 通过 |
| 每日/总数/间隔频控 | 通过 | 通过 |
| 三条连续队列与 CTA 终止 | 通过 | 通过 |
| 不再提醒跨刷新/设备 | 通过 | 通过 |
| 内容更新与当前弹窗不热更新 | 通过 | 通过 |
| 暂停/删除实时关闭 | 通过 | 通过 |
| 铃铛未读与最近消息 | 通过 | 直接进入页面 |
| 消息已读/未读/全部已读/隐藏 | 通过 | 通过 |
| 普通定时消息不自动弹窗 | 通过 | 通过 |
| 长正文、图片、三种尺寸 | 通过 | 通过 |
| 键盘与焦点恢复 | 通过 | 通过 |

视觉产物应写入任务专用 artifacts 或 test-results 目录，不覆盖其他任务报告。

## 严格完成条件

只有以下条件全部满足，才可以宣称完成：

- [x] Task 0 到 Task 12 全部完成并勾选。
- [x] Flyway 空库迁移和 V16 旧消息回填均有真实集成测试。
- [x] 没有 JPA repository。
- [x] 活动受众、时间、频控、队列、防重复和 opt-out 全部由后端权威执行。
- [x] 全站与指定用户的立即/定时活动均端到端可用。
- [x] 普通消息立即/定时发送、实时红点、读写和隐藏端到端可用。
- [x] 弹窗与同步消息共享当前内容，历史 revision 可审计。
- [x] 删除、恢复、暂停、结束语义与冻结合同一致。
- [x] richtext 是真实受限编辑器，不是 textarea 假实现。
- [x] HTML、图片和内部路由全部通过安全验证。
- [x] PC/Mobile 弹窗与消息中心全部通过视觉和交互验收。
- [x] WebSocket 只作唤醒；断线、重连和事件丢失不破坏权威状态。
- [x] scheduler 默认关闭，生产显式开启。
- [x] 所有 Admin 接口具有后端权限校验。
- [x] 用户级明细无导出入口，查看行为有审计。
- [x] OpenAPI、共享类型、admin、web、frontend-core、ui、architecture、backend 全绿。
- [x] 没有删除、覆盖、暂存或提交范围外用户改动。
- [x] 没有连接真实 broker、FIX、LP。
- [x] 最终答复列出所有实际测试命令和真实结果，不用“应该通过”替代证据。

## 会话中断恢复

新会话被中断或压缩后：

1. 重新完整读取 AGENTS.md 与本文件。
2. 执行 Worktree Reconciliation Protocol。
3. 检查本文件 checkbox、git diff、测试结果和最近日志。
4. 运行最近一个已勾选 Task 的最小验证，证明 checkpoint 仍成立。
5. 从第一个未勾选步骤继续，不重做已证明完成的工作。
6. 如果 checkbox 与代码不一致，以代码、测试和数据库迁移证据为准，并纠正文档状态。

## 最终交付报告格式

最终答复必须包括：

1. 实现结果摘要。
2. Task 0 到 Task 12 完成表。
3. 新增和修改的主要文件。
4. Flyway 版本与旧消息迁移结果。
5. Admin 与 User API 清单。
6. 权限目录与审计覆盖。
7. 实际运行的测试命令、退出码和结果。
8. PC/Mobile 视觉 QA 产物路径。
9. 当前仍存在但属于范围外的用户改动。
10. 尚存风险或明确没有完成的事项；不得隐藏失败。
