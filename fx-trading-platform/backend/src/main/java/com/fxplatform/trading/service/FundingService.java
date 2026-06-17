package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
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
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;
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

  public FundingRateEntity getCurrentFundingRate(String symbol) {
    String normalized = normalizeSymbol(symbol);
    return fundingRateRepository.findLatestBySymbol(normalized)
        .orElseThrow(() -> new BusinessException("FUNDING_RATE_NOT_FOUND", "Funding rate not found"));
  }

  @Transactional
  public BigDecimal settleFundingForAccount(UUID accountId) {
    return positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN)
        .stream()
        .filter(this::isPerpetualPosition)
        .map(this::settleFundingForPosition)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  @Transactional
  public BigDecimal settleFundingForPosition(PositionEntity position) {
    return settleFundingForPosition(position, getCurrentFundingRate(position.getSymbol()));
  }

  @Transactional
  public BigDecimal settleFundingForPosition(PositionEntity position, FundingRateEntity fundingRate) {
    if (position.getStatus() != PositionStatus.OPEN) {
      return zeroMoney();
    }

    InstrumentProfile profile = instrumentProfile(position);
    if (!isPerpetual(profile.kind())) {
      return zeroMoney();
    }

    TradingAccountEntity account = accountRepository.findById(position.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    BigDecimal cashflow = fundingCashflow(position, fundingRate, profile);
    FundingSettlementEntity settlement = fundingSettlement(account, position, fundingRate, profile, cashflow);
    if (!fundingSettlementRepository.insertIfAbsent(settlement)) {
      return zeroMoney();
    }

    if (cashflow.compareTo(BigDecimal.ZERO) == 0) {
      return cashflow;
    }

    applyCashflow(account, position, cashflow);
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
    return cashflow;
  }

  private FundingSettlementEntity fundingSettlement(
      TradingAccountEntity account,
      PositionEntity position,
      FundingRateEntity fundingRate,
      InstrumentProfile profile,
      BigDecimal cashflow
  ) {
    if (fundingRate.getFundingTime() == null) {
      throw new BusinessException("FUNDING_TIME_REQUIRED", "Funding time is required");
    }

    FundingSettlementEntity settlement = new FundingSettlementEntity();
    settlement.setId(UUID.randomUUID());
    settlement.setPositionId(position.getId());
    settlement.setAccountId(position.getAccountId());
    settlement.setSymbol(normalizeSymbol(position.getSymbol()));
    settlement.setFundingTime(fundingRate.getFundingTime());
    settlement.setFundingRate(orZero(fundingRate.getFundingRate()));
    settlement.setAmount(cashflow);
    settlement.setAsset(asset(profile.settlementAsset(), account.getBaseCurrency()));
    return settlement;
  }

  private BigDecimal fundingCashflow(
      PositionEntity position,
      FundingRateEntity fundingRate,
      InstrumentProfile profile
  ) {
    BigDecimal markPrice = positivePrice(fundingRate.getMarkPrice(), position.getMarkPrice());
    BigDecimal positionValue = profile.kind() == InstrumentKind.INVERSE_PERPETUAL
        ? inversePositionValue(position, profile, markPrice)
        : linearPositionValue(position, profile, markPrice);
    return sideSign(position.getSide())
        .negate()
        .multiply(positionValue)
        .multiply(orZero(fundingRate.getFundingRate()))
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal linearPositionValue(PositionEntity position, InstrumentProfile profile, BigDecimal markPrice) {
    return orZero(position.getLots()).abs()
        .multiply(orZero(profile.contractSize()))
        .multiply(orZero(profile.contractMultiplier()))
        .multiply(markPrice);
  }

  private BigDecimal inversePositionValue(PositionEntity position, InstrumentProfile profile, BigDecimal markPrice) {
    BigDecimal usdNotional = orZero(position.getLots()).abs()
        .multiply(orZero(profile.contractSize()))
        .multiply(orZero(profile.contractMultiplier()));
    return usdNotional.divide(markPrice, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private void applyCashflow(TradingAccountEntity account, PositionEntity position, BigDecimal cashflow) {
    BigDecimal balance = orZero(account.getBalance()).add(cashflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    account.setBalance(balance);
    account.setEquity(accountEquity(account).add(cashflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    account.setFreeMargin(accountEquity(account).subtract(orZero(account.getUsedMargin())).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    position.setFundingPnl(orZero(position.getFundingPnl()).add(cashflow).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
  }

  private boolean isPerpetualPosition(PositionEntity position) {
    return isPerpetual(instrumentProfile(position).kind());
  }

  private InstrumentProfile instrumentProfile(PositionEntity position) {
    return instrumentClassifier.profile(symbolFor(position));
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

  private BigDecimal positivePrice(BigDecimal preferred, BigDecimal fallback) {
    BigDecimal value = preferred != null ? preferred : fallback;
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("INVALID_MARK_PRICE", "Funding mark price must be positive");
    }
    return value;
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
}
