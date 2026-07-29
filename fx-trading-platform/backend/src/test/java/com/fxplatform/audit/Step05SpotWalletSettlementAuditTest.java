package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.service.SpotSettlementService;
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
class Step05SpotWalletSettlementAuditTest {

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  private final Map<String, WalletBalanceEntity> balances = new HashMap<>();
  private final List<AssetLedgerEntryEntity> ledgerEntries = new ArrayList<>();

  @BeforeEach
  void setUpRepositories() {
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        any(UUID.class), any(String.class), any(String.class)))
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
  void spotBuySettlesQuoteAndUsdtFeeOutAndFullBaseInWithoutMarginPosition() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "10000.00000000", "10000.00000000", "0");

    service().settleBuyFill(
        order(account.getId(), OrderSide.BUY, "0.1"),
        execution("50000.00000000", "0.1", "2.50000000", "USDT"),
        btcUsdt(),
        account);

    AuditAssertions.assertAmountClose(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable(), "4997.50000000");
    AuditAssertions.assertBtcClose(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable(), "0.10000000");
    AuditAssertions.assertAmountClose(account.getUsedMargin(), "0.00000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_BUY_DEBIT", "TRADE_FEE", "SPOT_BUY_CREDIT");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getAsset)
        .containsExactly("USDT", "USDT", "BTC");
  }

  @Test
  void spotSellSettlesBaseOutAndNetQuoteInWithoutSellPosition() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "BTC", "0.10000000", "0.10000000", "0");
    putBalance(account.getId(), "USDT", "0", "0", "0");

    service().settleSellFill(
        order(account.getId(), OrderSide.SELL, "0.1"),
        execution("55000.00000000", "0.1", "2.75000000", "USDT"),
        btcUsdt(),
        account);

    AuditAssertions.assertBtcClose(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable(), "0.00000000");
    AuditAssertions.assertAmountClose(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable(), "5497.25000000");
    AuditAssertions.assertAmountClose(account.getUsedMargin(), "0.00000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_SELL_DEBIT", "SPOT_SELL_CREDIT", "TRADE_FEE");
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

  private static ExecutionResult execution(String price, String quantity, String fee, String feeAsset) {
    return new ExecutionResult(
        new BigDecimal(price),
        Instant.parse("2026-06-16T01:00:00Z"),
        new BigDecimal(quantity),
        BigDecimal.ZERO,
        new BigDecimal(fee),
        feeAsset,
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
