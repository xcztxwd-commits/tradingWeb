package com.fxplatform.tradinglab.e2e;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.report.TradingLabReportChunkCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves that the real coordinator chain can create and stream a report larger than 50 MiB.
 *
 * <p>The scenario enters through the public Admin API and every report byte is produced by the
 * coordinator/validation evidence path. This test deliberately does not inject report rows,
 * append synthetic padding, or call a report writer directly.</p>
 */
class TradingLabLargeCoordinatorHttpIT extends TradingLabHttpIntegrationSupport {

  private static final int ACTION_COUNT = 40;
  private static final int CHUNK_BYTES = 16 * 1024;
  private static final long FIFTY_MIB = 50L * 1024L * 1024L;
  private static final long MAX_REPORT_BYTES = 128L * 1024L * 1024L;
  private static final TradingLabReportChunkCodec CHUNK_CODEC =
      new TradingLabReportChunkCodec(CHUNK_BYTES);

  @Test
  @Timeout(value = 25, unit = MINUTES)
  void realCoordinatorProducesAndStreamsMoreThanFiftyMiB() throws Exception {
    var fixture =
        TradingLabHttpScenarioFactory.largeCoordinator(currentConfig(), ACTION_COUNT);
    JsonNode scenario = createScenario(fixture.scenarioRequest());
    UUID scenarioId = UUID.fromString(scenario.path("id").asText());
    long scenarioVersion = scenario.path("version").asLong();

    RunView accepted = startRun(scenarioId, fixture.runRequest(scenarioVersion));
    UUID runId = accepted.id();
    try {
      assertThat(accepted.totalTicks()).isEqualTo(fixture.totalTicks());
      RunView terminal = awaitTerminal(runId, Duration.ofMinutes(18));
      RunFailure runFailure = queryMainDatabase(
          connection -> readRunFailure(connection, runId));
      assertThat(terminal.state())
          .as(
              "large coordinator run apiFailureCode=%s dbFailureCode=%s "
                  + "dbFailureMessage=%s transitions=%s latestHttp=%s",
              terminal.failureCode(),
              runFailure.code(),
              runFailure.message(),
              runFailure.transitions(),
              runFailure.latestHttp())
          .isEqualTo("COMPLETED");
      assertThat(terminal.processedTicks())
          .isEqualTo(terminal.totalTicks())
          .isEqualTo(fixture.totalTicks());

      JsonNode detail = reportDetail(terminal.reportId());
      ReportRow report = queryMainDatabase(
          connection -> readReport(connection, terminal.reportId()));
      ChunkAudit chunks = queryMainDatabase(
          connection -> auditChunks(connection, terminal.reportId()));

      assertThat(report.status()).isEqualTo("COMPLETED");
      assertThat(report.uncompressedBytes())
          .isEqualTo(detail.path("uncompressedBytes").asLong())
          .isGreaterThan(FIFTY_MIB)
          .isLessThan(MAX_REPORT_BYTES);
      assertThat(report.compressedBytes()).isEqualTo(chunks.compressedBytes());
      assertThat(report.chunkCount()).isEqualTo(chunks.chunkCount());
      assertThat(chunks.section("MARKET_TICKS").chunkCount()).isGreaterThan(1);
      assertThat(chunks.section("API_TRACE").chunkCount()).isGreaterThan(1);

      TradingLabReportStreamingProbe.Result streamed;
      try (ReportDownload download =
               openReportDownload(terminal.reportId(), Duration.ofMinutes(5))) {
        streamed = TradingLabReportStreamingProbe.check(
            download.body(),
            FIFTY_MIB + 1L);
      }
      assertThat(streamed.totalBytes()).isEqualTo(report.uncompressedBytes());
      assertThat(streamed.apiTraceItems()).isGreaterThan(ACTION_COUNT);
      assertThat(streamed.realHttpCategories())
          .containsExactlyInAnyOrder(
              "validation-run",
              "orders",
              "wallet",
              "ledger");

      System.out.printf(
          java.util.Locale.ROOT,
          "TRADING_LAB_REAL_LARGE_REPORT_EVIDENCE "
              + "{\"runId\":\"%s\",\"reportId\":\"%s\",\"reportBytes\":%d,"
              + "\"reportChunks\":%d,\"apiTraceChunks\":%d,"
              + "\"marketTickChunks\":%d,\"peakHeapDeltaBytes\":%d,"
              + "\"peakRssDeltaBytes\":%d}%n",
          runId,
          terminal.reportId(),
          report.uncompressedBytes(),
          report.chunkCount(),
          chunks.section("API_TRACE").chunkCount(),
          chunks.section("MARKET_TICKS").chunkCount(),
          streamed.peakHeapDeltaBytes(),
          streamed.peakRssDeltaBytes());
    } finally {
      cancelAndRetainIfActive(runId);
    }
  }

