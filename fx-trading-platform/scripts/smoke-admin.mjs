const baseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080'
const adminEmail = process.env.ADMIN_SMOKE_EMAIL ?? 'admin-smoke@example.com'
const adminPassword = process.env.ADMIN_SMOKE_PASSWORD ?? 'Password123!'
const runId = process.env.ADMIN_SMOKE_RUN_ID ?? String(Date.now())
const userEmail = process.env.ADMIN_SMOKE_USER_EMAIL ?? `admin-smoke-user+${runId}@example.com`
const userPassword = process.env.ADMIN_SMOKE_USER_PASSWORD ?? 'Password123!'
const ADMIN_ACTION_AUTHORITIES = [
  'market:symbol:create',
  'market:symbol:update',
  'market:symbol:disable',
  'market:data-provider:update',
  'finance:fund-order:approve',
  'finance:fund-order:reject',
  'finance:adjustment:create',
  'trading:order:cancel',
  'trading:position:force-close',
  'user:update',
  'user:disable',
  'user:force-logout'
]

const results = []
const context = { runId, adminEmail, userEmail }
let adminToken = ''
let userToken = ''

await step('actuator health is reachable', async () => {
  const health = await rawJson('/actuator/health')
  assert(health.status === 'UP', 'Actuator health must be UP')
  return { status: health.status }
})

await step('admin can login', async () => {
  const auth = await api('/api/auth/login', {
    method: 'POST',
    body: { email: adminEmail, password: adminPassword }
  })
  assert(auth.role === 'ADMIN', 'Admin login must return ADMIN role')
  assert(auth.accessToken, 'Admin login must return access token')
  context.adminUserId = auth.userId
  adminToken = auth.accessToken
  return { adminUserId: auth.userId }
})

await step('admin authenticated profile is available', async () => {
  const me = await api('/api/auth/me', { token: adminToken })
  assert(me.email === adminEmail, 'Admin profile email must match smoke admin')
  assert(me.role === 'ADMIN', 'Admin profile must have ADMIN role')
  return { email: me.email, role: me.role }
})

await step('admin action authorities are seeded through RBAC buttons', async () => {
  const role = await api('/api/admin/rbac/roles', {
    method: 'POST',
    token: adminToken,
    body: {
      name: `Smoke Action Role ${runId}`,
      code: `smoke_action_${runId}`,
      enabled: true,
      sortOrder: 1,
      description: 'admin action smoke'
    }
  })
  const menu = await api('/api/admin/rbac/menus', {
    method: 'POST',
    token: adminToken,
    body: {
      parentId: null,
      name: `Smoke Action Menu ${runId}`,
      permissionKey: `smoke:admin-action:${runId}`,
      path: `/smoke/action/${runId}`,
      component: 'SmokeActionPage',
      menuType: 'MENU',
      enabled: true,
      sortOrder: 1
    }
  })
  await api(`/api/admin/rbac/roles/${role.id}/menu-permissions`, {
    method: 'PUT',
    token: adminToken,
    body: { menuId: menu.id, buttons: ADMIN_ACTION_AUTHORITIES }
  })
  await api('/api/admin/rbac/user-roles', {
    method: 'POST',
    token: adminToken,
    body: { userId: context.adminUserId, roleId: role.id }
  })

  const refreshed = await api('/api/auth/login', {
    method: 'POST',
    body: { email: adminEmail, password: adminPassword }
  })
  assert(refreshed.accessToken, 'Admin relogin must return access token')
  assert(Array.isArray(refreshed.authorities), 'Admin relogin must return authorities')
  for (const authority of ADMIN_ACTION_AUTHORITIES) {
    assert(refreshed.authorities.includes(authority), `Admin authorities must include ${authority}`)
  }
  adminToken = refreshed.accessToken
  return { roleId: role.id, menuId: menu.id, authorities: ADMIN_ACTION_AUTHORITIES.length }
})

