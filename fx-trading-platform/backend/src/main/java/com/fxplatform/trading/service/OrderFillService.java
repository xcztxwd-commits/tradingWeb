package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderFillService {

  private static final int MONEY_SCALE = 8;
  private final OrderRepository orderRepository;
  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final SymbolRepository symbolRepository;
  private final SpotSettlementService spotSettlementService;
  private final PositionEngine positionEngine;
  private final WalletService walletService;
  private final MarginCalculator marginCalculator = new MarginCalculator();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private static final Set<String> CRYPTO_BASES = Set.of(
      "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "OKB", "BCH", "LTC");

  @Autowired
  public OrderFillService(
      OrderRepository orderRepository,
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      SpotSettlementService spotSettlementService,
      PositionEngine positionEngine,
      WalletService walletService
  ) {
    this.orderRepository = orderRepository;
    this.tradeRepository = tradeRepository;
    this.positionRepository = positionRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.symbolRepository = symbolRepository;
    this.spotSettlementService = spotSettlementService;
    this.positionEngine = positionEngine;
    this.walletService = walletService;
  }

  public OrderFillService(
      OrderRepository orderRepository,
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      SpotSettlementService spotSettlementService
  ) {
    this(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService,
        defaultPositionEngine(positionRepository, accountRepository, ledgerService),
        null);
  }

  public OrderFillService(
      OrderRepository orderRepository,
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      SpotSettlementService spotSettlementService,
      WalletService walletService
  ) {
    this(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService,
        defaultPositionEngine(positionRepository, accountRepository, ledgerService),
        walletService);
  }

  public OrderFillService(
      OrderRepository orderRepository,
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      SpotSettlementService spotSettlementService,
      PositionEngine positionEngine
  ) {
    this(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService,
        positionEngine,
        null);
  }

  public OrderFillService(
      OrderRepository orderRepository,
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      SymbolRepository symbolRepository
  ) {
    this(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService, symbolRepository, null);
  }

  public OrderFillService(
      OrderRepository orderRepository,
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService
  ) {
    this(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService, null);
  }

  public OrderEntity fill(
      OrderEntity order,
      TradingAccountEntity account,
      BigDecimal executionPrice,
      Instant filledAt,
      BigDecimal requiredMargin,
      String marginDescription
  ) {
    BigDecimal quantity = expectedFullQuantity(order);
    return fill(
        order,
        account,
        new ExecutionResult(
            executionPrice,
            filledAt,
            quantity,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            BigDecimal.ZERO,
            null,
            null),
        requiredMargin,
        marginDescription);
  }

  public OrderEntity fill(
      OrderEntity order,
      TradingAccountEntity account,
      FullFillResult fullFill,
      BigDecimal requiredMargin,
      String marginDescription
  ) {
    return fill(order, order, account, fullFill, requiredMargin, marginDescription);
  }

  /**
   * Linear Perpetual full-fill entry point. The shell initially delegates to the historical fill
   * path; the authority mark is made explicit so its position snapshot behavior can be driven by
   * focused tests without changing Spot or legacy products.
   */
  public OrderEntity fillPerpetual(
      OrderEntity order,
      TradingAccountEntity account,
      FullFillResult fullFill,
      BigDecimal authorityMark,
      int executionLeverage,
      String marginDescription
  ) {
    requireFullFill(order, fullFill == null ? null : fullFill.filledQuantity(),
        fullFill == null ? null : fullFill.remainingQuantity());
    return fillInternal(
        order,
        order,
        account,
        fullFill.toExecutionResult(),
        null,
        marginDescription,
        fullFill,
        authorityMark,
        executionLeverage);
  }

  public OrderEntity fill(
      OrderEntity order,
      OrderEntity holdOwner,
      TradingAccountEntity account,
      FullFillResult fullFill,
      BigDecimal requiredMargin,
      String marginDescription
  ) {
    requireFullFill(order, fullFill == null ? null : fullFill.filledQuantity(),
        fullFill == null ? null : fullFill.remainingQuantity());
    return fillInternal(
        order,
        holdOwner,
        account,
        fullFill.toExecutionResult(),
        requiredMargin,
        marginDescription,
        fullFill,
        null,
        null);
  }

  public OrderEntity fill(
      OrderEntity order,
      TradingAccountEntity account,
      ExecutionResult execution,
      BigDecimal requiredMargin,
      String marginDescription
  ) {
    requireFullFill(order,
        execution == null ? null : execution.filledQuantity(),
        execution == null ? null : execution.remainingQuantity());
    return fillInternal(
        order, order, account, execution, requiredMargin, marginDescription, null, null, null);
  }

  private OrderEntity fillInternal(
      OrderEntity order,
      OrderEntity holdOwner,
      TradingAccountEntity account,
      ExecutionResult execution,
      BigDecimal requiredMargin,
      String marginDescription,
      FullFillResult canonicalFill,
      BigDecimal authorityMark,
      Integer executionLeverage
  ) {
    BigDecimal orderQuantity = expectedFullQuantity(order);
    BigDecimal filledQuantity = execution.filledQuantity();
    BigDecimal remainingQuantity = BigDecimal.ZERO;
    BigDecimal fee = authorityMark == null
        ? orZero(execution.fee())
        : orZero(execution.fee()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal slippage = orZero(execution.slippage());
    OrderEntity effectiveHoldOwner = holdOwner == null ? order : holdOwner;
    BigDecimal existingOrderHold = orZero(effectiveHoldOwner.getHoldAmount());
    BigDecimal fullMargin = requiredMargin != null ? requiredMargin : existingOrderHold;
    SymbolEntity symbol = symbolFor(order);
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    boolean canonicalPerpetual = profile.kind() == InstrumentKind.LINEAR_PERPETUAL
        && authorityMark != null;
    BigDecimal marginToHold = canonicalPerpetual
        ? BigDecimal.ZERO
        : executionMargin(order, account, execution.filledPrice(), filledQuantity)
            .orElseGet(() -> proportionalMargin(fullMargin, filledQuantity, orderQuantity));

    order.setStatus(OrderStatus.FILLED);
    order.setExecutionPrice(execution.filledPrice());
    order.setAvgFillPrice(execution.filledPrice());
    order.setFilledQuantity(filledQuantity);
    order.setRemainingQuantity(remainingQuantity);
    order.setFee(fee);
    order.setFeeAsset(execution.feeAsset());
    if (canonicalFill != null) {
      order.setLiquidityRole(canonicalFill.liquidityRole());
    }
    order.setSlippage(slippage);
    order.setFilledAt(execution.filledAt());
    orderRepository.save(order);

    TradeEntity trade = new TradeEntity();
    trade.setOrderId(order.getId());
    trade.setAccountId(account.getId());
    trade.setSymbol(order.getSymbol());
    trade.setProductType(order.getProductType());
    trade.setPositionSide(order.getPositionSide());
    trade.setMarginMode(order.getMarginMode());
    trade.setSide(order.getSide());
    trade.setLots(filledQuantity);
    trade.setPrice(execution.filledPrice());
    trade.setFee(fee);
    trade.setFeeAsset(execution.feeAsset());
    trade.setLiquidityRole(canonicalFill == null ? order.getLiquidityRole() : canonicalFill.liquidityRole());
    trade.setSystemReason(order.getSystemReason());
    if (canonicalFill != null) {
      trade.setSourceMode(canonicalFill.sourceMode().name());
      trade.setProviderCode(canonicalFill.providerCode());
    }
    trade.setExecutedAt(execution.filledAt());
    if (trade.getId() == null) {
      trade.setId(UUID.randomUUID());
    }

    if (!canonicalPerpetual) {
      tradeRepository.save(trade);
    }
    if (profile.kind() == InstrumentKind.SPOT) {
      settleSpotFill(order, effectiveHoldOwner, account, execution, symbol, filledQuantity, trade.getId());
      if (existingOrderHold.compareTo(BigDecimal.ZERO) > 0) {
        effectiveHoldOwner.setHoldAmount(BigDecimal.ZERO);
        orderRepository.save(effectiveHoldOwner);
      }
      return order;
    }
    ExecutionResult normalizedFill = normalizeFill(execution, filledQuantity, fee);
    if (isNetPositionKind(profile.kind())) {
      if (canonicalPerpetual) {
        PositionEngine.PositionUpdateResult positionUpdate = positionEngine.applyPerpetualFill(
            account,
            order,
            normalizedFill,
            profile,
            authorityMark,
            executionLeverage,
            marginDescription);
        trade.setRealizedPnl(positionUpdate.realizedPnlDelta());
        tradeRepository.save(trade);
        effectiveHoldOwner.setHoldAmount(BigDecimal.ZERO);
        orderRepository.save(effectiveHoldOwner);
        applyCanonicalPerpetualTradeFee(account, fee);
        positionEngine.recordPerpetualFillLedger(account, positionUpdate);
        recordCanonicalPerpetualTradeFee(account, fee, trade.getId());
        return order;
      }
      positionEngine.applyFill(
          account,
          order,
          normalizedFill,
          profile,
          marginDescription);
      chargeTradeFee(account, fee, execution.feeAsset(), profile.kind(), trade.getId());
      return order;
    }

    PositionEntity position = new PositionEntity();
    position.setAccountId(account.getId());
    position.setSymbol(order.getSymbol());
    position.setSide(order.getSide());
    position.setLots(filledQuantity);
    position.setOpenPrice(execution.filledPrice());
    position.setCurrentPrice(execution.filledPrice());
    position.setStopLoss(order.getStopLoss());
    position.setTakeProfit(order.getTakeProfit());
    position.setLeverage(positionLeverage(order, account));
    if (isPerpetual(profile.kind())) {
      marginToHold = applyPerpMarginSnapshot(position, profile, filledQuantity, execution.filledPrice(), positionLeverage(order, account));
    }
    position.setMarginHeld(marginToHold);
    PositionEntity savedPosition = positionRepository.save(position);

    BigDecimal delta = marginToHold.subtract(existingOrderHold);
    boolean accountChanged = false;
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      if (accountRepository.reserveMarginIfAvailable(account.getId(), delta) != 1) {
        throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
      }
      account.setUsedMargin(orZero(account.getUsedMargin()).add(delta));
      accountChanged = true;
    } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
      account.setUsedMargin(orZero(account.getUsedMargin()).add(delta).max(BigDecimal.ZERO));
      accountChanged = true;
    }
    if (accountChanged) {
      account.setFreeMargin(accountEquity(account).subtract(orZero(account.getUsedMargin())));
      accountRepository.save(account);
    }
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      ledgerService.recordMarginHold(account, delta, savedPosition.getId(), marginDescription);
    }
    chargeTradeFee(account, fee, execution.feeAsset(), profile.kind(), trade.getId());

    return order;
  }

  private void requireFullFill(
      OrderEntity order,
      BigDecimal filledQuantity,
      BigDecimal remainingQuantity
  ) {
    BigDecimal expected = expectedFullQuantity(order);
    if (expected == null
        || filledQuantity == null
        || remainingQuantity == null
        || filledQuantity.compareTo(expected) != 0
        || remainingQuantity.compareTo(BigDecimal.ZERO) != 0) {
      throw new BusinessException(
          ErrorCode.PARTIAL_FILL_NOT_SUPPORTED,
          "Demo execution supports one full fill only");
    }
  }

  private BigDecimal expectedFullQuantity(OrderEntity order) {
    if (order == null) {
      return null;
    }
    if (order.getBaseQuantity() != null) {
      return order.getBaseQuantity();
    }
    return order.getQuantity() != null ? order.getQuantity() : order.getLots();
  }

  private boolean isNetPositionKind(InstrumentKind kind) {
    return kind == InstrumentKind.FOREX
        || kind == InstrumentKind.LINEAR_PERPETUAL
        || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private boolean isPerpetual(InstrumentKind kind) {
    return kind == InstrumentKind.LINEAR_PERPETUAL || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private BigDecimal applyPerpMarginSnapshot(
      PositionEntity position,
      InstrumentProfile profile,
      BigDecimal quantity,
      BigDecimal markPrice,
      int leverage
  ) {
    PerpMarginCalculator.MarginResult margin = perpMarginCalculator.calculate(profile, quantity, markPrice, leverage);
    position.setNotional(margin.notional());
    position.setInitialMargin(margin.initialMargin());
    position.setMaintenanceMargin(margin.maintenanceMargin());
    position.setMarkPrice(markPrice);
    position.setSettlementAsset(profile.settlementAsset());
    position.setMarginAsset(profile.marginAsset());
    return margin.initialMargin();
  }

  private void chargeTradeFee(
      TradingAccountEntity account,
      BigDecimal fee,
      String feeAsset,
      InstrumentKind kind,
      java.util.UUID tradeId
  ) {
    if (fee.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    String normalizedFeeAsset = normalizeFeeAsset(feeAsset);
    if (kind == InstrumentKind.INVERSE_PERPETUAL && normalizedFeeAsset != null && walletService != null) {
      walletService.debitAvailableWithEntryType(
          account.getId(),
          normalizedFeeAsset,
          fee,
          "TRADE",
          tradeId,
          "Inverse perpetual trade fee charged",
          "TRADE_FEE");
    }
    BigDecimal balanceBeforeFee = orZero(account.getBalance());
    BigDecimal equityBeforeFee = accountEquity(account);
    account.setBalance(balanceBeforeFee.subtract(fee));
    account.setEquity(equityBeforeFee.subtract(fee));
    account.setFreeMargin(accountEquity(account).subtract(orZero(account.getUsedMargin())));
    accountRepository.save(account);
    ledgerService.recordTradeFeeForTrade(account, fee, tradeId, "Trade fee charged");
  }

  private void applyCanonicalPerpetualTradeFee(
      TradingAccountEntity account,
      BigDecimal fee
  ) {
    if (fee.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    account.setBalance(orZero(account.getBalance()).subtract(fee));
    account.setEquity(accountEquity(account).subtract(fee));
    account.setFreeMargin(orZero(account.getFreeMargin()).subtract(fee));
    accountRepository.save(account);
  }

  private void recordCanonicalPerpetualTradeFee(
      TradingAccountEntity account,
      BigDecimal fee,
      java.util.UUID tradeId
  ) {
    if (fee.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    ledgerService.recordTradeFeeForTrade(account, fee, tradeId, "Trade fee charged");
  }

  private BigDecimal proportionalMargin(BigDecimal fullMargin, BigDecimal filledQuantity, BigDecimal orderQuantity) {
    if (orderQuantity == null || orderQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return orZero(fullMargin)
        .multiply(orZero(filledQuantity))
        .divide(orderQuantity, 8, RoundingMode.HALF_UP);
  }

  private java.util.Optional<BigDecimal> executionMargin(
      OrderEntity order,
      TradingAccountEntity account,
      BigDecimal executionPrice,
      BigDecimal filledQuantity
  ) {
    if (executionPrice == null || executionPrice.compareTo(BigDecimal.ZERO) <= 0) {
      return java.util.Optional.empty();
    }
    if (filledQuantity == null || filledQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      return java.util.Optional.empty();
    }

    InstrumentProfile profile = instrumentClassifier.profile(symbolFor(order));
    return java.util.Optional.of(marginCalculator.requiredMargin(
        profile.kind(),
        filledQuantity,
        executionPrice,
        positionLeverage(order, account),
        profile.unitSize()));
  }

  private void settleSpotFill(
      OrderEntity order,
      OrderEntity holdOwner,
      TradingAccountEntity account,
      ExecutionResult execution,
      SymbolEntity symbol,
      BigDecimal filledQuantity,
      java.util.UUID tradeId
  ) {
    ExecutionResult fill = normalizeFill(execution, filledQuantity);
    if (spotSettlementService != null) {
      if (order.getSide() == com.fxplatform.trading.enums.OrderSide.BUY) {
        if (holdOwner == order) {
          spotSettlementService.settleBuyFill(order, fill, symbol, account, tradeId);
        } else {
          spotSettlementService.settleBuyFill(order, holdOwner, fill, symbol, account, tradeId);
        }
      } else {
        if (holdOwner == order) {
          spotSettlementService.settleSellFill(order, fill, symbol, account, tradeId);
        } else {
          spotSettlementService.settleSellFill(order, holdOwner, fill, symbol, account, tradeId);
        }
      }
    }
  }

  private ExecutionResult normalizeFill(ExecutionResult execution, BigDecimal filledQuantity) {
    return normalizeFill(execution, filledQuantity, execution.fee());
  }

  private ExecutionResult normalizeFill(
      ExecutionResult execution,
      BigDecimal filledQuantity,
      BigDecimal fee
  ) {
    return new ExecutionResult(
        execution.filledPrice(),
        execution.filledAt(),
        execution.filledQuantity() == null ? filledQuantity : execution.filledQuantity(),
        execution.remainingQuantity(),
        fee,
        execution.feeAsset(),
        execution.slippage(),
        execution.rejectCode(),
        execution.rejectMessage());
  }

  private String normalizeFeeAsset(String feeAsset) {
    if (feeAsset == null || feeAsset.isBlank()) {
      return null;
    }
    return feeAsset.trim().toUpperCase(Locale.ROOT);
  }

  private Integer positionLeverage(OrderEntity order, TradingAccountEntity account) {
    if (order.getLeverage() != null && order.getLeverage() > 0) {
      return order.getLeverage();
    }
    return account.getLeverage();
  }

  private SymbolEntity symbolFor(OrderEntity order) {
    String symbol = order.getSymbol();
    String normalized = symbol == null ? "" : symbol.trim().toUpperCase();
    if (symbolRepository != null && !normalized.isBlank()) {
      return symbolRepository.findBySymbol(normalized).orElseGet(() -> fallbackSymbol(order, normalized));
    }
    return fallbackSymbol(order, normalized);
  }

  private SymbolEntity fallbackSymbol(OrderEntity order, String symbol) {
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    entity.setAssetClass(null);
    if (order.getLeverage() != null && order.getLeverage() <= 1 && isCryptoSymbol(symbol)) {
      entity.setAssetClass("SPOT");
      entity.setBaseCurrency(baseCurrency(symbol));
      entity.setQuoteCurrency(quoteCurrency(symbol));
      entity.setLotSize(BigDecimal.ONE);
      entity.setLeverage(1);
    }
    return entity;
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

  private static PositionEngine defaultPositionEngine(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService
  ) {
    return new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new MarginCalculator(),
        new PnLCalculator());
  }
}
