package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportImage;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportImageRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kanal teknik hata raporu servisi + depoları (2026-10-13) — H2'de GERÇEK SQL. {@code NOT_SUPPORTED}: uygulamadaki gibi
 * işlemsiz çağıran; {@code @Modifying} sorgularının kendi işlemi yoksa burada kırılır.
 */
@DataJpaTest
@Import({TechErrorReportService.class, TechErrorHistoryService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorReportServiceTest {

    @Autowired TechErrorReportService service;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorReportImageRepository images;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TechErrorHistoryService history;
    @Autowired com.sitemonitor.repository.TechErrorReportEventRepository events;
    @Autowired com.sitemonitor.repository.TechErrorTemplateVersionRepository versions;

    @BeforeEach
    void clean() {
        events.deleteAll();
        versions.deleteAll();
        images.deleteAll();
        reports.deleteAll();
        channels.deleteAll();
        // "Şimdi" = 2026-10-13 (İstanbul) → varsayılan dönem Eylül 2026, bitmiş son ay Eylül
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC));
    }

    private TechErrorChannel channel(long team, String name) {
        return service.createChannel(team, name, null, TechErrorTemplate.defaults("tr"), 7L);
    }

    @Test
    @DisplayName("dönem: varsayılan önceki ay; içinde bulunulan ay ve gelecek reddedilir; Ocak'ta önceki yılın Aralık'ı")
    void period() {
        assertThat(service.defaultPeriod().toString()).isEqualTo("2026-09");
        assertThat(service.isClosedMonth(2026, 9)).isTrue();
        assertThat(service.isClosedMonth(2026, 10)).isFalse();
        assertThat(service.isClosedMonth(2027, 1)).isFalse();
        // İstanbul gece yarısı: UTC 31 Ara 21:30 = İstanbul 1 Ocak 00:30 → Aralık BİTTİ
        service.useClock(Clock.fixed(Instant.parse("2025-12-31T21:30:00Z"), ZoneOffset.UTC));
        assertThat(service.defaultPeriod().toString()).isEqualTo("2025-12");
        assertThat(service.isClosedMonth(2025, 12)).isTrue();
    }

    @Test
    @DisplayName("kanal adı takım içinde tekil (büyük/küçük harf, Türkçe İ/i); başka takımda aynı ad serbest; 50 kanal sınırı")
    void channelNames() {
        channel(1, "İnternet Şubesi");
        assertThatThrownBy(() -> channel(1, "  internet   şubesi "))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode())
                .isEqualTo("CHANNEL_EXISTS");
        assertThat(channel(2, "İnternet Şubesi").getId()).isNotNull();
        assertThatThrownBy(() -> channel(1, " ")).isInstanceOf(FieldValidationException.class);
        for (int i = 1; i < TechErrorReportService.MAX_CHANNELS_PER_TEAM; i++) channel(3, "Kanal " + i);
        channel(3, "Son");
        assertThatThrownBy(() -> channel(3, "Fazla")).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("CHANNEL_LIMIT");
    }

    @Test
    @DisplayName("kanal güncellemesi yalnız gönderilen alanları değiştirir ve şablon sürümünü ARTIRMAZ")
    void updateChannelKeepsTemplateVersion() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorChannel up = service.updateChannel(ch.getId(), Map.of("description", "Mobil uygulama"), 8L);
        assertThat(up.getName()).isEqualTo("Mobil");
        assertThat(up.getDescription()).isEqualTo("Mobil uygulama");
        assertThat(up.getTemplateVersion()).isEqualTo(1);
        Map<String, Object> off = new HashMap<>();
        off.put("active", false);
        assertThat(service.updateChannel(ch.getId(), off, 8L).isActive()).isFalse();
        assertThatThrownBy(() -> service.updateChannel(ch.getId(), Map.of("active", "hayır"), 8L))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("şablon kaydı koşullu: eski sürümle ikinci kayıt 409, hiçbir şey ezilmez; silinmiş kanal 404")
    void templateOptimisticLock() {
        TechErrorChannel ch = channel(1, "POS");
        Template t = TechErrorTemplate.defaults("en");
        assertThat(service.saveTemplate(ch.getId(), 1, t, 9L)).isEqualTo(2);
        assertThatThrownBy(() -> service.saveTemplate(ch.getId(), 1, TechErrorTemplate.defaults("tr"), 9L))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(TechErrorTemplate.fromStored(channels.findById(ch.getId()).orElseThrow().getTemplateJson()).lang()).isEqualTo("en");
        assertThatThrownBy(() -> service.saveTemplate(999_999L, 1, t, 9L)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("rapor: bitmiş ay, kanal × ay tekil (409 + mevcut kimlik), pasif kanala yeni rapor açılmaz")
    void createReport() {
        TechErrorChannel ch = channel(1, "ATM");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        assertThat(r.getTemplateJson()).isEqualTo(ch.getTemplateJson());
        // Liste başlığı raporun KENDİ kopyasından: kalıp + dil saklanır
        assertThat(r.getTitlePattern()).isEqualTo(TechErrorTemplate.fromStored(ch.getTemplateJson()).title());
        assertThat(r.getTemplateLang()).isEqualTo("tr");
        assertThat(reports.findByChannelIdOrderByReportYearDescReportMonthDesc(ch.getId()).get(0).getTitlePattern())
                .isEqualTo(r.getTitlePattern());
        assertThatThrownBy(() -> service.createReport(ch, 2026, 9, 7L)).isInstanceOf(CodedConflictException.class)
                .satisfies(e -> {
                    CodedConflictException c = (CodedConflictException) e;
                    assertThat(c.getCode()).isEqualTo("REPORT_EXISTS");
                    assertThat(c.getExtra()).containsEntry("report_id", r.getId());
                });
        assertThatThrownBy(() -> service.createReport(ch, 2026, 10, 7L)).isInstanceOf(FieldValidationException.class);
        TechErrorChannel off = service.updateChannel(ch.getId(), Map.of("active", false), 7L);
        assertThatThrownBy(() -> service.createReport(off, 2026, 8, 7L)).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("CHANNEL_INACTIVE");
    }

    @Test
    @DisplayName("değer kaydı sürüm denetimli: eski sürüm 409; değişiklik yoksa sürüm artmaz; içerik damgası ilerler")
    void saveValuesVersioned() {
        TechErrorReport r = service.createReport(channel(1, "Kurumsal"), 2026, 9, 7L);
        Long v0 = r.getVersion();
        Map<String, Object> vals = Map.of("fields", Map.of("error-rate", 0.42));
        TechErrorReport s1 = service.saveValues(r.getId(), v0, vals, 8L);
        assertThat(s1.getVersion()).isGreaterThan(v0);
        assertThat(service.saveValues(r.getId(), s1.getVersion(), vals, 8L).getVersion()).isEqualTo(s1.getVersion());
        assertThatThrownBy(() -> service.saveValues(r.getId(), v0, Map.of("fields", Map.of("error-rate", 1)), 9L))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> service.saveValues(r.getId(), null, vals, 9L)).isInstanceOf(FieldValidationException.class);
        assertThat(reports.findById(r.getId()).orElseThrow().getValuesJson()).contains("0.42");
    }

    @Test
    @DisplayName("görsel: yuva başına bir (yeniden yükleme yerini alır), sürüm ARTMAZ, içerik damgası ilerler; bilinmeyen yuva 400")
    void uploadReplacesAndKeepsVersion() throws Exception {
        TechErrorReport r = service.createReport(channel(1, "Mobil"), 2026, 9, 7L);
        Long v = r.getVersion();
        Instant changed0 = r.getContentChangedAt();
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:05:00Z"), ZoneOffset.UTC));
        TechErrorReportImage a = service.upload(r.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "c:\\x\\grafik.png", 7L);
        TechErrorReportImage b = service.upload(r.getId(), "monthly-tx", TechErrorImageValidatorTest.png(500, 250), "grafik2.png", 7L);
        assertThat(images.findByReportIdOrderByIdAsc(r.getId())).extracting(TechErrorReportImageRepository.Meta::getId).containsExactly(b.getId());
        assertThat(a.getFileName()).isEqualTo("grafik.png");
        TechErrorReport after = reports.findById(r.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(v);
        assertThat(after.getContentChangedAt()).isAfter(changed0);
        assertThatThrownBy(() -> service.upload(r.getId(), "yok", TechErrorImageValidatorTest.png(400, 200), "x.png", 7L))
                .isInstanceOf(FieldValidationException.class);
        // başka raporun görseli bu raporun kimliğiyle silinemez
        TechErrorReport other = service.createReport(channel(2, "Başka"), 2026, 9, 7L);
        assertThatThrownBy(() -> service.deleteImage(other.getId(), b.getId(), 7L)).isInstanceOf(java.util.NoSuchElementException.class);
        service.deleteImage(r.getId(), b.getId(), 7L);
        assertThat(images.findByReportIdOrderByIdAsc(r.getId())).isEmpty();
    }

    @Test
    @DisplayName("şablonun son hâli: aynı kimlikli değer/görsel korunur, çıkarılanlar silinir; önizleme sayıları doğru")
    void refreshTemplate() throws Exception {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        TechErrorReport s = service.saveValues(r.getId(), r.getVersion(),
                Map.of("fields", Map.of("error-rate", 0.5, "quota-count", 12)), 7L);
        service.upload(r.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "a.png", 7L);
        service.upload(r.getId(), "quota-monthly", TechErrorImageValidatorTest.png(400, 200), "b.png", 7L);

        // Kanal şablonundan kota bölümü ve kota grafiği çıkar
        Map<String, Object> raw = TechErrorTemplate.toMap(TechErrorTemplate.fromStored(ch.getTemplateJson()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> secs = new ArrayList<>((List<Map<String, Object>>) raw.get("sections"));
        secs.removeIf(x -> "quota".equals(x.get("id")));
        Map<String, Object> charts = new LinkedHashMap<>(secs.stream().filter(x -> "charts-monthly".equals(x.get("id"))).findFirst().orElseThrow());
        @SuppressWarnings("unchecked") List<Map<String, Object>> slots = new ArrayList<>((List<Map<String, Object>>) charts.get("slots"));
        slots.removeIf(x -> "quota-monthly".equals(x.get("id")));
        charts.put("slots", slots);
        secs.replaceAll(x -> "charts-monthly".equals(x.get("id")) ? charts : x);
        raw.put("sections", secs);
        service.saveTemplate(ch.getId(), 1, TechErrorTemplate.normalize(raw), 7L);

        TechErrorChannel fresh = service.channel(ch.getId());
        Map<String, Object> preview = service.refreshPreview(reports.findById(r.getId()).orElseThrow(), fresh);
        assertThat(preview).containsEntry("differs", true).containsEntry("lost_values", 1).containsEntry("lost_images", 1);

        var res = service.refreshTemplate(r.getId(), s.getVersion(), 7L);
        assertThat(res.removedValues()).isEqualTo(1);
        assertThat(res.removedImages()).isEqualTo(1);
        assertThat(images.findByReportIdOrderByIdAsc(r.getId())).extracting(TechErrorReportImageRepository.Meta::getSlotId)
                .containsExactly("monthly-tx");
        TechErrorReport after = reports.findById(r.getId()).orElseThrow();
        assertThat(after.getValuesJson()).contains("error-rate").doesNotContain("quota-count");
        assertThat(service.refreshPreview(after, fresh)).containsEntry("differs", false);
    }

    @Test
    @DisplayName("gönderim talebi en çok bir kez; 10 dk'dan eski talep elle yeniden alınır; sonuç yalnız kendi talebine yazılır")
    void sendClaimAtMostOnce() {
        TechErrorReport r = service.createReport(channel(1, "POS"), 2026, 9, 7L);
        Instant t0 = Instant.parse("2026-10-13T09:00:00.123Z");
        assertThat(reports.claimSend(r.getId(), t0, t0.minusSeconds(600))).isEqualTo(1);
        assertThat(reports.claimSend(r.getId(), t0.plusSeconds(5), t0.minusSeconds(595))).isZero();
        Instant t1 = t0.plusSeconds(700);
        assertThat(reports.claimSend(r.getId(), t1, t1.minusSeconds(600))).isEqualTo(1);    // asılı talep devralındı
        assertThat(reports.finishSent(r.getId(), t0, "SENT", t1, 7L, "a@b.com", 0L)).isZero();  // eski talep yazamaz
        assertThat(reports.finishSent(r.getId(), t1, "SENT", t1.plusSeconds(30), 7L, "a@b.com", 0L)).isEqualTo(1);
        TechErrorReport after = reports.findById(r.getId()).orElseThrow();
        assertThat(after.getSendState()).isEqualTo("SENT");
        assertThat(after.getSendCount()).isEqualTo(1);
        assertThat(after.getLastSentTo()).isEqualTo("a@b.com");
        assertThat(after.getVersion()).isEqualTo(r.getVersion());                            // sürüm artmadı
        assertThat(reports.claimSend(r.getId(), t1.plusSeconds(1), t1.minusSeconds(599))).isEqualTo(1);
        assertThat(reports.finishNotSent(r.getId(), t1.plusSeconds(1), "FAILED", "FAILED: x", t1)).isEqualTo(1);
        assertThat(reports.findById(r.getId()).orElseThrow().getLastSentAt()).isEqualTo(t1);   // önceki başarı korunur
    }

    @Test
    @DisplayName("içerik sayacı: her içerik yazımı +1; gönderim PDF okumasının sayacını saklar, sonraki kayıt 'değişti' yapar")
    void contentRevisionDrivesChangedAfterSend() throws Exception {
        TechErrorReport r = service.createReport(channel(1, "POS"), 2026, 9, 7L);
        long rev0 = rev(r.getId());
        TechErrorReport s1 = service.saveValues(r.getId(), r.getVersion(), Map.of("fields", Map.of("error-rate", 1.5)), 8L);
        assertThat(rev(r.getId())).isEqualTo(rev0 + 1);
        service.upload(r.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "a.png", 7L);
        assertThat(rev(r.getId())).isEqualTo(rev0 + 2);
        // Değişmeyen kayıt sayacı artırmaz
        service.saveValues(r.getId(), s1.getVersion(), Map.of("fields", Map.of("error-rate", 1.5)), 8L);
        assertThat(rev(r.getId())).isEqualTo(rev0 + 2);

        Instant claim = Instant.parse("2026-10-13T09:10:00Z");
        assertThat(reports.claimSend(r.getId(), claim, claim.minusSeconds(600))).isEqualTo(1);
        long pdfRev = rev(r.getId());                                   // gönderici PDF'i bu okumadan üretti
        // PDF okunduktan SONRA (ama "şimdi"si talepten önce alınmış) bir kayıt tamamlandı
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:09:59Z"), ZoneOffset.UTC));
        service.saveValues(r.getId(), s1.getVersion(), Map.of("fields", Map.of("error-rate", 2)), 8L);
        assertThat(reports.finishSent(r.getId(), claim, "SENT", claim, 7L, "a@b.com", pdfRev)).isEqualTo(1);
        TechErrorReport after = reports.findById(r.getId()).orElseThrow();
        assertThat(after.getLastSentAt()).isEqualTo(claim);
        assertThat(after.getSentRev()).isEqualTo(pdfRev);
        assertThat(after.getContentRev()).isGreaterThan(after.getSentRev());   // "değişti" — zaman damgası bunu kaçırırdı
        assertThat(after.getContentChangedAt()).isBefore(after.getLastSentAt());
        // Değer kaydı (varlık kaydı) gönderim sütunlarını ezmez (@DynamicUpdate + updatable=false)
        TechErrorReport again = service.saveValues(r.getId(), after.getVersion(), Map.of("fields", Map.of("error-rate", 3)), 8L);
        TechErrorReport reread = reports.findById(again.getId()).orElseThrow();
        assertThat(reread.getSendCount()).isEqualTo(1);
        assertThat(reread.getLastSentTo()).isEqualTo("a@b.com");
        assertThat(reread.getSentRev()).isEqualTo(pdfRev);
    }

    private long rev(Long id) {
        Long v = reports.findById(id).orElseThrow().getContentRev();
        return v == null ? 0L : v;
    }

    @Test
    @DisplayName("bayat varlık kaydı arada biten gönderimi geri almaz (değer kaydı talepten ÖNCE okunmuş olsa bile)")
    void staleEntitySaveKeepsSendColumns() {
        TechErrorReport r = service.createReport(channel(1, "POS"), 2026, 9, 7L);
        TechErrorReport stale = reports.findById(r.getId()).orElseThrow();      // gönderimden önce okundu
        Instant claim = Instant.parse("2026-10-13T09:10:00Z");
        reports.claimSend(r.getId(), claim, claim.minusSeconds(600));
        reports.finishSent(r.getId(), claim, "SENT", claim, 7L, "a@b.com", 0L);
        stale.setValuesJson("{\"fields\":{}}");
        reports.saveAndFlush(stale);
        TechErrorReport after = reports.findById(r.getId()).orElseThrow();
        assertThat(after.getSendState()).isEqualTo("SENT");
        assertThat(after.getLastSentAt()).isEqualTo(claim);
        assertThat(after.getSendCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("talep bırakma: yalnız bu talep; durum satırın KENDİ son sonucundan türetilir")
    void releaseClaim() {
        TechErrorReport r = service.createReport(channel(1, "POS"), 2026, 9, 7L);
        Instant claim = Instant.parse("2026-10-13T09:10:00Z");
        reports.claimSend(r.getId(), claim, claim.minusSeconds(600));
        assertThat(reports.releaseClaim(r.getId(), claim.plusSeconds(1), null)).isZero();
        assertThat(reports.releaseClaim(r.getId(), claim, null)).isEqualTo(1);
        assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isNull();      // hiç denenmemiş
        Instant c2 = claim.plusSeconds(2);
        assertThat(reports.claimSend(r.getId(), c2, claim.minusSeconds(598))).isEqualTo(1);   // yeniden gönderilebilir
        reports.finishNotSent(r.getId(), c2, "QUEUED", "QUEUED_RETRY: 421", c2);
        Instant c3 = claim.plusSeconds(3);
        reports.claimSend(r.getId(), c3, claim.minusSeconds(597));
        reports.releaseClaim(r.getId(), c3, c2);
        assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isEqualTo("QUEUED");
        // Düzeltme incelemesi 2026-10-15: önceki talep zamanı geri yüklenir (kuyruktaki gönderim sonuçlanınca gönderim anı)
        assertThat(reports.findById(r.getId()).orElseThrow().getSendClaimedAt()).isEqualTo(c2);
        Instant c4 = claim.plusSeconds(4);
        reports.claimSend(r.getId(), c4, claim.minusSeconds(596));
        reports.finishNotSent(r.getId(), c4, "FAILED", "FAILED: 554", c4);
        Instant c5 = claim.plusSeconds(5);
        reports.claimSend(r.getId(), c5, claim.minusSeconds(595));
        reports.releaseClaim(r.getId(), c5, c4);
        assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isEqualTo("FAILED");
    }

    @Autowired com.sitemonitor.repository.NotificationLogRepository logs;

    private Long logRow(String status) {
        com.sitemonitor.model.NotificationLog n = new com.sitemonitor.model.NotificationLog();
        n.setAlertEventId(0L);
        n.setSubject("POS Kanalı Teknik Hata Mesajları - Eylül 2026");
        n.setEmailStatus(status);
        n.setTrigger("TECH_ERROR_REPORT");
        return logs.save(n).getId();
    }

    private void setLogStatus(Long id, String status) {
        com.sitemonitor.model.NotificationLog n = logs.findById(id).orElseThrow();
        n.setEmailStatus(status);
        logs.save(n);
    }

    @Test
    @DisplayName("kuyruktaki gönderim: posta servisi SENT yazınca rapor 'gönderildi' olur (bekleyen alanlardan), FAILED → başarısız")
    void queuedSendIsReconciledFromTheLogRow() {
        TechErrorChannel ch = channel(1, "POS");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        Instant claim = Instant.parse("2026-10-13T08:59:00Z");
        reports.claimSend(r.getId(), claim, claim.minusSeconds(600));
        Long logId = logRow("QUEUED_RETRY: 421");
        assertThat(reports.finishQueued(r.getId(), claim, "QUEUED_RETRY: 421", claim, logId, 7L, "a@b.com", 2L)).isEqualTo(1);
        assertThat(service.reconcileQueued(List.of(ch.getId()))).isZero();                    // henüz sonuç yok
        TechErrorReport queued = reports.findById(r.getId()).orElseThrow();
        assertThat(queued.getSendState()).isEqualTo("QUEUED");
        assertThat(queued.getLastSentAt()).isNull();
        // Üye kuyruktaki (teslim edilmiş olabilecek) raporu silemez
        assertThatThrownBy(() -> service.deleteReport(r.getId(), true)).isInstanceOf(CodedConflictException.class);

        setLogStatus(logId, "SENT");
        assertThat(service.reconcileQueued(List.of(ch.getId()))).isEqualTo(1);
        TechErrorReport sent = reports.findById(r.getId()).orElseThrow();
        assertThat(sent.getSendState()).isEqualTo("SENT");
        assertThat(sent.getLastSentAt()).isEqualTo(claim);
        assertThat(sent.getLastSentTo()).isEqualTo("a@b.com");
        assertThat(sent.getSentRev()).isEqualTo(2L);
        assertThat(sent.getSendCount()).isEqualTo(1);
        assertThat(service.reconcileQueued(List.of(ch.getId()))).isZero();                    // bir kez
        // Sonuç geçmişe sistem olayı olarak bir kez düşer (ikinci sonuçlandırma denemesi yazmaz)
        var ev = eventsOf(r.getId());
        assertThat(ev.get(0).getKind()).isEqualTo("SEND_RESULT");
        assertThat(ev.get(0).getActorId()).isNull();
        assertThat(TechErrorHistoryService.detail(ev.get(0))).containsEntry("state", "SENT");
        assertThat(ev).filteredOn(e -> "SEND_RESULT".equals(e.getKind())).hasSize(1);

        TechErrorReport r2 = service.createReport(ch, 2026, 8, 7L);
        reports.claimSend(r2.getId(), claim, claim.minusSeconds(600));
        Long log2 = logRow("QUEUED_RETRY: 421");
        reports.finishQueued(r2.getId(), claim, "QUEUED_RETRY: 421", claim, log2, 7L, "a@b.com", 0L);
        setLogStatus(log2, "FAILED: 554 relay denied");
        assertThat(service.reconcileQueued(List.of(ch.getId()))).isEqualTo(1);
        TechErrorReport failed = reports.findById(r2.getId()).orElseThrow();
        assertThat(failed.getSendState()).isEqualTo("FAILED");
        assertThat(failed.getLastSentAt()).isNull();
        assertThat(failed.getSendCount()).isZero();
        assertThat(TechErrorHistoryService.detail(eventsOf(r2.getId()).get(0))).containsEntry("state", "FAILED");
    }

    @Test
    @DisplayName("şablonun son hâli: yeni bağla muaf kalan değer KAYIP sayılır ve taşınmaz (hayalet değer yok)")
    void refreshTemplateDropsValuesExemptUnderNewLink() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sla-above", false);
        fields.put("sla-rate", 95);
        TechErrorReport s = service.saveValues(r.getId(), r.getVersion(), Map.of("fields", fields), 7L);
        Map<String, Object> raw = TechErrorTemplate.toMap(TechErrorTemplate.fromStored(ch.getTemplateJson()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> secs = new ArrayList<>((List<Map<String, Object>>) raw.get("sections"));
        int at = metricsSummaryIndex(secs);
        Map<String, Object> summary = new LinkedHashMap<>(secs.get(at));
        @SuppressWarnings("unchecked") List<Map<String, Object>> fs = new ArrayList<>((List<Map<String, Object>>) summary.get("fields"));
        fs.replaceAll(f -> {
            if (!"sla-rate".equals(f.get("id"))) return f;
            Map<String, Object> m = new LinkedHashMap<>(f);
            m.put("skip_if_no", "sla-above");
            return m;
        });
        summary.put("fields", fs);
        secs.set(at, summary);
        raw.put("sections", secs);
        service.saveTemplate(ch.getId(), 1, TechErrorTemplate.normalize(raw), 7L);
        TechErrorChannel fresh = service.channel(ch.getId());
        assertThat(service.refreshPreview(reports.findById(r.getId()).orElseThrow(), fresh)).containsEntry("lost_values", 1);
        var res = service.refreshTemplate(r.getId(), s.getVersion(), 7L);
        assertThat(res.removedValues()).isEqualTo(1);
        assertThat(reports.findById(r.getId()).orElseThrow().getValuesJson()).contains("sla-above").doesNotContain("sla-rate");
    }

    @Test
    @DisplayName("kanal silme: raporlarından biri gönderiliyorsa 409, hiçbir şey silinmez")
    void deleteChannelRefusedWhileSending() {
        TechErrorChannel ch = channel(1, "POS");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        Instant fresh = Instant.parse("2026-10-13T08:58:00Z");
        reports.claimSend(r.getId(), fresh, fresh.minusSeconds(600));
        assertThatThrownBy(() -> service.deleteChannel(ch.getId())).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("REPORT_SENT_OR_SENDING");
        assertThat(channels.findById(ch.getId())).isPresent();
        assertThat(reports.findById(r.getId())).isPresent();
    }

    @Test
    @DisplayName("koşullu silme: üye gönderilmiş raporu silemez (409, görseller yerinde); süren gönderim silinmez; asılı talep silinir")
    void conditionalDelete() throws Exception {
        TechErrorChannel ch = channel(1, "POS");
        TechErrorReport sent = service.createReport(ch, 2026, 9, 7L);
        service.upload(sent.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "a.png", 7L);
        Instant claim = Instant.parse("2026-10-13T08:59:00Z");
        reports.claimSend(sent.getId(), claim, claim.minusSeconds(600));
        reports.finishSent(sent.getId(), claim, "SENT", claim, 7L, "a@b.com", 0L);
        assertThatThrownBy(() -> service.deleteReport(sent.getId(), true)).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("REPORT_SENT_OR_SENDING");
        assertThat(reports.findById(sent.getId())).isPresent();
        assertThat(images.findByReportIdOrderByIdAsc(sent.getId())).hasSize(1);       // işlem geri alındı
        service.deleteReport(sent.getId(), false);                                     // yönetici siler
        assertThat(reports.findById(sent.getId())).isEmpty();
        assertThat(images.findByReportIdOrderByIdAsc(sent.getId())).isEmpty();

        TechErrorReport sending = service.createReport(ch, 2026, 8, 7L);
        Instant fresh = Instant.parse("2026-10-13T08:58:00Z");                          // 2 dk önce — taze talep
        reports.claimSend(sending.getId(), fresh, fresh.minusSeconds(600));
        assertThatThrownBy(() -> service.deleteReport(sending.getId(), false)).isInstanceOf(CodedConflictException.class);
        assertThat(reports.findById(sending.getId())).isPresent();

        TechErrorReport hung = service.createReport(ch, 2026, 7, 7L);
        Instant old = Instant.parse("2026-10-13T08:30:00Z");                            // 30 dk önce — asılı
        reports.claimSend(hung.getId(), old, old.minusSeconds(600));
        service.deleteReport(hung.getId(), true);
        assertThat(reports.findById(hung.getId())).isEmpty();
    }

    @Test
    @DisplayName("şablonun son hâli: aynı kimlik ama TÜRÜ değişen alanın değeri taşınmaz (kaybolacak olarak sayılır)")
    void refreshTemplateDropsTypeChangedValue() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        TechErrorReport s = service.saveValues(r.getId(), r.getVersion(),
                Map.of("fields", Map.of("total-tx", 1250000, "error-count", 12)), 7L);
        Map<String, Object> raw = TechErrorTemplate.toMap(TechErrorTemplate.fromStored(ch.getTemplateJson()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> secs = new ArrayList<>((List<Map<String, Object>>) raw.get("sections"));
        int at = metricsSummaryIndex(secs);
        Map<String, Object> summary = new LinkedHashMap<>(secs.get(at));
        @SuppressWarnings("unchecked") List<Map<String, Object>> fields = new ArrayList<>((List<Map<String, Object>>) summary.get("fields"));
        fields.replaceAll(f -> {
            if (!"total-tx".equals(f.get("id"))) return f;
            Map<String, Object> m = new LinkedHashMap<>(f);
            m.put("type", "yesno");
            return m;
        });
        summary.put("fields", fields);
        secs.set(at, summary);
        raw.put("sections", secs);
        service.saveTemplate(ch.getId(), 1, TechErrorTemplate.normalize(raw), 7L);
        TechErrorChannel fresh = service.channel(ch.getId());
        assertThat(service.refreshPreview(reports.findById(r.getId()).orElseThrow(), fresh)).containsEntry("lost_values", 1);
        service.refreshTemplate(r.getId(), s.getVersion(), 7L);
        String json = reports.findById(r.getId()).orElseThrow().getValuesJson();
        assertThat(json).contains("error-count").doesNotContain("total-tx");
    }

    @Test
    @DisplayName("kanal silme kalıcı: raporları ve görselleri birlikte gider; başka kanalınkine dokunmaz")
    void deleteChannelCascades() throws Exception {
        TechErrorChannel a = channel(1, "A");
        TechErrorChannel b = channel(1, "B");
        TechErrorReport ra = service.createReport(a, 2026, 9, 7L);
        service.createReport(a, 2026, 8, 7L);
        TechErrorReport rb = service.createReport(b, 2026, 9, 7L);
        service.upload(ra.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "a.png", 7L);
        service.upload(rb.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "b.png", 7L);
        assertThat(service.deleteChannel(a.getId())).isEqualTo(2);
        assertThat(channels.findById(a.getId())).isEmpty();
        assertThat(reports.findById(ra.getId())).isEmpty();
        assertThat(images.findByReportIdOrderByIdAsc(ra.getId())).isEmpty();
        assertThat(images.findByReportIdOrderByIdAsc(rb.getId())).hasSize(1);
        assertThat(service.reportCounts(List.of(a.getId(), b.getId()))).containsOnlyKeys(b.getId());
    }

    @Test
    @DisplayName("PDF girdisi: kopyalanmış şablon + değerler + görseller; hazırlayan adı çözülemezse boş kalır")
    void pdfInput() throws Exception {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        service.upload(r.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "a.png", 7L);
        var in = service.pdfInput(reports.findById(r.getId()).orElseThrow(), ch, "Dijital");
        assertThat(in.title()).isEqualTo("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
        assertThat(in.images()).containsOnlyKeys("monthly-tx");
        assertThat(in.images().get("monthly-tx").width()).isEqualTo(400);
        var rendered = service.renderPdf(() -> in);
        byte[] pdf = rendered.pdf();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(rendered.meta().images()).isEmpty();                       // görsel baytları elde tutulmaz
        assertThat(rendered.meta().title()).isEqualTo(in.title());
    }

    @Test
    @DisplayName("otomatik trend (H2): aynı kanalın pencere içindeki raporları okunur; başka kanal ve gelecek ay karışmaz")
    void trendReadsChannelReports() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorChannel other = channel(1, "POS");
        TechErrorReport jul = service.createReport(ch, 2026, 7, 7L);
        service.saveValues(jul.getId(), jul.getVersion(), Map.of("fields", Map.of("error-rate", 0.3)), 7L);
        TechErrorReport aug = service.createReport(ch, 2026, 8, 7L);
        service.saveValues(aug.getId(), aug.getVersion(), Map.of("fields", Map.of("error-rate", 0.5)), 7L);
        TechErrorReport oct = service.createReport(ch, 2026, 9, 7L);           // raporun ayı
        TechErrorReport posAug = service.createReport(other, 2026, 8, 7L);
        service.saveValues(posAug.getId(), posAug.getVersion(), Map.of("fields", Map.of("error-rate", 9.9)), 7L);
        TechErrorReport r = reports.findById(oct.getId()).orElseThrow();
        Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        var v = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42)), t);
        var trend = service.trend(r, t, v);
        assertThat(trend.series("error-rate").subList(9, 12)).containsExactly(0.3, 0.5, 0.42);
        assertThat(trend.series("error-rate")).doesNotContain(9.9);
        assertThat(trend.points("error-rate")).isEqualTo(3);
        // Haziran raporu için Temmuz / Ağustos "gelecek" — pencereye girmez
        TechErrorReport jun = service.createReport(ch, 2026, 6, 7L);
        var junTrend = service.trend(reports.findById(jun.getId()).orElseThrow(), t, TechErrorValues.normalize(Map.of(), t));
        assertThat(junTrend.points("error-rate")).isZero();
    }

    // ── Değişiklik ve sürüm geçmişi (2026-10-14, Faz B) ──────────────────────────────────────────────

    private List<com.sitemonitor.model.TechErrorReportEvent> eventsOf(Long reportId) {
        return history.reportHistory(reportId, null, TechErrorHistoryService.MAX_PAGE).events();
    }

    @Test
    @DisplayName("geçmiş: açılış, değer kaydı (alan bazında fark), görsel ekle / değiştir / sil, şablon uygulama sırayla yazılır")
    void reportEventsAreRecorded() throws Exception {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        TechErrorReport s1 = service.saveValues(r.getId(), r.getVersion(), Map.of("fields", Map.of("error-rate", 0.42)), 7L);
        // aynı değerle kayıt: sürüm artmaz, OLAY YOK
        service.saveValues(s1.getId(), s1.getVersion(), Map.of("fields", Map.of("error-rate", 0.42)), 7L);
        TechErrorReport s2 = service.saveValues(s1.getId(), s1.getVersion(), Map.of("fields", Map.of("error-rate", 0.5)), 7L);
        service.upload(r.getId(), "monthly-tx", TechErrorImageValidatorTest.png(400, 200), "a.png", 7L);
        TechErrorReportImage b = service.upload(r.getId(), "monthly-tx", TechErrorImageValidatorTest.png(500, 250), "b.png", 7L);
        service.deleteImage(r.getId(), b.getId(), 7L);
        service.refreshTemplate(r.getId(), s2.getVersion(), 7L);

        var list = eventsOf(r.getId());
        assertThat(list).extracting(com.sitemonitor.model.TechErrorReportEvent::getKind).containsExactly(
                "TEMPLATE_REFRESH", "IMAGE_DELETE", "IMAGE_REPLACE", "IMAGE_ADD", "VALUES", "VALUES", "CREATE");
        var lastValues = TechErrorHistoryService.detail(list.get(4));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) lastValues.get("changes");
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.get("label")).isEqualTo("Teknik hata oranı");
            assertThat(c.get("before")).isEqualTo("%0,42");
            assertThat(c.get("after")).isEqualTo("%0,5");
            assertThat(c.get("op")).isEqualTo("change");
        });
        assertThat(list.get(4).getReportVersion()).isEqualTo(s2.getVersion());
        assertThat(TechErrorHistoryService.detail(list.get(2))).containsEntry("slot", "Ay Bazlı Toplam İşlem Adetleri (Son 12 Ay)")
                .containsEntry("file_name", "b.png");
        assertThat(TechErrorHistoryService.detail(list.get(6))).containsEntry("template_version", 1);
        assertThat(list).allSatisfy(e -> assertThat(e.getActorId()).isEqualTo(7L));
    }

    @Test
    @DisplayName("geçmiş sayfalı (anahtar kümesi): limit + has_more, before ile sonraki sayfa; recorded_since ilk olay")
    void historyPaging() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        TechErrorReport cur = r;
        for (int i = 1; i <= 5; i++) cur = service.saveValues(r.getId(), cur.getVersion(), Map.of("fields", Map.of("error-rate", i)), 7L);
        var p1 = history.reportHistory(r.getId(), null, 4);
        assertThat(p1.events()).hasSize(4);
        assertThat(p1.hasMore()).isTrue();
        var p2 = history.reportHistory(r.getId(), p1.events().get(3).getId(), 4);
        assertThat(p2.events()).hasSize(2);
        assertThat(p2.hasMore()).isFalse();
        assertThat(p2.events().get(1).getKind()).isEqualTo("CREATE");
        assertThat(p1.recordedSince()).isEqualTo(p2.events().get(1).getEventAt());
        assertThat(history.reportHistory(r.getId(), null, 10_000).events()).hasSize(6);      // üst sınır MAX_PAGE
        assertThat(p2.capped()).isFalse();                                                    // sınıra ulaşılmadı
    }

    @Test
    @DisplayName("budama: rapor başına sınır + pay aşılınca en yeni 500 olay kalır")
    void historyIsPruned() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        Instant at = Instant.parse("2026-10-13T09:00:00Z");
        int total = TechErrorHistoryService.MAX_EVENTS_PER_REPORT + TechErrorHistoryService.PRUNE_SLACK + 1;
        for (int i = 1; i < total; i++) history.record(r, "SEND", 7L, null, Map.of("state", "SENT"), at);
        assertThat(events.countByReportId(r.getId())).isEqualTo(TechErrorHistoryService.MAX_EVENTS_PER_REPORT);
        assertThat(eventsOf(r.getId()).get(0).getKind()).isEqualTo("SEND");
        // Son sayfa "sınıra ulaşıldı" der (inceleme 2026-10-14): açılış olayı budandı — arayüz "geçmiş bu özellikle başladı" demez
        Long oldest = events.idsNewestFirst(r.getId(),
                org.springframework.data.domain.PageRequest.of(TechErrorHistoryService.MAX_EVENTS_PER_REPORT - 1, 1)).get(0);
        var last = history.reportHistory(r.getId(), oldest + 1, 10);
        assertThat(last.events()).extracting(e -> e.getId()).containsExactly(oldest);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.capped()).isTrue();
        assertThat(last.events()).noneMatch(e -> "CREATE".equals(e.getKind()));
    }

    @Test
    @DisplayName("tek olayın ayrıntısı sınırlı: sığmayan değişiklikler yalnız sayılır (more)")
    void valuesDetailIsBounded() {
        // 10 sütun × 40 satırlık tablo, uzun etiketler: 400 hücre → 200 değişiklik ≈ 190 KB olurdu
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr")));
        List<Object> secs = new ArrayList<>((List<?>) raw.get("sections"));
        List<Object> cols = new ArrayList<>();
        for (int i = 0; i < 10; i++) cols.add(Map.of("id", "c" + i, "label", ("Uzun sütun adı " + i + " ").repeat(6), "type", "number"));
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(Map.of("id", "r" + i, "label", ("Uzun satır adı " + i + " ").repeat(10)));
        secs.add(Map.of("id", "tb", "kind", "table", "title", "Uzun tablo adı ".repeat(12), "columns", cols, "rows", rows));
        raw.put("sections", secs);
        Template t = TechErrorTemplate.normalize(raw);
        TechErrorChannel ch = channel(1, "Mobil");
        service.saveTemplate(ch.getId(), 1, t, 7L);
        TechErrorReport r = service.createReport(channels.findById(ch.getId()).orElseThrow(), 2026, 9, 7L);   // güncel şablon
        Map<String, Object> cells = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < 10; j++) row.put("c" + j, 1000 + i * 10 + j);
            cells.put("r" + i, row);
        }
        service.saveValues(r.getId(), r.getVersion(), Map.of("tables", Map.of("tb", cells)), 7L);
        var ev = eventsOf(r.getId()).get(0);
        assertThat(ev.getKind()).isEqualTo("VALUES");
        assertThat(ev.getDetailJson().length()).isLessThanOrEqualTo(TechErrorHistoryService.MAX_DETAIL_CHARS + 200);
        var d = TechErrorHistoryService.detail(ev);
        int shown = ((List<?>) d.get("changes")).size();
        assertThat(shown).isBetween(1, TechErrorChangeDiff.MAX_CHANGES - 1);
        assertThat(shown + ((Number) d.get("more")).intValue()).isEqualTo(400);
    }

    @Test
    @DisplayName("kuyruk sonucu ve geçmiş olayı TEK işlemde: olay yazılamazsa sonuç da geri alınır, sonraki okumada yeniden denenir")
    void queuedResultAndEventAreAtomic() {
        TechErrorChannel ch = channel(1, "POS");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        Instant claim = Instant.parse("2026-10-13T08:59:00Z");
        reports.claimSend(r.getId(), claim, claim.minusSeconds(600));
        Long logId = logRow("QUEUED_RETRY: 421");
        reports.finishQueued(r.getId(), claim, "QUEUED_RETRY: 421", claim, logId, 7L, "a@b.com", 2L);
        setLogStatus(logId, "SENT");
        org.mockito.Mockito.doThrow(new IllegalStateException("olay yazılamadı")).when(history)
                .record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("SEND_RESULT"),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(service.reconcileQueued(List.of(ch.getId()))).isZero();
        assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isEqualTo("QUEUED");    // geri alındı
        org.mockito.Mockito.reset(history);
        assertThat(service.reconcileQueued(List.of(ch.getId()))).isEqualTo(1);
        assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isEqualTo("SENT");
        assertThat(eventsOf(r.getId())).filteredOn(e -> "SEND_RESULT".equals(e.getKind())).hasSize(1);
    }

    @Test
    @DisplayName("rapor silinince olayları aynı işlemde gider; 409 ile geri alınan silmede olaylar yerinde kalır")
    void historyDeletedWithReport() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport keep = service.createReport(ch, 2026, 8, 7L);
        TechErrorReport gone = service.createReport(ch, 2026, 9, 7L);
        service.deleteReport(gone.getId(), true);
        assertThat(events.countByReportId(gone.getId())).isZero();
        assertThat(events.countByReportId(keep.getId())).isEqualTo(1);
        // gönderilmiş rapor: üye silemez → 409, olaylar geri alınır (yerinde)
        Instant claim = Instant.parse("2026-10-13T08:59:00Z");
        reports.claimSend(keep.getId(), claim, claim.minusSeconds(600));
        reports.finishSent(keep.getId(), claim, "SENT", claim, 7L, "x@example.com", 0L);
        assertThatThrownBy(() -> service.deleteReport(keep.getId(), true)).isInstanceOf(CodedConflictException.class);
        assertThat(events.countByReportId(keep.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("gönderim olayı kendi işleminde; yazılamasa da istisna fırlatmaz; alıcılar en çok 20")
    void sendEvent() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        List<String> to = new ArrayList<>();
        for (int i = 0; i < 25; i++) to.add("k" + i + "@example.com");
        history.recordSend(r, "SEND", 7L, "SENT", to, null, Instant.parse("2026-10-13T09:00:00Z"));
        var d = TechErrorHistoryService.detail(eventsOf(r.getId()).get(0));
        assertThat(d).containsEntry("state", "SENT").containsEntry("recipient_count", 25);
        assertThat((List<?>) d.get("recipients")).hasSize(20);
        TechErrorReport ghost = new TechErrorReport();       // kimliksiz rapor → yazılamaz; sessizce günlüğe
        history.recordSend(ghost, "SEND", 7L, "SENT", List.of(), null, Instant.now());
    }

    @Test
    @DisplayName("şablon sürümleri: açılışta v1, kayıtta v2; eski sürümle kayıt 409 ve geçmişe hiçbir şey yazmaz")
    void templateVersions() {
        TechErrorChannel ch = channel(1, "Mobil");
        assertThat(history.templateVersions(ch.getId())).extracting(v -> v.getVersion()).containsExactly(1);
        Template t = TechErrorTemplate.defaults("en");
        assertThat(service.saveTemplate(ch.getId(), 1, t, 8L)).isEqualTo(2);
        var list = history.templateVersions(ch.getId());
        assertThat(list).extracting(v -> v.getVersion()).containsExactly(2, 1);
        assertThat(list.get(0).getFieldCount()).isEqualTo(t.allFields().size());
        assertThat(history.templateVersion(ch.getId(), 2).orElseThrow().getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(t));
        assertThatThrownBy(() -> service.saveTemplate(ch.getId(), 1, TechErrorTemplate.defaults("tr"), 8L))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(history.templateVersions(ch.getId())).hasSize(2);
    }

    @Test
    @DisplayName("bu özellikten önceki kanal: ilk kayıtta o anki sürüm taban olarak yazılır (kim / ne zaman kanaldan)")
    void templateBaselineForLegacyChannel() {
        TechErrorChannel ch = channel(1, "Mobil");
        service.saveTemplate(ch.getId(), 1, TechErrorTemplate.defaults("en"), 8L);
        service.saveTemplate(ch.getId(), 2, TechErrorTemplate.defaults("tr"), 8L);
        versions.deleteAll();                                   // geçmişi olmayan (eski) kanal benzetimi: v3 yazılı
        String v3 = channels.findById(ch.getId()).orElseThrow().getTemplateJson();
        service.saveTemplate(ch.getId(), 3, TechErrorTemplate.defaults("en"), 9L);
        var list = history.templateVersions(ch.getId());
        assertThat(list).extracting(v -> v.getVersion()).containsExactly(4, 3);
        assertThat(history.templateVersion(ch.getId(), 3).orElseThrow().getTemplateJson()).isEqualTo(v3);
    }

    @Test
    @DisplayName("şablon sürümü budaması: kanal başına en yeni 100 sürüm kalır; kanal silinince sürümler ve olaylar gider")
    void templateVersionsPrunedAndDeletedWithChannel() {
        TechErrorChannel ch = channel(1, "Mobil");
        int v = 1;
        for (int i = 0; i < TechErrorHistoryService.MAX_TEMPLATE_VERSIONS + 3; i++) {
            v = service.saveTemplate(ch.getId(), v, i % 2 == 0 ? TechErrorTemplate.defaults("en") : TechErrorTemplate.defaults("tr"), 8L);
        }
        assertThat(versions.countByChannelId(ch.getId())).isEqualTo(TechErrorHistoryService.MAX_TEMPLATE_VERSIONS);
        assertThat(history.templateVersions(ch.getId()).get(0).getVersion()).isEqualTo(v);
        TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
        service.deleteChannel(ch.getId());
        assertThat(versions.countByChannelId(ch.getId())).isZero();
        assertThat(events.countByReportId(r.getId())).isZero();
    }

    /** Gösterge özeti bölümünün sırası (2026-10-14'ten beri 0. sırada yönetici özeti var). */
    private static int metricsSummaryIndex(List<Map<String, Object>> secs) {
        for (int i = 0; i < secs.size(); i++) if ("summary".equals(secs.get(i).get("id"))) return i;
        throw new AssertionError("gösterge özeti bölümü yok");
    }
}
