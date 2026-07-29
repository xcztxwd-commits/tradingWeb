package com.fxplatform.engagement.application.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentCta;
import com.fxplatform.engagement.application.content.ContentRevisionService.ContentDraft;
import com.fxplatform.engagement.domain.content.ContentDocumentSanitizer;
import com.fxplatform.engagement.persistence.entity.ContentAssetEntity;
import com.fxplatform.engagement.persistence.entity.ContentItemEntity;
import com.fxplatform.engagement.persistence.entity.ContentRevisionEntity;
import com.fxplatform.engagement.persistence.enums.ContentAssetStatus;
import com.fxplatform.engagement.persistence.enums.ContentKind;
import com.fxplatform.engagement.persistence.repository.ContentAssetRepository;
import com.fxplatform.engagement.persistence.repository.ContentItemRepository;
import com.fxplatform.engagement.persistence.repository.ContentRevisionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.ibatis.annotations.Select;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ContentRevisionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-19T08:30:00Z");

  @Mock
  private ContentItemRepository contentItemRepository;

  @Mock
  private ContentRevisionRepository contentRevisionRepository;

  @Mock
  private ContentAssetRepository contentAssetRepository;

  @BeforeEach
  void activePlatformImagesByDefault() {
    org.mockito.Mockito.lenient()
        .when(contentAssetRepository.selectById(any(UUID.class)))
        .thenAnswer(invocation -> activeAsset(invocation.getArgument(0), "image/png"));
  }

  @Test
  void productionConstructorRequiresTheApplicationClock() {
    var constructors = ContentRevisionService.class.getConstructors();

    assertThat(constructors).hasSize(1);
    assertThat(constructors[0].getParameterTypes()).containsExactly(
        ContentItemRepository.class,
        ContentRevisionRepository.class,
        ContentAssetRepository.class,
        ContentDocumentSanitizer.class,
        Clock.class);
  }

  @Test
  void createsItemThenImmutableRevisionThenAtomicallySwitchesCurrentRevision() throws Exception {
    UUID actorId = UUID.randomUUID();
    UUID imageAssetId = UUID.randomUUID();
    when(contentItemRepository.insert(any(ContentItemEntity.class))).thenReturn(1);
    when(contentRevisionRepository.insert(any(ContentRevisionEntity.class))).thenReturn(1);
    when(contentItemRepository.updateById(any(ContentItemEntity.class))).thenReturn(1);

    ContentRevisionService service = service();
    var saved = service.create(
        ContentKind.POPUP_CAMPAIGN,
        actorId,
        new ContentDraft(
            "  风险提示  ",
            richDocument(imageAssetId),
            imageAssetId,
            new ContentCta("去交易", "TRADE_SPOT", "{\"symbol\":\"BTCUSDT\"}")));

    ArgumentCaptor<ContentItemEntity> item = ArgumentCaptor.forClass(ContentItemEntity.class);
    ArgumentCaptor<ContentRevisionEntity> revision =
        ArgumentCaptor.forClass(ContentRevisionEntity.class);
    InOrder writes = inOrder(contentItemRepository, contentRevisionRepository);
    writes.verify(contentItemRepository).insert(item.capture());
    writes.verify(contentRevisionRepository).insert(revision.capture());
    writes.verify(contentItemRepository).updateById(item.getValue());

    assertThat(item.getValue().getId()).isEqualTo(saved.contentItemId());
    assertThat(item.getValue().getCurrentRevisionId()).isEqualTo(saved.revisionId());
    assertThat(item.getValue().getContentKind()).isEqualTo(ContentKind.POPUP_CAMPAIGN);
    assertThat(item.getValue().getCreatedAt()).isEqualTo(NOW);
    assertThat(item.getValue().getUpdatedAt()).isEqualTo(NOW);

    ContentRevisionEntity persisted = revision.getValue();
    assertThat(persisted.getId()).isEqualTo(saved.revisionId());
    assertThat(persisted.getContentItemId()).isEqualTo(saved.contentItemId());
    assertThat(persisted.getRevisionNo()).isEqualTo(1);
    assertThat(persisted.getTitle()).isEqualTo("风险提示");
    assertThat(persisted.getCreatedBy()).isEqualTo(actorId);
    assertThat(persisted.getCreatedAt()).isEqualTo(NOW);
    assertThat(persisted.getBodyDocument()).doesNotContain("style", "onclick", "src");
    assertThat(persisted.getSanitizedHtml())
        .contains("<h2 class=\"align-center\">")
        .contains("<strong><span data-color=\"#ff0000\">风险&lt;script&gt;</span></strong>")
        .contains("<ul><li><p>仅使用白名单格式</p></li></ul>")
        .contains("<img data-asset-id=\"" + imageAssetId + "\" alt=\"平台图片\">")
        .doesNotContain("<script", "<iframe", "javascript:", "style=", "onclick=", "src=");
    assertThat(persisted.getCtaLabel()).isEqualTo("去交易");
    assertThat(persisted.getCtaRouteKey()).isEqualTo("TRADE_SPOT");
    assertThat(persisted.getCtaParams()).isEqualTo("{\"symbol\":\"BTCUSDT\"}");
    verify(contentAssetRepository).selectById(imageAssetId);
    assertThat(ContentRevisionService.class.getMethod(
            "create", ContentKind.class, UUID.class, ContentDraft.class)
        .isAnnotationPresent(Transactional.class)).isTrue();
  }

  @Test
  void revisingLocksItemAndInsertsNextRevisionWithoutUpdatingHistory() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID oldRevisionId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    ContentItemEntity item = item(itemId, oldRevisionId);
    ContentRevisionEntity oldRevision = revision(itemId, oldRevisionId, 7, "旧内容");
    when(contentItemRepository.selectByIdForUpdate(itemId)).thenReturn(item);
    when(contentRevisionRepository.selectById(oldRevisionId)).thenReturn(oldRevision);
    when(contentRevisionRepository.insert(any(ContentRevisionEntity.class))).thenReturn(1);
    when(contentItemRepository.updateById(item)).thenReturn(1);

    var saved = service().revise(
        itemId,
        actorId,
        new ContentDraft("新内容", plainDocument("下一次展示使用新修订"), null, null));

    ArgumentCaptor<ContentRevisionEntity> inserted =
        ArgumentCaptor.forClass(ContentRevisionEntity.class);
    verify(contentRevisionRepository).insert(inserted.capture());
    verify(contentRevisionRepository, never()).updateById(any(ContentRevisionEntity.class));
    verify(contentItemRepository, never()).selectById(itemId);
    Select lockingQuery = ContentItemRepository.class
        .getMethod("selectByIdForUpdate", UUID.class)
        .getAnnotation(Select.class);
    assertThat(String.join(" ", lockingQuery.value()).toUpperCase()).contains("FOR UPDATE");
    assertThat(inserted.getValue().getRevisionNo()).isEqualTo(8);
    assertThat(inserted.getValue().getContentItemId()).isEqualTo(itemId);
    assertThat(inserted.getValue().getCreatedAt()).isEqualTo(NOW);
    assertThat(item.getCurrentRevisionId()).isEqualTo(saved.revisionId());
    assertThat(item.getUpdatedAt()).isEqualTo(NOW);
    assertThat(oldRevision.getId()).isEqualTo(oldRevisionId);
    assertThat(oldRevision.getRevisionNo()).isEqualTo(7);
    assertThat(oldRevision.getTitle()).isEqualTo("旧内容");
  }

  @Test
  void textLookingLikeHtmlIsEscapedAndInternalLinksCarryNoExecutableHref() {
    UUID itemId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    ContentItemEntity item = item(itemId, revisionId);
    when(contentItemRepository.selectByIdForUpdate(itemId)).thenReturn(item);
    when(contentRevisionRepository.selectById(revisionId))
        .thenReturn(revision(itemId, revisionId, 1, "v1"));
    when(contentRevisionRepository.insert(any(ContentRevisionEntity.class))).thenReturn(1);
    when(contentItemRepository.updateById(item)).thenReturn(1);
    String document = """
        {"type":"doc","content":[{"type":"paragraph","content":[
          {"type":"text","text":"<iframe src='https://evil.example'></iframe>"},
          {"type":"text","text":"钱包","marks":[{"type":"link","attrs":{"routeKey":"WALLET","params":{}}}]}
        ]}]}
        """;

    service().revise(itemId, UUID.randomUUID(), new ContentDraft("安全内容", document, null, null));

    ArgumentCaptor<ContentRevisionEntity> inserted =
        ArgumentCaptor.forClass(ContentRevisionEntity.class);
    verify(contentRevisionRepository).insert(inserted.capture());
    assertThat(inserted.getValue().getSanitizedHtml())
        .contains("&lt;iframe src=&#39;https://evil.example&#39;&gt;&lt;/iframe&gt;")
        .contains("<a data-route-key=\"WALLET\" data-route-params=\"{}\">钱包</a>")
        .doesNotContain("<iframe", "href=", "javascript:");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<p onclick=\"steal()\">raw HTML</p>",
      "{\"type\":\"doc\",\"content\":[{\"type\":\"script\",\"content\":[]}]}",
      "{\"type\":\"doc\",\"content\":[{\"type\":\"iframe\",\"attrs\":{\"src\":\"https://evil.example\"}}]}",
      "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"style\":\"background:url(https://evil.example)\"}}]}",
      "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"bold\",\"attrs\":{\"onclick\":\"steal()\"}}]}]}]}",
      "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"assetId\":\"data:image/png;base64,AAAA\"}}]}",
      "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"assetId\":\"00000000-0000-0000-0000-000000000001\",\"src\":\"https://evil.example/a.png\"}}]}"
  })
  void rejectsRawOrNonWhitelistedHtmlAndImageInputs(String hostileDocument) {
    assertThatThrownBy(() -> service().create(
            ContentKind.MESSAGE,
            UUID.randomUUID(),
            new ContentDraft("安全检查", hostileDocument, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_DOCUMENT_INVALID");

    verifyNoInteractions(contentItemRepository, contentRevisionRepository);
  }

  @Test
  void rejectsExternalOrUnknownCtaAndUnvalidatedParametersBeforeWriting() {
    List<ContentCta> invalid = List.of(
        new ContentCta("外链", "https://evil.example", "{}"),
        new ContentCta("未知", "ARBITRARY_PAGE", "{}"),
        new ContentCta("跳转", "WALLET", "{\"redirect\":\"https://evil.example\"}"),
        new ContentCta("交易", "TRADE_SPOT", "{\"symbol\":\"BTC/USDT?<script>\"}"),
        new ContentCta("交易", "TRADE_SPOT", "[]"));

    for (ContentCta cta : invalid) {
      assertThatThrownBy(() -> service().create(
              ContentKind.POPUP_CAMPAIGN,
              UUID.randomUUID(),
              new ContentDraft("CTA", plainDocument("正文"), null, cta)))
          .isInstanceOf(BusinessException.class)
          .extracting("code")
          .isEqualTo("CONTENT_CTA_INVALID");
    }
    verifyNoInteractions(contentItemRepository, contentRevisionRepository);
  }

  @Test
  void rejectsMissingDeletedOrNonPlatformImageAssetsBeforeAnyContentWrite() {
    UUID missingCover = UUID.randomUUID();
    when(contentAssetRepository.selectById(missingCover)).thenReturn(null);
    assertUnavailableAsset(new ContentDraft(
        "missing", plainDocument("正文"), missingCover, null));

    UUID deletedBodyImage = UUID.randomUUID();
    ContentAssetEntity deleted = activeAsset(deletedBodyImage, "image/webp");
    deleted.setStatus(ContentAssetStatus.DELETED);
    deleted.setDeletedAt(NOW);
    when(contentAssetRepository.selectById(deletedBodyImage)).thenReturn(deleted);
    assertUnavailableAsset(new ContentDraft(
        "deleted", imageDocument(deletedBodyImage), null, null));

    UUID unsupportedMime = UUID.randomUUID();
    when(contentAssetRepository.selectById(unsupportedMime))
        .thenReturn(activeAsset(unsupportedMime, "image/svg+xml"));
    assertUnavailableAsset(new ContentDraft(
        "svg", imageDocument(unsupportedMime), null, null));

    verifyNoInteractions(contentItemRepository, contentRevisionRepository);
  }

  @Test
  void rejectsMissingItemAndCrossItemCurrentRevision() {
    UUID missingId = UUID.randomUUID();
    when(contentItemRepository.selectByIdForUpdate(missingId)).thenReturn(null);

    assertThatThrownBy(() -> service().revise(
            missingId,
            UUID.randomUUID(),
            new ContentDraft("标题", plainDocument("正文"), null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ITEM_NOT_FOUND");

    UUID itemId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    when(contentItemRepository.selectByIdForUpdate(itemId)).thenReturn(item(itemId, revisionId));
    when(contentRevisionRepository.selectById(revisionId))
        .thenReturn(revision(UUID.randomUUID(), revisionId, 1, "other"));

    assertThatThrownBy(() -> service().revise(
            itemId,
            UUID.randomUUID(),
            new ContentDraft("标题", plainDocument("正文"), null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_REVISION_INVALID");
  }

  private ContentRevisionService service() {
    return new ContentRevisionService(
        contentItemRepository,
        contentRevisionRepository,
        contentAssetRepository,
        new ContentDocumentSanitizer(new ObjectMapper()),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void assertUnavailableAsset(ContentDraft draft) {
    assertThatThrownBy(() -> service().create(
            ContentKind.POPUP_CAMPAIGN, UUID.randomUUID(), draft))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_ASSET_UNAVAILABLE");
  }

  private static ContentAssetEntity activeAsset(UUID id, String mimeType) {
    ContentAssetEntity asset = new ContentAssetEntity();
    asset.setId(id);
    asset.setMimeType(mimeType);
    asset.setStatus(ContentAssetStatus.ACTIVE);
    return asset;
  }

  private static ContentItemEntity item(UUID itemId, UUID revisionId) {
    ContentItemEntity item = new ContentItemEntity();
    item.setId(itemId);
    item.setCurrentRevisionId(revisionId);
    item.setContentKind(ContentKind.POPUP_CAMPAIGN);
    item.setCreatedAt(NOW.minusSeconds(3_600));
    item.setUpdatedAt(NOW.minusSeconds(3_600));
    return item;
  }

  private static ContentRevisionEntity revision(
      UUID itemId,
      UUID revisionId,
      int revisionNo,
      String title
  ) {
    ContentRevisionEntity revision = new ContentRevisionEntity();
    revision.setId(revisionId);
    revision.setContentItemId(itemId);
    revision.setRevisionNo(revisionNo);
    revision.setTitle(title);
    revision.setBodyDocument(plainDocument(title));
    revision.setSanitizedHtml("<p>" + title + "</p>");
    revision.setCreatedBy(UUID.randomUUID());
    revision.setCreatedAt(NOW.minusSeconds(3_600));
    return revision;
  }

  private static String plainDocument(String text) {
    return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
        + text
        + "\"}]}]}";
  }

  private static String richDocument(UUID imageAssetId) {
    return """
        {"type":"doc","content":[
          {"type":"heading","attrs":{"level":2,"textAlign":"center"},"content":[
            {"type":"text","text":"风险<script>","marks":[
              {"type":"bold"},{"type":"textStyle","attrs":{"color":"#FF0000"}}
            ]}
          ]},
          {"type":"bulletList","content":[
            {"type":"listItem","content":[
              {"type":"paragraph","content":[{"type":"text","text":"仅使用白名单格式"}]}
            ]}
          ]},
          {"type":"image","attrs":{"assetId":"%s","alt":"平台图片"}}
        ]}
        """.formatted(imageAssetId);
  }

  private static String imageDocument(UUID imageAssetId) {
    return """
        {"type":"doc","content":[
          {"type":"image","attrs":{"assetId":"%s","alt":"平台图片"}}
        ]}
        """.formatted(imageAssetId);
  }
}
