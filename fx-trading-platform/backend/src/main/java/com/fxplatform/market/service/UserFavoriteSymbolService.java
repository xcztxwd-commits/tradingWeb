package com.fxplatform.market.service;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.UserFavoriteSymbolEntity;
import com.fxplatform.market.repository.UserFavoriteSymbolRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserFavoriteSymbolService {

  private final UserFavoriteSymbolRepository favoriteSymbolRepository;

  public List<String> favoriteSymbols(UUID userId) {
    return favoriteSymbolRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
        .map(UserFavoriteSymbolEntity::getSymbol)
        .toList();
  }

  @Transactional
  public List<String> setFavorite(UUID userId, String symbol, boolean favorite) {
    String normalizedSymbol = normalizeSymbol(symbol);
    var existing = favoriteSymbolRepository.findByUserIdAndSymbol(userId, normalizedSymbol);

    if (favorite && existing.isEmpty()) {
      UserFavoriteSymbolEntity entity = new UserFavoriteSymbolEntity();
      entity.setUserId(userId);
      entity.setSymbol(normalizedSymbol);
      favoriteSymbolRepository.save(entity);
    }
    if (!favorite) {
      existing.ifPresent(entity -> favoriteSymbolRepository.deleteById(entity.getId()));
    }

    return favoriteSymbols(userId);
  }

  private String normalizeSymbol(String symbol) {
    if (StrUtil.isBlank(symbol)) {
      throw new BusinessException("INVALID_SYMBOL", "Symbol is required");
    }
    return symbol.trim().toUpperCase(Locale.ROOT);
  }
}