await step('regular user can register and load profile', async () => {
  const auth = await api('/api/auth/register', {
    method: 'POST',
    body: { email: userEmail, phone: null, password: userPassword }
  })
  assert(auth.role === 'USER', 'Register must return USER role')
  const me = await api('/api/auth/me', { token: auth.accessToken })
  assert(me.email === userEmail, 'Profile email must match registered user')
  context.userId = auth.userId
  userToken = auth.accessToken
  return { userId: auth.userId }
})

const account = await step('regular user can create and query demo account', async () => {
  let accounts = await api('/api/accounts', { token: userToken })
  assert(Array.isArray(accounts), 'Accounts endpoint must return a list')
  if (accounts.length === 0) {
    await api('/api/accounts/demo', { method: 'POST', body: {}, token: userToken })
    accounts = await api('/api/accounts', { token: userToken })
  }
  assert(accounts.length > 0, 'User must have at least one account after demo creation')
  const selected = accounts[0]
  const summary = await api(`/api/accounts/${selected.id}/summary`, { token: userToken })
  assert(summary.id === selected.id, 'Account summary must match selected account')
  context.accountId = selected.id
  return { accountId: selected.id, balance: selected.balance }
})

const market = await step('market symbols, quote, candles, order book and trades are usable', async () => {
  const symbols = await api('/api/market/symbols')
  assert(Array.isArray(symbols) && symbols.length > 0, 'Market symbols must be non-empty')
  const marketSymbol = symbols.find((item) => item.enabled && item.orderBookEnabled === true)
    ?? symbols.find((item) => item.enabled)
    ?? symbols[0]
  const tradingSymbol = symbols.find((item) => item.enabled && item.tradable && item.quoteCurrency === 'USD')
    ?? marketSymbol
  const quote = await api(`/api/market/quotes/${encodeURIComponent(marketSymbol.symbol)}`)
  assert(Number(quote.ask) > 0 && Number(quote.bid) > 0, 'Quote bid/ask must be positive')

  const to = new Date()
  const from = new Date(to.getTime() - 6 * 60 * 60 * 1000)
  const candles = await api(
    `/api/chart/candles?symbol=${encodeURIComponent(marketSymbol.symbol)}&timeframe=1h&from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`
  )
  assert(Array.isArray(candles), 'Chart candles must return a list')

  const orderBook = await api(`/api/market/order-book/${encodeURIComponent(marketSymbol.symbol)}`)
  assert(orderBook.bids && orderBook.asks, 'Order book must include bids and asks')
  const trades = await api(`/api/market/trades/${encodeURIComponent(marketSymbol.symbol)}?limit=5`)
  assert(Array.isArray(trades), 'Recent trades must return a list')
  const status = await api('/api/market/status')
  assert(status.status, 'Market status must include status')

  const tradingQuote = tradingSymbol.symbol === marketSymbol.symbol
    ? quote
    : await api(`/api/market/quotes/${encodeURIComponent(tradingSymbol.symbol)}`)
  assert(Number(tradingQuote.ask) > 0 && Number(tradingQuote.bid) > 0, 'Trading quote bid/ask must be positive')
  context.symbol = tradingSymbol.symbol
  context.marketSymbol = marketSymbol.symbol
  context.quoteMid = tradingQuote.mid
  return {
    marketSymbol: marketSymbol.symbol,
    tradingSymbol: tradingSymbol.symbol,
    candles: candles.length,
    trades: trades.length,
    status: status.status
  }
})

