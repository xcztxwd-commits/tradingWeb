# Admin MyBatis-Plus Full Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `fx-trading-platform/backend` 的数据库访问从 Spring Data JPA 全量迁移到 MyBatis-Plus，并补齐后台成交、风控配置接口与前端联调。

**Architecture:** 保留现有 Controller/Service/DTO/API 合同，实体改用 MyBatis-Plus 表映射，现有 Repository 接口转为 MyBatis-Plus Mapper。分页统一改为 MyBatis-Plus `Page`，公共字符串、ID、时间、集合判空等工具能力优先使用 Hutool。

**Tech Stack:** Spring Boot, MyBatis-Plus, PostgreSQL, Flyway, Hutool, React/Vite admin, Node test, Maven test.

---

### Task 1: Baseline And Red Tests

**Files:**
- Modify: `backend/src/test/java/com/fxplatform/ArchitectureRulesTest.java`
- Modify: `apps/admin/src/services/adminApi.test.mjs`

- [ ] **Step 1: Add architecture tests requiring MyBatis-Plus and Hutool**

Add assertions that `pom.xml` contains MyBatis-Plus and Hutool, and that production Java no longer contains `JpaRepository`, `jakarta.persistence`, or `spring-boot-starter-data-jpa`.

- [ ] **Step 2: Add admin API tests requiring trades and risk clients**

Update admin API tests so `getTradesPage` and `getRiskConfigs` are required exported functions mapped to `/api/admin/trading/trades` and `/api/admin/risk/configs`.

- [ ] **Step 3: Run red tests**

Run:
```powershell
C:\soft\Apache-Maven\apache-maven-3.9.11\bin\mvn.cmd -f backend\pom.xml test -Dtest=ArchitectureRulesTest
npm.cmd --workspace apps/admin run test
```

Expected: tests fail because MyBatis-Plus/Hutool and new frontend API calls are not implemented yet.

### Task 2: Backend Dependency And Mapper Infrastructure

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/fxplatform/FxPlatformApplication.java`
- Create: `backend/src/main/java/com/fxplatform/common/mybatis/FxBaseMapper.java`
- Create: `backend/src/main/java/com/fxplatform/common/mybatis/FxQuery.java`

- [ ] **Step 1: Replace JPA dependency with MyBatis-Plus**

Remove `spring-boot-starter-data-jpa`; add `mybatis-plus-spring-boot3-starter`, `mybatis-plus-jsqlparser`, and Hutool `5.8.46`.

- [ ] **Step 2: Configure MyBatis-Plus for PostgreSQL**

Add mapper scanning and YAML configuration for underscore-to-camel mapping and enum name handling. Keep Flyway and PostgreSQL datasource unchanged.

- [ ] **Step 3: Add mapper helpers**

Create a small MyBatis-Plus base mapper with `save`, `findById`, `findAll`, `count`, `findBy...` support used by current services. Use Hutool for UUID, reflection, string, collection, and time helpers.

### Task 3: Entity And Repository Migration

**Files:**
- Modify: all `backend/src/main/java/com/fxplatform/**/entity/*Entity.java`
- Modify: all `backend/src/main/java/com/fxplatform/**/repository/*Repository.java`

- [ ] **Step 1: Convert JPA annotations**

Replace `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column`, `@Enumerated`, `@PrePersist`, and `@PreUpdate` with MyBatis-Plus annotations or mapper lifecycle handling.

- [ ] **Step 2: Convert repositories to BaseMapper**

Make every repository extend `FxBaseMapper<Entity>` and implement module-specific query defaults with `LambdaQueryWrapper`.

- [ ] **Step 3: Run backend compile tests**

Run:
```powershell
C:\soft\Apache-Maven\apache-maven-3.9.11\bin\mvn.cmd -f backend\pom.xml test -DskipTests
```

Expected: compile succeeds before deeper tests.

### Task 4: Service Pagination And Missing Admin APIs

**Files:**
- Modify: `backend/src/main/java/com/fxplatform/admin/dto/AdminPageResponse.java`
- Modify: `backend/src/main/java/com/fxplatform/admin/service/AdminPageRequests.java`
- Modify: admin query services using Spring Data `PageRequest` or `Sort`
- Modify: `backend/src/main/java/com/fxplatform/admin/controller/AdminTradingController.java`
- Create: `backend/src/main/java/com/fxplatform/admin/dto/response/AdminTradeResponse.java`
- Create: `backend/src/main/java/com/fxplatform/risk/entity/RiskConfigEntity.java`
- Create: `backend/src/main/java/com/fxplatform/risk/repository/RiskConfigRepository.java`
- Create: `backend/src/main/java/com/fxplatform/admin/controller/AdminRiskController.java`
- Create: `backend/src/main/java/com/fxplatform/admin/dto/response/AdminRiskConfigResponse.java`
- Create: `backend/src/main/java/com/fxplatform/admin/service/AdminRiskQueryService.java`

- [ ] **Step 1: Convert pagination to MyBatis-Plus**

Use `com.baomidou.mybatisplus.extension.plugins.pagination.Page` and keep API response shape unchanged.

- [ ] **Step 2: Add trades endpoint**

Add `GET /api/admin/trading/trades?page=&size=` using MyBatis-Plus mapper pagination.

- [ ] **Step 3: Add risk configs endpoint**

Add `GET /api/admin/risk/configs` backed by existing `risk.risk_configs`.

### Task 5: Frontend Integration

**Files:**
- Modify: `apps/admin/src/types.ts`
- Modify: `apps/admin/src/services/adminApi.ts`
- Modify: `apps/admin/src/pages/TradesPage.tsx`
- Modify: `apps/admin/src/pages/RiskPage.tsx`

- [ ] **Step 1: Add types and API calls**

Add `TradeRow`, `RiskConfigRow`, `getTradesPage`, and `getRiskConfigs`.

- [ ] **Step 2: Replace placeholder pages**

Render real tables for trades and risk configs.

- [ ] **Step 3: Run frontend tests and build**

Run:
```powershell
npm.cmd --workspace apps/admin run test
npm.cmd --workspace apps/admin run build
```

### Task 6: Full Database Verification And Docs

**Files:**
- Modify: `docs/wh-admin-implementation-gap-plan-cn.md`
- Create: `docs/wh-admin-mybatis-plus-improved-cn.md`
- Create: `docs/wh-admin-optimization-suggestions-cn.md`

- [ ] **Step 1: Run backend tests**

Run all backend tests through portable Maven.

- [ ] **Step 2: Run live API smoke**

Verify login, admin read APIs, new trades API, new risk configs API, and database-backed smoke scripts.

- [ ] **Step 3: Run frontend integration**

Run admin tests/build and verify `5174` pages call backend APIs through the Vite proxy.

- [ ] **Step 4: Write final docs**

Document what changed, test evidence, remaining risk, and follow-up optimization advice.
