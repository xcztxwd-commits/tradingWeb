package com.fxplatform.account.service;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.dto.AssetConversionRequest;
import com.fxplatform.account.dto.AssetConversionResponse;
import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.wallet.enums.AssetLedgerEntryType;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.AssetConversionService;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

  @Autowired
  public AccountService(
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      AccountSnapshotService accountSnapshotService,
      WalletService walletService,
      AssetConversionService assetConversionService
  ) {
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.accountSnapshotService = accountSnapshotService;
    this.walletService = walletService;
    this.assetConversionService = assetConversionService;
  }

  public AccountService(
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      AccountSnapshotService accountSnapshotService,
      WalletService walletService
  ) {
    this(accountRepository, ledgerService, accountSnapshotService, walletService, null);
  }

  public AccountService(
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      AccountSnapshotService accountSnapshotService
  ) {
    this(accountRepository, ledgerService, accountSnapshotService, null, null);
  }

  @Value("${trading.default-demo-balance}")
  private BigDecimal defaultDemoBalance;

  @Value("${trading.default-account-currency}")
  private String defaultCurrency;

  @Value("${trading.default-leverage}")
  private Integer defaultLeverage;

  @Transactional
  public TradingAccountEntity createDemoAccount(UUID userId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setUserId(userId);
    account.setBaseCurrency(defaultCurrency);
    account.setLeverage(defaultLeverage);
    account.setBalance(defaultDemoBalance);
    account.setEquity(defaultDemoBalance);
    account.setFreeMargin(defaultDemoBalance);
    accountRepository.save(account);

    // 所有资金初始化都必须落 Ledger，避免出现无法审计的余额变化。
    ledgerService.recordDemoDeposit(account, defaultDemoBalance, "Initial DEMO balance");
    if (walletService != null) {
      walletService.creditAvailableWithEntryType(
          account.getId(),
          WalletType.FX_MARGIN,
          account.getBaseCurrency(),
          defaultDemoBalance,
          "DEMO_ACCOUNT",
          account.getId(),
          "Initial DEMO wallet balance",
          AssetLedgerEntryType.CREDIT_AVAILABLE.code());
    }
    return account;
  }

  public List<AccountResponse> accountsForUser(UUID userId) {
    return accountRepository.findByUserId(userId).stream().map(this::toResponse).toList();
  }

  public AccountResponse summary(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .map(this::toResponse)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
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
