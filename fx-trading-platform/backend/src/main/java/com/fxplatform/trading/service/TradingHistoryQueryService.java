package com.fxplatform.trading.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.trading.dto.response.FundingSettlementResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.dto.response.TradeResponse;
import com.fxplatform.trading.dto.response.TradingPageResponse;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.PositionRepository.ClosedPositionPageKey;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ownership-scoped read model for paged user-visible trading state and history. */
@Service
@RequiredArgsConstructor
public class TradingHistoryQueryService {

  private final TradingAccountRepository accountRepository;
  private final OrderRepository orderRepository;
  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final SpotPositionRepository spotPositionRepository;
  private final FundingSettlementRepository fundingSettlementRepository;
  private final OrderResponseMapper orderResponseMapper;
  private final PositionService positionService;

  @Transactional(readOnly = true)
  public TradingPageResponse<OrderResponse> orders(
      UUID userId,
      UUID accountId,
      OrderStatus status,
      String symbol,
      int page,
      int size
  ) {
    requireOwnedAccount(userId, accountId);
    var result = orderRepository.findPageByAccountId(
        accountId, status, normalizeSymbol(symbol), TradingPageResponse.request(page, size));
    return TradingPageResponse.from(result.convert(orderResponseMapper::toResponse));
  }

  @Transactional(readOnly = true)
  public TradingPageResponse<TradeResponse> trades(
      UUID userId,
      UUID accountId,
      String symbol,
      int page,
      int size
  ) {
    requireOwnedAccount(userId, accountId);
    Page<TradeEntity> result = tradeRepository.findPageByAccountId(
        accountId, normalizeSymbol(symbol), TradingPageResponse.request(page, size));
    return TradingPageResponse.from(result.convert(TradeResponse::from));
  }

  public TradingPageResponse<PositionResponse> positions(
      UUID userId,
      UUID accountId,
      PositionStatus status,
      String symbol,
      int page,
      int size
  ) {
    if (status == PositionStatus.OPEN) {
      return openPositions(userId, accountId, symbol, page, size);
    }
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    String normalizedSymbol = normalizeSymbol(symbol);
    Page<?> request = TradingPageResponse.request(page, size);
    long offset = (request.getCurrent() - 1) * request.getSize();
    List<ClosedPositionPageKey> keys = positionRepository.findClosedPageKeys(
        accountId, normalizedSymbol, offset, (int) request.getSize());
    long total = positionRepository.countClosedPageItems(accountId, normalizedSymbol);

    List<UUID> perpIds = keys.stream()
        .filter(key -> "PERP".equals(key.getSource()))
        .map(ClosedPositionPageKey::getId)
        .toList();
    List<UUID> spotIds = keys.stream()
        .filter(key -> "SPOT".equals(key.getSource()))
        .map(ClosedPositionPageKey::getId)
        .toList();
    var perpEntities = positionRepository.findAllByIds(perpIds);
    var spotEntities = spotPositionRepository.findAllByIds(spotIds);
    Map<UUID, PositionResponse> perpResponses = indexById(
        positionService.toStoredResponses(perpEntities, account));
    Map<UUID, PositionResponse> spotResponses = indexById(
        positionService.toClosedSpotResponses(spotEntities));
    List<PositionResponse> items = keys.stream()
        .map(key -> "SPOT".equals(key.getSource())
            ? spotResponses.get(key.getId())
            : perpResponses.get(key.getId()))
        .filter(java.util.Objects::nonNull)
        .toList();
    if (items.size() != keys.size()) {
      throw new IllegalStateException("Closed position page changed while it was being read");
    }
    return TradingPageResponse.of(items, page, size, total);
  }

  @Transactional(readOnly = true)
  public TradingPageResponse<FundingSettlementResponse> fundingSettlements(
      UUID userId,
      UUID accountId,
      String symbol,
      int page,
      int size
  ) {
    requireOwnedAccount(userId, accountId);
    Page<FundingSettlementEntity> result = fundingSettlementRepository.findPageByAccountId(
        accountId, normalizeSymbol(symbol), TradingPageResponse.request(page, size));
    return TradingPageResponse.from(result.convert(FundingSettlementResponse::from));
  }

  private TradingAccountEntity requireOwnedAccount(UUID userId, UUID accountId) {
    if (userId == null || accountId == null) {
      throw new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found");
    }
    return accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

  private TradingPageResponse<PositionResponse> openPositions(
      UUID userId,
      UUID accountId,
      String symbol,
      int page,
      int size
  ) {
    String normalizedSymbol = normalizeSymbol(symbol);
    List<PositionResponse> sorted = positionService.openPositions(userId, accountId).stream()
        .filter(position -> normalizedSymbol == null
            || normalizedSymbol.equals(normalizeSymbol(position.symbol())))
        .sorted(Comparator
            .comparing(
                PositionResponse::openedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PositionResponse::id, Comparator.reverseOrder()))
        .toList();
    return TradingPageResponse.fromBoundedItems(sorted, page, size);
  }

  private Map<UUID, PositionResponse> indexById(List<PositionResponse> positions) {
    return positions.stream().collect(Collectors.toMap(
        PositionResponse::id,
        Function.identity()));
  }

  private String normalizeSymbol(String symbol) {
    return symbol == null || symbol.isBlank()
        ? null
        : SymbolNormalizer.normalize(symbol.trim());
  }
}
