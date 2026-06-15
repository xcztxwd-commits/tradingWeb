import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const pagePath = join(currentDir, 'HomePage.tsx')
const stylesPath = join(currentDir, 'HomePage.module.css')
const homeComponentsDir = join(currentDir, 'components')
const homeHooksDir = join(currentDir, 'hooks')

describe('prototype home page source', () => {
  it('splits the homepage into explicit guest, unverified and verified states', () => {
    assert.equal(existsSync(pagePath), true)
    const source = readFileSync(pagePath, 'utf8')

    assert.match(source, /HomeHeroGuest/)
    assert.match(source, /HomeHeroUnverified/)
    assert.match(source, /HomeHeroVerified/)
    assert.match(source, /useHomeAuthVariant/)
    assert.match(source, /useHomeCounters/)
    assert.match(source, /MarketPreviewPanel/)
    assert.match(source, /NewsPreviewPanel/)
    assert.match(source, /TrustAwardsStrip/)
    assert.match(source, /HomeSupportSections/)
    assert.doesNotMatch(source, /featureEntries/)
  })

  it('keeps the guest conversion path minimal and account paths under account center', () => {
    const source = [
      readFileSync(pagePath, 'utf8'),
      readFileSync(join(homeComponentsDir, 'HomeHeroGuest.tsx'), 'utf8'),
      readFileSync(join(homeComponentsDir, 'HomeHeroUnverified.tsx'), 'utf8'),
      readFileSync(join(homeComponentsDir, 'HomeHeroVerified.tsx'), 'utf8')
    ].join('\n')

    assert.match(source, /to="\/register"/)
    assert.match(source, /to="\/markets"/)
    assert.match(source, /to="\/trading"/)
    assert.match(source, /to="\/account\/security\/kyc"/)
    assert.match(source, /to="\/account\/assets"/)
    assert.doesNotMatch(source, /\/dashboard|\/orders|\/positions|\/wallet|\/security"|\/settings"/)
  })

  it('adds small reusable home components and backend counter boundary', () => {
    const requiredFiles = [
      join(homeComponentsDir, 'AnimatedCounter.tsx'),
      join(homeComponentsDir, 'HomeHeroGuest.tsx'),
      join(homeComponentsDir, 'HomeHeroUnverified.tsx'),
      join(homeComponentsDir, 'HomeHeroVerified.tsx'),
      join(homeComponentsDir, 'MarketPreviewPanel.tsx'),
      join(homeComponentsDir, 'NewsPreviewPanel.tsx'),
      join(homeComponentsDir, 'TrustAwardsStrip.tsx'),
      join(homeHooksDir, 'useHomeAuthVariant.ts'),
      join(homeHooksDir, 'useHomeCounters.ts')
    ]

    for (const file of requiredFiles) {
      assert.equal(existsSync(file), true, `${file} should exist`)
    }

    const counterHook = readFileSync(join(homeHooksDir, 'useHomeCounters.ts'), 'utf8')
    assert.match(counterHook, /getHomeCounters/)
    assert.match(counterHook, /setInterval/)
    assert.match(counterHook, /1000/)
  })

  it('uses restrained black-gold styling, not decorative device mockups', () => {
    assert.equal(existsSync(stylesPath), true)
    const styles = readFileSync(stylesPath, 'utf8')

    assert.match(styles, /\.page\s*{[\s\S]*background:\s*#181a20/)
    assert.match(styles, /--home-accent:\s*#f0b90b/)
    assert.match(styles, /--home-button:\s*#fcd535/)
    assert.match(styles, /\.heroGrid\s*{[\s\S]*max-width:\s*1200px/)
    assert.match(styles, /\.heroGrid\s*{[\s\S]*grid-template-columns:\s*588px\s+432px/)
    assert.match(styles, /\.heroTitle\s*{[\s\S]*font-size:\s*64px[\s\S]*line-height:\s*80px[\s\S]*font-weight:\s*600/)
    assert.match(styles, /\.primaryCta\s*{[\s\S]*min-height:\s*48px[\s\S]*border-radius:\s*8px/)
    assert.match(styles, /\.appDownloadRow/)
    assert.match(styles, /\.supportGrid/)
    assert.match(styles, /\.counterTick\s*{[\s\S]*animation:\s*counterPulse/)
    assert.match(styles, /\.panelCard:hover/)
    assert.match(styles, /@media \(prefers-reduced-motion:\s*reduce\)/)
    assert.match(styles, /@media \(max-width:\s*760px\)[\s\S]*\.heroGrid\s*{[\s\S]*grid-template-columns:\s*1fr/)
    assert.doesNotMatch(styles, /perspective:\s*1200px|phoneReflection|deviceDrift|scanSweep|radial-gradient\(circle/)
  })

  it('matches Binance home module rhythm without using protected brand assets', () => {
    const source = [
      readFileSync(pagePath, 'utf8'),
      readFileSync(join(homeComponentsDir, 'HomeHeroGuest.tsx'), 'utf8'),
      readFileSync(join(homeComponentsDir, 'MarketPreviewPanel.tsx'), 'utf8'),
      readFileSync(join(homeComponentsDir, 'NewsPreviewPanel.tsx'), 'utf8'),
      readFileSync(join(homeComponentsDir, 'HomeSupportSections.tsx'), 'utf8')
    ].join('\n')

    assert.match(source, /用户的共同选择/)
    assert.match(source, /KYC/)
    assert.match(source, /account\/security\/kyc/)
    assert.match(source, /邮箱\/手机号码/)
    assert.match(source, /热门/)
    assert.match(source, /新币/)
    assert.match(source, /查看全部350多个代币/)
    assert.match(source, /资金受 SAFU 保护/)
    assert.match(source, /常见问题/)
    assert.doesNotMatch(source, /binance\.com\/static|Binance logo|BinanceNova/)
  })
})
