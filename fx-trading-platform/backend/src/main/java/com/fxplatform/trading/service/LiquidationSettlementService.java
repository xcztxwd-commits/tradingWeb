package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.entity.CrossLiquidationChargeEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.repository.CrossLiquidationChargeRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists per-fill liabilities and settles one Cross liquidation account at net scope. */
@Service
public class LiquidationSettlementService {

  private static final int MONEY_SCALE = 8;

  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final CrossLiquidationChargeRepository chargeRepository;
  private final LedgerService ledgerService;
  private final AuditLogService auditLogService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final TradingTransactionExecutor transactionExecutor;

  public LiquidationSettlementService(
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      CrossLiquidationChargeRepository chargeRepository,
      LedgerService ledgerService,
      AuditLogService auditLogService,
      DemoExecutionGuard demoExecutionGuard,
      TradingTransactionExecutor transactionExecutor
  ) {
    this.accountRepository = accountRepository;
    this.positionRepository = positionRepository;
    this.chargeRepository = chargeRepository;
    this.ledgerService = ledgerService;
    this.auditLogService = auditLogService;
    this.demoExecutionGuard = demoExecutionGuard;
    this.transactionExecutor = transactionExecutor;
  }

  /** Called inside the canonical fill transaction. */
  public void recordCrossChargeLocked(
      UUID accountId,
      UUID positionId,
      UUID orderId,
      BigDecimal contractualFee
  ) {
    CrossLiquidationChargeEntity charge = new CrossLiquidationChargeEntity();
    charge.setOrderId(orderId);
    charge.setAccountId(accountId);
    charge.setPositionId(positionId);
    charge.setFeeDue(money(contractualFee).max(BigDecimal.ZERO));
    charge.setFeeCharged(money(BigDecimal.ZERO));
    charge.setStatus("PENDING");
    chargeRepository.insertIfAbsent(charge);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public boolean settleIfReady(UUID accountId) {
    return transactionExecutor.execute(() -> settleLocked(accountId, true));
  }

  /** Admin cleanup variant that keeps its write gate until the final empty-scope check. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public boolean settleIfReadyKeepingPending(UUID accountId) {
    return transactionExecutor.execute(() -> settleLocked(accountId, false));
  }

  private boolean settleLocked(UUID accountId, boolean restoreActive) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemoRiskReductionAccount(account);
    List<PositionEntity> openPositions = positionRepository
        .findOpenLinearPerpByAccountIdForUpdate(accountId);
    List<CrossLiquidationChargeEntity> charges = chargeRepository
        .findPendingByAccountIdForUpdate(accountId);
    BigDecimal protectedIsolatedMargin = money(openPositions.stream()
        .filter(position -> position.getMarginMode() == MarginMode.ISOLATED)
        .map(PositionEntity::getMarginHeld)
        .map(LiquidationSettlementService::money)
        .reduce(BigDecimal.ZERO, BigDecimal::add));
    BigDecimal coreShortfall = money(
        protectedIsolatedMargin.subtract(orZero(account.getBalance())).max(BigDecimal.ZERO));
    if (coreShortfall.signum() > 0) {
      creditCash(account, coreShortfall);
    }
    floorCashState(account);

    if (openPositions.stream().anyMatch(position -> position.getMarginMode() == MarginMode.CROSS)) {
      accountRepository.save(account);
      recordShortfall(
          account,
          accountId,
          coreShortfall,
          partialSettlementId(accountId, charges));
      return false;
    }

    BigDecimal totalFee = money(charges.stream()
        .map(CrossLiquidationChargeEntity::getFeeDue)
        .map(LiquidationSettlementService::money)
        .reduce(BigDecimal.ZERO, BigDecimal::add));
    BigDecimal feeCapacity = money(
        orZero(account.getBalance()).subtract(protectedIsolatedMargin).max(BigDecimal.ZERO));
    BigDecimal chargedFee = money(totalFee.min(feeCapacity));

    BigDecimal remainingCharge = chargedFee;
    for (CrossLiquidationChargeEntity charge : charges) {
      BigDecimal itemCharge = money(money(charge.getFeeDue()).min(remainingCharge));
      if (itemCharge.signum() > 0) {
        debitCash(account, itemCharge);
        floorCashState(account);
        accountRepository.save(account);
        ledgerService.recordLiquidationFee(
            account,
            itemCharge,
            charge.getPositionId(),
            "Liquidation fee charged");
      }
      remainingCharge = money(remainingCharge.subtract(itemCharge));
      charge.setFeeCharged(itemCharge);
      charge.setStatus("SETTLED");
      charge.setSettledAt(Instant.now());
      chargeRepository.updateById(charge);
    }

    BigDecimal feeShortfall = money(totalFee.subtract(chargedFee).max(BigDecimal.ZERO));
    BigDecimal totalShortfall = money(coreShortfall.add(feeShortfall));
    if (restoreActive && account.getStatus() == AccountStatus.LIQUIDATION_PENDING) {
      account.setStatus(AccountStatus.ACTIVE);
    }
    floorCashState(account);
    accountRepository.save(account);
    recordShortfall(
        account,
        accountId,
        totalShortfall,
        settlementId(accountId, charges));
    return true;
  }

  private void recordShortfall(
      TradingAccountEntity account,
      UUID accountId,
      BigDecimal amount,
      UUID settlementId
  ) {
    if (amount.signum() <= 0) {
      return;
    }
    ledgerService.recordCrossLiquidationShortfall(
        account,
        amount,
        settlementId,
        "Cross liquidation bankruptcy shortfall");
    auditLogService.record(
        null,
        "BANKRUPTCY_SHORTFALL",
        "ACCOUNT",
        accountId.toString(),
        "{\"amount\":" + amount.toPlainString()
            + ",\"settlementId\":\"" + settlementId + "\"}");
  }

  private static UUID settlementId(
      UUID accountId,
      List<CrossLiquidationChargeEntity> charges
  ) {
    return settlementId("cross-liquidation:", accountId, charges);
  }

  private static UUID partialSettlementId(
      UUID accountId,
      List<CrossLiquidationChargeEntity> charges
  ) {
    return settlementId("cross-liquidation-partial:", accountId, charges);
  }

  private static UUID settlementId(
      String prefix,
      UUID accountId,
      List<CrossLiquidationChargeEntity> charges
  ) {
    String orderIds = charges.stream()
        .map(CrossLiquidationChargeEntity::getOrderId)
        .sorted()
        .map(UUID::toString)
        .reduce((left, right) -> left + ":" + right)
        .orElse("no-fee");
    return UUID.nameUUIDFromBytes(
        (prefix + accountId + ":" + orderIds)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static void creditCash(TradingAccountEntity account, BigDecimal amount) {
    account.setBalance(money(orZero(account.getBalance()).add(amount)));
    account.setEquity(money(orZero(account.getEquity()).add(amount)));
    account.setFreeMargin(money(orZero(account.getFreeMargin()).add(amount)));
  }

  private static void debitCash(TradingAccountEntity account, BigDecimal amount) {
    account.setBalance(money(orZero(account.getBalance()).subtract(amount)));
    account.setEquity(money(orZero(account.getEquity()).subtract(amount)));
    account.setFreeMargin(money(orZero(account.getFreeMargin()).subtract(amount)));
  }

  private static void floorCashState(TradingAccountEntity account) {
    account.setBalance(money(orZero(account.getBalance()).max(BigDecimal.ZERO)));
    account.setEquity(money(orZero(account.getEquity()).max(BigDecimal.ZERO)));
    account.setFreeMargin(money(orZero(account.getFreeMargin()).max(BigDecimal.ZERO)));
  }

  private static BigDecimal money(BigDecimal value) {
    return orZero(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }
}
