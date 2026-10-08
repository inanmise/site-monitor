package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sentetik izleme env politikası (2026-10-08, güvenlik denetimi). İki katman: kayıtta yeni girdi alan bazlı 400 alır;
 * koşumda saklı eski kayıttaki ayrılmış adlar sessizce düşer (koşum durmaz). Bkz. {@link ScriptedEnvPolicy}.
 */
class ScriptedEnvPolicyTest {

    private static Map<String, Object> var(String name, String value) {
        return Map.of("name", name, "value", value);
    }

    @Test
    @DisplayName("Ad biçimi: harf/alt çizgiyle başlar, yalnız [A-Za-z0-9_], ≤ 64")
    void nameShape() {
        assertThat(ScriptedEnvPolicy.validName("BASE_URL")).isTrue();
        assertThat(ScriptedEnvPolicy.validName("_x1")).isTrue();
        assertThat(ScriptedEnvPolicy.validName("A".repeat(64))).isTrue();
        assertThat(ScriptedEnvPolicy.validName("A".repeat(65))).isFalse();
        assertThat(ScriptedEnvPolicy.validName("1ABC")).isFalse();
        assertThat(ScriptedEnvPolicy.validName("base-url")).isFalse();
        assertThat(ScriptedEnvPolicy.validName("base.url")).isFalse();
        assertThat(ScriptedEnvPolicy.validName("A=B")).isFalse();
    }

    @Test
    @DisplayName("Kayıtta ayrılmış adlar (büyük/küçük harf duyarsız); GOOGLE_API_KEY gibi alt çizgili GO adları serbest")
    void reservedAtSave() {
        for (String n : List.of("K6_OUT", "k6_vus", "GOMAXPROCS", "GOMEMLIMIT", "GODEBUG", "GOGC", "gomaxprocs",
                "HTTPS_PROXY", "no_proxy", "MY_PROXY_URL", "SSL_CERT_FILE", "PATH", "HOME", "TMPDIR",
                "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "JAVA_TOOL_OPTIONS", "NODE_OPTIONS")) {
            assertThat(ScriptedEnvPolicy.reservedName(n)).as(n).isTrue();
        }
        for (String n : List.of("BASE_URL", "GOOGLE_API_KEY", "TOKEN", "API_PATH", "HOME_URL", "USER")) {
            assertThat(ScriptedEnvPolicy.reservedName(n)).as(n).isFalse();
        }
    }

    @Test
    @DisplayName("Koşumda düşenler yalnız süreci GERÇEKTEN etkileyenler — NODE_ENV/MY_PROXY_URL/path gibi eski adlar kalır")
    void droppedAtRuntimeIsNarrow() {
        for (String n : List.of("K6_OUT", "GOMAXPROCS", "GOMEMLIMIT", "GODEBUG", "HTTPS_PROXY", "https_proxy", "NO_PROXY",
                "ALL_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "PATH", "HOME", "TMPDIR", "LD_PRELOAD", "DYLD_X", "A=B", "")) {
            assertThat(ScriptedEnvPolicy.droppedAtRuntime(n)).as(n).isTrue();
        }
        for (String n : List.of("NODE_ENV", "JAVA_OPTS", "MY_PROXY_URL", "SSL_VERIFY", "path", "GOOGLE", "base.url", "BASE_URL")) {
            assertThat(ScriptedEnvPolicy.droppedAtRuntime(n)).as(n).isFalse();
        }
    }

    @Test
    @DisplayName("validateForSave: alan = env, ileti değişken adını taşır; geçerli liste sessiz geçer")
    void validateForSave() {
        assertThatCode(() -> ScriptedEnvPolicy.validateForSave(List.of(var("BASE_URL", "x"), var("", "boş ad atlanır")), Map.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScriptedEnvPolicy.validateForSave(List.of(var("K6_OUT", "x")), Map.of()))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("K6_OUT")
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("env"));
        // liste değilse dokunulmaz (kısmi PUT env'i hiç taşımaz)
        assertThatCode(() -> ScriptedEnvPolicy.validateForSave(null, Map.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateForSave: kayıtlı eski ad + değişmemiş uzun değer + eski sayı muaf; koşumu etkileyen kayıtlı ad muaf DEĞİL")
    void validateForSave_legacy() {
        String longVal = "x".repeat(ScriptedEnvPolicy.MAX_VALUE_CHARS + 10);
        Map<String, String> legacy = Map.of("base.url", "https://x", "BLOB", longVal, "K6_OUT", "json=x");
        assertThatCode(() -> ScriptedEnvPolicy.validateForSave(
                List.of(var("base.url", "https://y"), var("BLOB", longVal)), legacy)).doesNotThrowAnyException();
        // aynı kayıtlı ada YENİ uzun değer → red
        assertThatThrownBy(() -> ScriptedEnvPolicy.validateForSave(List.of(var("BLOB", longVal + "y")), legacy))
                .isInstanceOf(FieldValidationException.class);
        // koşumda düşen kayıtlı ad → her zaman red
        assertThatThrownBy(() -> ScriptedEnvPolicy.validateForSave(List.of(var("K6_OUT", "json=x")), legacy))
                .isInstanceOf(FieldValidationException.class);

        // Sayı: 51 yeni → red; ama monitörde zaten 60 kayıtlı ise 60'a kadar muaf.
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) many.add(var("V" + i, "x"));
        assertThatThrownBy(() -> ScriptedEnvPolicy.validateForSave(many, Map.of())).isInstanceOf(FieldValidationException.class);
        Map<String, String> sixty = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 60; i++) sixty.put("V" + i, "x");
        assertThatCode(() -> ScriptedEnvPolicy.validateForSave(many, sixty)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateForTest: yalnız koşumun yok sayacağı adlar reddedilir (test = kaydedilmiş koşumla aynı sonuç)")
    void validateForTest() {
        assertThatCode(() -> ScriptedEnvPolicy.validateForTest(List.of(var("base.url", "x"), var("NODE_ENV", "x"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScriptedEnvPolicy.validateForTest(List.of(var("GOMEMLIMIT", "off"))))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("forRuntime: ayrılmış adlar düşer, sıra korunur; temiz liste AYNI nesne döner")
    void forRuntime() {
        var clean = List.of(new ScriptedCheckerService.EnvVar("A", "1", false), new ScriptedCheckerService.EnvVar("B", "2", true));
        assertThat(ScriptedEnvPolicy.forRuntime(clean, "test")).isSameAs(clean);
        var dirty = List.of(new ScriptedCheckerService.EnvVar("A", "1", false),
                new ScriptedCheckerService.EnvVar("K6_OUT", "x", false),
                new ScriptedCheckerService.EnvVar("B", "2", false));
        assertThat(ScriptedEnvPolicy.forRuntime(dirty, "test")).extracting(ScriptedCheckerService.EnvVar::name)
                .containsExactly("A", "B");
        assertThat(ScriptedEnvPolicy.forRuntime(null, "test")).isNull();
    }
}
