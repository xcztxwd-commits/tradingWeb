package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BinanceSubscriptionManagerTest {

  private final BinanceStreamCommandLimiterTest.MutableClock clock = new BinanceStreamCommandLimiterTest.MutableClock();
  private final FakeRealtimeClient client = new FakeRealtimeClient();
  private final MarketRealtimeProperties properties = new MarketRealtimeProperties();

  @Test
  void activateSendsOneBatchedSubscribeWithAllStreams() {
    BinanceSubscriptionManager manager = manager();

    manager.activateSymbol("BTCUSDT");

    assertThat(client.commands).containsExactly(new Command("SUBSCRIBE", List.of(
        "btcusdt@bookTicker",
        "btcusdt@ticker",
        "btcusdt@aggTrade",
        "btcusdt@depth20@100ms",
        "btcusdt@kline_1s",
        "btcusdt@kline_1m",
        "btcusdt@kline_5m",
        "btcusdt@kline_15m",
        "btcusdt@kline_1h",
        "btcusdt@kline_4h",
        "btcusdt@kline_1d")));
    assertThat(manager.activeSymbols()).containsExactly("BTCUSDT");
    assertThat(manager.desiredStreams()).hasSize(11);
  }

  @Test
  void repeatedActivateDoesNotSendDuplicateCommand() {
    BinanceSubscriptionManager manager = manager();

    manager.activateSymbol("BTCUSDT");
    manager.activateSymbol("btcusdt");

    assertThat(client.commands).hasSize(1);
  }

  @Test
  void deactivateWaitsForGraceBeforeUnsubscribe() {
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    client.commands.clear();

    manager.deactivateSymbol("BTCUSDT");
    manager.runMaintenance();

    assertThat(client.commands).isEmpty();
    assertThat(manager.activeSymbols()).isEmpty();
    assertThat(manager.desiredStreams()).isEmpty();

    clock.advanceMillis(properties.unsubscribeGrace().toMillis());
    manager.runMaintenance();

    assertThat(client.commands).hasSize(1);
    assertThat(client.commands.getFirst().method()).isEqualTo("UNSUBSCRIBE");
    assertThat(manager.activeSymbols()).isEmpty();
  }

  @Test
  void reactivationBeforeGraceCancelsUnsubscribe() {
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    client.commands.clear();

    manager.deactivateSymbol("BTCUSDT");
    manager.activateSymbol("BTCUSDT");
    clock.advanceMillis(properties.unsubscribeGrace().toMillis());
    manager.runMaintenance();

    assertThat(client.commands).isEmpty();
    assertThat(manager.activeSymbols()).containsExactly("BTCUSDT");
  }

  @Test
  void reactivationBeforeGraceStillRespectsActiveSymbolBudget() {
    properties.setMaxActiveSymbols(1);
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    manager.deactivateSymbol("BTCUSDT");
    manager.activateSymbol("ETHUSDT");

    manager.activateSymbol("BTCUSDT");

    assertThat(manager.activeSymbols()).containsExactly("ETHUSDT");
    assertThat(manager.snapshotStatus().rejectedSymbolCount()).isEqualTo(1);
  }

  @Test
  void deactivatedSymbolDoesNotConsumeActiveSymbolBudgetDuringGrace() {
    properties.setMaxActiveSymbols(1);
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    client.commands.clear();

    manager.deactivateSymbol("BTCUSDT");
    manager.activateSymbol("ETHUSDT");

    assertThat(manager.activeSymbols()).containsExactly("ETHUSDT");
    assertThat(manager.snapshotStatus().rejectedSymbolCount()).isZero();
    assertThat(client.commands).containsExactly(new Command("SUBSCRIBE", manager.desiredStreams().stream().toList()));
  }

  @Test
  void maintenanceFlushesQueuedControlCommands() {
    properties.setBinanceCommandPerSecond(1);
    BinanceSubscriptionManager manager = manager();

    manager.activateSymbol("BTCUSDT");
    manager.activateSymbol("ETHUSDT");

    assertThat(client.commands).hasSize(1);
    clock.advanceMillis(1000);
    manager.runMaintenance();

    assertThat(client.commands).hasSize(2);
    assertThat(client.commands.getLast().method()).isEqualTo("SUBSCRIBE");
  }

  @Test
  void queuedControlCommandsPreserveFifoWhenLaterOppositeCommandIsGenerated() {
    properties.setBinanceCommandPerSecond(1);
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    manager.activateSymbol("ETHUSDT");
    manager.deactivateSymbol("ETHUSDT");

    clock.advanceMillis(properties.unsubscribeGrace().toMillis());
    manager.runMaintenance();

    assertThat(client.commands).hasSize(2);
    assertThat(client.commands.get(1).method()).isEqualTo("SUBSCRIBE");
    assertThat(client.commands.get(1).params()).allMatch(stream -> stream.toString().startsWith("ethusdt@"));

    clock.advanceMillis(1000);
    manager.runMaintenance();

    assertThat(client.commands).hasSize(3);
    assertThat(client.commands.get(2).method()).isEqualTo("UNSUBSCRIBE");
    assertThat(client.commands.get(2).params()).allMatch(stream -> stream.toString().startsWith("ethusdt@"));
  }

  @Test
  void reconnectReplaysDesiredStreams() {
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    client.commands.clear();

    manager.onConnected();

    assertThat(client.commands).containsExactly(new Command("SUBSCRIBE", manager.desiredStreams().stream().toList()));
  }

  @Test
  void reconnectDoesNotReplayPendingUnsubscribeStreams() {
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    manager.deactivateSymbol("BTCUSDT");
    manager.activateSymbol("ETHUSDT");
    client.commands.clear();

    manager.onConnected();

    assertThat(client.commands).containsExactly(new Command("SUBSCRIBE", manager.desiredStreams().stream().toList()));
    assertThat(client.commands.getFirst().params()).allMatch(stream -> stream.toString().startsWith("ethusdt@"));
  }

  @Test
  void reconnectClearsQueuedCommandsBeforeReplayingDesiredStreams() {
    properties.setBinanceCommandPerSecond(1);
    BinanceSubscriptionManager manager = manager();
    manager.activateSymbol("BTCUSDT");
    manager.activateSymbol("ETHUSDT");
    manager.deactivateSymbol("ETHUSDT");
    client.commands.clear();

    manager.onConnected();

    assertThat(client.commands).isEmpty();

    clock.advanceMillis(1000);
    manager.runMaintenance();

    assertThat(client.commands).containsExactly(new Command("SUBSCRIBE", manager.desiredStreams().stream().toList()));
    assertThat(client.commands.getFirst().params()).allMatch(stream -> stream.toString().startsWith("btcusdt@"));

    clock.advanceMillis(1000);
    manager.runMaintenance();

    assertThat(client.commands).hasSize(1);
  }

  @Test
  void activationOverMaxActiveSymbolsIsRejectedAndReported() {
    properties.setMaxActiveSymbols(1);
    BinanceSubscriptionManager manager = manager();

    manager.activateSymbol("BTCUSDT");
    manager.activateSymbol("ETHUSDT");

    assertThat(manager.activeSymbols()).containsExactly("BTCUSDT");
    assertThat(manager.snapshotStatus().rejectedSymbolCount()).isEqualTo(1);
  }

  private BinanceSubscriptionManager manager() {
    return new BinanceSubscriptionManager(properties, client, clock);
  }

  private record Command(String method, List<?> params) {
  }

  private static class FakeRealtimeClient implements BinanceRealtimeControlClient {

    private final List<Command> commands = new ArrayList<>();

    @Override
    public void sendControlMessage(String method, List<?> params) {
      commands.add(new Command(method, List.copyOf(params)));
    }
  }
}
