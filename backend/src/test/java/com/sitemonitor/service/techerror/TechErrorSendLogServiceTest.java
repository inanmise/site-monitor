package com.sitemonitor.service.techerror;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.repository.TechErrorTemplateVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kanal gönderim günlüğü (R18 / E22, 2026-10-18) — H2 üzerinde gerçek JPQL: yalnız KANALA BAĞLI teknik hata satırları
 * (başka kanal, bağsız eski satır ve teknik hata dışı tetikleyici görünmez), en yeni önce; tür ve durum sınıfı süzgeci
 * ({@link TechErrorReportSender#classify} kuralı — boş durum "failed"); sayfa / boyut sınırı; rapor dönemi (silinmiş rapor →
 * dönem yok); sığdırılmış alıcı listesinin sayısı ("… +N"); eski onay rolü PO → APPROVER; zaman ISO + Z.
 */
@DataJpaTest
@Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorSendLogService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorSendLogServiceTest {

    @Autowired TechErrorSendLogService sendLog;
    @Autowired TechErrorReportService service;
    @Autowired NotificationLogRepository logs;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorReportEventRepository events;
    @Autowired TechErrorTemplateVersionRepository versions;

    private TechErrorChannel ch;
    private TechErrorChannel other;
    private TechErrorReport sep;

    @BeforeEach
    void setUp() {
        logs.deleteAll();
        events.deleteAll();
        versions.deleteAll();
        reports.deleteAll();
        channels.deleteAll();
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC));
        ch = service.createChannel(1L, "Mobil", null, TechErrorTemplate.defaults("tr"), 7L);
        other = service.createChannel(1L, "Web", null, TechErrorTemplate.defaults("tr"), 7L);
        sep = service.createReport(ch, 2026, 9, 7L);
    }

    private NotificationLog row(Long channelId, Long reportId, String trigger, String role, String status, String to, String cc, String at) {
        NotificationLog n = new NotificationLog();
        n.setAlertEventId(0L);
        n.setSentAt(at);
        n.setRecipientName("Dijital");
        n.setRecipientEmail(to);
        n.setCc(cc);
        n.setRecipientRole(role);
        n.setSubject("Konu");
        n.setEmailStatus(status);
        n.setWebhookStatus("SKIPPED");
        n.setTrigger(trigger);
        TechErrorSendLogService.link(n, channelId, reportId);
        return logs.save(n);
    }

    @Test
    @DisplayName("yalnız bu kanala bağlı teknik hata satırları, en yeni önce; tür süzgeci; hatırlatma tetikleyicileri de; dönem, rol, sayılar, ISO zaman")
    void scopeOrderKindsAndRowShape() {
        NotificationLog sent = row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "TEAM", "SENT", "a@example.com, b@example.com",
                "ek@example.com", "2026-10-01T08:00:00");
        row(ch.getId(), sep.getId(), TechErrorReportSender.TEST_TRIGGER, "SELF", "SENT", "ben@example.com", null, "2026-10-01T09:00:00");
        NotificationLog appr = row(ch.getId(), sep.getId(), TechErrorApprovalService.TRIGGER, "PO", "QUEUED_RETRY: 421", "po@example.com", null, "2026-10-02T08:00:00");
        row(ch.getId(), sep.getId(), TechErrorSendLogService.APPROVAL_REMINDER_TRIGGER, "APPROVER", "SKIPPED_DISABLED", "po@example.com", null, "2026-10-03T08:00:00");
        NotificationLog rem = row(ch.getId(), 999L, TechErrorSendLogService.REMINDER_TRIGGER, "TEAM", null, "a@example.com", null, "2026-10-04T08:00:00");
        // Görünmeyenler: başka kanal, bağsız eski satır, kanal bağlı ama teknik hata dışı tetikleyici
        row(other.getId(), null, TechErrorReportSender.TRIGGER, "TEAM", "SENT", "x@example.com", null, "2026-10-05T08:00:00");
        row(null, null, TechErrorReportSender.TRIGGER, "TEAM", "SENT", "x@example.com", null, "2026-10-05T09:00:00");
        row(ch.getId(), null, "INITIAL", "TEAM", "SENT", "x@example.com", null, "2026-10-05T10:00:00");

        TechErrorSendLogService.Page all = sendLog.page(ch.getId(), null, null, null, null);
        assertThat(all.total()).isEqualTo(5);
        assertThat(all.size()).isEqualTo(TechErrorSendLogService.DEFAULT_PAGE_SIZE);
        assertThat(all.rows()).extracting(TechErrorSendLogService.Row::id).first().isEqualTo(rem.getId());      // en yeni önce
        assertThat(all.rows()).extracting(TechErrorSendLogService.Row::kind)
                .containsExactly("reminder", "reminder", "approval", "test", "report");
        TechErrorSendLogService.Row last = all.rows().get(4);
        assertThat(last.id()).isEqualTo(sent.getId());
        assertThat(last.at()).isEqualTo("2026-10-01T08:00:00Z");
        assertThat(last.role()).isEqualTo("TEAM");
        assertThat(last.status()).isEqualTo("sent");
        assertThat(last.year()).isEqualTo(2026);
        assertThat(last.month()).isEqualTo(9);
        assertThat(last.to()).containsExactly("a@example.com", "b@example.com");
        assertThat(last.cc()).containsExactly("ek@example.com");
        assertThat(last.toCount()).isEqualTo(2);
        assertThat(last.ccCount()).isEqualTo(1);
        TechErrorSendLogService.Row apprRow = all.rows().stream().filter(r -> r.id().equals(appr.getId())).findFirst().orElseThrow();
        assertThat(apprRow.role()).isEqualTo("APPROVER");                // eski rol adı PO
        assertThat(apprRow.status()).isEqualTo("queued");
        TechErrorSendLogService.Row remRow = all.rows().get(0);
        assertThat(remRow.status()).isEqualTo("failed");                  // boş durum = gönderilmedi
        assertThat(remRow.year()).isNull();                               // rapor yok (silinmiş) → dönem bilinmez
        assertThat(remRow.cc()).isEmpty();

        assertThat(sendLog.page(ch.getId(), "report", null, 0, 25).rows()).extracting(TechErrorSendLogService.Row::id).containsExactly(sent.getId());
        assertThat(sendLog.page(ch.getId(), "test", null, 0, 25).total()).isEqualTo(1);
        assertThat(sendLog.page(ch.getId(), "approval", null, 0, 25).total()).isEqualTo(1);
        assertThat(sendLog.page(ch.getId(), "reminder", null, 0, 25).total()).isEqualTo(2);
        assertThat(sendLog.page(ch.getId(), "bilinmeyen", null, 0, 25).kind()).isEqualTo("all");
        assertThat(sendLog.page(other.getId(), null, null, 0, 25).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("durum sınıfı süzgeci (sent / queued / skipped / failed — classify ile aynı), sayfalama ve sınırlar; boş kanal sorgusuz boş")
    void statusFilterAndPaging() {
        row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "TEAM", "SENT", "a@example.com", null, "2026-10-01T08:00:00");
        row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "TEAM", "QUEUED_RETRY: 421", "a@example.com", null, "2026-10-01T09:00:00");
        row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "TEAM", "SKIPPED: PDF sırası dolu, gönderilmedi", "a@example.com", null, "2026-10-01T10:00:00");
        row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "TEAM", "FAILED: 550 reddedildi", "a@example.com", null, "2026-10-01T11:00:00");
        row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "EXECUTIVE", null, "a@example.com", null, "2026-10-01T12:00:00");
        assertThat(sendLog.page(ch.getId(), null, "sent", 0, 25).total()).isEqualTo(1);
        assertThat(sendLog.page(ch.getId(), null, "queued", 0, 25).total()).isEqualTo(1);
        assertThat(sendLog.page(ch.getId(), null, "skipped", 0, 25).total()).isEqualTo(1);
        TechErrorSendLogService.Page failed = sendLog.page(ch.getId(), null, "failed", 0, 25);
        assertThat(failed.total()).isEqualTo(2);
        assertThat(failed.rows()).allSatisfy(r -> assertThat(r.status()).isEqualTo("failed"));
        assertThat(sendLog.page(ch.getId(), null, "garip", 0, 25).status()).isEqualTo("all");

        TechErrorSendLogService.Page p0 = sendLog.page(ch.getId(), null, null, 0, 2);
        TechErrorSendLogService.Page p2 = sendLog.page(ch.getId(), null, null, 2, 2);
        assertThat(p0.total()).isEqualTo(5);
        assertThat(p0.rows()).hasSize(2);
        assertThat(p2.rows()).hasSize(1);
        assertThat(p2.page()).isEqualTo(2);
        assertThat(sendLog.page(ch.getId(), null, null, -3, 1000).page()).isZero();
        assertThat(sendLog.page(ch.getId(), null, null, 0, 1000).size()).isEqualTo(TechErrorSendLogService.MAX_PAGE_SIZE);
        assertThat(sendLog.page(ch.getId(), null, null, 0, 0).size()).isEqualTo(TechErrorSendLogService.DEFAULT_PAGE_SIZE);
        assertThat(sendLog.page(other.getId(), null, null, 0, 25).rows()).isEmpty();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: sınırsız sayfa ofset taşmasıyla 500 vermez — son sayfaya sıkışır, yanıttaki sayfa gerçek sayfa; boş günlük sayfa 0")
    void pageIsClampedToLastPage() {
        for (int i = 0; i < 5; i++) {
            row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "TEAM", "SENT", "a@example.com", null, "2026-10-01T0" + i + ":00:00");
        }
        TechErrorSendLogService.Page huge = sendLog.page(ch.getId(), null, null, 99_999_999, 2);
        assertThat(huge.page()).isEqualTo(2);                      // 5 satır / 2 = sayfa 0, 1, 2
        assertThat(huge.rows()).hasSize(1);
        assertThat(huge.total()).isEqualTo(5);
        assertThat(sendLog.page(ch.getId(), null, null, Integer.MAX_VALUE, 100).page()).isZero();
        assertThat(sendLog.page(other.getId(), null, null, 99_999_999, 25).page()).isZero();   // boş günlük
        assertThat(TechErrorSendLogService.clampPage(null, 10, 3)).isZero();
        assertThat(TechErrorSendLogService.clampPage(-1, 10, 3)).isZero();
        assertThat(TechErrorSendLogService.clampPage(3, 10, 3)).isEqualTo(3);
        assertThat(TechErrorSendLogService.clampPage(4, 10, 3)).isEqualTo(3);
        assertThat(TechErrorSendLogService.clampPage(Integer.MAX_VALUE, Long.MAX_VALUE, 1)).isEqualTo(Integer.MAX_VALUE);
        assertThat(TechErrorSendLogService.clampPage(7, 0, 25)).isZero();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: ham durum metni (posta sunucusunun iletisi, sunucu adı / port) satırda yok — sabit sebep kodu; sığdırılmış alıcı listesi tam adresler + sığmayan sayısı")
    void rowCarriesReasonCodeAndSplitsFittedLists() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            many.append(i == 0 ? "" : ", ").append("dijital.kanallar.uygulama.gelistirme.").append(i).append("@ornek-kurum-bilgi-teknolojileri.example.com");
        }
        NotificationLog smtp = row(ch.getId(), sep.getId(), TechErrorReportSender.TRIGGER, "TEAM",
                "FAILED: 550 5.1.1 <a@example.com> rejected by mail-gw01.kurum.local:25", many.toString(), "ek@example.com", "2026-10-01T08:00:00");
        // Varlık kolona sığdırdı (NotificationLog.fitColumns): "… +N"
        assertThat(logs.findById(smtp.getId()).orElseThrow().getRecipientEmail()).contains(" … +");
        TechErrorSendLogService.Row r = sendLog.page(ch.getId(), null, null, 0, 25).rows().get(0);
        assertThat(r.reason()).isEqualTo("SMTP_FAILED");
        assertThat(r.toString()).doesNotContain("mail-gw01").doesNotContain("550");
        assertThat(r.to()).allSatisfy(a -> assertThat(a).doesNotContain("…").endsWith("@ornek-kurum-bilgi-teknolojileri.example.com"));
        assertThat(r.to()).hasSize(3);                             // 3 × 82 + 2 × 2 + " … +6" = 255
        assertThat(r.toMore()).isEqualTo(6);
        assertThat(r.toCount()).isEqualTo(9);
        assertThat(r.cc()).containsExactly("ek@example.com");
        assertThat(r.ccMore()).isZero();
    }

    @Test
    @DisplayName("sebep kodu (reasonOf): her bilinen durum biçimi bir koda; SENT kodsuz; bilinmeyen FAILED = SMTP_FAILED, istisna sınıfı = INTERNAL_ERROR; her kod REASON_CODES'ta")
    void reasonCodes() {
        java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
        table.put("QUEUED_RETRY: 421 4.7.0 Try again later", "QUEUED");
        table.put("QUEUED_RETRY", "QUEUED");
        table.put("SKIPPED_DISABLED", "MAIL_OFF");
        table.put("SKIPPED: pasif kullanıcı", "INACTIVE_RECIPIENTS");
        table.put("SKIPPED: PDF sırası dolu, gönderilmedi", "PDF_BUSY");
        table.put("SKIPPED: PDF sırası dolu, onay postası gönderilmedi", "PDF_BUSY");
        table.put("FAILED: PDF e-posta sınırını aşıyor (16.2 MB > 15 MB)", "PDF_TOO_LARGE");
        table.put("SKIPPED: onay eskidi, içerik onaydan sonra değişti — yeniden onay gerekir", "APPROVAL_STALE");
        table.put("SKIPPED_NO_RECIPIENT", "NO_RECIPIENT");
        table.put("SKIPPED: alıcı yok", "NO_RECIPIENT");
        table.put("FAILED: 550 5.1.1 <a@example.com> rejected by mail-gw01.kurum.local:25", "SMTP_FAILED");
        table.put("FAILED: Mail server connection failed; nested exception is java.net.ConnectException", "SMTP_FAILED");
        table.put("FAILED: retry kuyruğu dolu", "SMTP_FAILED");
        table.put("FAILED: null", "SMTP_FAILED");
        table.put("FAILED: IllegalStateException", "INTERNAL_ERROR");
        table.put("FAILED: OutOfMemoryError", "INTERNAL_ERROR");
        table.put("FAILED: gönderim tamamlanamadı", "INTERNAL_ERROR");
        table.put("FAILED: sonuç yok", "INTERNAL_ERROR");
        table.put("SKIPPED: başka bir sebep", "SKIPPED_OTHER");
        table.put("BAMBAŞKA", "SMTP_FAILED");
        for (var e : table.entrySet()) {
            assertThat(TechErrorSendLogService.reasonOf(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(TechErrorSendLogService.REASON_CODES).as(e.getKey()).contains(e.getValue());
        }
        assertThat(TechErrorSendLogService.reasonOf("SENT")).isNull();
        assertThat(TechErrorSendLogService.reasonOf(null)).isEqualTo("UNKNOWN");
        assertThat(TechErrorSendLogService.reasonOf("  ")).isEqualTo("UNKNOWN");
        assertThat(TechErrorSendLogService.REASON_CODES).doesNotHaveDuplicates().contains("UNKNOWN");
        // Uzun bir istisna-benzeri metin regex'e girmeden SMTP_FAILED (uzunluk regex'ten ÖNCE)
        assertThat(TechErrorSendLogService.reasonOf("FAILED: " + "A".repeat(5000) + "Exception")).isEqualTo("SMTP_FAILED");
    }

    @Test
    @DisplayName("sığdırma eki ayrılır (NotificationLog.fitList ile aynı biçim): tam adresler + sığmayan sayısı; ilk adres bile sığmadıysa yarım adres gösterilmez")
    void splitFittedMirrorsFitList() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) many.append(i == 0 ? "" : ", ").append("kisi").append(i).append("@example.com");
        String fitted = NotificationLog.fitList(many.toString(), NotificationLog.VARCHAR_MAX);
        TechErrorSendLogService.Fitted f = TechErrorSendLogService.splitFitted(fitted);
        assertThat(f.items()).allSatisfy(a -> assertThat(a).matches("kisi\\d+@example\\.com"));
        assertThat(f.items().size() + f.more()).isEqualTo(40);
        assertThat(f.count()).isEqualTo(40);
        assertThat(f.more()).isPositive();
        assertThat(String.join(", ", f.items()) + " … +" + f.more()).isEqualTo(fitted);   // biçim birebir
        TechErrorSendLogService.Fitted plain = TechErrorSendLogService.splitFitted("a@example.com, b@example.com");
        assertThat(plain.items()).containsExactly("a@example.com", "b@example.com");
        assertThat(plain.more()).isZero();
        // Düz kesilmiş (fit): son öğe yarım → gösterilmez, en az 1 sığmayan
        TechErrorSendLogService.Fitted cut = TechErrorSendLogService.splitFitted("tam@example.com, yar…");
        assertThat(cut.items()).containsExactly("tam@example.com");
        assertThat(cut.more()).isEqualTo(1);
        TechErrorSendLogService.Fitted cutOnly = TechErrorSendLogService.splitFitted("cok-uzun-adres…");
        assertThat(cutOnly.items()).isEmpty();
        assertThat(cutOnly.count()).isEqualTo(1);
        assertThat(TechErrorSendLogService.splitFitted(null).count()).isZero();
        // Ek benzeri ama rakam değil: ayrılmaz (adres değil, öğe olarak kalır)
        assertThat(TechErrorSendLogService.splitFitted("a@example.com … +x").more()).isZero();
    }

    @Test
    @DisplayName("yardımcılar: sığdırılmış liste sayısı (\"… +N\"), bölgesiz zaman → ISO Z (okunamazsa ham), roller, durum sınıfı")
    void helpers() {
        assertThat(TechErrorSendLogService.listCount("a@example.com, b@example.com … +3")).isEqualTo(5);
        assertThat(TechErrorSendLogService.listCount("a@example.com")).isEqualTo(1);
        assertThat(TechErrorSendLogService.listCount(null)).isZero();
        assertThat(TechErrorSendLogService.listCount(" , ")).isZero();
        assertThat(TechErrorSendLogService.listCount("uzun-adres…")).isEqualTo(1);
        // Gerçek sığdırma ile aynı biçim (NotificationLog.fitList)
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) many.append(i == 0 ? "" : ", ").append("kisi").append(i).append("@example.com");
        assertThat(TechErrorSendLogService.listCount(NotificationLog.fitList(many.toString(), NotificationLog.VARCHAR_MAX))).isEqualTo(40);
        assertThat(TechErrorSendLogService.isoUtc("2026-10-01T08:00:00")).isEqualTo("2026-10-01T08:00:00Z");
        assertThat(TechErrorSendLogService.isoUtc("bozuk")).isEqualTo("bozuk");
        assertThat(TechErrorSendLogService.isoUtc(null)).isNull();
        assertThat(TechErrorSendLogService.roleOf("po")).isEqualTo("APPROVER");
        assertThat(TechErrorSendLogService.roleOf("APPROVER_INFO")).isEqualTo("APPROVER_INFO");
        assertThat(TechErrorSendLogService.roleOf("NOC")).isEqualTo("OTHER");
        assertThat(TechErrorSendLogService.roleOf(null)).isEqualTo("OTHER");
        assertThat(TechErrorSendLogService.statusClass("SENT")).isEqualTo("sent");
        assertThat(TechErrorSendLogService.statusClass("QUEUED_RETRY")).isEqualTo("queued");
        assertThat(TechErrorSendLogService.statusClass("SKIPPED_DISABLED")).isEqualTo("skipped");
        assertThat(TechErrorSendLogService.statusClass("FAILED: x")).isEqualTo("failed");
        assertThat(TechErrorSendLogService.statusClass(" ")).isEqualTo("failed");
        assertThat(TechErrorSendLogService.ALL_TRIGGERS).containsExactly(TechErrorReportSender.TRIGGER, TechErrorReportSender.TEST_TRIGGER,
                TechErrorApprovalService.TRIGGER, TechErrorSendLogService.APPROVAL_REMINDER_TRIGGER, TechErrorSendLogService.REMINDER_TRIGGER);
        List<String> none = TechErrorSendLogService.splitList(null);
        assertThat(none).isEmpty();
    }
}
