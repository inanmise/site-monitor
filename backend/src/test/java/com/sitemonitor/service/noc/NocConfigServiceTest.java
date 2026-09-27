package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocSettings;
import com.sitemonitor.repository.NocSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NocConfigServiceTest {

    private final NocSettingsRepository repo = mock(NocSettingsRepository.class);
    private final NocConfigService svc = new NocConfigService(repo);

    @Test
    @DisplayName("satır yokken varsayılanlar: tüm türler AÇIK, CRITICAL, çözüldü e-postası açık")
    void defaults() {
        when(repo.findById(NocSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        NocConfigService.Config c = svc.get();
        for (NocType t : NocType.values()) assertThat(c.typeEnabled(t)).as(t.name()).isTrue();
        assertThat(c.minLevel()).isEqualTo("CRITICAL");
        assertThat(c.sendResolve()).isTrue();
        Map<String, Object> dto = NocConfigService.toDto(c);
        assertThat(((Map<?, ?>) dto.get("enabled_types"))).hasSize(10);
        assertThat(dto).containsEntry("min_level", "CRITICAL").containsEntry("send_resolve", true)
                .containsEntry("call_instructions", "");
    }

    @Test
    @DisplayName("seviye eşiği: WARNING < HIGH < CRITICAL")
    void levelOrder() {
        NocConfigService.Config high = new NocConfigService.Config(java.util.Set.of(), "HIGH", true, "", null, null);
        assertThat(high.meetsMinLevel("CRITICAL")).isTrue();
        assertThat(high.meetsMinLevel("HIGH")).isTrue();
        assertThat(high.meetsMinLevel("WARNING")).isFalse();
        assertThat(high.meetsMinLevel(null)).isFalse();
    }

    @Test
    @DisplayName("kaydet: kısmi gövde — gelmeyen alan korunur; tür kapatma/açma; talimat kırpılır")
    void savePartial() {
        NocSettings existing = new NocSettings();
        existing.setDisabledTypes("PING");
        existing.setMinLevel("HIGH");
        existing.setSendResolve(false);
        when(repo.findById(NocSettings.SINGLETON_ID)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabledTypes", Map.of("PING", true, "DNS", false));
        body.put("callInstructions", "  Önce ara.\r\nSonra yaz.  ");
        NocConfigService.Config c = svc.save(body, "admin", "Kişi A");
        assertThat(c.typeEnabled(NocType.PING)).isTrue();
        assertThat(c.typeEnabled(NocType.DNS)).isFalse();
        assertThat(c.minLevel()).isEqualTo("HIGH");          // gelmedi → korundu
        assertThat(c.sendResolve()).isFalse();              // gelmedi → korundu
        assertThat(c.callInstructions()).isEqualTo("Önce ara.\nSonra yaz.");
        assertThat(existing.getDisabledTypes()).isEqualTo("DNS");
    }

    @Test
    @DisplayName("kaydet: geçersiz seviye / bilinmeyen tür / boolean olmayan değer / aşırı uzun talimat → 400")
    void saveRejects() {
        when(repo.findById(NocSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.save(Map.of("minLevel", "LOW"), "a", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.save(Map.of("enabledTypes", Map.of("FTP", true)), "a", "a"))
                .hasMessageContaining("Bilinmeyen");
        assertThatThrownBy(() -> svc.save(Map.of("enabledTypes", Map.of("PING", "evet")), "a", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.save(Map.of("sendResolve", "true"), "a", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.save(Map.of("callInstructions", "x".repeat(2001)), "a", "a"))
                .hasMessageContaining("2000");
    }
}
