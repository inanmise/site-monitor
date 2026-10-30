package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 eklentisi kuralı (2026-10-29, k6 0.49 → 2.3 geçişi). k6 1.2+ {@code k6/x/…} içe aktarımı ya da {@code "use k6 …"}
 * yönergesi görünce Grafana'dan eklentili ikili derletip indiriyor ve çalıştırıyor; sistem bunu ortam değişkeniyle
 * kapatıyor, kayıt da reddediyor (çift kemer). Yorumlar ve sıradan metinler sayılmaz.
 */
class ScriptedExtensionRuleTest {

    private static String blocking(String script) {
        return ScriptedSafetyRules.check(script).blocking();
    }

    @Test
    @DisplayName("k6/x içe aktarımı her biçimde engellenir (from, yan etkili import, dinamik import, require)")
    void extensionImportsBlocked() {
        assertThat(blocking("import sql from 'k6/x/sql';\nexport default function () {}")).contains("k6/x/sql");
        assertThat(blocking("import \"k6/x/faker\";\nexport default function () {}")).contains("k6/x/faker");
        assertThat(blocking("export default async function () { const m = await import('k6/x/kafka'); }")).contains("k6/x/kafka");
        assertThat(blocking("const r = require( `k6/x/redis` );\nexport default function () {}")).contains("k6/x/redis");
        assertThat(blocking("import { a } from\n  'k6/x/a/b';\nexport default function () {}")).contains("k6/x/a/b");
    }

    @Test
    @DisplayName("\"use k6 …\" yönergesi engellenir (sürüm ya da eklenti bildirimi)")
    void useK6DirectiveBlocked() {
        assertThat(blocking("\"use k6 with k6/x/faker > 0.4.0\";\nexport default function () {}")).contains("use k6");
        assertThat(blocking("'use k6 >= 1.0';\nexport default function () {}")).contains("use k6");
        assertThat(blocking("  \"use k6\";\nexport default function () {}")).contains("use k6");
    }

    @Test
    @DisplayName("Yanlış-pozitif yok: yorumdaki örnek, sıradan metin, 'use strict', k6 yerleşik modülleri")
    void noFalsePositives() {
        assertThat(blocking("// import sql from 'k6/x/sql' — kullanmayın\nexport default function () {}")).isNull();
        assertThat(blocking("/* \"use k6 with k6/x/faker\"; */\nexport default function () {}")).isNull();
        assertThat(blocking("const note = 'k6/x/sql modülü burada çalışmaz';\nexport default function () {}")).isNull();
        assertThat(blocking("'use strict';\nimport http from 'k6/http';\nexport default function () {}")).isNull();
        assertThat(blocking("import { check } from 'k6';\nimport exec from 'k6/execution';\nexport default function () {}"))
                .isNull();
        assertThat(blocking("const s = \"a use k6 b\";\nexport default function () {}")).isNull();
    }

    @Test
    @DisplayName("Uzun boşluk dizisi tarama süresini patlatmaz (ReDoS)")
    void noBacktrackingOnWhitespace() {
        String script = "import" + " ".repeat(200_000) + "x;\nexport default function () {}";
        long t0 = System.nanoTime();
        assertThat(ScriptedSafetyRules.extensionUse(script)).isNull();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_000);
    }

    @Test
    @DisplayName("Şablon kuralları da aynı engeli uygular (şablon N izlemeye kopyalanır)")
    void templatesBlocked() {
        var d = ScriptedTemplateRules.check(null, "Ad", "a", "b",
                "import sql from 'k6/x/sql';\nexport default function () {}", null);
        assertThat(d.blocked()).isTrue();
        assertThat(d.blocking()).contains("k6/x/sql");
    }
}
