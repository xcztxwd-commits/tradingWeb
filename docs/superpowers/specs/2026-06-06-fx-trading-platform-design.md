# FX Trading Platform Product Design

Date: 2026-06-06

## Scope

This design covers the `fx-trading-platform` subproject inside the `tradingView-KlineChart` workspace. The root `klinecharts` package remains the charting library dependency and is out of scope except for existing build integration.

The chosen direction is **trusted trading core plus professional terminal**:

- Make `/trading` the primary trading terminal.
- Keep backend state as the source of truth for accounts, orders, positions, ledger entries, fills, and close-position results.
- Keep local preview only as an explicit offline state, never as a silent fallback when backend order submission fails.
- Reduce duplicate maintenance between `/trade` and `/trading`.
- Improve realtime market data without introducing a large matching engine or unnecessary OMS abstraction.

## Product Goals

The first usable milestone should prove that the platform is not only visually useful, but also trustworthy:

- A user cannot read positions, ledger entries, or close positions for another user's account.
- A backend order failure is shown as a failure in the UI.
- `/trading` is the single main trading workspace.
- Market quote, order book, and recent-trade streams do not create excessive STOMP connections.
- Existing architecture checks, backend tests, frontend tests, builds, and smoke scripts can verify the result.

## Non-Goals

- Do not rewrite the root KLineCharts library.
- Do not add a full exchange matching engine.
- Do not implement split order, iceberg, or TWAP in this iteration.
- Do not refactor unrelated styles, docs, or modules.
- Do not change existing API paths unless a compatibility reason forces it.

## Terminal UX Design

`/trading` becomes the main trading workspace.

Desktop layout:

- Left rail: `MarketSidebar` for symbol selection, favorites, search, and compact quote movement.
- Top band: `SymbolHeader` for selected symbol, price, spread, market source, and session state.
- Center: `ChartWorkspace` for KLineCharts, drawing tools, indicators, fullscreen, and interval controls.
- Right top: `MarketSidePanel` for order book and recent trades.
- Right bottom: `TradePanel` for order entry.
- Bottom: `BottomAccountPanel` with the current tab order: current orders, historical orders, current positions, historical positions, assets, strategies.

Mobile layout:

- Keep first-screen actions focused on symbol state, order entry, and market side panel access.
- Use existing drawer and sheet patterns for market selection, quote/order book, and order entry.
- Keep the chart collapsible so trading controls and market context remain reachable.

Entry strategy:

- `/trading` is the primary nav target.
- `/trade` should either redirect to `/trading` or be explicitly labeled as a legacy preview. It should not continue to receive new trading feature work.

Component boundary:

- `TradingPage` composes data and layout, but does not decide whether a failed backend order becomes a preview order.
- `TradePanel` owns form state and validation, then calls a passed `submitOrder(payload)` function.
- `MarketSidePanel` owns order book and recent trade display, but not account refresh.
- `TradingSession` owns token, account, orders, positions, ledger entries, refresh, submit order, and close position.

## Backend Design

All account-scoped API calls must validate account ownership with the current authenticated principal.

The existing account summary pattern is the standard:

- Use `principal.id()` plus `accountId` to load the account.
- If the account does not belong to the user, return an authorization failure instead of exposing data.

Required backend changes:

- `GET /api/trading/positions?accountId=...`: validate that `accountId` belongs to the current principal.
- `POST /api/trading/positions/{positionId}/close?accountId=...`: validate both account ownership and position ownership before closing.
- `GET /api/ledger?accountId=...`: validate that `accountId` belongs to the current principal.
- Keep `POST /api/trading/orders` through `OrderService`, preserving risk checks, idempotency, execution, order events, fills, positions, margin, and ledger writes.

Order state model:

- `RECEIVED`: request entered the trading service.
- `ACCEPTED`: order passed validation/risk and was accepted.
- `PENDING`: limit or stop order is waiting for trigger.
- `FILLED`: execution completed and position/ledger state was written.
- `REJECTED`: backend rejected the order with a clear reason.
- `CANCELED` and `EXPIRED`: reserved for cancellation and expiry workflows.

