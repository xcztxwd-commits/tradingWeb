import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const pagesDir = dirname(fileURLToPath(import.meta.url))
const srcDir = join(pagesDir, '..')
const webRoot = join(srcDir, '..')
const sharedHomeDir = join(srcDir, 'shared-widgets', 'home')

const homeSources = [
  readFileSync(join(sharedHomeDir, 'HomeContent.tsx'), 'utf8'),
  readFileSync(join(sharedHomeDir, 'HomeHeroGuest.tsx'), 'utf8'),
  readFileSync(join(sharedHomeDir, 'MarketPreviewPanel.tsx'), 'utf8'),
  readFileSync(join(sharedHomeDir, 'HomeSupportSections.tsx'), 'utf8'),
  readFileSync(join(srcDir, 'routes', 'home', 'useHomeRouteController.ts'), 'utf8')
].join('\n')
const marketsSource = readFileSync(join(pagesDir, 'markets', 'MarketsPage.tsx'), 'utf8')
const authSource = readFileSync(join(srcDir, 'shared-widgets', 'auth', 'AuthPageContent.tsx'), 'utf8')
const loginSource = readFileSync(join(srcDir, 'routes', 'auth', 'useAuthRouteController.ts'), 'utf8')
const accountSource = [
  readFileSync(join(srcDir, 'shared-widgets', 'account', 'AccountPagesContent.tsx'), 'utf8'),
  readFileSync(join(srcDir, 'routes', 'account', 'AccountRoutes.tsx'), 'utf8'),
  readFileSync(join(pagesDir, 'account', 'AccountHubPage.tsx'), 'utf8')
].join('\n')
const shellSource = [
  readFileSync(join(srcDir, 'app', 'AppShell.tsx'), 'utf8'),
  readFileSync(join(srcDir, 'pc', 'shell', 'PcShellChrome.tsx'), 'utf8'),
  readFileSync(join(srcDir, 'mobile', 'shell', 'MobileShellChrome.tsx'), 'utf8')
].join('\n')
const zhLocale = readFileSync(join(srcDir, 'i18n', 'locales', 'zh-CN.ts'), 'utf8')
const styles = readFileSync(join(srcDir, 'styles.css'), 'utf8')
const themeStyles = readFileSync(join(webRoot, '..', '..', 'packages', 'ui', 'src', 'theme', 'theme.css'), 'utf8')
const homeStyles = readFileSync(join(sharedHomeDir, 'HomeContent.module.css'), 'utf8')
const authStyles = readFileSync(join(srcDir, 'shared-widgets', 'auth', 'AuthPageContent.module.css'), 'utf8')
const tradingStyles = readFileSync(join(pagesDir, 'trading', 'TradingPage.module.css'), 'utf8')

const mojibakePattern =
  /鐢ㄦ埛|琛屾儏|鐧诲綍|娉ㄥ唽|閭|鎵嬫満|璧勯噾|鎬昏|浜ゆ槗|鍏呭€|鎻愮幇|甯傚€|鍔犲瘑|韬唤|甯歌/

describe('HTML export fidelity baseline', () => {
  it('uses readable Chinese copy on the imported prototype surfaces', () => {
    const targetText = [homeSources, marketsSource, authSource, loginSource, accountSource, shellSource].join('\n')

    for (const text of [
      '用户的共同选择',
      '邮箱/手机号',
      '热门',
      '新币',
      '查看全部350多个代币',
      '资金受 SAFU 保护',
      '常见问题',
      '加密货币市场 | 币价和市值',
      '总览',
      '交易数据',
      'AI 精选',
      '代币解锁',
      '24h成交量',
      '市值',
      '创建 FX Trader 账户',
      '返回登录',
      '身份认证',
      '资金流水',
      '交易订单'
    ]) {
      assert.match(targetText, new RegExp(escapeRegExp(text)), `missing readable copy: ${text}`)
    }

    assert.doesNotMatch(targetText, mojibakePattern)
    assert.match(zhLocale, /home:\s*'首页'/)
    assert.match(zhLocale, /markets:\s*'行情'/)
    assert.match(zhLocale, /wallet:\s*'资金'/)
    assert.match(zhLocale, /account:\s*'我的'/)
    assert.match(zhLocale, /loginTitle:\s*'专业交易终端登录'/)
    assert.match(zhLocale, /entryTitle:\s*'账户入口'/)
  })

  it('keeps the Binance-like structure without importing exported scripts', () => {
    assert.match(styles, /--bn-bg:\s*var\(--theme-background\)/)
    assert.match(styles, /--color-BtnBg:\s*var\(--theme-primary-hover\)/)
    assert.match(styles, /\.market-summary-grid/)
    assert.match(styles, /\.market-table/)
    assert.match(styles, /\.account-sidebar/)
    assert.match(styles, /\.account-dashboard-layout/)
    assert.match(authStyles, /\.shell\s*{[\s\S]*grid-template-columns:\s*minmax\(0,\s*520px\) 425px/)
    assert.match(authStyles, /\.form\s*{[\s\S]*width:\s*425px/)
    assert.doesNotMatch([homeSources, marketsSource, authSource, accountSource].join('\n'), /gtm\.js|captcha\.min\.js|common-widget|bnbstatic/)
  })

  it('uses the reference BinanceNova font and theme palette across the core web CSS', () => {
    const coreCss = [styles, themeStyles, homeStyles, authStyles, tradingStyles].join('\n')

    for (const weight of ['Regular', 'Medium', 'SemiBold', 'Bold']) {
      assert.equal(
        existsSync(join(webRoot, 'public', 'fonts', `BinanceNova-${weight}.woff2`)),
        true,
        `missing BinanceNova-${weight}.woff2`
      )
    }

    assert.match(styles, /@font-face\s*{[\s\S]*font-family:\s*"BinanceNova"[\s\S]*BinanceNova-Regular\.woff2/)
    assert.match(styles, /font-family:\s*BinanceNova,\s*Arial/)
    assert.match(themeStyles, /--font-ui:\s*BinanceNova,\s*Arial/)
    assert.match(authStyles, /font-family:\s*BinanceNova,\s*Arial/)
    assert.match(tradingStyles, /font-family:\s*BinanceNova,\s*Arial/)
    assert.doesNotMatch(coreCss, /\bInter\b/)

    for (const token of [
      '--theme-background: #181a20',
      '--theme-surface: #181a20',
      '--theme-surface-elevated: #1e2329',
      '--theme-border: #2b3139',
      '--theme-text-primary: #eaecef',
      '--theme-text-secondary: #929aa5',
      '--theme-text-muted: #707a8a',
      '--theme-primary: #f0b90b',
      '--theme-primary-hover: #fcd535',
      '--theme-accent: #fcd535',
      '--theme-success: #2ebd85',
      '--theme-buy: #2ebd85',
      '--theme-danger: #f6465d',
      '--theme-sell: #f6465d',
      '--theme-chart-grid: #2b3139'
    ]) {
      assert.match(themeStyles, new RegExp(escapeRegExp(token)))
    }

    for (const token of [
      '--theme-background: #f6f8fb',
      '--theme-surface: #ffffff',
      '--theme-text-primary: #111827',
      '--theme-primary: #111827',
      '--theme-chart-grid: #e8edf4'
    ]) {
      assert.match(themeStyles, new RegExp(escapeRegExp(token)))
    }
  })
})

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
