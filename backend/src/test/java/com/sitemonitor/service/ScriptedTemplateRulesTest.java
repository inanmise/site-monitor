package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Şablon yazım kuralları — kullanıcı katkısına açılan kütüphanenin kapısı.
 *
 * <p>Bu testler, silinen {@code frontend/src/test/scriptedTemplates.test.js}'in ÇALIŞTIRMA-ANI
 * karşılığıdır. Katalog testi (11 küratörlü şablon) ile birlikte çalışırlar: orada her kural
 * serttir, burada yalnız yanlış-pozitifi olmayanlar engeller.
 */
class ScriptedTemplateRulesTest {

    private static final String OK_SCRIPT = """
            import http from 'k6/http';
            import { check } from 'k6';
            export default function () {
              const r = http.get(__ENV.BASE_URL, { timeout: '15s' });
              check(r, { 'status 200': (res) => res.status === 200 });
            }
            """;

    private static final List<Map<String, Object>> ENV_BASE_URL =
            List.of(Map.of("name", "BASE_URL", "secret", false));

    private static ScriptedTemplateRules.TemplateDiagnostics check(String script) {
        return ScriptedTemplateRules.check(null, "Ad", "Açıklama", "Ne zaman", script, ENV_BASE_URL, "v0.49.0");
    }

    @Test
    @DisplayName("Sağlıklı şablon: engel YOK, uyarı YOK")
    void healthyTemplate_passesClean() {
        var d = check(OK_SCRIPT);
        assertThat(d.blocked()).isFalse();
        assertThat(d.warnings()).isEmpty();
    }

    // ── BLOCK dalı: script'i çalışamaz kılan, yanlış-pozitifi olmayan kurallar ──────────

    @Test
    @DisplayName("BLOCK: ad veya script boş")
    void blocksBlankNameOrScript() {
        assertThat(ScriptedTemplateRules.check(null, "  ", "d", "w", OK_SCRIPT, ENV_BASE_URL, "v0.49.0").blocked()).isTrue();
        assertThat(ScriptedTemplateRules.check(null, "Ad", "d", "w", "   ", ENV_BASE_URL, "v0.49.0").blocked()).isTrue();
    }

    @Test
    @DisplayName("BLOCK: `export default function` yoksa k6 script'i HİÇ çalıştıramaz")
    void blocksMissingDefaultExport() {
        var d = check("import http from 'k6/http';\nfunction main() {}\n");
        assertThat(d.blocked()).isTrue();
        assertThat(d.blocking()).contains("export default function");
    }

    /**
     * En kritik BLOCK: seçici `tpl:<token>` üretiyor, token ya builtinKey ya sayısal id.
     * Tamamen rakamdan oluşan bir anahtar iki dalı çakıştırır ve ESKİ monitörler yanlış
     * şablona çözülür — sessiz ve geri dönüşü zor bir veri hatası.
     */
    @Test
    @DisplayName("BLOCK: builtinKey slug olmalı — tamamen rakam olan anahtar tpl: çözümünü çakıştırır")
    void blocksNonSlugBuiltinKey() {
        assertThat(ScriptedTemplateRules.check("12345", "Ad", "d", "w", OK_SCRIPT, ENV_BASE_URL, "v0.49.0").blocked()).isTrue();
        assertThat(ScriptedTemplateRules.check("Smoke-Health", "Ad", "d", "w", OK_SCRIPT, ENV_BASE_URL, "v0.49.0").blocked()).isTrue();
        assertThat(ScriptedTemplateRules.check("smoke-health", "Ad", "d", "w", OK_SCRIPT, ENV_BASE_URL, "v0.49.0").blocked()).isFalse();
    }

    @Test
    @DisplayName("BLOCK: env tanımı DEĞER taşıyorsa reddedilir (sessizce ayıklanmaz)")
    void blocksEnvCarryingValue() {
        var withValue = List.<Map<String, Object>>of(Map.of("name", "API_KEY", "secret", true, "value", "gizli"));
        var d = ScriptedTemplateRules.check(null, "Ad", "d", "w", OK_SCRIPT, withValue, "v0.49.0");
        assertThat(d.blocked()).isTrue();
        assertThat(d.blocking()).contains("gizli değer taşıyamaz");
    }

    // ── Sözdizimi: dağıtımdaki motora BAĞLI karar ──────────────────────────────────────

    @Test
    @DisplayName("BLOCK: k6 0.49 (legacy) modern sözdizimini ayrıştıramaz — ?. / ?? / {...}")
    void blocksModernSyntaxOnLegacyK6() {
        for (String bad : List.of(
                "export default function () { const x = a?.b; }",
                "export default function () { const x = a ?? b; }",
                "export default function () { const o = { ...base }; }")) {
            var d = ScriptedTemplateRules.check(null, "Ad", "d", "w", bad, List.of(), "v0.49.0");
            assertThat(d.blocked()).as("legacy k6'da engellenmeli: %s", bad).isTrue();
        }
    }

