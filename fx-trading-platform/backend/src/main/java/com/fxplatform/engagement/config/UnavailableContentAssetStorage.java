package com.fxplatform.engagement.config;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.application.content.ContentAssetStorage;

/** Fail-closed adapter used unless local development storage is explicitly selected. */
public final class UnavailableContentAssetStorage implements ContentAssetStorage {

  @Override
  public void put(String storageKey, byte[] bytes) {
    throw unavailable();
  }

  @Override
  public byte[] get(String storageKey, long maximumBytes) {
    throw unavailable();
  }

  @Override
  public void delete(String storageKey) {
    throw unavailable();
  }

  private static BusinessException unavailable() {
    return new BusinessException(
        "CONTENT_ASSET_STORAGE_UNAVAILABLE", "Content asset storage is unavailable");
  }
}
