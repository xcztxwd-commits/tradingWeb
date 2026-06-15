package com.fxplatform.chart.controller;

import com.fxplatform.chart.dto.CandleResponse;
import com.fxplatform.chart.service.ChartService;
import com.fxplatform.common.response.ApiResponse;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChartController 是图表 K 线模块的 REST API 控制器。
 */
@RestController
@RequestMapping("/api/chart")
@RequiredArgsConstructor
public class ChartController {

  private final ChartService chartService;

  /**
   * 处理 candles 查询接口请求。
   */
  @GetMapping("/candles")
  public ApiResponse<List<CandleResponse>> candles(
      @RequestParam String symbol,
      @RequestParam String timeframe,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
  ) {
    return ApiResponse.success(chartService.candles(symbol, timeframe, from, to));
  }
}
