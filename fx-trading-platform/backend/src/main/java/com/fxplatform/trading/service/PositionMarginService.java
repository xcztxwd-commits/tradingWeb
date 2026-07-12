package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.PerpetualRiskService.PositionRisk;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest.Action;
import com.fxplatform.trading.dto.response.AdjustPositionMarginResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.AccountRiskProjection;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedAccountRisk;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedSymbolRisk;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Adjusts the actual principal allocated to one Demo Isolated Perpetual slot. */
@Service
@RequiredArgsConstructor
public class PositionMarginService {

  private static final int MONEY_SCALE = 8;

  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final SymbolRepository symbolRepository;
  private final MarketBundleResolver marketBundleResolver;
  private final DemoExecutionGuard demoExecutionGuard;
  private final PerpetualRiskService perpetualRiskService;
  private final PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  private final LedgerService ledgerService;
  private final TradingTransactionExecutor transactionExecutor;
  private final ApplicationEventPublisher eventPublisher;

  public AdjustPositionMarginResponse adjust(
      UUID userId,
      UUID positionId,
      AdjustPositionMarginRequest request
  ) {
    BigDecimal amount = validateRequest(userId, positionId, request);
    PositionEntity positionSnapshot = positionRepository.findById(positionId)
        .orElseThrow(PositionMarginService::positionNotFound);
    TradingAccountEntity accountSnapshot = accountRepository.findByIdAndUserId(
            positionSnapshot.getAccountId(), userId)
        .orElseThrow(PositionMarginService::accountNotFound);
    validateEligibility(positionSnapshot);
    demoExecutionGuard.requireDemo(
        accountSnapshot,
        ProductType.LINEAR_PERP,
        positionSnapshot.getSymbol());
    SymbolEntity symbol = requireSymbol(positionSnapshot.getSymbol());

    BusinessException staleFailure = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        ExecutableMarketSnapshot market = resolveMarket(positionSnapshot.getSymbol());
        PreparedAccountRisk prepared = accountRiskSnapshotService.prepare(
            positionSnapshot.getAccountId(),
            Map.of(positionSnapshot.getSymbol(), market));
        AdjustPositionMarginResponse response = transactionExecutor.execute(
            () -> adjustLocked(
                userId,
                positionSnapshot.getAccountId(),
                positionId,
                request,
                amount,
                symbol,
                market,
                prepared));
        eventPublisher.publishEvent(new PositionMarginAdjustedEvent(response));
        return response;
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode()) || attempt > 0) {
          throw exception;
        }
        staleFailure = exception;
      }
    }
    throw staleFailure == null
        ? new BusinessException(ErrorCode.MARKET_DATA_STALE, "Executable market snapshot expired")
        : staleFailure;
  }

  private AdjustPositionMarginResponse adjustLocked(
      UUID userId,
      UUID accountId,
      UUID positionId,
      AdjustPositionMarginRequest request,
      BigDecimal amount,
      SymbolEntity symbol,
      ExecutableMarketSnapshot market,
      PreparedAccountRisk prepared
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            accountId, userId)
        .orElseThrow(PositionMarginService::accountNotFound);
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, symbol.getSymbol());
    List<PositionEntity> lockedPositions = positionRepository
        .findOpenLinearPerpByAccountIdForUpdate(accountId);
    List<OrderEntity> activeOrders = orderRepository
        .findActiveLinearPerpByAccountIdForUpdate(accountId);
    AccountRiskProjection accountRisk = accountRiskSnapshotService.project(
        account,
        lockedPositions,
        activeOrders,
        prepared);
    PositionEntity position = lockedPositions.stream()
        .filter(candidate -> positionId.equals(candidate.getId()))
        .findFirst()
        .orElseThrow(PositionMarginService::positionNotFound);
    if (!Objects.equals(position.getAccountId(), account.getId())) {
      throw accountNotFound();
    }
    validateEligibility(position);
    String canonicalSymbol = SymbolNormalizer.normalize(position.getSymbol());
    if (!canonicalSymbol.equals(SymbolNormalizer.normalize(symbol.getSymbol()))) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "Position symbol changed while account risk was prepared");
    }
    if (!Objects.equals(position.getVersion(), request.expectedVersion())) {
      throw new BusinessException(
          ErrorCode.POSITION_VERSION_CONFLICT,
          "Position margin version is stale");
    }

    BigDecimal currentMargin = orZero(position.getMarginHeld()).setScale(
        MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal delta = request.action() == Action.ADD ? amount : amount.negate();
    BigDecimal nextMargin = currentMargin.add(delta).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (request.action() == Action.ADD
        && accountRisk.crossAvailable().compareTo(amount) < 0) {
      throw new BusinessException(ErrorCode.INSUFFICIENT_MARGIN, "Free margin is not enough");
    }
    if (nextMargin.compareTo(BigDecimal.ZERO) < 0) {
      throw unsafeReduction();
    }
    BigDecimal nextUsed = accountRisk.usedMargin().add(delta)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal nextFree = accountRisk.crossAvailable().subtract(delta)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (nextUsed.compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException(
          ErrorCode.MARGIN_ADJUSTMENT_INVALID,
          "Position margin adjustment would make account used margin negative");
    }

    PreparedSymbolRisk preparedSymbol = prepared.symbols().get(canonicalSymbol);
    if (preparedSymbol == null) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "Position risk configuration changed while account risk was prepared");
    }
    BigDecimal maintenanceMarginRate = preparedSymbol.maintenanceMarginRate();
    PositionRisk risk = positionRisk(
        position,
        market.mark(),
        nextMargin,
        maintenanceMarginRate);
    if (request.action() == Action.REDUCE && risk.liquidatable()) {
      throw unsafeReduction();
    }
    if (request.action() == Action.REDUCE
        && internalIsolatedHolds(positionId, activeOrders)
            .compareTo(isolatedCapacity(risk)) >= 0) {
      throw unsafeReduction();
    }

    long nextVersion = request.expectedVersion() + 1;
    Map<UUID, Long> originalVersions = new HashMap<>();
    for (PositionEntity lockedPosition : lockedPositions) {
      originalVersions.put(lockedPosition.getId(), lockedPosition.getVersion());
    }
    accountRiskSnapshotService.applyRevaluation(account, lockedPositions, accountRisk);
    account.setEquity(accountRisk.displayEquity());
    account.setUsedMargin(nextUsed);
    account.setFreeMargin(nextFree);
    position.setMarginHeld(nextMargin);
    position.setNotional(risk.markNotional());
    position.setInitialMargin(risk.initialMargin());
    position.setMaintenanceMargin(risk.maintenanceMargin());
    position.setFloatingPnl(risk.unrealizedPnl());
    position.setCurrentPrice(market.mark());
    position.setMarkPrice(market.mark());
    position.setVersion(nextVersion);
    accountRepository.save(account);
    for (PositionEntity lockedPosition : lockedPositions) {
      if (!Objects.equals(
          originalVersions.get(lockedPosition.getId()),
          lockedPosition.getVersion())) {
        positionRepository.save(lockedPosition);
      }
    }
    if (request.action() == Action.ADD) {
      ledgerService.recordMarginHold(
          account,
          amount,
          positionId,
          "Isolated position margin added");
    } else {
      ledgerService.recordMarginRelease(
          account,
          amount,
          positionId,
          "Isolated position margin reduced");
    }
    return response(account, position, request, amount, risk, nextVersion);
  }

  private PositionRisk positionRisk(
      PositionEntity position,
      BigDecimal markPrice,
      BigDecimal marginHeld,
      BigDecimal maintenanceMarginRate
  ) {
    try {
      return perpetualRiskService.positionRisk(
          position.getSide(),
          position.getLots(),
          position.getOpenPrice(),
          markPrice,
          position.getLeverage() == null ? 0 : position.getLeverage(),
          marginHeld,
          position.getFundingPnl(),
          maintenanceMarginRate);
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(
          ErrorCode.MARGIN_ADJUSTMENT_INVALID,
          "Position risk state is incomplete");
    }
  }

  private BigDecimal internalIsolatedHolds(UUID positionId, List<OrderEntity> activeOrders) {
    return activeOrders.stream()
        .filter(order -> order.getMarginMode() == MarginMode.ISOLATED)
        .filter(order -> positionId.equals(order.getParentPositionId()))
        .map(order -> orZero(order.getHoldAmount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal isolatedCapacity(PositionRisk risk) {
    return risk.isolatedEquity()
        .subtract(risk.maintenanceMargin())
        .subtract(risk.estimatedCloseTakerFee())
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private AdjustPositionMarginResponse response(
      TradingAccountEntity account,
      PositionEntity position,
      AdjustPositionMarginRequest request,
      BigDecimal amount,
      PositionRisk risk,
      long version
  ) {
    return new AdjustPositionMarginResponse(
        account.getId(),
        position.getId(),
        position.getSymbol(),
        position.getPositionSide(),
        position.getMarginMode(),
        request.action(),
        amount,
        risk.initialMargin(),
        position.getMarginHeld(),
        risk.isolatedEquity(),
        risk.maintenanceMargin(),
        risk.estimatedCloseTakerFee(),
        risk.estimatedLiquidationPrice(),
        account.getUsedMargin(),
        account.getFreeMargin(),
        version);
  }

  private BigDecimal validateRequest(
      UUID userId,
      UUID positionId,
      AdjustPositionMarginRequest request
  ) {
    if (userId == null
        || positionId == null
        || request == null
        || request.action() == null
        || request.amount() == null
        || request.amount().compareTo(BigDecimal.ZERO) <= 0
        || request.amount().scale() > MONEY_SCALE
        || request.expectedVersion() == null
        || request.expectedVersion() < 0) {
      throw new BusinessException(
          ErrorCode.MARGIN_ADJUSTMENT_INVALID,
          "Position margin adjustment is invalid");
    }
    return request.amount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
  }

  private void validateEligibility(PositionEntity position) {
    if (position.getProductType() != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Only Linear Perpetual position margin can be adjusted");
    }
    if (position.getStatus() != PositionStatus.OPEN) {
      throw new BusinessException("POSITION_NOT_OPEN", "Only open position margin can be adjusted");
    }
    if (position.getMarginMode() != MarginMode.ISOLATED) {
      throw new BusinessException(
          ErrorCode.INVALID_MARGIN_MODE,
          "Only Isolated position margin can be adjusted");
    }
  }

  private SymbolEntity requireSymbol(String symbol) {
    SymbolEntity configured = symbolRepository.findBySymbol(symbol)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.SYMBOL_NOT_TRADABLE,
            "Symbol is not tradable"));
    if (configured.getProductType() != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Only Linear Perpetual position margin can be adjusted");
    }
    return configured;
  }

  private ExecutableMarketSnapshot resolveMarket(String symbol) {
    Instant to = Instant.now();
    CandleRequest candles = new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to);
    return ExecutableMarketSnapshot.from(marketBundleResolver.resolvePerp(symbol, candles));
  }

  private static AuthorizationException accountNotFound() {
    return new AuthorizationException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
  }

  private static BusinessException positionNotFound() {
    return new BusinessException("POSITION_NOT_FOUND", "Position not found");
  }

  private static BusinessException unsafeReduction() {
    return new BusinessException(
        ErrorCode.MARGIN_REDUCTION_UNSAFE,
        "Position margin reduction would make the Isolated slot unsafe");
  }

  public record PositionMarginAdjustedEvent(AdjustPositionMarginResponse adjustment) {
  }
}
