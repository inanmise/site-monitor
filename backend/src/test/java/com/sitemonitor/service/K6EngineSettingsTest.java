package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** k6 motoru ayarları (2026-10-29): GODEBUG izin listesi, User-Agent, tarama dışı kimlikler, ikili yolları. */
class K6EngineSettingsTest {

    @Test
    @DisplayName("GODEBUG: izinli anahtar + 0/1 kabul, normalize (küçük harf, boşluksuz, sıra korunur)")
    void godebugStrictAccepts() {
        assertThat(K6EngineSettings.normalizeGodebug(" TLSRSAKEX = 1 , tlsmlkem=0 ")).isEqualTo("tlsrsakex=1,tlsmlkem=0");
        assertThat(K6EngineSettings.normalizeGodebug("tls3des=1,tlssha1=1,x509negativeserial=1,rsa1024min=0,tlssecpmlkem=0"))
                .isEqualTo("tls3des=1,tlssha1=1,x509negativeserial=1,rsa1024min=0,tlssecpmlkem=0");
    }

    @Test
    @DisplayName("GODEBUG: bilinmeyen anahtar, 0/1 dışı değer, tekrar, aşırı uzunluk → 400 alan godebug")
    void godebugStrictRejects() {
        for (String bad : new String[] { "gctrace=1", "tlsrsakex=2", "tlsrsakex", "tlsrsakex=1,tlsrsakex=0",
                "netdns=go", "x".repeat(400) }) {
            assertThatThrownBy(() -> K6EngineSettings.validate(K6EngineSettings.KEY_GODEBUG, bad))
                    .as(bad).isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("godebug"));
        }
    }

    @Test
    @DisplayName("GODEBUG çalışma anı: geçersiz parçalar atlanır, hiçbir koşulda fırlatmaz")
    void godebugLenient() {
        assertThat(K6EngineSettings.effectiveGodebug("tlsrsakex=1,gctrace=1,tls3des=9,tlsmlkem=0")).isEqualTo("tlsrsakex=1,tlsmlkem=0");
        assertThat(K6EngineSettings.effectiveGodebug(null)).isEmpty();
        assertThat(K6EngineSettings.effectiveGodebug("   ")).isEmpty();
        assertThat(K6EngineSettings.effectiveGodebug("x".repeat(1000))).isEmpty();
        assertThat(K6EngineSettings.effectiveGodebug("tlsrsakex=1,tlsrsakex=0")).isEqualTo("tlsrsakex=1");
    }

    @Test
    @DisplayName("User-Agent: yazdırılabilir ASCII ≤ 200; satır sonu / Türkçe harf reddedilir; çalışma anında geçersiz = boş")
    void userAgent() {
        K6EngineSettings.validate(K6EngineSettings.KEY_USER_AGENT, "SiteMonitor-k6/1.0 (+https://example.org)");
        assertThatThrownBy(() -> K6EngineSettings.validate(K6EngineSettings.KEY_USER_AGENT, "a\nb"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("user_agent"));
        assertThatThrownBy(() -> K6EngineSettings.validate(K6EngineSettings.KEY_USER_AGENT, "İzleme"))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> K6EngineSettings.validate(K6EngineSettings.KEY_USER_AGENT, "x".repeat(201)))
                .isInstanceOf(FieldValidationException.class);
        assertThat(K6EngineSettings.effectiveUserAgent("  SM-k6  ")).isEqualTo("SM-k6");
        assertThat(K6EngineSettings.effectiveUserAgent("a\tb")).isEmpty();
        assertThat(K6EngineSettings.effectiveUserAgent(null)).isEmpty();
    }

    @Test
    @DisplayName("Tarama dışı kimlikler: sayı listesi; harf / sıfır → 400; çalışma anı gevşek")
    void excludedIds() {
        K6EngineSettings.validate(K6EngineSettings.KEY_SCAN_EXCLUDED, "3, 7,,12");
        assertThatThrownBy(() -> K6EngineSettings.validate(K6EngineSettings.KEY_SCAN_EXCLUDED, "3,abc"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("excluded_ids"));
        assertThatThrownBy(() -> K6EngineSettings.validate(K6EngineSettings.KEY_SCAN_EXCLUDED, "0"))
                .isInstanceOf(FieldValidationException.class);
        assertThat(K6EngineSettings.effectiveExcludedIds("3,x,7,3")).containsExactly(3L, 7L);
        assertThat(K6EngineSettings.joinIds(java.util.List.of(5L, 9L))).isEqualTo("5,9");
    }

    @Test
    @DisplayName("İkili yolları: tek yol; boşluk / tırnak / kabuk karakteri → 400 (alan rollback_bin / candidate_bin)")
    void binPaths() {
        K6EngineSettings.validate(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, "/opt/k6-next/k6");
        K6EngineSettings.validate(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "k6");
        K6EngineSettings.validate(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "C:\\tools\\k6.exe");
        for (String bad : new String[] { "/opt/k6 next/k6", "k6;rm", "$(k6)", "\"k6\"", "k6|x" }) {
            assertThatThrownBy(() -> K6EngineSettings.validate(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, bad))
                    .as(bad).isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("candidate_bin"));
        }
        assertThatThrownBy(() -> K6EngineSettings.validate(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "a b"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("rollback_bin"));
    }

    @Test
    @DisplayName("Boş değer = varsayılana dön (doğrulanmaz)")
    void blankIsReset() {
        for (String k : K6EngineSettings.KEYS) K6EngineSettings.validate(k, "");
    }

    @Test
    @DisplayName("Tüm k6 motoru anahtarları katalogda, 'k6-engine' grubunda ve GLOBAL_ONLY")
    void catalogAndGlobalOnly() {
        for (String k : K6EngineSettings.KEYS) {
            AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(k);
            assertThat(s).as(k).isNotNull();
            assertThat(s.group()).as(k).isEqualTo(K6EngineSettings.GROUP);
            assertThat(AppSettingsCatalog.GLOBAL_ONLY).as(k).contains(k);
        }
    }

    @Test
    @DisplayName("Kaldırılan GODEBUG kaçışları (2026-11-16): Go 1.27+ motorda yalnız eski davranışı getiren değer yok sayılır; "
            + "Go 1.26 (k6 2.3 / 2.4) ve bilinmeyen Go sürümünde liste BOŞ")
    void ignoredGodebugKeys() {
        String stored = "tlsrsakex=1,tls3des=1,tlssha1=1,tlsmlkem=0";
        // Bugünkü ikililer go1.26.x → hiçbiri yok sayılmaz
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "go1.26.8")).isEmpty();
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "go1.26.11")).isEmpty();
        // Go 1.27 ve sonrası: tlsrsakex + tls3des (kayıt sırasıyla); tlssha1 / tlsmlkem kaldırılmadı
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "go1.27.0")).containsExactly("tlsrsakex", "tls3des");
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "go1.27rc1")).containsExactly("tlsrsakex", "tls3des");
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "go1.30.2")).containsExactly("tlsrsakex", "tls3des");
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "go2.0")).containsExactly("tlsrsakex", "tls3des");
        // =0 zaten yeni (zorunlu) davranış — kayıp yok, uyarı yok
        assertThat(K6EngineSettings.ignoredGodebugKeys("tlsrsakex=0,tls3des=0", "go1.27.0")).isEmpty();
        // Go sürümü bilinmiyor / okunamıyor → asla tahmin edilmez
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, null)).isEmpty();
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "devel")).isEmpty();
        assertThat(K6EngineSettings.ignoredGodebugKeys(stored, "go")).isEmpty();
        // Kayıt boş / kapalı / bozuk → boş; bozuk parça atlanır (gevşek okuma)
        assertThat(K6EngineSettings.ignoredGodebugKeys(null, "go1.27.0")).isEmpty();
        assertThat(K6EngineSettings.ignoredGodebugKeys("none", "go1.27.0")).isEmpty();
        assertThat(K6EngineSettings.ignoredGodebugKeys("gctrace=1,TLS3DES=1", "go1.28.1")).containsExactly("tls3des");
        // Tablo izinli anahtarların alt kümesi (kaldırılan bir anahtar izin listesinden düşmüş olmasın)
        assertThat(K6EngineSettings.godebugKeys().keySet()).containsAll(K6EngineSettings.godebugRemovals().keySet());
    }

    @Test
    @DisplayName("Aday ikili varsayılanı (2026-11-16): application.properties = /opt/k6-candidate/k6 = Dockerfile'ın aday "
            + "ikiliyi kopyaladığı yol; etkin ikili varsayılanı k6 kalır; yol kayıt doğrulamasından geçer")
    void candidateDefaultMatchesImage() throws Exception {
        String props = java.nio.file.Files.readString(
                java.nio.file.Path.of("src", "main", "resources", "application.properties"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(props).contains("site.monitor.scripted.k6-bin-candidate=${SCRIPTED_K6_BIN_CANDIDATE:/opt/k6-candidate/k6}");
        assertThat(props).contains("site.monitor.scripted.k6-bin=${SCRIPTED_K6_BIN:k6}");
        String dockerfile = java.nio.file.Files.readString(java.nio.file.Path.of("..", "Dockerfile"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(dockerfile).contains("COPY --from=k6-bin /k6-candidate /opt/k6-candidate/k6");
        assertThat(dockerfile).contains("COPY --from=k6-bin /k6 /usr/bin/k6");
        assertThat(dockerfile).contains("chmod 0111 /usr/bin/k6 /opt/k6-candidate/k6");
        assertThat(dockerfile).containsPattern("(?m)^ARG K6_CANDIDATE_VERSION=\\d+\\.\\d+\\.\\d+$");
        assertThat(dockerfile).containsPattern("(?m)^ARG K6_CANDIDATE_SHA256_AMD64=[0-9a-f]{64}$");
        assertThat(dockerfile).containsPattern("(?m)^ARG K6_CANDIDATE_SHA256_ARM64=[0-9a-f]{64}$");
        K6EngineSettings.validate(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, "/opt/k6-candidate/k6");
        // default_path kuralının tek kaynağı (2026-11-17): sabit = properties varsayılanı = Dockerfile'ın kopyaladığı yol
        assertThat(props).contains("${SCRIPTED_K6_BIN_CANDIDATE:" + K6EngineSettings.CANDIDATE_IMAGE_PATH + "}");
        assertThat(dockerfile).contains("COPY --from=k6-bin /k6-candidate " + K6EngineSettings.CANDIDATE_IMAGE_PATH);
        assertThat(K6EngineSettings.isImageCandidatePath(" /opt/k6-candidate/k6 ")).isTrue();
        assertThat(K6EngineSettings.isImageCandidatePath("/opt/k6-candidate/k6-2.4")).isFalse();
        assertThat(K6EngineSettings.isImageCandidatePath("/srv/k6/k6")).isFalse();
        assertThat(K6EngineSettings.isImageCandidatePath(null)).isFalse();
    }

    @Test
    @DisplayName("'none' = kapalı: doğrulamadan geçer, çalışma anında boş, ekranda boş (inceleme L1)")
    void offSentinel() {
        assertThat(K6EngineSettings.isOff(null)).isTrue();
        assertThat(K6EngineSettings.isOff(" ")).isTrue();
        assertThat(K6EngineSettings.isOff("NONE")).isTrue();
        assertThat(K6EngineSettings.isOff("/opt/k6-next/k6")).isFalse();
        K6EngineSettings.validate(K6EngineSettings.KEY_GODEBUG, "none");
        K6EngineSettings.validate(K6EngineSettings.KEY_USER_AGENT, "none");
        K6EngineSettings.validate(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, "none");
        assertThat(K6EngineSettings.effectiveGodebug("none")).isEmpty();
        assertThat(K6EngineSettings.effectiveUserAgent("none")).isEmpty();
        assertThat(K6EngineSettings.shown("none")).isEmpty();
        assertThat(K6EngineSettings.shown(" SM/1 ")).isEqualTo("SM/1");
    }
}
