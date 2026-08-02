package com.fxplatform.account.service;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.dto.AccountTransferRequest;
import com.fxplatform.account.dto.AccountTransferResponse;
import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.dto.AssetConversionRequest;
import com.fxplatform.account.dto.AssetConversionResponse;
import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.dto.DemoResetResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.wallet.service.AssetConversionService;
import com.fxplatform.wallet.service.WalletService;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AccountService 是账户模块的业务服务。
 */
@Service
public class AccountService {

  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final AccountSnapshotService accountSnapshotService;
  private final WalletService walletService;
  private final AssetConversionService assetConversionService;
  private final DemoAccountLifecycleService demoAccountLifecycleService;
  private final AccountTransferService accountTransferService;

  @Autowired
  public AccountService(
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      AccountSnapshotService accountSnapshotService,
      WalletService walletService,
      AssetConversionService assetConversionService,
      DemoAccountLifecycleService demoAccountLifecycleService,
      AccountTransferService accountTransferService
  ) {
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.accountSnapshotService = accountSnapshotService;
    this.walletService = walletService;
    this.assetConversionService = assetConversionService;
    this.demoAccountLifecycleService = demoAccountLifecycleService;
    this.accountTransferService = accountTransferService;
  }

  public AccountService(
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      AccountSnapshotService accountSnapshotService,
      WalletService walletService,
      AssetConversionService assetConversionService
  ) {
    this(
        accountRepository,
        ledgerService,
        accountSnapshotService,
        walletService,
        assetConversionService,
        null,
        null);
  }

  public AccountService(
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      AccountSnapshotService accountSnapshotService,
      WalletService walletService
  ) {
    this(accountRepository, ledgerService, accountSnapshotService, walletService, null, null, null);
  }

  public AccountService(
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      AccountSnapshotService accountSnapshotService
  ) {
    this(accountRepository, ledgerService, accountSnapshotService, null, null, null, null);
  }

  public TradingAccountEntity createDemoAccount(UUID userId) {
    return getOrCreateDemoAccount(userId);
  }

  public TradingAccountEntity getOrCreateDemoAccount(UUID userId) {
    if (demoAccountLifecycleService == null) {
      throw new BusinessException("DEMO_ACCOUNT_LIFECYCLE_UNAVAILABLE", "Demo account lifecycle is unavailable");
    }
    return demoAccountLifecycleService.getOrCreateDemoAccount(userId);
  }

  public AccountTransferResponse transfer(
      UUID userId,
      UUID accountId,
      AccountTransferRequest request
  ) {
    if (accountTransferService == null) {
      throw new BusinessException("ACCOUNT_TRANSFER_UNAVAILABLE", "Account transfer is unavailable");
    }
    return accountTransferService.transfer(
        userId,
        accountId,
        request.direction(),
        request.amount(),
        request.requestId());
  }

  public DemoResetResponse resetDemo(UUID userId, UUID accountId, UUID requestId) {
    if (demoAccountLifecycleService == null) {
      throw new BusinessException("DEMO_ACCOUNT_LIFECYCLE_UNAVAILABLE", "Demo account lifecycle is unavailable");
    }
    return demoAccountLifecycleService.reset(userId, accountId, requestId);
  }

  public List<AccountTransferResponse> transferHistory(UUID userId, UUID accountId) {
    if (accountTransferService == null) {
      throw new BusinessException("ACCOUNT_TRANSFER_UNAVAILABLE", "Account transfer is unavailable");
    }
    return accountTransferService.history(userId, accountId);
  }

  public List<AccountResponse> accountsForUser(UUID userId) {
    return accountRepository.findByUserId(userId).stream().map(this::toResponse).toList();
  }

  public AccountResponse summary(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .map(this::toResponse)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

  public List<WalletBalanceResponse> walletBalances(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    if (walletService == null) {
      return List.of();
    }
    return walletService.balances(accountId).stream()
        .map(WalletBalanceResponse::from)
        .toList();
  }

  public List<AssetLedgerEntryResponse> assetLedger(
      UUID userId,
      UUID accountId,
      String walletType,
      String asset,
      String entryType,
      UUID referenceId,
      Instant from,
      Instant to
  ) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    if (walletService == null) {
      return List.of();
    }
    return walletService.assetLedgerEntries(accountId, walletType, asset, entryType, referenceId, from, to).stream()
        .map(AssetLedgerEntryResponse::from)
        .toList();
  }

  public AssetConversionResponse convertAsset(
      UUID userId,
      UUID accountId,
      AssetConversionRequest request
  ) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    if (assetConversionService == null) {
      throw new BusinessException("ASSET_CONVERSION_UNAVAILABLE", "Asset conversion is unavailable");
    }
    UUID conversionId = request.conversionId() == null ? UUID.randomUUID() : request.conversionId();
    return AssetConversionResponse.from(assetConversionService.convert(
        userId,
        accountId,
        WalletType.fromCode(request.fromWalletType()),
        request.fromAsset(),
        WalletType.fromCode(request.toWalletType()),
        request.toAsset(),
        request.amount(),
        conversionId));
  }

  public AccountResponse toResponse(TradingAccountEntity account) {
    AccountSnapshot snapshot = accountSnapshot(account);
    return new AccountResponse(
        account.getId(),
        account.getAccountType().name(),
        snapshot.baseCurrency(),
        account.getBalance(),
        snapshot.equity(),
        snapshot.usedMargin(),
        snapshot.freeMargin(),
        snapshot.marginLevel(),
        account.getLeverage(),
        account.getStatus().name(),
        snapshot.openFloatingPnl(),
        snapshot.maintenanceMargin(),
        snapshot.positionValue(),
        snapshot.marginAvailable(),
        snapshot.lastSnapshotAt(),
        snapshot.warning());
  }

  private AccountSnapshot accountSnapshot(TradingAccountEntity account) {
    if (account.getId() == null) {
      return new AccountSnapshot(
          null,
          account.getBalance(),
          BigDecimal.ZERO,
          account.getEquity(),
          account.getUsedMargin(),
          BigDecimal.ZERO,
          account.getFreeMargin(),
          account.getMarginLevel(),
          account.getBaseCurrency());
    }
    return accountSnapshotService.snapshot(account);
  }
}
