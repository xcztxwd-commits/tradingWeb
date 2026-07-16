package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.entity.RiskConfigEntity;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.AccountRiskProjection;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PositionProjection;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedAccountRisk;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Coordinates fresh, cancel-first Demo liquidation without holding provider calls under locks. */
@Service
@Slf4j
public class LiquidationService {

  static final String FX_MARGIN_STOP_OUT = "FX_MARGIN_STOP_OUT";
  static final String PERP_MAINTENANCE_MARGIN = "PERP_MAINTENANCE_MARGIN";

  private static final BigDecimal DEFAULT_STOP_OUT_LEVEL = new BigDecimal("50");

  private final AccountSnapshotService accountSnapshotService;
  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final PositionService positionService;
  private final SymbolRepository symbolRepository;
  private final RiskConfigRepository riskConfigRepository;
  // Kept in the compatibility constructor graph; canonical Task 12 settlement owns these writes.
  @SuppressWarnings("unused")
  private final LedgerService ledgerService;
  @SuppressWarnings("unused")
  private final QuoteService quoteService;
  @SuppressWarnings("unused")
  private final WalletService walletService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final TradingTransactionExecutor transactionExecutor;
  private final OrderRepository orderRepository;
  private final CancelAllOrderService cancelAllOrderService;
  private final PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  private final SystemCloseOrderService systemCloseOrderService;
  private LiquidationSettlementService liquidationSettlementService;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();

  @Value("${trading.stop-out-level:50}")
  private BigDecimal defaultStopOutLevel = DEFAULT_STOP_OUT_LEVEL;

  /** Spring constructor for the canonical Task 12 workflow. */
  @Autowired
  public LiquidationService(
      AccountSnapshotService accountSnapshotService,
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      PositionService positionService,
      SymbolRepository symbolRepository,
      RiskConfigRepository riskConfigRepository,
      LedgerService ledgerService,
      QuoteService quoteService,
      WalletService walletService,
      DemoExecutionGuard demoExecutionGuard,
      TradingTransactionExecutor transactionExecutor,
      OrderRepository orderRepository,
      CancelAllOrderService cancelAllOrderService,
      PerpetualAccountRiskSnapshotService accountRiskSnapshotService,
      SystemCloseOrderService systemCloseOrderService
  ) {
    this.accountSnapshotService = accountSnapshotService;
    this.accountRepository = accountRepository;
    this.positionRepository = positionRepository;
    this.positionService = positionService;
    this.symbolRepository = symbolRepository;
    this.riskConfigRepository = riskConfigRepository;
    this.ledgerService = ledgerService;
    this.quoteService = quoteService;
    this.walletService = walletService;
    this.demoExecutionGuard = demoExecutionGuard;
    this.transactionExecutor = transactionExecutor;
    this.orderRepository = orderRepository;
    this.cancelAllOrderService = cancelAllOrderService;
    this.accountRiskSnapshotService = accountRiskSnapshotService;
    this.systemCloseOrderService = systemCloseOrderService;
  }

  /** Compatibility constructor for legacy FX-only fixtures. */
  public LiquidationService(
      AccountSnapshotService accountSnapshotService,
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      PositionService positionService,
      SymbolRepository symbolRepository,
      RiskConfigRepository riskConfigRepository,
      LedgerService ledgerService,
      QuoteService quoteService,
      WalletService walletService,
      DemoExecutionGuard demoExecutionGuard,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(
        accountSnapshotService,
        accountRepository,
        positionRepository,
        positionService,
        symbolRepository,
        riskConfigRepository,
        ledgerService,
        quoteService,
        walletService,
        demoExecutionGuard,
        transactionExecutor,
        null,
        null,
        null,
        null);
  }

  public int scanAccount(UUID accountId) {
    TradingAccountEntity preflightAccount = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemoRiskReductionAccount(preflightAccount);
    if (preflightAccount.getStatus() == AccountStatus.RISK_REDUCTION_PENDING) {
      return 0;
    }
    int closed = scanForex(accountId);
    if (!perpetualWorkflowAvailable()) {
      return closed;
    }
    List<PositionEntity> perpetuals = safe(
        positionRepository.findOpenLinearPerpByAccountId(accountId));
    if (perpetuals.isEmpty()) {
      if (preflightAccount.getStatus() == AccountStatus.ISOLATED_LIQUIDATION_PENDING) {
        restoreIsolatedGate(accountId);
      }
      settleCrossIfReady(accountId);
      return closed;
    }
    return closed + scanPerpetual(accountId);
  }

