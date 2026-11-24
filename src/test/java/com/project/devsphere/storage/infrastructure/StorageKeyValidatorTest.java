package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeyValidatorTest {

    @Test
    void acceptsTenantScopedAttachmentKeys() {
        String key = "workspaces/7b1d/projects/91c2/attachments/5f3e/design_v2.final.pdf";
        assertThat(StorageKeyValidator.requireValidKey(key)).isEqualTo(key);
        assertThat(StorageKeyValidator.requireValidKey("a".repeat(StorageKeyValidator.MAX_KEY_LENGTH))).hasSize(1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../etc/passwd", "workspaces/../../secrets", "workspaces/a/..", "a/./b", "a..b/file", "/absolute/key",
            "with\u0000nul", "line\nbreak", "tab\tchar", "carriage\rreturn", "bell\u0007", "del\u007f", "back\\slash",
            "sep\u2028arator", " ", ""})
    void rejectsTraversalAbsoluteAndControlCharacterKeys(String key) {
        assertThatThrownBy(() -> StorageKeyValidator.requireValidKey(key))
                .isInstanceOf(StorageException.class)
                .satisfies(ex -> assertThat(((StorageException) ex).getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void rejectsNullAndOversizedKeys() {
        assertThatThrownBy(() -> StorageKeyValidator.requireValidKey(null)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> StorageKeyValidator.requireValidKey("a".repeat(1025))).isInstanceOf(StorageException.class);
    }

    @Test
    void enforcesTtlBetweenOneAndThreeHundredSeconds() {
        assertThat(StorageKeyValidator.requireValidTtl(1)).isEqualTo(1);
        assertThat(StorageKeyValidator.requireValidTtl(300)).isEqualTo(300);
        for (long invalid : new long[]{0, -1, 301, 900, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> StorageKeyValidator.requireValidTtl(invalid))
                    .as("ttl %d", invalid).isInstanceOf(StorageException.class);
        }
    }

    @Test
    void acceptsOnlyPlainMediaTypes() {
        assertThat(StorageKeyValidator.requireValidContentType("application/pdf")).isEqualTo("application/pdf");
        assertThat(StorageKeyValidator.requireValidContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .startsWith("application/vnd");
        for (String invalid : new String[]{null, "", "pdf", "text/plain; charset=utf-8", "text/html\r\nX-Injected: 1", "a/b/c", "*/*x"}) {
            assertThatThrownBy(() -> StorageKeyValidator.requireValidContentType(invalid))
                    .as("content type %s", invalid).isInstanceOf(StorageException.class);
        }
    }
}
