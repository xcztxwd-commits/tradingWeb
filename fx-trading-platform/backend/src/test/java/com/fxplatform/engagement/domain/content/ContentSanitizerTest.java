package com.fxplatform.engagement.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContentSanitizerTest {

  private final ContentDocumentSanitizer sanitizer =
      new ContentDocumentSanitizer(new ObjectMapper());

  @Test
  void rendersOnlyAllowedFormattingInternalLinksAndPlatformAssetIds() {
    UUID assetId = UUID.randomUUID();
    String document = """
        {"type":"doc","content":[
          {"type":"paragraph","attrs":{"textAlign":"center"},"content":[
            {"type":"text","text":"safe <text>","marks":[
              {"type":"bold"},{"type":"italic"},
              {"type":"textStyle","attrs":{"color":"#A1B2C3"}},
              {"type":"link","attrs":{"routeKey":"TRADE_SPOT","params":{"symbol":"BTCUSDT"}}}
            ]}
          ]},
          {"type":"bulletList","content":[
            {"type":"listItem","content":[
              {"type":"paragraph","content":[{"type":"text","text":"item"}]}
            ]}
          ]},
          {"type":"image","attrs":{"assetId":"%s","alt":"platform image"}}
        ]}
        """.formatted(assetId);

    var sanitized = sanitizer.sanitize(document);

    assertThat(sanitized.html())
        .contains("<p class=\"align-center\">")
        .contains("<strong><em><span data-color=\"#a1b2c3\"><a data-route-key=\"TRADE_SPOT\"")
        .contains("safe &lt;text&gt;")
        .contains("<ul><li><p>item</p></li></ul>")
        .contains("<img data-asset-id=\"" + assetId + "\" alt=\"platform image\">")
        .doesNotContain("href=", "src=", "style=", "onclick=", "javascript:");
    assertThat(sanitized.assetIds()).containsExactly(assetId);
  }

  @Test
  void acceptsRealisticTiptapHeadingsOrderedListBreaksAndAlignmentFixture() {
    String tiptapDocument = """
        {
          "type":"doc",
          "content":[
            {"type":"heading","attrs":{"textAlign":"left","level":1},"content":[
              {"type":"text","text":"Heading one"}
            ]},
            {"type":"heading","attrs":{"textAlign":"center","level":2},"content":[
              {"type":"text","text":"Heading two"}
            ]},
            {"type":"heading","attrs":{"textAlign":"right","level":3},"content":[
              {"type":"text","text":"Heading three"}
            ]},
            {"type":"paragraph","attrs":{"textAlign":"right"},"content":[
              {"type":"text","text":"first line"},
              {"type":"hardBreak"},
              {"type":"text","text":"second line","marks":[
                {"type":"italic"},{"type":"bold"}
              ]}
            ]},
            {"type":"orderedList","attrs":{"start":3},"content":[
              {"type":"listItem","content":[
                {"type":"paragraph","attrs":{"textAlign":"left"},"content":[
                  {"type":"text","text":"third item"}
                ]}
              ]}
            ]},
            {"type":"paragraph","attrs":{"textAlign":"left"}}
          ]
        }
        """;

    var sanitized = sanitizer.sanitize(tiptapDocument);

    assertThat(sanitized.html())
        .contains("<h1 class=\"align-left\">Heading one</h1>")
        .contains("<h2 class=\"align-center\">Heading two</h2>")
        .contains("<h3 class=\"align-right\">Heading three</h3>")
        .contains("<p class=\"align-right\">first line<br><em><strong>second line</strong></em></p>")
        .contains("<ol start=\"3\"><li><p class=\"align-left\">third item</p></li></ol>")
        .endsWith("<p class=\"align-left\"></p>")
        .doesNotContain("style=", "onclick=", "href=", "src=");
  }

  @ParameterizedTest
  @MethodSource("hostileDocuments")
  void rejectsRawHtmlExternalAndBase64ImagesSvgAndScript(String document) {
    assertThatThrownBy(() -> sanitizer.sanitize(document))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("CONTENT_DOCUMENT_INVALID");
  }

  private static Stream<String> hostileDocuments() {
    return Stream.of(
        "<p onclick=\"steal()\">raw HTML</p>",
        "{\"type\":\"doc\",\"content\":[{\"type\":\"script\",\"content\":[]}]}",
        "{\"type\":\"doc\",\"content\":[{\"type\":\"svg\",\"attrs\":{\"onload\":\"steal()\"}}]}",
        "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"assetId\":\"data:image/png;base64,AAAA\"}}]}",
        "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"assetId\":\"00000000-0000-0000-0000-000000000001\",\"src\":\"https://evil.example/a.png\"}}]}",
        "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"routeKey\":\"https://evil.example\",\"params\":{}}}]}]}]}");
  }
}