  public int scanAllAccounts() {
    int closed = 0;
    for (TradingAccountEntity account : safe(
        accountRepository.findDemoLiquidationScanCandidates())) {
      if (account == null || account.getId() == null) {
        continue;
      }
      try {
        closed += scanAccount(account.getId());
      } catch (RuntimeException exception) {
        log.warn("Liquidation scan failed without aborting later accounts: accountId={}",
            account.getId(), exception);
      }
    }
    return closed;
  }

  /** Legacy FX gate retained for callers that render a stop-out preview. */
  public boolean shouldLiquidate(AccountSnapshot snapshot) {
    return snapshot != null && fxMarginBreached(snapshot);
  }

  /** Legacy FX close facade; P0 Perpetual closes use SystemCloseOrderService. */
  public boolean liquidatePosition(PositionEntity position, String reason) {
    if (position == null || position.getStatus() != PositionStatus.OPEN) {
      return false;
    }
    TradingAccountEntity account = accountRepository.findById(position.getAccountId())
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    SymbolEntity symbol = symbolRepository.findBySymbol(normalize(position.getSymbol()))
        .orElseGet(() -> fallbackForex(position.getSymbol()));
    ProductType productType = symbol.getProductType() == null
        ? ProductType.FX_MARGIN
        : symbol.getProductType();
    // DemoExecutionGuard intentionally rejects FX in P0. Historical audit fixtures mock this call.
    if (productType != ProductType.FX_MARGIN) {
      demoExecutionGuard.requireDemo(account, productType, position.getSymbol());
    }
    try {
      positionService.closeSystemPosition(position.getAccountId(), position.getId(), reason);
      return true;
    } catch (BusinessException exception) {
      if ("POSITION_NOT_OPEN".equals(exception.getCode())) {
        return false;
      }
      throw exception;
    }
  }

