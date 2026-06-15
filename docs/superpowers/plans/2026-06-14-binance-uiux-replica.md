# Binance UIUX Replica Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `fx-trading-platform/apps/web` so its user-facing frontend uses `C:\Users\User\Desktop\工具\html\` as the only visual and UIUX reference.

**Architecture:** Keep the existing Vite React routes, data flow, and smoke scripts. Extract the reference Binance-style visual system into local CSS tokens and components, then migrate pages in reference-page order: shell, home, auth, markets, account, wallet, trading terminal.

**Tech Stack:** React 19, Vite, CSS modules, existing global CSS, existing `lucide-react`, Node test runner, existing smoke scripts.

---

### Task 1: Reference Contract And Baseline

**Files:**
- Modify: `fx-trading-platform/apps/web/src/pages/prototypeFidelity.test.ts`
- Modify: `fx-trading-platform/apps/web/src/styles.css`
- Modify: `fx-trading-platform/apps/web/src/design-system/theme/theme.css`

- [ ] Add tests that assert the global UI tokens match the reference palette, font family, spacing, radius, header height, and mobile bottom navigation behavior.
- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"` and confirm the new assertions fail for the old token drift.
- [ ] Update only token and global shell CSS needed to pass those assertions.
- [ ] Re-run `web:test`.

### Task 2: App Shell

**Files:**
- Modify: `fx-trading-platform/apps/web/src/app/AppShell.tsx`
- Modify: `fx-trading-platform/apps/web/src/app/navigation.ts`
- Modify: `fx-trading-platform/apps/web/src/styles.css`
- Modify: locale files under `fx-trading-platform/apps/web/src/i18n/locales/`

- [ ] Add or update tests asserting the Binance-style topbar remains present for normal pages, hidden for auth pages, and compatible with the terminal route.
- [ ] Rework the brand, navigation labels, topbar actions, active states, mobile tabs, and auth CTAs to match the reference header interaction language.
- [ ] Keep route slugs and existing navigation destinations stable.

### Task 3: Home And Auth Pages

**Files:**
- Modify: `fx-trading-platform/apps/web/src/pages/home/HomePage.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/home/HomePage.module.css`
- Modify: `fx-trading-platform/apps/web/src/pages/home/components/*.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/login/LoginPage.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/login/LoginPage.module.css`
- Modify: `fx-trading-platform/apps/web/src/pages/login/AuthSupportPage.tsx`

- [ ] Add tests for visible home/auth structural contracts where practical.
- [ ] Align home layout to the reference homepage: large dark hero, registration entry, market/news preview stack, trust/download/support sections.
- [ ] Align auth layout to the reference registration page: dark full-screen form, Binance-style inputs, CTA buttons, support links, and compact mobile flow.

### Task 4: Markets And Account Pages

**Files:**
- Modify: `fx-trading-platform/apps/web/src/pages/markets/MarketsPage.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/account/AccountPages.tsx`
- Modify: `fx-trading-platform/apps/web/src/components/user-page/*.tsx`
- Modify: `fx-trading-platform/apps/web/src/styles.css`

- [ ] Add tests around market/account page contracts that should not regress during layout migration.
- [ ] Align markets page to reference overview/ranking pages: tabs, category filters, summary cards, ranking lists, desktop table, mobile list.
- [ ] Align account center to reference account overview: left account nav, profile summary, onboarding/KYC card, assets panel, ledger/order tables.

### Task 5: Trading Terminal And Wallet Finish

**Files:**
- Modify: `fx-trading-platform/apps/web/src/pages/trading/**/*.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/**/*.css`
- Modify: `fx-trading-platform/apps/web/src/features/trading/**/*.tsx`
- Modify: `fx-trading-platform/apps/web/src/features/trading/styles/trade-panel.css`
- Modify: `fx-trading-platform/apps/web/src/pages/wallet/WalletPage.tsx`

- [ ] Add or update tests for terminal shell sizing, login-gate preservation, and mobile trade action placement.
- [ ] Apply the same Binance token set to chart workspace, order book, trade panel, bottom account panel, dialogs, drawers, wallet cards, and empty/loading states.
- [ ] Preserve existing trading behavior and API calls.

### Task 6: Verification

**Files:**
- Modify only existing smoke scripts if selectors must track the new UI.

- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:test"`.
- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run web:build"`.
- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:user-core-pages"`.
- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:trading-login-gate"`.
- [ ] Run `cmd.exe /d /s /c "npm.cmd --prefix fx-trading-platform run smoke:visual-qa"`.
- [ ] Capture or inspect desktop and mobile screenshots for `/`, `/register`, `/markets`, `/account/overview`, `/trading`, and report any remaining visual gaps.
