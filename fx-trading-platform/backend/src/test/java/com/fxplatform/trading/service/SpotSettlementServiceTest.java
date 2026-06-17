package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpotSettlementServiceTest {

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  private final Map<String, WalletBalanceEntity> balances = new HashMap<>();
  private final List<AssetLedgerEntryEntity> ledgerEntries = new ArrayList<>();

  @BeforeEach
  void setUpRepositories() {
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(any(UUID.class), any(String.class), any(String.class)))
        .thenAnswer(invocation -> Optional.ofNullable(
            balances.get(key(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class))).thenAnswer(invocation -> {
      WalletBalanceEntity balance = invocation.getArgument(0);
      if (balance.getId() == null) {
        balance.setId(UUID.randomUUID());
      }
      balances.put(key(balance.getAccountId(), balance.getWalletType(), balance.getAsset()), balance);
      return balance;
    });
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class))).thenAnswer(invocation -> {
      AssetLedgerEntryEntity entry = invocation.getArgument(0);
      ledgerEntries.add(entry);
      return entry;
    });
  }

  @Test
  void settleBuyFillDebitsQuoteAndCreditsNetBaseWithBaseFee() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "10000.00000000", "10000.00000000", "0");
    SpotSettlementService service = service();

    service.settleBuyFill(
        order(account.getId(), OrderSide.BUY, "0.1"),
        execution("50000.00000000", "0.1", "5.00000000"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("5000.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getTotal()).isEqualByComparingTo("5000.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.09990000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getTotal()).isEqualByComparingTo("0.09990000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_BUY_DEBIT", "SPOT_BUY_CREDIT", "TRADE_FEE");
  }

  @Test
  void settleSellFillDebitsBaseAndCreditsNetQuoteWithQuoteFee() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "BTC", "0.10000000", "0.10000000", "0");
    putBalance(account.getId(), "USDT", "0", "0", "0");
    SpotSettlementService service = service();

    service.settleSellFill(
        order(account.getId(), OrderSide.SELL, "0.1"),
        execution("55000.00000000", "0.1", "5.50000000"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getTotal()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("5494.50000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getTotal()).isEqualByComparingTo("5494.50000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_SELL_DEBIT", "SPOT_SELL_CREDIT", "TRADE_FEE");
  }

  @Test
  void settlePendingBuyFillDebitsLockedQuoteAndReleasesRemainingHold() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "6000.00000000", "1000.00000000", "5000.00000000");
    SpotSettlementService service = service();

    service.settleBuyFill(
        pendingOrder(account.getId(), OrderSide.BUY, "0.10", "5000.00000000", "USDT"),
        execution("49000.00000000", "0.04", "1.96000000"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getTotal()).isEqualByComparingTo("4040.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("4040.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getLocked()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.03996000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_BUY_DEBIT", "ORDER_RELEASE", "SPOT_BUY_CREDIT", "TRADE_FEE");
  }

  @Test
  void settlePendingSellFillDebitsLockedBaseAndReleasesRemainingHold() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "BTC", "0.10000000", "0.00000000", "0.10000000");
    putBalance(account.getId(), "USDT", "0", "0", "0");
    SpotSettlementService service = service();

    service.settleSellFill(
        pendingOrder(account.getId(), OrderSide.SELL, "0.10", "0.10000000", "BTC"),
        execution("49000.00000000", "0.04", "1.96000000"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getTotal()).isEqualByComparingTo("0.06000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.06000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getLocked()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("1958.04000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_SELL_DEBIT", "ORDER_RELEASE", "SPOT_SELL_CREDIT", "TRADE_FEE");
  }

  private SpotSettlementService service() {
    return new SpotSettlementService(new WalletService(walletBalanceRepository, assetLedgerEntryRepository));
  }

  private WalletBalanceEntity balance(UUID accountId, WalletType walletType, String asset) {
    return balances.get(key(accountId, walletType.code(), asset));
  }

  private void putBalance(UUID accountId, String asset, String total, String available, String locked) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setId(UUID.randomUUID());
    balance.setAccountId(accountId);
    balance.setWalletType(WalletType.SPOT.code());
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(total));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(new BigDecimal(locked));
    balances.put(key(accountId, WalletType.SPOT.code(), asset), balance);
  }

  private static String key(UUID accountId, String walletType, String asset) {
    return accountId + ":" + walletType + ":" + asset;
  }

  private static TradingAccountEntity account() {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(UUID.randomUUID());
    account.setUsedMargin(BigDecimal.ZERO);
    return account;
  }

  private static OrderEntity order(UUID accountId, OrderSide side, String quantity) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT");
    order.setSide(side);
    order.setQuantity(new BigDecimal(quantity));
    order.setLots(new BigDecimal(quantity));
    return order;
  }

  private static OrderEntity pendingOrder(
      UUID accountId,
      OrderSide side,
      String quantity,
      String holdAmount,
      String holdCurrency
  ) {
    OrderEntity order = order(accountId, side, quantity);
    order.setHoldAmount(new BigDecimal(holdAmount));
    order.setHoldCurrency(holdCurrency);
    return order;
  }

  private static ExecutionResult execution(String price, String quantity, String fee) {
    return new ExecutionResult(
        new BigDecimal(price),
        Instant.parse("2026-06-16T01:00:00Z"),
        new BigDecimal(quantity),
        BigDecimal.ZERO,
        new BigDecimal(fee),
        BigDecimal.ZERO,
        null,
        null);
  }

  private static SymbolEntity btcUsdt() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("BTCUSDT");
    symbol.setAssetClass("CRYPTO");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setLeverage(1);
    return symbol;
  }
}
