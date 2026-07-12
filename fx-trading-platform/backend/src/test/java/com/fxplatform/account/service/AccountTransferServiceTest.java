package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.AccountRiskProjection;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedAccountRisk;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.TradingTransactionExecutor;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountTransferServiceTest {

  @Mock TradingAccountRepository accountRepository;
  @Mock WalletService walletService;
  @Mock LedgerService ledgerService;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService;
  @Mock PositionRepository positionRepository;
  @Mock OrderRepository orderRepository;
  @Mock TradingTransactionExecutor transactionExecutor;

  private UUID accountId;
  private UUID userId;
  private TradingAccountEntity account;
  private WalletBalanceEntity spot;
  private AccountTransferService service;

  @BeforeEach
  void setUp() {
    accountId = UUID.randomUUID();
    userId = UUID.randomUUID();
    account = demoAccount(accountId, "50000.00000000", "50000.00000000");
    account.setUserId(userId);
    spot = spotWallet(accountId, "50000.00000000", "50000.00000000", "0.00000000");
    service = new AccountTransferService(
        accountRepository,
        walletService,
        ledgerService,
        demoExecutionGuard,
        positionRepository,
        orderRepository,
        perpetualAccountRiskSnapshotService,
        transactionExecutor);
    lenient().when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(walletService.lockBalancesInOrder(accountId, List.of("USDT")))
        .thenReturn(List.of(spot));
    lenient().when(ledgerService.findTransfer(eq(accountId), any(UUID.class)))
        .thenReturn(Optional.empty());
    lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    PreparedAccountRisk prepared = new PreparedAccountRisk(accountId, Map.of(), List.of());
    lenient().when(perpetualAccountRiskSnapshotService.prepare(accountId, Map.of()))
        .thenReturn(prepared);
    lenient().when(perpetualAccountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), eq(prepared)))
        .thenAnswer(invocation -> projection(
            account.getEquity().toPlainString(),
            account.getUsedMargin().toPlainString(),
            account.getFreeMargin().toPlainString()));
    lenient().doAnswer(invocation -> {
      AccountRiskProjection risk = invocation.getArgument(2);
      account.setEquity(risk.displayEquity());
      account.setUsedMargin(risk.usedMargin());
      account.setFreeMargin(risk.crossAvailable());
      return null;
    }).when(perpetualAccountRiskSnapshotService)
        .applyRevaluation(eq(account), anyList(), any(AccountRiskProjection.class));
    lenient().when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
      Supplier<?> mutation = invocation.getArgument(0);
      return mutation.get();
    });
  }

  @Test
  void spotToPerpUsesOnlyAvailableAndWritesOppositePairedLedgers() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("1250.00000000");
    when(walletService.debitAvailableWithEntryType(
        accountId, WalletType.SPOT, "USDT", amount, "TRANSFER", requestId,
        "Spot to perpetual transfer", "TRANSFER_OUT"))
        .thenAnswer(invocation -> {
          spot.setAvailable(new BigDecimal("48750.00000000"));
          spot.setTotal(new BigDecimal("48750.00000000"));
          return spot;
        });

    var response = service.transfer(userId, accountId, Direction.SPOT_TO_PERP, amount, requestId);

    assertThat(response.transferId()).isEqualTo(requestId);
    assertThat(response.direction()).isEqualTo(Direction.SPOT_TO_PERP);
    assertThat(response.amount()).isEqualByComparingTo(amount);
    assertThat(response.spotAvailable()).isEqualByComparingTo("48750.00000000");
    assertThat(response.perpBalance()).isEqualByComparingTo("51250.00000000");
    assertThat(response.perpFreeMargin()).isEqualByComparingTo("51250.00000000");
    assertThat(response.replayed()).isFalse();
    assertThat(account.getEquity()).isEqualByComparingTo("51250.00000000");
    verify(ledgerService).recordTransfer(
        account, LedgerEntryType.TRANSFER_IN, amount, requestId, "Spot to perpetual transfer");
    verify(demoExecutionGuard, times(2)).requireDemoAccount(account);
  }

  @Test
  void perpToSpotUsesOnlyFreeMarginAndConservesTheTwoBalances() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("3000.00000000");
    account.setUsedMargin(new BigDecimal("10000.00000000"));
    account.setFreeMargin(new BigDecimal("40000.00000000"));
    when(walletService.creditAvailableWithEntryType(
        accountId, WalletType.SPOT, "USDT", amount, "TRANSFER", requestId,
        "Perpetual to Spot transfer", "TRANSFER_IN"))
        .thenAnswer(invocation -> {
          spot.setAvailable(new BigDecimal("53000.00000000"));
          spot.setTotal(new BigDecimal("53000.00000000"));
          return spot;
        });

    var response = service.transfer(userId, accountId, Direction.PERP_TO_SPOT, amount, requestId);

    assertThat(response.spotAvailable().add(response.perpBalance()))
        .isEqualByComparingTo("100000.00000000");
    assertThat(response.perpBalance()).isEqualByComparingTo("47000.00000000");
    assertThat(response.perpFreeMargin()).isEqualByComparingTo("37000.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("10000.00000000");
    verify(ledgerService).recordTransfer(
        account, LedgerEntryType.TRANSFER_OUT, amount.negate(), requestId,
        "Perpetual to Spot transfer");
  }

  @Test
  void perpToSpotRejectsAgainstFreshCrossLossInsteadOfPersistedFreeMargin() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("3000.00000000");
    account.setFreeMargin(new BigDecimal("40000.00000000"));
    org.mockito.Mockito.lenient().when(perpetualAccountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), any()))
        .thenReturn(projection("47000.00000000", "10000.00000000", "1000.00000000"));
    assertThatThrownBy(() -> service.transfer(
        userId, accountId, Direction.PERP_TO_SPOT, amount, requestId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_AMOUNT_UNAVAILABLE"));

    verify(walletService, never()).creditAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
    verify(ledgerService, never()).recordTransfer(any(), any(), any(), any(), any());
  }

  @Test
  void orphanInternalIsolatedHoldRejectsTransferBeforeAnyMutation() {
    UUID requestId = UUID.randomUUID();
    when(perpetualAccountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), any()))
        .thenThrow(new BusinessException(
            ErrorCode.ORDER_HOLD_INVALID,
            "orphan internal hold"));

    assertThatThrownBy(() -> service.transfer(
        userId, accountId, Direction.PERP_TO_SPOT, BigDecimal.ONE, requestId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.ORDER_HOLD_INVALID));

    verify(accountRepository, never()).save(any(TradingAccountEntity.class));
    verify(positionRepository, never()).save(any(com.fxplatform.trading.entity.PositionEntity.class));
    verify(walletService, never()).creditAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
    verify(walletService, never()).debitAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
    verify(ledgerService, never()).recordTransfer(any(), any(), any(), any(), any());
  }

  @Test
  void perpToSpotAllowsFreshCrossProfitInsteadOfPersistedFreeMargin() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("500.00000000");
    account.setFreeMargin(new BigDecimal("100.00000000"));
    org.mockito.Mockito.lenient().when(perpetualAccountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), any()))
        .thenReturn(projection("50500.00000000", "0.00000000", "1000.00000000"));
    when(walletService.creditAvailableWithEntryType(
        accountId, WalletType.SPOT, "USDT", amount, "TRANSFER", requestId,
        "Perpetual to Spot transfer", "TRANSFER_IN"))
        .thenAnswer(invocation -> {
          spot.setAvailable(new BigDecimal("50500.00000000"));
          spot.setTotal(new BigDecimal("50500.00000000"));
          return spot;
        });

    var response = service.transfer(
        userId, accountId, Direction.PERP_TO_SPOT, amount, requestId);

    assertThat(response.perpBalance()).isEqualByComparingTo("49500.00000000");
    assertThat(response.perpFreeMargin()).isEqualByComparingTo("500.00000000");
  }

  @Test
  void spotToPerpAddsFundsOnTopOfFreshCrossAvailable() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("500.00000000");
    account.setFreeMargin(new BigDecimal("100.00000000"));
    lenient().when(perpetualAccountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), any()))
        .thenReturn(projection("50000.00000000", "10000.00000000", "1000.00000000"));
    when(walletService.debitAvailableWithEntryType(
        accountId, WalletType.SPOT, "USDT", amount, "TRANSFER", requestId,
        "Spot to perpetual transfer", "TRANSFER_OUT"))
        .thenAnswer(invocation -> {
          spot.setAvailable(new BigDecimal("49500.00000000"));
          spot.setTotal(new BigDecimal("49500.00000000"));
          return spot;
        });

    var response = service.transfer(
        userId, accountId, Direction.SPOT_TO_PERP, amount, requestId);

    assertThat(response.perpBalance()).isEqualByComparingTo("50500.00000000");
    assertThat(response.perpFreeMargin()).isEqualByComparingTo("1500.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("10000.00000000");
  }

  @Test
  void retriesFingerprintStaleWithFreshPreparationAndOneMutation() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("10.00000000");
    PreparedAccountRisk first = mock(PreparedAccountRisk.class);
    PreparedAccountRisk second = mock(PreparedAccountRisk.class);
    when(perpetualAccountRiskSnapshotService.prepare(accountId, Map.of()))
        .thenReturn(first, second);
    when(perpetualAccountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), eq(first)))
        .thenThrow(new BusinessException("MARKET_DATA_STALE", "fingerprint changed"));
    when(perpetualAccountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), eq(second)))
        .thenReturn(projection("50000.00000000", "0.00000000", "50000.00000000"));
    when(walletService.creditAvailableWithEntryType(
        accountId, WalletType.SPOT, "USDT", amount, "TRANSFER", requestId,
        "Perpetual to Spot transfer", "TRANSFER_IN"))
        .thenReturn(spot);

    var response = service.transfer(
        userId, accountId, Direction.PERP_TO_SPOT, amount, requestId);

    assertThat(response.replayed()).isFalse();
    verify(perpetualAccountRiskSnapshotService, times(2)).prepare(accountId, Map.of());
    verify(transactionExecutor, times(2)).execute(any());
    verify(accountRepository).save(account);
    verify(ledgerService).recordTransfer(
        account, LedgerEntryType.TRANSFER_OUT, amount.negate(), requestId,
        "Perpetual to Spot transfer");
  }

  @Test
  void preparesFreshRiskBeforeOpeningTheMutationTransaction() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("1.00000000");
    when(walletService.creditAvailableWithEntryType(
        accountId, WalletType.SPOT, "USDT", amount, "TRANSFER", requestId,
        "Perpetual to Spot transfer", "TRANSFER_IN"))
        .thenReturn(spot);

    service.transfer(userId, accountId, Direction.PERP_TO_SPOT, amount, requestId);

    InOrder order = inOrder(
        perpetualAccountRiskSnapshotService,
        transactionExecutor,
        accountRepository,
        walletService,
        positionRepository,
        orderRepository);
    order.verify(perpetualAccountRiskSnapshotService).prepare(accountId, Map.of());
    order.verify(transactionExecutor).execute(any());
    order.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    order.verify(walletService).lockBalancesInOrder(accountId, List.of("USDT"));
    order.verify(positionRepository).findOpenLinearPerpByAccountIdForUpdate(accountId);
    order.verify(orderRepository).findActiveLinearPerpByAccountIdForUpdate(accountId);
  }

  @Test
  void rejectsSpotLockedFundsAndPerpUsedMarginWithoutAnyLedgerMutation() {
    spot.setTotal(new BigDecimal("1000.00000000"));
    spot.setAvailable(new BigDecimal("100.00000000"));
    spot.setLocked(new BigDecimal("900.00000000"));

    assertThatThrownBy(() -> service.transfer(
        userId, accountId, Direction.SPOT_TO_PERP, new BigDecimal("101.00000000"), UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_AMOUNT_UNAVAILABLE"));

    verify(walletService, never()).debitAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
    verify(ledgerService, never()).recordTransfer(any(), any(), any(), any(), any());
  }

  @Test
  void rejectsPerpTransferAboveFreeMarginEvenWhenBalanceIsLarger() {
    account.setBalance(new BigDecimal("50000.00000000"));
    account.setEquity(new BigDecimal("50000.00000000"));
    account.setUsedMargin(new BigDecimal("49000.00000000"));
    account.setFreeMargin(new BigDecimal("1000.00000000"));

    assertThatThrownBy(() -> service.transfer(
        userId, accountId, Direction.PERP_TO_SPOT, new BigDecimal("1000.00000001"), UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_AMOUNT_UNAVAILABLE"));

    verify(walletService, never()).creditAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
    verify(ledgerService, never()).recordTransfer(any(), any(), any(), any(), any());
  }

  @Test
  void sameRequestAndSemanticsReplaysWhileConflictingSemanticsFailClosed() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("25.00000000");
    when(ledgerService.findTransfer(accountId, requestId)).thenReturn(Optional.of(
        new LedgerService.TransferRecord(Direction.SPOT_TO_PERP, amount,
            Instant.parse("2026-07-12T00:00:00Z"))));

    var replay = service.transfer(userId, accountId, Direction.SPOT_TO_PERP, amount, requestId);

    assertThat(replay.replayed()).isTrue();
    verify(walletService, never()).debitAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
    verify(ledgerService, never()).recordTransfer(any(), any(), any(), any(), any());
    verify(perpetualAccountRiskSnapshotService, never()).prepare(any(), any());

    assertThatThrownBy(() -> service.transfer(userId, accountId, Direction.PERP_TO_SPOT, amount, requestId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_REQUEST_CONFLICT"));
  }

  @Test
  void serializesAccountThenSpotWalletThenReadsLedger() {
    UUID requestId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("1.00000000");
    when(walletService.debitAvailableWithEntryType(
        eq(accountId), eq(WalletType.SPOT), eq("USDT"), eq(amount), eq("TRANSFER"), eq(requestId),
        any(), eq("TRANSFER_OUT"))).thenReturn(spot);

    service.transfer(userId, accountId, Direction.SPOT_TO_PERP, amount, requestId);

    InOrder locks = inOrder(accountRepository, walletService, ledgerService);
    locks.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    locks.verify(walletService).lockBalancesInOrder(accountId, List.of("USDT"));
    locks.verify(ledgerService).findTransfer(accountId, requestId);
  }

  @Test
  void rejectsNonPositiveAmountBeforeTakingMutationLocks() {
    assertThatThrownBy(() -> service.transfer(
        userId, accountId, Direction.SPOT_TO_PERP, BigDecimal.ZERO, UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_AMOUNT_INVALID"));

    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
  }

  @Test
  void rejectsAnAccountNotOwnedByThePrincipalBeforeWalletOrLedgerMutation() {
    UUID attacker = UUID.randomUUID();
    when(accountRepository.findByIdAndUserId(accountId, attacker)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.transfer(
        attacker, accountId, Direction.SPOT_TO_PERP, BigDecimal.ONE, UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(walletService, never()).lockBalancesInOrder(any(), anyList());
    verify(ledgerService, never()).findTransfer(any(), any());
  }

  @Test
  void transferHistoryIsOwnerBoundAndAggregatesOneResponsePerTransferId() {
    UUID transferId = UUID.randomUUID();
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(ledgerService.transferRecords(accountId)).thenReturn(List.of(
        new LedgerService.TransferHistoryRecord(
            transferId,
            Direction.PERP_TO_SPOT,
            new BigDecimal("75.00000000"),
            new BigDecimal("50075.00000000"),
            new BigDecimal("49925.00000000"),
            Instant.parse("2026-07-12T02:00:00Z"))));

    var history = service.history(userId, accountId);

    assertThat(history).hasSize(1);
    assertThat(history.getFirst().transferId()).isEqualTo(transferId);
    assertThat(history.getFirst().direction()).isEqualTo(Direction.PERP_TO_SPOT);
    assertThat(history.getFirst().spotAvailable()).isEqualByComparingTo("50075.00000000");
    assertThat(history.getFirst().perpBalance()).isEqualByComparingTo("49925.00000000");
  }

  private static TradingAccountEntity demoAccount(UUID id, String balance, String freeMargin) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(id);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal(balance));
    account.setEquity(new BigDecimal(balance));
    account.setUsedMargin(BigDecimal.ZERO.setScale(8));
    account.setFreeMargin(new BigDecimal(freeMargin));
    return account;
  }

  private static AccountRiskProjection projection(String equity, String used, String free) {
    return new AccountRiskProjection(
        new BigDecimal(equity),
        new BigDecimal(used),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal(equity),
        new BigDecimal(free),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        false,
        List.of());
  }

  private static WalletBalanceEntity spotWallet(UUID accountId, String total, String available, String locked) {
    WalletBalanceEntity wallet = new WalletBalanceEntity();
    wallet.setId(UUID.randomUUID());
    wallet.setAccountId(accountId);
    wallet.setWalletType(WalletType.SPOT.code());
    wallet.setAsset("USDT");
    wallet.setTotal(new BigDecimal(total));
    wallet.setAvailable(new BigDecimal(available));
    wallet.setLocked(new BigDecimal(locked));
    return wallet;
  }
}
