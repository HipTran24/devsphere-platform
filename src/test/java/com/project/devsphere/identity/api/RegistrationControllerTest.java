package com.project.devsphere.identity.api;

import com.project.devsphere.identity.application.RegistrationService;
import com.project.devsphere.identity.application.RegistrationService.ChallengeReceipt;
import com.project.devsphere.identity.application.RegistrationService.RegistrationReceipt;
import com.project.devsphere.identity.application.RegistrationService.Rejection;
import com.project.devsphere.identity.application.RegistrationService.VerificationOutcome;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationControllerTest {
    private final RegistrationService service = mock(RegistrationService.class);
    private final UUID user = UUID.randomUUID();
    private final UUID challenge = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(service)).build();
    }

    @Test
    void postsNormalizedRegistrationAndReturnsPendingChallenge() throws Exception {
        when(service.register("alex@example.test", "a long test password", "Alex"))
                .thenReturn(new RegistrationReceipt(user, challenge, "a***@example.test", "PENDING", Instant.now()));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
                {"email":" Alex@example.test ","password":"a long test password","displayName":" Alex "}
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(user.toString()))
                .andExpect(jsonPath("$.challengeId").value(challenge.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectsInvalidRegistrationWithoutPassingValuesThrough() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
                {"email":"bad","password":"short","displayName":" "}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void verificationUsesChallengeIdAndAcceptsLeadingZero() throws Exception {
        when(service.verify(challenge, "003456")).thenReturn(new VerificationOutcome(user, true));
        mvc.perform(post("/api/v1/auth/verify-email").contentType("application/json")
                        .content("{\"challengeId\":\"" + challenge + "\",\"code\":\"003456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void resendReturnsRetryAfterForThrottledChallenge() throws Exception {
        when(service.resend(challenge)).thenThrow(new Rejection("RATE_LIMITED", 18));
        mvc.perform(post("/api/v1/auth/verification/resend").contentType("application/json")
                        .content("{\"challengeId\":\"" + challenge + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "18"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void resendReturnsNewChallengeAfterSuccess() throws Exception {
        UUID next = UUID.randomUUID();
        when(service.resend(challenge)).thenReturn(new ChallengeReceipt(next, "a***@example.test", Instant.now()));
        mvc.perform(post("/api/v1/auth/verification/resend").contentType("application/json")
                        .content("{\"challengeId\":\"" + challenge + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.challengeId").value(next.toString()));
    }
}
