package com.sitemonitor.service.techerror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Kanal notu görsel başvuruları — Java ve JS'in ORTAK doğruluk tablosu. İstemci testi bu dosyayı kaynak olarak okur
 * ({@code frontend/src/test/helpers/javaTable.js}); satır = { girdi, beklenen kimlikler (virgüllü, ilk görülme sırası) }.
 * Değerler JS sayısında tam temsil edilebilecek biçimde seçildi (18 rakamlı satır 10^17).
 */
class TechErrorNoteRefsTest {

    public static final String[][] REF_TABLE = {
            // düz başvuru
            { "![ekran](te-note-img:42)", "42" },
            // iki başvuru — ilk görülme sırası
            { "![a](te-note-img:7) metin ![b](te-note-img:12)", "7,12" },
            { "te-note-img:9 te-note-img:3 te-note-img:9 te-note-img:1", "9,3,1" },
            // yinelenen başvuru tek sayılır; baştaki sıfırlar aynı kimliktir
            { "te-note-img:5 ve yine te-note-img:5", "5" },
            { "te-note-img:007 te-note-img:7", "7" },
            // 18 rakam başvurudur; 19 ve üstü DEĞİLDİR (ilk 18'i de alınmaz)
            { "te-note-img:100000000000000000", "100000000000000000" },
            { "te-note-img:1234567890123456789", "" },
            { "te-note-img:12345678901234567890", "" },
            { "te-note-img:1234567890123456789 te-note-img:8", "8" },
            // rakam dizisinin TAMAMI kimliktir; ardından gelen harf diziyi bitirir
            { "te-note-img:567", "567" },
            { "te-note-img:123abc", "123" },
            // rakam yok → başvuru değil
            { "te-note-img:", "" },
            { "te-note-img: 5", "" },
            { "te-note-img:x9", "" },
            { "te-note-img:-5", "" },
            { "te-note-img:\n5", "" },
            // ASCII olmayan rakam sayılmaz
            { "te-note-img:٥", "" },
            // önek büyük / küçük harf duyarlıdır
            { "TE-NOTE-IMG:5", "" },
            { "Te-Note-Img:5", "" },
            // başka metnin içinde de sayılır (ekranda çizilenin üst kümesi)
            { "bkz.xte-note-img:77yy", "77" },
            { "[bağlantı](te-note-img:31)", "31" },
            { "`te-note-img:4`", "4" },
            { "(te-note-img:10),(te-note-img:11).", "10,11" },
            { "te-note-img:te-note-img:6", "6" },
            // başvuru yok / boş
            { "Veri her ayın ilk iş günü raporlama aracından alınır.", "" },
            { "", "" },
    };

    private static String csv(LinkedHashSet<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("doğruluk tablosu: her girdi beklenen kimlikleri ilk görülme sırasıyla verir")
    void refTable() {
        for (String[] row : REF_TABLE) {
            assertThat(csv(TechErrorNoteRefs.ids(row[0]))).as("girdi: [%s]", row[0]).isEqualTo(row[1]);
        }
    }

    @Test
    @DisplayName("null metin boş kümedir; references kimliği yalnız tam eşleşmede bulur")
    void nullAndReferences() {
        assertThat(TechErrorNoteRefs.ids(null)).isEmpty();
        assertThat(TechErrorNoteRefs.references("![x](te-note-img:15)", 15)).isTrue();
        assertThat(TechErrorNoteRefs.references("![x](te-note-img:15)", 1)).isFalse();     // 15'in ön eki başvuru değildir
        assertThat(TechErrorNoteRefs.references("![x](te-note-img:150)", 15)).isFalse();
        assertThat(TechErrorNoteRefs.references(null, 15)).isFalse();
        assertThat(TechErrorNoteRefs.references("", 15)).isFalse();
    }

    @Test
    @DisplayName("en uzun 18 rakam long'a sığar; önek sabiti sözleşmedeki metindir")
    void constants() {
        assertThat(TechErrorNoteRefs.PREFIX).isEqualTo("te-note-img:");
        assertThat(TechErrorNoteRefs.MAX_DIGITS).isEqualTo(18);
        assertThat(TechErrorNoteRefs.ids("te-note-img:999999999999999999")).containsExactly(999_999_999_999_999_999L);
    }

    @Test
    @DisplayName("tarama doğrusaldır: sınır uzunluğunda kötü niyetli gövdeler (önek / rakam yığını) anında biter")
    void scanIsLinear() {
        String prefixes = "te-note-img:".repeat(TechErrorChannelNoteService.MAX_CHARS / 12);
        String digits = "te-note-img:" + "9".repeat(TechErrorChannelNoteService.MAX_CHARS - 12);
        String partial = "te-note-img".repeat(TechErrorChannelNoteService.MAX_CHARS / 11);
        StringBuilder many = new StringBuilder();
        for (int i = 1; many.length() < TechErrorChannelNoteService.MAX_CHARS - 24; i++) many.append("te-note-img:").append(i).append(' ');
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(TechErrorNoteRefs.ids(prefixes)).isEmpty();
            assertThat(TechErrorNoteRefs.ids(digits)).isEmpty();
            assertThat(TechErrorNoteRefs.ids(partial)).isEmpty();
            assertThat(TechErrorNoteRefs.ids(many.toString())).hasSizeGreaterThan(3000).contains(1L, 2L, 3000L);
        });
    }
}
