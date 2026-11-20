package com.project.devsphere;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.otp.hmac-key=",
        "devsphere.auth.otp.encryption-key=",
        "devsphere.auth.mail.from=",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "spring.mail.host="
})
@AutoConfigureMockMvc
class RegistrationUnavailableIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
    }

    @Test
    void databaseStartsAndRegistrationFailsClosedWithoutMailConfiguration() throws Exception {
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
        String email = UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"a long test password","displayName":"New User"}
                                """.formatted(email)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_NOT_CONFIGURED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(jdbc.queryForObject("select count(*) from users where email=cast(? as citext)",
                Integer.class, email)).isZero();
    }
}
