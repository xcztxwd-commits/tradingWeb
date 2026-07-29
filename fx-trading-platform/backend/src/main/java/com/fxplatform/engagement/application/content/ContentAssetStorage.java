package com.fxplatform.engagement.application.content;

/** Stores opaque content-asset bytes under service-generated keys. */
public interface ContentAssetStorage {

  void put(String storageKey, byte[] bytes);

  byte[] get(String storageKey, long maximumBytes);

  void delete(String storageKey);
}
