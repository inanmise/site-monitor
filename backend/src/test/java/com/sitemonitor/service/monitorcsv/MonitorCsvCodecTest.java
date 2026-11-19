package com.sitemonitor.service.monitorcsv;

import com.sitemonitor.service.monitorcsv.MonitorCsvSpec.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** İzleme CSV hücre kuralları (2026-11-19): formül koruması geri dönüşü, ayrıştırma ve kanonik karşılaştırma. */
class MonitorCsvCodecTest {

    @Test
    @DisplayName("formül koruması: = + - @ sekme ile başlayan değer '-li yazılır, içe aktarımda AYNEN geri gelir; ' ile başlayan değer de")
    void formulaGuardRoundTrip() throws Exception {
        for (String v : List.of("=cmd|'/c calc'!A1", "+90 555", "-error", "@SUM(1)", "\tx", "'abc", "''=x", "'=x", "plain, text",
                "çok \"satırlı\"\nmetin", "a;b")) {
            String cell = MonitorCsvCodec.cell(v);
            if (!v.isEmpty() && "=+-@\t".indexOf(v.charAt(0)) >= 0) {
                assertThat(cell.startsWith("'") || cell.startsWith("\"'")).as("korumalı: " + v).isTrue();
            }
            MonitorCsvParser.Parsed p = MonitorCsvParser.parse("name\n" + cell + "\n");
            assertThat(MonitorCsvCodec.unguard(p.rows().get(0).cells().get(0))).as("geri dönüş: " + v).isEqualTo(v);
        }
        assertThat(MonitorCsvCodec.unguard("'abc")).isEqualTo("'abc");   // elle yazılmış ' — formül değilse dokunulmaz
        assertThat(MonitorCsvCodec.unguard("'=x")).isEqualTo("=x");      // Excel metin işareti
    }

    @Test
    @DisplayName("ayrıştırma: tam sayı, mantıksal (evet / hayır), JSON, boşlukla ayrılmış 7/24 grupları; hatalar kodla")
    void parse() throws Exception {
        assertThat(MonitorCsvCodec.parse(Kind.INT, " 60 ")).isEqualTo(60);
        assertThat(MonitorCsvCodec.parse(Kind.BOOL, "EVET")).isEqualTo(true);
        assertThat(MonitorCsvCodec.parse(Kind.BOOL, "Hayır")).isEqualTo(false);
        assertThat(MonitorCsvCodec.parse(Kind.BOOL, "0")).isEqualTo(false);
        assertThat(MonitorCsvCodec.parse(Kind.TEXT, "  a b ")).isEqualTo("a b");
        assertThat(MonitorCsvCodec.parse(Kind.LONG_TEXT, "a\r\nb ")).isEqualTo("a\nb ");
        assertThat(MonitorCsvCodec.parse(Kind.NOC_GROUPS, "NOC A; ;NOC B")).isEqualTo(List.of("NOC A", "NOC B"));
        assertThat(MonitorCsvCodec.parse(Kind.JSON, "[{\"days\":[1],\"from\":\"08:00\",\"to\":\"18:00\"}]")).isInstanceOf(List.class);
        assertThatThrownBy(() -> MonitorCsvCodec.parse(Kind.INT, "6O"))
                .isInstanceOfSatisfying(MonitorCsvCodec.CellException.class, e -> assertThat(e.code()).isEqualTo("not_int"));
        assertThatThrownBy(() -> MonitorCsvCodec.parse(Kind.INT, "99999999999999"))
                .isInstanceOfSatisfying(MonitorCsvCodec.CellException.class, e -> assertThat(e.code()).isEqualTo("not_int"));
        assertThatThrownBy(() -> MonitorCsvCodec.parse(Kind.BOOL, "belki"))
                .isInstanceOfSatisfying(MonitorCsvCodec.CellException.class, e -> assertThat(e.code()).isEqualTo("not_bool"));
        assertThatThrownBy(() -> MonitorCsvCodec.parse(Kind.JSON, "[{"))
                .isInstanceOfSatisfying(MonitorCsvCodec.CellException.class, e -> assertThat(e.code()).isEqualTo("not_json"));
    }

