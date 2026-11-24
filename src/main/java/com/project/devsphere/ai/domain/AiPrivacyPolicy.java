package com.project.devsphere.ai.domain;

import com.project.devsphere.ai.application.AiException;

import java.util.Locale;

/** Workspace-level AI routing privacy policy stored in {@code workspaces.settings.aiPrivacyPolicy}. */
public enum AiPrivacyPolicy {
    CLOUD_ALLOWED,
    SELF_HOST_ONLY;

    public static AiPrivacyPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AiException.invalidInput("privacyPolicy must not be blank");
        }
        try {
            return AiPrivacyPolicy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw AiException.invalidInput("Unsupported privacyPolicy: " + raw + ". Allowed: CLOUD_ALLOWED, SELF_HOST_ONLY");
        }
    }

    public static AiPrivacyPolicy parseOrDefault(Object raw, AiPrivacyPolicy defaultPolicy) {
        if (raw == null) {
            return defaultPolicy;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return defaultPolicy;
        }
        try {
            return AiPrivacyPolicy.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return defaultPolicy;
        }
    }
}