const order = await step('regular user can create, list and inspect trading resources', async () => {
  const idempotencyKey = `codex-order-${runId}`
  const requestedPrice = stableBuyLimitPrice(context.quoteMid)
  const created = await api('/api/trading/orders', {
    method: 'POST',
    token: userToken,
    body: {
      accountId: account.accountId,
      symbol: context.symbol,
      side: 'BUY',
      orderType: 'LIMIT',
      lots: '0.01',
      requestedPrice,
      idempotencyKey,
      clientOrderId: idempotencyKey
    }
  })
  assert(created.id, 'Created order must include id')
  const orders = await api('/api/trading/orders', { token: userToken })
  assert(orders.some((item) => item.id === created.id), 'Order list must include created order')
  const positions = await api(`/api/trading/positions?accountId=${encodeURIComponent(account.accountId)}`, { token: userToken })
  assert(Array.isArray(positions), 'Positions endpoint must return a list')
  const ledger = await api(`/api/ledger?accountId=${encodeURIComponent(account.accountId)}`, { token: userToken })
  assert(Array.isArray(ledger), 'Ledger endpoint must return a list')
  context.orderId = created.id
  context.orderRequestedPrice = requestedPrice
  return { orderId: created.id, status: created.status, requestedPrice, positions: positions.length, ledger: ledger.length }
})

const adminPages = await step('admin read endpoints return paged data', async () => {
  const dashboard = await api('/api/admin/dashboard/summary', { token: adminToken })
  assert(Number.isInteger(dashboard.userCount), 'Dashboard summary must include userCount')

  const pages = {
    users: await api('/api/admin/users?page=0&size=20', { token: adminToken }),
    accounts: await api('/api/admin/accounts?page=0&size=20', { token: adminToken }),
    orders: await api('/api/admin/trading/orders?page=0&size=20', { token: adminToken }),
    positions: await api('/api/admin/trading/positions?page=0&size=20', { token: adminToken }),
    trades: await api('/api/admin/trading/trades?page=0&size=20', { token: adminToken }),
    ledger: await api('/api/admin/finance/ledger?page=0&size=20', { token: adminToken }),
    symbols: await api('/api/admin/market/symbols?page=0&size=20', { token: adminToken }),
    riskConfigs: await api('/api/admin/risk/configs', { token: adminToken }),
    paymentMethods: await api('/api/admin/finance/payment-methods?page=0&size=20', { token: adminToken }),
    messages: await api('/api/admin/content/messages?page=0&size=20', { token: adminToken }),
    articles: await api('/api/admin/content/articles?page=0&size=20', { token: adminToken }),
    dictionaries: await api('/api/admin/config/dictionaries?page=0&size=20', { token: adminToken }),
    settings: await api('/api/admin/config/settings?page=0&size=20', { token: adminToken }),
    auditLogs: await api('/api/admin/audit-logs?page=0&size=20', { token: adminToken })
  }

  for (const [name, page] of Object.entries(pages)) {
    if (name === 'riskConfigs') {
      continue
    }
    assertPage(page, name)
  }
  assert(Array.isArray(pages.riskConfigs), 'Admin risk configs must return a list')
  assert(pages.users.items.some((item) => item.id === context.userId), 'Admin users must include registered user')
  assert(pages.accounts.items.some((item) => item.id === context.accountId), 'Admin accounts must include demo account')
  assert(pages.orders.items.some((item) => item.id === context.orderId), 'Admin orders must include created order')
  context.symbolId = pages.symbols.items[0]?.id
  assert(context.symbolId, 'Admin symbols must include symbol id')
  return {
    users: pages.users.total,
    accounts: pages.accounts.total,
    orders: pages.orders.total,
    trades: pages.trades.total,
    symbols: pages.symbols.total,
    riskConfigs: pages.riskConfigs.length
  }
})

