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
@Import(TechErrorReportService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorReportServiceTest {

    @Autowired TechErrorReportService service;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorReportImageRepository images;

    @BeforeEach
    void clean() {
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
        assertThatThrownBy(() -> service.deleteImage(other.getId(), b.getId())).isInstanceOf(java.util.NoSuchElementException.class);
        service.deleteImage(r.getId(), b.getId());
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
        Map<String, Object> charts = new LinkedHashMap<>(secs.stream().filter(x -> "charts".equals(x.get("id"))).findFirst().orElseThrow());
        @SuppressWarnings("unchecked") List<Map<String, Object>> slots = new ArrayList<>((List<Map<String, Object>>) charts.get("slots"));
        slots.removeIf(x -> "quota-monthly".equals(x.get("id")));
        charts.put("slots", slots);
        secs.replaceAll(x -> "charts".equals(x.get("id")) ? charts : x);
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
        assertThat(reports.releaseClaim(r.getId(), claim.plusSeconds(1))).isZero();
        assertThat(reports.releaseClaim(r.getId(), claim)).isEqualTo(1);
        assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isNull();      // hiç denenmemiş
        Instant c2 = claim.plusSeconds(2);
        assertThat(reports.claimSend(r.getId(), c2, claim.minusSeconds(598))).isEqualTo(1);   // yeniden gönderilebilir
        reports.finishNotSent(r.getId(), c2, "QUEUED", "QUEUED_RETRY: 421", c2);
        Instant c3 = claim.plusSeconds(3);
        reports.claimSend(r.getId(), c3, claim.minusSeconds(597));
        reports.releaseClaim(r.getId(), c3);
        assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isEqualTo("QUEUED");
        Instant c4 = claim.plusSeconds(4);
        reports.claimSend(r.getId(), c4, claim.minusSeconds(596));
        reports.finishNotSent(r.getId(), c4, "FAILED", "FAILED: 554", c4);
        Instant c5 = claim.plusSeconds(5);
        reports.claimSend(r.getId(), c5, claim.minusSeconds(595));
        reports.releaseClaim(r.getId(), c5);
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

    /** Gösterge özeti bölümünün sırası (2026-10-14'ten beri 0. sırada yönetici özeti var). */
    private static int metricsSummaryIndex(List<Map<String, Object>> secs) {
        for (int i = 0; i < secs.size(); i++) if ("summary".equals(secs.get(i).get("id"))) return i;
        throw new AssertionError("gösterge özeti bölümü yok");
    }
}
