package com.fxplatform.engagement.web;

import com.fxplatform.engagement.application.content.ContentAssetService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/engagement/assets")
@RequiredArgsConstructor
public class ContentAssetController {

  private final ContentAssetService service;

  @GetMapping("/{assetId}")
  public ResponseEntity<byte[]> asset(@PathVariable("assetId") UUID assetId) {
    var payload = service.load(assetId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(payload.mimeType()))
        .contentLength(payload.byteSize())
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(payload.bytes());
  }
}
