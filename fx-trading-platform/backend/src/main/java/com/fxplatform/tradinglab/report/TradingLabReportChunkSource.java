package com.fxplatform.tradinglab.report;

import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import java.util.UUID;
import org.apache.ibatis.cursor.Cursor;

public interface TradingLabReportChunkSource {

  TradingLabReportEntity lockReportForStream(UUID reportId);

  Cursor<TradingLabReportChunkEntity> openChunks(
      UUID reportId,
      TradingLabReportSection section);
}
