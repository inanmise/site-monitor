package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV hücresi kaçışı — dışa aktarımların ortak kuralı.
 *
 * <p>Kural projede vardı ama tek bir dışa aktarımda: denetim kaydı CSV'si, kontrol geçmişi CSV'si
 * ve arayüzdeki dört dışa aktarım kendi kaçışını yazmıştı, hiçbirinde FORMÜL nötrlemesi yoktu
 * (2026-08-23 denetimi). Kural artık tek yerde ve burada kilitli.
 */
class CsvTest {

    @Test
    @DisplayName("Formül karakteriyle BAŞLAYAN hücre metne sabitlenir (Excel/LibreOffice çalıştırmasın)")
    void neutralisesFormulaPrefixes() {
        assertThat(Csv.cell("=cmd|'/c calc'!A1")).isEqualTo("'=cmd|'/c calc'!A1");
        assertThat(Csv.cell("+1+1")).isEqualTo("'+1+1");
        assertThat(Csv.cell("-2+3")).isEqualTo("'-2+3");
        assertThat(Csv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(Csv.cell("\tsekme")).isEqualTo("'\tsekme");
    }

    @Test
    @DisplayName("Formül karakteri ORTADA ise dokunulmaz — normal metin bozulmasın")
    void leavesInnerCharactersAlone() {
        assertThat(Csv.cell("a=b")).isEqualTo("a=b");
        assertThat(Csv.cell("1-2")).isEqualTo("1-2");
        assertThat(Csv.cell("mail@akbank.com")).isEqualTo("mail@akbank.com");
    }

    @Test
    @DisplayName("Ayraç, tırnak ve satır sonu içeren değer tırnaklanır; tırnaklar ikilenir")
    void quotesWhenNeeded() {
        assertThat(Csv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.cell("a;b")).isEqualTo("\"a;b\"");
        assertThat(Csv.cell("de\"mek")).isEqualTo("\"de\"\"mek\"");
        assertThat(Csv.cell("iki\nsatır")).isEqualTo("\"iki\nsatır\"");
    }

    @Test
    @DisplayName("Tek başına CR de tırnaklanır — satırlar CRLF ile birleşiyor, yoksa satır bölünür")
    void carriageReturnIsQuoted() {
        assertThat(Csv.cell("a\rb")).isEqualTo("\"a\rb\"");
    }

    @Test
    @DisplayName("CR ile BAŞLAYAN değer hem nötrlenir hem tırnaklanır")
    void leadingCarriageReturnIsBothNeutralisedAndQuoted() {
        assertThat(Csv.cell("\rgizli")).isEqualTo("\"'\rgizli\"");
    }

    @Test
    @DisplayName("Formül nötrlemesi tırnaklamayı BOZMAZ (ikisi birlikte uygulanır)")
    void neutralisationAndQuotingCompose() {
        assertThat(Csv.cell("=1,2")).isEqualTo("\"'=1,2\"");
    }

    @Test
    @DisplayName("null ve boş değer boş hücre olur")
    void nullAndEmpty() {
        assertThat(Csv.cell(null)).isEmpty();
        assertThat(Csv.cell("")).isEmpty();
    }

    @Test
    @DisplayName("Metin olmayan değerler de kabul edilir (sayı/boolean)")
    void nonStringValues() {
        assertThat(Csv.cell(443)).isEqualTo("443");
        assertThat(Csv.cell(Boolean.TRUE)).isEqualTo("true");
        assertThat(Csv.cell(-5)).isEqualTo("'-5");   // negatif sayı da formül gibi başlar
    }
}
