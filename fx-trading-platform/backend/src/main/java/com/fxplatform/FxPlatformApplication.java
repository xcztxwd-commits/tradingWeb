package com.fxplatform;

import com.fxplatform.admin.service.AdminBootstrapProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动 FX/CFD 交易平台后端应用，并启用调度任务和配置属性。
 */
@EnableScheduling
@EnableConfigurationProperties(AdminBootstrapProperties.class)
@SpringBootApplication
@MapperScan("com.fxplatform.**.repository")
public class FxPlatformApplication {

  /**
   * 启动 Spring Boot 应用进程。
   */
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(FxPlatformApplication.class);
    application.setDefaultProperties(loadLocalDotenv(Path.of(System.getProperty("user.dir"))));
    application.run(args);
  }

  static Map<String, Object> loadLocalDotenv(Path workingDirectory) {
    Map<String, Object> values = new LinkedHashMap<>();
    loadDotenvFile(workingDirectory.resolve(".env"), values);
    Path parent = workingDirectory.getParent();
    if (parent != null) {
      loadDotenvFile(parent.resolve(".env"), values);
    }
    return values;
  }

  private static void loadDotenvFile(Path path, Map<String, Object> values) {
    if (!Files.isRegularFile(path)) {
      return;
    }
    try {
      for (String line : Files.readAllLines(path)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int separator = trimmed.indexOf('=');
        if (separator <= 0) {
          continue;
        }
        String key = trimmed.substring(0, separator).trim();
        String value = trimmed.substring(separator + 1).trim();
        if (!key.isEmpty()) {
          values.putIfAbsent(key, stripQuotes(value));
        }
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read " + path, ex);
    }
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
