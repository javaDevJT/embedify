package tech.javadevjt.embedify.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthControllerTest {
    @Test
    void sourceCommitRequiresExactlyFortyHexCharacters() {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        assertEquals(commit, HealthController.normalizeSourceCommit(commit));
        assertEquals("unknown", HealthController.normalizeSourceCommit("0123456789abcdef0123456789abcdef0123456"));
        assertEquals("unknown", HealthController.normalizeSourceCommit("not-a-commit"));
        assertEquals("unknown", HealthController.normalizeSourceCommit(null));
    }
}
