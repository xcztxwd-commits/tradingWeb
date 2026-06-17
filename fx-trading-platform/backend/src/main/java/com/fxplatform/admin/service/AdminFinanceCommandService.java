package com.fxplatform.admin.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.admin.dto.request.AdminBalanceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminFundOperationRequest;
import com.fxplatform.admin.dto.request.AdminPaymentMethodRequest;
import com.fxplatform.admin.dto.response.AdminFundOperationResponse;
import com.fxplatform.admin.dto.response.AdminPaymentMethodResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.finance.entity.AdminFundOperationEntity;
import com.fxplatform.finance.entity.AdminPaymentMethodEntity;
import com.fxplatform.finance.enums.AdminFundOperationType;
import com.fxplatform.finance.repository.AdminFundOperationRepository;
import com.fxplatform.finance.repository.AdminPaymentMethodRepository;
import com.fxplatform.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminFinanceCommandService 承载后台财务写操作。
 *
 * <p>服务负责账户余额、资金操作记录、资金流水和审计之间的编排；具体流水落库仍复用 LedgerService。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminFinanceCommandService {

  /** 交易账户仓储，用于加载和保存账户余额。 */
  private final TradingAccountRepository accountRepository;
  /** 资金流水服务，用于记录后台人工资金变更流水。 */
  private final LedgerService ledgerService;
  /** 后台资金操作仓储，用于保存入金、出金和余额调整记录。 */
  private final AdminFundOperationRepository fundOperationRepository;
  /** 支付方式仓储，用于保存后台支付配置。 */
  private final AdminPaymentMethodRepository paymentMethodRepository;
  /** 审计服务，用于记录所有后台财务写操作。 */
  private final AuditLogService auditLogService;

  /**
   * 后台人工入金。
   */
  @Transactional
  public AdminFundOperationResponse deposit(UUID actorUserId, UUID accountId, AdminFundOperationRequest request) {
    return applyBalanceChange(
        actorUserId,
        accountId,
        AdminFundOperationType.DEPOSIT,
        requirePositive(request.amount(), "Deposit amount must be positive"),
        request.reason(),
        request.paymentMethodId(),
        request.note(),
        request.idempotencyKey(),
        "ADMIN_FINANCE_DEPOSIT");
  }

  /**
   * 后台人工出金或扣款。
   */
  @Transactional
  public AdminFundOperationResponse withdraw(UUID actorUserId, UUID accountId, AdminFundOperationRequest request) {
    return applyBalanceChange(
        actorUserId,
        accountId,
        AdminFundOperationType.WITHDRAWAL,
        requirePositive(request.amount(), "Withdrawal amount must be positive").negate(),
        request.reason(),
        request.paymentMethodId(),
        request.note(),
        request.idempotencyKey(),
        "ADMIN_FINANCE_WITHDRAWAL");
  }

  /**
   * 后台余额调整，支持正向和负向修正。
   */
  @Transactional
  public AdminFundOperationResponse adjustBalance(
      UUID actorUserId,
      UUID accountId,
      AdminBalanceAdjustmentRequest request
  ) {
    if (request.delta().compareTo(BigDecimal.ZERO) == 0) {
      throw new BusinessException("ZERO_ADJUSTMENT_NOT_ALLOWED", "Adjustment delta must not be zero");
    }
    AdminActionConfirmation.require(request.confirmationText(), AdminActionConfirmation.CONFIRM_ADJUSTMENT);
    return applyBalanceChange(
        actorUserId,
        accountId,
        AdminFundOperationType.ADJUSTMENT,
        request.delta(),
        request.reason(),
        null,
        request.note(),
        request.idempotencyKey(),
        "ADMIN_FINANCE_ADJUSTMENT");
  }

  /**
   * 创建支付方式配置。
   */
  @Transactional
  public AdminPaymentMethodResponse createPaymentMethod(UUID actorUserId, AdminPaymentMethodRequest request) {
    AdminPaymentMethodEntity method = new AdminPaymentMethodEntity();
    applyPaymentMethodRequest(method, request);
    AdminPaymentMethodEntity saved = paymentMethodRepository.save(method);
    auditLogService.record(
        actorUserId,
        "ADMIN_PAYMENT_METHOD_CREATE",
        "PAYMENT_METHOD",
        saved.getId().toString(),
        details(request.name(), null, request.methodType(), null));
    return AdminPaymentMethodResponse.from(saved);
  }

  /**
   * 更新支付方式配置。
   */
  @Transactional
  public AdminPaymentMethodResponse updatePaymentMethod(
      UUID actorUserId,
      UUID paymentMethodId,
      AdminPaymentMethodRequest request
  ) {
    AdminPaymentMethodEntity method = paymentMethodRepository.findById(paymentMethodId)
        .orElseThrow(() -> new BusinessException("PAYMENT_METHOD_NOT_FOUND", "Payment method not found"));
    String before = method.getName() + ":" + method.getMethodType() + ":" + method.getEnabled();
    applyPaymentMethodRequest(method, request);
    AdminPaymentMethodEntity saved = paymentMethodRepository.save(method);
    auditLogService.record(
        actorUserId,
        "ADMIN_PAYMENT_METHOD_UPDATE",
        "PAYMENT_METHOD",
        paymentMethodId.toString(),
        details(request.name(), before, request.methodType() + ":" + request.enabled(), null));
    return AdminPaymentMethodResponse.from(saved);
  }

  /**
   * 删除收款方式配置。截图后台的删除按钮执行真实删除，并保留审计原因。
   */
  @Transactional
  public void deletePaymentMethod(UUID actorUserId, UUID paymentMethodId, String reason) {
    AdminPaymentMethodEntity method = paymentMethodRepository.findById(paymentMethodId)
        .orElseThrow(() -> new BusinessException("PAYMENT_METHOD_NOT_FOUND", "Payment method not found"));
    paymentMethodRepository.deleteById(paymentMethodId);
    auditLogService.record(
        actorUserId,
        "ADMIN_PAYMENT_METHOD_DELETE",
        "PAYMENT_METHOD",
        paymentMethodId.toString(),
        details(reason, method.getName(), method.getMethodType(), null));
  }

  /**
   * 后台资金写操作以 accountId + operationType + idempotencyKey 作为命令幂等边界。
   * 非空幂等键必须先抢占资金操作记录，抢占成功后才允许改账户余额和写流水。
   */
  private AdminFundOperationResponse applyBalanceChange(
      UUID actorUserId,
      UUID accountId,
      AdminFundOperationType operationType,
      BigDecimal signedAmount,
      String reason,
      UUID paymentMethodId,
      String note,
      String idempotencyKey,
      String auditAction
  ) {
    if (StrUtil.isNotBlank(idempotencyKey)) {
      var existing = fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
          accountId,
          operationType.code(),
          idempotencyKey);
      if (existing.isPresent()) {
        return AdminFundOperationResponse.from(existing.get());
      }
    }

    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    BigDecimal before = orZero(account.getBalance());
    BigDecimal after = before.add(signedAmount);
    ensureFreeMarginRemainsAvailable(after, orZero(account.getUsedMargin()));

    AdminFundOperationEntity operation = new AdminFundOperationEntity();
    operation.setAccountId(accountId);
    operation.setUserId(account.getUserId());
    operation.setOperationType(operationType);
    operation.setAmount(signedAmount);
    operation.setCurrency(account.getBaseCurrency());
    operation.setBeforeBalance(before);
    operation.setAfterBalance(after);
    operation.setAdminUserId(actorUserId);
    operation.setReason(reason);
    operation.setPaymentMethodId(paymentMethodId);
    operation.setNote(note);
    operation.setIdempotencyKey(idempotencyKey);
    AdminFundOperationEntity saved = saveFundOperation(operation);
    if (saved != operation) {
      return AdminFundOperationResponse.from(saved);
    }

    account.setBalance(after);
    account.setEquity(after);
    account.setFreeMargin(after.subtract(orZero(account.getUsedMargin())));
    accountRepository.save(account);

    ledgerService.recordAdminAdjustment(account, signedAmount, saved.getId(), reason);
    auditLogService.record(
        actorUserId,
        auditAction,
        "ACCOUNT",
        accountId.toString(),
        details(reason, before.toPlainString(), after.toPlainString(), idempotencyKey));
    return AdminFundOperationResponse.from(saved);
  }

  private AdminFundOperationEntity saveFundOperation(AdminFundOperationEntity operation) {
    if (StrUtil.isBlank(operation.getIdempotencyKey())) {
      return fundOperationRepository.save(operation);
    }

    operation.setId(UUID.randomUUID());
    if (fundOperationRepository.insertIfAbsent(operation) == 1) {
      return operation;
    }
    return fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
            operation.getAccountId(),
            operation.getOperationType().code(),
            operation.getIdempotencyKey())
        .orElseThrow(() -> new BusinessException("IDEMPOTENCY_RESULT_NOT_FOUND", "Idempotent operation result not found"));
  }

  /**
   * 将请求字段写入支付方式实体。
   */
  private void applyPaymentMethodRequest(AdminPaymentMethodEntity method, AdminPaymentMethodRequest request) {
    method.setName(request.name());
    method.setMethodType(request.methodType());
    method.setCurrency(request.currency());
    method.setEnabled(request.enabled());
    method.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
    method.setInstructions(request.instructions());
  }

  /**
   * 校验金额必须为正数。
   */
  private BigDecimal requirePositive(BigDecimal amount, String message) {
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("INVALID_AMOUNT", message);
    }
    return amount;
  }

  /**
   * 防止后台出金或负向调整导致可用保证金为负。
   */
  private void ensureFreeMarginRemainsAvailable(BigDecimal afterBalance, BigDecimal usedMargin) {
    if (afterBalance.subtract(usedMargin).compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException("INSUFFICIENT_FREE_MARGIN", "Insufficient free margin");
    }
  }

  /**
   * 构造审计 JSON 明细。
   */
  private String details(String reason, String before, String after, String idempotencyKey) {
    return AuditDetailsBuilder.create()
        .put("reason", reason)
        .put("before", before)
        .put("after", after)
        .put("idempotencyKey", idempotencyKey)
        .toJson();
  }
}