await step('admin user management commands write and audit', async () => {
  const targetUserId = context.userId
  const frozen = await api(`/api/admin/users/${targetUserId}/status`, {
    method: 'PATCH',
    token: adminToken,
    body: { status: 'FROZEN', reason: `codex smoke freeze ${runId}` }
  })
  assert(frozen.status === 'FROZEN', 'User status must become FROZEN')

  const active = await api(`/api/admin/users/${targetUserId}/status`, {
    method: 'PATCH',
    token: adminToken,
    body: { status: 'ACTIVE', reason: `codex smoke restore ${runId}` }
  })
  assert(active.status === 'ACTIVE', 'User status must be restored to ACTIVE')

  const kyc = await api(`/api/admin/users/${targetUserId}/kyc-review`, {
    method: 'POST',
    token: adminToken,
    body: { kycStatus: 'APPROVED', reason: `codex smoke kyc ${runId}`, reviewNote: 'e2e verified' }
  })
  assert(kyc.kycStatus === 'APPROVED', 'KYC status must be APPROVED')

  const risk = await api(`/api/admin/users/${targetUserId}/risk-level`, {
    method: 'PATCH',
    token: adminToken,
    body: { riskLevel: 'MEDIUM', reason: `codex smoke risk ${runId}` }
  })
  assert(risk.riskLevel === 'MEDIUM', 'Risk level must be MEDIUM')

  const note = await api(`/api/admin/users/${targetUserId}/notes`, {
    method: 'POST',
    token: adminToken,
    body: { note: `codex smoke note ${runId}` }
  })
  assert(note.id, 'User note must be created')

  await api(`/api/admin/users/${targetUserId}/force-logout`, {
    method: 'POST',
    token: adminToken,
    body: { reason: `codex smoke force logout ${runId}` }
  })
  return { targetUserId }
})

await step('admin market commands write and audit', async () => {
  const updated = await api(`/api/admin/market/symbols/${context.symbolId}/status`, {
    method: 'PATCH',
    token: adminToken,
    body: { enabled: true, reason: `codex smoke symbol status ${runId}` }
  })
  assert(updated.enabled === true, 'Symbol must be enabled')

  const adjustment = await api(`/api/admin/market/symbols/${context.symbolId}/price-adjustments`, {
    method: 'POST',
    token: adminToken,
    body: {
      mode: 'PRICE_REPAIR',
      adjustmentType: 'SET_MID_PRICE',
      targetPrice: String(context.quoteMid),
      startsAt: new Date(Date.now() + 60_000).toISOString(),
      endsAt: new Date(Date.now() + 120_000).toISOString(),
      reason: `codex smoke price adjustment ${runId}`
    }
  })
  assert(adjustment.id, 'Price adjustment must be created')
  return { symbolId: context.symbolId, adjustmentId: adjustment.id }
})

await step('admin trading commands can cancel pending order', async () => {
  const canceled = await api(`/api/admin/trading/orders/${context.orderId}/cancel`, {
    method: 'POST',
    token: adminToken,
    body: { reason: `codex smoke cancel order ${runId}`, idempotencyKey: `codex-cancel-${runId}` }
  })
  assert(['CANCELED', 'CANCELLED'].includes(canceled.status), 'Order must be canceled by admin')
  return { orderId: context.orderId, status: canceled.status }
})

