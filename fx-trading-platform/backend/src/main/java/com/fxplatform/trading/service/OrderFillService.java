package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SymbolAssets;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.SymbolAssetResolver;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderFillService {

  private static final int MONEY_SCALE = 8;
  private static final int MONEY_PRECISION = 24;
  private static final int FILL_IDENTITY_MAX_LENGTH = 64;
  private static final int PROVIDER_CODE_MAX_LENGTH = 32;
  private static final int TRADE_QUANTITY_PRECISION = 12;
  private static final int TRADE_QUANTITY_SCALE = 4;
  private static final int TRADE_PRICE_PRECISION = 24;
  private static final int TRADE_PRICE_SCALE = 10;
  private final OrderRepository orderRepository;
  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final SymbolRepository symbolRepository;
  private final SpotSettlementService spotSettlementService;
  private final PositionEngine positionEngine;
  private final WalletService walletService;
  private final ProtectionOrderService protectionOrderService;
  private ApplicationEventPublisher accountMutationPublisher;
  private final MarginCalculator marginCalculator = new MarginCalculator();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private static final Set<String> CRYPTO_BASES = Set.of(
      "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "OKB", "BCH", "LTC");

  void recordPerpetualOrderHoldIncrease(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID orderId
  ) {
    ledgerService.recordOrderHold(
        account,
        amount,
        orderId,
        "Pending Perpetual trigger margin increased");
  }

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
      WalletService walletService,
      ProtectionOrderService protectionOrderService
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
    this.protectionOrderService = protectionOrderService;
  }

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
    this(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService,
        positionEngine,
        walletService,
        null);
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

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderEntity applyFill(
      OrderEntity lockedOrder,
      OrderEntity lockedHoldOwner,
      TradingAccountEntity lockedAccount,
      DemoMatchFill fill,
      String fillIdentity,
      ExecutableMarketSnapshot snapshot,
      DemoExecutionPolicy policy,
      BigDecimal holdAfterFill
  ) {
    return applyFill(
        lockedOrder,
        lockedHoldOwner,
        lockedAccount,
        fill,
        fillIdentity,
        snapshot,
        policy,
        holdAfterFill,
        false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderEntity applyFill(
      OrderEntity lockedOrder,
      OrderEntity lockedHoldOwner,
      TradingAccountEntity lockedAccount,
      DemoMatchFill fill,
      String fillIdentity,
      ExecutableMarketSnapshot snapshot,
      DemoExecutionPolicy policy,
      BigDecimal holdAfterFill,
      boolean parentTerminalAfterBatch
  ) {
    if (lockedOrder != null && lockedOrder.getProductType() == ProductType.LINEAR_PERP) {
      return applyPerpetualDepthFill(
          lockedOrder,
          lockedHoldOwner,
          lockedAccount,
          fill,
          fillIdentity,
          snapshot,
          policy,
          holdAfterFill,
          parentTerminalAfterBatch);
    }
    validateDepthFillInputs(
        lockedOrder, lockedHoldOwner, lockedAccount, fill, fillIdentity, snapshot, policy);
    BigDecimal quantity = money(fill.quantity());
    BigDecimal price = fill.price();
    BigDecimal grossQuote = money(quantity.multiply(price));
    BigDecimal perFillFee = money(quantity.multiply(price).multiply(fill.feeRate()));
    requireDepthNumeric(
        grossQuote, MONEY_PRECISION, MONEY_SCALE,
        "Fill gross quote is not representable by wallet money columns");
    requireDepthNumeric(
        perFillFee, MONEY_PRECISION, MONEY_SCALE,
        "Fill fee is not representable by wallet money columns");

    java.util.Optional<TradeEntity> existing = tradeRepository.findByOrderIdAndFillIdentity(
        lockedOrder.getId(), fillIdentity);
    if (existing.isPresent()) {
      if (sameFillPayload(
          existing.get(), lockedOrder, lockedAccount, fillIdentity,
          quantity, price, perFillFee, fill.liquidityRole())) {
        return lockedOrder;
      }
      throw new BusinessException(
          ErrorCode.FILL_IDENTITY_CONFLICT,
          "Fill identity is already associated with a different payload");
    }

    validateNewDepthFillState(lockedOrder, fill);
    if (lockedHoldOwner.getHoldAmount() == null || holdAfterFill == null) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Spot DEPTH fill requires current and future hold amounts");
    }
    BigDecimal oldFilled = money(orZero(lockedOrder.getFilledQuantity()));
    BigDecimal oldAverage = money(orZero(lockedOrder.getAvgFillPrice()));
    BigDecimal oldRemaining = money(lockedOrder.getRemainingQuantity());
    BigDecimal newFilled = money(oldFilled.add(quantity));
    BigDecimal newRemaining = money(oldRemaining.subtract(quantity));
    BigDecimal holdBeforeFill = money(lockedHoldOwner.getHoldAmount());
    BigDecimal nextHold = money(holdAfterFill);
    BigDecimal lockedSpend = lockedOrder.getSide() == OrderSide.BUY
        ? money(grossQuote.add(perFillFee))
        : quantity;
    BigDecimal release = money(holdBeforeFill.subtract(lockedSpend).subtract(nextHold));
    BigDecimal average = newFilled.signum() == 0
        ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        : oldFilled.multiply(oldAverage)
            .add(quantity.multiply(price))
            .divide(newFilled, MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal feeTotal = money(orZero(lockedOrder.getFee()).add(perFillFee));
    requireDepthNumeric(
        newFilled, TRADE_QUANTITY_PRECISION, TRADE_QUANTITY_SCALE,
        "Cumulative fill quantity is not representable by trading.orders.filled_quantity");
    requireDepthNumeric(
        newRemaining, TRADE_QUANTITY_PRECISION, TRADE_QUANTITY_SCALE,
        "Remaining quantity is not representable by trading.orders.remaining_quantity");
    requireDepthNumeric(
        average, TRADE_PRICE_PRECISION, TRADE_PRICE_SCALE,
        "Average fill price is not representable by trading.orders.avg_fill_price");
    requireDepthNumeric(
        feeTotal, MONEY_PRECISION, MONEY_SCALE,
        "Cumulative fee is not representable by trading.orders.fee");
    requireDepthNumeric(
        holdBeforeFill, MONEY_PRECISION, MONEY_SCALE,
        "Current order hold is not representable by wallet money columns");
    requireDepthNumeric(
        nextHold, MONEY_PRECISION, MONEY_SCALE,
        "Future order hold is not representable by wallet money columns");
    requireDepthNumeric(
        lockedSpend, MONEY_PRECISION, MONEY_SCALE,
        "Locked spend is not representable by wallet money columns");
    requireDepthNumeric(
        release, MONEY_PRECISION, MONEY_SCALE,
        "Released hold is not representable by wallet money columns");

    validateDepthSnapshot(lockedOrder, snapshot);
    SymbolEntity symbol = strictDepthSymbol(lockedOrder);
    SymbolAssets assets = SymbolAssetResolver.resolve(symbol);
    String expectedHoldCurrency = lockedOrder.getSide() == OrderSide.BUY
        ? assets.quoteAsset()
        : assets.baseAsset();
    validateDepthHold(
        lockedHoldOwner,
        expectedHoldCurrency,
        holdBeforeFill,
        nextHold,
        newRemaining,
        lockedSpend);
    if (spotSettlementService == null || walletService == null) {
      throw invalidDepthFill("Spot DEPTH settlement services are unavailable");
    }
    UUID tradeId = DemoFillIdentity.tradeId(lockedOrder.getId(), fillIdentity);
    TradeEntity trade = depthTrade(
        lockedOrder, lockedAccount, fill, fillIdentity, snapshot,
        quantity, price, perFillFee, tradeId);

    tradeRepository.save(trade);
    ExecutionResult execution = new ExecutionResult(
        price,
        snapshot.asOf(),
        quantity,
        newRemaining,
        perFillFee,
        "USDT",
        BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        null,
        null);
    if (lockedOrder.getSide() == OrderSide.BUY) {
      spotSettlementService.settleBuyPartialFill(
          lockedOrder, lockedHoldOwner, execution, symbol, lockedAccount, tradeId);
    } else {
      spotSettlementService.settleSellPartialFill(
          lockedOrder, lockedHoldOwner, execution, symbol, lockedAccount, tradeId);
    }
    if (release.signum() > 0) {
      walletService.releaseLockedWithEntryType(
          lockedAccount.getId(),
          expectedHoldCurrency,
          release,
          "TRADE",
          tradeId,
          "Spot order hold released after DEPTH fill",
          "SPOT_ORDER_RELEASE");
    }

    lockedOrder.setStatus(newRemaining.signum() == 0
        ? OrderStatus.FILLED
        : OrderStatus.PARTIALLY_FILLED);
    lockedOrder.setExecutionPrice(price);
    lockedOrder.setAvgFillPrice(average);
    lockedOrder.setFilledQuantity(newFilled);
    lockedOrder.setRemainingQuantity(newRemaining);
    lockedOrder.setFee(feeTotal);
    lockedOrder.setFeeAsset("USDT");
    lockedOrder.setLiquidityRole(fill.liquidityRole());
    if (newRemaining.signum() == 0) {
      lockedOrder.setFilledAt(snapshot.asOf());
    }
    lockedHoldOwner.setHoldAmount(nextHold);
    if (lockedHoldOwner != lockedOrder) {
      orderRepository.save(lockedHoldOwner);
    }
    orderRepository.save(lockedOrder);
    publishFillEvents(lockedOrder, lockedAccount, trade, null);
    return lockedOrder;
  }

  private OrderEntity applyPerpetualDepthFill(
      OrderEntity lockedOrder,
      OrderEntity lockedHoldOwner,
      TradingAccountEntity lockedAccount,
      DemoMatchFill fill,
      String fillIdentity,
      ExecutableMarketSnapshot snapshot,
      DemoExecutionPolicy policy,
      BigDecimal holdAfterFill,
      boolean parentTerminalAfterBatch
  ) {
    validatePerpetualDepthFillInputs(
        lockedOrder, lockedHoldOwner, lockedAccount, fill, fillIdentity, snapshot, policy);
    BigDecimal quantity = money(fill.quantity());
    BigDecimal price = fill.price();
    BigDecimal grossQuote = money(quantity.multiply(price));
    BigDecimal perFillFee = money(quantity.multiply(price).multiply(fill.feeRate()));
    requireDepthNumeric(
        grossQuote, MONEY_PRECISION, MONEY_SCALE,
        "Perpetual fill gross notional is not representable by account money columns");
    requireDepthNumeric(
        perFillFee, MONEY_PRECISION, MONEY_SCALE,
        "Perpetual fill fee is not representable by account money columns");

    java.util.Optional<TradeEntity> existing = tradeRepository.findByOrderIdAndFillIdentity(
        lockedOrder.getId(), fillIdentity);
    if (existing.isPresent()) {
      if (sameFillPayload(
          existing.get(), lockedOrder, lockedAccount, fillIdentity,
          quantity, price, perFillFee, fill.liquidityRole())) {
        return lockedOrder;
      }
      throw new BusinessException(
          ErrorCode.FILL_IDENTITY_CONFLICT,
          "Fill identity is already associated with a different payload");
    }

    validateNewDepthFillState(lockedOrder, fill);
    if (lockedOrder.getHoldAmount() == null || holdAfterFill == null) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Perpetual DEPTH fill requires current and future hold amounts");
    }
    requireDepthNumeric(
        holdAfterFill, MONEY_PRECISION, MONEY_SCALE,
        "Future Perpetual order hold is not exactly representable by account money columns");
    BigDecimal oldFilled = money(orZero(lockedOrder.getFilledQuantity()));
    BigDecimal oldAverage = money(orZero(lockedOrder.getAvgFillPrice()));
    BigDecimal oldRemaining = money(lockedOrder.getRemainingQuantity());
    BigDecimal newFilled = money(oldFilled.add(quantity));
    BigDecimal newRemaining = money(oldRemaining.subtract(quantity));
    BigDecimal holdBeforeFill = money(lockedOrder.getHoldAmount());
    BigDecimal nextHold = money(holdAfterFill);
    BigDecimal consumedHold = money(holdBeforeFill.subtract(nextHold));
    BigDecimal average = oldFilled.multiply(oldAverage)
        .add(quantity.multiply(price))
        .divide(newFilled, MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal feeTotal = money(orZero(lockedOrder.getFee()).add(perFillFee));

    requireDepthNumeric(
        newFilled, TRADE_QUANTITY_PRECISION, TRADE_QUANTITY_SCALE,
        "Cumulative fill quantity is not representable by trading.orders.filled_quantity");
    requireDepthNumeric(
        newRemaining, TRADE_QUANTITY_PRECISION, TRADE_QUANTITY_SCALE,
        "Remaining quantity is not representable by trading.orders.remaining_quantity");
    requireDepthNumeric(
        average, TRADE_PRICE_PRECISION, TRADE_PRICE_SCALE,
        "Average fill price is not representable by trading.orders.avg_fill_price");
    requireDepthNumeric(
        feeTotal, MONEY_PRECISION, MONEY_SCALE,
        "Cumulative fee is not representable by trading.orders.fee");
    requireDepthNumeric(
        holdBeforeFill, MONEY_PRECISION, MONEY_SCALE,
        "Current Perpetual order hold is not representable by account money columns");
    requireDepthNumeric(
        nextHold, MONEY_PRECISION, MONEY_SCALE,
        "Future Perpetual order hold is not representable by account money columns");
    requireDepthNumeric(
        consumedHold, MONEY_PRECISION, MONEY_SCALE,
        "Consumed Perpetual order hold is not representable by account money columns");
    requireDepthNumeric(
        snapshot.mark(), TRADE_PRICE_PRECISION, TRADE_PRICE_SCALE,
        "Perpetual authority mark is not representable by position price columns");
    validatePerpetualDepthHold(
        lockedOrder, holdBeforeFill, nextHold, consumedHold, newRemaining);
    SymbolEntity symbol = strictPerpetualDepthSymbol(lockedOrder);
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    if (profile.kind() != InstrumentKind.LINEAR_PERPETUAL) {
      throw symbolNotTradable(
          "Perpetual DEPTH symbol does not resolve to a Linear Perpetual profile");
    }
    if (positionEngine == null) {
      throw invalidDepthFill("Perpetual DEPTH position service is unavailable");
    }

    ExecutionResult execution = new ExecutionResult(
        price,
        snapshot.asOf(),
        quantity,
        newRemaining,
        perFillFee,
        "USDT",
        BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        null,
        null);
    PositionEngine.PositionUpdateResult positionUpdate = positionEngine.applyPerpetualFill(
        lockedAccount,
        lockedOrder,
        execution,
        profile,
        snapshot.mark(),
        lockedOrder.getLeverage(),
        consumedHold,
        "Perpetual DEPTH fill");

    UUID tradeId = DemoFillIdentity.tradeId(lockedOrder.getId(), fillIdentity);
    TradeEntity trade = depthTrade(
        lockedOrder, lockedAccount, fill, fillIdentity, snapshot,
        quantity, price, perFillFee, tradeId);
    trade.setRealizedPnl(money(orZero(positionUpdate.realizedPnlDelta())));
    tradeRepository.save(trade);

    lockedOrder.setStatus(newRemaining.signum() == 0
        ? OrderStatus.FILLED
        : OrderStatus.PARTIALLY_FILLED);
    lockedOrder.setExecutionPrice(price);
    lockedOrder.setAvgFillPrice(average);
    lockedOrder.setFilledQuantity(newFilled);
    lockedOrder.setRemainingQuantity(newRemaining);
    lockedOrder.setFee(feeTotal);
    lockedOrder.setFeeAsset("USDT");
    lockedOrder.setLiquidityRole(fill.liquidityRole());
    lockedOrder.setHoldAmount(nextHold);
    if (newRemaining.signum() == 0) {
      lockedOrder.setFilledAt(snapshot.asOf());
    }
    orderRepository.save(lockedOrder);

    applyCanonicalPerpetualTradeFee(lockedAccount, perFillFee);
    positionEngine.recordPerpetualFillLedger(lockedAccount, positionUpdate);
    recordCanonicalPerpetualTradeFee(lockedAccount, perFillFee, tradeId);
    if (protectionOrderService != null) {
      protectionOrderService.afterPerpetualFillLocked(
          lockedOrder,
          positionUpdate,
          snapshot.mark(),
          newRemaining.signum() == 0 || parentTerminalAfterBatch);
    }
    publishFillEvents(lockedOrder, lockedAccount, trade, positionUpdate);
    return lockedOrder;
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
    trade.setCanonicalFullFill(isCanonicalDemoFullFill(account, order));
    trade.setPositionSide(order.getPositionSide());
    trade.setMarginMode(order.getMarginMode());
    trade.setSide(order.getSide());
    trade.setLots(filledQuantity);
    trade.setPrice(execution.filledPrice());
    trade.setFee(fee);
    trade.setFeeAsset(execution.feeAsset());
    trade.setLiquidityRole(canonicalFill == null ? order.getLiquidityRole() : canonicalFill.liquidityRole());
    trade.setSystemReason(OrderSystemReasonPolicy.external(order));
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
      publishFillEvents(order, account, trade, null);
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
        if (protectionOrderService != null) {
          protectionOrderService.afterPerpetualFillLocked(
              order,
              positionUpdate,
              authorityMark);
        }
        publishFillEvents(order, account, trade, positionUpdate);
        return order;
      }
      positionEngine.applyFill(
          account,
          order,
          normalizedFill,
          profile,
          marginDescription);
      chargeTradeFee(account, fee, execution.feeAsset(), profile.kind(), trade.getId());
      publishFillEvents(order, account, trade, null);
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

    publishFillEvents(
        order,
        account,
        trade,
        new PositionEngine.PositionUpdateResult(savedPosition));

    return order;
  }

  private void validateDepthFillInputs(
      OrderEntity order,
      OrderEntity holdOwner,
      TradingAccountEntity account,
      DemoMatchFill fill,
      String fillIdentity,
      ExecutableMarketSnapshot snapshot,
      DemoExecutionPolicy policy
  ) {
    if (order == null || holdOwner == null || account == null || fill == null
        || snapshot == null || policy == null) {
      throw invalidDepthFill("Spot DEPTH fill inputs are required");
    }
    if (order.getId() == null || holdOwner.getId() == null || account.getId() == null
        || order.getAccountId() == null || holdOwner.getAccountId() == null) {
      throw invalidDepthFill("Spot DEPTH fill identities are required");
    }
    if (!account.getId().equals(order.getAccountId())
        || !account.getId().equals(holdOwner.getAccountId())) {
      throw invalidDepthFill("Order, hold owner, and account ownership must match");
    }
    if ((account.getUserId() != null && order.getUserId() != null
        && !account.getUserId().equals(order.getUserId()))
        || (account.getUserId() != null && holdOwner.getUserId() != null
        && !account.getUserId().equals(holdOwner.getUserId()))
        || (order.getUserId() != null && holdOwner.getUserId() != null
        && !order.getUserId().equals(holdOwner.getUserId()))) {
      throw invalidDepthFill("Order, hold owner, and account users must match");
    }
    if (account.getAccountType() != AccountType.DEMO) {
      throw invalidDepthFill("Spot DEPTH fills are available only for Demo accounts");
    }
    if (holdOwner == order) {
      if (!holdOwner.getId().equals(order.getId())) {
        throw invalidDepthFill("Ordinary order hold owner is invalid");
      }
    } else if (order.getHoldOwnerOrderId() == null
        || !order.getHoldOwnerOrderId().equals(holdOwner.getId())) {
      throw invalidDepthFill("Shared order hold owner is invalid");
    }
    if (fillIdentity == null || fillIdentity.isBlank()) {
      throw invalidDepthFill("Fill identity is required");
    }
    if (fillIdentity.length() > FILL_IDENTITY_MAX_LENGTH) {
      throw invalidDepthFill("Fill identity must not exceed 64 characters");
    }
    if (fill.quantity() == null || fill.quantity().signum() <= 0
        || fill.price() == null || fill.price().signum() <= 0
        || fill.feeRate() == null || fill.feeRate().signum() < 0
        || fill.liquidityRole() == null) {
      throw invalidDepthFill("Fill quantity, price, role, and fee rate are invalid");
    }
    if (!isExactlyRepresentableAsNumeric(
        fill.quantity(), TRADE_QUANTITY_PRECISION, TRADE_QUANTITY_SCALE)) {
      throw invalidDepthFill("Fill quantity is not exactly representable by trading.trades.lots");
    }
    if (!isExactlyRepresentableAsNumeric(
        fill.price(), TRADE_PRICE_PRECISION, TRADE_PRICE_SCALE)) {
      throw invalidDepthFill("Fill price is not exactly representable by trading.trades.price");
    }
    BigDecimal walletQuantity = money(fill.quantity());
    if (walletQuantity.signum() <= 0 || walletQuantity.compareTo(fill.quantity()) != 0) {
      throw invalidDepthFill("Fill quantity must be exactly representable at money scale");
    }
    if (money(walletQuantity.multiply(fill.price())).signum() <= 0) {
      throw invalidDepthFill("Fill gross quote amount must remain positive at money scale");
    }
    if (order.getProductType() != ProductType.CRYPTO_SPOT
        || snapshot.productType() != ProductType.CRYPTO_SPOT) {
      throw invalidDepthFill("Spot DEPTH fill requires CRYPTO_SPOT order and snapshot");
    }
    if (order.getSide() == null || order.getSymbol() == null || order.getSymbol().isBlank()) {
      throw invalidDepthFill("Spot DEPTH order side and symbol are required");
    }
    if (policy.matchingMode() != DemoMatchingMode.DEPTH) {
      throw invalidDepthFill("Spot partial fill requires DEPTH matching mode");
    }
    BigDecimal expectedFeeRate = fill.liquidityRole() == LiquidityRole.MAKER
        ? policy.makerFeeRate()
        : policy.takerFeeRate();
    if (expectedFeeRate == null || fill.feeRate().compareTo(expectedFeeRate) != 0) {
      throw invalidDepthFill("Fill fee rate does not match the DEPTH execution policy");
    }
  }

  private void validatePerpetualDepthFillInputs(
      OrderEntity order,
      OrderEntity holdOwner,
      TradingAccountEntity account,
      DemoMatchFill fill,
      String fillIdentity,
      ExecutableMarketSnapshot snapshot,
      DemoExecutionPolicy policy
  ) {
    if (order == null || holdOwner == null || account == null || fill == null
        || snapshot == null || policy == null) {
      throw invalidDepthFill("Perpetual DEPTH fill inputs are required");
    }
    if (order != holdOwner) {
      throw invalidDepthFill("Perpetual DEPTH order must own its hold");
    }
    if (order.getId() == null || account.getId() == null || order.getAccountId() == null) {
      throw invalidDepthFill("Perpetual DEPTH fill identities are required");
    }
    if (!account.getId().equals(order.getAccountId())) {
      throw invalidDepthFill("Perpetual order and account ownership must match");
    }
    if (account.getUserId() != null && order.getUserId() != null
        && !account.getUserId().equals(order.getUserId())) {
      throw invalidDepthFill("Perpetual order and account users must match");
    }
    if (account.getAccountType() != AccountType.DEMO) {
      throw invalidDepthFill("Perpetual DEPTH fills are available only for Demo accounts");
    }
    if (fillIdentity == null || fillIdentity.isBlank()) {
      throw invalidDepthFill("Fill identity is required");
    }
    if (fillIdentity.length() > FILL_IDENTITY_MAX_LENGTH) {
      throw invalidDepthFill("Fill identity must not exceed 64 characters");
    }
    if (fill.quantity() == null || fill.quantity().signum() <= 0
        || fill.price() == null || fill.price().signum() <= 0
        || fill.feeRate() == null || fill.feeRate().signum() < 0
        || fill.liquidityRole() == null) {
      throw invalidDepthFill("Fill quantity, price, role, and fee rate are invalid");
    }
    if (!isExactlyRepresentableAsNumeric(
        fill.quantity(), TRADE_QUANTITY_PRECISION, TRADE_QUANTITY_SCALE)) {
      throw invalidDepthFill("Fill quantity is not exactly representable by trading.trades.lots");
    }
    if (!isExactlyRepresentableAsNumeric(
        fill.price(), TRADE_PRICE_PRECISION, TRADE_PRICE_SCALE)) {
      throw invalidDepthFill("Fill price is not exactly representable by trading.trades.price");
    }
    BigDecimal accountQuantity = money(fill.quantity());
    if (accountQuantity.signum() <= 0 || accountQuantity.compareTo(fill.quantity()) != 0) {
      throw invalidDepthFill("Fill quantity must be exactly representable at money scale");
    }
    if (money(accountQuantity.multiply(fill.price())).signum() <= 0) {
      throw invalidDepthFill("Fill gross notional must remain positive at money scale");
    }
    if (order.getProductType() != ProductType.LINEAR_PERP
        || snapshot.productType() != ProductType.LINEAR_PERP) {
      throw invalidDepthFill("Perpetual DEPTH fill requires LINEAR_PERP order and snapshot");
    }
    if (order.getSide() == null || order.getSymbol() == null || order.getSymbol().isBlank()) {
      throw invalidDepthFill("Perpetual DEPTH order side and symbol are required");
    }
    validateDepthSnapshot(order, snapshot);
    if (order.getPositionMode() == null
        || order.getPositionSide() == null
        || order.getMarginMode() == null
        || account.getPositionMode() != order.getPositionMode()) {
      throw invalidDepthFill("Perpetual DEPTH position and margin snapshots are invalid");
    }
    if ((order.getPositionMode() == PositionMode.ONE_WAY
        && order.getPositionSide() != PositionSide.BOTH)
        || (order.getPositionMode() == PositionMode.HEDGE
        && order.getPositionSide() == PositionSide.BOTH)
        || (order.getMarginMode() != MarginMode.CROSS
        && order.getMarginMode() != MarginMode.ISOLATED)) {
      throw invalidDepthFill("Perpetual DEPTH position slot is invalid");
    }
    if (order.getLeverage() == null || order.getLeverage() <= 0) {
      throw invalidDepthFill("Perpetual DEPTH execution leverage must be positive");
    }
    if (snapshot.mark() == null || snapshot.mark().signum() <= 0) {
      throw invalidDepthFill("Perpetual DEPTH authority mark must be positive");
    }
    if (policy.matchingMode() != DemoMatchingMode.DEPTH) {
      throw invalidDepthFill("Perpetual partial fill requires DEPTH matching mode");
    }
    BigDecimal expectedFeeRate = fill.liquidityRole() == LiquidityRole.MAKER
        ? policy.makerFeeRate()
        : policy.takerFeeRate();
    if (expectedFeeRate == null || fill.feeRate().compareTo(expectedFeeRate) != 0) {
      throw invalidDepthFill("Fill fee rate does not match the DEPTH execution policy");
    }
  }

  private void validateNewDepthFillState(OrderEntity order, DemoMatchFill fill) {
    if (order.getRemainingQuantity() == null
        || order.getRemainingQuantity().signum() <= 0
        || fill.quantity().compareTo(order.getRemainingQuantity()) > 0) {
      throw invalidDepthFill("Fill quantity exceeds the order remaining quantity");
    }
  }

  private void validateDepthSnapshot(
      OrderEntity order,
      ExecutableMarketSnapshot snapshot
  ) {
    if (snapshot.platformSymbol() == null || snapshot.platformSymbol().isBlank()
        || !snapshot.platformSymbol().trim().equalsIgnoreCase(order.getSymbol().trim())
        || snapshot.productType() != order.getProductType()
        || snapshot.providerCode() == null || snapshot.providerCode().isBlank()
        || snapshot.providerCode().length() > PROVIDER_CODE_MAX_LENGTH
        || snapshot.providerSymbol() == null || snapshot.providerSymbol().isBlank()
        || snapshot.sourceMode() == null
        || snapshot.asOf() == null
        || snapshot.expiresAt() == null
        || !snapshot.asOf().isBefore(snapshot.expiresAt())) {
      throw invalidDepthFill("Executable market snapshot is incoherent");
    }
  }

  private void validateDepthHold(
      OrderEntity holdOwner,
      String expectedCurrency,
      BigDecimal holdBeforeFill,
      BigDecimal holdAfterFill,
      BigDecimal newRemaining,
      BigDecimal lockedSpend
  ) {
    if (holdBeforeFill.signum() <= 0
        || holdAfterFill.signum() < 0
        || holdOwner.getHoldCurrency() == null
        || !holdOwner.getHoldCurrency().equalsIgnoreCase(expectedCurrency)) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Spot DEPTH fill requires a positive hold in the spent asset");
    }
    BigDecimal release = money(
        holdBeforeFill.subtract(money(lockedSpend)).subtract(holdAfterFill));
    if (release.signum() < 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Spot DEPTH fill hold is underfunded");
    }
    if (newRemaining.signum() == 0 && holdAfterFill.signum() != 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Final Spot DEPTH fill must leave zero hold");
    }
    if (newRemaining.signum() > 0 && holdAfterFill.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Partial Spot DEPTH fill must preserve a positive future hold");
    }
  }

  private void validatePerpetualDepthHold(
      OrderEntity order,
      BigDecimal holdBeforeFill,
      BigDecimal holdAfterFill,
      BigDecimal consumedHold,
      BigDecimal newRemaining
  ) {
    if (holdBeforeFill.signum() <= 0
        || holdAfterFill.signum() < 0
        || consumedHold.signum() <= 0
        || order.getHoldCurrency() == null
        || !"USDT".equalsIgnoreCase(order.getHoldCurrency())) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Perpetual DEPTH fill requires a positive USDT order hold slice");
    }
    if (newRemaining.signum() == 0 && holdAfterFill.signum() != 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Final Perpetual DEPTH fill must leave zero hold");
    }
    if (newRemaining.signum() > 0 && holdAfterFill.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Partial Perpetual DEPTH fill must preserve a positive future hold");
    }
  }

  private boolean sameFillPayload(
      TradeEntity existing,
      OrderEntity order,
      TradingAccountEntity account,
      String fillIdentity,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal fee,
      LiquidityRole liquidityRole
  ) {
    return order.getId().equals(existing.getOrderId())
        && account.getId().equals(existing.getAccountId())
        && order.getProductType() == existing.getProductType()
        && decimalEquals(existing.getLots(), quantity)
        && decimalEquals(existing.getPrice(), price)
        && decimalEquals(existing.getFee(), fee)
        && "USDT".equals(existing.getFeeAsset())
        && liquidityRole == existing.getLiquidityRole()
        && fillIdentity.equals(existing.getFillIdentity());
  }

  private TradeEntity depthTrade(
      OrderEntity order,
      TradingAccountEntity account,
      DemoMatchFill fill,
      String fillIdentity,
      ExecutableMarketSnapshot snapshot,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal fee,
      UUID tradeId
  ) {
    TradeEntity trade = new TradeEntity();
    trade.setId(tradeId);
    trade.setFillIdentity(fillIdentity);
    trade.setCanonicalFullFill(false);
    trade.setOrderId(order.getId());
    trade.setAccountId(account.getId());
    trade.setSymbol(order.getSymbol());
    trade.setProductType(order.getProductType());
    trade.setPositionSide(order.getPositionSide());
    trade.setMarginMode(order.getMarginMode());
    trade.setSide(order.getSide());
    trade.setLots(quantity);
    trade.setPrice(price);
    trade.setFee(fee);
    trade.setFeeAsset("USDT");
    trade.setLiquidityRole(fill.liquidityRole());
    trade.setSystemReason(OrderSystemReasonPolicy.external(order));
    trade.setSourceMode(snapshot.sourceMode().name());
    trade.setProviderCode(snapshot.providerCode());
    trade.setExecutedAt(snapshot.asOf());
    return trade;
  }

  private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static boolean isExactlyRepresentableAsNumeric(
      BigDecimal value,
      int precision,
      int scale
  ) {
    try {
      return value.setScale(scale, RoundingMode.UNNECESSARY).precision() <= precision;
    } catch (ArithmeticException exception) {
      return false;
    }
  }

  private static void requireDepthNumeric(
      BigDecimal value,
      int precision,
      int scale,
      String message
  ) {
    if (!isExactlyRepresentableAsNumeric(value, precision, scale)) {
      throw invalidDepthFill(message);
    }
  }

  private static BusinessException invalidDepthFill(String message) {
    return new BusinessException("INVALID_DEPTH_FILL", message);
  }

  private static boolean isCanonicalDemoFullFill(
      TradingAccountEntity account,
      OrderEntity order
  ) {
    ProductType productType = order.getProductType();
    return account.getAccountType() == AccountType.DEMO
        && (productType == ProductType.CRYPTO_SPOT || productType == ProductType.LINEAR_PERP);
  }

  @Autowired
  void setAccountMutationPublisher(ApplicationEventPublisher accountMutationPublisher) {
    this.accountMutationPublisher = accountMutationPublisher;
  }

  private void publishFillEvents(
      OrderEntity order,
      TradingAccountEntity account,
      TradeEntity trade,
      PositionEngine.PositionUpdateResult positionUpdate
  ) {
    if (accountMutationPublisher == null
        || order.getUserId() == null
        || account.getId() == null
        || trade.getId() == null) {
      return;
    }
    Instant occurredAt = trade.getExecutedAt() == null ? Instant.now() : trade.getExecutedAt();
    accountMutationPublisher.publishEvent(new TradingAccountMutationEvent(
        order.getUserId(),
        account.getId(),
        "TRADE_CREATED",
        "TRADE",
        trade.getId(),
        order.getId(),
        order.getVersion(),
        occurredAt));
    accountMutationPublisher.publishEvent(new TradingAccountMutationEvent(
        order.getUserId(),
        account.getId(),
        "BALANCE_UPDATED",
        "ACCOUNT",
        account.getId(),
        trade.getId(),
        order.getVersion(),
        occurredAt));
    publishPositionEvent(order, account, trade, positionUpdate, occurredAt);
  }

  private void publishPositionEvent(
      OrderEntity order,
      TradingAccountEntity account,
      TradeEntity trade,
      PositionEngine.PositionUpdateResult update,
      Instant occurredAt
  ) {
    if (update == null) {
      return;
    }
    PositionEntity position = update.position();
    UUID resourceId = update.reducedPositionId() == null
        ? position == null ? null : position.getId()
        : update.reducedPositionId();
    if (resourceId == null) {
      return;
    }
    boolean closed = update.toQuantity() != null
        && update.toQuantity().compareTo(BigDecimal.ZERO) == 0;
    if (!closed && position != null) {
      closed = position.getStatus() == com.fxplatform.trading.enums.PositionStatus.CLOSED;
    }
    accountMutationPublisher.publishEvent(new TradingAccountMutationEvent(
        order.getUserId(),
        account.getId(),
        closed ? "POSITION_CLOSED" : "POSITION_UPDATED",
        "POSITION",
        resourceId,
        trade.getId(),
        position == null ? null : position.getVersion(),
        occurredAt));
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

  private SymbolEntity strictDepthSymbol(OrderEntity order) {
    String orderSymbol = order.getSymbol();
    String normalized = orderSymbol == null ? "" : orderSymbol.trim().toUpperCase(Locale.ROOT);
    if (symbolRepository == null || normalized.isBlank()) {
      throw symbolNotTradable("Spot DEPTH fill requires a configured symbol");
    }
    SymbolEntity symbol = symbolRepository.findBySymbol(normalized)
        .orElseThrow(() -> symbolNotTradable("Spot DEPTH symbol is not configured"));
    String configuredCode = symbol.getSymbol() == null
        ? ""
        : symbol.getSymbol().trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals(configuredCode)
        || symbol.getProductType() != ProductType.CRYPTO_SPOT
        || !Boolean.TRUE.equals(symbol.getEnabled())
        || !Boolean.TRUE.equals(symbol.getTradable())
        || symbol.getBaseCurrency() == null
        || symbol.getBaseCurrency().isBlank()
        || symbol.getQuoteCurrency() == null
        || symbol.getQuoteCurrency().isBlank()
        || !"USDT".equalsIgnoreCase(symbol.getQuoteCurrency().trim())) {
      throw symbolNotTradable("Spot DEPTH symbol is not an enabled tradable USDT Spot market");
    }
    return symbol;
  }

  private SymbolEntity strictPerpetualDepthSymbol(OrderEntity order) {
    String orderSymbol = order.getSymbol();
    String normalized = orderSymbol == null ? "" : orderSymbol.trim().toUpperCase(Locale.ROOT);
    if (symbolRepository == null || normalized.isBlank()) {
      throw symbolNotTradable("Perpetual DEPTH fill requires a configured symbol");
    }
    SymbolEntity symbol = symbolRepository.findBySymbol(normalized)
        .orElseThrow(() -> symbolNotTradable("Perpetual DEPTH symbol is not configured"));
    String configuredCode = symbol.getSymbol() == null
        ? ""
        : symbol.getSymbol().trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals(configuredCode)
        || symbol.getProductType() != ProductType.LINEAR_PERP
        || !Boolean.TRUE.equals(symbol.getEnabled())
        || !Boolean.TRUE.equals(symbol.getTradable())
        || symbol.getQuoteCurrency() == null
        || !"USDT".equalsIgnoreCase(symbol.getQuoteCurrency().trim())
        || symbol.getSettlementAsset() == null
        || !"USDT".equalsIgnoreCase(symbol.getSettlementAsset().trim())
        || symbol.getMarginAsset() == null
        || !"USDT".equalsIgnoreCase(symbol.getMarginAsset().trim())) {
      throw symbolNotTradable(
          "Perpetual DEPTH symbol is not an enabled tradable USDT Linear Perpetual market");
    }
    return symbol;
  }

  private BusinessException symbolNotTradable(String message) {
    return new BusinessException(ErrorCode.SYMBOL_NOT_TRADABLE, message);
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
