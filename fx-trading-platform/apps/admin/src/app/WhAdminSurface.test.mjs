import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const appSource = readSource('AdminApp.tsx')
const layoutSource = readSource('AdminLayout.tsx')
const menuSource = readSource('adminMenu.ts')
const featurePageSource = readSource('../pages/FeatureCrudPage.tsx')
const apiSource = readSource('../services/adminApi.ts')

function readSource(relativePath) {
  const absolutePath = join(currentDir, relativePath)
  return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

describe('WH screenshot admin surface', () => {
  it('declares grouped screenshot menus and routes through a generic feature page', () => {
    for (const label of ['首页', '权限', '产品管理', '财务管理', '用户管理', '订单管理', '系统设置']) {
      assert.ok(menuSource.includes(label), `${label} menu group is missing`)
    }

    for (const path of [
      '/system/users',
      '/system/roles',
      '/system/departments',
      '/system/menus',
      '/system/posts',
      '/products/list',
      '/products/categories',
      '/products/price-schedules',
      '/finance/recharge-orders',
      '/finance/withdrawal-orders',
      '/members/list',
      '/orders/history',
      '/logs/verification-codes',
      '/logs/request-logs',
      '/content/notices',
      '/content/news',
      '/content/member-notices',
      '/config/settings/site'
    ]) {
      assert.ok(menuSource.includes(path), `${path} route is missing from adminMenu.ts`)
    }

    assert.match(appSource, /<Route path="\/system\/users" element=\{<FeatureCrudPage pageKey="system-users" \/>\} \/>/)
    assert.match(appSource, /<Route path="\/finance\/recharge-orders" element=\{<FeatureCrudPage pageKey="recharge-orders" \/>\} \/>/)
    assert.match(appSource, /<Route path="\/config\/settings\/footer" element=\{<FeatureCrudPage pageKey="settings-footer" \/>\} \/>/)
  })

  it('contains screenshot shell features and CRUD controls', () => {
    for (const token of ['RouteTabs', 'BreadcrumbTrail', '表格设置', '全屏', '通知', '九宫格']) {
      assert.ok(layoutSource.includes(token), `${token} shell feature is missing`)
    }

    for (const token of ['搜索', '重置', '新增', '删除', '导入', '导出', '展开', '审核', '编辑', '保存', '关闭']) {
      assert.ok(featurePageSource.includes(token), `${token} CRUD token is missing`)
    }
  })

  it('wires frontend to backend feature catalog APIs', () => {
    assert.match(apiSource, /export function getFeaturePages\(/)
    assert.match(apiSource, /export function getFeaturePage\(/)
    assert.match(apiSource, /export function runFeatureAction\(/)
    assert.ok(apiSource.includes('/api/admin/features'))
  })

  it('drives feature table pagination filters and sorting through backend queries', () => {
    assert.match(featurePageSource, /queryFilters/)
    assert.match(featurePageSource, /setPage\(0\)/)
    assert.match(featurePageSource, /getFeaturePage\(token, pageKey, \{/)
    assert.match(featurePageSource, /sortField/)
    assert.match(featurePageSource, /sortDirection/)
    assert.match(featurePageSource, /setPageSize\(Number\(event\.target\.value\)\)/)
    assert.doesNotMatch(featurePageSource, /const filteredRows = useMemo/)
  })
})
