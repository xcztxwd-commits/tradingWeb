package com.fxplatform.wallet.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.model.WalletReconciliationIssue;
import com.fxplatform.wallet.model.WalletReconciliationReport;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WalletReconciliationService {

  private static final int SCALE = 8;
  private static final List<OrderStatus> ACTIVE_HOLD_STATUSES = List.of(
      OrderStatus.PENDING,
      OrderStatus.WORKING,
      OrderStatus.PARTIALLY_FILLED);

  private final WalletBalanceRepository walletBalanceRepository;
  private final AssetLedgerEntryRepository assetLedgerEntryRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final OrderRepository orderRepository;
  private final PositionRepository positionRepository;

  public WalletReconciliationReport reconcileAll() {
    List<WalletReconciliationIssue> issues = new ArrayList<>();
    for (WalletBalanceEntity balance : walletBalanceRepository.findAll()) {
      reconcileWalletBalance(balance, issues);
    }
    for (TradingAccountEntity account : accountRepository.findAll()) {
      reconcileTradingAccount(account, issues);
    }
    return new WalletReconciliationReport(Instant.now(), List.copyOf(issues));
  }

  private void reconcileWalletBalance(
      WalletBalanceEntity balance,
      List<WalletReconciliationIssue> issues
  ) {
    BigDecimal parts = money(balance.getAvailable()).add(money(balance.getLocked()));
    addIssueIfDifferent(
        issues,
        "WALLET_TOTAL_PARTS_MISMATCH",
        balance.getAccountId(),
        balance.getWalletType(),
        balance.getAsset(),
        parts,
        money(balance.getTotal()),
        "wallet total must equal available plus locked");

    BigDecimal ledgerTotal = assetLedgerEntryRepository.findByFilters(
            balance.getAccountId(),
            balance.getWalletType(),
            balance.getAsset(),
            null,
            null,
            null,
            null)
        .stream()
        .map(this::assetLedgerTotalDelta)
        .reduce(zero(), BigDecimal::add);
    addIssueIfDifferent(
        issues,
        "ASSET_LEDGER_TOTAL_MISMATCH",
        balance.getAccountId(),
        balance.getWalletType(),
        balance.getAsset(),
        money(ledgerTotal),
        money(balance.getTotal()),
        "asset ledger total delta must match wallet total");

    BigDecimal pendingHold = pendingSpotHold(balance);
    addIssueIfDifferent(
        issues,
        "PENDING_SPOT_HOLD_MISMATCH",
        balance.getAccountId(),
        balance.getWalletType(),
        balance.getAsset(),
        pendingHold,
        money(balance.getLocked()),
        "active spot order holds must match wallet locked balance");
  }

  private void reconcileTradingAccount(
      TradingAccountEntity account,
      List<WalletReconciliationIssue> issues
  ) {
    BigDecimal openMargin = positionRepository
        .findByAccountIdAndStatusOrderByOpenedAtDesc(account.getId(), PositionStatus.OPEN)
        .stream()
        .map(PositionEntity::getMarginHeld)
        .map(WalletReconciliationService::money)
        .reduce(zero(), BigDecimal::add);
    BigDecimal pendingMargin = orderRepository.findByAccountIdAndStatusIn(account.getId(), ACTIVE_HOLD_STATUSES)
        .stream()
        .filter(order -> !hasSpotOrderLock(order))
        .map(OrderEntity::getHoldAmount)
        .map(WalletReconciliationService::money)
        .reduce(zero(), BigDecimal::add);
    BigDecimal expectedUsedMargin = money(openMargin.add(pendingMargin));
    addIssueIfDifferent(
        issues,
        "ACCOUNT_USED_MARGIN_MISMATCH",
        account.getId(),
        "MARGIN",
        asset(account.getBaseCurrency()),
        expectedUsedMargin,
        money(account.getUsedMargin()),
        "usedMargin must equal open position margin plus pending margin holds");

    BigDecimal ledgerBalance = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(account.getId())
        .stream()
        .map(this::cashLedgerBalanceDelta)
        .reduce(zero(), BigDecimal::add);
    addIssueIfDifferent(
        issues,
        "ACCOUNT_BALANCE_LEDGER_MISMATCH",
        account.getId(),
        "MARGIN",
        asset(account.getBaseCurrency()),
        money(ledgerBalance),
        money(account.getBalance()),
        "cash ledger balance delta must match account balance");
  }

  private BigDecimal pendingSpotHold(WalletBalanceEntity balance) {
    return orderRepository.findByAccountIdAndStatusIn(balance.getAccountId(), ACTIVE_HOLD_STATUSES)
        .stream()
        .filter(order -> asset(order.getHoldCurrency()).equals(asset(balance.getAsset())))
        .filter(this::hasSpotOrderLock)
        .map(OrderEntity::getHoldAmount)
        .map(WalletReconciliationService::money)
        .reduce(zero(), BigDecimal::add);
  }

  private boolean hasSpotOrderLock(OrderEntity order) {
    if (order.getId() == null || order.getAccountId() == null || order.getHoldCurrency() == null) {
      return false;
    }
    return assetLedgerEntryRepository.findByBusinessOperation(
        order.getAccountId(),
        WalletType.SPOT.code(),
        asset(order.getHoldCurrency()),
        "ORDER",
        order.getId(),
        "SPOT_ORDER_LOCK") != null;
  }

  private BigDecimal assetLedgerTotalDelta(AssetLedgerEntryEntity entry) {
    String operationType = operationType(entry);
    if ("SPOT_ORDER_LOCK".equals(operationType)
        || "LOCK_AVAILABLE".equals(operationType)
        || "ORDER_RELEASE".equals(operationType)
        || "SPOT_ORDER_RELEASE".equals(operationType)
        || "RELEASE_LOCKED".equals(operationType)) {
      return zero();
    }
    return money(entry.getAmount());
  }

  private BigDecimal cashLedgerBalanceDelta(LedgerEntryEntity entry) {
    LedgerEntryType type = entry.getEntryType();
    if (type == LedgerEntryType.ORDER_HOLD
        || type == LedgerEntryType.ORDER_RELEASE
        || type == LedgerEntryType.MARGIN_HOLD
        || type == LedgerEntryType.MARGIN_RELEASE
        || type == LedgerEntryType.FORCED_CLOSE) {
      return zero();
    }
    return money(entry.getAmount());
  }

  private void addIssueIfDifferent(
      List<WalletReconciliationIssue> issues,
      String code,
      java.util.UUID accountId,
      String walletType,
      String asset,
      BigDecimal expected,
      BigDecimal actual,
      String message
  ) {
    if (money(expected).compareTo(money(actual)) != 0) {
      issues.add(new WalletReconciliationIssue(
          code,
          accountId,
          walletType,
          asset,
          money(expected),
          money(actual),
          message));
    }
  }

  private String operationType(AssetLedgerEntryEntity entry) {
    String operationType = entry.getOperationType();
    if (operationType == null || operationType.isBlank()) {
      operationType = entry.getEntryType();
    }
    return operationType == null ? "" : operationType.trim().toUpperCase(Locale.ROOT);
  }

  private static String asset(String asset) {
    return asset == null || asset.isBlank() ? "" : asset.trim().toUpperCase(Locale.ROOT);
  }

  private static BigDecimal money(BigDecimal value) {
    return orZero(value).setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  }
}
