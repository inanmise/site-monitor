package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordGeneratorTest {

    @RepeatedTest(50)
    @DisplayName("generate() returns a 10-character string")
    void generate_isTenChars() {
        String pwd = PasswordGenerator.generate();
        assertThat(pwd).hasSize(10);
    }

    @RepeatedTest(50)
    @DisplayName("generate() contains at least one upper, one lower and one digit")
    void generate_hasAllRequiredClasses() {
        String pwd = PasswordGenerator.generate();
        assertThat(pwd).matches(".*[A-Z].*").as("upper");
        assertThat(pwd).matches(".*[a-z].*").as("lower");
        assertThat(pwd).matches(".*\\d.*").as("digit");
    }

    @RepeatedTest(50)
    @DisplayName("generate() avoids visually ambiguous characters I, l, O, 0, 1")
    void generate_noAmbiguousCharacters() {
        String pwd = PasswordGenerator.generate();
        assertThat(pwd).doesNotContain("I", "l", "O", "0", "1");
    }

    @Test
    @DisplayName("generate() produces highly unique results across many calls")
    void generate_hasEntropy() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) seen.add(PasswordGenerator.generate());
        // ~58^10 keyspace; 1000 samples should have effectively no collisions.
        assertThat(seen).hasSizeGreaterThanOrEqualTo(999);
    }
}
