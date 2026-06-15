package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.market.entity.UserFavoriteSymbolEntity;
import com.fxplatform.market.repository.UserFavoriteSymbolRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserFavoriteSymbolServiceTest {

  @Mock
  private UserFavoriteSymbolRepository favoriteSymbolRepository;

  @Test
  void setFavoriteAddsNormalizedSymbolAndReturnsCurrentFavorites() {
    UUID userId = UUID.randomUUID();
    when(favoriteSymbolRepository.findByUserIdAndSymbol(userId, "BTCUSDT")).thenReturn(Optional.empty());
    when(favoriteSymbolRepository.findByUserIdOrderByCreatedAtAsc(userId))
        .thenReturn(List.of(favorite(userId, "EURUSD"), favorite(userId, "BTCUSDT")));
    UserFavoriteSymbolService service = new UserFavoriteSymbolService(favoriteSymbolRepository);

    List<String> favorites = service.setFavorite(userId, "btcusdt", true);

    ArgumentCaptor<UserFavoriteSymbolEntity> captor = ArgumentCaptor.forClass(UserFavoriteSymbolEntity.class);
    verify(favoriteSymbolRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    assertThat(captor.getValue().getSymbol()).isEqualTo("BTCUSDT");
    assertThat(favorites).containsExactly("EURUSD", "BTCUSDT");
  }

  @Test
  void setFavoriteRemovesNormalizedSymbolAndReturnsRemainingFavorites() {
    UUID userId = UUID.randomUUID();
    UserFavoriteSymbolEntity existing = favorite(userId, "EURUSD");
    when(favoriteSymbolRepository.findByUserIdAndSymbol(userId, "EURUSD")).thenReturn(Optional.of(existing));
    when(favoriteSymbolRepository.findByUserIdOrderByCreatedAtAsc(userId))
        .thenReturn(List.of(favorite(userId, "BTCUSDT")));
    UserFavoriteSymbolService service = new UserFavoriteSymbolService(favoriteSymbolRepository);

    List<String> favorites = service.setFavorite(userId, "eurusd", false);

    verify(favoriteSymbolRepository).deleteById(existing.getId());
    assertThat(favorites).containsExactly("BTCUSDT");
  }

  private UserFavoriteSymbolEntity favorite(UUID userId, String symbol) {
    UserFavoriteSymbolEntity entity = new UserFavoriteSymbolEntity();
    entity.setId(UUID.randomUUID());
    entity.setUserId(userId);
    entity.setSymbol(symbol);
    return entity;
  }
}