Error semantics:

- `403`: account, position, or ledger resource does not belong to the current user.
- `400`: invalid input, failed risk check, or insufficient margin.
- `409`: idempotency or client order conflict.
- `503`: quote unavailable, stale quote, or execution adapter unavailable.
- `500`: return a generic message plus request id; do not expose raw internal exception messages.

## Frontend Data Flow

`TradingSession`:

- Boot by loading a stored demo token or creating a demo session.
- Load account, orders, positions, and ledger entries in parallel.
- Use one refresh path for initial load, post-order refresh, post-close refresh, and timed refresh.
- When a backend session exists, failed order submission must remain failed.
- Use local preview only when there is no backend session, and mark it as `offline-preview`.

`MarketStream`:

- Replace per-topic STOMP clients with a shared STOMP client per token/session.
- Manage quote, order book, and recent-trade subscriptions by topic.
- Continue using `marketDataStore` for high-frequency book/trade data and batched React updates.
- Keep REST as the first-screen fallback and disconnection recovery source.
- Add current-bar chart updates from quote or candle increments after initial REST candles load.

`TradePanel`:

- Own buy/sell form state, price, amount, total, TP/SL, and local validation.
- Call the provided submit function with a backend-compatible payload.
- Display backend success states such as `ACCEPTED`, `PENDING`, or `FILLED`.
- Display backend failure reasons, including insufficient margin, stale quote, validation error, and authorization error.
- Show local preview as a separate state and do not write preview orders into real account tables.

Common UI states:

- `loading`: session, account, or market data is still loading.
- `ready`: backend session is available and real order submission is enabled.
- `offline-preview`: backend session is unavailable and only local preview is enabled.
- `error`: show error code, message, and request id when available.

## Delivery Plan

### Phase 1: Trusted Trading Loop

Implement:

- Add principal/account ownership checks for positions, close-position, and ledger endpoints.
- Add tests for cross-account read and close-position denial.
- Change frontend order submission so backend failures are not converted into local successful preview orders.
- Extend API error handling so UI can display `code`, `message`, and request id.

Verify:

- `npm.cmd run verify:architecture`
- backend `mvn test`
- `npm.cmd run web:build`

### Phase 2: Terminal Consolidation

Implement:

- Make `/trading` the primary trading nav target.
- Redirect `/trade` to `/trading` or label it as legacy preview.
- Remove normal-path mock login from `TradePanel`.
- Add clear UI states for `loading`, `ready`, `offline-preview`, and `error`.

Verify:

- Frontend tests for route behavior and trade panel status behavior.
- `npm.cmd run web:build`
- Browser check of `/trading` on desktop and mobile widths.

### Phase 3: Realtime and Regression

Implement:

- Shared STOMP client for quote, order book, and recent trade subscriptions.
- K-line current-bar updates after REST candle loading.
- A `web:test` script that runs the existing frontend `node:test` files.

Verify:

- `npm.cmd run verify:architecture`
- backend `mvn test`
- `npm.cmd run web:test`
- `npm.cmd run web:build`
- `npm.cmd run admin:build`
- `npm.cmd run smoke:backend`
- `npm.cmd run smoke:admin`

## Acceptance Criteria

The design is implemented successfully when:

- Cross-account reads and close-position attempts are denied.
- Real backend order failures are visible failures in the terminal UI.
- Local preview is only available in an explicit offline-preview state.
- `/trading` is the single main trading workspace.
- Market stream subscriptions share a connection instead of creating one STOMP client per topic.
- The chart has initial REST candles and live current-bar movement.
- Architecture verification, backend tests, frontend tests, builds, and smoke scripts pass.

## Risk Controls

- Keep existing API paths stable.
- Keep edits scoped to trading platform modules directly involved in this design.
- Prefer service-level ownership checks so controller and service tests can cover the behavior.
- Treat UI preview as a separate product state, not as a fallback success path.
- Make every phase independently buildable and testable.
