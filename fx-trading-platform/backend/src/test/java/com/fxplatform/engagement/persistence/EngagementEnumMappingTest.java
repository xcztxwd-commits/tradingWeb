package com.fxplatform.engagement.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import java.sql.ResultSet;
import org.apache.ibatis.type.EnumTypeHandler;
import org.junit.jupiter.api.Test;

class EngagementEnumMappingTest {

  @Test
  void unknownDatabaseEnumValueFailsClosed() throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("lifecycle_status")).thenReturn("FUTURE_STATUS");
    EnumTypeHandler<PopupCampaignLifecycleStatus> handler =
        new EnumTypeHandler<>(PopupCampaignLifecycleStatus.class);

    assertThatThrownBy(() -> handler.getNullableResult(resultSet, "lifecycle_status"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FUTURE_STATUS");
  }
}
