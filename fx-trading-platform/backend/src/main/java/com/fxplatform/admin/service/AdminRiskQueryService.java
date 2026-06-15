package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.response.AdminRiskConfigResponse;
import com.fxplatform.risk.repository.RiskConfigRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 后台风控配置只读查询服务。
 */
@Service
@RequiredArgsConstructor
public class AdminRiskQueryService {

  /** 风控配置 Mapper，用于读取 PostgreSQL risk.risk_configs。 */
  private final RiskConfigRepository riskConfigRepository;

  /** 查询全部风控配置并映射为后台 DTO。 */
  public List<AdminRiskConfigResponse> configs() {
    return riskConfigRepository.findAll().stream()
        .map(AdminRiskConfigResponse::from)
        .toList();
  }
}
