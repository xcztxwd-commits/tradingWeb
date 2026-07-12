package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FundingService {

  private static final int MONEY_SCALE = 8;
  private static final String LEDGER_DESCRIPTION = "Perpetual funding fee";
  private static final Set<String> CRYPTO_BASES = Set.of(
      "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "OKB", "BCH", "LTC");

  private final FundingRateRepository fundingRateRepository;
  private final FundingSettlementRepository fundingSettlementRepository;
  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final SymbolRepository symbolRepository;
  private final TradingInstrumentClassifier instrumentClassifier;
  private final DemoExecutionGuard demoExecutionGuard;

  public FundingRateEntity getCurrentFundingRate(String symbol) {
    String normalized = normalizeSymbol(symbol);
    return fundingRateRepository.findLatestBySymbol(normalized)
        .orElseThrow(() -> new BusinessException("FUNDING_RATE_NOT_FOUND", "Funding rate not found"));
  }

  @Transactional
  public BigDecimal settleFundingForAccount(UUID accountId) {
    Map<UUID, FundingCandidate> candidates = positionRepository
        .findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN)
        .stream()
        .filter(this::isPerpetualPosition)
        .map(position -> new FundingCandidate(
            position.getId(),
            normalizeSymbol(position.getSymbol()),
            getCurrentFundingRate(position.getSymbol())))
        .collect(Collectors.toMap(FundingCandidate::positionId, Function.identity()));

    TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    return positionRepository.findOpenByAccountIdForUpdate(accountId)
        .stream()
        .filter(position -> matches(candidates.get(position.getId()), position))
        .map(position -> settleLockedPosition(
            account,
            position,
            candidates.get(position.getId()).fundingRate()).cashflow())
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  @Transactional
  public BigDecimal settleFundingForPosition(PositionEntity position) {
    return settleFundingForPosition(position, getCurrentFundingRate(position.getSymbol()));
  }

  @Transactional
  public BigDecimal settleFundingForPosition(PositionEntity position, FundingRateEntity fundingRate) {
    return settleFundingForPositionOutcome(position, fundingRate).cashflow();
  }

  @Transactional
  public FundingSettlementOutcome settleFundingForPositionOutcome(
      PositionEntity position,
      FundingRateEntity fundingRate
  ) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(position.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    PositionEntity lockedPosition = positionRepository.findByIdForUpdate(position.getId())
        .orElseThrow(() -> new BusinessException("POSITION_NOT_FOUND", "Position not found"));
    return settleLockedPosition(account, lockedPosition, fundingRate);
  }

  private FundingSettlementOutcome settleLockedPosition(
      TradingAccountEntity account,
      PositionEntity position,
    FundingRateEntity fundingRate
  ) {
    if (position.getStatus() != PositionStatus.OPEN) {
      return FundingSettlementOutcome.skipped(zeroMoney());
    }

    requireMatchingCycle(position, fundingRate);
    if (fundingRate.getFundingTime().isAfter(Instant.now())
        || position.getOpenedAt() != null
        && position.getOpenedAt().isAfter(fundingRate.getFundingTime())) {
      return FundingSettlementOutcome.skipped(zeroMoney());
    }

    SymbolEntity symbol = symbolFor(position);
    if (!matchesPerpetualProduct(position, symbol)) {
      return FundingSettlementOutcome.skipped(zeroMoney());
    }
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    if (!isPerpetual(profile.kind())) {
      return FundingSettlementOutcome.skipped(zeroMoney());
    }
    demoExecutionGuard.requireDemo(account, symbol.getProductType(), position.getSymbol());
    BigDecimal cashflow = fundingCashflow(position, fundingRate, profile);
    FundingSettlementEntity settlement = fundingSettlement(account, position, fundingRate, profile, cashflow);
    if (!fundingSettlementRepository.insertIfAbsent(settlement)) {
      return FundingSettlementOutcome.skipped(zeroMoney());
    }

    if (cashflow.compareTo(BigDecimal.ZERO) == 0) {
      return FundingSettlementOutcome.inserted(cashflow);
    }

    if (marginMode(position) == MarginMode.ISOLATED) {
      applyPositionFunding(position, cashflow);
      positionRepository.save(position);
    } else {
      applyCrossCashflow(account, position, cashflow);
      accountRepository.save(account);
      positionRepository.save(position);
      LedgerEntryEntity ledgerEntry = ledgerService.recordFundingFeeSettlement(
          account,
          cashflow,
          settlement.getId(),
          LEDGER_DESCRIPTION);
      if (ledgerEntry != null) {
        settlement.setLedgerEntryId(ledgerEntry.getId());
        fundingSettlementRepository.updateById(settlement);
      }
    }
    return FundingSettlementOutcome.inserted(cashflow);
  }

  private FundingSettlementEntity fundingSettlement(
      TradingAccountEntity account,
      PositionEntity position,
      FundingRateEntity fundingRate,
      InstrumentProfile profile,
      BigDecimal cashflow
  ) {
    FundingSettlementEntity settlement = new FundingSettlementEntity();
    settlement.setId(UUID.randomUUID());
    settlement.setPositionId(position.getId());
    settlement.setAccountId(position.getAccountId());
    settlement.setSymbol(normalizeSymbol(position.getSymbol()));
    settlement.setFundingTime(fundingRate.getFundingTime());
    settlement.setFundingRate(orZero(fundingRate.getFundingRate()));
    settlement.setAmount(cashflow);
    settlement.setAsset(asset(profile.settlementAsset(), account.getBaseCurrency()));
    settlement.setPositionSide(position.getPositionSide());
    settlement.setMarginMode(marginMode(position));
    settlement.setMarkPrice(requiredMark(fundingRate));
    settlement.setSource(source(fundingRate));
    settlement.setBalanceAfter(balanceAfter(account, position, cashflow));
    settlement.setIsolatedMarginAfter(isolatedMarginAfter(position, cashflow));
    settlement.setShortfall(zeroMoney());
    return settlement;
  }

  private BigDecimal fundingCashflow(
      PositionEntity position,
      FundingRateEntity fundingRate,
      InstrumentProfile profile
  ) {
    BigDecimal markPrice = requiredMark(fundingRate);
    BigDecimal positionValue = profile.kind() == InstrumentKind.INVERSE_PERPETUAL
        ? inversePositionValue(position, profile, markPrice)
        : linearPositionValue(position, markPrice);
    return sideSign(position.getSide())
        .negate()
        .multiply(positionValue)
        .multiply(orZero(fundingRate.getFundingRate()))
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal linearPositionValue(PositionEntity position, BigDecimal markPrice) {
    return orZero(position.getLots()).abs().multiply(markPrice);
  }

  private BigDecimal inversePositionValue(PositionEntity position, InstrumentProfile profile, BigDecimal markPrice) {
    BigDecimal usdNotional = orZero(position.getLots()).abs()
        .multiply(orZero(profile.contractSize()))
        .multiply(orZero(profile.contractMultiplier()));
    return usdNotional.divide(markPrice, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private void applyCrossCashflow(
      TradingAccountEntity account,
      PositionEntity position,
      BigDecimal cashflow
  ) {
    BigDecimal existingEquity = currentEquity(account);
    BigDecimal balance = orZero(account.getBalance()).add(cashflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    account.setBalance(balance);
    account.setEquity(existingEquity.add(cashflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    account.setFreeMargin(orZero(account.getFreeMargin()).add(cashflow)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    applyPositionFunding(position, cashflow);
  }

  private void applyPositionFunding(PositionEntity position, BigDecimal cashflow) {
    position.setFundingPnl(orZero(position.getFundingPnl()).add(cashflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    position.setVersion(version(position) + 1L);
  }

  private boolean isPerpetualPosition(PositionEntity position) {
    SymbolEntity symbol = symbolFor(position);
    return matchesPerpetualProduct(position, symbol)
        && isPerpetual(instrumentClassifier.profile(symbol).kind());
  }

  private SymbolEntity symbolFor(PositionEntity position) {
    String normalized = normalizeSymbol(position.getSymbol());
    return symbolRepository.findBySymbol(normalized)
        .orElseGet(() -> fallbackSymbol(position, normalized));
  }

  private SymbolEntity fallbackSymbol(PositionEntity position, String symbol) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    if (isCryptoSymbol(symbol)) {
      String quoteCurrency = quoteCurrency(symbol);
      entity.setAssetClass("USD".equals(quoteCurrency) ? "INVERSE_PERPETUAL" : "LINEAR_PERPETUAL");
      entity.setProductType("USD".equals(quoteCurrency)
          ? com.fxplatform.market.model.ProductType.INVERSE_PERP
          : com.fxplatform.market.model.ProductType.LINEAR_PERP);
      entity.setBaseCurrency(baseCurrency(symbol));
      entity.setQuoteCurrency(quoteCurrency);
      entity.setLotSize("USD".equals(quoteCurrency) ? new BigDecimal("100") : BigDecimal.ONE);
      entity.setContractSize(entity.getLotSize());
      entity.setContractMultiplier(BigDecimal.ONE);
      entity.setSettlementAsset("USD".equals(quoteCurrency) ? entity.getBaseCurrency() : quoteCurrency);
      entity.setMarginAsset(entity.getSettlementAsset());
    }
    return entity;
  }

  private BigDecimal requiredMark(FundingRateEntity fundingRate) {
    BigDecimal value = fundingRate.getMarkPrice();
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("INVALID_MARK_PRICE", "Funding mark price must be positive");
    }
    return value;
  }

  private void requireMatchingCycle(PositionEntity position, FundingRateEntity fundingRate) {
    if (fundingRate == null || fundingRate.getFundingTime() == null) {
      throw new BusinessException("FUNDING_TIME_REQUIRED", "Funding time is required");
    }
    if (!normalizeSymbol(position.getSymbol()).equals(normalizeSymbol(fundingRate.getSymbol()))) {
      throw new BusinessException(
          "FUNDING_RATE_SYMBOL_MISMATCH",
          "Funding rate symbol does not match the position");
    }
  }

  private BigDecimal balanceAfter(
      TradingAccountEntity account,
      PositionEntity position,
      BigDecimal cashflow
  ) {
    BigDecimal delta = marginMode(position) == MarginMode.ISOLATED ? BigDecimal.ZERO : cashflow;
    return orZero(account.getBalance()).add(delta).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal isolatedMarginAfter(PositionEntity position, BigDecimal cashflow) {
    if (marginMode(position) != MarginMode.ISOLATED) {
      return zeroMoney();
    }
    return orZero(position.getMarginHeld())
        .add(orZero(position.getFundingPnl()))
        .add(cashflow)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal currentEquity(TradingAccountEntity account) {
    return account.getEquity() == null ? orZero(account.getBalance()) : account.getEquity();
  }

  private MarginMode marginMode(PositionEntity position) {
    return position.getMarginMode() == null ? MarginMode.CROSS : position.getMarginMode();
  }

  private long version(PositionEntity position) {
    return position.getVersion() == null ? 0L : position.getVersion();
  }

  private String source(FundingRateEntity fundingRate) {
    String providerCode = fundingRate.getProviderCode();
    return providerCode == null || providerCode.isBlank()
        ? "legacy"
        : providerCode.trim().toLowerCase();
  }

  private BigDecimal sideSign(OrderSide side) {
    if (side == OrderSide.BUY) {
      return BigDecimal.ONE;
    }
    if (side == OrderSide.SELL) {
      return BigDecimal.ONE.negate();
    }
    throw new BusinessException("INVALID_POSITION_SIDE", "Position side must be BUY or SELL");
  }

  private boolean isPerpetual(InstrumentKind kind) {
    return kind == InstrumentKind.LINEAR_PERPETUAL || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private String asset(String preferred, String fallback) {
    String value = normalizeSymbol(preferred);
    return value.isBlank() ? normalizeSymbol(fallback) : value;
  }

  private boolean isCryptoSymbol(String symbol) {
    return hasCryptoBase(symbol, "USDT") || hasCryptoBase(symbol, "USDC") || hasCryptoBase(symbol, "USD");
  }

  private boolean hasCryptoBase(String symbol, String quoteSuffix) {
    if (!symbol.endsWith(quoteSuffix) || symbol.length() <= quoteSuffix.length()) {
      return false;
    }
    return CRYPTO_BASES.contains(symbol.substring(0, symbol.length() - quoteSuffix.length()));
  }

  private String baseCurrency(String symbol) {
    String quoteCurrency = quoteCurrency(symbol);
    return quoteCurrency.isBlank() || symbol.length() <= quoteCurrency.length()
        ? ""
        : symbol.substring(0, symbol.length() - quoteCurrency.length());
  }

  private String quoteCurrency(String symbol) {
    if (symbol.endsWith("USDT")) {
      return "USDT";
    }
    if (symbol.endsWith("USDC")) {
      return "USDC";
    }
    if (symbol.endsWith("USD")) {
      return "USD";
    }
    return "";
  }

  private String normalizeSymbol(String symbol) {
    return symbol == null ? "" : symbol.trim().toUpperCase();
  }

  private boolean matchesPerpetualProduct(PositionEntity position, SymbolEntity symbol) {
    return position.getProductType() != null
        && position.getProductType() == symbol.getProductType()
        && (position.getProductType() == com.fxplatform.market.model.ProductType.LINEAR_PERP
            || position.getProductType() == com.fxplatform.market.model.ProductType.INVERSE_PERP);
  }

  private boolean matches(FundingCandidate candidate, PositionEntity position) {
    return candidate != null && candidate.symbol().equals(normalizeSymbol(position.getSymbol()));
  }

  private record FundingCandidate(UUID positionId, String symbol, FundingRateEntity fundingRate) {
  }

  public record FundingSettlementOutcome(boolean inserted, BigDecimal cashflow) {

    private static FundingSettlementOutcome inserted(BigDecimal cashflow) {
      return new FundingSettlementOutcome(true, cashflow);
    }

    private static FundingSettlementOutcome skipped(BigDecimal cashflow) {
      return new FundingSettlementOutcome(false, cashflow);
    }
  }
}
