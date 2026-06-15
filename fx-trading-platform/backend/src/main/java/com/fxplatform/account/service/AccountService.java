package com.fxplatform.account.service;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AccountService 是账户模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;

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

  public AccountResponse toResponse(TradingAccountEntity account) {
    return new AccountResponse(
        account.getId(),
        account.getAccountType().name(),
        account.getBaseCurrency(),
        account.getBalance(),
        account.getEquity(),
        account.getUsedMargin(),
        account.getFreeMargin(),
        account.getMarginLevel(),
        account.getLeverage(),
        account.getStatus().name());
  }
}
