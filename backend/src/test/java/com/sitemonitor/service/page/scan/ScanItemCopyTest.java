package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScanItem#measurementCopy()} (prs3 G): ölçülen HER alan kopyalanır, analizin yazdıkları sıfırlanır. Yansımayla — yeni bir
 * alan eklenip kopyaya yazılmazsa (ya da analiz alanı listeye eklenmezse) bu test düşer.
 */
class ScanItemCopyTest {

    /** Analizin (PageResourceAnalyzer + derin bölümler) nesneye yazdığı alanlar — kopyada varsayılana döner. */
    static final Set<String> ANALYSIS_WRITTEN = Set.of("startMs", "findings", "renderBlocking", "integrity",
            "thirdPartyCategory", "parent", "seq");

    @Test
    @DisplayName("ölçülen her alan kopyalanır; analizin yazdıkları (başlangıç, bulgular, engelleme, SRI, 3. taraf türü, sıra) sıfırlanır")
    void everyMeasuredFieldIsCopied() throws Exception {
        ScanItem src = new ScanItem();
        ScanItem fresh = new ScanItem();
        int n = 0;
        for (Field f : ScanItem.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Class<?> t = f.getType();
            n++;
            if (Set.class.isAssignableFrom(t)) {
                @SuppressWarnings("unchecked") Set<String> s = (Set<String>) f.get(src);
                s.add("X_" + f.getName());
                continue;
            }
            Object v;
            if (t == String.class) v = "v-" + f.getName();
            else if (t == int.class || t == Integer.class) v = 7 + n;
            else if (t == long.class || t == Long.class) v = 1000L + n;
            else if (t == boolean.class || t == Boolean.class) v = Boolean.TRUE;
            else if (Map.class.isAssignableFrom(t)) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("k", "v");
                v = m;
            } else throw new AssertionError("bilinmeyen alan türü — teste ekleyin: " + f);
            f.set(src, v);
        }

        ScanItem copy = src.measurementCopy();
        for (Field f : ScanItem.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Object got = f.get(copy);
            if (ANALYSIS_WRITTEN.contains(f.getName())) {
                assertThat(got).as("analiz alanı sıfırlanmalı: " + f.getName()).isEqualTo(f.get(fresh));
            } else {
                assertThat(got).as("ölçülen alan kopyalanmalı: " + f.getName()).isEqualTo(f.get(src));
            }
        }
        assertThat(copy.headers).isNotSameAs(src.headers);
        copy.findings.add("YENI");
        assertThat(src.findings).doesNotContain("YENI");
    }
}