await step('admin finance commands write ledger and payment methods', async () => {
  const method = await api('/api/admin/finance/payment-methods', {
    method: 'POST',
    token: adminToken,
    body: {
      name: `Codex Bank ${runId}`,
      methodType: 'BANK_TRANSFER',
      currency: 'USD',
      enabled: true,
      displayOrder: 10,
      instructions: 'codex smoke test'
    }
  })
  assert(method.id, 'Payment method must be created')

  const updatedMethod = await api(`/api/admin/finance/payment-methods/${method.id}`, {
    method: 'PATCH',
    token: adminToken,
    body: {
      name: `Codex Bank Updated ${runId}`,
      methodType: 'BANK_TRANSFER',
      currency: 'USD',
      enabled: true,
      displayOrder: 11,
      instructions: 'codex smoke test updated'
    }
  })
  assert(updatedMethod.name.includes('Updated'), 'Payment method must be updated')

  const deposit = await api(`/api/admin/finance/accounts/${context.accountId}/deposit`, {
    method: 'POST',
    token: adminToken,
    body: {
      amount: '25.00',
      reason: `codex smoke deposit ${runId}`,
      paymentMethodId: method.id,
      note: 'e2e deposit',
      idempotencyKey: `codex-deposit-${runId}`
    }
  })
  assert(deposit.id, 'Deposit operation must be recorded')

  const withdraw = await api(`/api/admin/finance/accounts/${context.accountId}/withdraw`, {
    method: 'POST',
    token: adminToken,
    body: {
      amount: '1.00',
      reason: `codex smoke withdraw ${runId}`,
      paymentMethodId: method.id,
      note: 'e2e withdrawal',
      idempotencyKey: `codex-withdraw-${runId}`
    }
  })
  assert(withdraw.id, 'Withdrawal operation must be recorded')

  const adjustment = await api(`/api/admin/finance/accounts/${context.accountId}/adjustments`, {
    method: 'POST',
    token: adminToken,
    body: {
      delta: '-0.50',
      reason: `codex smoke balance adjustment ${runId}`,
      note: 'e2e adjustment',
      idempotencyKey: `codex-adjust-${runId}`,
      confirmationText: 'CONFIRM_ADJUSTMENT'
    }
  })
  assert(adjustment.id, 'Balance adjustment operation must be recorded')

  const ledger = await api('/api/admin/finance/ledger?page=0&size=20', { token: adminToken })
  assertPage(ledger, 'admin ledger after finance commands')
  assert(ledger.total >= 3, 'Admin ledger must include finance command entries')
  return { paymentMethodId: method.id, depositId: deposit.id, withdrawalId: withdraw.id, adjustmentId: adjustment.id }
})

await step('admin content and config commands write data', async () => {
  const message = await api('/api/admin/content/messages', {
    method: 'POST',
    token: adminToken,
    body: {
      targetUserId: context.userId,
      title: `Codex smoke message ${runId}`,
      body: 'Generated by local admin smoke test.',
      messageType: 'SYSTEM',
      status: 'PUBLISHED'
    }
  })
  assert(message.id, 'Message must be created')

  const article = await api('/api/admin/content/articles', {
    method: 'POST',
    token: adminToken,
    body: {
      articleType: 'ANNOUNCEMENT',
      title: `Codex smoke article ${runId}`,
      summary: 'Local smoke test announcement.',
      body: 'Generated by local admin smoke test.',
      status: 'PUBLISHED',
      language: 'zh-CN',
      sortOrder: 1
    }
  })
  assert(article.id, 'Article must be created')

  const dictionary = await api('/api/admin/config/dictionaries', {
    method: 'PUT',
    token: adminToken,
    body: {
      groupKey: 'codex_smoke',
      itemKey: `run_${runId}`,
      itemValue: 'enabled',
      enabled: true,
      displayOrder: 1,
      description: 'codex smoke test'
    }
  })
  assert(dictionary.id, 'Dictionary item must be upserted')

  const setting = await api('/api/admin/config/settings', {
    method: 'PUT',
    token: adminToken,
    body: {
      settingKey: `codex.smoke.${runId}`,
      settingValue: 'true',
      valueType: 'BOOLEAN',
      description: 'codex smoke test',
      editable: true
    }
  })
  assert(setting.id, 'System setting must be upserted')
  return { messageId: message.id, articleId: article.id, dictionaryId: dictionary.id, settingId: setting.id }
})

await step('CORS preflight allows PUT config APIs', async () => {
  const response = await fetch(`${baseUrl}/api/admin/config/settings`, {
    method: 'OPTIONS',
    headers: {
      Origin: 'http://localhost:5173',
      'Access-Control-Request-Method': 'PUT',
      'Access-Control-Request-Headers': 'authorization,content-type'
    }
  })
  const allowedMethods = response.headers.get('access-control-allow-methods') ?? ''
  assert(response.ok, `CORS preflight must be ok, got ${response.status}`)
  assert(allowedMethods.includes('PUT'), 'CORS allow-methods must include PUT')
  return { allowedMethods }
})

