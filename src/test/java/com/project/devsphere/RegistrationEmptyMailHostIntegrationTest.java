package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import com.project.devsphere.identity.infrastructure.mail.VerificationMailWorker;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "spring.mail.host="
})
@AutoConfigureMockMvc
class RegistrationEmptyMailHostIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JavaMailSender mailSender;
    @Autowired VerificationCodeCrypto crypto;
    @Autowired VerificationMailWorker worker;
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        hmac[0] = 1;
        encryption[0] = 2;
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
    }

    @Test
    void emptyHostDisablesIssuingResendingAndDispatchDespiteBootSenderBean() throws Exception {
        assertThat(mailSender).isInstanceOf(JavaMailSenderImpl.class);
        assertThat(((JavaMailSenderImpl) mailSender).getHost()).isEmpty();
        String email = UUID.randomUUID() + "@example.test";
        long jobsBefore = jdbc.queryForObject("select count(*) from background_jobs", Long.class);
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", "a long test password", "displayName", "New User"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_NOT_CONFIGURED"));
        mvc.perform(post("/api/v1/auth/forgot-password").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_NOT_CONFIGURED"));
        assertThat(jdbc.queryForObject("select count(*) from users where email=cast(? as citext)", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_tokens where email=cast(? as citext)", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(jobsBefore);

        UUID user = UUID.randomUUID();
        UUID challenge = UUID.randomUUID();
        String code = crypto.generateCode();
        jdbc.update("insert into users(id,email,display_name) values (?,cast(? as citext),'Pending User')", user, email);
        jdbc.update("""
                insert into auth_tokens(id,user_id,email,purpose,token_hash,created_at,expires_at)
                values (?,?,cast(? as citext),'VERIFY_EMAIL',?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '9 minutes')
                """, challenge, user, email, crypto.hash(challenge, code));
        mvc.perform(post("/api/v1/auth/verification/resend").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("challengeId", challenge))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_NOT_CONFIGURED"));
        assertThat(jdbc.queryForObject("select count(*) from auth_tokens where user_id=?", Long.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select used_at is null from auth_tokens where id=?", Boolean.class, challenge)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(jobsBefore);

        UUID job = jdbc.queryForObject("""
                insert into background_jobs(requested_by,job_kind,payload,idempotency_key,expires_at)
                values (?,'AUTH_VERIFY_EMAIL',
                    jsonb_build_object('schemaVersion',1,'challengeId',cast(? as text),'ciphertext',cast(? as text)),
                    ?,clock_timestamp()+interval '9 minutes') returning id
                """, UUID.class, user, challenge.toString(), crypto.encrypt(challenge, code), challenge.toString());
        worker.runJobCycle();
        assertThat(jdbc.queryForObject("select status from background_jobs where id=?", String.class, job)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select attempts from background_jobs where id=?", Integer.class, job)).isZero();
    }
}
