# Trading Theme Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for implementation and superpowers:verification-before-completion before reporting completion. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a maintainable multi-theme token system for the professional trading UI, with five named themes, global CSS variables, React theme state, localStorage persistence, and a user-facing switcher.

**Architecture:** Add a focused `apps/web/src/design-system/theme` module with theme objects, storage helpers, CSS-variable application, and a React provider. Bridge the new semantic theme objects to existing `--trading-*` CSS variables so `/trading`, `/login`, route loading, order book, chart, and trade panel inherit the same source of truth without changing business logic.

**Tech Stack:** React 19, Vite, TypeScript, CSS variables, Node test runner, existing CSS Modules and `lucide-react`.

---

### Task 1: Theme Token Contract

**Files:**
- Create: `fx-trading-platform/apps/web/src/design-system/theme/themes.ts`
- Test: `fx-trading-platform/apps/web/src/design-system/theme/themes.test.ts`

- [ ] Write failing tests that require exactly five themes with ids `midnight-pro`, `binance-inspired`, `okx-inspired`, `deep-blue-quant`, and `light-institutional`.
- [ ] Require every theme to expose `background`, `surface`, `surfaceElevated`, `border`, `textPrimary`, `textSecondary`, `textMuted`, `primary`, `primaryHover`, `accent`, `success`, `buy`, `danger`, `sell`, `warning`, `chartGrid`, `chartCandleUp`, `chartCandleDown`, `orderBookBidBg`, and `orderBookAskBg`.
- [ ] Implement the minimal theme object and helpers to pass.

### Task 2: Provider And Persistence

**Files:**
- Create: `fx-trading-platform/apps/web/src/design-system/theme/ThemeProvider.tsx`
- Test: `fx-trading-platform/apps/web/src/design-system/theme/ThemeProvider.test.ts`
- Modify: `fx-trading-platform/apps/web/src/main.tsx`

- [ ] Write failing tests for `fx-ui-theme` persistence, fallback to `midnight-pro`, DOM `data-theme`, and exported provider hooks.
- [ ] Implement `ThemeProvider`, `useTheme`, `loadThemeId`, `saveThemeId`, and `applyThemeToDocument`.
- [ ] Wrap `<App />` in `ThemeProvider`.

### Task 3: CSS Variable Bridge

**Files:**
- Create: `fx-trading-platform/apps/web/src/design-system/theme/theme.css`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx`
- Modify: `fx-trading-platform/apps/web/src/components/loading/ExchangeLoading.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/login/LoginPage.tsx`
- Modify: `fx-trading-platform/apps/web/src/pages/login/LoginPage.module.css`

- [ ] Write failing tests that block old `data-theme='light'` local theme mode and direct loading component localStorage reads.
- [ ] Import `theme.css` once.
- [ ] Remove page-local theme mode from `TradingPage` and consume global provider.
- [ ] Keep chart mode derived from theme color scheme for existing KLineCharts API.
- [ ] Make `ExchangeLoading` consume global CSS variables instead of local theme storage.
- [ ] Apply theme variables to login page without changing login flow.

### Task 4: Theme Switcher

**Files:**
- Create: `fx-trading-platform/apps/web/src/design-system/theme/ThemeSwitcher.tsx`
- Create: `fx-trading-platform/apps/web/src/design-system/theme/ThemeSwitcher.module.css`
- Test: `fx-trading-platform/apps/web/src/design-system/theme/ThemeSwitcher.test.ts`
- Modify: `fx-trading-platform/apps/web/src/pages/trading/TradingPage.tsx`

- [ ] Write failing tests that require a visible theme switcher using all five themes.
- [ ] Implement a compact swatch list with accessible labels.
- [ ] Place it inside the existing trading settings dialog.
- [ ] Keep the existing settings dialog behavior and Escape/backdrop close logic.

### Task 5: Verification

**Commands:**
- `npm.cmd --workspace apps/web run test`
- `npm.cmd --workspace apps/web run build`

- [ ] Run the full web test suite.
- [ ] Run the web build.
- [ ] Report exact evidence and any remaining gaps.
