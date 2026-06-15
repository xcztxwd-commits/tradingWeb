package com.fxplatform.ledger.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * LedgerService 是资金流水模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

  /** 资金流水仓储，用于保存和查询账务流水。 */
  private final LedgerEntryRepository ledgerEntryRepository;
  /** 交易账户仓储，用于校验用户对账户的访问权。 */
  private final TradingAccountRepository accountRepository;

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

  /**
   * 记录后台人工资金调整流水。
   */
  public LedgerEntryEntity recordAdminAdjustment(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID operationId,
      String description
  ) {
    return save(
        account,
        LedgerEntryType.ADMIN_ADJUSTMENT,
        amount,
        account.getBalance(),
        "ADMIN_FUND_OPERATION",
        operationId,
        description);
  }

  public List<LedgerEntryEntity> entries(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
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
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setAccountId(account.getId());
    entry.setEntryType(type);
    entry.setAmount(amount);
    entry.setBalanceAfter(balanceAfter);
    entry.setCurrency(account.getBaseCurrency());
    entry.setReferenceType(referenceType);
    entry.setReferenceId(referenceId);
    entry.setDescription(description);
    return ledgerEntryRepository.save(entry);
  }
}