  private int scanForex(UUID accountId) {
    if (perpetualWorkflowAvailable()
        && safe(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
            accountId, PositionStatus.OPEN)).stream().noneMatch(this::isForex)) {
      return 0;
    }
    int closed = 0;
    Set<UUID> attemptedPositionIds = new HashSet<>();
    while (true) {
      AccountSnapshot snapshot = accountSnapshotService.snapshot(accountId);
      if (!fxMarginBreached(snapshot)) {
        return closed;
      }
      Optional<PositionEntity> candidate = safe(positionRepository
          .findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN)).stream()
          .filter(position -> position != null && position.getStatus() == PositionStatus.OPEN)
          .filter(position -> position.getId() != null
              && !attemptedPositionIds.contains(position.getId()))
          .filter(this::isForex)
          .max(Comparator.comparing(this::lossAmount));
      if (candidate.isEmpty() || lossAmount(candidate.orElseThrow()).compareTo(BigDecimal.ZERO) <= 0) {
        return closed;
      }
      PositionEntity selected = candidate.orElseThrow();
      attemptedPositionIds.add(selected.getId());
      boolean didClose = transactionExecutor.execute(() ->
          liquidatePosition(selected, FX_MARGIN_STOP_OUT));
      if (!didClose) {
        return closed;
      }
      closed++;
    }
  }

  private int scanPerpetual(UUID accountId) {
    LockedRisk initial = projectFresh(accountId);
    boolean pendingRetry = initial.account().getStatus() == AccountStatus.LIQUIDATION_PENDING;
    LockedRisk current = initial;
    int closed = 0;

    List<UUID> isolatedCandidates = initial.positions().stream()
        .filter(position -> position.getMarginMode() == MarginMode.ISOLATED)
        .filter(position -> isolatedLiquidatable(initial.projection(), position.getId()))
        .map(PositionEntity::getId)
        .sorted()
        .toList();
    for (UUID positionId : isolatedCandidates) {
      cancelAllOrderService.cancelRiskIncreasingSlot(accountId, positionId);
      LockedRisk afterCancel = projectFresh(accountId);
      current = afterCancel;
      if (!isolatedLiquidatable(afterCancel.projection(), positionId)) {
        continue;
      }
      AccountStatus statusBeforeGate = afterCancel.account().getStatus();
      boolean ownsIsolatedGate = statusBeforeGate == AccountStatus.ACTIVE
          || statusBeforeGate == AccountStatus.ISOLATED_LIQUIDATION_PENDING;
      if (statusBeforeGate == AccountStatus.ACTIVE) {
        updateStatus(accountId, AccountStatus.ISOLATED_LIQUIDATION_PENDING);
      }
      try {
        // The slot is now conclusively unsafe. Drain its remaining risk-reducing orders too,
        // otherwise an ordinary opposite order may reopen after the liquidation fill and a
        // reduce-only order may keep an orphaned hold after the position disappears.
        cancelAllOrderService.cancelActiveSlot(accountId, positionId);
        LockedRisk afterSlotDrain = projectFresh(accountId);
        current = afterSlotDrain;
        if (!isolatedLiquidatable(afterSlotDrain.projection(), positionId)) {
          continue;
        }
        try {
          SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeWhole(
              accountId,
              positionId,
              OrderOrigin.LIQUIDATION,
              "ISOLATED_MAINTENANCE_MARGIN",
              liquidationRequestId(accountId, positionId));
          if (!result.replayed()) {
            closed++;
          }
        } catch (RuntimeException exception) {
          log.warn("Isolated liquidation item failed: accountId={}, positionId={}",
              accountId, positionId, exception);
        }
      } finally {
        if (ownsIsolatedGate) {
          restoreIsolatedGate(accountId);
        }
      }
    }

    // Crash recovery: the gated slot may already be safe or closed after restart, so no
    // candidate-level finally block would otherwise release the typed Isolated gate.
    if (initial.account().getStatus() == AccountStatus.ISOLATED_LIQUIDATION_PENDING) {
      restoreIsolatedGate(accountId);
    }

    boolean hasCross = current.positions().stream()
        .anyMatch(position -> position.getMarginMode() == MarginMode.CROSS);
    if (pendingRetry && !hasCross) {
      settleCrossIfReady(accountId);
      return closed;
    }
    LockedRisk crossRisk = closed > 0 && hasCross ? projectFresh(accountId) : current;
    if (!pendingRetry && !crossRisk.projection().crossLiquidatable()) {
      return closed;
    }

    if (!pendingRetry) {
      updateStatus(accountId, AccountStatus.LIQUIDATION_PENDING);
    }
    cancelAllOrderService.cancelActivePerpetual(accountId);
    LockedRisk afterCancel = projectFresh(accountId);
    if (!pendingRetry && !afterCancel.projection().crossLiquidatable()) {
      updateStatus(accountId, AccountStatus.ACTIVE);
      return closed;
    }

    boolean failed = false;
    for (PositionEntity position : afterCancel.positions().stream()
        .filter(candidate -> candidate.getMarginMode() == MarginMode.CROSS)
        .sorted(Comparator.comparing(PositionEntity::getId))
        .toList()) {
      try {
        SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeWhole(
            accountId,
            position.getId(),
            OrderOrigin.LIQUIDATION,
            "CROSS_MAINTENANCE_MARGIN",
            liquidationRequestId(accountId, position.getId()));
        if (!result.replayed()) {
          closed++;
        }
      } catch (RuntimeException exception) {
        failed = true;
        log.warn("Cross liquidation item failed: accountId={}, positionId={}",
            accountId, position.getId(), exception);
      }
    }
    if (liquidationSettlementService == null) {
      if (!failed) {
        updateStatus(accountId, AccountStatus.ACTIVE);
      }
    } else {
      settleCrossIfReady(accountId);
    }
    return closed;
  }

  private LockedRisk projectFresh(UUID accountId) {
    PreparedAccountRisk prepared = accountRiskSnapshotService.prepare(accountId, Map.of());
    return transactionExecutor.execute(() -> {
      TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
          .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
      demoExecutionGuard.requireDemoRiskReductionAccount(account);
      requireNotAdminCleanup(account);
      List<PositionEntity> positions = safe(
          positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId));
      List<OrderEntity> orders = safe(
          orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId));
      AccountRiskProjection projection = accountRiskSnapshotService.project(
          account, positions, orders, prepared);
      accountRiskSnapshotService.applyRevaluation(account, positions, projection);
      accountRepository.save(account);
      positions.forEach(positionRepository::save);
      return new LockedRisk(account, List.copyOf(positions), projection);
    });
  }

  private void updateStatus(UUID accountId, AccountStatus status) {
    transactionExecutor.execute(() -> {
      TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
          .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
      demoExecutionGuard.requireDemoRiskReductionAccount(account);
      requireNotAdminCleanup(account);
      account.setStatus(status);
      accountRepository.save(account);
      return null;
    });
  }

  private void restoreIsolatedGate(UUID accountId) {
    transactionExecutor.execute(() -> {
      TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
          .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
      demoExecutionGuard.requireDemoRiskReductionAccount(account);
      if (account.getStatus() == AccountStatus.ISOLATED_LIQUIDATION_PENDING) {
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
      }
      return null;
    });
  }

  private static void requireNotAdminCleanup(TradingAccountEntity account) {
    if (account.getStatus() == AccountStatus.RISK_REDUCTION_PENDING) {
      throw new BusinessException(
          "ACCOUNT_CLEANUP_PENDING",
          "Liquidation scan is suspended while Admin cleanup owns the account");
    }
  }

  @Autowired(required = false)
  public void setLiquidationSettlementService(
      LiquidationSettlementService liquidationSettlementService
  ) {
    this.liquidationSettlementService = liquidationSettlementService;
  }

  private void settleCrossIfReady(UUID accountId) {
    if (liquidationSettlementService != null) {
      liquidationSettlementService.settleIfReady(accountId);
    }
  }

  private boolean isolatedLiquidatable(AccountRiskProjection projection, UUID positionId) {
    return projection.positionProjections().stream()
        .filter(position -> positionId.equals(position.positionId()))
        .map(PositionProjection::risk)
        .anyMatch(risk -> risk != null && risk.liquidatable());
  }

  private boolean perpetualWorkflowAvailable() {
    return orderRepository != null
        && cancelAllOrderService != null
        && accountRiskSnapshotService != null
        && systemCloseOrderService != null;
  }

  private boolean isForex(PositionEntity position) {
    if (position.getProductType() == ProductType.FX_MARGIN) {
      return true;
    }
    SymbolEntity symbol = symbolRepository.findBySymbol(normalize(position.getSymbol()))
        .orElseGet(() -> fallbackForex(position.getSymbol()));
    return instrumentClassifier.profile(symbol).kind() == InstrumentKind.FOREX;
  }

  private boolean fxMarginBreached(AccountSnapshot snapshot) {
    return snapshot.marginLevel() != null
        && snapshot.marginLevel().compareTo(stopOutLevel()) <= 0;
  }

  private BigDecimal stopOutLevel() {
    Optional<RiskConfigEntity> config = riskConfigRepository.findFirstEnabledWithStopOutLevel();
    return config
        .map(RiskConfigEntity::getStopOutLevel)
        .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
        .orElseGet(() -> defaultStopOutLevel == null ? DEFAULT_STOP_OUT_LEVEL : defaultStopOutLevel);
  }

  private BigDecimal lossAmount(PositionEntity position) {
    BigDecimal pnl = orZero(position.getFloatingPnl());
    return pnl.compareTo(BigDecimal.ZERO) < 0 ? pnl.negate() : BigDecimal.ZERO;
  }

  private SymbolEntity fallbackForex(String code) {
    String symbol = normalize(code);
    SymbolEntity entity = new SymbolEntity();
    entity.setSymbol(symbol);
    entity.setProductType(ProductType.FX_MARGIN);
    entity.setAssetClass("FOREX");
    if (symbol.length() >= 6) {
      entity.setBaseCurrency(symbol.substring(0, 3));
      entity.setQuoteCurrency(symbol.substring(symbol.length() - 3));
    }
    return entity;
  }

  private String liquidationRequestId(UUID accountId, UUID positionId) {
    return "liquidation:" + accountId + ':' + positionId;
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private record LockedRisk(
      TradingAccountEntity account,
      List<PositionEntity> positions,
      AccountRiskProjection projection
  ) {
  }
}
