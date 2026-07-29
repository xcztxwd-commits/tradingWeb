package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
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

  @Mock
  private SpotPositionService spotPositionService;

  private final Map<String, WalletBalanceEntity> balances = new HashMap<>();
  private final List<AssetLedgerEntryEntity> ledgerEntries = new ArrayList<>();

  @BeforeEach
  void setUpRepositories() {
    org.mockito.Mockito.lenient().when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        any(UUID.class), any(String.class), any(String.class)))
        .thenAnswer(invocation -> Optional.ofNullable(
            balances.get(key(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
    org.mockito.Mockito.lenient().when(walletBalanceRepository.save(any(WalletBalanceEntity.class))).thenAnswer(invocation -> {
      WalletBalanceEntity balance = invocation.getArgument(0);
      if (balance.getId() == null) {
        balance.setId(UUID.randomUUID());
      }
      balances.put(key(balance.getAccountId(), balance.getWalletType(), balance.getAsset()), balance);
      return balance;
    });
    org.mockito.Mockito.lenient().when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class))).thenAnswer(invocation -> {
      AssetLedgerEntryEntity entry = invocation.getArgument(0);
      ledgerEntries.add(entry);
      return entry;
    });
  }

  @Test
  void settleBuyFillDebitsQuoteAndUsdtFeeAndCreditsFullBase() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "10000.00000000", "10000.00000000", "0");
    SpotSettlementService service = service();

    service.settleBuyFill(
        order(account.getId(), OrderSide.BUY, "0.1"),
        execution("50000.00000000", "0.1", "2.50000000", "USDT"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("4997.50000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getTotal()).isEqualByComparingTo("4997.50000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.10000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getTotal()).isEqualByComparingTo("0.10000000");
    assertThat(account.getBalance()).isEqualByComparingTo("10000.00000000");
    assertThat(account.getEquity()).isEqualByComparingTo("10000.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("10000.00000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_BUY_DEBIT", "TRADE_FEE", "SPOT_BUY_CREDIT");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getAsset)
        .containsExactly("USDT", "USDT", "BTC");
    verify(spotPositionService).applyBuy(
        account.getId(),
        "BTC",
        "USDT",
        new BigDecimal("0.10000000"),
        new BigDecimal("5000.00000000"),
        new BigDecimal("2.50000000"));
  }

  @Test
  void immediateBuyRejectsGrossPlusFeeShortfallBeforeAnyMutation() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "100.02000000", "100.02000000", "0");

    assertThatThrownBy(() -> service().settleBuyFill(
        order(account.getId(), OrderSide.BUY, "0.1"),
        execution("1000.00000000", "0.1", "0.05000000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_BALANCE"));

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getTotal()).isEqualByComparingTo("100.02000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("100.02000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC")).isNull();
    assertThat(ledgerEntries).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(spotPositionService);
  }

  @Test
  void settleSellFillDebitsBaseAndCreditsNetQuoteWithQuoteFee() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "BTC", "0.10000000", "0.10000000", "0");
    putBalance(account.getId(), "USDT", "0", "0", "0");
    SpotSettlementService service = service();

    service.settleSellFill(
        order(account.getId(), OrderSide.SELL, "0.1"),
        execution("55000.00000000", "0.1", "2.75000000", "USDT"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getTotal()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("5497.25000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getTotal()).isEqualByComparingTo("5497.25000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_SELL_DEBIT", "SPOT_SELL_CREDIT", "TRADE_FEE");
  }

  @Test
  void settleBuyPartialFillConsumesOnlyThisFillAndUsesDeterministicTradeReference() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "1001.00000000", "0", "1001.00000000");
    OrderEntity order = pendingOrder(
        account.getId(), OrderSide.BUY, "10", "1001.00000000", "USDT");
    UUID tradeId = UUID.randomUUID();

    service().settleBuyPartialFill(
        order,
        order,
        execution("90.00000000", "2", "0.09000000", "USDT"),
        btcUsdt(),
        account,
        tradeId);

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getTotal()).isEqualByComparingTo("820.91000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("0.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("820.91000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable())
        .isEqualByComparingTo("2.00000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_BUY_DEBIT", "TRADE_FEE", "SPOT_BUY_CREDIT");
    assertThat(ledgerEntries).allSatisfy(entry -> {
      assertThat(entry.getReferenceType()).isEqualTo("TRADE");
      assertThat(entry.getReferenceId()).isEqualTo(tradeId);
      assertThat(entry.getEntryType()).isNotEqualTo("ORDER_RELEASE");
      assertThat(entry.getEntryType()).isNotEqualTo("SPOT_ORDER_RELEASE");
    });
    verify(spotPositionService).applyBuy(
        account.getId(), "BTC", "USDT",
        new BigDecimal("2.00000000"),
        new BigDecimal("180.00000000"),
        new BigDecimal("0.09000000"));
  }

  @Test
  void settleSellPartialFillConsumesLockedBaseAndChargesQuoteFeePerTrade() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "BTC", "10.00000000", "0", "10.00000000");
    putBalance(account.getId(), "USDT", "0", "0", "0");
    OrderEntity order = pendingOrder(
        account.getId(), OrderSide.SELL, "10", "10.00000000", "BTC");
    UUID tradeId = UUID.randomUUID();

    service().settleSellPartialFill(
        order,
        order,
        execution("90.00000000", "2", "0.09000000", "USDT"),
        btcUsdt(),
        account,
        tradeId);

    WalletBalanceEntity base = balance(account.getId(), WalletType.SPOT, "BTC");
    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(base.getTotal()).isEqualByComparingTo("8.00000000");
    assertThat(base.getAvailable()).isEqualByComparingTo("0.00000000");
    assertThat(base.getLocked()).isEqualByComparingTo("8.00000000");
    assertThat(quote.getTotal()).isEqualByComparingTo("179.91000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("179.91000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_SELL_DEBIT", "SPOT_SELL_CREDIT", "TRADE_FEE");
    assertThat(ledgerEntries).allSatisfy(entry -> {
      assertThat(entry.getReferenceType()).isEqualTo("TRADE");
      assertThat(entry.getReferenceId()).isEqualTo(tradeId);
      assertThat(entry.getEntryType()).isNotEqualTo("ORDER_RELEASE");
      assertThat(entry.getEntryType()).isNotEqualTo("SPOT_ORDER_RELEASE");
    });
    verify(spotPositionService).applySell(
        account.getId(), "BTC", "USDT",
        new BigDecimal("2.00000000"),
        new BigDecimal("180.00000000"),
        new BigDecimal("0.09000000"));
  }

  @Test
  void partialSettlementInvokesEveryWalletOperationWithoutAnOverallReplayShortcut() {
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    SpotPositionService positions = org.mockito.Mockito.mock(SpotPositionService.class);
    SpotSettlementService service = new SpotSettlementService(walletService, positions);
    TradingAccountEntity account = account();
    OrderEntity order = pendingOrder(
        account.getId(), OrderSide.BUY, "10", "1001.00000000", "USDT");
    UUID tradeId = UUID.randomUUID();

    service.settleBuyPartialFill(
        order,
        order,
        execution("90.00000000", "2", "0.09000000", "USDT"),
        btcUsdt(),
        account,
        tradeId);

    verify(walletService).debitLockedWithEntryType(
        account.getId(), "USDT", new BigDecimal("180.00000000"), "TRADE", tradeId,
        "Spot buy quote spent", "SPOT_BUY_DEBIT");
    verify(walletService).debitLockedWithEntryType(
        account.getId(), "USDT", new BigDecimal("0.09000000"), "TRADE", tradeId,
        "Spot buy fee charged in USDT", "TRADE_FEE");
    verify(walletService).creditAvailableWithEntryType(
        account.getId(), "BTC", new BigDecimal("2.00000000"), "TRADE", tradeId,
        "Spot buy base received", "SPOT_BUY_CREDIT");
    verify(walletService, org.mockito.Mockito.never()).hasBusinessOperation(
        any(UUID.class), any(WalletType.class), any(String.class), any(String.class),
        any(UUID.class), any(String.class));
    verify(walletService, org.mockito.Mockito.never()).releaseLockedWithEntryType(
        any(UUID.class), any(String.class), any(BigDecimal.class), any(String.class),
        any(UUID.class), any(String.class), any(String.class));
  }

  @Test
  void rejectsFeeAssetMismatchBeforeAnyWalletMutation() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "10000.00000000", "10000.00000000", "0");
    SpotSettlementService service = service();

    assertThatThrownBy(() -> service.settleBuyFill(
        order(account.getId(), OrderSide.BUY, "0.1"),
        execution("50000.00000000", "0.1", "2.50000000", "BTC"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_FEE_ASSET"));

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable())
        .isEqualByComparingTo("10000.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void rejectsPositiveFeeWithoutAssetBeforeAnyWalletMutation() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "10000.00000000", "10000.00000000", "0");

    assertThatThrownBy(() -> service().settleBuyFill(
        order(account.getId(), OrderSide.BUY, "0.1"),
        execution("50000.00000000", "0.1", "2.50000000", null),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_FEE_ASSET"));

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable())
        .isEqualByComparingTo("10000.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void rejectsNegativeFeeBeforeAnyWalletMutation() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "10000.00000000", "10000.00000000", "0");

    assertThatThrownBy(() -> service().settleBuyFill(
        order(account.getId(), OrderSide.BUY, "0.1"),
        execution("50000.00000000", "0.1", "-2.50000000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_FEE_AMOUNT"));

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable())
        .isEqualByComparingTo("10000.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void settlePendingBuyFillDebitsLockedQuoteAndReleasesRemainingHold() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "6000.00000000", "1000.00000000", "5000.00000000");
    SpotSettlementService service = service();

    service.settleBuyFill(
        pendingOrder(account.getId(), OrderSide.BUY, "0.10", "5000.00000000", "USDT"),
        execution("49000.00000000", "0.04", "0.98000000", "USDT"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getTotal()).isEqualByComparingTo("4039.02000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("4039.02000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getLocked()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.04000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_BUY_DEBIT", "TRADE_FEE", "ORDER_RELEASE", "SPOT_BUY_CREDIT");
  }

  @Test
  void settlePendingSellFillDebitsLockedBaseAndReleasesRemainingHold() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "BTC", "0.10000000", "0.00000000", "0.10000000");
    putBalance(account.getId(), "USDT", "0", "0", "0");
    SpotSettlementService service = service();

    service.settleSellFill(
        pendingOrder(account.getId(), OrderSide.SELL, "0.10", "0.10000000", "BTC"),
        execution("49000.00000000", "0.04", "0.98000000", "USDT"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getTotal()).isEqualByComparingTo("0.06000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getAvailable()).isEqualByComparingTo("0.06000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "BTC").getLocked()).isEqualByComparingTo("0.00000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable()).isEqualByComparingTo("1959.02000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_SELL_DEBIT", "ORDER_RELEASE", "SPOT_SELL_CREDIT", "TRADE_FEE");
  }

  @Test
  void nonOwnerOcoLegConsumesAndReleasesTheOwnersSingleLockedHold() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "6000.00000000", "1000.00000000", "5000.00000000");
    OrderEntity owner = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "5000.00000000", "USDT");
    OrderEntity nonOwner = order(account.getId(), OrderSide.BUY, "0.04");
    nonOwner.setHoldAmount(BigDecimal.ZERO);
    nonOwner.setHoldCurrency("USDT");
    nonOwner.setHoldOwnerOrderId(owner.getId());

    service().settleBuyFillUsingHoldOwner(
        nonOwner,
        owner,
        execution("49000.00000000", "0.04", "0.98000000", "USDT"),
        btcUsdt(),
        account);

    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getTotal())
        .isEqualByComparingTo("4039.02000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getAvailable())
        .isEqualByComparingTo("4039.02000000");
    assertThat(balance(account.getId(), WalletType.SPOT, "USDT").getLocked())
        .isEqualByComparingTo("0.00000000");
    assertThat(ledgerEntries)
        .filteredOn(entry -> entry.getEntryType().equals("ORDER_RELEASE"))
        .singleElement()
        .extracting(AssetLedgerEntryEntity::getReferenceId)
        .isEqualTo(owner.getId());
  }

  @Test
  void buyJumpBeyondOwnersLockedHoldNeverFallsBackToAvailableBalance() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "150.00000000", "50.00000000", "100.00000000");
    OrderEntity owner = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "100.00000000", "USDT");
    OrderEntity nonOwner = order(account.getId(), OrderSide.BUY, "0.10");
    nonOwner.setHoldOwnerOrderId(owner.getId());

    assertThatThrownBy(() -> service().settleBuyFillUsingHoldOwner(
        nonOwner,
        owner,
        execution("1100.00000000", "0.10", "0.05500000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("LOCKED_BALANCE_NOT_ENOUGH"));

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getTotal()).isEqualByComparingTo("150.00000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("50.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("100.00000000");
    assertThat(quote.getTotal()).isEqualByComparingTo(
        quote.getAvailable().add(quote.getLocked()));
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void ownerHoldCannotConsumeAnotherOrdersAggregateLockedFunds() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "250.00000000", "50.00000000", "200.00000000");
    OrderEntity owner = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "100.00000000", "USDT");
    OrderEntity nonOwner = order(account.getId(), OrderSide.BUY, "0.10");
    nonOwner.setHoldOwnerOrderId(owner.getId());

    assertThatThrownBy(() -> service().settleBuyFillUsingHoldOwner(
        nonOwner,
        owner,
        execution("1100.00000000", "0.10", "0.05500000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("LOCKED_BALANCE_NOT_ENOUGH"));

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getTotal()).isEqualByComparingTo("250.00000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("50.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("200.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void singlePendingHoldCannotConsumeAnotherOrdersAggregateLockedFunds() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "250.00000000", "50.00000000", "200.00000000");
    OrderEntity pending = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "100.00000000", "USDT");

    assertThatThrownBy(() -> service().settleBuyFill(
        pending,
        execution("1100.00000000", "0.10", "0.05500000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("LOCKED_BALANCE_NOT_ENOUGH"));

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getTotal()).isEqualByComparingTo("250.00000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("50.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("200.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void pendingBuyNeverReleasesOrFallsBackWhenGrossFitsButFeeExceedsOwnerHold() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "150.00000000", "50.00000000", "100.00000000");
    OrderEntity pending = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "100.00000000", "USDT");

    assertThatThrownBy(() -> service().settleBuyFill(
        pending,
        execution("1000.00000000", "0.10", "0.05000000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("LOCKED_BALANCE_NOT_ENOUGH"));

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getTotal()).isEqualByComparingTo("150.00000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("50.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("100.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void ownerHoldComparisonUsesTheSameEightDecimalWalletRounding() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "100.00000000", "0.00000000", "100.00000000");
    OrderEntity pending = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "100.000000004", "USDT");

    service().settleBuyFill(
        pending,
        execution("1000.00000004", "0.10", "0", "USDT"),
        btcUsdt(),
        account);

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getTotal()).isEqualByComparingTo("0.00000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("0.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("0.00000000");
  }

  @Test
  void ocoHoldCurrencyMismatchNeverFallsBackToAvailableBalance() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "200.00000000", "100.00000000", "100.00000000");
    OrderEntity owner = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "100.00000000", "BTC");
    OrderEntity winner = order(account.getId(), OrderSide.BUY, "0.10");
    UUID groupId = UUID.randomUUID();
    owner.setContingencyGroupId(groupId);
    winner.setContingencyGroupId(groupId);
    winner.setHoldOwnerOrderId(owner.getId());

    assertThatThrownBy(() -> service().settleBuyFillUsingHoldOwner(
        winner,
        owner,
        execution("500.00000000", "0.10", "0.02500000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_GROUP_INCOMPLETE"));

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getAvailable()).isEqualByComparingTo("100.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("100.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  @Test
  void singlePendingHoldCurrencyMismatchNeverFallsBackToAvailableBalance() {
    TradingAccountEntity account = account();
    putBalance(account.getId(), "USDT", "200.00000000", "100.00000000", "100.00000000");
    OrderEntity pending = pendingOrder(
        account.getId(), OrderSide.BUY, "0.10", "100.00000000", "BTC");

    assertThatThrownBy(() -> service().settleBuyFill(
        pending,
        execution("500.00000000", "0.10", "0.02500000", "USDT"),
        btcUsdt(),
        account))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_HOLD_INVALID"));

    WalletBalanceEntity quote = balance(account.getId(), WalletType.SPOT, "USDT");
    assertThat(quote.getAvailable()).isEqualByComparingTo("100.00000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("100.00000000");
    assertThat(ledgerEntries).isEmpty();
  }

  private SpotSettlementService service() {
    return new SpotSettlementService(
        new WalletService(walletBalanceRepository, assetLedgerEntryRepository),
        spotPositionService);
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
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("10000.00000000"));
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
