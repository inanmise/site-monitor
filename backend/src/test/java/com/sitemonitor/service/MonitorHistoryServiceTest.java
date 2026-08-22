package com.sitemonitor.service;

import com.sitemonitor.model.MonitorChangeLog;
import com.sitemonitor.repository.MonitorChangeLogRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MonitorHistoryService} — "kim, ne zaman, hangi IP'den, neyi değiştirdi" satırının sözleşmesi.
 *
 * <p>Bu servis kullanıcının kayıt yolunun İÇİNDE çağrılıyor; bu yüzden burada test edilen iki şey
 * eşit derecede önemli: (1) doğru satırı yazması, (2) yazamadığında kullanıcının kaydını
 * DÜŞÜRMEMESİ. İkincisi olmadan geçmiş özelliği, izleme kaydetmeyi bozan bir risk olurdu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorHistoryServiceTest {

    @Mock MonitorChangeLogRepository repo;
    @Mock ClientIpResolver clientIpResolver;
    @InjectMocks MonitorHistoryService service;

    private static final String[] FIELDS = { "name", "intervalSeconds", "active", "password" };

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private HttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "N70678");
        s.setAttribute("userId", 42L);
        s.setAttribute("fullName", "Ada Lovelace");
        return s;
    }

    private void bindRequest(String ip, String ua) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("User-Agent", ua);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        when(clientIpResolver.resolve(any())).thenReturn(ip);
    }

    private MonitorChangeLog captureSaved() {
        ArgumentCaptor<MonitorChangeLog> cap = ArgumentCaptor.forClass(MonitorChangeLog.class);
        verify(repo).save(cap.capture());
        return cap.getValue();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ── Yazım ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CREATE: seq 0 ile yazılır, İLK DEĞERLER snapshot'ta durur")
    void create_writesSnapshotAsSeqZero() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.empty());
        bindRequest("10.20.30.40", "Mozilla/5.0 Chrome/126");

        service.record(MonitorHistoryService.PORT, 7L, "Ödeme portu", 5L,
                MonitorHistoryService.CREATE, null, map("name", "Ödeme portu", "intervalSeconds", 300),
                null, session());

        MonitorChangeLog row = captureSaved();
        assertThat(row.getSeq()).isZero();
        assertThat(row.getEventType()).isEqualTo("CREATE");
        assertThat(row.getResourceName()).isEqualTo("Ödeme portu");
        assertThat(row.getTeamId()).isEqualTo(5L);
        // "Hangi değerlerle doğdu" sorusunun cevabı: CREATE'te diff yok, snapshot VAR.
        assertThat(row.getSnapshot()).contains("\"intervalSeconds\":300");
        assertThat(row.getActor()).isEqualTo("N70678");
        assertThat(row.getActorId()).isEqualTo(42L);
        assertThat(row.getIpAddress()).isEqualTo("10.20.30.40");
        assertThat(row.getUserAgent()).contains("Chrome/126");
    }

    @Test
    @DisplayName("seq kaynak başına ARTAR — geçmiş 'ilk kayıt'tan itibaren numaralanır")
    void seq_incrementsPerResource() {
        when(repo.findMaxSeq("PORT", 7L)).thenReturn(Optional.of(4));

        service.record(MonitorHistoryService.PORT, 7L, "p", 5L, MonitorHistoryService.UPDATE,
                map("name", "a"), map("name", "b"), null, session());

        assertThat(captureSaved().getSeq()).isEqualTo(5);
    }

    @Test
    @DisplayName("UPDATE: yalnız DEĞİŞEN alanlar diff'e girer")
    void update_writesOnlyChangedFields() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.of(0));

        service.record(MonitorHistoryService.HTTP, 3L, "Ana sayfa", 5L, MonitorHistoryService.UPDATE,
                map("name", "Ana sayfa", "intervalSeconds", 300, "active", true),
                map("name", "Ana sayfa", "intervalSeconds", 60, "active", true),
                null, session());

        String changes = captureSaved().getChanges();
        assertThat(changes).contains("intervalSeconds").contains("300").contains("60");
        assertThat(changes).doesNotContain("\"name\"");
        assertThat(changes).doesNotContain("\"active\"");
    }

    @Test
    @DisplayName("UPDATE: hiçbir alan değişmediyse satır YAZILMAZ (kaydet'e basmak olay değildir)")
    void update_noChange_writesNothing() {
        MonitorChangeLog result = service.record(MonitorHistoryService.HTTP, 3L, "Ana sayfa", 5L,
                MonitorHistoryService.UPDATE, map("name", "a"), map("name", "a"), null, session());

        assertThat(result).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("UPDATE: değişiklik yok ama kullanıcı NOT yazmışsa satır yazılır")
    void update_noChangeButNote_writes() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.of(1));

        service.record(MonitorHistoryService.HTTP, 3L, "Ana sayfa", 5L, MonitorHistoryService.UPDATE,
                map("name", "a"), map("name", "a"), "  Yanlış alarm incelemesi  ", session());

        // Not kırpılır: baştaki/sondaki boşluk geçmişte görünmesin.
        assertThat(captureSaved().getNote()).isEqualTo("Yanlış alarm incelemesi");
    }

    @Test
    @DisplayName("DELETE: satır SON durumun snapshot'ıyla yazılır — kaynak gitse de geçmiş okunur")
    void delete_keepsFinalSnapshot() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.of(2));

        service.record(MonitorHistoryService.SCRIPTED, 9L, "Ödeme akışı", 5L,
                MonitorHistoryService.DELETE, map("name", "Ödeme akışı"), null, null, session());

        MonitorChangeLog row = captureSaved();
        assertThat(row.getEventType()).isEqualTo("DELETE");
        assertThat(row.getResourceName()).isEqualTo("Ödeme akışı");
        assertThat(row.getSnapshot()).contains("Ödeme akışı");
    }

    // ── Gizlilik ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Hassas alan hem diff'te hem snapshot'ta MASKELİ yazılır — düz metin sızmaz")
    void secrets_areMaskedEverywhere() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.of(0));

        service.record(MonitorHistoryService.HTTP, 3L, "h", 5L, MonitorHistoryService.UPDATE,
                map("password", "eskiSir123"), map("password", "yeniSir456"), null, session());

        MonitorChangeLog row = captureSaved();
        assertThat(row.getChanges()).contains(AuditDiff.MASK)
                .doesNotContain("eskiSir123").doesNotContain("yeniSir456");
        assertThat(row.getSnapshot()).contains(AuditDiff.MASK).doesNotContain("yeniSir456");
    }

    // ── Best-effort ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Depo patlarsa istisna YUTULUR — geçmiş yazılamaması kullanıcının kaydını düşürmez")
    void repositoryFailure_isSwallowed() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.of(0));
        when(repo.save(any())).thenThrow(new RuntimeException("tablo kilitli"));

        MonitorChangeLog result = service.record(MonitorHistoryService.PORT, 7L, "p", 5L,
                MonitorHistoryService.CREATE, null, map("name", "p"), null, session());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Oturum ve istek yoksa (zamanlanmış iş) satır yine yazılır, künye boş kalır")
    void noSessionNoRequest_stillWrites() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.empty());

        service.record(MonitorHistoryService.PORT, 7L, "p", 5L, MonitorHistoryService.CREATE,
                null, map("name", "p"), null, null);

        MonitorChangeLog row = captureSaved();
        assertThat(row.getActor()).isNull();
        assertThat(row.getIpAddress()).isNull();
        assertThat(row.getSnapshot()).contains("\"name\"");
    }

    @Test
    @DisplayName("Eksik zorunlu parametre satır yazdırmaz (çağıran yolun hatası geçmişi kirletmesin)")
    void missingKeyParams_writeNothing() {
        assertThat(service.record(null, 7L, "p", 5L, "CREATE", null, map(), null, session())).isNull();
        assertThat(service.record("PORT", null, "p", 5L, "CREATE", null, map(), null, session())).isNull();
        assertThat(service.record("PORT", 7L, "p", 5L, null, null, map(), null, session())).isNull();
        verify(repo, never()).save(any());
    }

    // ── Geri doldurma ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Backfill İKİ KEZ koşarsa ikinci kez satır yazmaz (geçmiş çiftlenmez)")
    void backfill_isIdempotent() {
        when(repo.existsByResourceKindAndResourceIdAndEventTypeAndCreatedAt(
                "PORT", 7L, "CREATE", "2026-01-01T00:00:00")).thenReturn(true);

        service.recordBackfill("PORT", 7L, "p", 5L, "CREATE", null, "N1", 1L, "1.2.3.4",
                "2026-01-01T00:00:00");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Backfill satırı NEREDEN geldiğini not olarak taşır")
    void backfill_marksOrigin() {
        when(repo.findMaxSeq(anyString(), anyLong())).thenReturn(Optional.empty());

        service.recordBackfill("PORT", 7L, "p", 5L, "CREATE", "{}", "N1", 1L, "1.2.3.4",
                "2026-01-01T00:00:00");

        MonitorChangeLog row = captureSaved();
        assertThat(row.getNote()).contains(MonitorHistoryService.AUDIT_BACKFILL);
        assertThat(row.getCreatedAt()).isEqualTo("2026-01-01T00:00:00");
        assertThat(row.getIpAddress()).isEqualTo("1.2.3.4");
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("changedFields diff JSON'undan alan adlarını çıkarır (Activity özeti)")
    void changedFields_parsesNames() {
        String changes = AuditDiff.diff(map("name", "a", "port", 80), map("name", "b", "port", 443));
        assertThat(MonitorHistoryService.changedFields(changes)).containsExactlyInAnyOrder("name", "port");
        assertThat(MonitorHistoryService.changedFields(null)).isEmpty();
        assertThat(MonitorHistoryService.changedFields("{}")).isEmpty();
    }

    @Test
    @DisplayName("stampCreated künyeyi basar; kolonu olmayan entity'de sessizce geçer")
    void stamp_worksAndDegradesQuietly() {
        bindRequest("10.0.0.9", "curl/8");
        Stamped bean = new Stamped();

        service.stampCreated(bean, session());
        service.stampUpdated(bean, session());
        service.stampCreated(new Object(), session()); // setter yok → istisna FIRLATMAZ

        assertThat(bean.createdBy).isEqualTo("N70678");
        assertThat(bean.createdByName).isEqualTo("Ada Lovelace");
        assertThat(bean.createdIp).isEqualTo("10.0.0.9");
        assertThat(bean.updatedBy).isEqualTo("N70678");
    }

    public static class Stamped {
        String createdBy, createdByName, createdIp, updatedBy, updatedByName;
        public void setCreatedBy(String v) { createdBy = v; }
        public void setCreatedByName(String v) { createdByName = v; }
        public void setCreatedIp(String v) { createdIp = v; }
        public void setUpdatedBy(String v) { updatedBy = v; }
        public void setUpdatedByName(String v) { updatedByName = v; }
    }

    // ── Sözleşme ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tür haritası sözleşmesi")
    class KindContract {

        /** Olay türleri bilinçli olarak elle sayılır: yeni bir OLAY eklemek görünür bir karardır. */
        private static final Set<String> EVENT_CONSTANTS = Set.of(
                "CREATE", "UPDATE", "DELETE", "RESTORE", "GROUP_RENAME", "AUDIT_BACKFILL");

        @Test
        @DisplayName("Yeni bir kaynak türü sabiti KIND_BY_PATH'e kaydedilmeden derlenmemeli")
        void everyKindConstantIsRoutable() throws Exception {
            Set<String> registered = new HashSet<>(MonitorHistoryService.KIND_BY_PATH.values());
            for (Field f : MonitorHistoryService.class.getDeclaredFields()) {
                if (!Modifier.isPublic(f.getModifiers()) || !Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != String.class) continue;
                String value = (String) f.get(null);
                if (EVENT_CONSTANTS.contains(f.getName())) continue;
                // Kaynak türü sabiti → arayüzün `kind` yolundan ERİŞİLEBİLİR olmalı; aksi halde
                // o türe yazılan satırları hiçbir ekran okuyamaz (sessiz ölü veri).
                assertThat(registered)
                        .as("%s sabiti KIND_BY_PATH'te yok — geçmişi okunamaz", f.getName())
                        .contains(value);
            }
        }

        @Test
        @DisplayName("Yol anahtarları küçük harf ve tekil — URL sözleşmesi kaymasın")
        void pathKeysAreLowercase() {
            MonitorHistoryService.KIND_BY_PATH.forEach((path, kind) -> {
                assertThat(path).isEqualTo(path.toLowerCase());
                assertThat(kind).isEqualTo(kind.toUpperCase());
            });
        }

        @Test
        @DisplayName("Sekiz izleme türünün TAMAMI kayıtlı (yeni tür eklenince burası kırılır)")
        void allEightMonitorTypesRegistered() {
            assertThat(MonitorHistoryService.KIND_BY_PATH.keySet()).contains(
                    "port", "dns", "keyword", "http", "page", "scripted", "domain", "ping",
                    "inventory", "group", "maintenance");
        }
    }
}
