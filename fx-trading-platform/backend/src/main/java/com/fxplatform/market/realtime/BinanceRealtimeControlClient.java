package com.fxplatform.market.realtime;

import java.util.List;

public interface BinanceRealtimeControlClient {

  void sendControlMessage(String method, List<?> params);
}
