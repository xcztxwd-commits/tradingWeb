package com.fxplatform.engagement.admin.support.repository;

import com.fxplatform.auth.enums.UserStatus;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminUserSearchRepository {

  String FILTER = """
      FROM auth.users user_row
      WHERE user_row.role = 'USER'
        AND user_row.status IN ('ACTIVE', 'FROZEN', 'DISABLED')
        AND (
          CAST(#{likePattern} AS varchar) IS NULL
          OR user_row.id = #{exactId}
          OR user_row.email ILIKE #{likePattern} ESCAPE '!'
          OR user_row.phone ILIKE #{likePattern} ESCAPE '!'
        )
      """;

  @Select("""
      SELECT user_row.id, user_row.email, user_row.phone, user_row.status
      """ + FILTER + """
      ORDER BY LOWER(user_row.email) ASC, user_row.id ASC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<AdminUserSearchRow> findUsers(
      @Param("exactId") UUID exactId,
      @Param("likePattern") String likePattern,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select("SELECT COUNT(*) " + FILTER)
  long countUsers(
      @Param("exactId") UUID exactId,
      @Param("likePattern") String likePattern);

  record AdminUserSearchRow(
      UUID id,
      String email,
      String phone,
      UserStatus status
  ) {
  }
}
