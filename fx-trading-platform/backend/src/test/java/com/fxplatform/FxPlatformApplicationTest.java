package com.fxplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FxPlatformApplicationTest {

  @TempDir
  private Path tempDir;

  @Test
  void loadLocalDotenvReadsWorkingDirectoryAndParentWithoutOverwritingNearestValues() throws Exception {
    Path appDir = Files.createDirectory(tempDir.resolve("backend"));
    Files.writeString(tempDir.resolve(".env"), """
        # parent env
        MASSIVE_API_KEY=parent-key
        MARKET_DEMO_QUOTES_ENABLED=false
        """);
    Files.writeString(appDir.resolve(".env"), """
        MASSIVE_API_KEY="local-key"
        MASSIVE_REST_BASE_URL=https://api.massive.com
        """);

    Map<String, Object> values = FxPlatformApplication.loadLocalDotenv(appDir);

    assertThat(values)
        .containsEntry("MASSIVE_API_KEY", "local-key")
        .containsEntry("MASSIVE_REST_BASE_URL", "https://api.massive.com")
        .containsEntry("MARKET_DEMO_QUOTES_ENABLED", "false");
  }
}
