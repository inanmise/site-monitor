package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gerekçe notu kuralı.
 *
 * <p>Kuralın SUNUCUDA olması şart: yalnız arayüzde dursaydı API'ye doğrudan boş notla istek atmak
 * mümkün olur ve "her manuel onayın bir gerekçesi vardır" garantisi çökerdi. Bu testler o
 * garantiyi kilitliyor.
 */
class AlertActionNoteTest {

    @Test
    @DisplayName("Boş / null / yalnız boşluk reddedilir")
    void emptyIsRejected() {
        assertThat(AlertActionNote.isValid(null)).isFalse();
        assertThat(AlertActionNote.isValid("")).isFalse();
        assertThat(AlertActionNote.isValid("     ")).isFalse();
        assertThat(AlertActionNote.isValid("\n\t ")).isFalse();
    }

    @Test
    @DisplayName("Üç kelimeden az reddedilir")
    void tooFewWordsRejected() {
        assertThat(AlertActionNote.isValid("planlı bakım")).isFalse();
        assertThat(AlertActionNote.isValid("yanlışalarmdı")).isFalse();
    }

    @Test
    @DisplayName("Zorunluluğu geçiştiren kısa girdiler reddedilir")
    void tokenGamingRejected() {
        // Üç "kelime" ama hiçbiri gerçek değil: kelime uzunluğu eşiği eler.
        assertThat(AlertActionNote.isValid("a b c")).isFalse();
        assertThat(AlertActionNote.isValid("x y z q")).isFalse();
        // Üç kelime, her biri 2 karakter → toplam 8 karakter, karakter eşiğinin altında.
        assertThat(AlertActionNote.isValid("ok ok ok")).isFalse();
    }

    @Test
    @DisplayName("Gerçek bir gerekçe kabul edilir")
    void realReasonAccepted() {
        assertThat(AlertActionNote.isValid("planlı bakım kapsamında kapatıldı")).isTrue();
        assertThat(AlertActionNote.isValid("bilinen sorun takip ediliyor")).isTrue();
        assertThat(AlertActionNote.isValid("  düzeltme devrede doğrulandı  ")).isTrue();
    }

    @Test
    @DisplayName("require: geçerli notu KIRPILMIŞ döner, geçersizde fırlatır")
    void requireTrimsOrThrows() {
        assertThat(AlertActionNote.require("  planlı bakım kapsamında  "))
                .isEqualTo("planlı bakım kapsamında");
        assertThatThrownBy(() -> AlertActionNote.require("a b c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("en az");
    }

    @Test
    @DisplayName("BİLİNEN SINIR: anlamsız ama kurallı metin geçer — bu bilinçli")
    void knownLimitation() {
        // Bir metin kuralıyla anlamlılık zorlanamaz. Kayıt altına alınıyor ki ileride
        // "kural bozuk" diye değil, "bilinen sınır" diye ele alınsın. Gerçek caydırıcılık
        // notun denetim günlüğüne ve raporlara ADLA düşmesinden gelir.
        assertThat(AlertActionNote.isValid("aaa bbb ccc")).isTrue();
    }
}
