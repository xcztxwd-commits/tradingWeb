package com.fxplatform.ledger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.ledger.dto.LedgerEntryResponse;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  @Test
  void entriesRejectsAccountThatDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    assertThatThrownBy(() -> service.entries(userId, accountId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Account not found");

    verify(ledgerEntryRepository, never()).findByAccountIdOrderByCreatedAtDesc(accountId);
  }

  @Test
  void entriesReadsLedgerForOwnedAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    service.entries(userId, accountId);

    verify(ledgerEntryRepository).findByAccountIdOrderByCreatedAtDesc(accountId);
  }

  @Test
  void visibleEntriesIncludesSpotAssetLedgerEntriesForOwnedAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    LedgerEntryEntity demoDeposit = new LedgerEntryEntity();
    demoDeposit.setId(UUID.randomUUID());
    demoDeposit.setAccountId(accountId);
    demoDeposit.setEntryType(LedgerEntryType.DEMO_DEPOSIT);
    demoDeposit.setAmount(new BigDecimal("10000.00000000"));
    demoDeposit.setBalanceAfter(new BigDecimal("10000.00000000"));
    demoDeposit.setCurrency("USD");
    demoDeposit.setCreatedAt(Instant.parse("2026-06-16T00:00:00Z"));
    AssetLedgerEntryEntity spotEntry = new AssetLedgerEntryEntity();
    spotEntry.setId(UUID.randomUUID());
    spotEntry.setAccountId(accountId);
    spotEntry.setAsset("USDT");
    spotEntry.setAmount(new BigDecimal("-5000.00000000"));
    spotEntry.setBalanceAfter(new BigDecimal("5000.00000000"));
    spotEntry.setEntryType("SPOT_BUY_QUOTE_OUT");
    spotEntry.setReferenceType("ORDER");
    spotEntry.setReferenceId(orderId);
    spotEntry.setDescription("Spot buy quote spent");
    spotEntry.setCreatedAt(Instant.parse("2026-06-16T00:01:00Z"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(demoDeposit));
    when(assetLedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(spotEntry));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository, assetLedgerEntryRepository);

    List<LedgerEntryResponse> entries = service.visibleEntries(userId, accountId);

    assertThat(entries).extracting(LedgerEntryResponse::entryType)
        .containsExactly("SPOT_BUY_QUOTE_OUT", "DEMO_DEPOSIT");
    assertThat(entries.getFirst().currency()).isEqualTo("USDT");
    assertThat(entries.getFirst().referenceId()).isEqualTo(orderId);
  }

  @Test
  void recordFundingFeeWritesSignedFundingLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("9995.00000000"));

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    LedgerEntryEntity entry = service.recordFundingFee(
        account,
        new BigDecimal("-5.00000000"),
        positionId,
        "Perpetual funding fee");

    assertThat(entry.getAccountId()).isEqualTo(accountId);
    assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.FUNDING_FEE);
    assertThat(entry.getAmount()).isEqualByComparingTo("-5.00000000");
    assertThat(entry.getBalanceAfter()).isEqualByComparingTo("9995.00000000");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getReferenceType()).isEqualTo("POSITION");
    assertThat(entry.getReferenceId()).isEqualTo(positionId);
  }

  @Test
  void transferPairIsReconstructedFromOppositeCashAndSpotLedgerEntries() {
    UUID accountId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    LedgerEntryEntity cash = new LedgerEntryEntity();
    cash.setAccountId(accountId);
    cash.setEntryType(LedgerEntryType.TRANSFER_IN);
    cash.setOperationType("TRANSFER_IN");
    cash.setAmount(new BigDecimal("250.00000000"));
    cash.setCreatedAt(Instant.parse("2026-07-12T00:00:00Z"));
    AssetLedgerEntryEntity spot = new AssetLedgerEntryEntity();
    spot.setAccountId(accountId);
    spot.setOperationType("TRANSFER_OUT");
    spot.setAmount(new BigDecimal("-250.00000000"));
    when(ledgerEntryRepository.findByReference(accountId, "TRANSFER", transferId))
        .thenReturn(List.of(cash));
    when(assetLedgerEntryRepository.findByReference(accountId, "TRANSFER", transferId))
        .thenReturn(List.of(spot));

    var record = new LedgerService(
        ledgerEntryRepository, accountRepository, assetLedgerEntryRepository)
        .findTransfer(accountId, transferId)
        .orElseThrow();

    assertThat(record.direction()).isEqualTo(Direction.SPOT_TO_PERP);
    assertThat(record.amount()).isEqualByComparingTo("250.00000000");
  }

  @Test
  void incompleteOrAmountMismatchedTransferPairFailsClosed() {
    UUID accountId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    LedgerEntryEntity cash = new LedgerEntryEntity();
    cash.setOperationType("TRANSFER_OUT");
    cash.setAmount(new BigDecimal("20.00000000"));
    AssetLedgerEntryEntity spot = new AssetLedgerEntryEntity();
    spot.setOperationType("TRANSFER_IN");
    spot.setAmount(new BigDecimal("19.00000000"));
    when(ledgerEntryRepository.findByReference(accountId, "TRANSFER", transferId))
        .thenReturn(List.of(cash));
    when(assetLedgerEntryRepository.findByReference(accountId, "TRANSFER", transferId))
        .thenReturn(List.of(spot));
    LedgerService service = new LedgerService(
        ledgerEntryRepository, accountRepository, assetLedgerEntryRepository);

    assertThatThrownBy(() -> service.findTransfer(accountId, transferId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_REQUEST_CONFLICT"));

    when(assetLedgerEntryRepository.findByReference(accountId, "TRANSFER", transferId))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.findTransfer(accountId, transferId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_REQUEST_CONFLICT"));
  }

  @Test
  void demoInitResetAndTransferUseStableV47LedgerEntryTypes() {
    UUID accountId = UUID.randomUUID();
    UUID resetId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000.00000000"));
    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    LedgerEntryEntity init = service.recordDemoInit(account, new BigDecimal("50000.00000000"));
    LedgerEntryEntity reset = service.recordDemoReset(account, new BigDecimal("1000.00000000"), resetId);
    LedgerEntryEntity transfer = service.recordTransfer(
        account, LedgerEntryType.TRANSFER_OUT, new BigDecimal("-50.00000000"), transferId,
        "Perpetual to Spot transfer");

    assertThat(init.getEntryType()).isEqualTo(LedgerEntryType.DEMO_INIT);
    assertThat(init.getReferenceType()).isEqualTo("DEMO_ACCOUNT");
    assertThat(reset.getEntryType()).isEqualTo(LedgerEntryType.DEMO_RESET);
    assertThat(reset.getReferenceType()).isEqualTo("DEMO_RESET");
    assertThat(transfer.getEntryType()).isEqualTo(LedgerEntryType.TRANSFER_OUT);
    assertThat(transfer.getReferenceType()).isEqualTo("TRANSFER");
    assertThat(LedgerEntryType.valueOf("DEMO_INIT")).isEqualTo(LedgerEntryType.DEMO_INIT);
  }

  @Test
  void recordFundingFeeSettlementIsIdempotentForSameBusinessOperation() {
    UUID accountId = UUID.randomUUID();
    UUID settlementId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("9995.00000000"));
    List<LedgerEntryEntity> savedEntries = new ArrayList<>();

    when(ledgerEntryRepository.findByBusinessOperation(accountId, "FUNDING_SETTLEMENT", settlementId, "FUNDING_FEE"))
        .thenAnswer(invocation -> savedEntries.stream().findFirst().orElse(null));
    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> {
          LedgerEntryEntity entry = invocation.getArgument(0);
          savedEntries.add(entry);
          return entry;
        });

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    service.recordFundingFeeSettlement(
        account,
        new BigDecimal("-5.00000000"),
        settlementId,
        "Perpetual funding fee");
    service.recordFundingFeeSettlement(
        account,
        new BigDecimal("-5.00000000"),
        settlementId,
        "Perpetual funding fee");

    verify(ledgerEntryRepository, org.mockito.Mockito.times(1)).save(any(LedgerEntryEntity.class));
  }

  @Test
  void fundingBankruptcyShortfallUsesItsOwnIdempotentSettlementOperation() {
    UUID accountId = UUID.randomUUID();
    UUID settlementId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USDT");
    account.setBalance(BigDecimal.ZERO.setScale(8));
    List<LedgerEntryEntity> savedEntries = new ArrayList<>();

    when(ledgerEntryRepository.findByBusinessOperation(
        accountId, "FUNDING_SETTLEMENT", settlementId, "BANKRUPTCY_SHORTFALL"))
        .thenAnswer(invocation -> savedEntries.stream().findFirst().orElse(null));
    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> {
          LedgerEntryEntity entry = invocation.getArgument(0);
          savedEntries.add(entry);
          return entry;
        });

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);
    service.recordFundingBankruptcyShortfall(
        account, new BigDecimal("1.00000000"), settlementId, "Funding bankruptcy shortfall");
    service.recordFundingBankruptcyShortfall(
        account, new BigDecimal("1.00000000"), settlementId, "Funding bankruptcy shortfall");

    assertThat(savedEntries).singleElement().satisfies(entry -> {
      assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.BANKRUPTCY_SHORTFALL);
      assertThat(entry.getAmount()).isEqualByComparingTo("1.00000000");
      assertThat(entry.getReferenceType()).isEqualTo("FUNDING_SETTLEMENT");
      assertThat(entry.getReferenceId()).isEqualTo(settlementId);
    });
  }

  @Test
  void orderHoldAndReleaseDeltasAreRecordedForEachPendingOrderLifecycleStep() {
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("10000.00000000"));
    List<LedgerEntryEntity> savedEntries = new ArrayList<>();

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> {
          LedgerEntryEntity entry = invocation.getArgument(0);
          savedEntries.add(entry);
          return entry;
        });

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    service.recordOrderHold(account, new BigDecimal("20.00000000"), orderId, "Pending order margin reserved");
    service.recordOrderHold(account, new BigDecimal("20.00000000"), orderId, "Pending order margin increased");
    service.recordOrderRelease(account, new BigDecimal("30.00000000"), orderId, "Pending order margin decreased");
    service.recordOrderRelease(account, new BigDecimal("10.00000000"), orderId, "Pending order canceled");

    assertThat(savedEntries)
        .extracting(LedgerEntryEntity::getEntryType)
        .containsExactly(
            LedgerEntryType.ORDER_HOLD,
            LedgerEntryType.ORDER_HOLD,
            LedgerEntryType.ORDER_RELEASE,
            LedgerEntryType.ORDER_RELEASE);
    assertThat(savedEntries)
        .extracting(LedgerEntryEntity::getAmount)
        .containsExactly(
            new BigDecimal("20.00000000"),
            new BigDecimal("20.00000000"),
            new BigDecimal("30.00000000"),
            new BigDecimal("10.00000000"));
  }

  @Test
  void recordFinancingWritesSignedFxFinancingLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("9990.00000000"));

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    LedgerEntryEntity entry = service.recordFinancing(
        account,
        new BigDecimal("-10.00000000"),
        positionId,
        "FX rollover financing");

    assertThat(entry.getAccountId()).isEqualTo(accountId);
    assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.FINANCING);
    assertThat(entry.getAmount()).isEqualByComparingTo("-10.00000000");
    assertThat(entry.getBalanceAfter()).isEqualByComparingTo("9990.00000000");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getReferenceType()).isEqualTo("POSITION");
    assertThat(entry.getReferenceId()).isEqualTo(positionId);
    assertThat(entry.getDescription()).isEqualTo("FX rollover financing");
  }

  @Test
  void recordForcedCloseWritesZeroAmountForcedCloseEntry() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("950.00000000"));

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    LedgerEntryEntity entry = service.recordForcedClose(
        account,
        positionId,
        "FX_MARGIN_STOP_OUT");

    assertThat(entry.getAccountId()).isEqualTo(accountId);
    assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.FORCED_CLOSE);
    assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(entry.getBalanceAfter()).isEqualByComparingTo("950.00000000");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getReferenceType()).isEqualTo("POSITION");
    assertThat(entry.getReferenceId()).isEqualTo(positionId);
    assertThat(entry.getDescription()).isEqualTo("FX_MARGIN_STOP_OUT");
  }
}
