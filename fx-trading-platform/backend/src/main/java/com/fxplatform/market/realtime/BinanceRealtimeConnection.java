package com.fxplatform.market.realtime;

import java.net.URI;
import java.nio.ByteBuffer;

public interface BinanceRealtimeConnection {

  void connect(URI uri, Listener listener);

  void sendText(String text);

  void sendPong(ByteBuffer payload);

  void close();

  interface Listener {

    void onOpen();

    void onText(String payload);

    void onPing(ByteBuffer payload);

    void onClose(int statusCode, String reason);

    void onError(Throwable error);
  }
}
