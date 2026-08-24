package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.RememberMeToken;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.RememberMeTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Cihaz Geçmişi okuma servisi.
 *
 * <p>Buradaki testlerin çoğu GİZLİLİK sözleşmesini pinler: yanıt oturum kimliği, token değeri ya
 * da token hash'i taşımamalı. Bu sözleşme olmadan ekran, kullanıcıya açık bir sayfanın ağ yükünde
 * kimlik doğrulama sırrı taşırdı.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceHistoryServiceTest {

    @Mock RememberMeTokenRepository rememberRepo;
    @Mock AuditLogRepository auditLogRepo;
    @Mock GeoIpService geoIpService;
    @Mock AppSettingsService appSettings;

    @InjectMocks DeviceHistoryService service;

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setUsername("N68753");
        user.setLastSeenAt("2026-08-24T18:00:00");
        when(appSettings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(365);
        when(auditLogRepo.findLatestOwnLogin(anyString())).thenReturn(Optional.empty());
        when(rememberRepo.findByUsername(anyString())).thenReturn(List.of());
    }

    private static AuditLog login(String ip, String city, String country, String ua) {
        AuditLog a = new AuditLog();
        a.setId(7L);
        a.setEventTime("2026-08-24T09:15:00");
        a.setEventType("LOGIN");
        a.setOutcome("SUCCESS");
        a.setIpAddress(ip);
        a.setIpCity(city);
        a.setIpCountry(country);
        a.setUserAgent(ua);
        a.setSessionId("GIZLI-OTURUM");
        return a;
    }

    private static final String CHROME_WIN =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";

    // ── Gizlilik sözleşmesi ──────────────────────────────────────────────────

    @Test
    @DisplayName("GIZLILIK: yanit oturum kimligi, token degeri ya da HASH tasimaz")
    void neverLeaksSecrets() {
        when(auditLogRepo.findLatestOwnLogin(anyString()))
                .thenReturn(Optional.of(login("88.1.2.3", "Istanbul", "TR", CHROME_WIN)));
        RememberMeToken t = new RememberMeToken();
        t.setId(3L);
        t.setToken("GIZLI-TOKEN-HASHI");
        t.setUaSummary("Windows · Chrome");
        when(rememberRepo.findByUsername("N68753")).thenReturn(List.of(t));

        String json = String.valueOf(service.devicesFor(user, "GIZLI-TOKEN-HASHI"));

        assertThat(json).doesNotContain("GIZLI-OTURUM");
        assertThat(json).doesNotContain("GIZLI-TOKEN-HASHI");
        // Opak satir id'si TASINIR — iptal ucu bununla calisir.
        assertThat(json).contains("id=3");
    }

    @Test
    @DisplayName("Bu cihaz isaretlemesi HASH esitligiyle yapilir (deger disari cikmadan)")
    void marksCurrentDeviceByHash() {
        RememberMeToken mine = new RememberMeToken();
        mine.setId(1L); mine.setToken("HASH-A");
        RememberMeToken other = new RememberMeToken();
        other.setId(2L); other.setToken("HASH-B");
        when(rememberRepo.findByUsername("N68753")).thenReturn(List.of(mine, other));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.devicesFor(user, "HASH-A").get("remembered");

        assertThat(rows.get(0).get("is_this_device")).isEqualTo(true);
        assertThat(rows.get(1).get("is_this_device")).isEqualTo(false);
    }

    // ── Konum (K5) ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("PRIVATE IP: sehir/ulke yerine 'kurum agi' etiketi — satir bos gorunmez")
    void privateIpBecomesCorporateNetwork() {
        when(geoIpService.isPrivateIp("10.1.2.3")).thenReturn(true);
        when(auditLogRepo.findLatestOwnLogin(anyString()))
                .thenReturn(Optional.of(login("10.1.2.3", null, null, CHROME_WIN)));

        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) service.devicesFor(user, null).get("current");

        assertThat(current.get("location_kind")).isEqualTo(DeviceHistoryService.CORPORATE_NETWORK);
        assertThat(current.get("city")).isNull();
    }

    @Test
    @DisplayName("PUBLIC IP: geo alanlari oldugu gibi tasinir")
    void publicIpKeepsGeo() {
        when(geoIpService.isPrivateIp("88.1.2.3")).thenReturn(false);
        when(auditLogRepo.findLatestOwnLogin(anyString()))
                .thenReturn(Optional.of(login("88.1.2.3", "Istanbul", "TR", CHROME_WIN)));

        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) service.devicesFor(user, null).get("current");

        assertThat(current.get("city")).isEqualTo("Istanbul");
        assertThat(current.get("country")).isEqualTo("TR");
        assertThat(current.get("location_kind")).isNull();
        assertThat(current.get("ua_summary")).isEqualTo("Windows · Chrome");
    }

    // ── Dayaniklilik ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Hic giris kaydi YOKSA ekran cokmez — 'bilgi yok' doner (grandfathered kullanici)")
    void noLoginRowYieldsUnknown() {
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) service.devicesFor(user, null).get("current");

        assertThat(current.get("known")).isEqualTo(false);
        assertThat(current.get("last_seen_at")).isEqualTo("2026-08-24T18:00:00");
    }

    @Test
    @DisplayName("Giris gecmisi: anomali bayraklari LISTEYE cevrilir, ham UA ayri alanda kalir")
    void loginRowsSplitFlagsAndKeepRawUa() {
        AuditLog a = login("88.1.2.3", "Istanbul", "TR", CHROME_WIN);
        a.setAnomalyFlags("OFF_HOURS,UNUSUAL_IP");
        Page<AuditLog> page = new PageImpl<>(List.of(a), Pageable.ofSize(50), 1);
        when(auditLogRepo.findOwnLogins(anyString(), anyString(), any())).thenReturn(page);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.loginsFor(user, false, 0, 50).get("rows");

        assertThat(rows).hasSize(1);
        assertThat((List<String>) rows.get(0).get("anomaly_flags"))
                .containsExactly("OFF_HOURS", "UNUSUAL_IP");
        assertThat(rows.get(0).get("ua_summary")).isEqualTo("Windows · Chrome");
        assertThat(rows.get(0).get("ua_raw")).isEqualTo(CHROME_WIN);   // yalniz satir genisletmesi icin
    }

    @Test
    @DisplayName("failed=true LOGIN_FAILED sorgular; false LOGIN — iki bolum karismaz")
    void failedFlagSelectsEventType() {
        Page<AuditLog> empty = new PageImpl<>(List.of(), Pageable.ofSize(50), 0);
        when(auditLogRepo.findOwnLogins(anyString(), anyString(), any())).thenReturn(empty);

        service.loginsFor(user, true, 0, 50);
        org.mockito.Mockito.verify(auditLogRepo)
                .findOwnLogins(anyString(), org.mockito.ArgumentMatchers.eq("LOGIN_FAILED"), any());

        service.loginsFor(user, false, 0, 50);
        org.mockito.Mockito.verify(auditLogRepo)
                .findOwnLogins(anyString(), org.mockito.ArgumentMatchers.eq("LOGIN"), any());
    }

    @Test
    @DisplayName("Sayfa boyutu 200'le SINIRLI (istemci suruyu cekemesin)")
    void pageSizeIsCapped() {
        Page<AuditLog> empty = new PageImpl<>(List.of(), Pageable.ofSize(50), 0);
        when(auditLogRepo.findOwnLogins(anyString(), anyString(), any())).thenReturn(empty);

        service.loginsFor(user, false, 0, 5000);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> cap =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(auditLogRepo).findOwnLogins(anyString(), anyString(), cap.capture());
        assertThat(cap.getValue().getPageSize()).isEqualTo(200);
    }
}
