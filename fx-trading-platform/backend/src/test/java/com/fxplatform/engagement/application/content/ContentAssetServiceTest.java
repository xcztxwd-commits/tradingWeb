package com.fxplatform.engagement.application.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.config.ContentAssetStorageConfiguration;
import com.fxplatform.engagement.config.LocalContentAssetStorage;
import com.fxplatform.engagement.config.UnavailableContentAssetStorage;
import com.fxplatform.engagement.persistence.entity.ContentAssetEntity;
import com.fxplatform.engagement.persistence.enums.ContentAssetStatus;
import com.fxplatform.engagement.persistence.repository.ContentAssetRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ContentAssetServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T02:30:00Z");

  @Mock
  private ContentAssetRepository repository;

  @Mock
  private ContentAssetStorage storage;

  @Mock
  private ContentAssetAuditPort audit;

  @TempDir
  private Path tempDirectory;

  @Test
  void acceptsVerifiedPlatformImageAndPersistsOnlyTrustedMetadata() throws Exception {
    byte[] png = png(2, 3);
    UUID actorId = UUID.randomUUID();
    when(repository.insert(any(ContentAssetEntity.class))).thenReturn(1);

    var result = service(5 * 1024 * 1024L).upload(
        actorId,
        "../../private/secrets.svg",
        "image/png",
        png);

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
    ArgumentCaptor<ContentAssetEntity> entity =
        ArgumentCaptor.forClass(ContentAssetEntity.class);
    InOrder writes = inOrder(storage, repository, audit);
    writes.verify(storage).put(key.capture(), storedBytes.capture());
    writes.verify(repository).insert(entity.capture());
    writes.verify(audit).recordUpload(
        actorId, result.assetId(), "image/png", png.length, 2, 3);

    assertThat(storedBytes.getValue()).isEqualTo(png);
    assertThat(key.getValue())
        .isEqualTo(result.assetId() + ".png")
        .doesNotContain("..", "private", "secrets", "svg");
    assertThat(entity.getValue().getId()).isEqualTo(result.assetId());
    assertThat(entity.getValue().getStorageKey()).isEqualTo(key.getValue());
    assertThat(entity.getValue().getMimeType()).isEqualTo("image/png");
    assertThat(entity.getValue().getByteSize()).isEqualTo(png.length);
    assertThat(entity.getValue().getWidth()).isEqualTo(2);
    assertThat(entity.getValue().getHeight()).isEqualTo(3);
    assertThat(entity.getValue().getSha256()).matches("[0-9a-f]{64}");
    assertThat(entity.getValue().getStatus()).isEqualTo(ContentAssetStatus.ACTIVE);
    assertThat(entity.getValue().getCreatedBy()).isEqualTo(actorId);
    assertThat(entity.getValue().getCreatedAt()).isEqualTo(NOW);
    assertThat(result.url())
        .isEqualTo("/api/public/engagement/assets/" + result.assetId());
    assertThat(result.mimeType()).isEqualTo("image/png");
    assertThat(result.byteSize()).isEqualTo(png.length);
    assertThat(result.width()).isEqualTo(2);
    assertThat(result.height()).isEqualTo(3);
  }

  @Test
  void acceptsJpegPngAndWebpMagicAndDimensions() throws Exception {
    when(repository.insert(any(ContentAssetEntity.class))).thenReturn(1);
    byte[][] images = {jpeg(4, 5), png(2, 3), webp1x1()};
    String[] mimeTypes = {"image/jpeg", "image/png", "image/webp"};
    String[] extensions = {".jpg", ".png", ".webp"};
    int[][] dimensions = {{4, 5}, {2, 3}, {1, 1}};

    for (int index = 0; index < images.length; index++) {
      var uploaded = service(5 * 1024 * 1024L).upload(
          UUID.randomUUID(), "image" + extensions[index], mimeTypes[index], images[index]);

      assertThat(uploaded.mimeType()).isEqualTo(mimeTypes[index]);
      assertThat(uploaded.width()).isEqualTo(dimensions[index][0]);
      assertThat(uploaded.height()).isEqualTo(dimensions[index][1]);
      assertThat(uploaded.url()).endsWith(uploaded.assetId().toString());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsVp8xCanvasThatDoesNotMatchItsLossyOrLosslessFrame(boolean lossless) {
    byte[] hostileWebp = webpWithOneByOneCanvasAndHugeFrame(lossless);

    assertThatThrownBy(() -> service(5 * 1024 * 1024L).upload(
            UUID.randomUUID(), "hostile.webp", "image/webp", hostileWebp))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_TYPE_INVALID");
    verifyNoInteractions(repository, storage, audit);
  }

  @Test
  void rejectsDeclaredMimeThatDoesNotMatchImageMagicBeforeAnyWrite() throws Exception {
    assertThatThrownBy(() -> service(5 * 1024 * 1024L).upload(
            UUID.randomUUID(), "spoof.jpg", "image/jpeg", png(1, 1)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_TYPE_INVALID");

    verifyNoInteractions(repository, storage, audit);
  }

  @Test
  void rejectsSvgScriptAndOversizedPayloadsBeforeAnyWrite() {
    byte[] svgScript = "<svg><script>alert(1)</script></svg>"
        .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service(5 * 1024 * 1024L).upload(
            UUID.randomUUID(), "attack.svg", "image/svg+xml", svgScript))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_TYPE_INVALID");
    assertThatThrownBy(() -> service(8).upload(
            UUID.randomUUID(), "too-large.png", "image/png", new byte[9]))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_TOO_LARGE");

    verifyNoInteractions(repository, storage, audit);
  }

  @Test
  void removesStoredObjectAndEmitsNoAuditWhenDatabaseInsertFails() throws Exception {
    byte[] png = png(1, 1);
    when(repository.insert(any(ContentAssetEntity.class))).thenThrow(new IllegalStateException("db"));

    assertThatThrownBy(() -> service(5 * 1024 * 1024L).upload(
            UUID.randomUUID(), "safe.png", "image/png", png))
        .isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(storage).put(key.capture(), any(byte[].class));
    verify(storage).delete(key.getValue());
    verifyNoInteractions(audit);
  }

  @Test
  void removesStoredObjectWhenTransactionRollsBackDuringCommit() throws Exception {
    byte[] png = png(1, 1);
    when(repository.insert(any(ContentAssetEntity.class))).thenReturn(1);
    TransactionSynchronizationManager.initSynchronization();
    try {
      service(5 * 1024 * 1024L).upload(
          UUID.randomUUID(), "safe.png", "image/png", png);
      var synchronizations = TransactionSynchronizationManager.getSynchronizations();

      assertThat(synchronizations).hasSize(1);
      synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

      verify(storage).delete(any(String.class));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void emitsNoDatabaseWriteOrAuditWhenStorageFails() throws Exception {
    byte[] png = png(1, 1);
    org.mockito.Mockito.doThrow(new BusinessException(
            "CONTENT_ASSET_STORAGE_UNAVAILABLE", "disabled"))
        .when(storage).put(any(String.class), any(byte[].class));

    assertThatThrownBy(() -> service(5 * 1024 * 1024L).upload(
            UUID.randomUUID(), "safe.png", "image/png", png))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_STORAGE_UNAVAILABLE");

    verify(repository, never()).insert(any(ContentAssetEntity.class));
    verify(storage, never()).delete(any(String.class));
    verifyNoInteractions(audit);
  }

  @Test
  void servesOnlyActivePlatformImages() throws Exception {
    UUID assetId = UUID.randomUUID();
    byte[] png = png(1, 2);
    ContentAssetEntity active = asset(assetId, ContentAssetStatus.ACTIVE, "image/png", png.length);
    when(repository.selectById(assetId)).thenReturn(active);
    active.setSha256(sha256(png));
    when(storage.get(active.getStorageKey(), png.length)).thenReturn(png);

    var result = service(5 * 1024 * 1024L).load(assetId);

    assertThat(result.bytes()).isEqualTo(png);
    assertThat(result.mimeType()).isEqualTo("image/png");
    assertThat(result.byteSize()).isEqualTo(png.length);

    active.setStatus(ContentAssetStatus.DELETED);
    assertThatThrownBy(() -> service(5 * 1024 * 1024L).load(assetId))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_NOT_FOUND");
  }

  @Test
  void rejectsStoredObjectWhenLengthOrSha256NoLongerMatchesDatabaseMetadata()
      throws Exception {
    UUID assetId = UUID.randomUUID();
    byte[] png = png(1, 2);
    ContentAssetEntity active = asset(assetId, ContentAssetStatus.ACTIVE, "image/png", png.length);
    active.setSha256(sha256(png));
    when(repository.selectById(assetId)).thenReturn(active);
    when(storage.get(active.getStorageKey(), png.length))
        .thenReturn(java.util.Arrays.copyOf(png, png.length - 1), png);

    assertThatThrownBy(() -> service(5 * 1024 * 1024L).load(assetId))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_INTEGRITY_INVALID");

    active.setSha256("0".repeat(64));
    assertThatThrownBy(() -> service(5 * 1024 * 1024L).load(assetId))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_INTEGRITY_INVALID");
  }

  @Test
  void hidesStorageImplementationDetailsWhenPublicReadFails() throws Exception {
    UUID assetId = UUID.randomUUID();
    byte[] png = png(1, 1);
    ContentAssetEntity active = asset(assetId, ContentAssetStatus.ACTIVE, "image/png", png.length);
    active.setSha256(sha256(png));
    when(repository.selectById(assetId)).thenReturn(active);
    when(storage.get(active.getStorageKey(), png.length)).thenThrow(new BusinessException(
        "CONTENT_ASSET_STORAGE_KEY_INVALID",
        "C:\\private\\engagement-assets\\secret.png"));

    assertThatThrownBy(() -> service(5 * 1024 * 1024L).load(assetId))
        .isInstanceOf(BusinessException.class)
        .hasMessage("Content asset is unavailable")
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_STORAGE_UNAVAILABLE");
  }

  @Test
  void baseAndProductionStyleWiringFailClosedWhileDevelopmentLocalStorageIsEnabled()
      throws Exception {
    ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ContentAssetStorageConfiguration.class);
    byte[] png = png(1, 1);

    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(ContentAssetStorage.class);
      assertThat(context.getBean(ContentAssetStorage.class))
          .isInstanceOf(UnavailableContentAssetStorage.class);
      assertThatThrownBy(() -> new ContentAssetService(
              repository,
              context.getBean(ContentAssetStorage.class),
              audit,
              Clock.fixed(NOW, ZoneOffset.UTC),
              5 * 1024 * 1024L)
          .upload(UUID.randomUUID(), "safe.png", "image/png", png))
          .isInstanceOf(BusinessException.class)
          .extracting("code")
          .isEqualTo("CONTENT_ASSET_STORAGE_UNAVAILABLE");
    });
    contextRunner.withPropertyValues("app.engagement.assets.storage=disabled")
        .run(context -> assertThat(context.getBean(ContentAssetStorage.class))
            .isInstanceOf(UnavailableContentAssetStorage.class));
    contextRunner.withPropertyValues(
            "app.engagement.assets.storage=local",
            "app.engagement.assets.local-root="
                + tempDirectory.toString().replace('\\', '/'))
        .run(context -> assertThat(context.getBean(ContentAssetStorage.class))
            .isInstanceOf(LocalContentAssetStorage.class));
    contextRunner.withPropertyValues(
            "spring.profiles.active=prod",
            "app.engagement.assets.storage=local",
            "app.engagement.assets.local-root="
                + tempDirectory.toString().replace('\\', '/'))
        .run(context -> assertThat(context.getBean(ContentAssetStorage.class))
            .isInstanceOf(UnavailableContentAssetStorage.class));
  }

  @Test
  void localStorageRejectsTraversalKeysWithoutWritingOutsideItsRoot() {
    LocalContentAssetStorage local = new LocalContentAssetStorage(tempDirectory);

    assertThatThrownBy(() -> local.put("../escape.png", new byte[]{1}))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_STORAGE_KEY_INVALID");
    assertThat(tempDirectory.resolve("../escape.png").normalize()).doesNotExist();
  }

  @Test
  void localStorageWritesReadsAndDeletesTrustedGeneratedKeys() {
    LocalContentAssetStorage local = new LocalContentAssetStorage(tempDirectory);
    String key = UUID.randomUUID() + ".png";
    byte[] bytes = {1, 2, 3};

    local.put(key, bytes);

    assertThat(local.get(key, bytes.length)).isEqualTo(bytes);
    assertThat(tempDirectory.resolve(key)).hasBinaryContent(bytes);
    local.delete(key);
    assertThat(tempDirectory.resolve(key)).doesNotExist();
  }

  private ContentAssetService service(long maximumBytes) {
    return new ContentAssetService(
        repository,
        storage,
        audit,
        Clock.fixed(NOW, ZoneOffset.UTC),
        maximumBytes);
  }

  private static ContentAssetEntity asset(
      UUID id,
      ContentAssetStatus status,
      String mimeType,
      long byteSize
  ) {
    ContentAssetEntity entity = new ContentAssetEntity();
    entity.setId(id);
    entity.setStorageKey(id + ".png");
    entity.setMimeType(mimeType);
    entity.setByteSize(byteSize);
    entity.setWidth(1);
    entity.setHeight(2);
    entity.setSha256("0".repeat(64));
    entity.setStatus(status);
    return entity;
  }

  private static byte[] png(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThat(ImageIO.write(image, "png", output)).isTrue();
    return output.toByteArray();
  }

  private static byte[] jpeg(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThat(ImageIO.write(image, "jpeg", output)).isTrue();
    return output.toByteArray();
  }

  private static byte[] webp1x1() {
    return Base64.getDecoder().decode(
        "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
  }

  private static byte[] webpWithOneByOneCanvasAndHugeFrame(boolean lossless) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
    writeWebpChunk(body, "VP8X", new byte[10]);
    byte[] frame = lossless
        ? new byte[]{0x2f, (byte) 0xfe, (byte) 0xbf, (byte) 0xff, 0x0f}
        : new byte[]{
            0, 0, 0, (byte) 0x9d, 0x01, 0x2a,
            (byte) 0xff, 0x3f, (byte) 0xff, 0x3f};
    writeWebpChunk(body, lossless ? "VP8L" : "VP8 ", frame);

    ByteArrayOutputStream file = new ByteArrayOutputStream();
    file.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
    writeLittleEndianInt(file, body.size());
    file.writeBytes(body.toByteArray());
    return file.toByteArray();
  }

  private static void writeWebpChunk(
      ByteArrayOutputStream output,
      String fourCc,
      byte[] data
  ) {
    output.writeBytes(fourCc.getBytes(StandardCharsets.US_ASCII));
    writeLittleEndianInt(output, data.length);
    output.writeBytes(data);
    if ((data.length & 1) != 0) {
      output.write(0);
    }
  }

  private static void writeLittleEndianInt(ByteArrayOutputStream output, int value) {
    output.write(value & 0xff);
    output.write((value >>> 8) & 0xff);
    output.write((value >>> 16) & 0xff);
    output.write((value >>> 24) & 0xff);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
