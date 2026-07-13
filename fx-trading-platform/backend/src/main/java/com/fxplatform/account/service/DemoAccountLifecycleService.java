package com.fxplatform.account.service;

import com.fxplatform.account.dto.DemoResetResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.wallet.service.WalletService;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoAccountLifecycleService {

  private static final int MONEY_SCALE = 8;
  private static final ApplicationEventPublisher NO_OP_EVENT_PUBLISHER = event -> {
  };
  private static final BigDecimal INITIAL_BALANCE =
      new BigDecimal("50000.00000000");
  private static final List<String> CANONICAL_SYMBOLS = List.of(
      "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
      "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");

  private final TradingAccountRepository accountRepository;
  private final WalletService walletService;
  private final LedgerService ledgerService;
  private final AccountSymbolSettingRepository settingRepository;
  private final SpotPositionRepository spotPositionRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final DemoExecutionGuard demoExecutionGuard;
  private final AuditLogService auditLogService;
  private final ApplicationEventPublisher eventPublisher;

  public DemoAccountLifecycleService(
      TradingAccountRepository accountRepository,
      WalletService walletService,
      LedgerService ledgerService,
      AccountSymbolSettingRepository settingRepository,
      SpotPositionRepository spotPositionRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      DemoExecutionGuard demoExecutionGuard,
      AuditLogService auditLogService
  ) {
    this(
        accountRepository,
        walletService,
        ledgerService,
        settingRepository,
        spotPositionRepository,
        positionRepository,
        orderRepository,
        demoExecutionGuard,
        auditLogService,
        NO_OP_EVENT_PUBLISHER);
  }

  @Autowired
  public DemoAccountLifecycleService(
      TradingAccountRepository accountRepository,
      WalletService walletService,
      LedgerService ledgerService,
      AccountSymbolSettingRepository settingRepository,
      SpotPositionRepository spotPositionRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      DemoExecutionGuard demoExecutionGuard,
      AuditLogService auditLogService,
      ApplicationEventPublisher eventPublisher
  ) {
    this.accountRepository = accountRepository;
    this.walletService = walletService;
    this.ledgerService = ledgerService;
    this.settingRepository = settingRepository;
    this.spotPositionRepository = spotPositionRepository;
    this.positionRepository = positionRepository;
    this.orderRepository = orderRepository;
    this.demoExecutionGuard = demoExecutionGuard;
    this.auditLogService = auditLogService;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public TradingAccountEntity getOrCreateDemoAccount(UUID userId) {
    if (userId == null) {
      throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "User is required");
    }
    var existing = accountRepository.findActiveDemoByUserId(userId);
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }

    UUID accountId = UUID.randomUUID();
    int inserted = accountRepository.insertActiveDemoIfAbsent(accountId, userId);
    if (inserted == 0) {
      return accountRepository.findActiveDemoByUserId(userId)
          .orElseThrow(() -> new BusinessException(
              ErrorCode.ACCOUNT_NOT_FOUND,
              "Concurrent Demo account creation did not produce an account"));
    }

    TradingAccountEntity account = initializedAccount(accountId, userId);
    walletService.creditAvailableWithEntryType(
        accountId,
        WalletType.SPOT,
        "USDT",
        INITIAL_BALANCE,
        "DEMO_ACCOUNT",
        accountId,
        "Initialize Demo Spot balance",
        "DEMO_INIT");
    ledgerService.recordDemoInit(account, INITIAL_BALANCE);
    CANONICAL_SYMBOLS.forEach(symbol -> settingRepository.save(defaultSetting(accountId, symbol)));
    return account;
  }

  @Transactional
  public DemoResetResponse reset(UUID userId, UUID accountId, UUID requestId) {
    if (requestId == null) {
      throw new BusinessException(ErrorCode.DEMO_RESET_BLOCKED, "Reset requestId is required");
    }
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    return resetLocked(userId, account, requestId, null);
  }

  @Transactional
  public DemoResetResponse resetAsAdmin(
      UUID actorUserId,
      UUID accountId,
      UUID requestId,
      String reason
  ) {
    if (actorUserId == null || accountId == null) {
      throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Admin actor and account are required");
    }
    if (requestId == null) {
      throw new BusinessException(ErrorCode.DEMO_RESET_BLOCKED, "Reset requestId is required");
    }
    if (reason == null || reason.isBlank()) {
      throw new BusinessException("ADMIN_RESET_REASON_REQUIRED", "Admin reset reason is required");
    }
    TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    return resetLocked(actorUserId, account, requestId, reason.trim());
  }

  private DemoResetResponse resetLocked(
      UUID auditActorUserId,
      TradingAccountEntity account,
      UUID requestId,
      String adminReason
  ) {
    UUID accountId = account.getId();
    demoExecutionGuard.requireDemoAccount(account);

    List<AccountSymbolSettingEntity> settings = settingRepository.findByAccountIdForUpdate(accountId);
    List<WalletBalanceEntity> wallets = walletService.lockAllBalances(accountId);
    List<SpotPositionEntity> spotPositions = spotPositionRepository.findByAccountIdForUpdate(accountId);
    var openPerpPositions = positionRepository.findOpenByAccountIdForUpdate(accountId);
    var activeOrders = orderRepository.findActiveByAccountIdForUpdate(accountId);
    var replay = ledgerService.findDemoReset(accountId, requestId);
    if (replay.isPresent()) {
      return resetResponse(account, currentSpotAvailable(wallets), requestId, true,
          replay.orElseThrow().getCreatedAt());
    }
    if (!openPerpPositions.isEmpty() || !activeOrders.isEmpty()) {
      throw new BusinessException(
          ErrorCode.DEMO_RESET_BLOCKED,
          "Demo reset requires no active orders or open perpetual positions");
    }

    BigDecimal oldPerpBalance = value(account.getBalance());
    account.setBaseCurrency("USDT");
    account.setBalance(INITIAL_BALANCE);
    account.setEquity(INITIAL_BALANCE);
    account.setUsedMargin(zero());
    account.setFreeMargin(INITIAL_BALANCE);
    account.setMarginLevel(null);
    account.setLeverage(10);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setDemoGeneration(Math.max(0L, value(account.getDemoGeneration())) + 1L);
    Instant resetAt = Instant.now();
    account.setResetAt(resetAt);

    WalletBalanceEntity spotUsdt = resetWallets(accountId, requestId, wallets);
    resetSpotPositions(spotPositions);
    restoreSettings(accountId, settings);
    accountRepository.save(account);

    ledgerService.recordDemoReset(
        account,
        INITIAL_BALANCE.subtract(oldPerpBalance).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        requestId);
    auditLogService.recordWithRequestId(
        auditActorUserId,
        adminReason == null ? "DEMO_RESET" : "ADMIN_DEMO_RESET",
        "TRADING_ACCOUNT",
        accountId.toString(),
        requestId,
        resetAuditDetails(account, adminReason));

    DemoResetResponse completed =
        resetResponse(account, spotUsdt.getAvailable(), requestId, false, resetAt);
    publishCompletedReset(account, requestId, completed);
    return completed;
  }

  private void publishCompletedReset(
      TradingAccountEntity account,
      UUID requestId,
      DemoResetResponse completed
  ) {
    eventPublisher.publishEvent(new TradingAccountMutationEvent(
        account.getUserId(),
        account.getId(),
        "DEMO_RESET",
        "ACCOUNT",
        account.getId(),
        requestId,
        completed.demoGeneration(),
        completed.resetAt()));
    eventPublisher.publishEvent(new TradingAccountMutationEvent(
        account.getUserId(),
        account.getId(),
        "BALANCE_UPDATED",
        "ACCOUNT",
        account.getId(),
        requestId,
        completed.demoGeneration(),
        completed.resetAt()));
  }

  private static String resetAuditDetails(
      TradingAccountEntity account,
      String adminReason
  ) {
    if (adminReason == null) {
      return "{\"generation\":" + account.getDemoGeneration() + "}";
    }
    return AuditDetailsBuilder.create()
        .put("generation", account.getDemoGeneration())
        .put("reason", adminReason)
        .toJson();
  }

  private WalletBalanceEntity resetWallets(
      UUID accountId,
      UUID requestId,
      List<WalletBalanceEntity> wallets
  ) {
    WalletBalanceEntity spotUsdt = null;
    for (WalletBalanceEntity wallet : wallets) {
      WalletType walletType = WalletType.fromCode(wallet.getWalletType());
      boolean isSpotUsdt = walletType == WalletType.SPOT && "USDT".equals(wallet.getAsset());
      BigDecimal target = isSpotUsdt ? INITIAL_BALANCE : zero();
      String description = isSpotUsdt ? "Reset Demo Spot balance" : "Reset Demo Spot asset";
      WalletBalanceEntity reset = walletService.resetBalance(
          accountId, walletType, wallet.getAsset(), target, requestId, description);
      if (isSpotUsdt) {
        spotUsdt = reset;
      }
    }
    if (spotUsdt == null) {
      spotUsdt = walletService.resetBalance(
          accountId,
          WalletType.SPOT,
          "USDT",
          INITIAL_BALANCE,
          requestId,
          "Reset Demo Spot balance");
    }
    return spotUsdt;
  }

  private void resetSpotPositions(List<SpotPositionEntity> positions) {
    for (SpotPositionEntity position : positions) {
      position.setQuantity(zero());
      position.setAverageCost(zero());
      position.setUnrealizedPnl(zero());
      position.setFeeCost(zero());
      spotPositionRepository.save(position);
    }
  }

  private void restoreSettings(UUID accountId, List<AccountSymbolSettingEntity> settings) {
    Set<String> existingSymbols = new HashSet<>();
    for (AccountSymbolSettingEntity setting : settings) {
      existingSymbols.add(setting.getSymbol());
      settingRepository.resetToDemoDefaults(accountId, setting.getSymbol());
      setting.setLeverage(10);
      setting.setMarginMode(MarginMode.CROSS);
      setting.setQuantityUnit(QuantityUnit.BASE);
      setting.setVersion(value(setting.getVersion()) + 1L);
    }
    CANONICAL_SYMBOLS.stream()
        .filter(symbol -> !existingSymbols.contains(symbol))
        .map(symbol -> defaultSetting(accountId, symbol))
        .forEach(settingRepository::save);
  }

  private static DemoResetResponse resetResponse(
      TradingAccountEntity account,
      BigDecimal spotAvailable,
      UUID requestId,
      boolean replayed,
      Instant resetAt
  ) {
    Instant effectiveResetAt = account.getResetAt() == null ? resetAt : account.getResetAt();
    return new DemoResetResponse(
        account.getId(),
        requestId,
        value(account.getDemoGeneration()),
        value(spotAvailable),
        value(account.getBalance()),
        value(account.getFreeMargin()),
        effectiveResetAt,
        replayed);
  }

  private static BigDecimal currentSpotAvailable(List<WalletBalanceEntity> wallets) {
    return wallets.stream()
        .filter(wallet -> WalletType.SPOT.code().equals(wallet.getWalletType()))
        .filter(wallet -> "USDT".equals(wallet.getAsset()))
        .map(WalletBalanceEntity::getAvailable)
        .findFirst()
        .orElse(zero());
  }

  private static TradingAccountEntity initializedAccount(UUID accountId, UUID userId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(INITIAL_BALANCE);
    account.setEquity(INITIAL_BALANCE);
    account.setUsedMargin(zero());
    account.setFreeMargin(INITIAL_BALANCE);
    account.setLeverage(10);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setDemoGeneration(1L);
    return account;
  }

  private static AccountSymbolSettingEntity defaultSetting(UUID accountId, String symbol) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(symbol);
    setting.setLeverage(10);
    setting.setMarginMode(MarginMode.CROSS);
    setting.setQuantityUnit(QuantityUnit.BASE);
    setting.setVersion(0L);
    return setting;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal value(BigDecimal value) {
    return value == null ? zero() : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static long value(Long value) {
    return value == null ? 0L : value;
  }
}
