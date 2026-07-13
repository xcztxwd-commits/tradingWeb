package com.fxplatform.admin.service;

import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.dto.WalletBalanceResponse;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminAccountResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.dto.LedgerEntryResponse;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.trading.dto.response.FundingSettlementResponse;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.wallet.service.WalletService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read-only Admin account projections. */
@Service
public class AdminAccountQueryService {

  private final TradingAccountRepository accountRepository;
  private final WalletService walletService;
  private final FundingSettlementRepository fundingSettlementRepository;
  private final LedgerEntryRepository ledgerEntryRepository;

  /** Retained for focused legacy account-page tests. */
  public AdminAccountQueryService(TradingAccountRepository accountRepository) {
    this(accountRepository, null, null, null);
  }

  @Autowired
  public AdminAccountQueryService(
      TradingAccountRepository accountRepository,
      WalletService walletService,
      FundingSettlementRepository fundingSettlementRepository,
      LedgerEntryRepository ledgerEntryRepository
  ) {
    this.accountRepository = accountRepository;
    this.walletService = walletService;
    this.fundingSettlementRepository = fundingSettlementRepository;
    this.ledgerEntryRepository = ledgerEntryRepository;
  }

  public AdminPageResponse<AdminAccountResponse> accounts(int page, int size) {
    return AdminPageResponse.from(accountRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminAccountResponse::from));
  }

  public List<WalletBalanceResponse> walletBalances(UUID accountId) {
    requireAccount(accountId);
    requireWalletService();
    return walletService.balances(accountId).stream()
        .map(WalletBalanceResponse::from)
        .toList();
  }

  public List<AssetLedgerEntryResponse> assetLedger(
      UUID accountId,
      String walletType,
      String asset,
      String entryType,
      UUID referenceId,
      Instant from,
      Instant to
  ) {
    requireAccount(accountId);
    requireWalletService();
    return walletService.assetLedgerEntries(
            accountId,
            walletType,
            asset,
            entryType,
            referenceId,
            from,
            to)
        .stream()
        .map(AssetLedgerEntryResponse::from)
        .toList();
  }

  public List<FundingSettlementResponse> fundingSettlements(UUID accountId) {
    requireAccount(accountId);
    if (fundingSettlementRepository == null) {
      throw new BusinessException(
          "FUNDING_SETTLEMENT_QUERY_UNAVAILABLE",
          "Funding settlement query is unavailable");
    }
    return fundingSettlementRepository.findByAccountIdOrderByFundingTimeDesc(accountId).stream()
        .map(FundingSettlementResponse::from)
        .toList();
  }

  public List<LedgerEntryResponse> ledger(UUID accountId) {
    requireAccount(accountId);
    if (ledgerEntryRepository == null) {
      throw new BusinessException(
          "LEDGER_QUERY_UNAVAILABLE",
          "Cash ledger query is unavailable");
    }
    return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
        .map(LedgerEntryResponse::fromCashLedger)
        .toList();
  }

  private void requireAccount(UUID accountId) {
    if (accountId == null || accountRepository.findById(accountId).isEmpty()) {
      throw new BusinessException("ACCOUNT_NOT_FOUND", "Account not found");
    }
  }

  private void requireWalletService() {
    if (walletService == null) {
      throw new BusinessException("WALLET_QUERY_UNAVAILABLE", "Wallet query is unavailable");
    }
  }
}
