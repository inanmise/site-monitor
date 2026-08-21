package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScriptedSafetyRules} — kaydetme anı güvenlik denetimi (L1).
 *
 * <p>Bu testin İKİ yarısı eşit önemde. Engellenen kalıpları doğrulamak yarısı; <b>meşru
 * script'lerin engellenmediğini</b> kanıtlamak diğer yarısı. İkincisi olmadan kural seti
 * kullanıcıyı kendi kütüphanesinden kilitler ve bu, kaçırılan bir uyarıdan pahalıdır
 * (bkz. {@link ScriptedTemplateRules} sınıf javadoc'u — aynı felsefe).
 */
class ScriptedSafetyRulesTest {

    private static String wrap(String body) {
        return "import http from 'k6/http';\nimport { sleep } from 'k6';\n"
                + "export default function () {\n" + body + "\n}\n";
    }

    @Nested
    @DisplayName("ENGELLER — üretim sistemine zarar veren kesin kalıplar")
    class Blocks {

        @Test
        @DisplayName("sonsuz döngü içinde istek: hedefe kesintisiz sel")
        void infiniteLoopWithRequest() {
            var d = ScriptedSafetyRules.check(wrap("while (true) { http.get('https://x'); }"));
            assertThat(d.blocked()).isTrue();
            assertThat(d.blocking()).contains("sonsuz döngü").contains("istek");
        }

        @Test
        @DisplayName("sonsuz döngü isteksiz de engellenir: CPU yanması (default fonksiyon DÖNMEZ)")
        void infiniteLoopWithoutRequest() {
            var d = ScriptedSafetyRules.check(wrap("let x = 0; for (;;) { x++; }"));
            assertThat(d.blocked()).isTrue();
            assertThat(d.blocking()).contains("CPU");
        }

        @Test
        @DisplayName("`while (1)` de sonsuzdur — `true` dışındaki biçim kaçış yolu olmamalı")
        void whileOneIsAlsoInfinite() {
            assertThat(ScriptedSafetyRules.check(wrap("while(1){ http.get('https://x'); }")).blocked()).isTrue();
        }

        @Test
        @DisplayName("sabit sınırlı AŞIRI döngü + istek: üretim sistemine yük testi")
        void hugeBoundedLoopWithRequest() {
            var d = ScriptedSafetyRules.check(wrap(
                    "for (let i = 0; i < 5000; i++) { http.get('https://x'); sleep(1); }"));
            assertThat(d.blocked()).isTrue();
            assertThat(d.blocking()).contains("5000");
        }

        @Test
        @DisplayName("statik istek sayısı tavanı")
        void tooManyStaticRequests() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < ScriptedSafetyRules.MAX_STATIC_REQUESTS + 1; i++) {
                b.append("http.get('https://x/").append(i).append("');\n");
            }
            var d = ScriptedSafetyRules.check(wrap(b.toString()));
            assertThat(d.blocked()).isTrue();
            assertThat(d.blocking()).contains("tavan");
        }

        @Test
        @DisplayName("devasa bellek ayırma — tek pod'da OOM kesinti demek")
        void hugeAllocation() {
            var d = ScriptedSafetyRules.check(wrap("const big = new Array(100000000);"));
            assertThat(d.blocked()).isTrue();
            assertThat(d.blocking()).contains("100000000").contains("ayırma");
        }

        @Test
        @DisplayName("üstel gösterim de okunur: `1e8` kaçış yolu değil")
        void hugeAllocationExponential() {
            assertThat(ScriptedSafetyRules.check(wrap("const s = 'x'.repeat(1e8);")).blocked()).isTrue();
        }

        @Test
        @DisplayName("BÜYÜK döngüde sleep YOK: istekler aralıksız, tek seferde gider")
        void burstLoopWithoutSleep() {
            var d = ScriptedSafetyRules.check(wrap(
                    "for (let i = 0; i < 40; i++) { http.get('https://x'); }"));
            assertThat(d.blocked()).isTrue();
            assertThat(d.blocking()).contains("sleep");
        }
    }

    @Nested
    @DisplayName("ENGELLEMEZ — meşru senaryolar kilitlenmemeli (yanlış-pozitif koruması)")
    class NoFalsePositives {

        @Test
        @DisplayName("küçük döngüde sleep olmaması ENGELLEMEZ: 3 URL gezmek olağan bir senaryodur")
        void smallLoopWithoutSleepIsFine() {
            var d = ScriptedSafetyRules.check(wrap(
                    "for (let i = 0; i < 3; i++) { http.get('https://x/' + i); }"));
            assertThat(d.blocked()).isFalse();
        }

        @Test
        @DisplayName("orta boy döngü + sleep: gerçek kullanıcı davranışı taklidi")
        void moderateLoopWithSleepIsFine() {
            var d = ScriptedSafetyRules.check(wrap(
                    "for (let i = 0; i < 20; i++) { http.get('https://x'); sleep(1); }"));
            assertThat(d.blocked()).isFalse();
        }

        @Test
        @DisplayName("YORUM içindeki tehlikeli örnek kaydı engellemez")
        void dangerousPatternInsideCommentIsIgnored() {
            var d = ScriptedSafetyRules.check(
                    "// UYARI: while (true) { http.get(x) } YAZMAYIN\n"
                    + "import http from 'k6/http';\n"
                    + "export default function () { http.get('https://x'); }\n");
            assertThat(d.blocked()).isFalse();
        }

        @Test
        @DisplayName("değişken sınırlı döngü ENGELLENMEZ — statik olarak kanıtlanamaz, UYARIR")
        void variableBoundLoopOnlyWarns() {
            var d = ScriptedSafetyRules.check(wrap(
                    "const n = urls.length;\nfor (let i = 0; i < n; i++) { http.get(urls[i]); }"));
            assertThat(d.blocked()).isFalse();
            assertThat(d.warnings()).anyMatch(w -> w.contains("değişken"));
        }

        @Test
        @DisplayName("makul ayırma engellenmez")
        void smallAllocationIsFine() {
            assertThat(ScriptedSafetyRules.check(wrap("const a = new Array(100);")).blocked()).isFalse();
        }

        @Test
        @DisplayName("tipik tek istekli senaryo tertemiz geçer — uyarı bile üretmez")
        void typicalScenarioIsClean() {
            var d = ScriptedSafetyRules.check(wrap(
                    "const r = http.get('https://x', { timeout: '20s' });\n"
                    + "check(r, { 'durum 200': (x) => x.status === 200 });"));
            assertThat(d.blocked()).isFalse();
            assertThat(d.warnings()).isEmpty();
        }

        @Test
        @DisplayName("boş/null script çökmez")
        void nullAndBlankAreSafe() {
            assertThat(ScriptedSafetyRules.check(null).blocked()).isFalse();
            assertThat(ScriptedSafetyRules.check("   ").blocked()).isFalse();
        }
    }

    @Nested
    @DisplayName("UYARIR — engellemez ama kullanıcıyı kaydetmeden önce bilgilendirir")
    class Warns {

        @Test
        @DisplayName("büyük http.batch: aynı anda giden eşzamanlı yük")
        void largeBatchWarns() {
            StringBuilder b = new StringBuilder("http.batch([");
            for (int i = 0; i < 25; i++) b.append("['GET','https://x/").append(i).append("'],");
            b.append("['GET','https://x/last']]);");
            var d = ScriptedSafetyRules.check(wrap(b.toString()));
            assertThat(d.blocked()).isFalse();
            assertThat(d.warnings()).anyMatch(w -> w.contains("batch"));
        }

        @Test
        @DisplayName("tavanın yarısını geçen istek sayısı: büyümeye devam ederse engellenecek")
        void approachingRequestCapWarns() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < ScriptedSafetyRules.MAX_STATIC_REQUESTS / 2 + 2; i++) {
                b.append("http.get('https://x/").append(i).append("');\n");
            }
            var d = ScriptedSafetyRules.check(wrap(b.toString()));
            assertThat(d.blocked()).isFalse();
            assertThat(d.warnings()).anyMatch(w -> w.contains("Tavan"));
        }
    }

    @Nested
    @DisplayName("Döngü gövdesi ayrıştırma — ENGELLEME kararı buna dayanıyor")
    class LoopBody {

        @Test
        @DisplayName("iç içe süslü parantez doğru kapanır (nesne literali gövdeyi erken bitirmez)")
        void nestedBracesBalance() {
            String src = "for (let i=0;i<9999;i++) { http.get('u', { headers: { a: 'b' } }); } after();";
            String body = ScriptedSafetyRules.loopBody(src, src.indexOf(')') + 1);
            assertThat(body).contains("http.get").doesNotContain("after()");
        }

        @Test
        @DisplayName("süssüz tek deyimli gövde ilk `;`'ye kadar okunur")
        void bracelessBody() {
            String src = "for (let i=0;i<9999;i++) http.get('u'); after();";
            String body = ScriptedSafetyRules.loopBody(src, src.lastIndexOf(')', src.indexOf("http")) + 1);
            assertThat(body).contains("http.get").doesNotContain("after()");
        }

        @Test
        @DisplayName("kapanmayan blok null döner → çağıran ENGELLEMEZ (tahmine dayalı blok yok)")
        void unbalancedReturnsNull() {
            assertThat(ScriptedSafetyRules.loopBody("{ http.get('u');", 0)).isNull();
        }
    }
}