await step('WH admin gap modules use real MyBatis APIs', async () => {
  const role = await api('/api/admin/rbac/roles', {
    method: 'POST',
    token: adminToken,
    body: { name: `Smoke Role ${runId}`, code: `smoke_role_${runId}`, enabled: true, sortOrder: 1, description: 'smoke' }
  })
  const menu = await api('/api/admin/rbac/menus', {
    method: 'POST',
    token: adminToken,
    body: {
      parentId: null,
      name: `Smoke Menu ${runId}`,
      permissionKey: `smoke:menu:${runId}`,
      path: `/smoke/${runId}`,
      component: 'SmokePage',
      menuType: 'MENU',
      enabled: true,
      sortOrder: 1
    }
  })
  await api(`/api/admin/rbac/roles/${role.id}/menu-permissions`, {
    method: 'PUT',
    token: adminToken,
    body: { menuId: menu.id, buttons: ['view', 'export'] }
  })
  await api('/api/admin/rbac/user-roles', {
    method: 'POST',
    token: adminToken,
    body: { userId: context.adminUserId, roleId: role.id }
  })
  await api(`/api/admin/rbac/roles/${role.id}/data-scope`, {
    method: 'PUT',
    token: adminToken,
    body: { scopeType: 'ALL', departmentIds: [] }
  })

  const category = await api('/api/admin/market/categories', {
    method: 'POST',
    token: adminToken,
    body: { name: `Smoke Category ${runId}`, code: `SMOKE_${runId}`, sortOrder: 1, enabled: true }
  })
  const risk = await api('/api/admin/risk/configs', {
    method: 'POST',
    token: adminToken,
    body: {
      symbol: `S${String(runId).slice(-10)}`,
      maxLeverage: 100,
      maxLots: '10.0000',
      marginCallLevel: '120.0000',
      stopOutLevel: '60.0000',
      enabled: true,
      reason: 'smoke'
    }
  })
  await api(`/api/admin/risk/configs/${risk.id}`, {
    method: 'DELETE',
    token: adminToken,
    body: { reason: 'smoke cleanup' }
  })

  const profile = await api(`/api/admin/members/${context.userId}/profile`, {
    method: 'PUT',
    token: adminToken,
    body: { realName: `Smoke User ${runId}`, phone: '0800000000', address: 'Tokyo', remark: 'smoke' }
  })
  const kyc = await api(`/api/admin/members/${context.userId}/kyc-applications`, {
    method: 'POST',
    token: adminToken,
    body: { realName: `Smoke User ${runId}`, documentType: 'PASSPORT', documentNo: `P${runId}`, frontImageUrl: 'front.png', backImageUrl: 'back.png' }
  })
  await api(`/api/admin/members/kyc-applications/${kyc.id}/review`, {
    method: 'POST',
    token: adminToken,
    body: { status: 'APPROVED', reason: 'smoke' }
  })
  const paymentAccount = await api(`/api/admin/members/${context.userId}/payment-accounts`, {
    method: 'POST',
    token: adminToken,
    body: {
      accountType: 'BANK',
      currency: 'USD',
      network: 'BANK',
      holderName: `Smoke User ${runId}`,
      bankName: 'Smoke Bank',
      branchName: 'Smoke Branch',
      bankCode: '001',
      accountNo: `SMOKE-${runId}`,
      enabled: true
    }
  })

  const codeLog = await api('/api/admin/logs/verification-codes', {
    method: 'POST',
    token: adminToken,
    body: { scene: 'LOGIN', account: context.userEmail, channel: 'EMAIL', code: '123456', status: 'SENT', errorMessage: null }
  })
  const requestLogs = await api('/api/admin/logs/request-logs?size=20', { token: adminToken })
  assert(Array.isArray(requestLogs) && requestLogs.length > 0, 'Request logs must return rows')

  const preference = await api('/api/admin/table-tools/preferences/system-roles', {
    method: 'PUT',
    token: adminToken,
    body: { hiddenColumns: ['createdAt'], tableSize: 'small', showBorder: true, zebra: false }
  })
  const exportTask = await api('/api/admin/table-tools/export-tasks', {
    method: 'POST',
    token: adminToken,
    body: { pageKey: 'system-roles', filterJson: '{}' }
  })
  const importTask = await api('/api/admin/table-tools/import-tasks', {
    method: 'POST',
    token: adminToken,
    body: { pageKey: 'system-roles', fileName: `roles-${runId}.xlsx` }
  })
  const batch = await api('/api/admin/table-tools/batch-operations', {
    method: 'POST',
    token: adminToken,
    body: { pageKey: 'system-roles', operation: 'delete', rowIds: [role.id], reason: 'smoke' }
  })

  return {
    roleId: role.id,
    menuId: menu.id,
    categoryId: category.id,
    profileId: profile.id,
    paymentAccountId: paymentAccount.id,
    verificationCodeLogId: codeLog.id,
    preferenceId: preference.id,
    exportTaskId: exportTask.id,
    importTaskId: importTask.id,
    batchId: batch.id
  }
})

