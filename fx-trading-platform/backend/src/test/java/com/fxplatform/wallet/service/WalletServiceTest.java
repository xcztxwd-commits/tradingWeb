package com.fxplatform.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  @BeforeEach
  void rowLockLookupUsesTheSameFixtureBalance() {
    org.mockito.Mockito.lenient().when(walletBalanceRepository
            .findByAccountIdAndWalletTypeAndAssetForUpdate(
                any(UUID.class), any(String.class), any(String.class)))
        .thenAnswer(invocation -> walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(
            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
  }

  @Test
  void walletMutationLocksTheBalanceRowBeforeDebit() {
    UUID accountId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(
        accountId, "USDT", "100.00000000", "100.00000000", "0.00000000");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "USDT")).thenReturn(Optional.of(balance));
    org.mockito.Mockito.clearInvocations(walletBalanceRepository);

    service().debitAvailable(
        accountId,
        "USDT",
        new BigDecimal("10.00000000"),
        "TEST",
        UUID.randomUUID(),
        "Serialized debit");

    verify(walletBalanceRepository).findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "USDT");
  }

  @Test
  void requiredSpotWalletsAreCreatedAndLockedInAssetOrder() {
    UUID accountId = UUID.randomUUID();
    WalletBalanceEntity usdt = balance(
        accountId, "USDT", "100.00000000", "100.00000000", "0.00000000");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "BTC")).thenReturn(Optional.empty());
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "USDT")).thenReturn(Optional.of(usdt));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service().lockBalancesInOrder(accountId, List.of("USDT", "btc", "BTC"));

    org.mockito.InOrder assetOrder = org.mockito.Mockito.inOrder(walletBalanceRepository);
    assetOrder.verify(walletBalanceRepository).findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "BTC");
    assetOrder.verify(walletBalanceRepository).save(any(WalletBalanceEntity.class));
    assetOrder.verify(walletBalanceRepository).findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "USDT");
  }

  @Test
  void heterogeneousWalletKeysUseTheSameWalletTypeThenAssetOrderAsReset() {
    UUID accountId = UUID.randomUUID();
    WalletBalanceEntity fxUsd = balance(
        accountId, WalletType.FX_MARGIN, "USD", "0", "0", "0");
    WalletBalanceEntity spotUsdt = balance(
        accountId, WalletType.SPOT, "USDT", "50000", "50000", "0");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.FX_MARGIN.code(), "USD")).thenReturn(Optional.of(fxUsd));
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "USDT")).thenReturn(Optional.of(spotUsdt));

    service().lockWalletsInOrder(accountId, List.of(
        new WalletService.WalletKey(WalletType.SPOT, "USDT"),
        new WalletService.WalletKey(WalletType.FX_MARGIN, "USD")));

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(walletBalanceRepository);
    order.verify(walletBalanceRepository).findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.FX_MARGIN.code(), "USD");
    order.verify(walletBalanceRepository).findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "USDT");
  }

  @Test
  void walletTypeSeparatesSameAssetBalances() {
    UUID accountId = UUID.randomUUID();
    UUID referenceId = UUID.randomUUID();
    WalletBalanceEntity spot = balance(accountId, WalletType.SPOT, "USDT", "100.00000000", "100.00000000", "0");
    WalletBalanceEntity perp = balance(accountId, WalletType.USDT_PERP, "USDT", "50.00000000", "50.00000000", "0");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(accountId, WalletType.USDT_PERP.code(), "USDT"))
        .thenReturn(Optional.of(perp));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    WalletService service = service();

    service.debitAvailable(
        accountId,
        WalletType.USDT_PERP,
        "USDT",
        new BigDecimal("10.00000000"),
        "TEST",
        referenceId,
        "Perp wallet debit");

    assertThat(spot.getAvailable()).isEqualByComparingTo("100.00000000");
    assertThat(perp.getAvailable()).isEqualByComparingTo("40.00000000");
  }

  @Test
  void creditDebitLockAndReleaseUpdateWalletBalanceAndWriteAssetLedgerEntries() {
    UUID accountId = UUID.randomUUID();
    UUID referenceId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(accountId, "USDT", "0", "0", "0");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(accountId, WalletType.SPOT.code(), "USDT"))
        .thenReturn(Optional.of(balance));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    WalletService service = service();

    service.creditAvailable(accountId, "usdt", new BigDecimal("100.00000000"), "DEMO_ACCOUNT", referenceId, "Initial wallet balance");
    service.debitAvailable(accountId, "USDT", new BigDecimal("20.00000000"), "TEST", referenceId, "Debit available");
    service.lockAvailable(accountId, "USDT", new BigDecimal("30.00000000"), "ORDER", referenceId, "Lock available");
    service.releaseLocked(accountId, "USDT", new BigDecimal("10.00000000"), "ORDER", referenceId, "Release locked");

    assertThat(balance.getTotal()).isEqualByComparingTo("80.00000000");
    assertThat(balance.getAvailable()).isEqualByComparingTo("60.00000000");
    assertThat(balance.getLocked()).isEqualByComparingTo("20.00000000");

    ArgumentCaptor<AssetLedgerEntryEntity> ledgerCaptor = ArgumentCaptor.forClass(AssetLedgerEntryEntity.class);
    verify(assetLedgerEntryRepository, org.mockito.Mockito.times(4)).save(ledgerCaptor.capture());
    assertThat(ledgerCaptor.getAllValues())
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("CREDIT_AVAILABLE", "DEBIT_AVAILABLE", "LOCK_AVAILABLE", "RELEASE_LOCKED");
    assertThat(ledgerCaptor.getAllValues().get(0).getAmount()).isEqualByComparingTo("100.00000000");
    assertThat(ledgerCaptor.getAllValues().get(1).getAmount()).isEqualByComparingTo("-20.00000000");
    assertThat(ledgerCaptor.getAllValues().get(2).getAmount()).isEqualByComparingTo("-30.00000000");
    assertThat(ledgerCaptor.getAllValues().get(3).getAmount()).isEqualByComparingTo("10.00000000");
    assertThat(ledgerCaptor.getAllValues().getLast().getBalanceAfter()).isEqualByComparingTo("60.00000000");
  }

  @Test
  void creditAvailableIsIdempotentForSameBusinessOperation() {
    UUID accountId = UUID.randomUUID();
    UUID referenceId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(accountId, "USDT", "0", "0", "0");
    List<AssetLedgerEntryEntity> savedEntries = new ArrayList<>();
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(accountId, WalletType.SPOT.code(), "USDT"))
        .thenReturn(Optional.of(balance));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(assetLedgerEntryRepository.findByBusinessOperation(
        accountId,
        WalletType.SPOT.code(),
        "USDT",
        "DEMO_ACCOUNT",
        referenceId,
        "CREDIT_AVAILABLE"))
        .thenAnswer(invocation -> savedEntries.stream().findFirst().orElse(null));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> {
          AssetLedgerEntryEntity entry = invocation.getArgument(0);
          savedEntries.add(entry);
          return entry;
        });

    WalletService service = service();

    service.creditAvailableWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("100.00000000"),
        "DEMO_ACCOUNT",
        referenceId,
        "Initial wallet balance",
        "CREDIT_AVAILABLE");
    service.creditAvailableWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("100.00000000"),
        "DEMO_ACCOUNT",
        referenceId,
        "Initial wallet balance",
        "CREDIT_AVAILABLE");

    assertThat(balance.getTotal()).isEqualByComparingTo("100.00000000");
    assertThat(balance.getAvailable()).isEqualByComparingTo("100.00000000");
    verify(assetLedgerEntryRepository, org.mockito.Mockito.times(1)).save(any(AssetLedgerEntryEntity.class));
  }

  @Test
  void resetBalanceWritesTheExactDeltaAndRestoresAvailableTotalAndLocked() {
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(
        accountId, "USDT", "70000.00000000", "60000.00000000", "10000.00000000");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(
        accountId, WalletType.SPOT.code(), "USDT")).thenReturn(Optional.of(balance));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    WalletBalanceEntity reset = service().resetBalance(
        accountId,
        WalletType.SPOT,
        "USDT",
        new BigDecimal("50000.00000000"),
        requestId,
        "Reset Demo Spot balance");

    assertThat(reset.getTotal()).isEqualByComparingTo("50000.00000000");
    assertThat(reset.getAvailable()).isEqualByComparingTo("50000.00000000");
    assertThat(reset.getLocked()).isEqualByComparingTo("0.00000000");
    ArgumentCaptor<AssetLedgerEntryEntity> entry = ArgumentCaptor.forClass(AssetLedgerEntryEntity.class);
    verify(assetLedgerEntryRepository).save(entry.capture());
    assertThat(entry.getValue().getAmount()).isEqualByComparingTo("-20000.00000000");
    assertThat(entry.getValue().getBalanceAfter()).isEqualByComparingTo("50000.00000000");
    assertThat(entry.getValue().getEntryType()).isEqualTo("DEMO_RESET");
    assertThat(entry.getValue().getReferenceType()).isEqualTo("DEMO_RESET");
    assertThat(entry.getValue().getReferenceId()).isEqualTo(requestId);
  }

  @Test
  void resetBalanceIsIdempotentForTheSameRequestId() {
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(accountId, "BTC", "0.5", "0.5", "0");
    List<AssetLedgerEntryEntity> saved = new ArrayList<>();
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(
        accountId, WalletType.SPOT.code(), "BTC")).thenReturn(Optional.of(balance));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(assetLedgerEntryRepository.findByBusinessOperation(
        accountId, WalletType.SPOT.code(), "BTC", "DEMO_RESET", requestId, "DEMO_RESET"))
        .thenAnswer(invocation -> saved.stream().findFirst().orElse(null));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> {
          AssetLedgerEntryEntity entry = invocation.getArgument(0);
          saved.add(entry);
          return entry;
        });

    WalletService service = service();
    service.resetBalance(accountId, WalletType.SPOT, "BTC", BigDecimal.ZERO, requestId, "Reset asset");
    service.resetBalance(accountId, WalletType.SPOT, "BTC", BigDecimal.ZERO, requestId, "Reset asset");

    assertThat(balance.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(assetLedgerEntryRepository, org.mockito.Mockito.times(1)).save(any(AssetLedgerEntryEntity.class));
  }

  @Test
  void buyLimitWalletHoldLocksQuoteAndCancelRestoresAvailable() {
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(accountId, "USDT", "10000.00000000", "10000.00000000", "0");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(accountId, WalletType.SPOT.code(), "USDT"))
        .thenReturn(Optional.of(balance));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    WalletService service = service();

    service.lockAvailableWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("5000.00000000"),
        "ORDER",
        orderId,
        "Pending spot order wallet locked",
        "SPOT_ORDER_LOCK");

    assertThat(balance.getTotal()).isEqualByComparingTo("10000.00000000");
    assertThat(balance.getAvailable()).isEqualByComparingTo("5000.00000000");
    assertThat(balance.getLocked()).isEqualByComparingTo("5000.00000000");

    service.releaseLockedWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("5000.00000000"),
        "ORDER",
        orderId,
        "Pending spot order canceled",
        "SPOT_ORDER_RELEASE");

    assertThat(balance.getTotal()).isEqualByComparingTo("10000.00000000");
    assertThat(balance.getAvailable()).isEqualByComparingTo("10000.00000000");
    assertThat(balance.getLocked()).isEqualByComparingTo("0.00000000");
  }

  @Test
  void getOrCreateBalanceCreatesZeroBalanceWhenMissing() {
    UUID accountId = UUID.randomUUID();
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(accountId, WalletType.SPOT.code(), "USD"))
        .thenReturn(Optional.empty());
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    WalletBalanceEntity balance = service().getOrCreateBalance(accountId, " usd ");

    assertThat(balance.getAccountId()).isEqualTo(accountId);
    assertThat(balance.getAsset()).isEqualTo("USD");
    assertThat(balance.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(balance.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(balance.getLocked()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(walletBalanceRepository).save(balance);
  }

  @Test
  void debitAvailableRejectsInsufficientAvailableBalance() {
    UUID accountId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(accountId, "USD", "10.00000000", "5.00000000", "5.00000000");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(accountId, WalletType.SPOT.code(), "USD"))
        .thenReturn(Optional.of(balance));

    assertThatThrownBy(() -> service().debitAvailable(accountId, "USD", new BigDecimal("6.00000000"), "TEST", UUID.randomUUID(), "Too much"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Available balance is not enough");
  }

  @Test
  void lockAvailableRejectsInsufficientAvailableBalance() {
    UUID accountId = UUID.randomUUID();
    WalletBalanceEntity balance = balance(accountId, "BTC", "1.00000000", "0.10000000", "0.90000000");
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAsset(accountId, WalletType.SPOT.code(), "BTC"))
        .thenReturn(Optional.of(balance));

    assertThatThrownBy(() -> service().lockAvailable(accountId, "BTC", new BigDecimal("0.20000000"), "ORDER", UUID.randomUUID(), "Lock too much"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Available balance is not enough");
  }

  private WalletService service() {
    return new WalletService(walletBalanceRepository, assetLedgerEntryRepository);
  }

  private static WalletBalanceEntity balance(
      UUID accountId,
      String asset,
      String total,
      String available,
      String locked
  ) {
    return balance(accountId, WalletType.SPOT, asset, total, available, locked);
  }

  private static WalletBalanceEntity balance(
      UUID accountId,
      WalletType walletType,
      String asset,
      String total,
      String available,
      String locked
  ) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setId(UUID.randomUUID());
    balance.setAccountId(accountId);
    balance.setWalletType(walletType.code());
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(total));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(new BigDecimal(locked));
    return balance;
  }
}
