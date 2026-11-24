package com.project.devsphere.ai.domain;

import com.project.devsphere.ai.application.AiException;

import java.util.Locale;

/** Supported AI connection provider types stored in {@code workspaces.ai_connections}. */
public enum AiProviderType {
    OPENAI,
    VLLM_SELF_HOST;

    public boolean isCloud() {
        return this == OPENAI;
    }

    public static AiProviderType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AiException.invalidInput("provider must not be blank");
        }
        try {
            return AiProviderType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw AiException.invalidInput("Unsupported AI provider: " + raw + ". Allowed: OPENAI, VLLM_SELF_HOST");
        }
    }
}
