package com.sitemonitor.service.monitorcsv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** İzleme CSV ayrıştırıcısı (2026-11-19): RFC 4180, ayırıcı seçimi, sınırlar ve doğrusal süre. */
class MonitorCsvParserTest {

    @Test
    @DisplayName("tırnaklı alan: virgül, noktalı virgül, \"\" ve çok satırlı hücre (CRLF → LF); BOM atılır")
    void quotedCells() throws Exception {
        String csv = "﻿name,script,team\r\n\"A, b\",\"line1\r\nline \"\"2\"\"; x\",T1\r\n";
        MonitorCsvParser.Parsed p = MonitorCsvParser.parse(csv);
        assertThat(p.header().cells()).containsExactly("name", "script", "team");
        assertThat(p.rows()).hasSize(1);
        assertThat(p.rows().get(0).cells()).containsExactly("A, b", "line1\nline \"2\"; x", "T1");
        assertThat(p.rows().get(0).line()).isEqualTo(2);
        assertThat(p.delimiter()).isEqualTo(',');
    }

    @Test
    @DisplayName("LF, tek CR ve son satırda satır sonu yok; tamamen boş satırlar sayılmaz; satır numarası çok satırlı hücreyi sayar")
    void lineEndingsAndBlankRows() throws Exception {
        String csv = "name,team\n\"a\nb\",T\r,\n\nc,T";
        MonitorCsvParser.Parsed p = MonitorCsvParser.parse(csv);
        assertThat(p.rows()).hasSize(2);
        assertThat(p.rows().get(0).cells()).containsExactly("a\nb", "T");
        assertThat(p.rows().get(0).line()).isEqualTo(2);
        assertThat(p.rows().get(1).cells()).containsExactly("c", "T");
        assertThat(p.rows().get(1).line()).isEqualTo(6);
    }

    @Test
    @DisplayName("Türkçe Excel: başlıkta noktalı virgül fazlaysa ayırıcı ';'")
    void semicolonDelimiter() throws Exception {
        MonitorCsvParser.Parsed p = MonitorCsvParser.parse("name;team;tags\nA;T;\"a,b\"\n");
        assertThat(p.delimiter()).isEqualTo(';');
        assertThat(p.rows().get(0).cells()).containsExactly("A", "T", "a,b");
    }

    @Test
    @DisplayName("boş dosya, kapanmamış tırnak ve kapanan tırnaktan sonra karakter → satırı söyleyen hata")
    void errors() {
        assertThatThrownBy(() -> MonitorCsvParser.parse(""))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> assertThat(e.reason()).isEqualTo("empty"));
        assertThatThrownBy(() -> MonitorCsvParser.parse("\n\n,,\n"))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> assertThat(e.reason()).isEqualTo("empty"));
        assertThatThrownBy(() -> MonitorCsvParser.parse("name,team\n\"abc,T\n"))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> {
                    assertThat(e.reason()).isEqualTo("unclosed_quote");
                    assertThat(e.line()).isEqualTo(2);
                });
        assertThatThrownBy(() -> MonitorCsvParser.parse("name,team\n\"abc\"x,T\n"))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> assertThat(e.reason()).isEqualTo("bad_quote"));
    }

    @Test
    @DisplayName("sınırlar: 2000 veri satırı geçer, 2001 hata; 200 sütun geçer, 201 hata; 64 KB hücre sınırı; 5 MB metin sınırı")
    void bounds() throws Exception {
        StringBuilder ok = new StringBuilder("name,team\n");
        for (int i = 0; i < MonitorCsvParser.MAX_ROWS; i++) ok.append("m").append(i).append(",T\n");
        assertThat(MonitorCsvParser.parse(ok.toString()).rows()).hasSize(MonitorCsvParser.MAX_ROWS);
        ok.append("extra,T\n");
        assertThatThrownBy(() -> MonitorCsvParser.parse(ok.toString()))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> assertThat(e.reason()).isEqualTo("too_many_rows"));

        String cols200 = String.join(",", java.util.Collections.nCopies(MonitorCsvParser.MAX_COLUMNS, "x"));
        assertThat(MonitorCsvParser.parse(cols200 + "\n").header().cells()).hasSize(MonitorCsvParser.MAX_COLUMNS);
        assertThatThrownBy(() -> MonitorCsvParser.parse(cols200 + ",y\n"))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> assertThat(e.reason()).isEqualTo("too_many_columns"));

        String bigCell = "a".repeat(MonitorCsvParser.MAX_CELL_CHARS);
        assertThat(MonitorCsvParser.parse("name\n" + bigCell + "\n").rows().get(0).cells().get(0)).hasSize(MonitorCsvParser.MAX_CELL_CHARS);
        assertThatThrownBy(() -> MonitorCsvParser.parse("name\n" + bigCell + "b\n"))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> assertThat(e.reason()).isEqualTo("cell_too_long"));

        assertThatThrownBy(() -> MonitorCsvParser.parse("x".repeat(MonitorCsvParser.MAX_CHARS + 1)))
                .isInstanceOfSatisfying(MonitorCsvParser.CsvFormatException.class, e -> assertThat(e.reason()).isEqualTo("too_large"));
    }

    @Test
    @DisplayName("doğrusal: 5 MB'a yakın düşmanca girdi (tırnak / ayırıcı yağmuru) saniyeler değil milisaniyeler sürer")
    void linearOnHostileInput() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            String quotes = "\"" + "\"\"".repeat(30_000) + "\"\n";   // tek hücre, 30 000 kaçışlı tırnak
            assertThat(MonitorCsvParser.parse("name\n" + quotes).rows()).hasSize(1);
            String commas = "a" + ",".repeat(150) + "\n";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1500; i++) sb.append(commas);
            assertThat(MonitorCsvParser.parse(sb.toString()).rows()).hasSize(1499);
            String unclosed = "name\n\"" + "a".repeat(60_000);
            assertThatThrownBy(() -> MonitorCsvParser.parse(unclosed)).isInstanceOf(MonitorCsvParser.CsvFormatException.class);
        });
    }

    @Test
    @DisplayName("tırnaksız alan içindeki tırnak harf olarak alınır (hoşgörü)")
    void quoteInsideUnquoted() throws Exception {
        assertThat(MonitorCsvParser.parse("name,team\nab\"c,T\n").rows().get(0).cells()).isEqualTo(List.of("ab\"c", "T"));
    }
}