    @Test
    @DisplayName("k6 0.53+ (modern): aynı sözdizimi UYARI olur, engel DEĞİL")
    void modernK6_warnsInsteadOfBlocking() {
        var d = ScriptedTemplateRules.check(null, "Ad", "d", "w",
                "export default function () { const x = a?.b; }", List.of(), "v0.53.0");
        assertThat(d.blocked()).isFalse();
        assertThat(d.warnings()).anyMatch(w -> w.contains("?."));
    }

    @Test
    @DisplayName("k6 sürümü okunamıyorsa BLOKLAMA yok — bilgisizlik üzerine kilitleme yapılmaz")
    void unknownK6Version_doesNotBlock() {
        var d = ScriptedTemplateRules.check(null, "Ad", "d", "w",
                "export default function () { const x = a?.b; }", List.of(), null);
        assertThat(d.blocked()).isFalse();
        assertThat(d.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("syntaxLevel: eşik sürümü (0.53.0) modern sayılır, altı legacy")
    void syntaxLevel_threshold() {
        assertThat(ScriptedTemplateRules.syntaxLevel("v0.49.0")).isEqualTo("legacy");
        assertThat(ScriptedTemplateRules.syntaxLevel("v0.52.9")).isEqualTo("legacy");
        assertThat(ScriptedTemplateRules.syntaxLevel("v0.53.0")).isEqualTo("modern");
        assertThat(ScriptedTemplateRules.syntaxLevel("v1.0.0")).isEqualTo("modern");
        assertThat(ScriptedTemplateRules.syntaxLevel("bozuk")).isEqualTo("unknown");
        assertThat(ScriptedTemplateRules.syntaxLevel(null)).isEqualTo("unknown");
    }

    // ── WARN dalı: bağlam bilmeyen, yanlış-pozitifi olan kurallar ──────────────────────

    @Test
    @DisplayName("WARN: vus/iterations/stages tanımlı — motor eziyor, script yine çalışır")
    void warnsOnEngineOverrides() {
        var d = check("export options = { vus: 5 };\nexport default function () { http.get(__ENV.BASE_URL, { timeout: '5s' }); }");
        assertThat(d.blocked()).isFalse();
        assertThat(d.warnings()).anyMatch(w -> w.contains("vus"));
    }

    @Test
    @DisplayName("WARN: timeout'suz istek — engellenmez, çünkü paylaşılan params nesnesi yanlış-pozitif verir")
    void warnsOnMissingTimeout() {
        var d = check("export default function () { http.get(__ENV.BASE_URL); }");
        assertThat(d.blocked()).isFalse();
        assertThat(d.warnings()).anyMatch(w -> w.contains("timeout"));
    }

    @Test
    @DisplayName("WARN: 60 sn ve üstü istek timeout'u — süreç bütçesinden kısa olmalı")
    void warnsOnTooLongTimeout() {
        var d = check("export default function () { http.get(__ENV.BASE_URL, { timeout: '90s' }); }");
        assertThat(d.warnings()).anyMatch(w -> w.contains("60 sn"));
    }

    @Test
    @DisplayName("WARN: tanımsız __ENV — ScriptedCheckerService'in belgeli kararı yeniden yazılmaz, ÇAĞRILIR")
    void warnsOnUndefinedEnv_viaSharedAudit() {
        var d = ScriptedTemplateRules.check(null, "Ad", "d", "w",
                "export default function () { http.get(__ENV.TANIMSIZ, { timeout: '5s' }); }", List.of(), "v0.49.0");
        assertThat(d.blocked()).isFalse();
        assertThat(d.warnings()).anyMatch(w -> w.contains("TANIMSIZ"));
    }

    @Test
    @DisplayName("WARN: açıklama/kullanım metni boş — şablon seçilebilir olmaktan çıkar ama engellenmez")
    void warnsOnMissingProse() {
        var d = ScriptedTemplateRules.check(null, "Ad", "", "  ", OK_SCRIPT, ENV_BASE_URL, "v0.49.0");
        assertThat(d.blocked()).isFalse();
        assertThat(d.warnings()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("null env listesi ve null metinlerde istisna YOK")
    void nullInputsAreSafe() {
        var d = ScriptedTemplateRules.check(null, "Ad", null, null, OK_SCRIPT, null, "v0.49.0");
        assertThat(d.blocked()).isFalse();
    }
}
