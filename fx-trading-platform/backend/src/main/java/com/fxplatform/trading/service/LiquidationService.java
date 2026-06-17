package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.entity.RiskConfigEntity;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class LiquidationService {

  static final String FX_MARGIN_STOP_OUT = "FX_MARGIN_STOP_OUT";
  static final String PERP_MAINTENANCE_MARGIN = "PERP_MAINTENANCE_MARGIN";

  private static final BigDecimal DEFAULT_STOP_OUT_LEVEL = new BigDecimal("50");
  private static final int MONEY_SCALE = 8;
  private static final String LIQUIDATION_FEE_DESCRIPTION = "Liquidation fee charged";
  private static final String POSITION_REFERENCE = "POSITION";
  private static final String LIQUIDATION_FEE_ENTRY_TYPE = "LIQUIDATION_FEE";
  private static final Set<String> CRYPTO_BASES = Set.of(
      "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "OKB", "BCH", "LTC");

  private final AccountSnapshotService accountSnapshotService;
  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final PositionService positionService;
  private final SymbolRepository symbolRepository;
  private final RiskConfigRepository riskConfigRepository;
  private final LedgerService ledgerService;
  private final QuoteService quoteService;
  private final WalletService walletService;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private final PnLCalculator pnlCalculator = new PnLCalculator();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();

  @Value("${trading.stop-out-level:50}")
  private BigDecimal defaultStopOutLevel = DEFAULT_STOP_OUT_LEVEL;

  @Transactional
  public int scanAccount(UUID accountId) {
    int closed = 0;
    while (true) {
      RiskSnapshot snapshot = freshRiskSnapshot(accountId);
      Optional<LiquidationCandidate> candidate = highestRiskCandidate(snapshot);
      if (candidate.isEmpty()) {
        return closed;
      }
      LiquidationCandidate selected = candidate.get();
      if (!liquidatePosition(selected.position(), selected.reason())) {
        return closed;
      }
      selected.position().setStatus(PositionStatus.CLOSED);
      chargeLiquidationFee(accountId, selected.risk());
      closed++;
    }
  }

  public int scanAllAccounts() {
    int closed = 0;
    for (TradingAccountEntity account : accountRepository.findAll()) {
      closed += scanAccount(account.getId());
    }
    return closed;
  }

  /**
   * Uses the same liquidation gate as {@link #scanAccount(UUID)} without closing positions.
   * Perpetual risk is recomputed from fresh marks and includes maintenance margin plus
   * liquidation fee buffer.
   */
  public boolean shouldLiquidate(AccountSnapshot snapshot) {
    return highestRiskCandidate(freshRiskSnapshot(snapshot)).isPresent();
  }

  public boolean liquidatePosition(PositionEntity position, String reason) {
    if (position == null || position.getStatus() != PositionStatus.OPEN) {
      return false;
    }
    try {
      positionService.closeSystemPosition(position.getAccountId(), position.getId(), reason);
      return true;
    } catch (BusinessException ex) {
      if ("POSITION_NOT_OPEN".equals(ex.getCode())) {
        return false;
      }
      throw ex;
    }
  }

  private RiskSnapshot freshRiskSnapshot(UUID accountId) {
    AccountSnapshot accountSnapshot = accountSnapshotService.snapshot(accountId);
    return freshRiskSnapshot(accountSnapshot);
  }

  private RiskSnapshot freshRiskSnapshot(AccountSnapshot accountSnapshot) {
    UUID accountId = accountSnapshot.accountId();
    List<PositionEntity> openPositions = positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId,
        PositionStatus.OPEN);
    List<ClassifiedPosition> classifiedPositions = openPositions.stream()
        .filter(position -> position.getStatus() == PositionStatus.OPEN)
        .map(this::classify)
        .toList();
    TradingAccountEntity account = classifiedPositions.stream().anyMatch(ClassifiedPosition::isPerpetual)
        ? accountForRisk(accountId, accountSnapshot)
        : null;
    List<PerpRiskPosition> perpRisks = classifiedPositions.stream()
        .filter(ClassifiedPosition::isPerpetual)
        .map(position -> recomputePerpRisk(account, position))
        .toList();
    BigDecimal perpThreshold = perpRisks.stream()
        .map(PerpRiskPosition::threshold)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal perpEquity = perpRisks.isEmpty()
        ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        : orZero(account.getBalance())
            .add(perpRisks.stream()
                .map(PerpRiskPosition::floatingPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new RiskSnapshot(accountSnapshot, classifiedPositions, perpRisks, perpEquity, perpThreshold);
  }

  private TradingAccountEntity accountForRisk(UUID accountId, AccountSnapshot snapshot) {
    Optional<TradingAccountEntity> account = accountRepository.findById(accountId);
    if (account != null && account.isPresent()) {
      return account.get();
    }
    TradingAccountEntity fallback = new TradingAccountEntity();
    fallback.setId(accountId);
    fallback.setBaseCurrency(snapshot.baseCurrency());
    fallback.setBalance(orZero(snapshot.balance()));
    fallback.setEquity(orZero(snapshot.equity()));
    fallback.setUsedMargin(orZero(snapshot.usedMargin()));
    fallback.setFreeMargin(orZero(snapshot.freeMargin()));
    fallback.setMarginLevel(snapshot.marginLevel());
    fallback.setLeverage(1);
    return fallback;
  }

  private Optional<LiquidationCandidate> highestRiskCandidate(RiskSnapshot snapshot) {
    List<LiquidationCandidate> candidates = new ArrayList<>();
    if (fxMarginBreached(snapshot.accountSnapshot())) {
      candidates.addAll(snapshot.classifiedPositions().stream()
          .filter(position -> position.kind() == InstrumentKind.FOREX)
          .map(position -> new LiquidationCandidate(
              position.position(),
              FX_MARGIN_STOP_OUT,
              null,
              lossAmount(position.position())))
          .sorted(Comparator.comparing(LiquidationCandidate::sortValue).reversed())
          .toList());
    }

    if (perpMarginBreached(snapshot.perpEquity(), snapshot.perpThreshold())) {
      candidates.addAll(snapshot.perpRisks().stream()
          .map(risk -> new LiquidationCandidate(
              risk.position(),
              PERP_MAINTENANCE_MARGIN,
              risk,
              riskContribution(risk)))
          .sorted(Comparator.comparing(LiquidationCandidate::sortValue).reversed())
          .toList());
    }
    return candidates.stream()
        .max(Comparator.comparing(LiquidationCandidate::sortValue));
  }

  private boolean fxMarginBreached(AccountSnapshot snapshot) {
    BigDecimal marginLevel = snapshot.marginLevel();
    return marginLevel != null && marginLevel.compareTo(stopOutLevel()) <= 0;
  }

  private boolean perpMarginBreached(AccountSnapshot snapshot, BigDecimal threshold) {
    return threshold.compareTo(BigDecimal.ZERO) > 0
        && orZero(snapshot.equity()).compareTo(threshold) <= 0;
  }

  private boolean perpMarginBreached(BigDecimal equity, BigDecimal threshold) {
    return threshold.compareTo(BigDecimal.ZERO) > 0
        && orZero(equity).compareTo(threshold) <= 0;
  }

  private BigDecimal stopOutLevel() {
    Optional<RiskConfigEntity> config = riskConfigRepository.findFirstEnabledWithStopOutLevel();
    return config
        .map(RiskConfigEntity::getStopOutLevel)
        .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
        .orElseGet(() -> defaultStopOutLevel == null ? DEFAULT_STOP_OUT_LEVEL : defaultStopOutLevel);
  }

  private ClassifiedPosition classify(PositionEntity position) {
    SymbolEntity symbol = symbolFor(position);
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    return new ClassifiedPosition(position, profile, symbol);
  }

  private PerpRiskPosition recomputePerpRisk(TradingAccountEntity account, ClassifiedPosition classifiedPosition) {
    PositionEntity position = classifiedPosition.position();
    QuoteResponse quote = quoteService.freshQuote(position.getSymbol());
    BigDecimal closeoutPrice = position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
    BigDecimal markPrice = quote.mid() != null ? quote.mid() : closeoutPrice;
    InstrumentProfile profile = classifiedPosition.profile();
    int leverage = positionLeverage(position, account);
    PerpMarginCalculator.MarginResult margin = perpMarginCalculator.calculate(
        profile,
        position.getLots(),
        markPrice,
        leverage);
    BigDecimal floatingPnl = pnlCalculator.floatingPnl(
        profile.kind(),
        position.getSide(),
        position.getLots(),
        position.getOpenPrice(),
        markPrice,
        profile.unitSize()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal liquidationFee = margin.notional()
        .multiply(orZero(classifiedPosition.symbol().getLiquidationFeeRate()))
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    position.setCurrentPrice(closeoutPrice);
    position.setMarkPrice(markPrice);
    position.setFloatingPnl(floatingPnl);
    position.setNotional(margin.notional());
    position.setInitialMargin(margin.initialMargin());
    position.setMaintenanceMargin(margin.maintenanceMargin());
    position.setSettlementAsset(profile.settlementAsset());
    position.setMarginAsset(profile.marginAsset());
    positionRepository.save(position);

    return new PerpRiskPosition(
        position,
        profile.kind(),
        markPrice,
        floatingPnl,
        margin.notional(),
        margin.maintenanceMargin(),
        liquidationFee,
        profile.settlementAsset());
  }

  private int positionLeverage(PositionEntity position, TradingAccountEntity account) {
    if (position.getLeverage() != null && position.getLeverage() > 0) {
      return position.getLeverage();
    }
    return account.getLeverage() != null && account.getLeverage() > 0 ? account.getLeverage() : 1;
  }

  private void chargeLiquidationFee(UUID accountId, PerpRiskPosition risk) {
    if (risk == null || risk.liquidationFee().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    BigDecimal balance = orZero(account.getBalance()).subtract(risk.liquidationFee()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal equity = accountEquity(account).subtract(risk.liquidationFee()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    account.setBalance(balance);
    account.setEquity(equity);
    account.setFreeMargin(equity.subtract(orZero(account.getUsedMargin())).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    accountRepository.save(account);

    if (risk.kind() == InstrumentKind.INVERSE_PERPETUAL && StringUtils.hasText(risk.settlementAsset()) && walletService != null) {
      walletService.debitAvailableWithEntryType(
          accountId,
          risk.settlementAsset(),
          risk.liquidationFee(),
          POSITION_REFERENCE,
          risk.position().getId(),
          LIQUIDATION_FEE_DESCRIPTION,
          LIQUIDATION_FEE_ENTRY_TYPE);
    }
    ledgerService.recordLiquidationFee(
        account,
        risk.liquidationFee(),
        risk.position().getId(),
        LIQUIDATION_FEE_DESCRIPTION);
  }

  private BigDecimal riskContribution(PerpRiskPosition risk) {
    return risk.threshold().add(lossAmount(risk.position()));
  }

  private BigDecimal lossAmount(PositionEntity position) {
    BigDecimal floatingPnl = orZero(position.getFloatingPnl());
    return floatingPnl.compareTo(BigDecimal.ZERO) < 0 ? floatingPnl.negate() : BigDecimal.ZERO;
  }

  private SymbolEntity symbolFor(PositionEntity position) {
    String normalized = normalize(position.getSymbol());
    return symbolRepository.findBySymbol(normalized)
        .orElseGet(() -> fallbackSymbol(normalized));
  }

  private SymbolEntity fallbackSymbol(String symbol) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    entity.setAssetClass(fallbackAssetClass(symbol));
    entity.setBaseCurrency(baseCurrency(symbol));
    entity.setQuoteCurrency(quoteCurrency(symbol));
    entity.setLotSize("USD".equals(quoteCurrency(symbol)) ? new BigDecimal("100") : BigDecimal.ONE);
    entity.setContractSize(entity.getLotSize());
    entity.setContractMultiplier(BigDecimal.ONE);
    entity.setSettlementAsset("USD".equals(quoteCurrency(symbol)) ? entity.getBaseCurrency() : entity.getQuoteCurrency());
    entity.setMarginAsset(entity.getSettlementAsset());
    return entity;
  }

  private String fallbackAssetClass(String symbol) {
    if (hasCryptoBase(symbol, "USDT") || hasCryptoBase(symbol, "USDC")) {
      return "LINEAR_PERPETUAL";
    }
    if (hasCryptoBase(symbol, "USD")) {
      return "INVERSE_PERPETUAL";
    }
    return "FOREX";
  }

  private String baseCurrency(String symbol) {
    String quoteCurrency = quoteCurrency(symbol);
    if (quoteCurrency.isBlank() || symbol.length() <= quoteCurrency.length()) {
      return "";
    }
    return symbol.substring(0, symbol.length() - quoteCurrency.length());
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
    if (symbol.length() >= 6) {
      return symbol.substring(symbol.length() - 3);
    }
    return "";
  }

  private boolean hasCryptoBase(String symbol, String quoteSuffix) {
    if (!symbol.endsWith(quoteSuffix) || symbol.length() <= quoteSuffix.length()) {
      return false;
    }
    return CRYPTO_BASES.contains(symbol.substring(0, symbol.length() - quoteSuffix.length()));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private record ClassifiedPosition(
      PositionEntity position,
      InstrumentProfile profile,
      SymbolEntity symbol
  ) {
    InstrumentKind kind() {
      return profile.kind();
    }

    boolean isPerpetual() {
      return kind() == InstrumentKind.LINEAR_PERPETUAL || kind() == InstrumentKind.INVERSE_PERPETUAL;
    }
  }

  private record PerpRiskPosition(
      PositionEntity position,
      InstrumentKind kind,
      BigDecimal markPrice,
      BigDecimal floatingPnl,
      BigDecimal notional,
      BigDecimal maintenanceMargin,
      BigDecimal liquidationFee,
      String settlementAsset
  ) {
    BigDecimal threshold() {
      return maintenanceMargin.add(liquidationFee);
    }
  }

  private record RiskSnapshot(
      AccountSnapshot accountSnapshot,
      List<ClassifiedPosition> classifiedPositions,
      List<PerpRiskPosition> perpRisks,
      BigDecimal perpEquity,
      BigDecimal perpThreshold
  ) {
  }

  private record LiquidationCandidate(
      PositionEntity position,
      String reason,
      PerpRiskPosition risk,
      BigDecimal sortValue
  ) {
  }
}
