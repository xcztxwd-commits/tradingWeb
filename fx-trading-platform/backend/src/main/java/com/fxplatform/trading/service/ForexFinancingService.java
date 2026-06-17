package com.fxplatform.trading.service;

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
import com.fxplatform.risk.service.ForexConversionService;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.ForexFinancingRateEntity;
import com.fxplatform.trading.entity.ForexFinancingSettlementEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.ForexFinancingRateRepository;
import com.fxplatform.trading.repository.ForexFinancingSettlementRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForexFinancingService {

  private static final int MONEY_SCALE = 8;
  private static final int DEFAULT_DAY_COUNT = 360;
  private static final BigDecimal FOREX_STANDARD_LOT = new BigDecimal("100000");
  private static final String LEDGER_DESCRIPTION = "FX rollover financing";
  static final ForexFinancingBasis POSITION_NOTIONAL_BASIS = ForexFinancingBasis.QUOTE_POSITION_VALUE;
  static final ForexFinancingBasis FALLBACK_BASIS = ForexFinancingBasis.BASE_UNITS;
  static final ForexFinancingBasis LEDGER_BASIS = ForexFinancingBasis.ACCOUNT_POSITION_VALUE;

  private final ForexFinancingRateRepository financingRateRepository;
  private final ForexFinancingSettlementRepository financingSettlementRepository;
  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final SymbolRepository symbolRepository;
  private final TradingInstrumentClassifier instrumentClassifier;
  private final ForexConversionService conversionService;
  private final Clock clock;

  @Autowired
  public ForexFinancingService(
      ForexFinancingRateRepository financingRateRepository,
      ForexFinancingSettlementRepository financingSettlementRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      TradingInstrumentClassifier instrumentClassifier,
      ForexConversionService conversionService
  ) {
    this(
        financingRateRepository,
        financingSettlementRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        instrumentClassifier,
        conversionService,
        Clock.systemUTC());
  }

  ForexFinancingService(
      ForexFinancingRateRepository financingRateRepository,
      ForexFinancingSettlementRepository financingSettlementRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      TradingInstrumentClassifier instrumentClassifier,
      ForexConversionService conversionService,
      Clock clock
  ) {
    this.financingRateRepository = financingRateRepository;
    this.financingSettlementRepository = financingSettlementRepository;
    this.positionRepository = positionRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.symbolRepository = symbolRepository;
    this.instrumentClassifier = instrumentClassifier;
    this.conversionService = conversionService;
    this.clock = clock;
  }

  @Transactional
  public BigDecimal settleDailyFinancing(UUID accountId) {
    List<PositionEntity> positions = positionRepository
        .findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN)
        .stream()
        .filter(this::isForexPosition)
        .toList();
    if (positions.isEmpty()) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    BigDecimal total = BigDecimal.ZERO;
    for (PositionEntity position : positions) {
      total = total.add(settlePosition(account, position));
    }
    return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal settlePosition(TradingAccountEntity account, PositionEntity position) {
    ForexFinancingRateEntity rate = financingRateRepository.findLatestBySymbol(position.getSymbol())
        .orElseThrow(() -> new BusinessException("FX_FINANCING_RATE_NOT_FOUND", "FX financing rate not found"));
    BigDecimal annualRate = annualRate(position.getSide(), rate);
    int dayCount = dayCount(rate);
    int daysCharged = daysCharged(position.getSymbol());
    FinancingExposure exposure = financingExposure(position);
    BigDecimal sourceFinancing = financingAmount(exposure.amount(), annualRate, dayCount, daysCharged);
    BigDecimal financing = accountCurrencyAmount(account, exposure.currency(), sourceFinancing);
    ForexFinancingSettlementEntity settlement = financingSettlement(
        account,
        position,
        LocalDate.now(clock),
        daysCharged,
        annualRate,
        financing);
    if (!financingSettlementRepository.insertIfAbsent(settlement)) {
      return zeroMoney();
    }

    if (financing.compareTo(BigDecimal.ZERO) == 0) {
      return financing;
    }

    applyFinancing(account, position, financing);
    accountRepository.save(account);
    positionRepository.save(position);
    LedgerEntryEntity ledgerEntry = ledgerService.recordFinancingSettlement(
        account,
        financing,
        settlement.getId(),
        LEDGER_DESCRIPTION);
    if (ledgerEntry != null) {
      settlement.setLedgerEntryId(ledgerEntry.getId());
      financingSettlementRepository.updateById(settlement);
    }
    return financing;
  }

  private ForexFinancingSettlementEntity financingSettlement(
      TradingAccountEntity account,
      PositionEntity position,
      LocalDate settlementDate,
      int daysCharged,
      BigDecimal annualRate,
      BigDecimal financing
  ) {
    ForexFinancingSettlementEntity settlement = new ForexFinancingSettlementEntity();
    settlement.setId(UUID.randomUUID());
    settlement.setPositionId(position.getId());
    settlement.setAccountId(position.getAccountId());
    settlement.setSymbol(normalizeSymbol(position.getSymbol()));
    settlement.setSettlementDate(settlementDate);
    settlement.setDaysCharged(daysCharged);
    settlement.setRate(annualRate);
    settlement.setAmount(financing);
    settlement.setAsset(asset(account.getBaseCurrency()));
    return settlement;
  }

  private BigDecimal financingAmount(
      BigDecimal exposureAmount,
      BigDecimal annualRate,
      int dayCount,
      int daysCharged
  ) {
    return exposureAmount
        .multiply(annualRate)
        .multiply(BigDecimal.valueOf(daysCharged))
        .divide(BigDecimal.valueOf(dayCount), MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private FinancingExposure financingExposure(PositionEntity position) {
    SymbolEntity symbol = symbolFor(position);
    BigDecimal notional = orZero(position.getNotional()).abs();
    if (notional.compareTo(BigDecimal.ZERO) > 0) {
      return new FinancingExposure(notional, quoteCurrency(symbol), POSITION_NOTIONAL_BASIS);
    }
    BigDecimal baseUnits = orZero(position.getLots()).abs()
        .multiply(instrumentClassifier.profile(symbol).unitSize())
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new FinancingExposure(baseUnits, baseCurrency(symbol), FALLBACK_BASIS);
  }

  private BigDecimal accountCurrencyAmount(
      TradingAccountEntity account,
      String sourceCurrency,
      BigDecimal sourceAmount
  ) {
    BigDecimal amount = sourceAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (amount.compareTo(BigDecimal.ZERO) == 0) {
      return amount;
    }
    String accountCurrency = asset(account.getBaseCurrency());
    String source = asset(sourceCurrency);
    if (source.equals(accountCurrency)) {
      return amount;
    }
    return conversionService.convert(amount, source, accountCurrency)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal annualRate(OrderSide side, ForexFinancingRateEntity rate) {
    if (side == OrderSide.BUY) {
      return orZero(rate.getLongRateAnnual());
    }
    if (side == OrderSide.SELL) {
      return orZero(rate.getShortRateAnnual());
    }
    throw new BusinessException("INVALID_POSITION_SIDE", "Position side must be BUY or SELL");
  }

  private int dayCount(ForexFinancingRateEntity rate) {
    Integer dayCount = rate.getDayCount();
    return dayCount == null || dayCount <= 0 ? DEFAULT_DAY_COUNT : dayCount;
  }

  int daysCharged(String symbol) {
    DayOfWeek dayOfWeek = LocalDate.now(clock).getDayOfWeek();
    if (isUsdCad(symbol)) {
      return dayOfWeek == DayOfWeek.THURSDAY ? 3 : 1;
    }
    return dayOfWeek == DayOfWeek.WEDNESDAY ? 3 : 1;
  }

  private void applyFinancing(
      TradingAccountEntity account,
      PositionEntity position,
      BigDecimal financing
  ) {
    BigDecimal balanceBefore = orZero(account.getBalance());
    BigDecimal equityBefore = account.getEquity() == null ? balanceBefore : account.getEquity();
    BigDecimal balance = balanceBefore.add(financing).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal equity = equityBefore.add(financing).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    account.setBalance(balance);
    account.setEquity(equity);
    account.setFreeMargin(equity.subtract(orZero(account.getUsedMargin())).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    position.setFinancingAccrued(orZero(position.getFinancingAccrued()).add(financing).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
  }

  private boolean isForexPosition(PositionEntity position) {
    return instrumentProfile(position).kind() == InstrumentKind.FOREX;
  }

  private InstrumentProfile instrumentProfile(PositionEntity position) {
    return instrumentClassifier.profile(symbolFor(position));
  }

  private SymbolEntity symbolFor(PositionEntity position) {
    String normalized = normalizeSymbol(position.getSymbol());
    return symbolRepository.findBySymbol(normalized)
        .orElseGet(() -> fallbackSymbol(normalized));
  }

  private SymbolEntity fallbackSymbol(String symbol) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    if (symbol.length() == 6) {
      entity.setAssetClass("FOREX");
      entity.setBaseCurrency(symbol.substring(0, 3));
      entity.setQuoteCurrency(symbol.substring(3));
      entity.setLotSize(FOREX_STANDARD_LOT);
    }
    return entity;
  }

  private boolean isUsdCad(String symbol) {
    return "USDCAD".equals(normalizeSymbol(symbol));
  }

  private BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private String asset(String value) {
    String normalized = normalizeSymbol(value);
    return normalized.isBlank() ? "USD" : normalized;
  }

  private String baseCurrency(SymbolEntity symbol) {
    String currency = normalizeSymbol(symbol.getBaseCurrency());
    if (!currency.isBlank()) {
      return currency;
    }
    String symbolCode = normalizeSymbol(symbol.getSymbol());
    return symbolCode.length() == 6 ? symbolCode.substring(0, 3) : "";
  }

  private String quoteCurrency(SymbolEntity symbol) {
    String currency = normalizeSymbol(symbol.getQuoteCurrency());
    if (!currency.isBlank()) {
      return currency;
    }
    String symbolCode = normalizeSymbol(symbol.getSymbol());
    return symbolCode.length() == 6 ? symbolCode.substring(3) : "";
  }

  private String normalizeSymbol(String symbol) {
    return symbol == null ? "" : symbol.trim().toUpperCase();
  }

  enum ForexFinancingBasis {
    BASE_UNITS,
    QUOTE_POSITION_VALUE,
    ACCOUNT_POSITION_VALUE
  }

  private record FinancingExposure(
      BigDecimal amount,
      String currency,
      ForexFinancingBasis basis
  ) {
  }
}
