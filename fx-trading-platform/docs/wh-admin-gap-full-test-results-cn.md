# WH 后台缺口补全测试结果

日期：2026-06-10

## 后端验证

- `mvn.cmd test`
  - 结果：通过
  - 覆盖：92 个测试，0 失败，0 错误

- `npm.cmd run verify:architecture`
  - 结果：通过
  - 输出：`Architecture verification passed.`

## API 验证

- `npm.cmd run smoke:admin`
  - 结果：通过
  - 覆盖：
    - 登录、注册、账户、行情、交易、后台分页
    - 用户管理、产品管理、订单、财务、内容、配置、审计
    - RBAC 角色/菜单/按钮权限/数据权限
    - 产品分类、风控配置
    - 会员资料、KYC、支付账户
    - 验证码日志、请求日志
    - 表格偏好、导出任务、导入任务、批量操作任务

## 前端验证

- `npm.cmd --workspace apps/admin run test`
  - 结果：通过
  - 覆盖：18 个测试，0 失败

- `npm.cmd --workspace apps/admin run build`
  - 结果：通过
  - 输出：Vite production build 成功，生成 `apps/admin/dist`

## 浏览器联调验证

已使用 Playwright 登录 `http://localhost:5174` 后验证以下页面：

- `/system/roles`
- `/system/menus`
- `/system/departments`
- `/system/posts`
- `/finance/recharge-orders`
- `/finance/withdrawal-orders`
- `/members/list`
- `/members/payment-accounts`
- `/products/categories`
- `/products/price-schedules`
- `/risk`
- `/logs/verification-codes`
- `/logs/request-logs`

联调结论：

- 页面均可正常渲染。
- 控制台无错误。
- 已捕获真实业务 API 200 响应，包括 `/api/admin/rbac/*`、`/api/admin/finance/fund-orders`、`/api/admin/members/payment-accounts`、`/api/admin/market/*`、`/api/admin/risk/configs`、`/api/admin/logs/*`、`/api/admin/table-tools/*`。

## 数据库迁移验证

后端启动日志确认 Flyway 已验证 22 个迁移，并成功到达 `v22 - admin table tools`。
