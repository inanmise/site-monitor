package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Şablon yer-tutucu doğrulaması — bilinmeyen yer tutucu kaydetmede REDDEDİLİR.
 *
 * <p>Sessiz bozulmanın kendisi: {@code {sevye}} gibi bir yazım hatası kaydedilseydi çalışma
 * anında mesajda aynen "{sevye}" basılır, kimse fark etmezdi. Doğrulama kaydetme anında.
 */
class UserPushControllerUnitTest {

    @Test
    @DisplayName("Bilinen yer tutucular geçer, bilinmeyenin ADI döner")
    void unknownPlaceholderDetection() {
        assertThat(UserPushController.unknownPlaceholder(
                "{seviye} > {ad}: {hedef} yanıt vermiyor — {neden}. {saat}")).isNull();
        assertThat(UserPushController.unknownPlaceholder("düz metin, yer tutucu yok")).isNull();
        assertThat(UserPushController.unknownPlaceholder("{seviye} {sevye}")).isEqualTo("sevye");
        assertThat(UserPushController.unknownPlaceholder("{unknown_thing}")).isEqualTo("unknown_thing");
    }
}
