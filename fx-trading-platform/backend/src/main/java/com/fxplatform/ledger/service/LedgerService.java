package com.fxplatform.ledger.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.ledger.dto.LedgerEntryResponse;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * LedgerService 是资金流水模块的业务服务。
 */
@Service
public class LedgerService {

  /** 资金流水仓储，用于保存和查询账务流水。 */
  private final LedgerEntryRepository ledgerEntryRepository;
  /** 交易账户仓储，用于校验用户对账户的访问权。 */
  private final TradingAccountRepository accountRepository;
  private final AssetLedgerEntryRepository assetLedgerEntryRepository;

  @Autowired
  public LedgerService(
      LedgerEntryRepository ledgerEntryRepository,
      TradingAccountRepository accountRepository,
      AssetLedgerEntryRepository assetLedgerEntryRepository
  ) {
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.accountRepository = accountRepository;
    this.assetLedgerEntryRepository = assetLedgerEntryRepository;
  }

  public LedgerService(
      LedgerEntryRepository ledgerEntryRepository,
      TradingAccountRepository accountRepository
  ) {
    this(ledgerEntryRepository, accountRepository, null);
  }

  public LedgerEntryEntity recordDemoDeposit(
      TradingAccountEntity account,
      BigDecimal amount,
      String description
  ) {
    return save(account, LedgerEntryType.DEMO_DEPOSIT, amount, account.getBalance(), null, null, description);
  }

  public LedgerEntryEntity recordMarginHold(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.MARGIN_HOLD, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordOrderHold(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID orderId,
      String description
  ) {
    return save(account, LedgerEntryType.ORDER_HOLD, amount, account.getBalance(), "ORDER", orderId, description);
  }

  public LedgerEntryEntity recordOrderRelease(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID orderId,
      String description
  ) {
    return save(account, LedgerEntryType.ORDER_RELEASE, amount, account.getBalance(), "ORDER", orderId, description);
  }

  public LedgerEntryEntity recordMarginRelease(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.MARGIN_RELEASE, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordTradePnl(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID tradeOrPositionId,
      String description
  ) {
    return save(account, LedgerEntryType.TRADE_PNL, amount, account.getBalance(), "POSITION", tradeOrPositionId, description);
  }

  public LedgerEntryEntity recordTradeFee(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID tradeOrPositionId,
      String description
  ) {
    return save(account, LedgerEntryType.TRADE_FEE, amount.negate(), account.getBalance(), "POSITION", tradeOrPositionId, description);
  }

  public LedgerEntryEntity recordTradeFeeForTrade(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID tradeId,
      String description
  ) {
    return saveIdempotent(account, LedgerEntryType.TRADE_FEE, amount.negate(), account.getBalance(), "TRADE", tradeId, description);
  }

  public LedgerEntryEntity recordFundingFee(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.FUNDING_FEE, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordFundingFeeSettlement(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID settlementId,
      String description
  ) {
    return saveIdempotent(account, LedgerEntryType.FUNDING_FEE, amount, account.getBalance(), "FUNDING_SETTLEMENT", settlementId, description);
  }

  public LedgerEntryEntity recordLiquidationFee(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.LIQUIDATION_FEE, amount.negate(), account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordFinancing(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.FINANCING, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordFinancingSettlement(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID settlementId,
      String description
  ) {
    return saveIdempotent(account, LedgerEntryType.FINANCING, amount, account.getBalance(), "FX_FINANCING_SETTLEMENT", settlementId, description);
  }

  public LedgerEntryEntity recordForcedClose(
      TradingAccountEntity account,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.FORCED_CLOSE, BigDecimal.ZERO, account.getBalance(), "POSITION", positionId, description);
  }

  /**
   * 记录后台人工资金调整流水。
   */
  public LedgerEntryEntity recordAdminAdjustment(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID operationId,
      String description
  ) {
    return saveIdempotent(
        account,
        LedgerEntryType.ADMIN_ADJUSTMENT,
        amount,
        account.getBalance(),
        "ADMIN_FUND_OPERATION",
        operationId,
        description);
  }

  private LedgerEntryEntity saveIdempotent(
      TradingAccountEntity account,
      LedgerEntryType type,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    String operationType = type.name();
    LedgerEntryEntity existing = ledgerEntryRepository.findByBusinessOperation(
        account.getId(),
        referenceType,
        referenceId,
        operationType);
    if (existing != null) {
      return existing;
    }
    return save(
        account,
        type,
        amount,
        balanceAfter,
        referenceType,
        referenceId,
        description);
  }

  public List<LedgerEntryEntity> entries(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
  }

  public List<LedgerEntryResponse> visibleEntries(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    List<LedgerEntryResponse> combined = new ArrayList<>();
    ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
        .map(LedgerEntryResponse::fromCashLedger)
        .forEach(combined::add);
    if (assetLedgerEntryRepository != null) {
      assetLedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
          .map(LedgerEntryResponse::fromAssetLedger)
          .forEach(combined::add);
    }
    combined.sort(Comparator
        .comparing(LedgerEntryResponse::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
        .reversed());
    return combined;
  }

  private LedgerEntryEntity save(
      TradingAccountEntity account,
      LedgerEntryType type,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    String operationType = type.name();
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setAccountId(account.getId());
    entry.setEntryType(type);
    entry.setOperationType(operationType);
    entry.setAmount(amount);
    entry.setBalanceAfter(balanceAfter);
    entry.setCurrency(account.getBaseCurrency());
    entry.setReferenceType(referenceType);
    entry.setReferenceId(referenceId);
    entry.setDescription(description);
    return ledgerEntryRepository.save(entry);
  }
}
