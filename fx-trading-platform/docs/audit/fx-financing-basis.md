# FX Financing Basis

Date: 2026-06-16

`ForexFinancingService` currently uses explicit service constants rather than an external runtime configuration:

- `POSITION_NOTIONAL_BASIS = QUOTE_POSITION_VALUE`
- `FALLBACK_BASIS = BASE_UNITS`
- `LEDGER_BASIS = ACCOUNT_POSITION_VALUE`

## Basis Definitions

| Basis | Current meaning |
|---|---|
| `QUOTE_POSITION_VALUE` | Positive `position.notional` is treated as the position value in the symbol quote currency. For `USDJPY`, this is JPY. |
| `BASE_UNITS` | Fallback when `position.notional` is empty or zero: `abs(lots) * unitSize`, denominated in the symbol base currency. |
| `ACCOUNT_POSITION_VALUE` | Account, position accrued financing, settlement amount, and `FINANCING` ledger amount after conversion into `account.baseCurrency`. |

## Settlement Rule

The service calculates financing in the source basis currency first, then converts any non-account-currency financing amount with `ForexConversionService.convert(sourceCurrency, account.baseCurrency)`.

The persisted `fx_financing_settlements.amount`, `fx_financing_settlements.asset`, account balance/equity/free margin, `positions.financing_accrued`, and the `FINANCING` ledger amount are account-currency values. `LedgerService.recordFinancing` writes the ledger currency from `account.baseCurrency`.

Missing conversion rates fail closed with `FX_CONVERSION_RATE_NOT_FOUND`; the service does not silently write a non-account-currency financing amount into the account ledger.

## Examples Covered By Tests

- `USDJPY` with JPY quote-position-value financing converts JPY financing into USD before settlement and ledger write.
- `EURUSD` in a USD account stays in USD and does not query conversion rates.
- Missing `JPY -> USD` and reverse `USD -> JPY` conversion rates throw before settlement/account/ledger mutation.

OANDA-style `gainQuoteHome` / `lossQuoteHome` conversion factors and conversion markup ledger entries remain out of scope.
