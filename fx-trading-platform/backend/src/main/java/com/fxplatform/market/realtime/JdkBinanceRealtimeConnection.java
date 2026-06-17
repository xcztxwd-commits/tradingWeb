package com.fxplatform.market.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public class JdkBinanceRealtimeConnection implements BinanceRealtimeConnection {

  private final HttpClient httpClient;
  private volatile WebSocket webSocket;

  public JdkBinanceRealtimeConnection() {
    this(HttpClient.newHttpClient());
  }

  JdkBinanceRealtimeConnection(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public void connect(URI uri, Listener listener) {
    httpClient.newWebSocketBuilder()
        .buildAsync(uri, new WebSocket.Listener() {
          private final StringBuilder textBuffer = new StringBuilder();

          @Override
          public void onOpen(WebSocket webSocket) {
            JdkBinanceRealtimeConnection.this.webSocket = webSocket;
            listener.onOpen();
            WebSocket.Listener.super.onOpen(webSocket);
          }

          @Override
          public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
              listener.onText(textBuffer.toString());
              textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            listener.onPing(message);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClose(statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
          }

          @Override
          public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
          }
        })
        .whenComplete((webSocket, error) -> {
          if (error != null) {
            listener.onError(unwrap(error));
          }
        });
  }

  @Override
  public void sendText(String text) {
    WebSocket current = webSocket;
    if (current != null) {
      current.sendText(text, true);
    }
  }

  @Override
  public void sendPong(ByteBuffer payload) {
    WebSocket current = webSocket;
    if (current != null) {
      current.sendPong(payload);
    }
  }

  @Override
  public void close() {
    WebSocket current = webSocket;
    if (current != null) {
      current.sendClose(WebSocket.NORMAL_CLOSURE, "rotate");
    }
  }

  private Throwable unwrap(Throwable error) {
    if (error instanceof CompletionException completionException && completionException.getCause() != null) {
      return completionException.getCause();
    }
    return error;
  }
}