    @Test
    @DisplayName("k6 ortam değişkeni: gizli değişkenin DEĞERİ dosyadan alınmaz (sır CSV'de dolaşmaz), gizli olmayan korunur")
    @SuppressWarnings("unchecked")
    void envSecretValuesDropped() throws Exception {
        List<Map<String, Object>> env = (List<Map<String, Object>>) MonitorCsvCodec.parse(Kind.ENV,
                "[{\"name\":\"BASE\",\"value\":\"https://x\",\"secret\":false},{\"name\":\"TOKEN\",\"value\":\"s3cr3t\",\"secret\":true}]");
        assertThat(env).hasSize(2);
        assertThat(env.get(0)).containsEntry("value", "https://x");
        assertThat(env.get(1)).containsEntry("value", "").containsEntry("secret", true);
        assertThatThrownBy(() -> MonitorCsvCodec.parse(Kind.ENV, "{\"a\":1}"))
                .isInstanceOfSatisfying(MonitorCsvCodec.CellException.class, e -> assertThat(e.code()).isEqualTo("not_list"));
    }

    @Test
    @DisplayName("kanonik karşılaştırma: null = varsayılan (nullAs), büyük / küçük harf duyarsız küme, JSON anahtar sırası ve boş liste")
    void canon() {
        assertThat(MonitorCsvCodec.canon(Kind.TEXT, null, "WARNING", true))
                .isEqualTo(MonitorCsvCodec.canon(Kind.TEXT, "warning", "WARNING", true));
        assertThat(MonitorCsvCodec.canon(Kind.BOOL, null, "true", false)).isEqualTo(MonitorCsvCodec.canon(Kind.BOOL, true, "true", false));
        assertThat(MonitorCsvCodec.canon(Kind.INT, 60, null, false)).isEqualTo(MonitorCsvCodec.canon(Kind.INT, 60L, null, false));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("from", "08:00");
        a.put("to", "18:00");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("to", "18:00");
        b.put("from", "08:00");
        assertThat(MonitorCsvCodec.canon(Kind.JSON, List.of(a), null, false)).isEqualTo(MonitorCsvCodec.canon(Kind.JSON, List.of(b), null, false));
        assertThat(MonitorCsvCodec.canon(Kind.JSON, List.of(), null, false)).isEqualTo(MonitorCsvCodec.canon(Kind.JSON, null, null, false));
        assertThat(MonitorCsvCodec.canon(Kind.LONG_TEXT, "a\r\nb", null, false)).isEqualTo("a\nb");
        assertThat(MonitorCsvCodec.canon(Kind.NOC_GROUPS, List.of(3L, 1L), null, false))
                .isEqualTo(MonitorCsvCodec.canon(Kind.NOC_GROUPS, List.of(1L, 3L), null, false));
        assertThat(MonitorCsvCodec.canon(Kind.TEXT, "GET", null, false)).isNotEqualTo(MonitorCsvCodec.canon(Kind.TEXT, "get", null, false));
    }

    @Test
    @DisplayName("dışa aktarım metni: boş liste / nesne boş hücre, mantıksal true / false, tam sayı")
    void exportText() {
        assertThat(MonitorCsvCodec.text(Kind.JSON, List.of())).isEmpty();
        assertThat(MonitorCsvCodec.text(Kind.JSON, Map.of())).isEmpty();
        assertThat(MonitorCsvCodec.text(Kind.BOOL, false)).isEqualTo("false");
        assertThat(MonitorCsvCodec.text(Kind.INT, 300)).isEqualTo("300");
        assertThat(MonitorCsvCodec.row(List.of("a", "b,c", ""))).isEqualTo("a,\"b,c\",\r\n");
    }
}