await step('audit log records admin write commands', async () => {
  const auditLogs = await api('/api/admin/audit-logs?page=0&size=100', { token: adminToken })
  assertPage(auditLogs, 'audit logs')
  const actions = new Set(auditLogs.items.map((log) => log.action))
  for (const action of [
    'ADMIN_USER_STATUS_UPDATE',
    'ADMIN_USER_KYC_REVIEW',
    'ADMIN_USER_RISK_LEVEL_UPDATE',
    'ADMIN_USER_NOTE_CREATE',
    'ADMIN_ORDER_CANCEL',
    'ADMIN_FINANCE_DEPOSIT',
    'ADMIN_PAYMENT_METHOD_CREATE',
    'ADMIN_MESSAGE_CREATE',
    'ADMIN_ARTICLE_CREATE',
    'ADMIN_DICTIONARY_UPSERT',
    'ADMIN_SETTING_UPDATE'
  ]) {
    assert(actions.has(action), `Audit log must contain ${action}`)
  }
  return { auditEvents: auditLogs.total }
})

console.log(JSON.stringify({ baseUrl, context, results }, null, 2))

async function step(name, fn) {
  const startedAt = Date.now()
  try {
    const details = await fn()
    const result = { name, status: 'PASS', durationMs: Date.now() - startedAt, details }
    results.push(result)
    return details
  } catch (error) {
    const result = {
      name,
      status: 'FAIL',
      durationMs: Date.now() - startedAt,
      error: error instanceof Error ? error.message : String(error)
    }
    results.push(result)
    console.error(JSON.stringify({ baseUrl, context, results }, null, 2))
    throw error
  }
}

async function api(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  })

  const payload = await parseJsonResponse(response)
  if (!response.ok || !payload.success) {
    throw new Error(payload.message ?? `Request failed: ${response.status}`)
  }
  return payload.data
}

async function rawJson(path) {
  const response = await fetch(`${baseUrl}${path}`)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
  return parseJsonResponse(response)
}

async function parseJsonResponse(response) {
  const text = await response.text()
  try {
    return text ? JSON.parse(text) : {}
  } catch (error) {
    throw new Error(`Expected JSON response from ${response.url}, got: ${text.slice(0, 120)}`)
  }
}

function assertPage(page, name) {
  assert(page && Array.isArray(page.items), `${name} must return AdminPageResponse.items`)
  assert(Number.isInteger(page.page), `${name} must include page`)
  assert(Number.isInteger(page.size), `${name} must include size`)
  assert(typeof page.total === 'number', `${name} must include total`)
}

function stableBuyLimitPrice(mid) {
  const value = Number(mid)
  assert(Number.isFinite(value) && value > 0, 'Quote mid must be a positive number')
  return (value * 0.95).toFixed(value >= 10 ? 3 : 5)
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}
