package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.SessionTokens;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokenConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SessionTokenConfiguration.class);

    @Test
    void missingKeyFailsStartupWithoutCreatingADefaultKey() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void wrongKeyLengthFailsStartup() {
        String key = Base64.getEncoder().encodeToString(new byte[31]);
        runner.withPropertyValues("devsphere.auth.session.signing-key=" + key).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void suitableKeyEnablesIssuing() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        runner.withPropertyValues("devsphere.auth.session.signing-key=" + Base64.getEncoder().encodeToString(key))
                .run(context -> assertThat(context).hasSingleBean(SessionTokens.class));
    }

    @Test
    void malformedKeyFailsStartupWithoutPublishingItsValue() {
        String invalidKey = "synthetic-invalid-signing-key!";
        runner.withPropertyValues("devsphere.auth.session.signing-key=" + invalidKey).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(org.springframework.core.NestedExceptionUtils
                    .getMostSpecificCause(context.getStartupFailure()).getMessage()).doesNotContain(invalidKey);
        });
    }
}
