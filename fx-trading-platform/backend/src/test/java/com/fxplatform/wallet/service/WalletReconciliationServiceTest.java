package com.fxplatform.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import com.fxplatform.wallet.model.WalletReconciliationReport;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletReconciliationServiceTest {

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private PositionRepository positionRepository;

  @Test
  void reconcileAllAcceptsConsistentWalletLedgerAndMarginState() {
    UUID accountId = UUID.randomUUID();
    UUID spotOrderId = UUID.randomUUID();
    UUID marginOrderId = UUID.randomUUID();
    WalletBalanceEntity btcBalance = walletBalance(accountId, "BTC", "1.00000000", "0.80000000", "0.20000000");
    AssetLedgerEntryEntity buyEntry = assetEntry(accountId, "BTC", "1.00000000", "SPOT_BUY_CREDIT");
    AssetLedgerEntryEntity lockEntry = assetEntry(accountId, "BTC", "-0.20000000", "SPOT_ORDER_LOCK");
    TradingAccountEntity account = account(accountId);
    PositionEntity position = position(accountId, "100.00000000");
    OrderEntity spotOrder = order(accountId, spotOrderId, "BTC", "0.20000000");
    OrderEntity marginOrder = order(accountId, marginOrderId, "USD", "10.00000000");

    when(walletBalanceRepository.findAll()).thenReturn(List.of(btcBalance));
    when(assetLedgerEntryRepository.findByFilters(accountId, WalletType.SPOT.code(), "BTC", null, null, null, null))
        .thenReturn(List.of(buyEntry, lockEntry));
    when(assetLedgerEntryRepository.findByBusinessOperation(
        eq(accountId),
        eq(WalletType.SPOT.code()),
        eq("BTC"),
        eq("ORDER"),
        eq(spotOrderId),
        eq("SPOT_ORDER_LOCK")))
        .thenReturn(lockEntry);
    when(assetLedgerEntryRepository.findByBusinessOperation(
        eq(accountId),
        eq(WalletType.SPOT.code()),
        eq("USD"),
        eq("ORDER"),
        eq(marginOrderId),
        eq("SPOT_ORDER_LOCK")))
        .thenReturn(null);
    when(accountRepository.findAll()).thenReturn(List.of(account));
    when(orderRepository.findByAccountIdAndStatusIn(eq(accountId), any())).thenReturn(List.of(spotOrder, marginOrder));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId))
        .thenReturn(List.of(
            cashEntry(accountId, LedgerEntryType.DEMO_DEPOSIT, "1000.00000000"),
            cashEntry(accountId, LedgerEntryType.ORDER_HOLD, "10.00000000")));

    WalletReconciliationReport report = service().reconcileAll();

    assertThat(report.balanced()).isTrue();
    assertThat(report.issues()).isEmpty();
  }

  private WalletReconciliationService service() {
    return new WalletReconciliationService(
        walletBalanceRepository,
        assetLedgerEntryRepository,
        accountRepository,
        ledgerEntryRepository,
        orderRepository,
        positionRepository);
  }

  private static WalletBalanceEntity walletBalance(
      UUID accountId,
      String asset,
      String total,
      String available,
      String locked
  ) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setAccountId(accountId);
    balance.setWalletType(WalletType.SPOT.code());
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(total));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(new BigDecimal(locked));
    return balance;
  }

  private static AssetLedgerEntryEntity assetEntry(UUID accountId, String asset, String amount, String operationType) {
    AssetLedgerEntryEntity entry = new AssetLedgerEntryEntity();
    entry.setAccountId(accountId);
    entry.setWalletType(WalletType.SPOT.code());
    entry.setAsset(asset);
    entry.setAmount(new BigDecimal(amount));
    entry.setEntryType(operationType);
    entry.setOperationType(operationType);
    entry.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
    return entry;
  }

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("1000.00000000"));
    account.setUsedMargin(new BigDecimal("110.00000000"));
    return account;
  }

  private static PositionEntity position(UUID accountId, String marginHeld) {
    PositionEntity position = new PositionEntity();
    position.setAccountId(accountId);
    position.setStatus(PositionStatus.OPEN);
    position.setMarginHeld(new BigDecimal(marginHeld));
    return position;
  }

  private static OrderEntity order(UUID accountId, UUID orderId, String holdCurrency, String holdAmount) {
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setAccountId(accountId);
    order.setStatus(OrderStatus.PENDING);
    order.setHoldCurrency(holdCurrency);
    order.setHoldAmount(new BigDecimal(holdAmount));
    return order;
  }

  private static LedgerEntryEntity cashEntry(UUID accountId, LedgerEntryType type, String amount) {
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setAccountId(accountId);
    entry.setEntryType(type);
    entry.setAmount(new BigDecimal(amount));
    return entry;
  }
}
