package com.fxplatform.tradinglab.admin;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabConfigResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import java.util.UUID;

public interface TradingLabAdminService {

  TradingLabConfigResponse config();

  AdminPageResponse<TradingLabScenarioResponse> scenarios(int page, int size);

  TradingLabScenarioResponse scenario(UUID scenarioId);

  TradingLabScenarioResponse createScenario(
      UUID actorId,
      String clientIp,
      UUID requestId,
      TradingLabScenarioWriteRequest request);

  TradingLabScenarioResponse updateScenario(
      UUID scenarioId,
      UUID actorId,
      String clientIp,
      UUID requestId,
      TradingLabScenarioWriteRequest request);

  void deleteScenario(
      UUID scenarioId,
      UUID actorId,
      String clientIp,
      UUID requestId,
      long expectedVersion);

  TradingLabRunResponse createRun(
      UUID scenarioId,
      UUID actorId,
      String clientIp,
      UUID requestId,
      TradingLabRunCreateRequest request);

  TradingLabRunResponse run(UUID runId);
}
