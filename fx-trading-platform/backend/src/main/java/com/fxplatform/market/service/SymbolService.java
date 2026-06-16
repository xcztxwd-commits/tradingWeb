package com.fxplatform.market.service;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.market.dto.SymbolResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.provider.MarketDataCapability;
import com.fxplatform.market.provider.ProviderResolver;
import com.fxplatform.market.repository.SymbolRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Provides user-facing platform symbols only.
 */
@Service
@RequiredArgsConstructor
public class SymbolService {

  private final SymbolRepository symbolRepository;
  private final ProviderResolver providerResolver;

  public List<SymbolResponse> enabledSymbols() {
    return enabledSymbols(null, 2000);
  }

  public List<SymbolResponse> enabledSymbols(String assetClass, int limit) {
    int normalizedLimit = limit > 0 ? limit : 1000;
    return symbolRepository.findVisibleSymbols(normalizeAssetClass(assetClass))
        .stream()
        .limit(normalizedLimit)
        .map(this::toResponse)
        .toList();
  }

  private SymbolResponse toResponse(SymbolEntity entity) {
    boolean quoteEnabled = capabilityEnabled(entity, entity.getQuoteEnabled(), MarketDataCapability.QUOTE);
    boolean chartEnabled = capabilityEnabled(entity, entity.getChartEnabled(), MarketDataCapability.CANDLES);
    boolean orderBookEnabled = capabilityEnabled(entity, entity.getOrderBookEnabled(), MarketDataCapability.ORDER_BOOK);
    return new SymbolResponse(
        entity.getSymbol(),
        entity.getDisplayName(),
        entity.getAssetClass(),
        SymbolProductTypes.readOrLegacy(entity),
        entity.getBaseCurrency(),
        entity.getQuoteCurrency(),
        entity.getMinLot(),
        entity.getMaxLot(),
        entity.getLeverage(),
        Boolean.TRUE.equals(entity.getEnabled()),
        entity.getProvider(),
        entity.getProviderSymbol(),
        Boolean.TRUE.equals(entity.getTradable()),
        entity.getIconUrl(),
        Boolean.TRUE.equals(entity.getDisplayEnabled()),
        quoteEnabled,
        chartEnabled,
        orderBookEnabled,
        Boolean.TRUE.equals(entity.getFeatured()),
        entity.getDisplayGroup(),
        entity.getDisplayOrder(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "{}");
  }

  private boolean capabilityEnabled(SymbolEntity entity, Boolean symbolEnabled, MarketDataCapability capability) {
    return Boolean.TRUE.equals(symbolEnabled) && providerResolver.canResolve(entity, capability);
  }

  private String normalizeAssetClass(String assetClass) {
    return StrUtil.isBlank(assetClass) ? null : assetClass.trim().toUpperCase(Locale.ROOT);
  }
}
