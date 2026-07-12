package com.fxplatform.account.service;

import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.dto.AccountTransferResponse;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountTransferService {

  private static final int MONEY_SCALE = 8;
  private static final String ASSET = "USDT";

  private final TradingAccountRepository accountRepository;
  private final WalletService walletService;
  private final LedgerService ledgerService;
  private final DemoExecutionGuard demoExecutionGuard;

  public AccountTransferService(
      TradingAccountRepository accountRepository,
      WalletService walletService,
      LedgerService ledgerService,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this.accountRepository = accountRepository;
    this.walletService = walletService;
    this.ledgerService = ledgerService;
    this.demoExecutionGuard = demoExecutionGuard;
  }

  @Transactional
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

    String description;
    if (direction == Direction.SPOT_TO_PERP) {
      requireAvailable(spot.getAvailable(), normalizedAmount);
      description = "Spot to perpetual transfer";
      spot = walletService.debitAvailableWithEntryType(
          accountId,
          WalletType.SPOT,
          ASSET,
          normalizedAmount,
          "TRANSFER",
          requestId,
          description,
          LedgerEntryType.TRANSFER_OUT.name());
      addPerp(account, normalizedAmount);
      accountRepository.save(account);
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

    return response(
        accountId, requestId, direction, normalizedAmount, spot, account, false, Instant.now());
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
