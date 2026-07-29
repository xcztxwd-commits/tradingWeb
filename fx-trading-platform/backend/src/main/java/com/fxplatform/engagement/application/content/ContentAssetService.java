package com.fxplatform.engagement.application.content;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.persistence.entity.ContentAssetEntity;
import com.fxplatform.engagement.persistence.enums.ContentAssetStatus;
import com.fxplatform.engagement.persistence.repository.ContentAssetRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ContentAssetService {

  private static final String PUBLIC_PATH = "/api/public/engagement/assets/";
  private static final int MAX_DIMENSION = 16_384;
  private static final long MAX_PIXELS = 100_000_000L;
  private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
      "image/jpeg", "image/png", "image/webp");
  private static final Map<String, String> EXTENSIONS = Map.of(
      "image/jpeg", ".jpg",
      "image/png", ".png",
      "image/webp", ".webp");

  private final ContentAssetRepository repository;
  private final ContentAssetStorage storage;
  private final ContentAssetAuditPort audit;
  private final Clock clock;
  private final long maximumBytes;

  public ContentAssetService(
      ContentAssetRepository repository,
      ContentAssetStorage storage,
      ContentAssetAuditPort audit,
      Clock clock,
      @Value("${app.engagement.assets.max-bytes:5242880}") long maximumBytes
  ) {
    if (maximumBytes <= 0) {
      throw new IllegalArgumentException("maximumBytes must be positive");
    }
    this.repository = Objects.requireNonNull(repository, "repository");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maximumBytes = maximumBytes;
  }

  @Transactional
  public UploadResult upload(
      UUID actorUserId,
      String originalFilename,
      String declaredMimeType,
      byte[] bytes
  ) {
    if (actorUserId == null || bytes == null || bytes.length == 0) {
      throw new BusinessException("CONTENT_ASSET_INVALID", "Content asset is required");
    }
    if (bytes.length > maximumBytes) {
      throw new BusinessException("CONTENT_ASSET_TOO_LARGE", "Content asset is too large");
    }

    String mimeType = normalizeMimeType(declaredMimeType);
    InspectedImage inspected = inspect(bytes);
    if (!mimeType.equals(inspected.mimeType())) {
      throw invalidType();
    }

    UUID assetId = UUID.randomUUID();
    String storageKey = assetId + EXTENSIONS.get(mimeType);
    ContentAssetEntity entity = new ContentAssetEntity();
    entity.setId(assetId);
    entity.setStorageKey(storageKey);
    entity.setMimeType(mimeType);
    entity.setByteSize((long) bytes.length);
    entity.setWidth(inspected.width());
    entity.setHeight(inspected.height());
    entity.setSha256(sha256(bytes));
    entity.setStatus(ContentAssetStatus.ACTIVE);
    entity.setCreatedBy(actorUserId);
    entity.setCreatedAt(clock.instant());

    storage.put(storageKey, bytes);
    RollbackCleanup rollbackCleanup = new RollbackCleanup(storage, storageKey);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(rollbackCleanup);
    }
    try {
      if (repository.insert(entity) != 1) {
        throw new BusinessException(
            "CONTENT_ASSET_PERSIST_FAILED", "Content asset could not be saved");
      }
      audit.recordUpload(
          actorUserId,
          assetId,
          mimeType,
          bytes.length,
          inspected.width(),
          inspected.height());
    } catch (RuntimeException exception) {
      rollbackCleanup.deleteNow(exception);
      throw exception;
    }

    return new UploadResult(
        assetId,
        publicUrl(assetId),
        mimeType,
        bytes.length,
        inspected.width(),
        inspected.height());
  }

  @Transactional(readOnly = true)
  public AssetPayload load(UUID assetId) {
    if (assetId == null) {
      throw notFound();
    }
    ContentAssetEntity entity = repository.selectById(assetId);
    if (entity == null
        || entity.getStatus() != ContentAssetStatus.ACTIVE
        || !ALLOWED_MIME_TYPES.contains(entity.getMimeType())
        || entity.getByteSize() == null
        || entity.getByteSize() <= 0
        || entity.getByteSize() > maximumBytes
        || entity.getSha256() == null
        || !entity.getSha256().matches("[0-9a-f]{64}")) {
      throw notFound();
    }

    byte[] bytes;
    try {
      bytes = storage.get(entity.getStorageKey(), entity.getByteSize());
    } catch (RuntimeException exception) {
      throw new BusinessException(
          "CONTENT_ASSET_STORAGE_UNAVAILABLE", "Content asset is unavailable", exception);
    }
    if (bytes == null
        || bytes.length != entity.getByteSize()
        || !MessageDigest.isEqual(
            sha256(bytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            entity.getSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
      throw new BusinessException(
          "CONTENT_ASSET_INTEGRITY_INVALID", "Content asset is unavailable");
    }
    return new AssetPayload(entity.getMimeType(), entity.getByteSize(), bytes);
  }

  public static String publicUrl(UUID assetId) {
    return PUBLIC_PATH + assetId;
  }

  private static String normalizeMimeType(String declaredMimeType) {
    if (declaredMimeType == null) {
      throw invalidType();
    }
    String normalized = declaredMimeType.trim().toLowerCase(Locale.ROOT);
    if (!ALLOWED_MIME_TYPES.contains(normalized)) {
      throw invalidType();
    }
    return normalized;
  }

  private static InspectedImage inspect(byte[] bytes) {
    String mimeType = detectMimeType(bytes);
    Dimensions dimensions = "image/webp".equals(mimeType)
        ? webpDimensions(bytes)
        : imageIoDimensions(bytes, mimeType);
    if (dimensions.width() <= 0
        || dimensions.height() <= 0
        || dimensions.width() > MAX_DIMENSION
        || dimensions.height() > MAX_DIMENSION
        || (long) dimensions.width() * dimensions.height() > MAX_PIXELS) {
      throw invalidType();
    }
    return new InspectedImage(mimeType, dimensions.width(), dimensions.height());
  }

  private static String detectMimeType(byte[] bytes) {
    if (startsWith(bytes, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
      return "image/png";
    }
    if (startsWith(bytes, new int[]{0xff, 0xd8, 0xff})) {
      return "image/jpeg";
    }
    if (bytes.length >= 20
        && asciiEquals(bytes, 0, "RIFF")
        && asciiEquals(bytes, 8, "WEBP")) {
      return "image/webp";
    }
    throw invalidType();
  }

  private static Dimensions imageIoDimensions(byte[] bytes, String expectedMimeType) {
    try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (input == null) {
        throw invalidType();
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw invalidType();
      }
      ImageReader reader = readers.next();
      try {
        String expectedFormat = "image/png".equals(expectedMimeType) ? "png" : "JPEG";
        if (!reader.getFormatName().equalsIgnoreCase(expectedFormat)) {
          throw invalidType();
        }
        reader.setInput(input, true, true);
        return new Dimensions(reader.getWidth(0), reader.getHeight(0));
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof BusinessException businessException) {
        throw businessException;
      }
      throw new BusinessException(
          "CONTENT_ASSET_TYPE_INVALID", "Content asset must be a valid platform image", exception);
    }
  }

  private static Dimensions webpDimensions(byte[] bytes) {
    long riffSize = littleEndianUnsignedInt(bytes, 4);
    if (riffSize != bytes.length - 8L) {
      throw invalidType();
    }
    Dimensions canvas = null;
    Dimensions frame = null;
    int offset = 12;
    while (offset + 8 <= bytes.length) {
      String chunk = ascii(bytes, offset, 4);
      long chunkSizeLong = littleEndianUnsignedInt(bytes, offset + 4);
      if (chunkSizeLong > Integer.MAX_VALUE) {
        throw invalidType();
      }
      int chunkSize = (int) chunkSizeLong;
      int dataOffset = offset + 8;
      long endLong = (long) dataOffset + chunkSize;
      long paddedEndLong = endLong + (chunkSize & 1);
      if (endLong > bytes.length || paddedEndLong > bytes.length) {
        throw invalidType();
      }
      int end = (int) endLong;
      if ("VP8X".equals(chunk)) {
        if (canvas != null || chunkSize != 10 || (bytes[dataOffset] & 0x02) != 0) {
          throw invalidType();
        }
        canvas = new Dimensions(
            1 + littleEndian24(bytes, dataOffset + 4),
            1 + littleEndian24(bytes, dataOffset + 7));
      } else if ("VP8 ".equals(chunk)) {
        if (frame != null
            || chunkSize < 10
            || (bytes[dataOffset] & 1) != 0
            || (bytes[dataOffset + 3] & 0xff) != 0x9d
            || (bytes[dataOffset + 4] & 0xff) != 0x01
            || (bytes[dataOffset + 5] & 0xff) != 0x2a) {
          throw invalidType();
        }
        frame = new Dimensions(
            littleEndian16(bytes, dataOffset + 6) & 0x3fff,
            littleEndian16(bytes, dataOffset + 8) & 0x3fff);
      } else if ("VP8L".equals(chunk)) {
        if (frame != null
            || chunkSize < 5
            || (bytes[dataOffset] & 0xff) != 0x2f) {
          throw invalidType();
        }
        int b1 = bytes[dataOffset + 1] & 0xff;
        int b2 = bytes[dataOffset + 2] & 0xff;
        int b3 = bytes[dataOffset + 3] & 0xff;
        int b4 = bytes[dataOffset + 4] & 0xff;
        frame = new Dimensions(
            1 + b1 + ((b2 & 0x3f) << 8),
            1 + ((b2 & 0xc0) >> 6) + (b3 << 2) + ((b4 & 0x0f) << 10));
      }
      offset = (int) paddedEndLong;
    }
    if (offset != bytes.length || frame == null) {
      throw invalidType();
    }
    if (canvas != null && !canvas.equals(frame)) {
      throw invalidType();
    }
    return frame;
  }

  private static boolean startsWith(byte[] bytes, int[] signature) {
    if (bytes.length < signature.length) {
      return false;
    }
    for (int index = 0; index < signature.length; index++) {
      if ((bytes[index] & 0xff) != signature[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
    return offset >= 0
        && offset + expected.length() <= bytes.length
        && ascii(bytes, offset, expected.length()).equals(expected);
  }

  private static String ascii(byte[] bytes, int offset, int length) {
    return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
  }

  private static int littleEndian16(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
  }

  private static int littleEndian24(byte[] bytes, int offset) {
    return littleEndian16(bytes, offset) | ((bytes[offset + 2] & 0xff) << 16);
  }

  private static long littleEndianUnsignedInt(byte[] bytes, int offset) {
    if (offset < 0 || offset + 4 > bytes.length) {
      throw invalidType();
    }
    return (bytes[offset] & 0xffL)
        | ((bytes[offset + 1] & 0xffL) << 8)
        | ((bytes[offset + 2] & 0xffL) << 16)
        | ((bytes[offset + 3] & 0xffL) << 24);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK does not provide SHA-256", impossible);
    }
  }

  private static BusinessException invalidType() {
    return new BusinessException(
        "CONTENT_ASSET_TYPE_INVALID", "Content asset must be a valid JPG, PNG, or WebP image");
  }

  private static BusinessException notFound() {
    return new BusinessException("CONTENT_ASSET_NOT_FOUND", "Content asset not found");
  }

  private static final class RollbackCleanup implements TransactionSynchronization {

    private final ContentAssetStorage storage;
    private final String storageKey;
    private boolean deleted;

    private RollbackCleanup(ContentAssetStorage storage, String storageKey) {
      this.storage = storage;
      this.storageKey = storageKey;
    }

    private synchronized void deleteNow(RuntimeException originalFailure) {
      if (deleted) {
        return;
      }
      try {
        storage.delete(storageKey);
      } catch (RuntimeException cleanupFailure) {
        originalFailure.addSuppressed(cleanupFailure);
      } finally {
        deleted = true;
      }
    }

    @Override
    public void afterCompletion(int status) {
      if (status == STATUS_COMMITTED || deleted) {
        return;
      }
      try {
        storage.delete(storageKey);
      } finally {
        deleted = true;
      }
    }
  }

  private record Dimensions(int width, int height) {
  }

  private record InspectedImage(String mimeType, int width, int height) {
  }

  public record UploadResult(
      UUID assetId,
      String url,
      String mimeType,
      long byteSize,
      int width,
      int height
  ) {
  }

  public record AssetPayload(String mimeType, long byteSize, byte[] bytes) {

    public AssetPayload {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
