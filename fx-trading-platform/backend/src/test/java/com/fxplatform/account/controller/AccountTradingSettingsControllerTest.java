package com.fxplatform.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.TradingSettingsResponse;
import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.service.TradingSettingsService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class AccountTradingSettingsControllerTest {

  @Test
  void routesMatchTheAccountTradingSettingsContract() throws Exception {
    RequestMapping root = AccountTradingSettingsController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/accounts");
    assertThat(AccountTradingSettingsController.class
        .getMethod("get", UserPrincipal.class, UUID.class)
        .getAnnotation(GetMapping.class).value())
        .containsExactly("/{accountId}/trading-settings");
    assertThat(AccountTradingSettingsController.class
        .getMethod("updatePositionMode", UserPrincipal.class, UUID.class, UpdatePositionModeRequest.class)
        .getAnnotation(PatchMapping.class).value())
        .containsExactly("/{accountId}/position-mode");
    assertThat(AccountTradingSettingsController.class
        .getMethod("updateSymbolSettings", UserPrincipal.class, UUID.class, String.class,
            UpdateSymbolSettingsRequest.class)
        .getAnnotation(PatchMapping.class).value())
        .containsExactly("/{accountId}/symbols/{symbol}/settings");
  }

  @Test
  void endpointsBindTheAuthenticatedOwnerAndReturnAtomicSettingsState() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.test", "TRADER");
    TradingSettingsService service = org.mockito.Mockito.mock(TradingSettingsService.class);
    AccountTradingSettingsController controller = new AccountTradingSettingsController(service);
    TradingSettingsResponse initial = response(accountId, PositionMode.ONE_WAY, 0L);
    TradingSettingsResponse hedge = response(accountId, PositionMode.HEDGE, 0L);
    TradingSettingsResponse updated = response(accountId, PositionMode.HEDGE, 1L);
    UpdatePositionModeRequest modeRequest = new UpdatePositionModeRequest(PositionMode.HEDGE);
    UpdateSymbolSettingsRequest symbolRequest = new UpdateSymbolSettingsRequest(
        20, MarginMode.ISOLATED, QuantityUnit.CONTRACTS, 0L);
    when(service.get(userId, accountId)).thenReturn(initial);
    when(service.updatePositionMode(userId, accountId, modeRequest)).thenReturn(hedge);
    when(service.updateSymbolSettings(userId, accountId, "BTCUSDT-PERP", symbolRequest))
        .thenReturn(updated);

    assertThat(controller.get(principal, accountId).data()).isEqualTo(initial);
    assertThat(controller.updatePositionMode(principal, accountId, modeRequest).data())
        .isEqualTo(hedge);
    assertThat(controller.updateSymbolSettings(
        principal, accountId, "BTCUSDT-PERP", symbolRequest).data()).isEqualTo(updated);
  }

  private static TradingSettingsResponse response(UUID accountId, PositionMode mode, long version) {
    return new TradingSettingsResponse(
        accountId,
        mode,
        List.of(new TradingSettingsResponse.SymbolSettings(
            "BTCUSDT-PERP", 10, MarginMode.CROSS, QuantityUnit.BASE, version, 100)));
  }
}