  private static ReportRow readReport(Connection connection, UUID reportId)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("""
        select status, uncompressed_bytes, compressed_bytes, chunk_count
        from trading_lab.reports
        where id = ?
        """)) {
      statement.setObject(1, reportId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).as("large report row").isTrue();
        ReportRow report = new ReportRow(
            rows.getString("status"),
            rows.getLong("uncompressed_bytes"),
            rows.getLong("compressed_bytes"),
            rows.getInt("chunk_count"));
        assertThat(rows.next()).as("exactly one large report row").isFalse();
        return report;
      }
    }
  }

  private static RunFailure readRunFailure(Connection connection, UUID runId)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("""
        select failure_code,
               failure_message,
               (
                 select coalesce(string_agg(
                   transition.from_state || '->' || transition.to_state
                     || ':' || coalesce(transition.reason, ''),
                   ' | ' order by transition.run_version
                 ), '')
                 from trading_lab.run_transitions transition
                 where transition.run_id = run_row.id
               ) transitions,
               (
                 select left(event.payload_json::text, 4000)
                 from trading_lab.run_events event
                 where event.run_id = run_row.id
                   and event.event_type = 'VALIDATION_HTTP_RESULT'
                 order by event.sequence desc
                 limit 1
               ) latest_http
        from trading_lab.runs run_row
        where run_row.id = ?
        """)) {
      statement.setObject(1, runId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).as("large run diagnostic row").isTrue();
        RunFailure failure = new RunFailure(
            rows.getString("failure_code"),
            rows.getString("failure_message"),
            rows.getString("transitions"),
            rows.getString("latest_http"));
        assertThat(rows.next()).as("exactly one large run diagnostic row").isFalse();
        return failure;
      }
    }
  }

  private static ChunkAudit auditChunks(Connection connection, UUID reportId)
      throws Exception {
    LinkedHashMap<String, MutableSectionStats> sections = new LinkedHashMap<>();
    long compressedBytes = 0L;
    int chunkCount = 0;
    try (PreparedStatement statement = connection.prepareStatement("""
        select id,
               report_id,
               section,
               sequence,
               encoding,
               uncompressed_bytes,
               compressed_bytes,
               payload,
               checksum
        from trading_lab.report_chunks
        where report_id = ?
        order by section, sequence
        """)) {
      statement.setFetchSize(32);
      statement.setObject(1, reportId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String section = rows.getString("section");
          MutableSectionStats stats =
              sections.computeIfAbsent(section, ignored -> new MutableSectionStats());
          TradingLabReportChunkEntity chunk = new TradingLabReportChunkEntity();
          chunk.setId(rows.getObject("id", UUID.class));
          chunk.setReportId(rows.getObject("report_id", UUID.class));
          chunk.setSection(section);
          chunk.setSequence(rows.getLong("sequence"));
          chunk.setEncoding(rows.getString("encoding"));
          chunk.setUncompressedBytes(rows.getLong("uncompressed_bytes"));
          chunk.setCompressedBytes(rows.getLong("compressed_bytes"));
          chunk.setPayload(rows.getBytes("payload"));
          chunk.setChecksum(rows.getString("checksum"));

          assertThat(chunk.getEncoding()).isEqualTo("GZIP");
          assertThat(chunk.getSequence()).isEqualTo(stats.chunkCount);
          byte[] decoded = CHUNK_CODEC.decodeAndVerify(chunk);
          assertThat(decoded.length)
              .isPositive()
              .isLessThanOrEqualTo(CHUNK_BYTES);
          stats.chunkCount++;
          stats.uncompressedBytes =
              Math.addExact(stats.uncompressedBytes, decoded.length);
          compressedBytes =
              Math.addExact(compressedBytes, chunk.getCompressedBytes());
          chunkCount = Math.incrementExact(chunkCount);
        }
      }
    }
    LinkedHashMap<String, SectionStats> immutable = new LinkedHashMap<>();
    sections.forEach((section, stats) -> immutable.put(
        section,
        new SectionStats(stats.chunkCount, stats.uncompressedBytes)));
    return new ChunkAudit(Map.copyOf(immutable), chunkCount, compressedBytes);
  }

  private record ReportRow(
      String status,
      long uncompressedBytes,
      long compressedBytes,
      int chunkCount
  ) {
  }

  private record RunFailure(
      String code,
      String message,
      String transitions,
      String latestHttp
  ) {
  }

  private record SectionStats(int chunkCount, long uncompressedBytes) {
  }

  private record ChunkAudit(
      Map<String, SectionStats> sections,
      int chunkCount,
      long compressedBytes
  ) {

    private SectionStats section(String name) {
      SectionStats value = sections.get(name);
      assertThat(value).as(name + " section").isNotNull();
      return value;
    }
  }

  private static final class MutableSectionStats {

    private int chunkCount;
    private long uncompressedBytes;
  }
}
