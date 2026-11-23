package com.project.devsphere;

import com.project.devsphere.identity.domain.AuthSession;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.infrastructure.persistence.AuthSessionRepository;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import com.project.devsphere.operations.infrastructure.database.RuntimeRolePrivileges;
import com.project.devsphere.project.application.ProjectTransactions;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.domain.DeveloperCapacity;
import com.project.devsphere.resource.infrastructure.persistence.DeveloperCapacityRepository;
import com.project.devsphere.workitem.domain.WorkItem;
import com.project.devsphere.workitem.infrastructure.persistence.WorkItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "devsphere.database.manage-runtime-privileges=false")
@AutoConfigureMockMvc
class DevsphereApplicationTests {
    static final String RUNTIME_PASSWORD = "temporary-test-'role-$password";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7")
            .withCommand("postgres", "-c", "max_connections=300")
            .withEnv("DATABASE_USER", "devsphere_app")
            .withEnv("DATABASE_PASSWORD", RUNTIME_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(java.nio.file.Path.of("infra/postgres/runtime-role.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/010-runtime-role.sql");
    static { POSTGRES.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        r.add("spring.datasource.hikari.minimum-idle", () -> 1);
        r.add("spring.datasource.hikari.idle-timeout", () -> 10000);
        r.add("spring.datasource.hikari.max-lifetime", () -> 30000);
        r.add("spring.flyway.user",POSTGRES::getUsername);
        r.add("spring.flyway.password",POSTGRES::getPassword);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String sessionSigningKey = Base64.getEncoder().encodeToString(key);
        r.add("devsphere.auth.session.signing-key", () -> sessionSigningKey);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired AuthSessionRepository sessions;
    @Autowired SessionService sessionService;
    @Autowired WorkItemRepository items;
    @Autowired DeveloperCapacityRepository capacities;
    @Autowired ProjectTransactions projectTx;
    @Autowired JobQueue queue;
    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;
    record Fixture(UUID workspace,UUID project,UUID owner,UUID member) {}

    @BeforeEach void clearBusinessData() {
        var names=jdbc.queryForList("select tablename from pg_tables where schemaname='public' and tablename<>'flyway_schema_history'",String.class);
        jdbc.execute("truncate table "+String.join(",",names)+" cascade");
    }
    Fixture fixture() {
        return tx.execute(s->{
            UUID owner=addUser("Owner"),member=addUser("Member"),w=UUID.randomUUID(),p=UUID.randomUUID();
            jdbc.update("insert into workspaces(id,slug,name,owner_id) values (?,?,?,?)",w,"w-"+w,"Workspace",owner);
            jdbc.update("insert into workspace_members(workspace_id,user_id,role) values (?,?,'OWNER'),(?,?,'MEMBER')",w,owner,w,member);
            jdbc.update("insert into projects(id,workspace_id,project_key,name,objective,planned_start) values (?,?,'DEV','Project','Build Devsphere',current_date)",p,w);
            for(UUID u:List.of(owner,member)) {
                UUID m=UUID.randomUUID();
                jdbc.update("insert into project_members(id,project_id,workspace_id,user_id) values (?,?,?,?)",m,p,w,u);
                jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by) values (?,?,'DEVELOPER',?)",p,m,owner);
                if(u.equals(owner)) jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by,accepted_by,accepted_at) values (?,?,'PM',?,?,now())",p,m,owner,owner);
            }
            return new Fixture(w,p,owner,member);
        });
    }
    UUID addUser(String label) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,email,display_name,status) values (?,?,?,'ACTIVE')",id,id+"@example.test",label);return id;
    }
    UUID task(Fixture f) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into work_items(id,project_id,type,title,assignee_id) values (?,?,'TASK','Task',?)",id,f.project(),f.member());return id;
    }
    void rejects(Runnable command) {
        Throwable failure=catchThrowable(command::run);assertThat(failure).as("Invalid data must fail").isNotNull();
        Throwable cause=failure;while(cause.getCause()!=null) cause=cause.getCause();
        assertThat(cause).isInstanceOf(SQLException.class);
        assertThat(((SQLException)cause).getSQLState()).startsWith("23");
    }
    long projectVersion(Fixture f) {return jdbc.queryForObject("select version from projects where id=?",Long.class,f.project());}

    @Test void nativeBootstrapAndFlywayCallbackKeepRuntimePrivilegesRestricted() throws Exception {
        var callback = new RuntimeRolePrivileges("devsphere_app");
        var runner = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .callbacks(callback).load();
        assertThat(runner.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select rolsuper or rolcreatedb or rolcreaterole or rolreplication from pg_roles where rolname='devsphere_app'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_schema_privilege('devsphere_app','public','CREATE')", Boolean.class)).isFalse();
        for (String table : List.of("audit_logs", "schedule_versions", "schedule_entries")) {
            assertThat(jdbc.queryForObject("select has_table_privilege('devsphere_app',?,'UPDATE') or has_table_privilege('devsphere_app',?,'DELETE') or has_table_privilege('devsphere_app',?,'TRUNCATE')", Boolean.class, table, table, table)).isFalse();
        }
        assertThat(jdbc.queryForObject("select has_table_privilege('devsphere_app','flyway_schema_history','SELECT')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_table_privilege('devsphere_app','work_items','SELECT,INSERT,UPDATE,DELETE')", Boolean.class)).isTrue();
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "devsphere_app", RUNTIME_PASSWORD);
             var statement = connection.createStatement();
             var user = statement.executeQuery("select current_user")) {
            assertThat(user.next()).isTrue();
            assertThat(user.getString(1)).isEqualTo("devsphere_app");
        }
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThatThrownBy(() -> new RuntimeRolePrivileges(POSTGRES.getUsername()).apply(connection)).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
            assertThatThrownBy(() -> new RuntimeRolePrivileges("missing_runtime_role").apply(connection)).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        }
    }

    @Test void migratesThirtySixTablesAndValidatesEveryJpaEntity() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(36);
        assertThat(em.getMetamodel().getEntities()).hasSize(36);
        assertThat(flyway.info().applied()).extracting(item -> item.getVersion())
                .containsExactlyElementsOf(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10").stream()
                        .map(MigrationVersion::fromVersion).toList());
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test void eachMigrationUpgradesFromItsPredecessor() {
        jdbc.execute("create database migration_upgrade_test");
        String url=POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)","/migration_upgrade_test");
        try {
            for(var migration : flyway.info().applied()) {
                var runner=Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword()).target(migration.getVersion().getVersion()).load();
                assertThat(runner.migrate().migrationsExecuted).isEqualTo(1);runner.validate();
            }
        } finally {jdbc.execute("drop database migration_upgrade_test with (force)");}
    }
    @Test void loginRotationAndReplayRevokeTheFamilyWithoutStoringRawTokens() {
        UUID user = addPasswordUser("active@example.test", "ACTIVE");
        var login = sessionService.login("ACTIVE@example.test", "A strong integration test password");
        assertThat(login.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        assertThat(sessionService.authenticate(login.access().value())).get()
                .extracting(SessionService.Principal::userId).isEqualTo(user);
        assertThat(jdbc.queryForObject("select refresh_hash from auth_sessions where user_id=?", String.class, user))
                .hasSize(64).isNotEqualTo(login.refresh());

        var successor = sessionService.refresh(login.refresh());
        assertThat(successor.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        assertThat(successor.refreshExpiresAt()).isEqualTo(login.refreshExpiresAt());
        assertThat(successor.refresh()).isNotEqualTo(login.refresh());
        assertThat(sessionService.authenticate(login.access().value())).isEmpty();
        assertThat(sessionService.authenticate(successor.access().value())).isPresent();

        assertThat(sessionService.refresh(login.refresh()).outcome()).isEqualTo(SessionService.Outcome.SESSION_REPLAYED);
        assertThat(sessionService.authenticate(successor.access().value())).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from auth_sessions where user_id=? and revoked_at is null", Long.class, user)).isZero();
    }

    @Test void inactiveAccountsAndWrongPasswordsCannotCreateSessions() {
        addPasswordUser("pending@example.test", "PENDING");
        UUID user = addPasswordUser("lock@example.test", "ACTIVE");
        String password = "A strong integration test password";
        var login = sessionService.login("lock@example.test", password);
        assertThat(login.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        jdbc.update("update users set status='LOCKED' where id=?", user);

        assertThat(sessionService.login("pending@example.test", password).outcome())
                .isEqualTo(SessionService.Outcome.EMAIL_UNVERIFIED);
        assertThat(sessionService.login("lock@example.test", "wrong").outcome())
                .isEqualTo(SessionService.Outcome.INVALID_CREDENTIALS);
        assertThat(sessionService.login("lock@example.test", password).outcome())
                .isEqualTo(SessionService.Outcome.ACCOUNT_LOCKED);
        assertThat(sessionService.authenticate(login.access().value())).isEmpty();
        assertThat(sessionService.refresh(login.refresh()).outcome()).isEqualTo(SessionService.Outcome.INVALID_SESSION);
    }

    @Test void concurrentRefreshCreatesAtMostOneSuccessorAndRevokesItOnReplay() throws Exception {
        UUID user = addPasswordUser("race@example.test", "ACTIVE");
        var login = sessionService.login("race@example.test", "A strong integration test password");
        assertThat(login.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<SessionService.Attempt> refresh = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent refresh");
                }
                return sessionService.refresh(login.refresh());
            };
            var first = workers.submit(refresh);
            var second = workers.submit(refresh);
            start.countDown();
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results).extracting(SessionService.Attempt::outcome).containsExactlyInAnyOrder(
                    SessionService.Outcome.SUCCESS, SessionService.Outcome.SESSION_REPLAYED);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from auth_sessions where user_id=? and revoked_at is null", Long.class, user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where user_id=?", Long.class, user)).isEqualTo(2);
    }

    @Test void owningAccountCanRevokeOneFamilyWithoutAffectingOtherAccounts() {
        addPasswordUser("owner@example.test", "ACTIVE");
        addPasswordUser("other@example.test", "ACTIVE");
        String password = "A strong integration test password";
        var current = sessionService.login("owner@example.test", password);
        var second = sessionService.login("owner@example.test", password);
        var foreign = sessionService.login("other@example.test", password);
        var owner = sessionService.authenticate(current.access().value()).orElseThrow();
        var other = sessionService.authenticate(foreign.access().value()).orElseThrow();
        var foreignFamily = sessionService.list(other).getFirst().familyId();
        var owned = sessionService.list(owner);
        assertThat(owned).hasSize(2).extracting(SessionService.SessionSummary::familyId).doesNotContain(foreignFamily);
        var secondFamily = owned.stream().filter(item -> !item.current()).findFirst().orElseThrow().familyId();
        assertThat(sessionService.revoke(owner, foreignFamily)).isFalse();
        assertThat(sessionService.revoke(owner, secondFamily)).isTrue();
        assertThat(sessionService.revoke(owner, secondFamily)).isFalse();
        assertThat(sessionService.authenticate(second.access().value())).isEmpty();
        assertThat(sessionService.authenticate(foreign.access().value())).isPresent();
        assertThat(sessionService.authenticate(current.access().value())).isPresent();

        sessionService.logout(current.refresh());
        assertThat(sessionService.authenticate(current.access().value())).isEmpty();
        assertThat(sessionService.list(owner)).isEmpty();
        assertThat(sessionService.revoke(owner, secondFamily)).isFalse();
    }

    @Test
    void sessionLifecycleAndReplayRevocation() throws Exception {
        UUID userId = addPasswordUser("m12-test@example.com", "ACTIVE");
        var mapper = new JsonMapper();

        var csrfRes = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn();
        Cookie csrfCookie = csrfRes.getResponse().getCookie("__Host-devsphere-csrf");
        assertThat(csrfCookie).isNotNull();
        var csrfJson = mapper.readTree(csrfRes.getResponse().getContentAsString());
        String csrfHeader = csrfJson.get("headerName").asText();
        String csrfToken = csrfJson.get("token").asText();

        String loginBody = "{\"email\":\"m12-test@example.com\",\"password\":\"A strong integration test password\"}";
        mvc.perform(post("/api/v1/auth/login").secure(true)
                .contentType("application/json").content(loginBody))
                .andExpect(status().isForbidden());

        var loginRes = mvc.perform(post("/api/v1/auth/login").secure(true)
                        .contentType("application/json").content(loginBody)
                        .cookie(csrfCookie).header(csrfHeader, csrfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        Cookie refresh1 = loginRes.getResponse().getCookie("__Secure-devsphere-refresh");
        assertThat(refresh1).isNotNull();
        String access1 = mapper.readTree(loginRes.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + access1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));

        var refreshRes = mvc.perform(post("/api/v1/auth/refresh").secure(true)
                        .cookie(csrfCookie, refresh1).header(csrfHeader, csrfToken))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refresh2 = refreshRes.getResponse().getCookie("__Secure-devsphere-refresh");
        assertThat(refresh2).isNotNull();
        assertThat(refresh2.getValue()).isNotEqualTo(refresh1.getValue());
        String access2 = mapper.readTree(refreshRes.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + access1))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + access2))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/refresh").secure(true)
                        .cookie(csrfCookie, refresh1).header(csrfHeader, csrfToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REPLAYED"));

        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + access2))
                .andExpect(status().isUnauthorized());
    }

    @Test void sessionHttpEndpointsEnforceOwnershipAgainstPostgres() throws Exception {
        addPasswordUser("api-owner@example.test", "ACTIVE");
        addPasswordUser("api-other@example.test", "ACTIVE");
        String password = "A strong integration test password";
        var owner = sessionService.login("api-owner@example.test", password);
        var other = sessionService.login("api-other@example.test", password);
        assertThat(owner.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        assertThat(other.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);

        String ownerHeader = "Bearer " + owner.access().value();
        String otherHeader = "Bearer " + other.access().value();
        String path = "/api/v1/auth/sessions";
        var mapper = new JsonMapper();
        var ownerSessions = mvc.perform(get(path).secure(true).header("Authorization", ownerHeader))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[1]").doesNotExist()).andReturn().getResponse();
        var otherSessions = mvc.perform(get(path).secure(true).header("Authorization", otherHeader))
                .andExpect(status().isOk()).andExpect(jsonPath("$[1]").doesNotExist()).andReturn().getResponse();
        String ownerFamily = mapper.readTree(ownerSessions.getContentAsString()).get(0).get("familyId").asText();
        String otherFamily = mapper.readTree(otherSessions.getContentAsString()).get(0).get("familyId").asText();
        assertThat(ownerFamily).isNotEqualTo(otherFamily);

        mvc.perform(delete(path + "/" + otherFamily).secure(true).header("Authorization", ownerHeader))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path + "/" + ownerFamily).secure(true).header("Authorization", ownerHeader))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).secure(true).header("Authorization", ownerHeader)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).secure(true).header("Authorization", otherHeader))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].familyId").value(otherFamily));
    }

    private UUID addPasswordUser(String email, String status) {
        UUID id = UUID.randomUUID();
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        jdbc.update("insert into users(id,email,display_name,status,password_hash) values (?,?,?,?,?)",
                id, email, "Session Test User", status, encoder.encode("A strong integration test password"));
        return id;
    }
    @Test void ownerMustExistAndMatchWorkspacePointerAtCommit() {
        UUID u=addUser("No membership");
        rejects(()->jdbc.update("insert into workspaces(slug,name,owner_id) values ('invalid','Invalid',?)",u));
        Fixture f=fixture();
        rejects(()->jdbc.update("update workspaces set owner_id=? where id=?",f.member(),f.workspace()));
        tx.executeWithoutResult(s->{
            jdbc.update("update workspace_members set role='MEMBER' where workspace_id=? and user_id=?",f.workspace(),f.owner());
            jdbc.update("update workspace_members set role='OWNER' where workspace_id=? and user_id=?",f.workspace(),f.member());
            jdbc.update("update workspaces set owner_id=? where id=?",f.member(),f.workspace());
        });
        assertThat(jdbc.queryForObject("select owner_id from workspaces where id=?",UUID.class,f.workspace())).isEqualTo(f.member());
        assertThat(jdbc.queryForObject("select ds_has_project_role(?,?,'PM')",Boolean.class,f.project(),f.owner())).isTrue();
    }
    @Test void oneProjectPerWorkspaceAndNoCrossProjectRelations() {
        Fixture a=fixture(),b=fixture();
        rejects(()->jdbc.update("insert into projects(workspace_id,project_key,name) values (?,'NEW','Second')",a.workspace()));
        UUID ta=task(a),tb=task(b);
        rejects(()->jdbc.update("insert into work_item_dependencies(project_id,predecessor_id,successor_id) values (?,?,?)",a.project(),ta,tb));
        rejects(()->jdbc.update("update work_items set project_id=? where id=?",b.project(),ta));
        UUID sprint=UUID.randomUUID();
        jdbc.update("insert into sprints(id,project_id,name,start_date,end_date) values (?,?,'Sprint',current_date,current_date+14)",sprint,b.project());
        rejects(()->jdbc.update("update work_items set sprint_id=? where id=?",sprint,ta));
        assertThat(items.findByIdAndProjectId(ta,b.project())).isEmpty();
    }
    @Test void multipleRolesButOnlyOnePmAndNoDuplicateActiveRole() {
        Fixture f=fixture();
        assertThat(jdbc.queryForObject("select count(*) from project_member_roles where project_id=? and revoked_at is null",Integer.class,f.project())).isEqualTo(3);
        UUID m=jdbc.queryForObject("select id from project_members where project_id=? and user_id=?",UUID.class,f.project(),f.member());
        rejects(()->jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by) values (?,?,'PM',?)",f.project(),m,f.owner()));
        rejects(()->jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by) values (?,?,'DEVELOPER',?)",f.project(),m,f.owner()));
    }
    @Test void activationRequiresBacklogAndPmTransferIsAtomic() {
        Fixture f=fixture();rejects(()->jdbc.update("update projects set status='ACTIVE' where id=?",f.project()));
        task(f);jdbc.update("update projects set status='ACTIVE' where id=?",f.project());
        rejects(()->jdbc.update("update project_member_roles set revoked_at=now() where project_id=? and role='PM' and revoked_at is null",f.project()));
        tx.executeWithoutResult(s->{
            jdbc.update("update project_member_roles set revoked_at=now() where project_id=? and role='PM' and revoked_at is null",f.project());
            jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by,accepted_by,accepted_at) select ?,id,'PM',?,?,now() from project_members where project_id=? and user_id=?",f.project(),f.owner(),f.member(),f.project(),f.member());
        });
        assertThat(jdbc.queryForObject("select ds_has_project_role(?,?,'PM')",Boolean.class,f.project(),f.member())).isTrue();
    }
    @Test void lockingPmHoldsProjectAndRevokesSessions() {
        Fixture f=fixture();task(f);jdbc.update("update projects set status='ACTIVE' where id=?",f.project());
        jdbc.update("insert into auth_sessions(user_id,refresh_hash,expires_at,session_family_id) values (?,'hash',now()+interval '1 day',?)",f.owner(),UUID.randomUUID());
        jdbc.update("update users set status='LOCKED' where id=?",f.owner());
        assertThat(jdbc.queryForObject("select status from projects where id=?",String.class,f.project())).isEqualTo("ON_HOLD");
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where revoked_at is null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select ds_has_project_role(?,?,'PM')",Boolean.class,f.project(),f.owner())).isFalse();
    }
    @Test void dependencyDagRejectsSequentialAndConcurrentCycles() throws Exception {
        Fixture f=fixture();UUID a=task(f),b=task(f),c=task(f);
        jdbc.update("insert into work_item_dependencies(project_id,predecessor_id,successor_id) values (?,?,?),(?,?,?)",f.project(),a,b,f.project(),b,c);
        rejects(()->jdbc.update("insert into work_item_dependencies(project_id,predecessor_id,successor_id) values (?,?,?)",f.project(),c,a));
        jdbc.update("delete from work_item_dependencies where project_id=?",f.project());
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> result=new ArrayList<>();
            for(boolean reverse:List.of(false,true)) result.add(pool.submit(()->{
                start.await();try {jdbc.update("insert into work_item_dependencies(project_id,predecessor_id,successor_id) values (?,?,?)",f.project(),reverse?b:a,reverse?a:b);return true;}
                catch(org.springframework.dao.DataIntegrityViolationException e) {return false;}
            }));
            start.countDown();
            assertThat(List.of(result.get(0).get(10,TimeUnit.SECONDS),result.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void taskWorkflowRequiresPmAndFreezesDoneScope() {
        Fixture f=fixture();UUID t=task(f);
        rejects(()->jdbc.update("update work_items set status='DONE',completed_at=now(),accepted_by=? where id=?",f.owner(),t));
        rejects(()->jdbc.update("update work_items set is_blocked=true where id=?",t));
        jdbc.update("update work_items set status='IN_PROGRESS',started_at=now() where id=?",t);
        jdbc.update("update work_items set status='IN_REVIEW' where id=?",t);
        rejects(()->jdbc.update("update work_items set status='DONE',completed_at=now(),accepted_by=? where id=?",f.member(),t));
        jdbc.update("update work_items set status='DONE',completed_at=now(),accepted_by=? where id=?",f.owner(),t);
        rejects(()->jdbc.update("update work_items set title='Overwrite Done' where id=?",t));
        rejects(()->jdbc.update("update work_items set is_blocked=true,blocked_reason='Blocked' where id=?",t));
    }
    @Test void capacityRejectsOverlappingPeriodsSlotsAndInvalidNumbers() {
        Fixture f=fixture();
        jdbc.update("insert into developer_capacity(project_id,user_id,minutes_per_workday,effective_from,effective_to) values (?,?,480,current_date,current_date+14)",f.project(),f.member());
        rejects(()->jdbc.update("insert into developer_capacity(project_id,user_id,minutes_per_workday,effective_from) values (?,?,480,current_date+1)",f.project(),f.member()));
        rejects(()->jdbc.update("update developer_capacity set weekly_slots='[{\"dayOfWeek\":1,\"startMinute\":0,\"endMinute\":60},{\"dayOfWeek\":1,\"startMinute\":30,\"endMinute\":90}]'::jsonb"));
        rejects(()->jdbc.update("update developer_capacity set allocation_pct=101"));
        rejects(()->jdbc.update("update developer_capacity set skills='[{\"skill\":\"Java\",\"level\":-1}]'::jsonb"));
    }
    @Test void commandIsAtomicIdempotentAndRejectsStaleVersions() {
        Fixture f=fixture();UUID task=task(f),key=UUID.randomUUID();long v=projectVersion(f);
        var result=projectTx.execute(f.workspace(),f.project(),f.owner(),v,key,"TaskChanged",Map.of("title","Updated"),()->{
            jdbc.update("update work_items set title='Updated' where id=?",task);return Map.of("taskId",task.toString());
        });
        assertThat(projectTx.execute(f.workspace(),f.project(),f.owner(),v,key,"TaskChanged",Map.of("title","Updated"),()->{throw new AssertionError("Duplicate");})).isEqualTo(result);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where job_kind='OUTBOX_EVENT'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->projectTx.execute(f.workspace(),f.project(),f.owner(),v,UUID.randomUUID(),"Apply",Map.of(),Map::of)).isInstanceOf(ConcurrentModificationException.class);
        assertThatThrownBy(()->projectTx.execute(f.workspace(),f.project(),f.owner(),v,key,"Other",Map.of(),Map::of)).isInstanceOf(IllegalArgumentException.class);
        long fresh=projectVersion(f);
        assertThatThrownBy(()->projectTx.execute(f.workspace(),f.project(),f.owner(),fresh,UUID.randomUUID(),"Rollback",Map.of(),()->{
            jdbc.update("update work_items set title='Partial' where id=?",task);throw new IllegalStateException("Injected");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select title from work_items where id=?",String.class,task)).isEqualTo("Updated");
        assertThat(projectVersion(f)).isEqualTo(fresh);
    }
    @Test void jpaRoundTripsJsonArraysDecimalsInetAndOptimisticVersions() throws Exception {
        Fixture f=fixture();
        UUID id=tx.execute(s->{
            WorkItem w=new WorkItem();w.setProjectId(f.project());w.setType(WorkItem.Type.TASK);w.setTitle("Mapped task");
            w.setStoryPoints(new BigDecimal("3.50"));w.setLabels(new String[]{"backend","database"});w.setRequiredSkills(new ArrayList<>(List.of(new SkillLevel("Java",3))));
            items.save(w);items.flush();return w.getId();
        });
        WorkItem first=items.findByIdAndProjectId(id,f.project()).orElseThrow(),stale=items.findByIdAndProjectId(id,f.project()).orElseThrow();
        assertThat(first.getLabels()).containsExactly("backend","database");assertThat(first.getStoryPoints()).isEqualByComparingTo("3.50");assertThat(first.getRequiredSkills()).hasSize(1);
        first.setTitle("First");tx.executeWithoutResult(s->{items.save(first);items.flush();});
        stale.setTitle("Stale");assertThatThrownBy(()->tx.executeWithoutResult(s->{items.save(stale);items.flush();})).isInstanceOf(RuntimeException.class);
        InetAddress ip=InetAddress.getByName("127.0.0.1");
        UUID sessionId=tx.execute(s->{
            AuthSession session=new AuthSession();session.setUserId(f.owner());session.setRefreshHash("roundtrip");session.setExpiresAt(Instant.now().plus(1,ChronoUnit.DAYS));session.setSessionFamilyId(UUID.randomUUID());session.setIp(ip);
            sessions.save(session);sessions.flush();return session.getId();
        });
        assertThat(sessions.findById(sessionId).orElseThrow().getIp()).isEqualTo(ip);
    }
    @Test void auditIsAppendOnlyAndDoesNotRetainSecretHashes() {
        Fixture f=fixture();jdbc.update("update users set password_hash='sensitive-hash' where id=?",f.owner());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where after_json ? 'password_hash' or before_json ? 'password_hash'",Integer.class)).isZero();
        rejects(()->jdbc.update("update audit_logs set action='TAMPER'"));rejects(()->jdbc.update("delete from audit_logs"));
        rejects(()->jdbc.update("update workspaces set settings='{\"nested\":[{\"apiKey\":\"plaintext\"}]}'::jsonb where id=?",f.workspace()));
    }
    @Test void alertsDeduplicateAndIdempotencyIsTenantScoped() {
        Fixture a=fixture(),b=fixture();
        jdbc.update("insert into alerts(project_id,fingerprint,alert_code,severity,title) values (?,?,'BLOCKED','HIGH','Blocked')",a.project(),"a".repeat(64));
        rejects(()->jdbc.update("insert into alerts(project_id,fingerprint,alert_code,severity,title) values (?,?,'BLOCKED','HIGH','Duplicate')",a.project(),"a".repeat(64)));
        jdbc.update("update alerts set status='RESOLVED',resolved_at=now() where project_id=?",a.project());
        jdbc.update("insert into alerts(project_id,fingerprint,alert_code,severity,title) values (?,?,'BLOCKED','HIGH','New episode')",a.project(),"a".repeat(64));
        for(Fixture f:List.of(a,b)) jdbc.update("insert into background_jobs(workspace_id,project_id,job_kind,idempotency_key) values (?,?,'SCHEDULE_SOLVE','same-key')",f.workspace(),f.project());
        rejects(()->jdbc.update("insert into background_jobs(workspace_id,project_id,job_kind,idempotency_key) values (?,?,'SCHEDULE_SOLVE','same-key')",a.workspace(),a.project()));
    }
    @Test void jobLeasesHaveOneOwnerAndRecoverAfterExpiry() throws Exception {
        jdbc.update("insert into background_jobs(job_kind) values ('BACKUP_CHECK')");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->queue.claim("a",60));var b=pool.submit(()->queue.claim("b",60));
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).stream().filter(Optional::isPresent)).hasSize(1);
        }
        UUID id=jdbc.queryForObject("select id from background_jobs",UUID.class);
        assertThatThrownBy(()->queue.complete(id,"wrong")).isInstanceOf(IllegalStateException.class);
        jdbc.update("update background_jobs set locked_until=now()-interval '1 second' where id=?",id);
        assertThat(queue.claim("recovery",60)).contains(id);queue.complete(id,"recovery");assertThat(queue.claim("other",60)).isEmpty();
    }
    @Test void deadlineRequiresConfirmedAppliedProposal() {
        Fixture f=fixture();rejects(()->jdbc.update("update projects set committed_deadline=current_date+30 where id=?",f.project()));
    }

    UUID addClient(Fixture f) {
        return tx.execute(s->{
            UUID u=addUser("Client"),m=UUID.randomUUID();
            jdbc.update("insert into workspace_members(workspace_id,user_id) values (?,?)",f.workspace(),u);
            jdbc.update("insert into project_members(id,project_id,workspace_id,user_id) values (?,?,?,?)",m,f.project(),f.workspace(),u);
            jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by) values (?,?,'CLIENT',?)",f.project(),m,f.owner());return u;
        });
    }
    UUID repository(Fixture f) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into github_repositories(id,workspace_id,project_id,github_installation_id,github_repo_id,full_name,default_branch) values (?,?,?,1,?,'example/devsphere','main')",id,f.workspace(),f.project(),Math.abs(id.getMostSignificantBits()%1_000_000_000L)+1);return id;
    }
    UUID proposal(Fixture f,String payload) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into plan_proposals(id,project_id,kind,base_project_version,proposal_payload,created_by) values (?,?,'MANUAL_REPLAN',?,?::jsonb,?)",id,f.project(),projectVersion(f),payload,f.owner());return id;
    }
    @Test void pmNominationKeepsOldPmUntilRecipientAccepts() {
        Fixture f=fixture();task(f);jdbc.update("update projects set status='ACTIVE' where id=?",f.project());UUID invitation=UUID.randomUUID();
        jdbc.queryForObject("select ds_propose_pm_assignment(?,?,?,?,?)",String.class,f.project(),f.owner(),f.member(),invitation,projectVersion(f));
        assertThat(jdbc.queryForObject("select ds_has_project_role(?,?,'PM')",Boolean.class,f.project(),f.owner())).isTrue();
        rejects(()->jdbc.queryForObject("select ds_accept_pm_assignment(?,?,?,?)",String.class,f.project(),f.owner(),invitation,projectVersion(f)));
        jdbc.queryForObject("select ds_accept_pm_assignment(?,?,?,?)",String.class,f.project(),f.member(),invitation,projectVersion(f));
        assertThat(jdbc.queryForObject("select ds_has_project_role(?,?,'PM')",Boolean.class,f.project(),f.member())).isTrue();
        assertThat(jdbc.queryForObject("select ds_has_project_role(?,?,'PM')",Boolean.class,f.project(),f.owner())).isFalse();
        assertThat(jdbc.queryForObject("select settings#>>'{pmAssignment,status}' from projects where id=?",String.class,f.project())).isEqualTo("ACCEPTED");
    }
    @Test void removingWorkspaceMemberRevokesProjectRolesAndChannelMembership() {
        Fixture f=fixture();
        jdbc.update("update workspace_members set status='REMOVED' where workspace_id=? and user_id=?",f.workspace(),f.member());
        assertThat(jdbc.queryForObject("select ds_has_project_role(?,?,'DEVELOPER')",Boolean.class,f.project(),f.member())).isFalse();
        assertThat(jdbc.queryForObject("select status from project_members where project_id=? and user_id=?",String.class,f.project(),f.member())).isEqualTo("REMOVED");
        rejects(()->task(f));
    }
    @Test void chatChecksScopeMembershipClientBoundaryAndReplyChannel() {
        Fixture f=fixture();UUID client=addClient(f),channel=UUID.randomUUID(),other=UUID.randomUUID();
        for(UUID c:List.of(channel,other)) jdbc.update("insert into chat_channels(id,workspace_id,project_id,type,created_by) values (?,?,?,'TEAM',?)",c,f.workspace(),f.project(),f.owner());
        rejects(()->jdbc.update("insert into chat_channel_members(workspace_id,project_id,channel_id,user_id) values (?,?,?,?)",f.workspace(),f.project(),channel,client));
        for(UUID c:List.of(channel,other)) jdbc.update("insert into chat_channel_members(workspace_id,project_id,channel_id,user_id) values (?,?,?,?)",f.workspace(),f.project(),c,f.owner());
        UUID message=UUID.randomUUID();
        jdbc.update("insert into chat_messages(id,workspace_id,project_id,channel_id,sender_id,body) values (?,?,?,?,?,'Hello')",message,f.workspace(),f.project(),channel,f.owner());
        rejects(()->jdbc.update("insert into chat_messages(workspace_id,project_id,channel_id,sender_id,body,reply_to_id) values (?,?,?,?,'Wrong thread',?)",f.workspace(),f.project(),other,f.owner(),message));
        rejects(()->jdbc.update("insert into chat_messages(workspace_id,project_id,channel_id,sender_id,body) values (?,?,?,?,'Not a member')",f.workspace(),f.project(),channel,f.member()));
    }
    @Test void attachmentsRequireExactlyOneOwnerMatchingKindAndCleanClientFiles() {
        Fixture f=fixture();UUID t=task(f);
        String sql="insert into attachments(workspace_id,project_id,work_item_id,kind,storage_key,filename,content_type,file_size,sha256,visibility,scan_status) values (?,?,?,'TASK_FILE',?,'report.pdf','application/pdf',10,?,?,?)";
        rejects(()->jdbc.update(sql,f.workspace(),f.project(),t,"bad-key","a".repeat(64),"CLIENT_SHARED","PENDING"));
        jdbc.update(sql,f.workspace(),f.project(),t,"good-key","a".repeat(64),"CLIENT_SHARED","CLEAN");
        rejects(()->jdbc.update("update attachments set kind='CHAT_FILE' where storage_key='good-key'"));
        rejects(()->jdbc.update("update attachments set project_id=null where storage_key='good-key'"));
    }
    @Test void webhookAndNotificationDuplicatesAreRejected() {
        Fixture f=fixture();UUID repo=repository(f);
        String event="insert into github_events(project_id,repository_id,delivery_id,event_type,payload) values (?,?,'delivery-1','push','{}'::jsonb)";
        jdbc.update(event,f.project(),repo);rejects(()->jdbc.update(event,f.project(),repo));
        String notification="insert into notifications(user_id,workspace_id,project_id,event_type,event_key,title,body) values (?,?,?,'TASK','event-1','Title','Body')";
        jdbc.update(notification,f.owner(),f.workspace(),f.project());rejects(()->jdbc.update(notification,f.owner(),f.workspace(),f.project()));
    }
    @Test void approvedRequirementAndScheduleHistoryAreImmutable() {
        Fixture f=fixture();UUID requirement=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            jdbc.update("insert into requirements(id,project_id,code,title,requested_by) values (?,?,'REQ-1','Requirement',?)",requirement,f.project(),f.owner());
            jdbc.update("insert into requirement_versions(project_id,requirement_id,version_no,description,approved_at) values (?,?,1,'Approved content',now())",f.project(),requirement);
        });
        rejects(()->jdbc.update("update requirement_versions set description='Rewrite history' where requirement_id=?",requirement));
        rejects(()->jdbc.update("update requirements set current_version=2 where id=?",requirement));
        UUID baseline=UUID.randomUUID();
        jdbc.update("insert into schedule_versions(id,project_id,version_no,source,approved_by,approved_at) values (?,?,1,'MANUAL',?,now())",baseline,f.project(),f.owner());
        rejects(()->jdbc.update("update schedule_versions set version_no=2 where id=?",baseline));
    }
    @Test void proposalsAreDraftsAndRejectNegativeEstimatesOrDependencyCycles() {
        Fixture f=fixture();
        proposal(f,"{\"schemaVersion\":1,\"items\":[{\"tempKey\":\"A\",\"estimateMinutes\":60}]}");
        assertThat(jdbc.queryForObject("select count(*) from work_items",Integer.class)).isZero();
        rejects(()->proposal(f,"{\"schemaVersion\":1,\"items\":[{\"tempKey\":\"A\",\"estimateMinutes\":-1}]}"));
        rejects(()->proposal(f,"{\"schemaVersion\":1,\"items\":[{\"tempKey\":\"A\"},{\"tempKey\":\"B\"}],\"dependencies\":[{\"predecessorKey\":\"A\",\"successorKey\":\"B\"},{\"predecessorKey\":\"B\",\"successorKey\":\"A\"}]}"));
    }
    @Test void confirmedDeadlineAppliesAndChangingDraftClearsPriorConsent() {
        Fixture f=fixture();UUID client=addClient(f);
        jdbc.update("update projects set deadline_approver_id=? where id=?",client,f.project());
        UUID p=proposal(f,"{\"schemaVersion\":1,\"items\":[]}");
        jdbc.update("update plan_proposals set changes_committed_deadline=true,proposed_committed_deadline=current_date+30 where id=?",p);
        jdbc.update("update plan_proposals set status='APPROVED',approved_by=?,approved_at=now(),solver_status='FEASIBLE' where id=?",f.owner(),p);
        rejects(()->jdbc.update("update plan_proposals set status='APPLIED' where id=?",p));
        jdbc.update("update plan_proposals set deadline_confirmed_by=?,deadline_confirmed_at=now(),deadline_confirmation_version=version+1,deadline_confirmed_project_version=base_project_version where id=?",client,p);
        jdbc.update("update plan_proposals set status='APPLIED' where id=?",p);
        UUID schedule=UUID.randomUUID();
        jdbc.update("insert into schedule_versions(id,project_id,version_no,source,proposal_id,approved_by,approved_at) values (?,?,1,'RESCHEDULE',?,?,now())",schedule,f.project(),p,f.owner());
        jdbc.update("update projects set active_schedule_version_id=?,committed_deadline=current_date+30 where id=?",schedule,f.project());
        assertThat(jdbc.queryForObject("select committed_deadline is not null from projects where id=?",Boolean.class,f.project())).isTrue();
        UUID draft=proposal(f,"{\"schemaVersion\":1,\"items\":[]}");
        jdbc.update("update plan_proposals set deadline_confirmed_by=?,deadline_confirmed_at=now(),deadline_confirmation_version=version+1 where id=?",client,draft);
        jdbc.update("update plan_proposals set proposed_committed_deadline=current_date+40 where id=?",draft);
        assertThat(jdbc.queryForObject("select deadline_confirmed_by is null from plan_proposals where id=?",Boolean.class,draft)).isTrue();
    }
    @Test void oldShaReviewIsNotCurrentAndAuthorCannotApproveOwnPr() {
        Fixture f=fixture();UUID repo=repository(f),pr=UUID.randomUUID();
        jdbc.update("insert into oauth_accounts(user_id,provider,provider_subject,provider_login) values (?,'GITHUB','reviewer-subject','reviewer')",f.member());
        jdbc.update("insert into github_activities(id,project_id,repository_id,kind,github_object_id,pr_number,pr_head_sha,actor_login,state,happened_at) values (?,?,?,'PULL_REQUEST','pr-1',1,?,'author','OPEN',now())",pr,f.project(),repo,"a".repeat(40));
        jdbc.update("insert into code_reviews(project_id,repository_id,pr_number,head_sha,context_version,scope,reviewer_kind,reviewer_id,status) values (?,?,1,?,?,'PR','HUMAN',?,'PASS')",f.project(),repo,"a".repeat(40),projectVersion(f),f.member());
        assertThat(jdbc.queryForObject("select count(*) from current_pr_reviews",Integer.class)).isEqualTo(1);
        jdbc.update("update github_activities set pr_head_sha=? where id=?","b".repeat(40),pr);
        assertThat(jdbc.queryForObject("select count(*) from current_pr_reviews",Integer.class)).isZero();
        jdbc.update("update oauth_accounts set provider_login='author' where user_id=?",f.member());
        rejects(()->jdbc.update("insert into code_reviews(project_id,repository_id,pr_number,head_sha,scope,reviewer_kind,reviewer_id,status) values (?,?,1,?,'PR','HUMAN',?,'PASS')",f.project(),repo,"b".repeat(40),f.member()));
    }
    @Test void closeSprintMovesUnfinishedTasksBackToBacklogAndFreezesMetrics() {
        Fixture f=fixture();UUID t=task(f),s=UUID.randomUUID();
        jdbc.update("insert into sprints(id,project_id,name,start_date,end_date) values (?,?,'Sprint',current_date,current_date+14)",s,f.project());
        jdbc.update("update work_items set sprint_id=? where id=?",s,t);
        jdbc.update("update sprints set status='CLOSED' where id=?",s);
        assertThat(jdbc.queryForObject("select sprint_id is null from work_items where id=?",Boolean.class,t)).isTrue();
        rejects(()->jdbc.update("update sprints set completed_points=999 where id=?",s));
    }
    @Test void concurrentTaskKeysAreUniqueAndMonotonic() throws Exception {
        Fixture f=fixture();
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<UUID>> created=new ArrayList<>();
            for(int i=0;i<12;i++) created.add(pool.submit(()->task(f)));
            for(var result:created) assertThat(result.get(10,TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(jdbc.queryForObject("select count(distinct item_key) from work_items where project_id=?",Integer.class,f.project())).isEqualTo(12);
        assertThat(jdbc.queryForObject("select next_item_number from projects where id=?",Long.class,f.project())).isEqualTo(13L);
    }
    @Test void scheduleRejectsOverlapAndChangesToFrozenBaselineEntries() {
        Fixture f=fixture();UUID a=task(f),b=task(f),v1=UUID.randomUUID(),v2=UUID.randomUUID();
        for(int n=1;n<=2;n++) jdbc.update("insert into schedule_versions(id,project_id,version_no,source,approved_by,approved_at) values (?,?,?,'MANUAL',?,now())",n==1?v1:v2,f.project(),n,f.owner());
        String entry="insert into schedule_entries(project_id,schedule_version_id,work_item_id,assignee_id,start_at,finish_at,remaining_minutes_snapshot,frozen) values (?,?,?,?, '2026-12-01T01:00Z','2026-12-01T02:00Z',60,true)";
        jdbc.update(entry,f.project(),v1,a,f.member());
        rejects(()->jdbc.update(entry,f.project(),v1,b,f.member()));
        jdbc.update("update projects set active_schedule_version_id=? where id=?",v1,f.project());
        rejects(()->jdbc.update("insert into schedule_entries(project_id,schedule_version_id,work_item_id,assignee_id,start_at,finish_at,remaining_minutes_snapshot,frozen) values (?,?,?,?,'2026-12-01T02:00Z','2026-12-01T03:00Z',60,true)",f.project(),v2,a,f.member()));
        jdbc.update(entry,f.project(),v2,a,f.member());
        rejects(()->jdbc.update("delete from schedule_entries where schedule_version_id=?",v1));
    }
    @Test void aiGenerationSuccessDoesNotAuthorizeReportPublication() {
        Fixture f=fixture();UUID job=UUID.randomUUID(),report=UUID.randomUUID();
        jdbc.update("insert into ai_jobs(id,workspace_id,project_id,job_type,model_name,connection_id,route_snapshot,prompt_version,status,output_json,validation_status) values (?,?,?,'REPORT_SUMMARY','model','route','{}','1','SUCCESS','{}','INVALID')",job,f.workspace(),f.project());
        jdbc.update("insert into reports(id,project_id,type,period_start,period_end,content,ai_job_id,visible_to_client) values (?,?,'WEEKLY',current_date,current_date,'{}',?,true)",report,f.project(),job);
        rejects(()->jdbc.update("update reports set status='PUBLISHED',approved_by=?,published_at=now() where id=?",f.owner(),report));
        jdbc.update("update ai_jobs set validation_status='VALID' where id=?",job);
        jdbc.update("update reports set status='PUBLISHED',approved_by=?,published_at=now() where id=?",f.owner(),report);
        assertThat(jdbc.queryForObject("select count(*) from client_published_reports",Integer.class)).isEqualTo(1);
    }
    @Test void milestoneAcceptanceRequiresClientAndPublishedScope() {
        Fixture f=fixture();UUID client=addClient(f),m=UUID.randomUUID();
        jdbc.update("insert into milestones(id,project_id,code,name,due_date) values (?,?,'MS-1','Milestone',current_date+30)",m,f.project());
        String accept="update milestones set status='ACCEPTED',acceptance_status='ACCEPTED',accepted_by=?,accepted_at=now() where id=?";
        rejects(()->jdbc.update(accept,client,m));
        jdbc.update("update milestones set published_to_client=true where id=?",m);
        rejects(()->jdbc.update(accept,f.member(),m));jdbc.update(accept,client,m);
    }
    @Test void tenantAndUserBindingsCannotBeMoved() {
        Fixture a=fixture(),b=fixture();
        rejects(()->jdbc.update("update workspace_members set workspace_id=? where workspace_id=? and user_id=?",b.workspace(),a.workspace(),a.member()));
        UUID user=addUser("Another");
        rejects(()->jdbc.update("update project_members set user_id=? where project_id=? and user_id=?",user,a.project(),a.member()));
        rejects(()->jdbc.update("update projects set timezone='Invalid/Zone' where id=?",a.project()));
    }
    @Test void emailIsCaseInsensitiveAndOtpCannotBeReusedOrExtended() {
        jdbc.update("insert into users(email,display_name) values ('Mixed@Example.test','Mixed')");
        rejects(()->jdbc.update("insert into users(email,display_name) values ('mixed@example.TEST','Duplicate')"));
        UUID id=UUID.randomUUID();
        jdbc.update("insert into auth_tokens(id,email,purpose,token_hash,expires_at) values (?,'otp@example.test','VERIFY_EMAIL','otp-hash',now()+interval '10 minutes')",id);
        jdbc.update("update auth_tokens set used_at=now(),attempts=1 where id=?",id);
        rejects(()->jdbc.update("update auth_tokens set used_at=null where id=?",id));
        rejects(()->jdbc.update("update auth_tokens set expires_at=expires_at+interval '1 hour' where id=?",id));
    }
    @Test void capacityValueObjectsRoundTripWithoutLosingTimezoneOrSkillTypes() {
        Fixture f=fixture();TimeOff off=new TimeOff(Instant.parse("2026-12-24T00:00:00Z"),Instant.parse("2026-12-25T00:00:00Z"));
        UUID id=tx.execute(s->{
            DeveloperCapacity c=new DeveloperCapacity();c.setProjectId(f.project());c.setUserId(f.member());c.setMinutesPerWorkday(480);
            c.setEffectiveFrom(java.time.LocalDate.of(2026,12,1));c.setSkills(new ArrayList<>(List.of(new SkillLevel("Java",4))));
            c.setWeeklySlots(new ArrayList<>(List.of(new WeeklySlot(1,540,1020))));c.setTimeOff(new ArrayList<>(List.of(off)));
            capacities.save(c);capacities.flush();return c.getId();
        });
        var read=capacities.findByIdAndProjectId(id,f.project()).orElseThrow();
        assertThat(read.getSkills()).containsExactly(new SkillLevel("java",4));
        assertThat(read.getTimeOff()).containsExactly(off);
        rejects(()->jdbc.update("update developer_capacity set skills='[{\"skill\":\"java\",\"level\":4},{\"skill\":\"JAVA\",\"level\":3}]'::jsonb where id=?",id));
    }
}
