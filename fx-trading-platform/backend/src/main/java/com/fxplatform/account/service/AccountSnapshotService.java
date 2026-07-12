package com.fxplatform.account.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AccountSnapshotService {

  private static final int MONEY_SCALE = 8;
  private static final int RATIO_SCALE = 8;
  private static final List<OrderStatus> ACTIVE_ORDER_STATUSES = List.of(
      OrderStatus.RECEIVED,
      OrderStatus.VALIDATING,
      OrderStatus.ACCEPTED,
      OrderStatus.PENDING_ACTIVATION,
      OrderStatus.PENDING,
      OrderStatus.WORKING,
      OrderStatus.PARTIALLY_FILLED,
      OrderStatus.CANCEL_PENDING);

  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final QuoteService quoteService;
  private final PnLCalculator pnlCalculator;
  private final SymbolRepository symbolRepository;
  private final OrderRepository orderRepository;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();

  @Autowired
  public AccountSnapshotService(
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      QuoteService quoteService,
      PnLCalculator pnlCalculator,
      SymbolRepository symbolRepository,
      OrderRepository orderRepository
  ) {
    this.accountRepository = accountRepository;
    this.positionRepository = positionRepository;
    this.quoteService = quoteService;
    this.pnlCalculator = pnlCalculator;
    this.symbolRepository = symbolRepository;
    this.orderRepository = orderRepository;
  }

  /** Compatibility constructor for historical non-P0 unit fixtures. */
  public AccountSnapshotService(
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      QuoteService quoteService,
      PnLCalculator pnlCalculator,
      SymbolRepository symbolRepository
  ) {
    this(accountRepository, positionRepository, quoteService, pnlCalculator, symbolRepository, null);
  }

  public AccountSnapshot snapshot(UUID accountId) {
    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    return snapshot(account);
  }

  public AccountSnapshot snapshot(TradingAccountEntity account) {
    List<PositionEntity> openPositions = positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        account.getId(),
        PositionStatus.OPEN);
    Map<String, QuoteResponse> quoteCache = new HashMap<>();
    List<PositionValuation> valuations = openPositions.stream()
        .map(position -> valuation(position, account, quoteCache))
        .toList();
    BigDecimal openFloatingPnl = valuations.stream()
        .map(PositionValuation::floatingPnl)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal openPositionMargin = openPositions.stream()
        .map(this::positionMargin)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal positionValue = valuations.stream()
        .map(PositionValuation::positionValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal maintenanceMargin = valuations.stream()
        .map(PositionValuation::maintenanceMargin)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    PerpetualOrderHolds perpetualOrderHolds = activePerpetualHolds(account.getId(), openPositions);
    BigDecimal activePerpetualHolds = perpetualOrderHolds.total();
    BigDecimal balance = orZero(account.getBalance());
    BigDecimal equity = balance
        .add(openFloatingPnl)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    boolean canonicalPerpetualAccount = valuations.stream()
        .anyMatch(value -> value.kind() == InstrumentKind.LINEAR_PERPETUAL)
        || activePerpetualHolds.compareTo(BigDecimal.ZERO) > 0;
    BigDecimal calculatedUsedMargin = openPositionMargin.add(activePerpetualHolds)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal usedMargin = usedMargin(
        account,
        openPositions,
        calculatedUsedMargin,
        canonicalPerpetualAccount);
    BigDecimal freeMargin = canonicalPerpetualAccount
        ? crossAvailable(
            balance,
            openPositions,
            valuations,
            perpetualOrderHolds.crossEncumbrance())
        : equity.subtract(usedMargin).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    String warning = usedMarginWarning(
        account,
        openPositions,
        calculatedUsedMargin,
        canonicalPerpetualAccount);

    return new AccountSnapshot(
        account.getId(),
        balance,
        openFloatingPnl,
        equity,
        usedMargin,
        maintenanceMargin,
        freeMargin,
        marginLevel(equity, usedMargin),
        account.getBaseCurrency(),
        positionValue,
        freeMargin,
        Instant.now(),
        warning);
  }

  private PositionValuation valuation(
      PositionEntity position,
      TradingAccountEntity account,
      Map<String, QuoteResponse> quoteCache
  ) {
    String symbolCode = normalize(position.getSymbol());
    QuoteResponse quote = quoteCache.computeIfAbsent(symbolCode, quoteService::freshQuote);
    BigDecimal closeoutPrice = closeoutPrice(position, quote);
    SymbolEntity symbol = symbolFor(position);
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    BigDecimal valuationPrice = profile.kind() == InstrumentKind.LINEAR_PERPETUAL
        ? requireAuthorityMark(quote)
        : isPerpetual(profile.kind()) ? markPrice(quote, closeoutPrice) : closeoutPrice;
    BigDecimal floatingPnl = floatingPnl(position, account.getBaseCurrency(), symbol, profile, valuationPrice);
    if (isPerpetual(profile.kind())) {
      PerpMarginCalculator.MarginResult margin = perpetualMargin(
          profile,
          position.getLots(),
          valuationPrice,
          positionLeverage(position, account));
      return new PositionValuation(
          floatingPnl,
          margin.notional(),
          margin.maintenanceMargin(),
          profile.kind());
    }
    return new PositionValuation(
        floatingPnl,
        orZero(position.getNotional()),
        orZero(position.getMaintenanceMargin()),
        profile.kind());
  }

  private BigDecimal floatingPnl(
      PositionEntity position,
      String accountCurrency,
      SymbolEntity symbol,
      InstrumentProfile profile,
      BigDecimal valuationPrice
  ) {
    if (profile.kind() == InstrumentKind.FOREX) {
      return pnlCalculator.floatingPnl(
          symbol.getSymbol(),
          accountCurrency,
          position.getSide(),
          position.getLots(),
          position.getOpenPrice(),
          valuationPrice);
    }
    return pnlCalculator.floatingPnl(
        profile.kind(),
        position.getSide(),
        position.getLots(),
        position.getOpenPrice(),
        valuationPrice,
        canonicalUnitSize(profile));
  }

  private PerpMarginCalculator.MarginResult perpetualMargin(
      InstrumentProfile profile,
      BigDecimal quantity,
      BigDecimal markPrice,
      int leverage
  ) {
    if (profile.kind() == InstrumentKind.LINEAR_PERPETUAL) {
      return perpMarginCalculator.calculate(
          InstrumentKind.LINEAR_PERPETUAL,
          quantity,
          BigDecimal.ONE,
          BigDecimal.ONE,
          markPrice,
          leverage,
          profile.maintenanceMarginRate());
    }
    return perpMarginCalculator.calculate(profile, quantity, markPrice, leverage);
  }

  private BigDecimal canonicalUnitSize(InstrumentProfile profile) {
    return profile.kind() == InstrumentKind.LINEAR_PERPETUAL
        ? BigDecimal.ONE
        : profile.unitSize();
  }

  private BigDecimal closeoutPrice(PositionEntity position, QuoteResponse quote) {
    return position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
  }

  private BigDecimal markPrice(QuoteResponse quote, BigDecimal closeoutPrice) {
    if (quote.markPrice() != null) {
      return quote.markPrice();
    }
    if (quote.mid() != null) {
      return quote.mid();
    }
    return closeoutPrice;
  }

  private BigDecimal requireAuthorityMark(QuoteResponse quote) {
    if (quote == null
        || quote.markPrice() == null
        || quote.markPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          "MARKET_DATA_UNAVAILABLE",
          "Linear Perpetual account snapshot requires a positive authority mark");
    }
    return quote.markPrice();
  }

  private int positionLeverage(PositionEntity position, TradingAccountEntity account) {
    if (position.getLeverage() != null && position.getLeverage() > 0) {
      return position.getLeverage();
    }
    return account.getLeverage() != null && account.getLeverage() > 0 ? account.getLeverage() : 1;
  }

  private boolean isPerpetual(InstrumentKind kind) {
    return kind == InstrumentKind.LINEAR_PERPETUAL || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private BigDecimal positionMargin(PositionEntity position) {
    BigDecimal marginHeld = orZero(position.getMarginHeld());
    if (position.getProductType() == ProductType.LINEAR_PERP) {
      return marginHeld;
    }
    return marginHeld.compareTo(BigDecimal.ZERO) > 0 ? marginHeld : orZero(position.getInitialMargin());
  }

  private BigDecimal usedMargin(
      TradingAccountEntity account,
      List<PositionEntity> openPositions,
      BigDecimal calculatedUsedMargin,
      boolean canonicalPerpetualAccount
  ) {
    if (!openPositions.isEmpty() || canonicalPerpetualAccount) {
      return calculatedUsedMargin;
    }
    BigDecimal ledgerUsedMargin = orZero(account.getUsedMargin());
    return ledgerUsedMargin.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private String usedMarginWarning(
      TradingAccountEntity account,
      List<PositionEntity> openPositions,
      BigDecimal calculatedUsedMargin,
      boolean canonicalPerpetualAccount
  ) {
    BigDecimal ledgerUsedMargin = orZero(account.getUsedMargin());
    if (openPositions.isEmpty() && !canonicalPerpetualAccount) {
      return null;
    }
    if (ledgerUsedMargin.compareTo(calculatedUsedMargin) == 0) {
      return null;
    }
    return "Persisted account.usedMargin " + ledgerUsedMargin
        + " does not match open position margin plus active Perpetual holds "
        + calculatedUsedMargin;
  }

  private PerpetualOrderHolds activePerpetualHolds(
      UUID accountId,
      List<PositionEntity> openPositions
  ) {
    if (orderRepository == null) {
      return new PerpetualOrderHolds(moneyZero(), moneyZero());
    }
    Map<UUID, PositionEntity> positionsById = new HashMap<>();
    for (PositionEntity position : openPositions) {
      if (position != null && position.getId() != null) {
        positionsById.put(position.getId(), position);
      }
    }
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal crossEncumbrance = BigDecimal.ZERO;
    for (OrderEntity order : orderRepository.findByAccountIdAndStatusIn(
        accountId, ACTIVE_ORDER_STATUSES)) {
      if (order == null || order.getProductType() != ProductType.LINEAR_PERP) {
        continue;
      }
      BigDecimal hold = safeAmount(order.getHoldAmount());
      total = total.add(hold);
      boolean isolatedClosePool = order.getMarginMode() == MarginMode.ISOLATED
          && order.getParentPositionId() != null
          && matchesOpenIsolatedParent(
              order,
              positionsById.get(order.getParentPositionId()));
      if (!isolatedClosePool) {
        crossEncumbrance = crossEncumbrance.add(hold);
      }
    }
    return new PerpetualOrderHolds(
        total.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        crossEncumbrance.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
  }

  private boolean matchesOpenIsolatedParent(OrderEntity order, PositionEntity parent) {
    return parent != null
        && parent.getProductType() == ProductType.LINEAR_PERP
        && parent.getStatus() == PositionStatus.OPEN
        && parent.getMarginMode() == MarginMode.ISOLATED
        && accountAndSymbolMatch(order, parent)
        && order.getPositionMode() == parent.getPositionMode()
        && order.getPositionSide() == parent.getPositionSide();
  }

  private boolean accountAndSymbolMatch(OrderEntity order, PositionEntity parent) {
    return java.util.Objects.equals(order.getAccountId(), parent.getAccountId())
        && order.getSymbol() != null
        && parent.getSymbol() != null
        && SymbolNormalizer.normalize(order.getSymbol())
            .equals(SymbolNormalizer.normalize(parent.getSymbol()));
  }

  private static BigDecimal moneyZero() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal crossAvailable(
      BigDecimal balance,
      List<PositionEntity> positions,
      List<PositionValuation> valuations,
      BigDecimal activePerpetualHolds
  ) {
    BigDecimal isolatedPrincipal = BigDecimal.ZERO;
    BigDecimal crossUpl = BigDecimal.ZERO;
    BigDecimal crossPositionMargin = BigDecimal.ZERO;
    for (int index = 0; index < positions.size(); index++) {
      PositionEntity position = positions.get(index);
      PositionValuation valuation = valuations.get(index);
      BigDecimal held = positionMargin(position);
      boolean isolatedLinear = valuation.kind() == InstrumentKind.LINEAR_PERPETUAL
          && position.getMarginMode() == MarginMode.ISOLATED;
      if (isolatedLinear) {
        isolatedPrincipal = isolatedPrincipal.add(held);
      } else {
        crossUpl = crossUpl.add(valuation.floatingPnl());
        crossPositionMargin = crossPositionMargin.add(held);
      }
    }
    return orZero(balance)
        .subtract(isolatedPrincipal)
        .add(crossUpl)
        .subtract(crossPositionMargin)
        .subtract(orZero(activePerpetualHolds))
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal safeAmount(BigDecimal amount) {
    return amount == null || amount.compareTo(BigDecimal.ZERO) <= 0
        ? BigDecimal.ZERO
        : amount;
  }

  private BigDecimal marginLevel(BigDecimal equity, BigDecimal usedMargin) {
    if (usedMargin.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return equity
        .multiply(new BigDecimal("100"))
        .divide(usedMargin, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  private SymbolEntity symbolFor(PositionEntity position) {
    String normalized = normalize(position.getSymbol());
    if (normalized.isBlank()) {
      throw new BusinessException("SYMBOL_METADATA_NOT_FOUND", "Symbol metadata not found");
    }
    return symbolRepository.findBySymbol(normalized)
        .map(this::requireProductType)
        .orElseThrow(() -> new BusinessException("SYMBOL_METADATA_NOT_FOUND", "Symbol metadata not found"));
  }

  private SymbolEntity requireProductType(SymbolEntity symbol) {
    return SymbolProductTypes.requireExplicit(symbol);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private record PositionValuation(
      BigDecimal floatingPnl,
      BigDecimal positionValue,
      BigDecimal maintenanceMargin,
      InstrumentKind kind
  ) {
  }

  private record PerpetualOrderHolds(
      BigDecimal total,
      BigDecimal crossEncumbrance
  ) {
  }
}
