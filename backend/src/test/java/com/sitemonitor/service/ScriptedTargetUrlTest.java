package com.sitemonitor.service;

import com.sitemonitor.service.ScriptedCheckerService.EnvVar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hedef URL çıkarımı — "Bağlantı Teşhisi"nin hangi adrese sonda atacağını bilmesi için.
 *
 * <p>Sentetik monitörün diğer 9 türün aksine tek bir "host" alanı YOKTUR; hedef script gövdesinin
 * içindedir. Kullanıcıdan tam da hata ayıklamaya çalıştığı anda adres istemek yerine script'ten
 * çıkarılır; çıkarım bir KOLAYLIKTIR — uç elle URL vermeye de izin verir.
 */
class ScriptedTargetUrlTest {

    @Test
    @DisplayName("Düz URL literalleri sırayla ve tekilleştirilerek çıkar")
    void extractsPlainLiterals() {
        String script = """
            import http from 'k6/http';
            export default function () {
              http.get('https://www.example.com/login', { timeout: '20s' });
              http.post("https://api.example.com/v1/x", '{}');
              http.get('https://www.example.com/login');
            }
            """;

        assertThat(ScriptedCheckerService.extractTargetUrls(script, List.of()))
                .containsExactly("https://www.example.com/login", "https://api.example.com/v1/x");
    }

    @Test
    @DisplayName("`${__ENV.BASE_URL}` env DEĞERİYLE doldurulur — BASE_URL deseni çok yaygın")
    void substitutesEnvTemplates() {
        String script = "export default function () { http.get(`${__ENV.BASE_URL}/health`); }";
        var env = List.of(new EnvVar("BASE_URL", "https://gateway.internal", false));

        assertThat(ScriptedCheckerService.extractTargetUrls(script, env))
                .containsExactly("https://gateway.internal/health");
    }

    @Test
    @DisplayName("Secret env değeri de çözülür (çağıran maskeler) — teşhis hedefi doğru olmalı")
    void substitutesSecretEnv() {
        String script = "export default function () { http.get(`${__ENV.SECRET_BASE}/x`); }";
        var env = List.of(new EnvVar("SECRET_BASE", "https://gizli.example", true));

        assertThat(ScriptedCheckerService.extractTargetUrls(script, env))
                .containsExactly("https://gizli.example/x");
    }

    @Test
    @DisplayName("YORUM içindeki URL sayılmaz — teşhis ölü bir adrese sonda atmasın")
    void ignoresCommentedUrls() {
        String script = """
            // eski hedef: https://eski.example/x
            export default function () { http.get('https://yeni.example/x'); }
            """;

        assertThat(ScriptedCheckerService.extractTargetUrls(script, List.of()))
                .containsExactly("https://yeni.example/x");
    }

    @Test
    @DisplayName("URL'den sonraki tırnak/parantez adrese YAPIŞMAZ")
    void trimsDelimiters() {
        String script = "http.get('https://a.example/x?q=1'); http.get(\"https://b.example\");";

        assertThat(ScriptedCheckerService.extractTargetUrls(script, List.of()))
                .containsExactly("https://a.example/x?q=1", "https://b.example");
    }

    @Test
    @DisplayName("Çözülemeyen kurgu / URL yok → boş liste (kullanıcı elle girer, uç bunu kabul eder)")
    void emptyWhenUnresolvable() {
        // String birleştirme statik olarak çözülmez — bilinen ve kabul edilen sınır.
        assertThat(ScriptedCheckerService.extractTargetUrls(
                "const u = base + '/health'; http.get(u);", List.of())).isEmpty();
        assertThat(ScriptedCheckerService.extractTargetUrls("", List.of())).isEmpty();
        assertThat(ScriptedCheckerService.extractTargetUrls(null, List.of())).isEmpty();
        // Tanımsız env: şablon AYNEN kalır, URL üretilmez (uydurma adres sondalanmasın)
        assertThat(ScriptedCheckerService.extractTargetUrls(
                "http.get(`${__ENV.YOK}/x`);", List.of())).isEmpty();
    }

    @Test
    @DisplayName("Çıkarım tavanı 10 — bozuk/dev script yanıtı şişirmesin")
    void capsAtTen() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) sb.append("http.get('https://h").append(i).append(".example');\n");

        assertThat(ScriptedCheckerService.extractTargetUrls(sb.toString(), List.of())).hasSize(10);
    }
}
