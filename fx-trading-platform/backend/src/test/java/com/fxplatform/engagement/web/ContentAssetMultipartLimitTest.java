package com.fxplatform.engagement.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fxplatform.common.exception.GlobalExceptionHandler;
import com.fxplatform.engagement.admin.asset.ContentAssetAdminController;
import com.fxplatform.engagement.application.content.ContentAssetService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(ContentAssetMultipartLimitTest.MultipartLimitConfig.class)
class ContentAssetMultipartLimitTest {

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private ContentAssetService service;

  @Test
  void multipartResolverLimitUsesStableHttp413WithoutCallingAssetService() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

    mockMvc.perform(post("/api/admin/engagement/assets")
            .contentType("multipart/form-data; boundary=test-boundary")
            .content("--test-boundary\r\ncontent beyond configured limit\r\n"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("CONTENT_ASSET_TOO_LARGE"))
        .andExpect(jsonPath("$.message").value("Content asset is too large"));

    verifyNoInteractions(service);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class MultipartLimitConfig {

    @Bean
    ContentAssetService contentAssetService() {
      return mock(ContentAssetService.class);
    }

    @Bean
    ContentAssetAdminController contentAssetAdminController(ContentAssetService service) {
      return new ContentAssetAdminController(service);
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
      return new GlobalExceptionHandler();
    }

    @Bean(name = "multipartResolver")
    MultipartResolver multipartResolver() {
      return new MultipartResolver() {
        @Override
        public boolean isMultipart(HttpServletRequest request) {
          return request.getContentType() != null
              && request.getContentType().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
        }

        @Override
        public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) {
          throw new MaxUploadSizeExceededException(5_242_880L);
        }

        @Override
        public void cleanupMultipart(MultipartHttpServletRequest request) {
          // Resolution failed before any multipart resources were created.
        }
      };
    }
  }
}
