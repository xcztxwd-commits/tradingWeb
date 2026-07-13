package com.fxplatform.account.service;

import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.dto.AccountTransferResponse;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.trading.dto.response.TradingPageResponse;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ownership-scoped, database-paged view of paired Spot/Perp transfer ledger rows. */
@Service
@RequiredArgsConstructor
public class AccountTransferQueryService {

  private final TradingAccountRepository accountRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final AssetLedgerEntryRepository assetLedgerEntryRepository;

  @Transactional(readOnly = true)
  public TradingPageResponse<AccountTransferResponse> history(
      UUID userId,
      UUID accountId,
      Direction direction,
      int page,
      int size
  ) {
    requireOwnedAccount(userId, accountId);
    var result = ledgerEntryRepository.findTransferPage(
        accountId, operationType(direction), TradingPageResponse.request(page, size));
    List<UUID> transferIds = result.getRecords().stream()
        .map(LedgerEntryEntity::getReferenceId)
        .toList();
    List<AssetLedgerEntryEntity> assetEntries = transferIds.isEmpty()
        ? List.of()
        : assetLedgerEntryRepository.findByReferences(accountId, "TRANSFER", transferIds);
    Map<UUID, List<AssetLedgerEntryEntity>> assetsByTransfer = assetEntries.stream()
        .collect(Collectors.groupingBy(AssetLedgerEntryEntity::getReferenceId));
    return TradingPageResponse.from(
        result.convert(entry -> toResponse(entry, assetsByTransfer)));
  }

  private void requireOwnedAccount(UUID userId, UUID accountId) {
    if (userId == null || accountId == null
        || accountRepository.findByIdAndUserId(accountId, userId).isEmpty()) {
      throw new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found");
    }
  }

  private String operationType(Direction direction) {
    if (direction == null) {
      return null;
    }
    return direction == Direction.SPOT_TO_PERP
        ? LedgerEntryType.TRANSFER_IN.name()
        : LedgerEntryType.TRANSFER_OUT.name();
  }

  private AccountTransferResponse toResponse(
      LedgerEntryEntity cashEntry,
      Map<UUID, List<AssetLedgerEntryEntity>> assetsByTransfer
  ) {
    UUID transferId = cashEntry.getReferenceId();
    Direction direction = direction(cashEntry);
    List<AssetLedgerEntryEntity> assetEntries = assetsByTransfer.getOrDefault(
        transferId, List.of());
    if (assetEntries.size() != 1) {
      throw transferConflict();
    }
    AssetLedgerEntryEntity assetEntry = assetEntries.getFirst();
    String expectedAssetOperation = direction == Direction.SPOT_TO_PERP
        ? LedgerEntryType.TRANSFER_OUT.name()
        : LedgerEntryType.TRANSFER_IN.name();
    if (!expectedAssetOperation.equals(assetEntry.getOperationType())
        || cashEntry.getAmount() == null
        || assetEntry.getAmount() == null
        || cashEntry.getAmount().abs().compareTo(assetEntry.getAmount().abs()) != 0) {
      throw transferConflict();
    }
    return new AccountTransferResponse(
        cashEntry.getAccountId(),
        transferId,
        direction,
        cashEntry.getAmount().abs(),
        assetEntry.getBalanceAfter(),
        cashEntry.getBalanceAfter(),
        null,
        false,
        cashEntry.getCreatedAt());
  }

  private Direction direction(LedgerEntryEntity entry) {
    if (LedgerEntryType.TRANSFER_IN.name().equals(entry.getOperationType())) {
      return Direction.SPOT_TO_PERP;
    }
    if (LedgerEntryType.TRANSFER_OUT.name().equals(entry.getOperationType())) {
      return Direction.PERP_TO_SPOT;
    }
    throw transferConflict();
  }

  private BusinessException transferConflict() {
    return new BusinessException(
        ErrorCode.TRANSFER_REQUEST_CONFLICT,
        "Transfer ledger pair is incomplete or inconsistent");
  }
}
