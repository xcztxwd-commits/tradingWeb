package com.fxplatform.account.service;

import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.dto.AccountTransferResponse;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedAccountRisk;
import com.fxplatform.trading.service.TradingTransactionExecutor;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AccountTransferService {

  private static final int MONEY_SCALE = 8;
  private static final String ASSET = "USDT";
  private static final ApplicationEventPublisher NO_OP_EVENT_PUBLISHER = event -> {
  };

  private final TradingAccountRepository accountRepository;
  private final WalletService walletService;
  private final LedgerService ledgerService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService;
  private final TradingTransactionExecutor transactionExecutor;
  private final ApplicationEventPublisher eventPublisher;

  public AccountTransferService(
      TradingAccountRepository accountRepository,
      WalletService walletService,
      LedgerService ledgerService,
      DemoExecutionGuard demoExecutionGuard,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(
        accountRepository,
        walletService,
        ledgerService,
        demoExecutionGuard,
        positionRepository,
        orderRepository,
        perpetualAccountRiskSnapshotService,
        transactionExecutor,
        NO_OP_EVENT_PUBLISHER);
  }

  @Autowired
  public AccountTransferService(
      TradingAccountRepository accountRepository,
      WalletService walletService,
      LedgerService ledgerService,
      DemoExecutionGuard demoExecutionGuard,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService,
      TradingTransactionExecutor transactionExecutor,
      ApplicationEventPublisher eventPublisher
  ) {
    this.accountRepository = accountRepository;
    this.walletService = walletService;
    this.ledgerService = ledgerService;
    this.demoExecutionGuard = demoExecutionGuard;
    this.positionRepository = positionRepository;
    this.orderRepository = orderRepository;
    this.perpetualAccountRiskSnapshotService = perpetualAccountRiskSnapshotService;
    this.transactionExecutor = transactionExecutor;
    this.eventPublisher = eventPublisher;
  }

  public AccountTransferResponse transfer(
      UUID userId,
      UUID accountId,
      Direction direction,
      BigDecimal amount,
      UUID requestId
  ) {
    BigDecimal normalizedAmount = positive(amount);
    if (direction == null || requestId == null) {
      throw new BusinessException(
          ErrorCode.TRANSFER_AMOUNT_INVALID,
          "Transfer direction and requestId are required");
    }

    var accountSnapshot = accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemoAccount(accountSnapshot);
    if (ledgerService.findTransfer(accountId, requestId).isPresent()) {
      return transactionExecutor.execute(
          () -> transferLocked(userId, accountId, direction, normalizedAmount, requestId, null));
    }

    BusinessException lastStale = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        PreparedAccountRisk prepared = perpetualAccountRiskSnapshotService.prepare(
            accountId,
            Map.of());
        return transactionExecutor.execute(
            () -> transferLocked(
                userId,
                accountId,
                direction,
                normalizedAmount,
                requestId,
                prepared));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode()) || attempt > 0) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw lastStale == null
        ? new BusinessException(ErrorCode.MARKET_DATA_STALE, "Perpetual account risk is stale")
        : lastStale;
  }

  private AccountTransferResponse transferLocked(
      UUID userId,
      UUID accountId,
      Direction direction,
      BigDecimal normalizedAmount,
      UUID requestId,
      PreparedAccountRisk prepared
  ) {

    var account = accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemoAccount(account);

    WalletBalanceEntity spot = requireSpotWallet(
        walletService.lockBalancesInOrder(accountId, List.of(ASSET)));

    var replay = ledgerService.findTransfer(accountId, requestId);
    if (replay.isPresent()) {
      LedgerService.TransferRecord existing = replay.orElseThrow();
      if (existing.direction() != direction || existing.amount().compareTo(normalizedAmount) != 0) {
        throw new BusinessException(
            ErrorCode.TRANSFER_REQUEST_CONFLICT,
            "requestId was already used for a different transfer");
      }
      return response(accountId, requestId, direction, normalizedAmount, spot, account, true,
          existing.createdAt());
    }

    var lockedPositions = positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId);
    var lockedOrders = orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId);
    var freshRisk = perpetualAccountRiskSnapshotService.project(
        account,
        lockedPositions,
        lockedOrders,
        prepared);
    perpetualAccountRiskSnapshotService.applyRevaluation(account, lockedPositions, freshRisk);

    String description;
    if (direction == Direction.SPOT_TO_PERP) {
      requireAvailable(spot.getAvailable(), normalizedAmount);
      description = "Spot to perpetual transfer";
      addPerp(account, normalizedAmount);
      accountRepository.save(account);
      lockedPositions.forEach(positionRepository::save);
      spot = walletService.debitAvailableWithEntryType(
          accountId,
          WalletType.SPOT,
          ASSET,
          normalizedAmount,
          "TRANSFER",
          requestId,
          description,
          LedgerEntryType.TRANSFER_OUT.name());
      ledgerService.recordTransfer(
          account,
          LedgerEntryType.TRANSFER_IN,
          normalizedAmount,
          requestId,
          description);
    } else {
      requireAvailable(account.getFreeMargin(), normalizedAmount);
      requireAvailable(account.getBalance(), normalizedAmount);
      description = "Perpetual to Spot transfer";
      subtractPerp(account, normalizedAmount);
      accountRepository.save(account);
      lockedPositions.forEach(positionRepository::save);
      spot = walletService.creditAvailableWithEntryType(
          accountId,
          WalletType.SPOT,
          ASSET,
          normalizedAmount,
          "TRANSFER",
          requestId,
          description,
          LedgerEntryType.TRANSFER_IN.name());
      ledgerService.recordTransfer(
          account,
          LedgerEntryType.TRANSFER_OUT,
          normalizedAmount.negate(),
          requestId,
          description);
    }

    Instant completedAt = Instant.now();
    AccountTransferResponse completed = response(
        accountId, requestId, direction, normalizedAmount, spot, account, false, completedAt);
    publishCompletedTransfer(userId, account, requestId, completedAt);
    return completed;
  }

  private void publishCompletedTransfer(
      UUID userId,
      com.fxplatform.account.entity.TradingAccountEntity account,
      UUID requestId,
      Instant completedAt
  ) {
    eventPublisher.publishEvent(new TradingAccountMutationEvent(
        userId,
        account.getId(),
        "TRANSFER_COMPLETED",
        "TRANSFER",
        requestId,
        null,
        account.getDemoGeneration(),
        completedAt));
    eventPublisher.publishEvent(new TradingAccountMutationEvent(
        userId,
        account.getId(),
        "BALANCE_UPDATED",
        "ACCOUNT",
        account.getId(),
        requestId,
        account.getDemoGeneration(),
        completedAt));
  }

  public List<AccountTransferResponse> history(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    return ledgerService.transferRecords(accountId).stream()
        .map(record -> new AccountTransferResponse(
            accountId,
            record.transferId(),
            record.direction(),
            record.amount(),
            record.spotBalanceAfter(),
            record.perpBalanceAfter(),
            null,
            false,
            record.createdAt()))
        .toList();
  }

  private static AccountTransferResponse response(
      UUID accountId,
      UUID requestId,
      Direction direction,
      BigDecimal amount,
      WalletBalanceEntity spot,
      com.fxplatform.account.entity.TradingAccountEntity account,
      boolean replayed,
      Instant createdAt
  ) {
    return new AccountTransferResponse(
        accountId,
        requestId,
        direction,
        amount,
        scale(spot.getAvailable()),
        scale(account.getBalance()),
        scale(account.getFreeMargin()),
        replayed,
        createdAt);
  }

  private static WalletBalanceEntity requireSpotWallet(List<WalletBalanceEntity> balances) {
    return balances.stream()
        .filter(balance -> WalletType.SPOT.code().equals(balance.getWalletType()))
        .filter(balance -> ASSET.equals(balance.getAsset()))
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.TRANSFER_AMOUNT_UNAVAILABLE,
            "Spot USDT wallet is unavailable"));
  }

  private static void addPerp(
      com.fxplatform.account.entity.TradingAccountEntity account,
      BigDecimal amount
  ) {
    account.setBalance(scale(value(account.getBalance()).add(amount)));
    account.setEquity(scale(value(account.getEquity()).add(amount)));
    account.setFreeMargin(scale(value(account.getFreeMargin()).add(amount)));
  }

  private static void subtractPerp(
      com.fxplatform.account.entity.TradingAccountEntity account,
      BigDecimal amount
  ) {
    account.setBalance(scale(value(account.getBalance()).subtract(amount)));
    account.setEquity(scale(value(account.getEquity()).subtract(amount)));
    account.setFreeMargin(scale(value(account.getFreeMargin()).subtract(amount)));
  }

  private static void requireAvailable(BigDecimal available, BigDecimal amount) {
    if (value(available).compareTo(amount) < 0) {
      throw new BusinessException(
          ErrorCode.TRANSFER_AMOUNT_UNAVAILABLE,
          "Transfer amount exceeds available funds");
    }
  }

  private static BigDecimal positive(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new BusinessException(ErrorCode.TRANSFER_AMOUNT_INVALID, "Transfer amount must be positive");
    }
    BigDecimal normalized = scale(amount);
    if (normalized.signum() <= 0) {
      throw new BusinessException(ErrorCode.TRANSFER_AMOUNT_INVALID, "Transfer amount must be positive");
    }
    return normalized;
  }

  private static BigDecimal value(BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO.setScale(MONEY_SCALE) : amount;
  }

  private static BigDecimal scale(BigDecimal amount) {
    return value(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }
}
