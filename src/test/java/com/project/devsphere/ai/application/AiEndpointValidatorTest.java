package com.project.devsphere.ai.application;

import com.project.devsphere.ai.domain.AiProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiEndpointValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://169.254.169.254",
            "http://169.254.169.254/latest/meta-data",
            "https://169.254.170.2",
            "https://100.100.100.200",
            "https://[fd00:ec2::254]",
            "https://[::ffff:169.254.169.254]",
            "https://metadata.google.internal",
            "https://metadata",
            "https://instance-data",
            "https://instance-data.ec2.internal",
            "https://169.254.10.20",
            "https://[fe80::1]",
            "https://224.0.0.1",
            "https://[ff02::1]",
            "https://0.0.0.0",
            "https://[::]",
            "https://user:pass@api.openai.com",
            "https://api.openai.com?token=abc",
            "https://api.openai.com#frag",
            "https://2130706433",
            "https://0177.0.0.1",
            "https://0x7f000001",
            "https://127.1",
            "ftp://api.openai.com",
            "not a uri"
    })
    @DisplayName("Rejects metadata, link-local, multicast, unspecified, credentialed, and obfuscated URLs even when loopback self-host is allowed")
    void rejectsForbiddenEndpointsAlways(String url) {
        assertThatThrownBy(() -> AiEndpointValidator.validate(url, AiProviderType.OPENAI, true))
                .isInstanceOf(AiException.class);
        assertThatThrownBy(() -> AiEndpointValidator.validate(url, AiProviderType.VLLM_SELF_HOST, true))
                .isInstanceOf(AiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.openai.com",
            "https://localhost:8443",
            "https://127.0.0.1:8443",
            "https://[::1]:8443",
            "https://[::ffff:127.0.0.1]:8443",
            "https://10.0.1.50",
            "https://172.16.0.10",
            "https://192.168.1.20",
            "https://[fd00::1]"
    })
    @DisplayName("OPENAI strictly requires HTTPS and rejects loopback/private addresses even when allowLoopbackSelfHost=true")
    void openAiRejectsHttpLoopbackAndPrivateAddresses(String url) {
        assertThatThrownBy(() -> AiEndpointValidator.validate(url, AiProviderType.OPENAI, true))
                .isInstanceOf(AiException.class);
    }

    @Test
    @DisplayName("OPENAI accepts public HTTPS endpoint and strips trailing slash")
    void openAiAcceptsPublicHttps() {
        URI validated = AiEndpointValidator.validate("https://api.openai.com/", AiProviderType.OPENAI, false);
        assertThat(validated.toString()).isEqualTo("https://api.openai.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://vllm.internal.example:8000",
            "http://127.0.0.1:8000",
            "https://127.0.0.1:8000",
            "https://localhost:8000",
            "https://[::1]:8000"
    })
    @DisplayName("VLLM_SELF_HOST rejects HTTP and loopback when allowLoopbackSelfHost=false")
    void vllmRejectsHttpAndLoopbackWhenDisabled(String url) {
        assertThatThrownBy(() -> AiEndpointValidator.validate(url, AiProviderType.VLLM_SELF_HOST, false))
                .isInstanceOf(AiException.class);
    }

    @Test
    @DisplayName("VLLM_SELF_HOST allows HTTPS private VPC endpoints always, and HTTP/loopback only when enabled")
    void vllmAllowsHttpsAlwaysAndLoopbackWhenEnabled() {
        assertThat(AiEndpointValidator.validate("https://10.0.1.50:8443/", AiProviderType.VLLM_SELF_HOST, false).toString())
                .isEqualTo("https://10.0.1.50:8443");
        assertThat(AiEndpointValidator.validate("http://127.0.0.1:8000/", AiProviderType.VLLM_SELF_HOST, true).toString())
                .isEqualTo("http://127.0.0.1:8000");
        assertThat(AiEndpointValidator.validate("http://localhost:8000", AiProviderType.VLLM_SELF_HOST, true).getHost())
                .isEqualTo("localhost");
        assertThat(AiEndpointValidator.validate("http://[::1]:8000", AiProviderType.VLLM_SELF_HOST, true).getPort())
                .isEqualTo(8000);
    }
}
