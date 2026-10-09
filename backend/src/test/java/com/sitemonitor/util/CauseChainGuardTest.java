package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Neden zinciri yürüyüşleri sonsuza dek dönemez (2026-10-09). {@code A.initCause(B); B.initCause(A)} geçerli bir iki
 * düğümlü döngüdür; yalnız "kendine işaret" denetimi bunu durdurmaz.
 */
class CauseChainGuardTest {

    /** İki düğümlü neden döngüsü: a → b → a → … */
    private static Throwable cyclic() {
        Exception a = new Exception("a");
        Exception b = new Exception("b");
        a.initCause(b);
        b.initCause(a);
        return a;
    }

    @Test
    @DisplayName("KAPI (kaynak): her getCause() döngüsü tavanlı — causeDepth / guard sayacı olmadan while/for yürüyüşü yok")
    void everyCauseLoopIsBounded() throws IOException {
        Pattern loopOverCause = Pattern.compile("\\b(while|for)\\s*\\(.*getCause\\(\\)");
        List<String> offenders = new ArrayList<>();
        Path root = Paths.get("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String ln = lines.get(i);
                    if (!loopOverCause.matcher(ln).find()) continue;
                    if (ln.contains("causeDepth++") || ln.contains("guard++")) continue;
                    offenders.add(root.relativize(f) + ":" + (i + 1) + "  " + ln.trim());
                }
            }
        }
        assertThat(offenders).as("neden zinciri yürüyüşüne CauseChain.MAX_DEPTH tavanı ekleyin").isEmpty();
    }

    @Test
    @DisplayName("Davranış: döngüsel neden zinciri sınıflandırıcıları ve istisna işleyicisini kilitlemez")
    void cyclicCauseChainTerminates() {
        Throwable t = cyclic();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            com.sitemonitor.service.DiagnosticErrorClassifier.classify(t);
            com.sitemonitor.service.keyword.KeywordFailureClassifier.codeForException(t);
            com.sitemonitor.service.CaAutoPinService.isTrustFailure(t);
            com.sitemonitor.service.failure.CheckFailureClassifier.forException(t, false);
        });
    }
}
