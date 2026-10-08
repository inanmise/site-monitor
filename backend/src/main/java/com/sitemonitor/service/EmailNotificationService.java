package com.sitemonitor.service;

import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Row;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.mail.MailTokens;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.mail.WeeklyAvailabilityMail;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E-posta gönderim hunisi + tüm e-posta şablonları. Şablonlar {@link MailDoc} ile kurulur
 * (e-posta yeniden tasarımı 2026-09-26, BRAND.md §5.1): mobil-web duyarlı, shadcn görsel dili,
 * Outlook-güvenli; HTML ve düz metin aynı kurucudan çıkar → her e-posta multipart/alternative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    // Mail'e özel logger — bağımsız açılır/kapanır: logging.level.com.sitemonitor.mail=TRACE
    // (env LOGGING_LEVEL_COM_SITEMONITOR_MAIL=TRACE). Detaylı gönderim TRACE'leri buraya gider;
    // operasyonel INFO/ERROR (stack dahil) mevcut @Slf4j `log` üzerinde kalır → TRACE kapalıyken
    // bile hatanın tam stack'i her zaman görünür.
    private static final Logger MAIL_LOG = LoggerFactory.getLogger("com.sitemonitor.mail");

    // Outbound mail is driven by the DB-backed SMTP settings (admin Settings page).
    // When nothing is saved yet, SmtpSettingsService falls back to env spring.mail.*
    // so behaviour is unchanged until the admin saves on the screen.
    private final SmtpSettingsService smtpSettings;
    private final SmtpMailService smtpMailService;
    /** Async 421-retry'ın terminal sonucunu, ilk denemede "QUEUED_RETRY" kaydedilen
     *  bildirim loguna geri-yazmak için (subject ile eşleştirilir). */
    private final com.sitemonitor.repository.NotificationLogRepository notificationLogRepo;
    private final AppSettingsService appSettings;
    private final EmailTemplateBuilder templateBuilder;   // süre-bitişi / kusur alarm şablonu (tek merkez)

    /** Uygulama dış adresi — e-posta CTA deep-link'leri için. Spring @Value enjekte eder;
     *  birim testte (manuel new) initializer değeri kullanılır. */
    @Value("${site.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl = "http://localhost:5173";

    /**
     * Tek thread'lik scheduler — SMTP 421 retry'ları için. Caller thread
     * (sweep executor ya da HTTP request) 90 saniye block etmesin.
     */
    private final ScheduledExecutorService mailRetryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mail-retry");
                t.setDaemon(true);
                return t;
            });

    /**
     * Aynı anda bekleyen 421-retry görevi ÜST SINIRI (2026-08-20 bellek denetimi).
     *
     * mailRetryExecutor'ın kuyruğu JDK'nın DelayedWorkQueue'sudur ve SINIRSIZDIR. Planlanan her
     * görev, closure'ıyla TÜM MimeMessage'ı canlı tutar: HTML gövde + gömülü logo (ByteArrayResource)
     * + varsa haftalık rapor PDF eki. Tek bir mesaj en çok MAX_SEND_ATTEMPTS-1 kez yeniden denenir
     * (90/180/360 sn ≈ 10.5 dk), yani sınırsız olan derinlik değil FAN-OUT: SMTP ağ geçidi uzun süre
     * 421 dönerken bir alarm fırtınası + haftalık rapor aynı pencereye denk gelirse bekleyen mesaj
     * sayısı kadar tam e-posta gövdesi bellekte birikir.
     *
     * Tavan dolduğunda yeni retry PLANLANMAZ: mesaj düşürülür, log.error basılır ve bildirim logu
     * FAILED'a çekilir — "Alarm gönderilemedi" rozeti gerçeği yansıtsın (sessizce yutmak, operatörün
     * gönderilmemiş bir alarmı gönderilmiş sanmasına yol açardı).
     */
    private static final int MAX_PENDING_RETRIES = 50;

    /** Kuyrukta bekleyen 421-retry görev sayısı (tavan denetimi + gözlemlenebilirlik). */
    private final java.util.concurrent.atomic.AtomicInteger pendingRetries =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Bekleyen 421-retry görev sayısı — test ve bellek örnekleyicisi için. */
    int pendingRetryCount() { return pendingRetries.get(); }

    /**
     * Pasif kullanıcı ağı (2026-10-02, kullanıcı kararı): her giden e-posta {@link #doSend}'den geçer ve YALNIZ pasif
     * kullanıcılara ait adresler alıcılardan çıkarılır; hepsi düşerse gönderim yapılmaz, durum
     * {@link InactiveRecipientGuard#STATUS_SKIPPED} döner. Alan enjeksiyonu + isteğe bağlı: elle kurulan birim testlerinde
     * yoktur ve gönderim bugünküyle aynıdır.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InactiveRecipientGuard inactiveRecipientGuard;

    /** Test kancası. */
    void setInactiveRecipientGuard(InactiveRecipientGuard guard) { this.inactiveRecipientGuard = guard; }

    @PreDestroy
    void shutdownRetryExecutor() {
        mailRetryExecutor.shutdown();
        try {
            // Graceful shutdown: bekleyen 421-retry görevlerine kısa süre tanı
            if (!mailRetryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                mailRetryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mailRetryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Current SMTP settings (DB-backed; SmtpSettingsService falls back to env) ──
    private boolean isEnabled() {
        return Boolean.TRUE.equals(smtpSettings.getOrDefaults().getEnabled());
    }
    private String currentFrom() {
        String f = smtpSettings.getOrDefaults().getFromAddress();
        return (f != null && !f.isBlank()) ? f : "noreply@sitemonitor";
    }
    private String currentFromName() {
        return smtpSettings.getOrDefaults().getFromName();
    }
    private long retry() {
        Integer r = smtpSettings.getOrDefaults().getRetryDelayMs();
        return r != null ? r.longValue() : 90000L;
    }
    private org.springframework.mail.javamail.JavaMailSenderImpl currentSender() {
        return smtpMailService.currentSender();
    }
    private void applyFrom(MimeMessageHelper helper) throws Exception {
        String name = currentFromName();
        if (name != null && !name.isBlank()) helper.setFrom(currentFrom(), name);
        else helper.setFrom(currentFrom());
    }

    public String getEmailFrom() { return currentFrom(); }

    public String sendAlert(String to, String subject, String message) {
        return sendAlert(to, subject, message, null, null, null, null, null);
    }

    public String sendAlert(String to, String subject, String message,
                            String domain, String level, String alertType,
                            Integer daysRemaining, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", SecretMask.maskEmails(to), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            applyFrom(helper);
            helper.setSubject(subject);
            // Logo şablonun BAŞLIK ÇUBUĞUNDAN gelir (BRAND.md §5.1) — gönderim yalnız CID ekini iliştirir.
            String variant = BrandMailAssets.variantForLevel(level);
            MailDoc.Mail mail = alertMail(subject, message, domain, level, alertType, daysRemaining, certContext);
            helper.setText(mail.text(), mail.html());   // multipart/alternative (plain + HTML)
            BrandMailAssets.addInline(helper, mail.html(), variant);   // CID inline marka logosu (setText SONRASI)
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(to), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendAlert(String[] toAddresses, String subject, String message,
                            String domain, String level, String alertType,
                            Integer daysRemaining, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", SecretMask.maskEmails(toAddresses), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            applyFrom(helper);
            helper.setSubject(subject);
            String variant = BrandMailAssets.variantForLevel(level);
            MailDoc.Mail mail = alertMail(subject, message, domain, level, alertType, daysRemaining, certContext);
            helper.setText(mail.text(), mail.html());
            BrandMailAssets.addInline(helper, mail.html(), variant);   // CID inline marka logosu (setText SONRASI)
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(toAddresses), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendResolutionAlert(String[] toAddresses, String subject,
                                      String domain, String alertType, String alertLevel,
                                      Integer daysRemaining, String resolvedBy, String resolvedAt,
                                      String createdAt, Map<String, Object> certContext) {
        return sendResolutionAlert(toAddresses, subject, domain, alertType, alertLevel, daysRemaining,
                resolvedBy, resolvedAt, createdAt, certContext, null, null);
    }

    public String sendResolutionAlert(String[] toAddresses, String subject,
                                      String domain, String alertType, String alertLevel,
                                      Integer daysRemaining, String resolvedBy, String resolvedAt,
                                      String createdAt, Map<String, Object> certContext,
                                      String teamNames, UptimeSummary uptime) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", SecretMask.maskEmails(toAddresses));
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            applyFrom(helper);
            helper.setSubject(subject);
            // Çözülme maili DAİMA "ok" varyantı — "sorun çözüldü, yapraklar yeşile döndü" (BRAND.md)
            MailDoc.Mail mail = resolutionMail(domain, alertType, alertLevel, daysRemaining, resolvedBy, resolvedAt,
                    createdAt, certContext, teamNames, uptime);
            helper.setText(mail.text(), mail.html());
            BrandMailAssets.addInline(helper, mail.html(), "ok");
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(toAddresses), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    private String doSend(String toLabel, MimeMessage msg, int attempt) {
        // Pasif kullanıcı ağı — yalnız İLK denemede (421 yeniden denemesi aynı, zaten süzülmüş mesajı gönderir).
        String filteredTo = toLabel;
        if (attempt == 1 && inactiveRecipientGuard != null) {
            InactiveRecipientGuard.MailFilterResult f = inactiveRecipientGuard.filter(msg);
            if (f.skipStatus() != null) return f.skipStatus();
            if (f.dropped() > 0 && f.remainingTo() != null && !f.remainingTo().isBlank()) filteredTo = f.remainingTo();
        }
        final String to = filteredTo;
        long t0 = System.currentTimeMillis();
        if (MAIL_LOG.isTraceEnabled()) {
            MAIL_LOG.trace("→ SMTP gönderim: TO={} | deneme={}/{} | {} | {}",
                    to, attempt, MAX_SEND_ATTEMPTS, describeMessage(msg), smtpContext());
        }
        try {
            currentSender().send(msg);
            long ms = System.currentTimeMillis() - t0;
            if (attempt == 1) {
                log.info("✓ E-posta gönderildi: TO={}", SecretMask.maskEmails(to));
            } else {
                log.info("✓ E-posta gönderildi (retry #{}): TO={}", attempt - 1, SecretMask.maskEmails(to));
            }
            if (MAIL_LOG.isTraceEnabled()) {
                MAIL_LOG.trace("✓ SMTP gönderim OK: TO={} | süre={}ms | messageId={} | boyut={}B",
                        to, ms, safeMessageId(msg), safeSize(msg));
            }
            // Bir async retry (attempt>1) sonunda başarılıysa: ilk denemede "QUEUED_RETRY"
            // kaydedilen log satırını SENT'e güncelle (aksi halde sahte "gönderilemedi" görünür).
            if (attempt > 1) writeBackRetryStatus(msg, "SENT");
            return "SENT";
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            String err = e.getMessage() != null ? e.getMessage() : "";
            // 421 = transient rate-limit from SMTP gateway — birden çok kez, artan
            // bekleme (exponential backoff) ile async retry. Check both getMessage()
            // and toString() because MailSendException may wrap the inner cause.
            String errFull = err + " " + e.toString();
            if (errFull.contains("421") && attempt < MAX_SEND_ATTEMPTS) {
                // backoff: retryDelay × 2^(attempt-1) → base, 2×, 4× …
                long delay = retry() * (1L << (attempt - 1));
                log.warn("⏳ SMTP 421 rate limit (deneme {}/{}) — {}ms sonra async retry: TO={}",
                        attempt, MAX_SEND_ATTEMPTS, delay, SecretMask.maskEmails(to));
                if (MAIL_LOG.isTraceEnabled()) {
                    // Son arg `e` (Throwable) → TRACE'te tam stack de basılır.
                    MAIL_LOG.trace("⏳ SMTP 421 ayrıntı: TO={} | süre={}ms | {} | kök sebep={}",
                            to, ms, smtpContext(), rootMessage(e), e);
                }
                // Bellek tavanı: kuyruk doluysa retry PLANLAMA (bkz. MAX_PENDING_RETRIES).
                if (pendingRetries.get() >= MAX_PENDING_RETRIES) {
                    log.error("✗ 421 retry kuyruğu dolu ({} bekleyen) — retry PLANLANMADI, e-posta düşürüldü: TO={}",
                            MAX_PENDING_RETRIES, SecretMask.maskEmails(to));
                    if (attempt > 1) writeBackRetryStatus(msg, "FAILED: retry kuyruğu dolu");
                    return "FAILED: retry kuyruğu dolu";
                }
                // Caller'ı bloke etme; retry'ı ayrı thread'de tetikle.
                pendingRetries.incrementAndGet();
                mailRetryExecutor.schedule(
                    () -> {
                        try { doSend(to, msg, attempt + 1); }
                        catch (Exception ex) {
                            log.error("✗ Async retry başarısız: TO={} | HATA={}", SecretMask.maskEmails(to), ex.getMessage(), ex);
                        } finally {
                            pendingRetries.decrementAndGet();
                        }
                    },
                    delay, TimeUnit.MILLISECONDS);
                // İlk denemenin sonucu caller'a döner (sonraki retry'lar async, sonucu yutulur).
                return attempt == 1 ? "QUEUED_RETRY: " + err : "QUEUED_RETRY";
            }
            if (errFull.contains("421")) {
                log.error("✗ E-posta {} denemede de 421 rate limit ile gönderilemedi: TO={}", MAX_SEND_ATTEMPTS, SecretMask.maskEmails(to));
            }
            // Son arg `e` (Throwable) → SLF4J tam stack trace'i ERROR'a HER ZAMAN basar
            // (TRACE açmaya gerek yok). SMTP bağlamı + mesaj ayrıntısı ek olarak TRACE'te.
            log.error("✗ E-posta gönderilemedi: TO={} | süre={}ms | HATA={}", SecretMask.maskEmails(to), ms, err, e);
            if (MAIL_LOG.isTraceEnabled()) {
                MAIL_LOG.trace("✗ SMTP hata ayrıntı: TO={} | {} | {} | kök sebep={}",
                        to, describeMessage(msg), smtpContext(), rootMessage(e));
            }
            // Tüm async retry'lar (attempt>1) tükendi ve gönderilemedi: ilk denemede "QUEUED_RETRY"
            // kaydedilen log satırını gerçek FAILED durumuna güncelle (rozet bunu yakalar).
            if (attempt > 1) writeBackRetryStatus(msg, "FAILED: " + err);
            return "FAILED: " + err;
        }
    }

    /**
     * Async 421-retry'ın terminal sonucunu ("SENT" / "FAILED: ...") bildirim loguna geri-yazar:
     * ilk denemede EscalationService'in "QUEUED_RETRY..." olarak kaydettiği satırı subject ile
     * (en güncel) bulup günceller. Async retry sonuçları aksi halde yutulduğundan, log gerçeği
     * yansıtmaz ve "Alarm gönderilemedi" rozeti yanlış çalışır. Gönderimi ASLA kırmaz (try/catch).
     */
    private void writeBackRetryStatus(MimeMessage msg, String terminalStatus) {
        try {
            String subject = msg.getSubject();
            if (subject == null || subject.isBlank()) return;
            notificationLogRepo
                    .findTopBySubjectAndEmailStatusStartingWithOrderByIdDesc(subject, "QUEUED_RETRY")
                    .ifPresent(logRow -> {
                        logRow.setEmailStatus(terminalStatus);
                        notificationLogRepo.save(logRow);
                    });
        } catch (Exception e) {
            log.warn("Retry sonucu bildirim loguna yazılamadı: {}", e.getMessage());
        }
    }

    // ── Mail tanılama yardımcıları (yalnız log; mail GÖVDESİ ve SMTP PAROLASI asla loglanmaz) ──

    /** Mesaj meta verisi (alıcılar, konu, boyut) — gövde OKUNMAZ. Hata olsa bile gönderimi etkilemez. */
    private static String describeMessage(MimeMessage msg) {
        try {
            jakarta.mail.Address[] rcpts = msg.getAllRecipients();
            int size = msg.getSize();
            return "alıcılar=" + Arrays.toString(rcpts)
                    + " konu=" + msg.getSubject()
                    + " boyut=" + (size >= 0 ? size + "B" : "?");
        } catch (Exception ex) {
            return "msg=?(okunamadı: " + ex.getClass().getSimpleName() + ")";
        }
    }

    /** Etkin SMTP bağlamı — host/port/auth/TLS/timeout. PAROLA ASLA dahil edilmez. */
    private String smtpContext() {
        try {
            var s = smtpSettings.getOrDefaults();
            return "smtp=" + s.getHost() + ":" + s.getPort()
                    + " auth=" + (Boolean.TRUE.equals(s.getAuthEnabled()) ? "on" : "off")
                    + " user=" + (s.getUsername() != null ? s.getUsername() : "-")
                    + " starttls=" + Boolean.TRUE.equals(s.getStartTlsEnable())
                    + "/req=" + Boolean.TRUE.equals(s.getStartTlsRequired())
                    + " sslTrust=" + (s.getSslTrust() != null ? s.getSslTrust() : "-")
                    + " timeout(conn/read/write)=" + s.getConnectionTimeoutMs()
                    + "/" + s.getReadTimeoutMs() + "/" + s.getWriteTimeoutMs() + "ms";
        } catch (Exception ex) {
            return "smtp=?(okunamadı: " + ex.getClass().getSimpleName() + ")";
        }
    }

    /** Throwable zincirinin kök sebebine inip mesajını döndürür (mesaj boşsa sınıf adı). */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cur.getClass().getSimpleName();
    }

    private static String safeMessageId(MimeMessage msg) {
        try { String id = msg.getMessageID(); return id != null ? id : "?"; }
        catch (Exception ex) { return "?"; }
    }

    private static String safeSize(MimeMessage msg) {
        try { int n = msg.getSize(); return n >= 0 ? String.valueOf(n) : "?"; }
        catch (Exception ex) { return "?"; }
    }

    /** 421 rate-limit için toplam deneme sayısı (1 ilk + 3 retry); her retry artan beklemeli. */
    private static final int MAX_SEND_ATTEMPTS = 4;

    /** Rich resolution email with full context (manual or auto resolve). */
    public String sendResolutionAlert(String to, String subject,
                                      String domain, String alertType, String alertLevel,
                                      Integer daysRemaining, String resolvedBy, String resolvedAt,
                                      String createdAt, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", SecretMask.maskEmails(to));
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            applyFrom(helper);
            helper.setSubject(subject);
            MailDoc.Mail mail = resolutionMail(domain, alertType, alertLevel, daysRemaining, resolvedBy, resolvedAt,
                    createdAt, certContext, null, null);
            helper.setText(mail.text(), mail.html());
            BrandMailAssets.addInline(helper, mail.html(), "ok");   // dizi-overload ile simetri (çözülme = ok)
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(to), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /**
     * Elle kurulan admin/güvenlik mailleri için TEK huni: helper + setText + CID logo + doSend.
     *
     * <p>Eskiden altı metot (parola sıfırlama, yeni cihaz, ağ alarmı/çözümü, login anomalisi/çözümü)
     * kendi MimeMessageHelper'ını kurup {@code setText} sonrası {@code BrandMailAssets.addInline}'ı
     * ATLIYORDU: gövde {@code cid:brand-logo} referansı taşırken mesajda Content-ID parçası yoktu →
     * istemci logonun yerinde kırık resim (X) gösteriyordu ("Failed-Login Anomaly test maili",
     * 2026-09-10). sendAlert/sendResolutionAlert ve sendHtml hunisi doğruydu; sınıf-kapatıcı kapı
     * {@code EmailBrandCidTest} (her elle kurulan gönderici Content-ID taşımalı + huni sayısı sabit).
     *
     * <p>Düz metin: MailDoc'un yazdığı metin (yoksa HTML'den türetilir) → multipart/alternative.
     */
    private String sendFramedHtml(String[] to, String subject, String html, String variant) throws Exception {
        MimeMessage msg = currentSender().createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setTo(to);
        applyFrom(helper);
        helper.setSubject(subject);
        String text = MailKit.plainTextFor(html);
        if (text != null && !text.isBlank()) helper.setText(text, html);
        else helper.setText(html, true);
        BrandMailAssets.addInline(helper, html, variant);   // setText SONRASI (Spring helper sırası)
        return doSend(String.join(",", to), msg, 1);
    }

    /**
     * SMTP Gönderim Logu "Yeniden gönder" (2026-09-19): kayıtlı HTML gövdeyi AYNEN, aynı alıcıya ve aynı
     * konuyla gönderir — şablon yeniden kurulmaz (gövde zaten çerçeveli), {@link #sendFramedHtml} hunisi
     * CID logoyu iliştirir. Dönüş sözlüğü diğer gönderimlerle aynı (SENT / FAILED: … / SKIPPED_DISABLED).
     */
    public String resendStoredHtml(String to, String subject, String html, String logoVariant) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — yeniden gönderim atlandı: TO={}", SecretMask.maskEmails(to));
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{to}, subject, html, logoVariant);
        } catch (Exception e) {
            log.error("✗ Yeniden gönderim hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(to), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    // ── Password reset — admin auto-reset flow ──────────────────────────────

    /**
     * Sends a one-time temporary password to a user whose account was
     * auto-reset by an admin. The plaintext temp password is ONLY ever
     * present in this email body — it is never logged.
     */
    public String sendPasswordResetEmail(String toAddress, String username,
                                          String displayName, String tempPassword) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — şifre sıfırlama: TO={}", SecretMask.maskEmails(toAddress));
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{toAddress}, "[Site Monitor] Şifreniz sıfırlandı — lütfen güncelleyin",
                    buildPasswordResetHtml(username, displayName, tempPassword), "ok");
        } catch (Exception e) {
            log.error("✗ Şifre sıfırlama e-postası hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(toAddress), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    String buildPasswordResetHtml(String username, String displayName, String tempPwd) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : username;
        MailDoc d = MailDoc.create("[Site Monitor] Şifreniz sıfırlandı")
                .preheader("Hesabınızın şifresi bir yönetici tarafından sıfırlandı — geçici şifre 24 saat geçerli.")
                .kicker("Hesap Güvenliği");
        d.title("Şifreniz sıfırlandı", "Site Monitor hesabınızın şifresi bir yönetici tarafından sıfırlandı.");
        d.paragraphHtml("Sayın <strong>" + esc(name) + "</strong>,", "Sayın " + nzs(name) + ",");
        d.keyValue(MailDoc.rows(
                new Row("Kullanıcı adı", MailKit.mono(username), nzs(username)),
                new Row("Geçici şifre", "<span class=\"mono\" style=\"font-family:" + MailTokens.MONO
                        + ";font-size:16px;font-weight:600;letter-spacing:0.04em\">" + esc(tempPwd) + "</span>", nzs(tempPwd))));
        d.alert(Tone.WARNING, "Bu şifre 24 saat geçerlidir.",
                "Bu süre içinde giriş yapmazsanız geçici şifreniz devre dışı kalır ve yeni bir sıfırlama talep etmeniz gerekir.");
        d.paragraph("İlk girişinizde sistem sizden kalıcı bir şifre belirlemenizi isteyecektir.");
        d.note("Bu işlemi siz başlatmadıysanız lütfen sistem yöneticinizle iletişime geçin.");
        d.footerMeta("Site Monitor — Hesap Güvenliği", "Bu e-posta otomatik gönderilmiştir.");
        return d.html();
    }

    /**
     * "Yeni bir cihazdan giriş yapıldı" bilgi e-postası (E1).
     *
     * <p>Ton bilinçli olarak SAKİN: bu bir alarm değil bilgilendirmedir; girişi yapan çoğu zaman
     * kullanıcının kendisidir. "Bu sen değilsen" yolu net ama panik yaratmadan verilir.
     */
    /**
     * Kodla giriş e-postası (2026-10-02, kullanıcı isteği) — TEK huniden ({@code sendHtmlInternal}: marka CID'i,
     * multipart/alternative, pasif alıcı ağı, 421 yeniden denemesi). {@code force=true}: alarm e-postası susturulmuş olsa
     * da gider — kullanıcının kendi istediği işlemsel ileti (giriş sorunu bildirimiyle aynı karar); SMTP hiç kurulmamışsa
     * gönderim yine FAILED döner. {@code notification_logs}'a YAZILMAZ (kod o tabloya düşmez); gövde hiçbir log'a yazılmaz —
     * huni yalnız maskeli alıcı + konu loglar, konu kodu taşımaz.
     */
    public String sendLoginCode(String to, com.sitemonitor.service.mail.LoginCodeMail.Info info) {
        if (to == null || to.isBlank()) return "SKIPPED: alıcı yok";
        MailDoc.Mail m;
        try {
            m = com.sitemonitor.service.mail.LoginCodeMail.build(info);
        } catch (Exception e) {
            log.error("✗ Giriş kodu e-postası hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(to), e.getClass().getSimpleName());
            return "FAILED: " + e.getClass().getSimpleName();
        }
        return sendHtmlInternal(new String[]{to}, null, com.sitemonitor.service.mail.LoginCodeMail.subject(),
                m.html(), m.text(), null, true, null);
    }

    public String sendNewDeviceEmail(String toAddress, String displayName, String deviceSummary,
                                     String ip, String location, String whenIso) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — yeni cihaz bildirimi: TO={}", SecretMask.maskEmails(toAddress));
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{toAddress}, "[Site Monitor] Hesabınıza yeni bir cihazdan giriş yapıldı",
                    buildNewDeviceHtml(displayName, deviceSummary, ip, location, whenIso), "ok");
        } catch (Exception e) {
            log.error("✗ Yeni cihaz e-postası hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(toAddress), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    String buildNewDeviceHtml(String displayName, String deviceSummary,
                              String ip, String location, String whenIso) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of("Cihaz", deviceSummary));
        if (whenIso != null && !whenIso.isBlank()) rows.add(Row.of("Zaman", whenIso));
        if (location != null && !location.isBlank()) rows.add(Row.of("Konum", location));
        if (ip != null && !ip.isBlank()) rows.add(Row.of("IP", ip));

        MailDoc d = MailDoc.create("[Site Monitor] Yeni cihazdan giriş")
                .preheader("Hesabınıza daha önce görmediğimiz bir cihazdan giriş yapıldı.")
                .kicker("Hesap Güvenliği");
        d.badges(Badge.tint("BİLGİ", Tone.INFO));
        d.title("Yeni cihazdan giriş", "Site Monitor hesabınıza daha önce görmediğimiz bir cihazdan giriş yapıldı.");
        d.paragraphHtml("Sayın <strong>" + esc(displayName) + "</strong>,", "Sayın " + nzs(displayName) + ",");
        d.keyValue(rows);
        d.alertHtml(Tone.SUCCESS, "Bu sizseniz", "yapmanız gereken bir şey yok.", "Bu sizseniz, yapmanız gereken bir şey yok.");
        d.alertHtml(Tone.WARNING, "Bu siz değilseniz",
                "parolanızı değiştirin ve \"Etkinliklerim → Cihaz Geçmişi\" ekranından hatırlanan cihazları iptal edin.",
                "Bu siz değilseniz, parolanızı değiştirin ve \"Etkinliklerim → Cihaz Geçmişi\" ekranından hatırlanan cihazları iptal edin.");
        d.footerMeta("Site Monitor — Hesap Güvenliği", "Bu e-posta otomatik gönderilmiştir.");
        return d.html();
    }

    // ── System admin — sürüm geçişi bildirimi (E3, opt-in) ───────────────────

    /** Dağıtım bildirimi verisi — {@code DeploymentNotifyService} toplar, burada yalnız çizilir. */
    public record DeploymentNotice(String kind, String environment, String fromVersion, String toVersion,
                                   String commitShort, String startedAt, List<String> highlights, boolean breaking) {}

    /** UPGRADE → yeşil (ok) logo, ROLLBACK → kırmızı (critical) logo. */
    public String sendDeploymentNotice(String[] recipients, DeploymentNotice n) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — dağıtım bildirimi atlanıyor: {} {}→{}", n.environment(), n.fromVersion(), n.toVersion());
            return "SKIPPED_DISABLED";
        }
        if (recipients == null || recipients.length == 0) {
            log.warn("Dağıtım bildirimi alıcısı yok — atlanıyor");
            return "SKIPPED_NO_RECIPIENT";
        }
        try {
            boolean rollback = "ROLLBACK".equals(n.kind());
            String subject = "[Site Monitor] " + (rollback ? "⏪ Geri alma: " : "🚀 Yükseltme: ")
                    + n.environment() + " " + n.fromVersion() + " → " + n.toVersion();
            return sendFramedHtml(recipients, subject, buildDeploymentNoticeHtml(n), rollback ? "critical" : "ok");
        } catch (Exception e) {
            log.error("✗ Dağıtım bildirimi hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(recipients), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /** Sürüm geçişi bildirimi — TR ana metin + EN alt satır. */
    public String buildDeploymentNoticeHtml(DeploymentNotice n) {
        boolean rollback = "ROLLBACK".equals(n.kind());
        String titleTr = rollback ? "Geri alma yapıldı" : "Yeni sürüm devreye alındı";
        String titleEn = rollback ? "Rollback deployed" : "New version deployed";
        String range = n.fromVersion() + " → " + n.toVersion();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of("Ortam / Environment", n.environment()));
        rows.add(new Row("Sürüm / Version", MailKit.strong(range, null), range));
        rows.add(Row.of("Tür / Kind", rollback ? "ROLLBACK (geri alma)" : "UPGRADE (yükseltme)"));
        if (n.commitShort() != null && !n.commitShort().isBlank()) rows.add(new Row("Commit", MailKit.mono(n.commitShort()), n.commitShort()));
        if (n.startedAt() != null && !n.startedAt().isBlank()) rows.add(Row.of("Başlangıç / Started", n.startedAt()));

        MailDoc d = MailDoc.create("[Site Monitor] " + titleTr + " — " + n.environment() + " " + range)
                .preheader(titleEn + " · " + n.environment() + " " + range)
                .kicker("Sürüm & Dağıtım");
        d.badges(rollback ? Badge.solid("ROLLBACK", Tone.DESTRUCTIVE) : Badge.tint("UPGRADE", Tone.SUCCESS),
                Badge.outline(n.environment()));
        d.title(titleTr, titleEn);
        d.keyValue(rows);
        if (n.breaking()) d.alert(Tone.DESTRUCTIVE, "Kırıcı değişiklik içerir / Contains breaking changes.", null);
        if (n.highlights() != null && !n.highlights().isEmpty()) {
            d.heading("Öne çıkanlar / Highlights");
            d.bullets(n.highlights());
        }
        d.noteHtml("Ayrıntı: Sistem Sağlığı → Sürüm &amp; Dağıtım. Bu bildirim <em>site.monitor.deploy.notify.enabled</em> ayarı ile açılıp kapatılır.",
                "Ayrıntı: Sistem Sağlığı → Sürüm & Dağıtım. Bu bildirim site.monitor.deploy.notify.enabled ayarı ile açılıp kapatılır.");
        d.footerMeta("Site Monitor — Sistem Yöneticisi Bildirimi");
        return d.html();
    }

    // ── System admin — network outage notifications ──────────────────────────

    public String sendSystemAdminNetworkAlert(String to, String detectedAt,
                                              int networkErrors, int total,
                                              double errorRate, double threshold) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — admin network alert: TO={}", SecretMask.maskEmails(to));
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{to}, "[Site Monitor] ⚠ Ağ Erişim Sorunu Tespit Edildi",
                    buildAdminNetworkAlertHtml(detectedAt, networkErrors, total, errorRate, threshold), "critical");
        } catch (Exception e) {
            log.error("✗ Admin network alert hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(to), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendSystemAdminNetworkResolved(String to, String detectedAt, String resolvedAt,
                                                 long durationMs, int networkErrors, int total,
                                                 double errorRate) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — admin network resolved: TO={}", SecretMask.maskEmails(to));
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{to}, "[Site Monitor] ✅ Ağ Erişim Sorunu Çözüldü",
                    buildAdminNetworkResolvedHtml(detectedAt, resolvedAt, durationMs, networkErrors, total, errorRate), "ok");
        } catch (Exception e) {
            log.error("✗ Admin network resolved hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(to), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    String buildAdminNetworkAlertHtml(String detectedAt, int networkErrors, int total,
                                      double errorRate, double threshold) {
        String ratePct = String.format("%.0f%%", errorRate * 100);
        String threshPct = String.format("%.0f%%", threshold * 100);
        MailDoc d = MailDoc.create("[Site Monitor] Ağ erişim sorunu tespit edildi")
                .preheader(networkErrors + " / " + total + " domain ağ hatasıyla düştü (oran " + ratePct + ")")
                .kicker("Sistem Yöneticisi");
        d.badges(Badge.solid("KRİTİK", Tone.DESTRUCTIVE), Badge.outline("Ağ Erişimi"));
        d.title("Ağ erişim sorunu tespit edildi", "Site Monitor host'unun bir veya daha fazla sertifika kontrolünü tamamlayamadığı tespit edildi.");
        d.alertHtml(Tone.DESTRUCTIVE, "Outbound bağlantı sorunu olabilir",
                "Tarama turunda <strong>" + networkErrors + " / " + total + "</strong> domain ağ-class hatasıyla düştü "
                        + "(oran: <strong>" + ratePct + "</strong>, eşik: " + threshPct + ").",
                "Tarama turunda " + networkErrors + " / " + total + " domain ağ-class hatasıyla düştü (oran: " + ratePct + ", eşik: " + threshPct + ").");
        d.keyValue(MailDoc.rows(
                Row.of("Tespit Zamanı", formatIso(detectedAt)),
                Row.of("Etkilenen Domain", networkErrors + " / " + total),
                new Row("Hata Oranı", MailKit.strong(ratePct, Tone.DESTRUCTIVE.strong), ratePct),
                Row.of("Eşik", threshPct)));
        d.heading("Sistemin Aksiyonu");
        d.bulletsHtml(List.of("Yeni alarm üretimi <strong>geçici olarak duraklatıldı</strong>",
                        "Auto-resolve işlemi <strong>askıya alındı</strong> (sahte resolved e-posta yağmuru engellenir)",
                        "Dashboard'da operatörlere uyarı banner'ı gösterildi"),
                List.of("Yeni alarm üretimi geçici olarak duraklatıldı",
                        "Auto-resolve işlemi askıya alındı (sahte resolved e-posta yağmuru engellenir)",
                        "Dashboard'da operatörlere uyarı banner'ı gösterildi"));
        d.heading("Önerilen Kontroller");
        d.bullets(List.of("Host'un internet bağlantısı (modem/router)", "Outbound proxy ayarları",
                "Kurumsal firewall/NAT politikaları", "DNS sunucu erişilebilirliği"));
        d.noteHtml("Ağ erişimi normale döner dönmez ayrıca bir <strong>\"Çözüldü\"</strong> e-postası alacaksınız.",
                "Ağ erişimi normale döner dönmez ayrıca bir \"Çözüldü\" e-postası alacaksınız.");
        d.footerMeta("Site Monitor — System Admin Notification");
        return d.html();
    }

    String buildAdminNetworkResolvedHtml(String detectedAt, String resolvedAt, long durationMs,
                                         int networkErrors, int total, double errorRate) {
        long durationMin = durationMs / 60000;
        long durationSec = (durationMs / 1000) % 60;
        String durationStr = durationMin + " dk " + durationSec + " sn";
        String ratePct = String.format("%.0f%%", errorRate * 100);
        MailDoc d = MailDoc.create("[Site Monitor] Ağ erişim sorunu çözüldü")
                .preheader("Outbound bağlantı sorunu çözüldü — toplam süre " + durationStr)
                .kicker("Sistem Yöneticisi");
        d.badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS), Badge.outline("Ağ Erişimi"));
        d.title("Ağ erişim sorunu çözüldü", "Site Monitor host'unun outbound bağlantı sorunu çözüldü. Sertifika kontrolleri normal işleyişe döndü.");
        d.keyValue(MailDoc.rows(
                Row.of("Tespit Zamanı", formatIso(detectedAt)),
                Row.of("Çözüm Zamanı", formatIso(resolvedAt)),
                new Row("Toplam Süre", MailKit.strong(durationStr, Tone.SUCCESS.strong), durationStr),
                Row.of("Tespit Anında Etkilenen", networkErrors + " / " + total + " (" + ratePct + ")")));
        d.heading("Sistemin Aksiyonu");
        d.bulletsHtml(List.of("Yeni alarm üretimi <strong>yeniden aktif</strong>", "Auto-resolve işlemi <strong>yeniden aktif</strong>",
                        "Dashboard uyarı banner'ı kaldırıldı"),
                List.of("Yeni alarm üretimi yeniden aktif", "Auto-resolve işlemi yeniden aktif", "Dashboard uyarı banner'ı kaldırıldı"));
        d.alert(Tone.SUCCESS, null,
                "Not: Outage süresince üretilebilecek sahte alarmlar bastırıldığı için ekibinize ÇÖZÜLDÜ e-posta yağmuru gönderilmedi.");
        d.footerMeta("Site Monitor — System Admin Notification");
        return d.html();
    }

    // ── System admin — başarısız-login anomali uyarısı ────────────────────────

    /** Anomali uyarısı — çoklu alıcı; Outlook-safe. {@code triggerLabel} = INITIAL/ESCALATION/… (mail üstü). */
    public String sendSystemAdminLoginAnomalyAlert(String[] recipients,
            FailedLoginAnomalyService.AnomalyReport r, String triggerLabel) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — login anomaly alert atlanıyor");
            return "SKIPPED_DISABLED";
        }
        if (recipients == null || recipients.length == 0) {
            log.warn("Login anomaly alert alıcısı yok — atlanıyor");
            return "SKIPPED_NO_RECIPIENT";
        }
        try {
            int ruleCount = (r.hits() == null) ? 0 : r.hits().size();
            return sendFramedHtml(recipients, "[Site Monitor] ⚠ Anomali: " + r.total() + " başarısız login / "
                    + r.windowMinutes() + "dk — " + ruleCount + " kural tetiklendi",
                    buildLoginAnomalyHtml(r, triggerLabel), "critical");
        } catch (Exception e) {
            log.error("✗ Login anomaly alert hazırlanamadı: HATA={}", e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /** "Durum normale döndü" uyarısı. */
    public String sendSystemAdminLoginAnomalyResolved(String[] recipients,
            String openedAt, String resolvedAt, long peakTotal) {
        if (!isEnabled()) return "SKIPPED_DISABLED";
        if (recipients == null || recipients.length == 0) return "SKIPPED_NO_RECIPIENT";
        try {
            return sendFramedHtml(recipients, "[Site Monitor] ✅ Login anomalisi normale döndü",
                    buildLoginAnomalyResolvedHtml(openedAt, resolvedAt, peakTotal), "ok");
        } catch (Exception e) {
            log.error("✗ Login anomaly resolved hazırlanamadı: HATA={}", e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    String buildLoginAnomalyHtml(FailedLoginAnomalyService.AnomalyReport r, String triggerLabel) {
        int n = (r.hits() == null) ? 0 : r.hits().size();
        MailDoc d = MailDoc.create("[Site Monitor] Başarısız login anomalisi")
                .preheader(r.total() + " başarısız login / " + r.windowMinutes() + " dk — " + n + " kural tetiklendi")
                .kicker("Güvenlik");
        d.badges(Badge.solid("KRİTİK", Tone.DESTRUCTIVE),
                triggerLabel != null && !triggerLabel.isBlank() ? Badge.outline(triggerLabel) : null);
        d.title("Başarısız login anomalisi", r.total() + " başarısız login · " + r.windowMinutes() + " dk pencere · " + n + " kural tetiklendi");
        d.keyValue(MailDoc.rows(
                Row.of("Zaman penceresi", r.windowStart() + " → " + r.windowEnd() + " (UTC)"),
                new Row("Toplam başarısız login", MailKit.strong(String.valueOf(r.total()), Tone.DESTRUCTIVE.strong), String.valueOf(r.total())),
                Row.of("Önceki dönem ort.", FailedLoginAnomalyService.fmt1(r.baselineAvgPerWindow()) + " / " + r.windowMinutes() + " dk pencere")));

        List<List<Cell>> rules = new ArrayList<>();
        if (r.hits() != null) for (FailedLoginAnomalyService.RuleHit h : r.hits()) {
            String label = laRuleLabel(h.code());
            rules.add(List.of(
                    Cell.html(esc(label) + "<br><span style=\"font-size:12px;font-weight:400;color:" + MailTokens.MUTED + "\">" + esc(h.detail()) + "</span>",
                            label + " (" + nzs(h.detail()) + ")"),
                    Cell.of("≥ " + h.threshold()),
                    Cell.of(String.valueOf(h.actual()), Tone.DESTRUCTIVE.strong, true)));
        }
        if (!rules.isEmpty()) {
            d.heading("Tetiklenen kurallar (" + n + ")");
            d.table(List.of(Col.of("Kural"), Col.num("Eşik"), Col.num("Gerçekleşen")), rules, false);
        }
        laKvSection(d, "En çok hedeflenen hesaplar", "Hesap", r.topAccounts(), "deneme");
        laKvSection(d, "En aktif kaynak IP'ler", "IP", r.topIps(), "deneme");
        laKvSection(d, "IP → farklı kullanıcı (credential stuffing)", "IP", r.stuffingIps(), "kullanıcı");
        laReasonSection(d, r.reasonDistribution());

        String cta = loginAnomalyCtaUrl(r.windowStart());
        if (!cta.isEmpty()) d.button(cta, "Denetim kaydını aç");
        d.footerMeta("Site Monitor — Güvenlik Bildirimi", "Bildirim: " + nowStamp());
        return d.html();
    }

    String buildLoginAnomalyResolvedHtml(String openedAt, String resolvedAt, long peakTotal) {
        MailDoc d = MailDoc.create("[Site Monitor] Login anomalisi normale döndü")
                .preheader("Başarısız-login hacmi eşiklerin altına indi.")
                .kicker("Güvenlik");
        d.badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS));
        d.title("Login anomalisi normale döndü", "Takip eden kontrolde başarısız-login hacmi eşiklerin altına indi.");
        d.keyValue(MailDoc.rows(
                Row.of("Başlangıç", nzs(openedAt) + " (UTC)"),
                Row.of("Çözülme", nzs(resolvedAt) + " (UTC)"),
                Row.of("Zirve hacim", String.valueOf(peakTotal))));
        d.footerMeta("Site Monitor — Güvenlik Bildirimi", "Bildirim: " + nowStamp());
        return d.html();
    }

    private void laKvSection(MailDoc d, String title, String keyLabel, java.util.List<FailedLoginAnomalyService.KV> items, String unit) {
        if (items == null || items.isEmpty()) return;
        List<List<Cell>> rows = new ArrayList<>();
        int i = 0;
        for (FailedLoginAnomalyService.KV kv : items) {
            if (i++ >= 5) break;
            rows.add(List.of(Cell.of(kv.key()), Cell.of(kv.count() + " " + unit)));
        }
        d.heading(title);
        d.table(List.of(Col.of(keyLabel), Col.num("Adet")), rows, false);
    }

    private void laReasonSection(MailDoc d, java.util.Map<String, Long> dist) {
        if (dist == null || dist.isEmpty()) return;
        List<List<Cell>> rows = new ArrayList<>();
        for (java.util.Map.Entry<String, Long> e : dist.entrySet()) {
            rows.add(List.of(Cell.of(e.getKey()), Cell.of(String.valueOf(e.getValue()))));
        }
        d.heading("Başarısızlık nedeni dağılımı");
        d.table(List.of(Col.of("Neden"), Col.num("Adet")), rows, false);
    }

    private static String laRuleLabel(String code) {
        if (code == null) return "";
        return switch (code) {
            case "GLOBAL_VOLUME"          -> "Genel hacim";
            case "ACCOUNT_TARGETED"       -> "Hesap odaklı";
            case "IP_BRUTE_FORCE"         -> "IP brute force";
            case "IP_CREDENTIAL_STUFFING" -> "Credential stuffing";
            case "DISTRIBUTED"            -> "Dağıtık saldırı";
            case "RELATIVE_SPIKE"         -> "Görece sıçrama";
            default                       -> code;
        };
    }

    /** CANLI base-url'den Denetim (audit) ekranına başarısız-login filtresiyle deep-link. */
    private String loginAnomalyCtaUrl(String windowStart) {
        String base = liveBaseUrl();
        if (base.isEmpty()) return "";
        String since = java.net.URLEncoder.encode(windowStart == null ? "" : windowStart, java.nio.charset.StandardCharsets.UTF_8);
        return base + "/?tab=system&a_eventType=LOGIN_FAILED&a_since=" + since;
    }

    // ── Sorun bildirimi (login-issue / uygulama hatası / kullanıcı bildirimi) ─────

    /** Login-issue mail gönderim sonucu — {@code status} (SENT/FAILED/SKIPPED_*) + geçmişe yazılacak
     *  {@code from}/{@code subject}/{@code bodyHtml}. Alıcının gördüğü mailin aynısı ({@code bodyHtml}). */
    public record LoginIssueMailResult(String status, String from, String subject, String bodyHtml) {}

    /** Gönderen (from) adresi — DB SMTP ayarlarından; login-issue geçmişinde "kimden" için. */
    public String senderAddress() { return currentFrom(); }

    /** Login sayfasından "sorun bildir" — Genel Ayarlar'daki Sistem Yöneticisi E-postası'na gider.
     *  Kimliksiz (public) akıştan geldiği için içerik tamamen escape'lenir; alıcı sabittir.
     *  Ekran görüntüleri (≤5) CID inline gömülür ({@link #sendHtml}). */
    public LoginIssueMailResult sendLoginIssueReport(String to, String refCode, String username, String reporterEmail,
                                       String errorText, String message,
                                       List<InlineImage> images,
                                       String clientIp, String userAgent, String reportedAt, boolean force) {
        List<InlineImage> inline = images != null ? images : List.of();
        String html = buildLoginIssueHtml(refCode, username, reporterEmail, errorText, message, inline, clientIp, userAgent, reportedAt, false);
        String subject = "[Site Monitor] 🛟 Giriş Sorunu Bildirimi — " + refCode +
                (username != null && !username.isBlank() ? " · " + username : "");
        String status = sendHtml(new String[]{ to }, null, subject, html, inline, force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    /** Uygulama içi ekran çökmesi (ErrorBoundary) otomatik bildirimi — Sistem Yöneticisi E-postası'na gider.
     *  Login sorun bildiriminden farkı: kullanıcı oturum içindedir, görsel yoktur, hata metni stack trace'tir. */
    public LoginIssueMailResult sendClientErrorReport(String to, String refCode, String username,
                                       String errorText, String message,
                                       String clientIp, String userAgent, String reportedAt, boolean force) {
        String html = buildClientErrorHtml(refCode, username, errorText, message, clientIp, userAgent, reportedAt);
        String subject = "[Site Monitor] 🐞 Uygulama Hatası — " + refCode +
                (username != null && !username.isBlank() ? " · " + username : "");
        String status = sendHtml(new String[]{ to }, null, subject, html, List.of(), force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    /** Ekran çökmesi bildirimi gövdesi (gönderimden ayrı — önizleme galerisi ve testler için). */
    String buildClientErrorHtml(String refCode, String username, String errorText, String message,
                                String clientIp, String userAgent, String reportedAt) {
        MailDoc d = MailDoc.create("[Site Monitor] Uygulama hatası — " + nzs(refCode))
                .preheader("Uygulama içinde bir ekran hatası (çökme) yakalandı — " + nzs(refCode))
                .kicker("Sorun Bildirimi");
        d.badges(Badge.tint("UYGULAMA HATASI", Tone.DESTRUCTIVE));
        d.title("Uygulama hatası bildirimi", "Uygulama içinde bir ekran hatası (çökme) yakalandı ve otomatik olarak bildirildi.");
        d.keyValue(MailDoc.rows(
                new Row("Referans Numarası", MailKit.strong(refCode, null), nzs(refCode)),
                new Row("Kullanıcı", MailKit.strong(username, null), nzs(username)),
                Row.of("Bildirim Zamanı", formatIso(reportedAt)),
                Row.of("IP Adresi", clientIp != null ? clientIp : "—"),
                Row.of("Tarayıcı", userAgent != null ? userAgent : "—")));
        d.card("Sayfa / Bağlam", null, MailKit.paragraph(MailKit.escBr(message)), nzs(message));
        d.pre("Hata Ayrıntısı (Stack)", errorText);
        d.note("Bu bildirim uygulamanın hata yakalayıcısı (ErrorBoundary) tarafından otomatik gönderilmiştir; kullanıcı ayrıca bir açıklama girmemiştir.");
        d.footerMeta("Site Monitor — Sorun Bildirimi", "Bildirim: " + nowStamp());
        return d.html();
    }

    /** Kullanıcı-tetiklemeli sorun bildirimi (USER_REPORT) — Sistem Yöneticisi'ne. Alarm DEĞİL:
     *  nötr ton, kullanıcının açıklaması odakta; otomatik bağlam ayrı blokta. Görseller CID inline. */
    public LoginIssueMailResult sendUserIssueReport(String to, String refCode, String username, String reporterEmail,
                                       String category, String message, String errorText, String linkedReference,
                                       String tabKey, String appVersion, List<InlineImage> images,
                                       String clientIp, String userAgent, String reportedAt, boolean force) {
        return sendUserIssueReport(to, refCode, username, reporterEmail, category, message, errorText, linkedReference,
                tabKey, appVersion, images, clientIp, userAgent, reportedAt, force, null, null);
    }

    /** Çoklu etkili imza (2026-09-28) — {@code impacts} kanonik CSV, {@code impactOther} yalnız OTHER'da. */
    public LoginIssueMailResult sendUserIssueReport(String to, String refCode, String username, String reporterEmail,
                                       String category, String message, String errorText, String linkedReference,
                                       String tabKey, String appVersion, List<InlineImage> images,
                                       String clientIp, String userAgent, String reportedAt, boolean force,
                                       String impacts, String impactOther) {
        List<InlineImage> inline = images != null ? images : List.of();
        String html = buildUserIssueHtml(refCode, username, reporterEmail, category, message, errorText, linkedReference,
                tabKey, appVersion, inline, clientIp, userAgent, reportedAt, impacts, impactOther);
        String subject = "[Site Monitor] 📝 Sorun Bildirimi — " + refCode +
                (username != null && !username.isBlank() ? " · " + username : "");
        String status = sendHtml(new String[]{ to }, null, subject, html, inline, force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    String buildUserIssueHtml(String refCode, String username, String reporterEmail, String category, String message,
                              String errorText, String linkedReference, String tabKey, String appVersion,
                              List<InlineImage> inline, String clientIp, String userAgent, String reportedAt) {
        return buildUserIssueHtml(refCode, username, reporterEmail, category, message, errorText, linkedReference,
                tabKey, appVersion, inline, clientIp, userAgent, reportedAt, null, null);
    }

    String buildUserIssueHtml(String refCode, String username, String reporterEmail, String category, String message,
                              String errorText, String linkedReference, String tabKey, String appVersion,
                              List<InlineImage> inline, String clientIp, String userAgent, String reportedAt,
                              String impacts, String impactOther) {
        MailDoc d = MailDoc.create("[Site Monitor] Sorun bildirimi — " + nzs(refCode))
                .preheader("Bir kullanıcı uygulama içinden sorun bildirdi — " + nzs(refCode))
                .kicker("Sorun Bildirimi");
        d.badges(category != null && !category.isBlank() ? Badge.tint(labelForCategory(category).toUpperCase(java.util.Locale.forLanguageTag("tr")),
                "BLOCKER".equals(category) ? Tone.DESTRUCTIVE : "ANNOYANCE".equals(category) ? Tone.WARNING : Tone.INFO) : null);
        d.title("Sorun bildirimi", "Bir kullanıcı uygulama içinden sorun bildirdi.");
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Referans Numarası", MailKit.strong(refCode, null), nzs(refCode)));
        rows.add(new Row("Kullanıcı", MailKit.strong(username, null), nzs(username)));
        if (reporterEmail != null && !reporterEmail.isBlank()) rows.add(Row.of("E-posta", reporterEmail));
        if (category != null && !category.isBlank())
            rows.add(Row.of("DOMAIN_TRANSFER".equals(category) ? "Talep Türü" : "Önem", labelForCategory(category)));
        // Çoklu etki (2026-09-28): okunur Türkçe etiketler; "Diğer" metni etiketle birlikte (Row.of kaçışlar).
        String impactText = impactLabels(impacts, impactOther);
        if (impactText != null) rows.add(Row.of("Yaşanan Sorunlar", impactText));
        if (tabKey != null && !tabKey.isBlank()) rows.add(Row.of("Ekran/Sekme", tabKey));
        if (appVersion != null && !appVersion.isBlank()) rows.add(Row.of("Uygulama Sürümü", appVersion));
        if (linkedReference != null && !linkedReference.isBlank()) rows.add(Row.of("Bağlı Çökme Kaydı", linkedReference));
        rows.add(Row.of("Bildirim Zamanı", formatIso(reportedAt)));
        rows.add(Row.of("IP Adresi", clientIp != null ? clientIp : "—"));
        rows.add(Row.of("Tarayıcı", userAgent != null ? userAgent : "—"));
        d.keyValue(rows);
        d.card("Kullanıcının Açıklaması", null, MailKit.paragraph(MailKit.escBr(message)), nzs(message));
        d.pre("Ekrandaki Hata", errorText);
        screenshots(d, inline, false);
        d.note("Ayrıntılı otomatik bağlam (tema/dil/son başarısız istekler) Yönetim > Sorun Bildirimleri ekranındadır.");
        d.footerMeta("Site Monitor — Sorun Bildirimi", "Bildirim: " + nowStamp());
        return d.html();
    }

    /** Etki CSV'si → "Giriş yapamıyor / oturumu düşüyor · Uygulama yavaş · Diğer: …" (bilinmeyen kod düşer); boş → null. */
    static String impactLabels(String impacts, String impactOther) {
        List<String> out = new ArrayList<>();
        for (String code : com.sitemonitor.service.LoginIssueService.impactList(impacts)) {
            String label = labelForImpact(code);
            if ("OTHER".equals(code) && impactOther != null && !impactOther.isBlank()) label = label + ": " + impactOther;
            out.add(label);
        }
        return out.isEmpty() ? null : String.join(" · ", out);
    }

    private static String labelForImpact(String code) {
        return switch (code) {
            case "LOGIN"            -> "Giriş yapamıyor / oturumu düşüyor";
            case "PAGE_NOT_LOADING" -> "Sayfa açılmıyor ya da yüklenmiyor";
            case "SLOW"             -> "Uygulama yavaş";
            case "WRONG_DATA"       -> "Veriler yanlış ya da eksik";
            case "SAVE_ERROR"       -> "Kaydedemiyor / hata mesajı alıyor";
            case "NO_ALERTS"        -> "Alarm e-postası ya da bildirim gelmiyor";
            case "FALSE_ALERTS"     -> "Gereksiz ya da yanlış alarm";
            case "ACCESS"           -> "Yetki / ekrana erişim sorunu";
            case "MOBILE"           -> "Telefonda / tablette düzgün görünmüyor";
            case "REPORT_EXPORT"    -> "Rapor ya da dışa aktarma sorunu";
            case "FEATURE_REQUEST"  -> "Yeni özellik isteği";
            case "OTHER"            -> "Diğer";
            default                 -> code;
        };
    }

    private static String labelForCategory(String category) {
        return switch (category) {
            case "BLOCKER"    -> "Engelliyor";
            case "ANNOYANCE"  -> "Rahatsız ediyor";
            case "SUGGESTION" -> "Öneri";
            case "DOMAIN_TRANSFER" -> "Alan adı aktarımı";   // 2026-09-28: envanter mükerrer kaydı → aktarım talebi
            default           -> category;
        };
    }

    /** Günlük özet (digest) — {@code site.monitor.issue-reports.daily-digest} açıkken tekil mailler yerine
     *  son 24 saatin USER_REPORT bildirimleri tek mailde. Satır başına referans + kullanıcı + özet. */
    public LoginIssueMailResult sendIssueDigest(String to, List<Map<String, String>> items, String periodLabel, boolean force) {
        String html = buildIssueDigestHtml(items, periodLabel);
        String subject = "[Site Monitor] 📝 Sorun Bildirimleri Özeti — " + items.size() + " yeni bildirim";
        String status = sendHtml(new String[]{ to }, null, subject, html, List.of(), force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    String buildIssueDigestHtml(List<Map<String, String>> items, String periodLabel) {
        MailDoc d = MailDoc.create("[Site Monitor] Sorun bildirimleri — günlük özet")
                .preheader(periodLabel + " döneminde " + items.size() + " bildirim alındı")
                .kicker("Sorun Bildirimi");
        d.badges(Badge.tint("GÜNLÜK ÖZET", Tone.INFO));
        d.title("Sorun bildirimleri — günlük özet", periodLabel + " döneminde " + items.size() + " bildirim alındı.");
        List<List<Cell>> rows = new ArrayList<>();
        for (Map<String, String> it : items) {
            rows.add(List.of(Cell.of(it.getOrDefault("refCode", "—")), Cell.of(it.getOrDefault("username", "—")),
                    Cell.of(it.getOrDefault("summary", ""))));
        }
        d.table(List.of(Col.nw("Referans"), Col.of("Kullanıcı"), Col.of("Özet")), rows);
        d.note("Ayrıntılar Yönetim > Sorun Bildirimleri ekranındadır.");
        d.footerMeta("Site Monitor — Sorun Bildirimi", "Bildirim: " + nowStamp());
        return d.html();
    }

    /** Bildiren kişiye "alındı" onayı — admin'e gidenle BENZER içerik (hata mesajı + görseller) +
     *  referans numarası. Best-effort (asla fırlatmaz). */
    public LoginIssueMailResult sendLoginIssueAck(String to, String refCode, String username, String errorText, String message,
                                    List<InlineImage> images, String reportedAt, boolean force) {
        if (to == null || to.isBlank()) return new LoginIssueMailResult("SKIPPED_NO_RECIPIENT", currentFrom(), null, null);
        List<InlineImage> inline = images != null ? images : List.of();
        // forReporter=true → e-posta satırı gösterilmez (kişi kendi adresini bilir); yine de tutarlılık için geçilir.
        String html = buildLoginIssueHtml(refCode, username, to, errorText, message, inline, null, null, reportedAt, true);
        String subject = "[Site Monitor] Sorun bildiriminiz alındı — " + refCode;
        String status = sendHtml(new String[]{ to }, null, subject, html, inline, force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    /**
     * Login sayfası (KİMLİKSİZ) "sorun bildir" akışında bildirene NÖTR "alındı" onayı (2026-10-08, ürün kararı).
     *
     * <p>Neden ayrı: alıcı adresi formdaki serbest alandır ve doğrulanmamıştır. Eski onay maili kullanıcının yazdığı
     * açıklamayı, hata metnini, kullanıcı adını ve en çok 5 ekran görüntüsünü KOPYALIYORDU — herkes kurumsal SMTP'den
     * istediği adrese istediği içeriği (oltalama) gönderebiliyordu. Bu mail yalnız sabit metin + sunucunun ürettiği
     * referans numarası + zaman taşır; kullanıcı girdisinden TEK karakter içermez. Yöneticiye giden bildirim tam içerikle
     * sürer. Oturumlu akışlar (cihaz geçmişi / uygulama içi bildirim) kendi adresine giden {@link #sendLoginIssueAck}'i
     * kullanmaya devam eder. Best-effort (asla fırlatmaz).
     */
    public LoginIssueMailResult sendLoginIssueAckNeutral(String to, String refCode, String reportedAt, boolean force) {
        if (to == null || to.isBlank()) return new LoginIssueMailResult("SKIPPED_NO_RECIPIENT", currentFrom(), null, null);
        String html = buildLoginIssueAckNeutralHtml(refCode, reportedAt);
        String subject = "[Site Monitor] Sorun bildiriminiz alındı — " + nzs(refCode);
        String status = sendHtml(new String[]{ to }, null, subject, html, List.of(), force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    /** Nötr onay gövdesi — yalnız referans + zaman; kullanıcı girdisi YOK (bkz. {@link #sendLoginIssueAckNeutral}). */
    String buildLoginIssueAckNeutralHtml(String refCode, String reportedAt) {
        MailDoc d = MailDoc.create("[Site Monitor] Sorun bildiriminiz alındı — " + nzs(refCode))
                .preheader("Bildiriminiz kaydedildi — referans " + nzs(refCode))
                .kicker("Sorun Bildirimi");
        d.badges(Badge.tint("ALINDI", Tone.SUCCESS));
        d.title("Sorun bildiriminiz alındı", "Bildiriminiz kaydedildi ve sistem yöneticilerine iletildi. Aşağıdaki referans "
                + "numarasıyla durumu takip edebilir, bizimle iletişimde bu numarayı belirtebilirsiniz.");
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Referans Numarası", MailKit.strong(refCode, null), nzs(refCode)));
        rows.add(Row.of("Bildirim Zamanı", formatIso(reportedAt)));
        d.keyValue(rows);
        d.note("Bu bildirimi siz göndermediyseniz bu e-postayı dikkate almayın. Bu e-posta Site Monitor tarafından otomatik "
                + "gönderilmiştir. Yanıtlamayınız.");
        d.footerMeta("Site Monitor — Sorun Bildirimi");
        return d.html();
    }

    /** "Çözüldü" bildirimi — hem bildiren kişiye (To) hem sistem yöneticisine (CC) gider. Best-effort.
     *  Zenginleştirilmiş: bildirim zamanı + orijinal sorun (hata + açıklama) + ekran görüntüleri (CID) +
     *  çözüm notu, yeşil "çözümlendi" başlığıyla. */
    public LoginIssueMailResult sendLoginIssueResolved(String reporterEmail, String adminEmail, String refCode,
                                         String username, String errorText, String message, String reportedAt,
                                         String resolutionNote, String resolvedAt, List<InlineImage> images, boolean force) {
        String to = (reporterEmail != null && !reporterEmail.isBlank()) ? reporterEmail : null;
        String cc = (adminEmail != null && !adminEmail.isBlank()) ? adminEmail : null;
        if (to == null && cc == null) return new LoginIssueMailResult("SKIPPED_NO_RECIPIENT", currentFrom(), null, null);
        // Alıcı yalnız admin ise onu To yap (boş To olmasın).
        String[] toArr = to != null ? new String[]{ to } : new String[]{ cc };
        String[] ccArr = (to != null && cc != null) ? new String[]{ cc } : null;
        List<InlineImage> inline = images != null ? images : List.of();

        String html = buildLoginIssueResolvedHtml(refCode, username, reporterEmail, errorText, message, reportedAt,
                resolutionNote, resolvedAt, inline);
        String subject = "[Site Monitor] ✅ Giriş sorunu çözümlendi — " + refCode;
        String status = sendHtml(toArr, ccArr, subject, html, inline, force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    String buildLoginIssueResolvedHtml(String refCode, String username, String reporterEmail, String errorText,
                                       String message, String reportedAt, String resolutionNote, String resolvedAt,
                                       List<InlineImage> inline) {
        MailDoc d = MailDoc.create("[Site Monitor] Giriş sorunu çözümlendi — " + nzs(refCode))
                .preheader(nzs(refCode) + " referans numaralı giriş sorunu bildirimi çözümlendi.")
                .kicker("Sorun Bildirimi");
        d.badges(Badge.tint("ÇÖZÜMLENDİ", Tone.SUCCESS));
        d.titleHtml("Sorun çözümlendi", "<strong>" + esc(refCode) + "</strong> referans numaralı giriş sorunu bildirimi çözümlenmiştir.",
                nzs(refCode) + " referans numaralı giriş sorunu bildirimi çözümlenmiştir.");
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Referans Numarası", MailKit.strong(refCode, null), nzs(refCode)));
        String uname = username != null && !username.isBlank() ? username : "—";
        rows.add(new Row("Kullanıcı Adı", MailKit.strong(uname, null), uname));
        if (reporterEmail != null && !reporterEmail.isBlank()) rows.add(Row.of("E-posta", reporterEmail));
        rows.add(Row.of("Bildirim Zamanı", formatIso(reportedAt)));
        rows.add(new Row("Çözülme Zamanı", MailKit.strong(formatIso(resolvedAt), Tone.SUCCESS.strong), formatIso(resolvedAt)));
        d.keyValue(rows);
        // Çözüm notu — okuyanın asıl merak ettiği bilgi, orijinal sorundan ÖNCE.
        if (resolutionNote != null && !resolutionNote.isBlank()) {
            d.alert(Tone.SUCCESS, "Çözüm Notu", resolutionNote);
        }
        // Orijinal sorun — alınan hata mesajı (varsa) + iletilen açıklama.
        d.pre("Alınan Hata Mesajı", errorText);
        if (message != null && !message.isBlank()) {
            d.card("İletilen Açıklama", null, MailKit.paragraph(MailKit.escBr(message)), message);
        }
        screenshots(d, inline, true);
        d.note("Sorun devam ediyorsa lütfen tekrar bildiriniz. Bu e-posta otomatik gönderilmiştir.");
        d.footerMeta("Site Monitor — Sorun Bildirimi");
        return d.html();
    }

    // ── Konuşma dizisi (2026-09-26): yönetici yanıtı / durum geçişi → bildirene KISA mail ──────────

    /** Bildirenin görebileceği yönetici yanıtı — kısa mail: referans + durum + yanıt metni + "bildirimi aç" düğmesi. */
    public LoginIssueMailResult sendIssueReply(String to, Long reportId, String refCode, String username, String status,
                                               String replyBody, String repliedAt, String messageSummary, boolean force) {
        if (to == null || to.isBlank()) return new LoginIssueMailResult("SKIPPED_NO_RECIPIENT", currentFrom(), null, null);
        String html = buildIssueReplyHtml(reportId, refCode, username, status, replyBody, repliedAt, messageSummary);
        String subject = "[Site Monitor] Bildiriminize yanıt verildi — " + nzs(refCode);
        String status0 = sendHtml(new String[]{ to }, null, subject, html, List.of(), force);
        return new LoginIssueMailResult(status0, currentFrom(), subject, html);
    }

    /** Durum geçişi (OPEN ↔ IN_PROGRESS; RESOLVED ayrı zengin maille gider) — kısa mail. */
    public LoginIssueMailResult sendIssueStatusChange(String to, Long reportId, String refCode, String username, String newStatus,
                                                      String note, String changedAt, String messageSummary, boolean force) {
        if (to == null || to.isBlank()) return new LoginIssueMailResult("SKIPPED_NO_RECIPIENT", currentFrom(), null, null);
        String html = buildIssueStatusHtml(reportId, refCode, username, newStatus, note, changedAt, messageSummary);
        String subject = "[Site Monitor] Bildiriminizin durumu güncellendi — " + nzs(refCode);
        String status0 = sendHtml(new String[]{ to }, null, subject, html, List.of(), force);
        return new LoginIssueMailResult(status0, currentFrom(), subject, html);
    }

    String buildIssueReplyHtml(Long reportId, String refCode, String username, String status,
                               String replyBody, String repliedAt, String messageSummary) {
        MailDoc d = MailDoc.create("[Site Monitor] Bildiriminize yanıt verildi — " + nzs(refCode))
                .preheader(nzs(refCode) + " referans numaralı bildiriminize yönetici yanıt yazdı.")
                .kicker("Sorun Bildirimi");
        d.badges(Badge.tint("YANIT", Tone.INFO), issueStatusBadge(status));
        d.titleHtml("Bildiriminize yanıt verildi",
                "<strong>" + esc(refCode) + "</strong> referans numaralı bildiriminize bir yanıt yazıldı. "
                        + "Yanıtınızı uygulamadaki bildirim sayfasından yazabilirsiniz.",
                nzs(refCode) + " referans numaralı bildiriminize bir yanıt yazıldı. Yanıtınızı uygulamadaki bildirim sayfasından yazabilirsiniz.");
        d.keyValue(issueRows(refCode, username, status, repliedAt, "Yanıt Zamanı"));
        d.card("Yanıt", null, MailKit.paragraph(MailKit.escBr(replyBody)), nzs(replyBody));
        issueSummary(d, messageSummary);
        d.button(issueUrl(reportId), "Bildirimi aç");
        d.note("Bu e-postayı yanıtlamayın — yanıtınızı uygulamadaki konuşma alanına yazın. Bu e-posta otomatik gönderilmiştir.");
        d.footerMeta("Site Monitor — Sorun Bildirimi");
        return d.html();
    }

    String buildIssueStatusHtml(Long reportId, String refCode, String username, String newStatus,
                                String note, String changedAt, String messageSummary) {
        String label = issueStatusLabel(newStatus);
        MailDoc d = MailDoc.create("[Site Monitor] Bildiriminizin durumu güncellendi — " + nzs(refCode))
                .preheader(nzs(refCode) + " referans numaralı bildiriminizin durumu: " + label + ".")
                .kicker("Sorun Bildirimi");
        d.badges(issueStatusBadge(newStatus));
        d.titleHtml("Bildiriminizin durumu güncellendi",
                "<strong>" + esc(refCode) + "</strong> referans numaralı bildiriminiz artık <strong>" + esc(label) + "</strong> durumunda.",
                nzs(refCode) + " referans numaralı bildiriminiz artık " + label + " durumunda.");
        d.keyValue(issueRows(refCode, username, newStatus, changedAt, "Güncelleme Zamanı"));
        if (note != null && !note.isBlank()) d.alert(Tone.INFO, "Çalışma Notu", note);
        issueSummary(d, messageSummary);
        d.button(issueUrl(reportId), "Bildirimi aç");
        d.note("Gelişmeleri uygulamadaki bildirim sayfasından takip edebilir, soru ya da ek bilginizi oraya yazabilirsiniz. Bu e-posta otomatik gönderilmiştir.");
        d.footerMeta("Site Monitor — Sorun Bildirimi");
        return d.html();
    }

    private List<Row> issueRows(String refCode, String username, String status, String at, String atLabel) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Referans Numarası", MailKit.strong(refCode, null), nzs(refCode)));
        String uname = username != null && !username.isBlank() ? username : "—";
        rows.add(new Row("Kullanıcı Adı", MailKit.strong(uname, null), uname));
        rows.add(new Row("Durum", MailKit.strong(issueStatusLabel(status), issueStatusTone(status).strong), issueStatusLabel(status)));
        rows.add(Row.of(atLabel, formatIso(at)));
        return rows;
    }

    private static void issueSummary(MailDoc d, String messageSummary) {
        if (messageSummary != null && !messageSummary.isBlank()) {
            d.card("Bildiriminiz", null, MailKit.paragraph(MailKit.escBr(messageSummary)), messageSummary);
        }
    }

    /** CANLI base-url'den bildirim sayfasına derin bağlantı (?tab=login-issues&ir_id=<id>); base-url yoksa düğme çizilmez. */
    private String issueUrl(Long reportId) {
        String base = liveBaseUrl();
        return base.isEmpty() || reportId == null ? "" : base + "/?tab=login-issues&ir_id=" + reportId;
    }

    private static String issueStatusLabel(String status) {
        if (status == null) return "—";
        return switch (status) { case "IN_PROGRESS" -> "İşlemde"; case "RESOLVED" -> "Çözümlendi"; case "OPEN" -> "Açık"; default -> status; };
    }

    private static Tone issueStatusTone(String status) {
        return "RESOLVED".equals(status) ? Tone.SUCCESS : Tone.WARNING;
    }

    private static Badge issueStatusBadge(String status) {
        return Badge.tint(issueStatusLabel(status).toUpperCase(java.util.Locale.forLanguageTag("tr-TR")), issueStatusTone(status));
    }

    // NOT: login-issue mailleri artık LoginIssueMailService üzerinden ASYNC gönderilir + login_issue_mail_logs'a
    // loglanır (mail geçmişi). Eski buradaki @Async sarmalayıcılar oraya taşındı.

    /** Login sorun bildirimi HTML'i — admin (forReporter=false: IP/UA + kimliksiz uyarısı) ve bildiren
     *  (forReporter=true: takip metni, IP/UA gizli) için ortak; her ikisinde referans no + hata + görseller. */
    String buildLoginIssueHtml(String refCode, String username, String reporterEmail, String errorText, String message,
                               List<InlineImage> images,
                               String clientIp, String userAgent, String reportedAt, boolean forReporter) {
        MailDoc d = MailDoc.create(forReporter ? "[Site Monitor] Sorun bildiriminiz alındı — " + nzs(refCode)
                        : "[Site Monitor] Giriş sorunu bildirimi — " + nzs(refCode))
                .preheader(forReporter ? "Bildiriminiz kaydedildi — referans " + nzs(refCode)
                        : "Bir kullanıcı login sayfasından giriş sorunu bildirdi — " + nzs(refCode))
                .kicker("Sorun Bildirimi");
        if (forReporter) {
            d.badges(Badge.tint("ALINDI", Tone.SUCCESS));
            d.title("Sorun bildiriminiz alındı", "Bildiriminiz kaydedildi. Aşağıdaki referans numarasıyla durumu takip edebilir, "
                    + "bizimle iletişimde bu numarayı belirtebilirsiniz. Sorun çözüldüğünde bu e-posta adresine bilgi verilecektir.");
        } else {
            d.badges(Badge.tint("GİRİŞ SORUNU", Tone.WARNING));
            d.title("Giriş sorunu bildirimi", "Bir kullanıcı login sayfasından giriş sorunu bildirdi.");
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Referans Numarası", MailKit.strong(refCode, null), nzs(refCode)));
        rows.add(new Row("Kullanıcı Adı", MailKit.strong(username, null), nzs(username)));
        // Bildirenin e-posta adresi — admin varyantında göster (yöneticinin iletişim için ihtiyacı var).
        if (!forReporter && reporterEmail != null && !reporterEmail.isBlank()) rows.add(Row.of("E-posta", reporterEmail));
        rows.add(Row.of("Bildirim Zamanı", formatIso(reportedAt)));
        if (!forReporter) {
            rows.add(Row.of("IP Adresi", clientIp != null ? clientIp : "—"));
            rows.add(Row.of("Tarayıcı", userAgent != null ? userAgent : "—"));
        }
        d.keyValue(rows);
        d.pre("Alınan Hata Mesajı", errorText);
        d.card(forReporter ? "İlettiğiniz Açıklama" : "Kullanıcının Açıklaması", null,
                MailKit.paragraph(MailKit.escBr(message)), nzs(message));
        screenshots(d, images, true);
        if (forReporter) {
            d.note("Bu e-posta Site Monitor tarafından otomatik gönderilmiştir. Yanıtlamayınız.");
        } else {
            d.alert(Tone.WARNING, null, "Bu bildirim login sayfasındaki \"sorun bildir\" bağlantısından, kimlik doğrulaması yapılmadan "
                    + "gönderilmiştir — içeriği buna göre değerlendirin.");
        }
        d.footerMeta("Site Monitor — Sorun Bildirimi");
        return d.html();
    }

    /** Ekran görüntüleri (CID inline) — akışkan görsel, içerik genişliğine sığar. */
    private static void screenshots(MailDoc d, List<InlineImage> images, boolean captions) {
        if (images == null || images.isEmpty()) return;
        d.heading("Ekran Görüntüleri (" + images.size() + ")");
        int i = 1;
        for (InlineImage img : images) {
            if (captions) d.note("Görsel " + i);
            d.image("cid:" + img.cid(), "Ekran görüntüsü " + i, d.contentWidth());
            i++;
        }
    }

    // ── Alarm / çözüm şablon seçimi ──────────────────────────────────────────

    private static final java.util.Set<String> MONITORING_OUTAGE_TYPES =
            java.util.Set.of("ACCESSIBILITY", "PORT_DOWN", "DNS_FAILURE");

    /**
     * Alarm e-postası (HTML + düz metin). Süre-bitişi / kusur ailesi (alan adı, sertifika, sayfa,
     * sentetik…) {@link EmailTemplateBuilder}'dan; izleme-kesintisi türleri (uptime/port/dns/keyword/
     * ping/dns-changed) tür-özel belgelerinden; alan adı olmayan jenerik bildirim sade belgeden geçer.
     */
    MailDoc.Mail alertMail(String subject, String message, String domain, String level, String alertType,
                           Integer daysRemaining, Map<String, Object> ctx) {
        // Runbook notu (2026-10-01): tür-özel belgelerde de gövdenin SONUNA (RunbookNote.appendTo — rehber yoksa no-op).
        if (alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType)) {
            return withRunbook(outageAlertDoc(message, domain, alertType, level, ctx), ctx).build();
        }
        if ("KEYWORD".equals(alertType)) return withRunbook(keywordAlertDoc(message, domain, level, ctx), ctx).build();
        if ("PING_DOWN".equals(alertType)) return withRunbook(pingAlertDoc(message, domain, level, ctx), ctx).build();
        if ("DNS_CHANGED".equals(alertType)) return withRunbook(dnsChangedDoc(message, domain, level, ctx), ctx).build();
        if (domain == null) {
            // Ton konu metninden TAHMİN EDİLMEZ (eski "KRİTİK geçiyor mu" taraması); seviye açıkça verilir.
            Tone tone = EmailTemplateBuilder.severityTone(level);
            return simpleDoc(subject, message, tone, level == null ? "BİLDİRİM" : EmailTemplateBuilder.severityLabel(level),
                    true, null, null).build();
        }
        EmailTemplateBuilder.AlertMail m = new EmailTemplateBuilder.AlertMail(
                alertType, level, domain, message, daysRemaining, ctx, teamNameOf(ctx));
        return new MailDoc.Mail(templateBuilder.buildHtml(m), templateBuilder.buildText(m));
    }

    /** Alarm belgesinin sonuna runbook notu ("Ne yapılmalı") — bağlamda rehber yoksa belge DEĞİŞMEZ. */
    private static MailDoc withRunbook(MailDoc d, Map<String, Object> ctx) {
        com.sitemonitor.service.mail.RunbookNote.appendTo(d, ctx);
        return d;
    }

    public String buildAlertEmailHtml(String subject, String message,
                                       String domain, String level, String alertType,
                                       Integer daysRemaining, Map<String, Object> certContext) {
        return alertMail(subject, message, domain, level, alertType, daysRemaining, certContext).html();
    }

    /** Alarm e-postasının plain-text (multipart) alternatifi — HTML ile AYNI belgeden. */
    public String buildAlertEmailText(String subject, String message,
                                      String domain, String level, String alertType,
                                      Integer daysRemaining, Map<String, Object> certContext) {
        return alertMail(subject, message, domain, level, alertType, daysRemaining, certContext).text();
    }

    /** Çözüm e-postası (HTML + düz metin) — alarm ailesiyle aynı ayrım. */
    MailDoc.Mail resolutionMail(String domain, String alertType, String alertLevel, Integer daysRemaining,
                                String resolvedBy, String resolvedAt, String createdAt, Map<String, Object> ctx,
                                String teamNames, UptimeSummary uptime) {
        if ("KEYWORD".equals(alertType)) return keywordResolvedDoc(domain, ctx, alertLevel, resolvedBy, resolvedAt, createdAt, teamNames, uptime).build();
        if ("PING_DOWN".equals(alertType)) return pingResolvedDoc(domain, ctx, alertLevel, resolvedBy, resolvedAt, createdAt, teamNames, uptime).build();
        if ((alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType)) || "DNS_CHANGED".equals(alertType)) {
            return monitoringResolvedDoc(domain, alertType, alertLevel, resolvedBy, resolvedAt, createdAt, teamNames, uptime, ctx).build();
        }
        // domain + sertifika → "çözüldü" (yenilenen bitiş/registrar bağlamıyla zenginleştirilir)
        return new MailDoc.Mail(
                templateBuilder.buildResolvedHtml(domain, alertType, resolvedBy, resolvedAt, createdAt, ctx, teamNames),
                templateBuilder.buildResolvedText(domain, alertType, resolvedBy, resolvedAt, createdAt, ctx));
    }

    public String buildResolutionEmailHtml(String domain, String alertType, String alertLevel,
                                            Integer daysRemaining, String resolvedBy,
                                            String resolvedAt, String createdAt,
                                            Map<String, Object> certContext) {
        return buildResolutionEmailHtml(domain, alertType, alertLevel, daysRemaining, resolvedBy,
                resolvedAt, createdAt, certContext, null, null);
    }

    /** Şeffaflık bloğu (teamNames) + erişilebilirlik özeti (uptime, yalnız HTTP uptime tiplerinde) ile zenginleştirilmiş. */
    public String buildResolutionEmailHtml(String domain, String alertType, String alertLevel,
                                            Integer daysRemaining, String resolvedBy,
                                            String resolvedAt, String createdAt,
                                            Map<String, Object> certContext,
                                            String teamNames, UptimeSummary uptime) {
        return resolutionMail(domain, alertType, alertLevel, daysRemaining, resolvedBy, resolvedAt, createdAt,
                certContext, teamNames, uptime).html();
    }

    /** Çözüm e-postasının plain-text (multipart) alternatifi. */
    public String buildResolutionEmailText(String domain, String alertType, String resolvedBy, String resolvedAt) {
        return buildResolutionEmailText(domain, alertType, resolvedBy, resolvedAt, null, null);
    }

    /** Çözüm plain-text — HTML ile aynı belgeden (zengin türlerde tür-özel metin). */
    public String buildResolutionEmailText(String domain, String alertType, String resolvedBy, String resolvedAt,
                                           String createdAt, Map<String, Object> certContext) {
        return resolutionMail(domain, alertType, null, null, resolvedBy, resolvedAt, createdAt, certContext, null, null).text();
    }

    private static String teamNameOf(Map<String, Object> ctx) {
        if (ctx == null) return null;
        Object v = ctx.get("team_name");
        return v == null ? null : String.valueOf(v);
    }

    // ── Sade bildirim (alan adı olmayan alarm, haftalık rapor onay/iade) ──────

    /**
     * Sade bildirim belgesi. Ton ÇAĞIRANDAN gelir (eski sürüm konu metninde "KRİTİK" arayıp
     * başlık rengini tahmin ediyordu). Mesajın satır sonları korunur ({@code <br>}) — iade notu
     * gibi çok satırlı metinler tek paragrafa ezilmez.
     */
    private MailDoc simpleDoc(String subject, String message, Tone tone, String badge, boolean asAlert,
                              String ctaUrl, String ctaLabel) {
        String title = subject == null ? "Bildirim" : subject.replaceFirst("^\\[Site Monitor[^\\]]*\\]\\s*", "");
        MailDoc d = MailDoc.create(subject).preheader(firstLine(message)).kicker("Bildirim");
        if (badge != null) d.badges(tone == Tone.DESTRUCTIVE ? Badge.solid(badge, tone) : Badge.tint(badge, tone));
        d.title(title, null);
        if (asAlert) d.alert(tone, null, message);
        else d.paragraph(message);
        if (ctaUrl != null && !ctaUrl.isBlank()) d.button(ctaUrl, ctaLabel);
        d.footerMeta("Site Monitor", nowStamp());
        return d;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String t = s.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 140 ? line.substring(0, 139) + "…" : line;
    }

    // ── İzleme kesintisi alarmı — ACCESSIBILITY / PORT_DOWN / DNS_FAILURE ─────

    /**
     * İzleme kesintisi alarm belgesi — üç tür için ortak; etiketler tipe göre çözülür. ctx,
     * MonitoringOutageService'in ürettiği kesinti bağlamıdır (port/protocol/record_type,
     * first_failure_at, last_error, confirm_attempts, confirm_delay_ms, confirm_attempt_count).
     * "Tekrar Bildir" yolu eksik context geçirebileceğinden TÜM okumalar null-toleranslıdır.
     */
    private MailDoc outageAlertDoc(String message, String domain, String alertType, String level, Map<String, Object> ctx) {
        String kicker = switch (alertType) {
            case "PORT_DOWN"   -> "Port İzleme";
            case "DNS_FAILURE" -> "DNS İzleme";
            default            -> "Erişilebilirlik İzleme";
        };
        String heroTitle = switch (alertType) {
            case "PORT_DOWN"   -> "Port Erişilemez";
            case "DNS_FAILURE" -> "DNS Çözülemiyor";
            default            -> "Site Erişilemez";
        };
        String typeTrLabel = switch (alertType) {
            case "PORT_DOWN"   -> "Port Kesintisi";
            case "DNS_FAILURE" -> "DNS Çözümleme Hatası";
            default            -> "Erişim Kesintisi";
        };
        String accessLabel = switch (alertType) {
            case "PORT_DOWN"   -> "Port";
            case "DNS_FAILURE" -> "DNS Çözümleme";
            default            -> "Erişim";
        };

        String port           = ctxStr(ctx, "port");
        String protocol       = ctxStr(ctx, "protocol");
        String recordType     = ctxStr(ctx, "record_type");
        String firstFailureAt = ctxStr(ctx, "first_failure_at");
        String lastError      = ctxStr(ctx, "last_error");
        String endpoint = "DNS_FAILURE".equals(alertType)
                ? nzs(domain) + (!recordType.isEmpty() ? " · " + recordType + " kaydı" : "")
                : nzs(domain) + (!port.isEmpty() ? ":" + port : "");
        List<Map<String, Object>> attempts = attempts(ctx);

        MailDoc d = MailDoc.create("[Site Monitor] " + EmailTemplateBuilder.severityLabel(level) + " · " + endpoint)
                .preheader(heroTitle + " — " + endpoint)
                .kicker(kicker);
        d.badges(EmailTemplateBuilder.severityBadge(level), Badge.outline(typeTrLabel));
        d.title(endpoint, null);
        d.alert(EmailTemplateBuilder.severityTone(level), heroTitle, message);   // D-c8: kutu tonu seviyeden (UYARI mavi)

        List<Row> rows = new ArrayList<>();
        rows.add(Row.of("Uç Nokta", endpoint));
        if ("PORT_DOWN".equals(alertType) && !protocol.isEmpty()) rows.add(Row.of("Protokol", protocol));
        rows.add(new Row(accessLabel, MailKit.strong("Erişilemiyor", Tone.DESTRUCTIVE.strong), "Erişilemiyor"));
        if (!firstFailureAt.isEmpty()) rows.add(Row.of("İlk Hata", formatIsoFull(firstFailureAt)));
        if (!lastError.isEmpty()) rows.add(Row.of("Son Hata", lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError));
        rows.add(verificationRow(ctx, attempts, "deneme başarısız"));
        rows.add(levelRow(level));
        rows.add(new Row("İzleme", MailKit.strong("Devam ediyor", Tone.SUCCESS.strong), "Devam ediyor"));
        d.keyValue("Kesinti Bilgileri", rows);
        attemptTable(d, attempts, "yanıt yok");
        d.note("Sorun düzeldiğinde bu alarm otomatik kapatılır ve çözüm e-postası gönderilir. "
                + "Alarmı Site Monitor → Uyarılar → Alarm Geçmişi ekranından onaylayabilir veya kapatabilirsiniz.");
        // E-posta CTA: port/dns alarmında monitör detay deep-link'i (?tab=&monitor=<id>);
        // accessibility (uptime) monitör-id taşımaz → CTA basılmaz, olay aksiyonları yine görünür.
        String outageTab = "PORT_DOWN".equals(alertType) ? "port" : "DNS_FAILURE".equals(alertType) ? "dns" : null;
        if (outageTab != null) d.button(monitorCtaUrl(outageTab, ctx), "Monitörü Aç →");
        MailCta.appendIncidentActions(d, liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"));
        d.footerWhy(teamNameOf(ctx)).footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attempts(Map<String, Object> ctx) {
        return (ctx != null && ctx.get("confirm_attempts") instanceof List<?> l) ? (List<Map<String, Object>>) l : List.of();
    }

    /** "Doğrulama" satırı: "3/3 deneme başarısız · 30 sn arayla" (deneme listesi yoksa sayaç + "Başarısız"). */
    private Row verificationRow(Map<String, Object> ctx, List<Map<String, Object>> attempts, String failWord) {
        String ac = ctxStr(ctx, "confirm_attempt_count");
        String delayMs = ctxStr(ctx, "confirm_delay_ms");
        long delaySec = 0;
        try { delaySec = delayMs.isEmpty() ? 0 : Long.parseLong(delayMs) / 1000; } catch (NumberFormatException ignore) { }
        String base = !attempts.isEmpty() ? attempts.size() + "/" + attempts.size() + " " + failWord
                : (!ac.isEmpty() ? ac + " ardışık deneme — tümü başarısız" : "Başarısız");
        String text = base + (delaySec > 0 ? " · " + delaySec + " sn arayla" : "");
        return new Row("Doğrulama", MailKit.strong(text, Tone.DESTRUCTIVE.strong), text);
    }

    /** Teyit denemeleri tablosu (Deneme / Zaman / Sonuç). */
    private void attemptTable(MailDoc d, List<Map<String, Object>> attempts, String noAnswer) {
        if (attempts.isEmpty()) return;
        List<List<Cell>> rows = new ArrayList<>();
        for (Map<String, Object> a : attempts) {
            String at = String.valueOf(a.getOrDefault("checked_at", ""));
            String err = String.valueOf(a.getOrDefault("error", ""));
            String result = !err.isEmpty() && !"null".equals(err) ? (err.length() > 60 ? err.substring(0, 60) + "…" : err) : noAnswer;
            rows.add(List.of(Cell.of("Deneme " + a.getOrDefault("attempt", "?")), Cell.of(formatIso(at)),
                    Cell.of(result, Tone.DESTRUCTIVE.text, false)));
        }
        d.heading("Doğrulama Denemeleri");
        d.table(List.of(Col.nw("Deneme"), Col.nw("Zaman"), Col.of("Sonuç")), rows);
    }

    // ── İzleme çözümü — ACCESSIBILITY / PORT_DOWN / DNS_FAILURE / DNS_CHANGED ──

    /** İzleme çözüm belgesi — süre createdAt→resolvedAt'ten hesaplanır; etiketler tipe göre. */
    private MailDoc monitoringResolvedDoc(String domain, String alertType, String level, String resolvedBy,
                                          String resolvedAt, String createdAt,
                                          String teamNames, UptimeSummary uptime,
                                          Map<String, Object> ctx) {
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)";
        String duration = outageDurationDisplay(createdAt, resolvedAt);
        boolean dnsChanged = "DNS_CHANGED".equals(alertType);
        String t = alertType != null ? alertType : "";

        String kicker = switch (t) {
            case "PORT_DOWN"                  -> "Port İzleme";
            case "DNS_FAILURE", "DNS_CHANGED" -> "DNS İzleme";
            default                           -> "Erişilebilirlik İzleme";
        };
        String heroLine = switch (t) {
            case "PORT_DOWN"   -> "Port Yeniden Açıldı";
            case "DNS_FAILURE" -> "DNS Çözümleme Düzeldi";
            case "DNS_CHANGED" -> "DNS Değişikliği Alarmı Kapatıldı";
            default            -> "Erişim Yeniden Sağlandı";
        };
        String typeTrLabel = switch (t) {
            case "PORT_DOWN"   -> "Port Kesintisi";
            case "DNS_FAILURE" -> "DNS Çözümleme Hatası";
            case "DNS_CHANGED" -> "DNS Değişikliği";
            default            -> "Erişim Kesintisi";
        };
        String durationLabel = dnsChanged ? "Alarm Süresi" : "Toplam Kesinti Süresi";
        // Kesinti başlangıç→bitiş net çifti (StatusCake gibi); DNS-changed'de "alarm" terminolojisi korunur.
        String startLabel = dnsChanged ? "Alarm Başlangıcı" : "Kesinti Başlangıcı";
        String endLabel   = dnsChanged ? "Çözülme Zamanı"   : "Yeniden Ulaşılabilir";

        MailDoc d = MailDoc.create("[Site Monitor] ÇÖZÜLDÜ · " + nzs(domain))
                .preheader(heroLine + " — " + nzs(domain) + " · " + duration)
                .kicker(kicker);
        d.badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS), Badge.outline(typeTrLabel));
        d.title(nzs(domain), "Alarm kapatıldı, izleme devam ediyor.");
        d.alertHtml(Tone.SUCCESS, esc(heroLine),
                "<strong>" + esc(domain) + "</strong> için açık olan <strong>" + esc(typeTrLabel) + "</strong> alarmı kapatıldı. "
                        + (dnsChanged ? "Alarm süresi: " : "Toplam kesinti süresi: ") + "<strong>" + esc(duration) + "</strong>.",
                heroLine + " — " + nzs(domain) + " için açık olan " + typeTrLabel + " alarmı kapatıldı. "
                        + (dnsChanged ? "Alarm süresi: " : "Toplam kesinti süresi: ") + duration + ".");
        d.keyValue("Çözüm Bilgisi", MailDoc.rows(
                Row.of("Çözen", by),
                Row.of(endLabel, fmtOrDash(formatIstanbul(resolvedAt))),
                Row.of(startLabel, fmtOrDash(formatIstanbul(createdAt))),
                new Row(durationLabel, MailKit.strong(duration, Tone.SUCCESS.strong), duration),
                Row.of("Alarm Tipi", typeTrLabel),
                levelRow(level)));
        uptimeSummary(d, uptime);
        MailCta.appendIncidentActions(d, liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"));
        d.footerWhy(teamNames).footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d;
    }

    /**
     * O-b1 / D-2 (2026-09-29): "Seviye" satırı alarmın GERÇEK seviyesinden — tek sözlük
     * {@link EscalationService#levelWordTr} (rozetle, konuyla, ileti gövdesiyle ve push ile aynı sözcük). Eskiden
     * izleme e-postalarında sabit "KRİTİK" (DNS değişikliğinde "YÜKSEK") yazıyordu; WARNING alarmda gövde "UYARI:"
     * derken rozet ve bu satır "KRİTİK" diyordu.
     */
    private static Row levelRow(String level) {
        String w = EmailTemplateBuilder.severityLabel(level);
        return new Row("Seviye", MailKit.strong(w, EmailTemplateBuilder.severityTone(level).strong), w);
    }

    /** Erişilebilirlik özeti (son 24s/7g uptime% + kesinti sayısı) — veri yoksa hiç eklenmez. */
    private void uptimeSummary(MailDoc d, UptimeSummary u) {
        if (u == null || u.pct24h() == null) return;
        String p24 = String.format(java.util.Locale.US, "%.2f", u.pct24h());
        String p7 = u.pct7d() != null ? String.format(java.util.Locale.US, "%.2f", u.pct7d()) : "—";
        d.keyValue("Erişilebilirlik Özeti", MailDoc.rows(
                Row.of("Son 24 saat", p24 + "% uptime · " + u.outages24h() + " kesinti"),
                Row.of("Son 7 gün", p7 + "% uptime · " + u.outages7d() + " kesinti")));
    }

    // ── Alarm Fırtınası (Alert Storm) — çok monitör birden düştüğünde TEK toplu bildirim ──

    /** CANLI base-url (sondaki bölü işaretleri atılmış); @Value yalnız fallback. */
    private String liveBaseUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        return (url == null || url.isBlank()) ? "" : url.replaceAll("/+$", "");
    }

    /** CANLI base-url'den olay (incidents) ekranına deep-link — reminder/approve/incident ile AYNI kaynak. */
    private String stormCtaUrl() {
        String base = liveBaseUrl();
        return base.isEmpty() ? "" : base + "/?tab=incidents";
    }

    /** Duz-metin partlarinda satir sonu. Kacissiz sabit: kaynak uretimi sirasinda "\n" kacisinin
     *  cokmesi bu dosyayi bir kez derlenemez hale getirdi; Character.toString(10) o riski kaldirir. */
    private static final String NL = Character.toString(10);

    /** Fırtına alarmının düz-metin karşılığı (multipart/alternative ikinci partı). */
    public String buildStormAlertText(int monitorCount, String scopeLabel, String rootCauseLabel,
                                      String startedAt, List<String> sampleTargets, int truncatedExtra) {
        return buildStormAlertText(monitorCount, scopeLabel, rootCauseLabel, startedAt, sampleTargets, truncatedExtra, "CRITICAL");
    }

    /** D-c7: HTML partıyla aynı seviye sözcüğü. */
    public String buildStormAlertText(int monitorCount, String scopeLabel, String rootCauseLabel,
                                      String startedAt, List<String> sampleTargets, int truncatedExtra, String level) {
        StringBuilder sb = new StringBuilder();
        sb.append("ALARM FIRTINASI — ").append(monitorCount).append(" monitör birden erişilemez").append(NL).append(NL);
        sb.append("Seviye: ").append(EmailTemplateBuilder.severityLabel(level)).append(NL);
        sb.append("Kapsam: ").append(nz(scopeLabel)).append(NL);
        sb.append("Ortak kök-neden: ").append(nz(rootCauseLabel)).append(NL);
        sb.append("Başlangıç: ").append(fmtOrDash(formatIstanbul(startedAt))).append(NL);
        sb.append(stormTargetsText(sampleTargets, truncatedExtra, "ETKİLENEN MONİTÖRLER"));
        String cta = stormCtaUrl();
        if (!cta.isBlank()) sb.append(NL).append("Olayları aç: ").append(cta).append(NL);
        return sb.toString();
    }

    /** Fırtına çözümünün düz-metin karşılığı. */
    public String buildStormRecoveryText(int recoveredCount, int stillDownCount, String scopeLabel,
                                         String startedAt, String resolvedAt,
                                         List<String> sampleTargets, int truncatedExtra,
                                         List<String> stillDownTargets) {
        StringBuilder sb = new StringBuilder();
        sb.append("ÇÖZÜLDÜ — alarm fırtınası sona erdi (").append(recoveredCount)
          .append(" monitör kurtarıldı)").append(NL).append(NL);
        sb.append("Kapsam: ").append(nz(scopeLabel)).append(NL);
        sb.append("Başlangıç: ").append(fmtOrDash(formatIstanbul(startedAt))).append(NL);
        sb.append("Çözülme: ").append(fmtOrDash(formatIstanbul(resolvedAt))).append(NL);
        sb.append("Toplam süre: ").append(formatOutageDuration(startedAt, resolvedAt)).append(NL);
        sb.append(stormTargetsText(sampleTargets, truncatedExtra, "KURTARILAN MONİTÖRLER"));
        if (stillDownCount > 0) {
            sb.append(NL).append("HÂLÂ ERİŞİLEMEYEN (").append(stillDownCount).append("):").append(NL);
            // Kırpma sayacı HTML partıyla aynı hesap: liste sampleTargets ile 12'de kesiliyor,
            // düz metin sabit 0 geçtiği için "+ N monitör daha" satırı düşüyordu — aynı mailin
            // iki partı çelişiyor, düz metne düşen istemci 12 adı TAM liste sanıyordu.
            sb.append(stormTargetsText(stillDownTargets,
                    Math.max(0, stillDownCount - (stillDownTargets == null ? 0 : stillDownTargets.size())), null));
        }
        String cta = stormCtaUrl();
        if (!cta.isBlank()) sb.append(NL).append("Olayları aç: ").append(cta).append(NL);
        return sb.toString();
    }

    private static String nz(String v) { return v == null || v.isBlank() ? "-" : v; }

    private static String stormTargetsText(List<String> targets, int truncatedExtra, String heading) {
        StringBuilder sb = new StringBuilder();
        if (heading != null) sb.append(NL).append(heading).append(":").append(NL);
        if (targets == null || targets.isEmpty()) sb.append("  (liste yok)").append(NL);
        else for (String t : targets) sb.append("  - ").append(t).append(NL);
        if (truncatedExtra > 0) sb.append("  ... ve ").append(truncatedExtra).append(" monitör daha").append(NL);
        return sb.toString();
    }

    /** Hedef listesi + "+ N monitör daha…" kırpma satırı. */
    private static List<String> targetItems(List<String> targets, int truncatedExtra) {
        List<String> items = new ArrayList<>(targets == null ? List.of() : targets);
        if (truncatedExtra > 0) items.add("+ " + truncatedExtra + " monitör daha…");
        return items;
    }

    /**
     * Toplu alarm fırtınası e-postası — {@code monitorCount} monitör birden erişilemez.
     * StormService promotion (INITIAL) + günlük toplu re-alert (DAILY_REALERT) bunu kullanır.
     */
    public String buildStormAlertHtml(int monitorCount, String scopeLabel, String rootCauseLabel,
                                      String startedAt, List<String> sampleTargets, int truncatedExtra) {
        return buildStormAlertHtml(monitorCount, scopeLabel, rootCauseLabel, startedAt, sampleTargets, truncatedExtra, "CRITICAL");
    }

    /**
     * D-c7 (2026-09-29): {@code level} = fırtına seviyesi (üyelerin EN YÜKSEK seviyesi — push ile aynı,
     * {@code StormService.stormPushLevel}). Rozet, uyarı kutusu ve "Seviye" satırı tek sözlükten ({@code levelWordTr});
     * eskiden rozet sabit "KRİTİK"ti — UYARI üyeli fırtınada push "UYARI", e-posta "KRİTİK" diyordu.
     */
    public String buildStormAlertHtml(int monitorCount, String scopeLabel, String rootCauseLabel,
                                      String startedAt, List<String> sampleTargets, int truncatedExtra, String level) {
        Tone tone = EmailTemplateBuilder.severityTone(level);
        MailDoc d = MailDoc.create("[Site Monitor] Alarm fırtınası — " + monitorCount + " monitör birden erişilemez")
                .preheader(monitorCount + " monitör birden erişilemez — olası ortak kesinti")
                .kicker("İzleme");
        d.badges(EmailTemplateBuilder.severityBadge(level), Badge.outline("Alarm Fırtınası"));
        d.title("Alarm fırtınası", monitorCount + " monitör birden erişilemez.");
        d.alert(tone, "Olası ortak kesinti",
                "Kısa bir zaman penceresinde çok sayıda monitör birden erişilemez oldu — olası paylaşılan sunucu / ağ / veri merkezi kesintisi. "
                + "Bireysel alarmlar bu TEK toplu bildirimde gruplandı; sorunlar giderildikçe tek bir toplu \"çözüldü\" e-postası gönderilecektir.");
        d.keyValue("Fırtına Özeti", MailDoc.rows(
                Row.of("Kapsam", scopeLabel),
                Row.of("Ortak Kök-Neden", rootCauseLabel),
                Row.of("Başlangıç", fmtOrDash(formatIstanbul(startedAt))),
                levelRow(level),
                new Row("Etkilenen Monitör", MailKit.strong(String.valueOf(monitorCount), tone.strong), String.valueOf(monitorCount))));
        d.heading("Etkilenen Monitörler");
        d.bullets(targetItems(sampleTargets, truncatedExtra));
        d.button(stormCtaUrl(), "Olayları Aç →");
        d.footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d.html();
    }

    /** Toplu alarm fırtınası ÇÖZÜLDÜ e-postası — fırtına sona erdi, {@code recoveredCount} monitör kurtarıldı. */
    public String buildStormRecoveryHtml(int recoveredCount, int stillDownCount, String scopeLabel,
                                         String startedAt, String resolvedAt,
                                         List<String> sampleTargets, int truncatedExtra) {
        return buildStormRecoveryHtml(recoveredCount, stillDownCount, scopeLabel, startedAt, resolvedAt,
                sampleTargets, truncatedExtra, List.of());
    }

    /** {@code stillDownTargets}: hâlâ erişilemeyen üyelerin adları — sayı yerine liste (hangileri?). */
    public String buildStormRecoveryHtml(int recoveredCount, int stillDownCount, String scopeLabel,
                                         String startedAt, String resolvedAt,
                                         List<String> sampleTargets, int truncatedExtra,
                                         List<String> stillDownTargets) {
        String duration = formatOutageDuration(startedAt, resolvedAt);
        MailDoc d = MailDoc.create("[Site Monitor] Alarm fırtınası sona erdi — " + recoveredCount + " monitör kurtarıldı")
                .preheader("Alarm fırtınası sona erdi — " + recoveredCount + " monitör kurtarıldı · " + duration)
                .kicker("İzleme");
        d.badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS), Badge.outline("Alarm Fırtınası"));
        d.title("Alarm fırtınası sona erdi", recoveredCount + " monitör kurtarıldı.");
        List<Row> rows = new ArrayList<>(MailDoc.rows(
                Row.of("Kapsam", scopeLabel),
                Row.of("Başlangıç", fmtOrDash(formatIstanbul(startedAt))),
                Row.of("Çözülme", fmtOrDash(formatIstanbul(resolvedAt))),
                new Row("Toplam Süre", MailKit.strong(duration, Tone.SUCCESS.strong), duration),
                Row.of("Kurtarılan", recoveredCount + " monitör")));
        if (stillDownCount > 0) rows.add(Row.of("Hâlâ İzlemede", stillDownCount + " monitör (bireysel alarma döndü)"));
        d.keyValue("Fırtına Özeti", rows);
        d.heading("Kurtarılan Monitörler");
        d.bullets(targetItems(sampleTargets, truncatedExtra));
        if (stillDownTargets != null && !stillDownTargets.isEmpty()) {
            d.alert(Tone.WARNING, "Hâlâ erişilemeyen (" + stillDownCount + ")", "Bu monitörler bireysel alarma döndü.");
            d.bullets(targetItems(stillDownTargets, Math.max(0, stillDownCount - stillDownTargets.size())));
        }
        d.button(stormCtaUrl(), "Olayları Aç →");
        d.footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d.html();
    }

    // ── Keyword / Ping izleme — kendine ÖZGÜ alarm + çözüm belgeleri ──────────

    /** Monitör detayına deep-link CTA URL'si (?tab=<tab>&monitor=<id>); base/monitor yoksa boş. */
    private String monitorCtaUrl(String tab, Map<String, Object> ctx) {
        Object mid = ctx != null ? ctx.get("monitor_id") : null;
        if (mid == null) return "";
        // CANLI okunur (Genel Ayarlar'dan değişebilir); @Value yalnız fallback — reminder/approve/incident ile AYNI kaynak.
        return liveBaseUrl() + "/?tab=" + tab + "&monitor=" + mid;
    }

    /** İzlenen hedef: gerçek URL ise (http/https) tıklanabilir bağlantı, çıplak host ise düz metin
     *  (şemasız host'u linklemek kırık link üretir). */
    private static String endpointHtml(String value) {
        boolean url = value != null && (value.startsWith("http://") || value.startsWith("https://"));
        return url ? MailKit.link(value, value) : esc(value);
    }

    /** Keyword izleme alarmı — adet/operatör koşulu alanları. */
    private MailDoc keywordAlertDoc(String message, String url, String level, Map<String, Object> ctx) {
        String keyword   = ctxStr(ctx, "keyword");
        String operator  = ctxStr(ctx, "operator"); if (operator.isEmpty()) operator = "GTE";
        int threshold = ctx != null && ctx.get("match_count") instanceof Number mn ? mn.intValue() : 1;
        boolean absent = ("LTE".equals(operator) || "EQ".equals(operator) || "LT".equals(operator)) && threshold == 0;
        String condPhrase = KeywordCheckerService.opPhrase(operator, threshold);
        String occ = ctxStr(ctx, "occurrences");
        String httpStatus = ctxStr(ctx, "http_status");
        String responseMs = ctxStr(ctx, "response_ms");
        String snippet    = ctxStr(ctx, "snippet");
        String firstFailureAt = ctxStr(ctx, "first_failure_at");
        String lastError      = ctxStr(ctx, "last_error");
        List<Map<String, Object>> attempts = attempts(ctx);
        String heroTitle = absent ? "İstenmeyen İfade Bulundu" : "Koşul Sağlanmadı";

        MailDoc d = MailDoc.create("[Site Monitor] " + EmailTemplateBuilder.severityLabel(level) + " · " + nzs(url))
                .preheader(heroTitle + " — " + nzs(url))
                .kicker("İçerik (Keyword) İzleme");
        d.badges(EmailTemplateBuilder.severityBadge(level), Badge.outline("İçerik Doğrulama"));
        d.title(nzs(url), null);
        d.alert(EmailTemplateBuilder.severityTone(level), heroTitle, message);   // D-c8: kutu tonu seviyeden (UYARI mavi)

        List<Row> left = new ArrayList<>();
        left.add(new Row("Adres", endpointHtml(url), nzs(url)));
        left.add(Row.of("Aranan kelime", keyword));
        left.add(Row.of("Beklenen koşul", condPhrase + " bulunmalı"));
        if (!occ.isEmpty()) left.add(Row.of("Bulunan adet", occ + " kez"));
        if (!httpStatus.isEmpty()) left.add(Row.of("HTTP durumu", httpStatus));
        if (!responseMs.isEmpty()) left.add(Row.of("Yanıt süresi", responseMs + " ms"));
        if (!firstFailureAt.isEmpty()) left.add(Row.of("İlk hata", formatIso(firstFailureAt)));
        if (!lastError.isEmpty() && !"null".equalsIgnoreCase(lastError))
            left.add(Row.of("Son hata", lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError));
        d.keyValue("İzleme Bilgileri", left);

        String wordStatus = absent ? "İstenmeyen ifade var"
                : (!occ.isEmpty() ? occ + " kez · gerekli: " + condPhrase : "Koşul sağlanmadı");
        d.keyValue("Doğrulama Özeti", MailDoc.rows(
                new Row("Kelime durumu", MailKit.strong(wordStatus, Tone.DESTRUCTIVE.strong), wordStatus),
                verificationRow(ctx, attempts, "başarısız"),
                levelRow(level),
                new Row("İzleme", MailKit.strong("Devam ediyor", Tone.SUCCESS.strong), "Devam ediyor")));
        attemptTable(d, attempts, "doğrulanamadı");
        if (absent && !snippet.isEmpty()) d.pre("Eşleşme Bağlamı", "…" + snippet + "…");
        d.note("Koşul yeniden sağlandığında bu alarm otomatik kapatılır ve çözüm e-postası gönderilir.");
        d.button(monitorCtaUrl("keyword", ctx), "Monitörü Aç →");
        MailCta.appendIncidentActions(d, liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"));
        d.footerWhy(teamNameOf(ctx)).footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d;
    }

    /** Ping (ICMP) izleme alarmı — erişilebilirlik alanları. */
    private MailDoc pingAlertDoc(String message, String host, String level, Map<String, Object> ctx) {
        boolean na = "true".equalsIgnoreCase(ctxStr(ctx, "na"));
        String ipVersion  = ctxStr(ctx, "ip_version");
        String packetLoss = ctxStr(ctx, "packet_loss");
        String rttMs      = ctxStr(ctx, "rtt_ms");
        String firstFailureAt = ctxStr(ctx, "first_failure_at");
        String lastError      = ctxStr(ctx, "last_error");
        String lossLabel     = !packetLoss.isEmpty() ? "%" + packetLoss : "%100";
        List<Map<String, Object>> attempts = attempts(ctx);
        String heroTitle = na ? "ICMP Kullanılamıyor" : "Host Yanıt Vermiyor";

        MailDoc d = MailDoc.create("[Site Monitor] " + EmailTemplateBuilder.severityLabel(level) + " · " + nzs(host))
                .preheader(heroTitle + " — " + nzs(host))
                .kicker("Ping (ICMP) İzleme");
        d.badges(EmailTemplateBuilder.severityBadge(level), Badge.outline("Erişilebilirlik (Ping)"));
        d.title(nzs(host), null);
        d.alert(EmailTemplateBuilder.severityTone(level), heroTitle, message);   // D-c8: kutu tonu seviyeden (UYARI mavi)

        List<Row> left = new ArrayList<>();
        left.add(Row.of("Host", host));
        left.add(Row.of("IP sürümü", ipVersion.isEmpty() || "auto".equals(ipVersion) ? "Otomatik" : ipVersion.toUpperCase(java.util.Locale.ROOT)));
        left.add(new Row("Paket kaybı", MailKit.strong(lossLabel, Tone.DESTRUCTIVE.strong), lossLabel));
        if (!rttMs.isEmpty()) left.add(Row.of("RTT", rttMs + " ms"));
        if (!firstFailureAt.isEmpty()) left.add(Row.of("İlk hata", formatIso(firstFailureAt)));
        if (!lastError.isEmpty() && !"null".equalsIgnoreCase(lastError))
            left.add(Row.of("Son hata", lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError));
        d.keyValue("İzleme Bilgileri", left);

        String pingStatus = na ? "ICMP kullanılamıyor" : "Yanıt yok";
        d.keyValue("Doğrulama Özeti", MailDoc.rows(
                new Row("Ping durumu", MailKit.strong(pingStatus, Tone.DESTRUCTIVE.strong), pingStatus),
                verificationRow(ctx, attempts, "başarısız"),
                levelRow(level),
                new Row("İzleme", MailKit.strong("Devam ediyor", Tone.SUCCESS.strong), "Devam ediyor")));
        attemptTable(d, attempts, "doğrulanamadı");
        d.note("Host yeniden yanıt verdiğinde bu alarm otomatik kapatılır ve çözüm e-postası gönderilir.");
        d.button(monitorCtaUrl("ping", ctx), "Monitörü Aç →");
        MailCta.appendIncidentActions(d, liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"));
        d.footerWhy(teamNameOf(ctx)).footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d;
    }

    /** Ortak çözüm belgesi — keyword/ping kimliğiyle. */
    private MailDoc typedResolvedDoc(String domain, String kicker, String heroLine, String typeTrLabel,
                                     String level, List<Row> detailRows, String ctaUrl,
                                     String resolvedBy, String resolvedAt, String createdAt,
                                     String teamNames, UptimeSummary uptime, Map<String, Object> ctx) {
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)";
        String duration = outageDurationDisplay(createdAt, resolvedAt);
        MailDoc d = MailDoc.create("[Site Monitor] ÇÖZÜLDÜ · " + nzs(domain))
                .preheader(heroLine + " — " + nzs(domain) + " · " + duration)
                .kicker(kicker);
        d.badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS), Badge.outline(typeTrLabel));
        d.title(nzs(domain), "Alarm kapatıldı, izleme devam ediyor.");
        d.alertHtml(Tone.SUCCESS, esc(heroLine),
                "<strong>" + esc(domain) + "</strong> için açık olan <strong>" + esc(typeTrLabel)
                        + "</strong> alarmı kapatıldı. Toplam kesinti süresi: <strong>" + esc(duration) + "</strong>.",
                heroLine + " — " + nzs(domain) + " için açık olan " + typeTrLabel + " alarmı kapatıldı. Toplam kesinti süresi: " + duration + ".");
        d.keyValue("Çözüm Bilgisi", MailDoc.rows(
                Row.of("Çözen", by),
                Row.of("Yeniden Ulaşılabilir", fmtOrDash(formatIstanbul(resolvedAt))),
                Row.of("Kesinti Başlangıcı", fmtOrDash(formatIstanbul(createdAt))),
                new Row("Toplam Kesinti Süresi", MailKit.strong(duration, Tone.SUCCESS.strong), duration),
                Row.of("Alarm Tipi", typeTrLabel),
                levelRow(level)));
        if (detailRows != null && !detailRows.isEmpty()) d.keyValue("Çözülen Alarm Detayı", detailRows);
        uptimeSummary(d, uptime);
        d.button(ctaUrl, "Monitörü Aç →");
        MailCta.appendIncidentActions(d, liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"));
        d.footerWhy(teamNames).footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d;
    }

    private MailDoc keywordResolvedDoc(String url, Map<String, Object> ctx, String level, String resolvedBy, String resolvedAt,
                                       String createdAt, String teamNames, UptimeSummary uptime) {
        List<Row> rows = new ArrayList<>();
        if (ctx != null) {
            String kw = ctxStr(ctx, "keyword");
            String op = ctxStr(ctx, "operator"); if (op.isEmpty()) op = "GTE";
            int n = ctx.get("match_count") instanceof Number mn ? mn.intValue() : 1;
            String occ = ctxStr(ctx, "occurrences");
            if (!kw.isEmpty()) rows.add(Row.of("Aranan kelime", kw));
            rows.add(Row.of("Koşul", KeywordCheckerService.opPhrase(op, n) + " bulunmalı"));
            if (!occ.isEmpty()) rows.add(Row.of("Alarm anı bulunan", occ + " kez"));
        }
        return typedResolvedDoc(url, "İçerik (Keyword) İzleme", "İçerik Doğrulaması Yeniden Başarılı", "İçerik Doğrulama",
                level, rows, monitorCtaUrl("keyword", ctx), resolvedBy, resolvedAt, createdAt, teamNames, uptime, ctx);
    }

    private MailDoc pingResolvedDoc(String host, Map<String, Object> ctx, String level, String resolvedBy, String resolvedAt,
                                    String createdAt, String teamNames, UptimeSummary uptime) {
        List<Row> rows = new ArrayList<>();
        if (ctx != null) {
            String ipv = ctxStr(ctx, "ip_version");
            rows.add(Row.of("Host", host));
            if (!ipv.isEmpty() && !"auto".equals(ipv)) rows.add(Row.of("IP sürümü", ipv.toUpperCase(java.util.Locale.ROOT)));
        }
        return typedResolvedDoc(host, "Ping (ICMP) İzleme", "Host Yeniden Yanıt Veriyor", "Erişilebilirlik (Ping)",
                level, rows, monitorCtaUrl("ping", ctx), resolvedBy, resolvedAt, createdAt, teamNames, uptime, ctx);
    }

    // ── DNS kayıt değişikliği ────────────────────────────────────────────────

    /** Tabloda gösterilecek en fazla değer satırı; fazlası "…ve N tane daha" ile özetlenir. */
    private static final int DNS_MAX_VALUE_ROWS = 6;
    /** Tek bir değerin en fazla karakteri (uzun TXT/DKIM kayıtları düzeni bozmasın). */
    private static final int DNS_MAX_VALUE_LEN = 160;

    /**
     * DNS kayıt değişikliği alarmı (YÜKSEK) — ESKİ→YENİ değerler satır-kilitli tabloda. Teyit
     * denemeleri bölümü yoktur (değişiklik başarılı sorgudan pozitif gözlemdir); alarm otomatik
     * kapanmaz. ctx okumaları null-toleranslıdır ("Tekrar Bildir" yolu eksik context geçirebilir).
     */
    private MailDoc dnsChangedDoc(String message, String domain, String level, Map<String, Object> ctx) {
        String recordType = ctxStr(ctx, "record_type");
        String changedAt  = ctxStr(ctx, "changed_at");
        List<String> oldValues = ctxList(ctx, "old_values");
        List<String> newValues = ctxList(ctx, "new_values");
        String lead = (!recordType.isEmpty() ? recordType + " kaydı" : "")
                + (!changedAt.isEmpty() ? (recordType.isEmpty() ? "" : " · ") + "Tespit zamanı: " + formatIsoFull(changedAt) : "");

        MailDoc d = MailDoc.create("[Site Monitor] " + EmailTemplateBuilder.severityLabel(level) + " · " + nzs(domain) + " — DNS değişikliği")
                .preheader("DNS kaydı değişti — " + nzs(domain))
                .kicker("DNS İzleme");
        d.badges(EmailTemplateBuilder.severityBadge(level), Badge.outline("DNS Değişikliği"));
        d.title(nzs(domain), lead.isBlank() ? null : lead);
        d.alert(Tone.WARNING, "DNS Kaydı Değişti", message);
        d.heading("Değişiklik");
        d.raw(dnsDiffTable(oldValues, newValues),
                "Eski değerler: " + (oldValues.isEmpty() ? "kayıt yoktu" : String.join(", ", oldValues))
                + "\nYeni değerler: " + (newValues.isEmpty() ? "kayıt kalmadı" : String.join(", ", newValues)));
        d.note("Bu alarm otomatik kapanmaz. Değişiklik planlı ise Site Monitor → Uyarılar → Alarm Geçmişi ekranından alarmı "
                + "onaylayın ve kapatın. Beklenmedik bir değişiklikse (olası domain hijack / hatalı migrasyon) derhal ağ ekibiyle iletişime geçin.");
        MailCta.appendIncidentActions(d, liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"));
        d.footerWhy(teamNameOf(ctx)).footerMeta("Site Monitor", "Bildirim: " + nowStamp());
        return d;
    }

    @SuppressWarnings("unchecked")
    private List<String> ctxList(Map<String, Object> ctx, String key) {
        if (ctx == null) return List.of();
        Object v = ctx.get(key);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    /**
     * ESKİ→YENİ değerleri SATIR-KİLİTLİ tek tabloda gösterir: i. eski ile i. yeni değer AYNI
     * {@code <tr>}'de durur, aralarında yön oku. Silinen değer üstü çizik; uzun değer kırılır
     * (telefonda taşma yok). Tamamen tablo tabanlı + td bgcolor + düz hex → Outlook güvenli.
     */
    private String dnsDiffTable(List<String> oldValues, List<String> newValues) {
        List<String> olds = oldValues == null ? List.of() : oldValues;
        List<String> news = newValues == null ? List.of() : newValues;
        int rows = Math.max(olds.size(), news.size());
        int shown = Math.min(rows, DNS_MAX_VALUE_ROWS);
        String head = "background-color:" + MailTokens.SUBTLE + ";padding:10px 12px;border-bottom:1px solid " + MailTokens.BORDER
                + ";font-size:12px;line-height:16px;font-weight:600;letter-spacing:0.04em;";

        StringBuilder body = new StringBuilder();
        if (rows == 0) {
            body.append("<tr><td colspan=\"3\" align=\"center\" style=\"padding:14px;font-size:13px;line-height:20px;color:")
                .append(MailTokens.MUTED).append("\">Değişiklik ayrıntısı bu bildirimde taşınmıyor.</td></tr>");
        }
        for (int i = 0; i < shown; i++) {
            String o = i < olds.size() ? olds.get(i) : null;
            String n = i < news.size() ? news.get(i) : null;
            String line = (i == shown - 1 && rows <= shown) ? "0" : "1px solid " + MailTokens.DIVIDER;
            body.append("<tr>")
                .append(dnsValueCell(o, true, olds.isEmpty() ? "kayıt yoktu" : "—", line))
                .append("<td align=\"center\" valign=\"top\" style=\"padding:10px 4px;border-bottom:").append(line)
                .append(";font-size:16px;line-height:20px;color:").append(MailTokens.MUTED).append("\">&#8594;</td>")
                .append(dnsValueCell(n, false, news.isEmpty() ? "kayıt kalmadı" : "—", line))
                .append("</tr>");
        }
        if (rows > shown) {
            body.append("<tr><td colspan=\"3\" align=\"center\" style=\"padding:10px 12px;font-size:12px;line-height:18px;color:")
                .append(MailTokens.MUTED).append("\">…ve ").append(rows - shown).append(" tane daha</td></tr>");
        }
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:separate;border:1px solid "
            + MailTokens.BORDER + ";border-radius:8px;font-family:" + MailTokens.FONT + "\"><tr>"
            + "<td width=\"46%\" bgcolor=\"" + MailTokens.SUBTLE + "\" style=\"width:46%;" + head + "color:" + MailTokens.MUTED
            + ";border-radius:8px 0 0 0\">ESKİ DEĞERLER &#183; " + olds.size() + "</td>"
            + "<td width=\"8%\" bgcolor=\"" + MailTokens.SUBTLE + "\" style=\"width:8%;" + head + "\">&nbsp;</td>"
            + "<td width=\"46%\" bgcolor=\"" + MailTokens.SUBTLE + "\" style=\"width:46%;" + head + "color:" + Tone.WARNING.text
            + ";border-radius:0 8px 0 0\">YENİ DEĞERLER &#183; " + news.size() + "</td>"
            + "</tr>" + body + "</table>";
    }

    /** Fark tablosunun tek değer hücresi. {@code removed=true} → solgun + üstü çizik (span'de:
     *  Word, text-decoration'ı hücreden miras almıyor). Değer yoksa {@code emptyText} basılır. */
    private static String dnsValueCell(String value, boolean removed, String emptyText, String line) {
        String td = "<td valign=\"top\" class=\"mono\" style=\"padding:10px 12px;border-bottom:" + line + ";font-family:" + MailTokens.MONO
                + ";font-size:13px;line-height:20px;word-break:break-all;overflow-wrap:anywhere;mso-line-height-rule:exactly;";
        if (value == null || value.isBlank()) {
            return td + "font-style:italic;color:" + MailTokens.MUTED + "\">" + esc(emptyText) + "</td>";
        }
        String shown = esc(truncValue(value));
        String inner = removed ? "<span style=\"text-decoration:line-through\">" + shown + "</span>" : shown;
        return td + "font-weight:" + (removed ? "400" : "600") + ";color:" + (removed ? MailTokens.MUTED : MailTokens.FG) + "\">" + inner + "</td>";
    }

    /** Çok uzun tek değeri (DKIM TXT vb.) kırpar — düzen bozulmasın. */
    private static String truncValue(String v) {
        return v.length() <= DNS_MAX_VALUE_LEN ? v : v.substring(0, DNS_MAX_VALUE_LEN) + "…";
    }

    // ── Tarih biçimleri ──────────────────────────────────────────────────────

    private String formatIsoFull(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            // UTC ISO'yu Europe/Istanbul'a çevir → kullanıcıya her zaman yerel saat
            LocalDateTime dt = LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(IST).toLocalDateTime();
            String[] months = {"Ocak","Şubat","Mart","Nisan","Mayıs","Haziran",
                               "Temmuz","Ağustos","Eylül","Ekim","Kasım","Aralık"};
            String[] days = {"Pazartesi","Salı","Çarşamba","Perşembe","Cuma","Cumartesi","Pazar"};
            String month   = months[dt.getMonthValue() - 1];
            String dayName = days[dt.getDayOfWeek().getValue() - 1];
            return dt.getDayOfMonth() + " " + month + " " + dt.getYear() + ", " + dayName
                 + " — " + String.format("%02d:%02d", dt.getHour(), dt.getMinute());
        } catch (Exception e) {
            return formatIso(iso);
        }
    }

    private String ctxStr(Map<String, Object> ctx, String key) {
        if (ctx == null) return "";
        Object v = ctx.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    /** UTC ISO ("yyyy-MM-dd'T'HH:mm:ss") → Europe/Istanbul "dd.MM.yyyy HH:mm" (yerel saat).
     *  Tüm e-posta tarihleri buradan geçer; proje genelinde kullanıcıya her zaman local gösterilir. */
    private String formatIso(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            LocalDateTime utc = LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(IST)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception e) {
            try {
                // Beklenmeyen biçim — saat dilimi çevirmeden en azından okunur biçime getir
                return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4)
                     + " " + iso.substring(11, 16);
            } catch (Exception e2) {
                return iso;
            }
        }
    }

    /** Proje saat dilimi (UTC+3, DST yok). Stored ISO string'leri UTC kabul edilir. */
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Şu an (Europe/Istanbul) "dd.MM.yyyy HH:mm" — alt bilgi damgası. */
    private static String nowStamp() {
        return LocalDateTime.now(IST).format(STAMP_FMT);
    }

    /** UTC ISO ("yyyy-MM-dd'T'HH:mm:ss") → Europe/Istanbul "dd.MM.yyyy HH:mm".
     *  null/boş → null (footer'da ilgili satır gizlensin). */
    private String formatIstanbul(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            LocalDateTime utc = LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(IST)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception e) {
            return formatIso(iso);
        }
    }

    // ── Haftalık rapor mailleri ──────────────────────────────────────────────

    /** Onay mailinde inline (CID) gömülecek görsel. */
    public record InlineImage(String cid, byte[] data, String contentType) {}

    /** Marka logosu InlineImage'i — sendHtml çağıranları için (varyant seçimi BrandMailAssets'te, tek nokta). */
    public InlineImage brandLogo(String variant) {
        byte[] bytes = BrandMailAssets.logoBytes(variant);
        return bytes == null ? null : new InlineImage(BrandMailAssets.CID, bytes, "image/png");
    }

    private static final ObjectMapper WR_JSON = new ObjectMapper();
    private static final List<org.commonmark.Extension> MD_EXTENSIONS = List.of(
            TablesExtension.create(),
            org.commonmark.ext.task.list.items.TaskListItemsExtension.create());
    private static final Parser MD_PARSER = Parser.builder().extensions(MD_EXTENSIONS).build();
    // softbreak("<br />\n"): tek satır-sonu (\n) HTML'de <br/> olur — kullanıcının alt alta yazdığı
    // satırlar (log/stack trace/adım listesi) mailde de alt alta görünür (yoksa markdown boşluğa düzlerdi).
    // sanitizeUrls (prod kapısı 2026-09-25, D-3): escapeHtml ham HTML'i kaçırıyordu ama [x](javascript:…)
    // bağlantısı href olarak KALIYORDU — olay/haftalık rapor önizlemesinde tıklamayla çalışırdı. İzinli
    // şemalar açıkça verilir: commonmark'ın varsayılan listesi mailin CID görsellerini (cid:img…, cid:incimg…)
    // boşaltır, data: ise bağlantıda tehlikelidir. Şemasız (göreli /api/… ya da #) adresler etkilenmez.
    private static final HtmlRenderer MD_RENDERER = HtmlRenderer.builder()
            .extensions(MD_EXTENSIONS).escapeHtml(true).softbreak("<br />\n")
            .sanitizeUrls(true)
            .urlSanitizer(new org.commonmark.renderer.html.DefaultUrlSanitizer(
                    List.of("http", "https", "mailto", "tel", "cid")))
            .build();

    /** Gönderen adres — rapor gönderim geçmişi kayıtları için (haftalık + aylık envanter). */
    public String fromAddress() {
        return currentFrom();
    }

    /**
     * Genel amaçlı HTML mail — CC ve inline CID görsel desteğiyle.
     * ÖNEMLİ: setText(html, true) addInline'dan ÖNCE çağrılmalıdır
     * (MimeMessageHelper related multipart sıralaması).
     */
    public String sendHtml(String[] to, String[] cc, String subject, String html,
                           List<InlineImage> inline) {
        return sendHtml(to, cc, subject, html, inline, false);
    }

    /**
     * İNDİRİLEBİLİR dosya eki (inline görsel DEĞİL) — aylık envanter raporunun CSV/PDF ekleri.
     * {@code InlineImage}'in simetriği: o gövdeye gömülür (cid:), bu ise ek olarak listelenir.
     */
    public record MailAttachment(String fileName, byte[] data, String contentType) { }

    /** Dosya ekli HTML mail. Ek yoksa {@link #sendHtml(String[], String[], String, String, List)} ile aynıdır. */
    public String sendHtmlWithAttachments(String[] to, String[] cc, String subject, String html,
                                          List<InlineImage> inline, List<MailAttachment> attachments) {
        return sendHtml(to, cc, subject, html, inline, false, attachments);
    }

    /** {@code force=true}: mail mute'unu ({@code SmtpSettings.enabled=false}) atlar — login-issue gibi
     *  operasyonel bildirimler için. SMTP config eksikse gönderim yine de {@code FAILED} döner (SKIP değil). */
    public String sendHtml(String[] to, String[] cc, String subject, String html,
                           List<InlineImage> inline, boolean force) {
        return sendHtml(to, cc, subject, html, inline, force, null);
    }

    /**
     * {@code plainText} dolu ise mail {@code multipart/alternative} (düz metin + HTML) gider.
     *
     * <p>Bireysel alarm/çözüm mailleri bunu zaten yapıyordu ({@code sendAlert}: setText(text, html)),
     * {@code sendHtml} hunisi ise YALNIZ HTML part üretiyordu. Düz metine düşen istemci ya da
     * kurumsal gateway'de fırtına maili (ürünün en kritik bildirimi) boş görünüyordu.
     */
    public String sendHtml(String[] to, String[] cc, String subject, String html, String plainText,
                           List<InlineImage> inline, boolean force, List<MailAttachment> attachments) {
        return sendHtmlInternal(to, cc, subject, html, plainText, inline, force, attachments);
    }

    public String sendHtml(String[] to, String[] cc, String subject, String html,
                           List<InlineImage> inline, boolean force, List<MailAttachment> attachments) {
        return sendHtmlInternal(to, cc, subject, html, null, inline, force, attachments);
    }

    /**
     * Toplu duyuru — alıcılar GİZLİ (BCC): kurum geneli duyuruda (sistem bakımı, 2026-10-02) herkesin adresi herkese
     * görünmesin. Aynı huniden geçer: mail kapalıysa {@code SKIPPED_DISABLED}, pasif kullanıcı ağı ({@code doSend} →
     * {@code InactiveRecipientGuard} TO/CC/BCC süzgeci) ve 421 yeniden denemesi aynen işler. Marka logosu CID olarak eklenir.
     */
    public String sendHtmlBcc(String[] bcc, String subject, String html, String plainText) {
        if (bcc == null || bcc.length == 0) return "SKIPPED: alıcı yok";
        // Ayrı bir MimeMessageHelper KURMAZ (EmailBrandCidTest huni sayısı): tek huninin BCC kipi — marka CID'i,
        // multipart/alternative, mail kapalı → SKIPPED_DISABLED ve doSend ağı birebir aynı yoldan geçer.
        return sendHtmlInternal(new String[0], null, bcc, subject, html, plainText, null, false, null);
    }

    private String sendHtmlInternal(String[] to, String[] cc, String subject, String html, String plainText,
                                    List<InlineImage> inline, boolean force, List<MailAttachment> attachments) {
        return sendHtmlInternal(to, cc, null, subject, html, plainText, inline, force, attachments);
    }

    private String sendHtmlInternal(String[] to, String[] cc, String[] bcc, String subject, String html, String plainText,
                                    List<InlineImage> inline, boolean force, List<MailAttachment> attachments) {
        boolean bccOnly = (to == null || to.length == 0) && bcc != null && bcc.length > 0;
        if (!force && !isEnabled()) {
            if (bccOnly) log.info("⚠ Email devre dışı — BCC={} alıcı | KONU={}", bcc.length, subject);
            else log.info("⚠ Email devre dışı — TO={} CC={} | KONU={}",
                    SecretMask.maskEmails(to), SecretMask.maskEmails(cc), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    msg, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            if (to != null && to.length > 0) helper.setTo(to);
            if (cc != null && cc.length > 0) helper.setCc(cc);
            if (bcc != null && bcc.length > 0) helper.setBcc(bcc);
            applyFrom(helper);
            helper.setSubject(subject);
            // Çağıran metin vermediyse MailDoc'un aynı belge için yazdığı metin (yoksa HTML'den türetilir)
            // → yeniden tasarımla (2026-09-26) TÜM sendHtml mailleri multipart/alternative gider.
            String text = plainText != null ? plainText : MailKit.plainTextFor(html);
            if (text != null && !text.isBlank()) helper.setText(text, html);
            else helper.setText(html, true);
            boolean brandAttached = false;
            if (inline != null) {
                for (InlineImage img : inline) {
                    helper.addInline(img.cid(), new ByteArrayResource(img.data()), img.contentType());
                    if (BrandMailAssets.CID.equals(img.cid())) brandAttached = true;
                }
            }
            // Tek huni garantisi: şablon header lockup'ı cid:brand-logo referanslıyorsa ve çağıran
            // eki vermediyse, nötr "ok" logosu otomatik iliştirilir (15 sendHtml çağıranı tek tek
            // elden geçirmeden kırık-görsel riski kapanır).
            if (!brandAttached) BrandMailAssets.addInline(helper, html, "ok");
            // Dosya ekleri EN SON: MimeMessageHelper sırası setText → addInline → addAttachment.
            if (attachments != null) {
                for (MailAttachment a : attachments) {
                    if (a == null || a.data() == null || a.data().length == 0) continue;
                    helper.addAttachment(a.fileName(), new ByteArrayResource(a.data()), a.contentType());
                }
            }
            return doSend(bccOnly ? "BCC×" + bcc.length : Arrays.toString(to), msg, 1);
        } catch (Exception e) {
            if (bccOnly) log.error("✗ Toplu (BCC) e-posta hazırlanamadı: {} alıcı | HATA={}", bcc.length, e.getMessage(), e);
            else log.error("✗ HTML e-posta hazırlanamadı: TO={} | HATA={}", SecretMask.maskEmails(to), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /** Markdown → HTML (GFM tabloları destekli, raw HTML escape'li).
     *  forEmail=true: /api/weekly-reports/images/{id} → cid:img{id}. Blok stilleri HER İKİ yolda
     *  satır içi — önizleme ile gönderilen mail aynı görünür (Outlook head stilini okumaz). */
    private String mdToHtml(String md, boolean forEmail, Map<Long, Integer> imageWidths, int maxWidth) {
        if (md == null || md.isBlank()) return "";
        String src = forEmail
                ? md.replaceAll("\\]\\(/api/weekly-reports/images/(\\d+)\\)", "](cid:img$1)")
                : md;
        String html = MD_RENDERER.render(MD_PARSER.parse(src));
        html = inlineImageStyles(taskCheckboxesToSymbols(html), forEmail, imageWidths, maxWidth);
        return inlineBlockStyles(html);
    }

    /** Markdown çıktısındaki blok elemanlara belirteç renkleriyle satır içi stil ekler. Yalnız markdown
     *  HTML'ine uygulanır. GFM tablo align attr'ı korunur; uzun kod/URL kırılır (telefonda taşma yok). */
    private String inlineBlockStyles(String html) {
        if (html == null || html.isEmpty()) return html;
        String fg = MailTokens.FG, border = MailTokens.BORDER;
        return html
            .replace("<ul>", "<ul style=\"margin:6px 0;padding-left:22px;font-size:14px;line-height:22px;color:" + fg + "\">")
            .replace("<ol>", "<ol style=\"margin:6px 0;padding-left:22px;font-size:14px;line-height:22px;color:" + fg + "\">")
            .replace("<p>",  "<p style=\"margin:6px 0;font-size:14px;line-height:22px;color:" + fg + ";word-break:break-word;overflow-wrap:anywhere\">")
            .replace("<table>", "<table style=\"border-collapse:collapse;width:100%;margin:8px 0\">")
            .replace("<pre>", "<pre style=\"margin:8px 0;padding:10px 12px;background-color:" + MailTokens.SUBTLE + ";border:1px solid " + border
                    + ";border-radius:6px;font-family:" + MailTokens.MONO + ";font-size:12px;line-height:18px;white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere\">")
            .replace("<code>", "<code style=\"font-family:" + MailTokens.MONO + ";font-size:13px\">")
            .replace("<blockquote>", "<blockquote style=\"margin:8px 0;padding:8px 12px;background-color:" + MailTokens.SUBTLE + ";border:1px solid " + border
                    + ";border-radius:6px;color:" + MailTokens.MUTED + "\">")
            .replaceAll("<h[1-3]>", "<p style=\"margin:10px 0 4px;font-size:15px;line-height:22px;font-weight:600;color:" + fg + "\">")
            .replaceAll("<h[4-6]>", "<p style=\"margin:8px 0 4px;font-size:14px;line-height:20px;font-weight:600;color:" + fg + "\">")
            .replaceAll("</h[1-6]>", "</p>")
            .replaceAll("<th(\\s|>)", "<th style=\"border:1px solid " + border + ";padding:6px 10px;font-size:13px;text-align:left;background-color:"
                    + MailTokens.SUBTLE + ";font-weight:600;color:" + fg + "\"$1")
            .replaceAll("<td(\\s|>)", "<td style=\"border:1px solid " + border + ";padding:6px 10px;font-size:13px;text-align:left;color:" + fg
                    + ";word-break:break-word;overflow-wrap:anywhere\"$1")
            .replace("<a href=", "<a style=\"color:" + MailTokens.PRIMARY + ";word-break:break-word;overflow-wrap:anywhere\" href=");
    }

    /** Görev listesi checkbox'larını sembole çevirir — mail istemcileri form
     *  input'larını desteklemez (Outlook tamamen düşürür); ☑/☐ her yerde görünür. */
    private static final Pattern TASK_CHECKBOX = Pattern.compile("<input[^>]*type=\"checkbox\"[^>]*>");

    private String taskCheckboxesToSymbols(String html) {
        Matcher m = TASK_CHECKBOX.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.group().contains("checked") ? "☑ " : "☐ ");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Outlook masaüstü (Word motoru) head'deki style bloğunu yok sayar — görsel
     *  taşmasını ancak inline stil + açık width attribute engeller. Görsel genişliği bulunduğu
     *  bölümün iç genişliği ile sınırlanır (konuma göre): 640px rapor kartı − bölüm kartı boşlukları.
     *  width attr → Outlook, width:100%/max-width → modern, display:block → Gmail alt boşluğu. */
    /* package */ static final int MAIL_IMG_MAX_WIDTH = 556; // madde 1-3: 640 kart − 2×24 − bölüm kartı (2×16+2)
    /* package */ static final int MAIL_IMG_MAX_WIDTH_CHANNEL = 520; // madde 4 kanal alt-kartı (bir kart daha)
    private static final Pattern CID_IMG = Pattern.compile("<img src=\"cid:img(\\d+)\"");
    private static final Pattern API_IMG = Pattern.compile("<img src=\"(/api/weekly-reports/images/\\d+)\"");

    private String inlineImageStyles(String html, boolean forEmail, Map<Long, Integer> imageWidths, int maxWidth) {
        if (!forEmail) {
            return API_IMG.matcher(html).replaceAll(
                    "<img style=\"display:block;max-width:100%;height:auto;border-radius:8px;margin:6px 0\" src=\"$1\"");
        }
        Matcher m = CID_IMG.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Integer natural = imageWidths != null ? imageWidths.get(Long.parseLong(m.group(1))) : null;
            // Görsel doğal genişliğini AŞMASIN ama bölüm sınırını da geçmesin (taşma yok)
            int w = Math.min(natural != null ? natural : maxWidth, maxWidth);
            m.appendReplacement(sb, "<img width=\"" + w + "\" border=\"0\" alt=\"Rapor görseli\" style=\"display:block;width:100%;"
                    + "max-width:" + w + "px;height:auto;border-radius:8px;margin:6px 0\""
                    + " src=\"cid:img" + m.group(1) + "\"");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail, null);
    }

    /** imageWidths: görsel id → gösterim genişliği px (Outlook width attr için); eksikse bölüm tavanı. */
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail,
                imageWidths, null, null, null);
    }

    /** Footer'a onay bilgisi ekler: approverName + approvedAtIso + sentAtIso (UTC ISO,
     *  Europe/Istanbul'a çevrilir). null olanlar gizlenir (örn. DRAFT önizleme). */
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail,
                imageWidths, approverName, approvedAtIso, sentAtIso, null);
    }

    /** approveCtaUrl'süz, KPI özetsiz uyumluluk overload'u. */
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso,
                                        String approveCtaUrl) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail,
                imageWidths, approverName, approvedAtIso, sentAtIso, approveCtaUrl, null);
    }

    /** HTML + düz metin parçası (iç içe kart gövdeleri için). */
    private record Part(String html, String text) {
        static final Part EMPTY = new Part("", "");
        boolean isEmpty() { return html == null || html.isBlank(); }
    }

    /** {@code approveCtaUrl} doluysa (PO onay-bekleyen maili): rapor içeriğinin üstüne ve altına "Raporu onayla"
     *  CTA bloğu eklenir. {@code kpiSummary} doluysa başlık altında canlı KPI özeti (skor, aksiyonlar, göstergeler)
     *  gösterilir — WeeklyReportKpiService'ten (read-only). */
    @SuppressWarnings("unchecked")
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso,
                                        String approveCtaUrl, Map<String, Object> kpiSummary) {
        String generatedAt = ZonedDateTime.now(IST).format(STAMP_FMT);
        Map<String, Object> c;
        try {
            c = WR_JSON.readValue(contentJson != null ? contentJson : "{}", Map.class);
        } catch (Exception e) {
            c = Map.of();
        }
        Map<String, Object> i1 = asMap(c.get("item1"));
        Map<String, Object> i2 = asMap(c.get("item2"));
        Map<String, Object> i3 = asMap(c.get("item3"));
        Map<String, Object> i4 = asMap(c.get("item4"));
        String manager = managerName != null && !managerName.isBlank() ? managerName : "Yönetici";

        MailDoc d = MailDoc.create("[Site Monitor] " + nzs(teamName) + " — Haftalık Rapor — " + nzs(weekLabel)).wide()
                .preheader(nzs(teamName) + " ekibinin " + nzs(weekLabel) + " haftalık raporu")
                .kicker("Haftalık Rapor");
        d.title(nzs(teamName), weekLabel);
        d.paragraphHtml("<strong>Sayın " + esc(manager) + ",</strong>", "Sayın " + manager + ",");
        d.paragraphHtml(esc(teamName) + " ekibi olarak <strong>" + esc(weekLabel) + "</strong> haftası raporumuzu aşağıda paylaşıyoruz.",
                nzs(teamName) + " ekibi olarak " + nzs(weekLabel) + " haftası raporumuzu aşağıda paylaşıyoruz.");
        // Onay CTA — hitabın hemen ardında, rapor içeriğinin ÜSTÜNDE (PO kaydırmadan görsün).
        approveBlock(d, approveCtaUrl);
        Part overview = weeklyOverview(kpiSummary);
        if (!overview.isEmpty()) d.card("Haftalık Özet ve Göstergeler", null, overview.html(), overview.text());

        // ── Madde 1 — sayı kutuları + durum + takip linki ──
        Part s1 = join(
                statsPart(List.of(numStat("Toplam", i1.get("total"), null), numStat("Acil", i1.get("urgent"), Tone.DESTRUCTIVE.strong),
                        numStat("Yüksek", i1.get("high"), Tone.WARNING.strong), numStat("Orta", i1.get("medium"), Tone.INFO.strong),
                        numStat("Düşük", i1.get("low"), Tone.SUCCESS.strong))),
                item1StatusPart(i1),
                linkLine("Proaktif İyileştirme kayıtlarına erişmek için tıklayınız", str(i1.get("tracking_url"))),
                mdPart(str(i1.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH));
        section(d, "1. Proaktif Servis İyileştirme Kayıtları", s1);

        // ── Madde 2 — üç sayı da 0 ise otomatik vurgulu "kayıt yok" notu (manuel yazıma gerek kalmaz)
        boolean noItem2Records = intVal(i2.get("open_incidents")) == 0
                && intVal(i2.get("problem_records")) == 0
                && intVal(i2.get("postmortems")) == 0;
        String noRec = "Bu hafta aşım yaşanan olay, problem veya açık postmortem kaydı bulunmamaktadır.";
        Part s2 = join(
                statsPart(List.of(numStat("Açık Olay", i2.get("open_incidents"), Tone.DESTRUCTIVE.strong),
                        numStat("Problem", i2.get("problem_records"), Tone.WARNING.strong),
                        numStat("Postmortem", i2.get("postmortems"), Tone.INFO.strong))),
                noItem2Records ? new Part(MailKit.space(MailKit.alert(Tone.SUCCESS, null, esc(noRec)), 12), noRec) : Part.EMPTY,
                linkLine("Açık olay kayıtları için tıklayınız", str(i2.get("incidents_url"))),
                linkLine("Problem kayıtları için tıklayınız", str(i2.get("problems_url"))),
                linkLine("Postmortem kayıtları için tıklayınız", str(i2.get("postmortems_url"))),
                linkLine("İlgili kayıtlar için tıklayınız", str(i2.get("tracking_url"))), // eski raporlardaki genel link
                mdPart(str(i2.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH));
        section(d, "2. Aşım Yaşanan Olay / Problem ve Açık Postmortem Kayıtları", s2);

        // ── Madde 3 ──
        section(d, "3. Haftalık Katılım Sağlanan Çalışmalar", mdPart(str(i3.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH));

        // ── Madde 4 — kanal alt-kartları ──
        StringBuilder ch = new StringBuilder();
        StringBuilder chText = new StringBuilder();
        if (i4.get("channels") instanceof List<?> channels) {
            for (Object chObj : channels) {
                Map<String, Object> m = asMap(chObj);
                Part md = mdPart(str(m.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH_CHANNEL);
                ch.append(MailKit.space(MailKit.card(str(m.get("name")), null,
                        md.isEmpty() ? MailKit.note("—") : md.html()), 10));
                chText.append(str(m.get("name"))).append('\n').append(md.text()).append("\n\n");
            }
        }
        section(d, "4. Domain Bazlı Kritik İşlerin Durumu", new Part(ch.toString(), chText.toString()));

        approveBlock(d, approveCtaUrl);
        String approvedIst = formatIstanbul(approvedAtIso);
        String sentIst = formatIstanbul(sentAtIso);
        d.footerMeta("Site Monitor — Haftalık Rapor",
                approverName != null && !approverName.isBlank() ? "Onaylayan: " + approverName : null,
                approvedIst != null ? "Onay: " + approvedIst : null,
                sentIst != null ? "Gönderim: " + sentIst : null,
                "Oluşturuldu: " + generatedAt);
        return d.html();
    }

    /** Rapor bölüm kartı — gövde boşsa "—". */
    private static void section(MailDoc d, String title, Part body) {
        d.card(title, null, body.isEmpty() ? MailKit.note("—") : body.html(), body.isEmpty() ? "—" : body.text());
    }

    /** PO onay-bekleyen mailinde rapor içeriğinin üstüne/altına eklenen onay bloğu. url boşsa eklenmez. */
    private static void approveBlock(MailDoc d, String approveUrl) {
        if (approveUrl == null || approveUrl.isBlank()) return;
        d.alert(Tone.SUCCESS, "Bu rapor onayınızı bekliyor", "Aşağıdaki butonla (giriş yapmadan) doğrudan onaylayabilirsiniz.");
        d.button(approveUrl, "Raporu onaylamak için tıklayınız →");
    }

    private static Part join(Part... parts) {
        StringBuilder h = new StringBuilder(), t = new StringBuilder();
        for (Part p : parts) {
            if (p == null || p.isEmpty()) continue;
            h.append(p.html());
            if (p.text() != null && !p.text().isBlank()) t.append(p.text().strip()).append('\n');
        }
        return new Part(h.toString(), t.toString());
    }

    private static Stat numStat(String label, Object value, String color) {
        return new Stat(label, value != null ? String.valueOf(value) : "0", color, null);
    }

    private static Part statsPart(List<Stat> stats) {
        StringBuilder t = new StringBuilder();
        for (Stat s : stats) t.append(t.length() == 0 ? "" : " · ").append(s.label()).append(": ").append(s.value());
        return new Part(MailKit.space(MailKit.stats(stats), 4), t.toString());
    }

    private Part mdPart(String md, boolean forEmail, Map<Long, Integer> imageWidths, int maxWidth) {
        String html = mdToHtml(md, forEmail, imageWidths, maxWidth);
        return html.isBlank() ? Part.EMPTY : new Part(html, md);
    }

    /**
     * Madde 1 kayıtlarının durum dağılımı (2026-09-27): sayılardan biri > 0 ise "Durum dağılımı" başlıklı dört istatistik
     * kutusu (MailKit.stats — Outlook hayalet tablolu, medya sorgusuz sarar); hepsi 0 / alan yoksa eski raporların tekil
     * {@code status_text} satırı (geriye uyum); o da yoksa hiçbir şey.
     */
    static Part item1StatusPart(Map<String, Object> i1) {
        Map<String, Object> sc = asMap(i1.get("status_counts"));
        int working = Math.max(0, intVal(sc.get("working"))), planned = Math.max(0, intVal(sc.get("planned")));
        int onHold = Math.max(0, intVal(sc.get("on_hold"))), done = Math.max(0, intVal(sc.get("done")));
        if (working + planned + onHold + done > 0) {
            Part head = new Part("<p style=\"margin:10px 0 2px;font-size:13px;line-height:20px;font-weight:600;color:" + MailTokens.FG
                    + "\">Durum dağılımı</p>", "Durum dağılımı");
            return join(head, statsPart(List.of(numStat("Çalışılıyor", working, Tone.INFO.strong),
                    numStat("Planlandı", planned, null), numStat("Beklemede", onHold, Tone.WARNING.strong),
                    numStat("Tamamlandı", done, Tone.SUCCESS.strong))));
        }
        return metaLine("Durum", str(i1.get("status_text")));
    }

    private static Part metaLine(String label, String value) {
        if (value == null || value.isBlank()) return Part.EMPTY;
        return new Part("<p style=\"margin:4px 0;font-size:14px;line-height:22px;color:" + MailTokens.FG + "\"><strong>"
                + esc(label) + ":</strong> " + esc(value) + "</p>", label + ": " + value);
    }

    /** Takip linki satırı — URL açık yazılmaz; tıklanabilir metin etikettir
     *  (kullanıcı isteği: mailde çıplak URL paylaşılmasın). */
    private static Part linkLine(String label, String url) {
        if (url == null || url.isBlank()) return Part.EMPTY;
        // BD1 (bug regresyon 2026-09-27): takip bağlantıları raporun kullanıcı girdisidir ve sunucuda doğrulanmıyor;
        // izinli şema (http/https/mailto) değilse TIKLANAMAZ düz metin — değer görünür kalır, bağlantı kurulmaz.
        if (!MailKit.safeHref(url)) {
            return new Part("<p style=\"margin:4px 0;font-size:14px;line-height:22px;color:" + MailTokens.FG + "\">"
                    + esc(label) + ": <span style=\"word-break:break-word;overflow-wrap:anywhere\">" + esc(url) + "</span></p>",
                    label + ": " + url);
        }
        return new Part("<p style=\"margin:4px 0;font-size:14px;line-height:22px\">" + MailKit.link(url, label) + "</p>", label + ": " + url);
    }

    /** PO'ya onay bekleyen rapor bilgilendirmesi. */
    public String buildWeeklyReportSubmittedHtml(String teamName, String weekLabel, String submittedBy,
                                                 String approveUrl) {
        return simpleDoc(
                "[Site Monitor] " + teamName + " — " + weekLabel + " raporu onayınızı bekliyor",
                teamName + " ekibinin " + weekLabel + " haftalık raporu "
                + (submittedBy != null ? submittedBy : "ekip üyesi")
                + " tarafından onayınıza sunuldu. Aşağıdaki butonla (giriş yapmadan) doğrudan "
                + "onaylayabilir ya da Site Monitor → Raporlar → Haftalık Raporlar ekranından "
                + "inceleyip düzeltme talebiyle iade edebilirsiniz.",
                Tone.INFO, "ONAY BEKLİYOR", false, approveUrl, "Raporu onaylamak için tıklayınız →").html();
    }

    /** Takıma iade/düzeltme talebi bildirimi — not satır sonlarıyla korunur. */
    public String buildWeeklyReportRejectedHtml(String teamName, String weekLabel,
                                                String note, String rejectedBy) {
        String by = rejectedBy != null ? rejectedBy : "PO";
        MailDoc d = MailDoc.create("[Site Monitor] " + teamName + " — " + weekLabel + " raporu iade edildi")
                .preheader(weekLabel + " haftalık raporunuz " + by + " tarafından düzeltme talebiyle iade edildi.")
                .kicker("Haftalık Rapor");
        d.badges(Badge.tint("İADE EDİLDİ", Tone.WARNING));
        d.title(nzs(teamName) + " — " + nzs(weekLabel) + " raporu iade edildi", null);
        d.paragraph(weekLabel + " haftalık raporunuz " + by + " tarafından düzeltme talebiyle iade edildi.");
        // Not çok satırlı olabilir (madde listesi) — escBr ile satır sonları korunur (eskiden tek paragrafa eziliyordu).
        d.alert(Tone.WARNING, "Düzeltme notu", note != null && !note.isBlank() ? note : "—");
        d.paragraph("Raporu güncelleyip tekrar onaya gönderebilirsiniz.");
        d.footerMeta("Site Monitor — Haftalık Rapor", nowStamp());
        return d.html();
    }

    /** Cuma hatırlatma maili. Henüz raporunu girmemiş SY takımlarına son giriş hatırlatması +
     *  "nasıl girilir" kısa kılavuz + doğrudan Haftalık Raporlar'a giden CTA. Mail her zaman TR. */
    public String buildWeeklyReportReminderHtml(String teamName, String weekLabel, String reportUrl) {
        return buildWeeklyReportReminderHtml(teamName, weekLabel, reportUrl, "bugün saat 15:00");
    }

    /** {@code deadlineText} = "bugün saat 15:00" / "Perşembe saat 17:00" — canlı ayardan (2026-09-12). */
    public String buildWeeklyReportReminderHtml(String teamName, String weekLabel, String reportUrl, String deadlineText) {
        MailDoc d = MailDoc.create("[Site Monitor] " + nzs(teamName) + " — Haftalık rapor hatırlatması (" + nzs(weekLabel) + ")")
                .preheader("Bu haftanın raporu henüz girilmedi — son giriş " + nzs(deadlineText))
                .kicker("Haftalık Rapor Hatırlatması");
        d.badges(Badge.tint("HATIRLATMA", Tone.WARNING));
        d.title(nzs(teamName), weekLabel);
        d.paragraphHtml("<strong>Sayın " + esc(teamName) + " ekibi,</strong>", "Sayın " + nzs(teamName) + " ekibi,");
        d.paragraphHtml("Bu haftanın (<strong>" + esc(weekLabel) + "</strong>) haftalık raporu sistemde henüz görünmüyor. "
                        + "Mesai başlangıcıyla birlikte raporunuzu hatırlatmak isteriz.",
                "Bu haftanın (" + nzs(weekLabel) + ") haftalık raporu sistemde henüz görünmüyor. Mesai başlangıcıyla birlikte raporunuzu hatırlatmak isteriz.");
        d.alertHtml(Tone.WARNING, "Son giriş: " + esc(deadlineText),
                "Lütfen bu haftanın raporunu Site Monitor üzerinden zamanında giriniz.",
                "Son giriş: " + nzs(deadlineText) + " — lütfen bu haftanın raporunu Site Monitor üzerinden zamanında giriniz.");
        d.button(reportUrl, "Haftalık raporu girmek için tıklayınız →");
        // "Nasıl girilir?" — sabit (güvenilir) HTML; <strong> kaçırılmaz
        List<String> stepsHtml = List.of(
                "Sol menüden <strong>Raporlar → Haftalık Raporlar</strong>'a gidin.",
                "<strong>Yeni Hafta Raporu</strong> ile yıl/hafta seçip <strong>Oluştur</strong>'a tıklayın.",
                "Dört maddeyi doldurun: Proaktif İyileştirmeler · Olay/Problem/Postmortem · Katılımlar · Domain bazlı kritik işler.",
                "<strong>Kaydet</strong>; hazır olunca <strong>Onaya Gönder</strong>.",
                "PO onayından sonra rapor müdüre otomatik iletilir.");
        List<String> stepsText = List.of(
                "Sol menüden Raporlar → Haftalık Raporlar'a gidin.",
                "Yeni Hafta Raporu ile yıl/hafta seçip Oluştur'a tıklayın.",
                "Dört maddeyi doldurun: Proaktif İyileştirmeler · Olay/Problem/Postmortem · Katılımlar · Domain bazlı kritik işler.",
                "Kaydet; hazır olunca Onaya Gönder.",
                "PO onayından sonra rapor müdüre otomatik iletilir.");
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < stepsText.size(); i++) t.append(i + 1).append(". ").append(stepsText.get(i)).append('\n');
        d.card("Haftalık Rapor Nasıl Girilir?", null, MailKit.steps(stepsHtml), t.toString());
        d.footerMeta("Site Monitor — Otomatik Hatırlatma", "Oluşturuldu: " + nowStamp());
        return d.html();
    }

    // ── Alan adı süre-bitişi hatırlatması (2026-09-22, madde E) ─────────────────────

    /**
     * Eşik hatırlatması: "X alan adının kaydı N gün sonra doluyor". CTA izleme detayına derin
     * bağlantı (?tab=domain&monitor=id); base URL canlı ayardan.
     */
    public String buildDomainExpiryReminderHtml(String name, String domain, int days, String expiryIso, int threshold,
                                                String registrar, String level, Long monitorId) {
        boolean critical = "CRITICAL".equals(level);
        boolean expired = days < 0;
        Tone tone = critical || expired ? Tone.DESTRUCTIVE : "WARNING".equals(level) ? Tone.WARNING : Tone.INFO;
        String expiryText = expiryIso == null ? "—" : expiryIso.length() >= 10 ? expiryIso.substring(8, 10) + "." + expiryIso.substring(5, 7) + "." + expiryIso.substring(0, 4) : expiryIso;
        String headline = expired
                ? nzs(name) + " — alan adı kaydı " + Math.abs(days) + " gün önce doldu"
                : nzs(name) + " — alan adı bitişine " + days + " gün";
        String url = liveBaseUrl().isBlank() || monitorId == null ? "" : liveBaseUrl() + "/?tab=domain&monitor=" + monitorId;
        String daysText = expired ? Math.abs(days) + " gün önce doldu" : days + " gün";

        MailDoc d = MailDoc.create("[Site Monitor] " + headline)
                .preheader(nzs(domain) + " · bitiş " + expiryText + " · " + threshold + " gün hatırlatma eşiği")
                .kicker("Alan Adı İzleme");
        d.badges(tone == Tone.DESTRUCTIVE ? Badge.solid(critical ? "KRİTİK" : "SÜRESİ DOLDU", tone)
                        : Badge.tint("WARNING".equals(level) ? "UYARI" : "BİLGİ", tone),
                Badge.outline("Hatırlatma · " + threshold + " gün eşiği"));
        d.title(headline, nzs(domain) + " · bitiş " + expiryText);
        d.metricCard(String.valueOf(Math.abs(days)), expired ? "gün önce doldu" : "gün kaldı", tone.strong,
                expired ? null : Math.round(Math.max(0, days) * 100f / 90), "Bitiş tarihi: " + expiryText);
        d.keyValue(MailDoc.rows(
                new Row("Alan adı", MailKit.strong(domain, null), nzs(domain)),
                Row.of("Bitiş tarihi", expiryText),
                new Row("Kalan gün", MailKit.strong(daysText, tone.strong), daysText),
                Row.of("Registrar", registrar == null || registrar.isBlank() ? "—" : registrar),
                Row.of("Hatırlatma eşiği", threshold + " gün")));
        d.noteHtml("Bu ileti izlemenin <strong>" + threshold + " gün</strong> hatırlatma eşiği için <strong>bir kez</strong> gönderilir; "
                        + "sonraki eşiklerde (daha az gün kala) yeniden hatırlatılır. Alan adı yenilenince seri kendiliğinden sıfırlanır.",
                "Bu ileti izlemenin " + threshold + " gün hatırlatma eşiği için bir kez gönderilir; sonraki eşiklerde (daha az gün kala) "
                        + "yeniden hatırlatılır. Alan adı yenilenince seri kendiliğinden sıfırlanır.");
        d.button(url, "Alan adı izlemesini aç →");
        List<String> stepsHtml = List.of(
                "Registrar panelinde alan adını yenileyin (otomatik yenileme açıksa ödeme yöntemini doğrulayın).",
                "Yenileme sonrası Site Monitor'de <strong>Şimdi Kontrol Et</strong> ile bitiş tarihinin ileri gittiğini görün — bu hatırlatma serisi yeni bitiş için sıfırdan başlar.",
                "Transfer kilidi yoksa (kartta \"Kilit yok\") registrar'dan kilidi açtırın.");
        d.card("Ne yapmalı?", null, MailKit.steps(stepsHtml),
                "1. Registrar panelinde alan adını yenileyin (otomatik yenileme açıksa ödeme yöntemini doğrulayın).\n"
                + "2. Yenileme sonrası Site Monitor'de Şimdi Kontrol Et ile bitiş tarihinin ileri gittiğini görün.\n"
                + "3. Transfer kilidi yoksa registrar'dan kilidi açtırın.");
        d.footerMeta("Site Monitor — Otomatik Hatırlatma", "Oluşturuldu: " + nowStamp());
        return d.html();
    }

    // ── Sessiz saat özeti (2026-10-01, onaylı öneri 15) ─────────────────────────────

    /**
     * Özetin tek satırı. {@code openedAt}/{@code resolvedAt} UTC ISO (gösterimde Europe/Istanbul); {@code target} = görünen
     * hedef adı (monitör adı ya da şema-soyulmuş adres); {@code typeLabel} = okunur alarm türü.
     */
    public record QuietDigestRow(Long alertId, String level, String target, String typeLabel,
                                 String openedAt, String resolvedAt, boolean resolved) {}

    /** Sessiz saat özeti konusu — gönderim ve galeri aynı metni kullanır. */
    public static String quietDigestSubject(String teamName, List<QuietDigestRow> rows) {
        int total = rows == null ? 0 : rows.size();
        long open = rows == null ? 0 : rows.stream().filter(r -> !r.resolved()).count();
        return "[Site Monitor] Sessiz saat özeti · " + nzs(teamName) + " · " + total + " alarm"
                + (open > 0 ? " (" + open + " açık)" : " (tümü çözüldü)");
    }

    /**
     * Takımın sessiz saat penceresi bitince giden TEK özet: pencerede bildirimi ertelenen alarmlar, "hâlâ açık" ve
     * "pencerede çözüldü" olarak iki tabloda; her satır Alarm Geçmişi'ndeki kayda derin bağlantı. KRİTİK alarmlar bu
     * özete hiç girmez (pencerede de hemen gönderilir) — not bunu söyler.
     */
    public String buildQuietDigestHtml(String teamName, String windowLabel, List<QuietDigestRow> rows) {
        List<QuietDigestRow> all = rows == null ? List.of() : rows;
        List<QuietDigestRow> open = all.stream().filter(r -> !r.resolved()).toList();
        List<QuietDigestRow> closed = all.stream().filter(QuietDigestRow::resolved).toList();
        String base = liveBaseUrl();
        MailDoc d = MailDoc.create(quietDigestSubject(teamName, all))
                .preheader(all.size() + " alarm sessiz saatte ertelendi · " + open.size() + " hâlâ açık · " + closed.size() + " çözüldü")
                .kicker("Sessiz Saat Özeti");
        d.badges(open.isEmpty() ? Badge.tint("TÜMÜ ÇÖZÜLDÜ", Tone.SUCCESS) : Badge.tint(open.size() + " AÇIK", Tone.WARNING),
                Badge.outline("Pencere · " + nzs(windowLabel)));
        d.title(nzs(teamName) + " — sessiz saat özeti",
                "Sessiz saat penceresinde (" + nzs(windowLabel) + ", Europe/Istanbul) bildirimi ertelenen alarmlar.");
        d.stats(List.of(Stat.of("Ertelenen alarm", String.valueOf(all.size())),
                new Stat("Hâlâ açık", String.valueOf(open.size()), open.isEmpty() ? null : Tone.WARNING.strong, null),
                new Stat("Pencerede çözüldü", String.valueOf(closed.size()), closed.isEmpty() ? null : Tone.SUCCESS.strong, null)));
        List<Col> cols = List.of(Col.nw("Seviye"), Col.of("Hedef"), Col.opt("Tür"), Col.nw("Açıldı"));
        if (!open.isEmpty()) {
            d.heading("Hâlâ açık", "Ertelenen bildirim bu özettir; günlük hatırlatma bu andan itibaren normal aralığıyla sürer.");
            d.table(cols, open.stream().map(r -> quietDigestCells(r, base, false)).toList());
        }
        if (!closed.isEmpty()) {
            d.heading("Pencerede çözüldü", "Bu alarmlar için ayrıca \"çözüldü\" e-postası gönderilmedi.");
            List<Col> closedCols = List.of(Col.nw("Seviye"), Col.of("Hedef"), Col.opt("Tür"), Col.nw("Açıldı"), Col.nw("Çözüldü"));
            d.table(closedCols, closed.stream().map(r -> quietDigestCells(r, base, true)).toList());
        }
        d.note("KRİTİK alarmlar sessiz saatte de hemen gönderilir ve bu özete girmez. Sessiz saat penceresini takım ayarlarından "
                + "(Takım Yönetimi → takımı düzenle) değiştirebilir ya da kaldırabilirsiniz.");
        d.button(base.isBlank() ? "" : base + "/?tab=alerthistory", "Alarm Geçmişini aç →");
        d.footerMeta("Site Monitor — Sessiz Saat Özeti", "Oluşturuldu: " + nowStamp());
        return d.html();
    }

    private List<Cell> quietDigestCells(QuietDigestRow r, String base, boolean withResolved) {
        Tone tone = EmailTemplateBuilder.severityTone(r.level());
        String target = nzs(r.target());
        String url = base.isBlank() || r.alertId() == null ? ""
                : base + "/?tab=alerthistory&alert=" + r.alertId() + (r.resolved() ? "&view=closed" : "");
        List<Cell> cells = new ArrayList<>(List.of(
                Cell.of(EscalationService.levelWordTr(r.level()), tone.strong, true),
                url.isBlank() ? Cell.of(target) : Cell.html(MailKit.link(url, target), target),
                Cell.of(nzs(r.typeLabel())),
                Cell.of(fmtOrDash(formatIstanbul(r.openedAt())))));
        if (withResolved) cells.add(Cell.of(fmtOrDash(formatIstanbul(r.resolvedAt()))));
        return cells;
    }

    // ── Haftalık Erişilebilirlik (availability) e-postası ───────────────────────

    /** E-posta görünüm DTO'ları — view katmanı kendi girdilerini sahiplenir (servis bağımlılığı
     *  email→report yönünde değil). availabilityPct/avgMs/p95Ms/certDays null = veri yok. */
    public record AvailabilityRow(String domain, Double availabilityPct,
                                  int outageCount, long downtimeMinutes, long longestOutageMinutes,
                                  Long avgMs, Long p95Ms, Integer certDaysRemaining) {}
    /** Recovery e-postası erişilebilirlik özeti (son 24s / 7g). pct null → veri yok, kart basılmaz. */
    public record UptimeSummary(Double pct24h, int outages24h, Double pct7d, int outages7d) {}
    public record AvailabilitySummary(int domainCount, int withDataCount, Double avgAvailabilityPct,
                                      String bestDomain, Double bestPct, String worstDomain, Double worstPct,
                                      int downDomainCount, Integer nearestCertDays) {}

    /**
     * Mailin ekindeki kesinti raporunu gövdede duyurmak için gereken bilgi.
     *
     * <p>Ek sessizce iliştirilirse okuyan çoğu zaman fark etmez — özellikle telefonda. Gövdede
     * ekin ADI ve NE İÇERDİĞİ yazılıysa hem bulunur hem de açmaya değip değmeyeceği anlaşılır.
     */
    public record AttachmentInfo(String fileName, int alarmCount, int stillOpenCount,
                                 int affectedTargets, int monitorTypeCount) {}

    /**
     * Haftalık rapordaki Sayfa Hızı bölümü için tek satır.
     *
     * @param avgLoadMs     bu haftanın ortalama toplam yükleme süresi (null = hiç ölçüm yok)
     * @param prevAvgLoadMs GEÇEN haftanın ortalaması — trend oku bundan hesaplanır (null = kıyas yok)
     * @param breachedChecks bu hafta eşik aşan ölçüm sayısı
     */
    public record PageSpeedWeeklyRow(String name, String url, Long avgLoadMs, Long prevAvgLoadMs,
                                     long breachedChecks) {}

    /** Sayfa Hızı bölümü: en yavaş N sayfa + kaç izlemenin eşiği aşıldığı. */
    public record PageSpeedWeekly(List<PageSpeedWeeklyRow> slowest, int monitorCount, int breachedMonitorCount) {}

    /**
     * Haftalık rapordaki "Sürüm &amp; Dağıtım" satırı (E2): rapor penceresindeki dağıtım/yeniden
     * başlatma/geri alma sayıları ve haftanın ilk→son sürümü. {@code null} = veri toplanamadı (satır
     * çizilmez); sıfır dağıtım ise "dağıtım yapılmadı" der (satır yine çizilir).
     */
    public record DeploymentWeekly(int deployments, String fromVersion, String toVersion, int restarts, int rollbacks) {}

    /** Haftalık rapordaki alan adı (registrar) bitiş satırı (2026-09-22, madde G). plannedAt null = plan yok. */
    public record DomainExpiryWeeklyRow(String domain, Integer daysRemaining, String expiryDate, String registrar,
                                        String transferLock, String plannedAt, boolean planOverdue) {}
    /** Alan adı bölümü: pencere içindeki satırlar + izlenen toplam + kilitsiz sayısı. rows boş olabilir. */
    public record DomainExpiryWeekly(List<DomainExpiryWeeklyRow> rows, int monitorCount, int windowDays, int unlockedCount) {}

    /** Zayıf algoritma satırı (2026-09-12): takımın alanlarında zayıf sertifika sayısı + taranan alan sayısı. */
    public record WeakAlgoWeekly(int weak, int scanned) {}

    /** Geriye uyumlu: ek yokken (ya da üretilemediğinde) gövdede ek bandı çizilmez. */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s) {
        return buildWeeklyAvailabilityHtml(teamName, weekLabel, rows, s, null, null);
    }

    /** Geriye uyumlu: Sayfa Hızı bölümü olmadan. */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s,
                                              AttachmentInfo att) {
        return buildWeeklyAvailabilityHtml(teamName, weekLabel, rows, s, att, null);
    }

    /** Sertifika sahibi takıma haftalık erişilebilirlik özeti. rows en kötü availability üstte sıralı
     *  gelir; down domainler ayrı vurgulanır. {@code att} doluysa gövdeye ek duyurusu eklenir. */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s,
                                              AttachmentInfo att, PageSpeedWeekly ps) {
        return buildWeeklyAvailabilityHtml(teamName, weekLabel, rows, s, att, ps, null);
    }

    /** {@code dep} doluysa gövdeye "Sürüm &amp; Dağıtım" bandı eklenir (E2). */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s,
                                              AttachmentInfo att, PageSpeedWeekly ps, DeploymentWeekly dep) {
        return buildWeeklyAvailabilityHtml(teamName, weekLabel, rows, s, att, ps, dep, null);
    }

    /** {@code weak} doluysa "Zayıf algoritma: N (tarandı: M)" bandı eklenir (2026-09-12). */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s,
                                              AttachmentInfo att, PageSpeedWeekly ps, DeploymentWeekly dep,
                                              WeakAlgoWeekly weak) {
        return buildWeeklyAvailabilityHtml(teamName, weekLabel, rows, s, att, ps, dep, weak, null);
    }

    /** {@code dom} doluysa "Alan Adı Bitişleri" bölümü eklenir (2026-09-22, madde G): 90 gün içinde bitenler + kilitsizler. */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s,
                                              AttachmentInfo att, PageSpeedWeekly ps, DeploymentWeekly dep,
                                              WeakAlgoWeekly weak, DomainExpiryWeekly dom) {
        return buildWeeklyAvailabilityHtml(teamName, weekLabel, rows, s, att, ps, dep, weak, dom, null);
    }

    /**
     * Haftalık Erişilebilirlik e-postası (yeniden tasarım 2026-09-28): gövde {@link WeeklyAvailabilityMail}'de kurulur
     * (hüküm, KPI'lar + geçen haftaya göre değişim, izleme türü çubukları, en kötü domainler, haftanın alarmları,
     * yaklaşan sertifikalar, derin bağlantılar). {@code ins} null → alarm/tür bölümleri ve değişim çipi çizilmez
     * (eski çağıranlar). Bağlantılar CANLI taban adresten ({@link #liveBaseUrl()}).
     */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s,
                                              AttachmentInfo att, PageSpeedWeekly ps, DeploymentWeekly dep,
                                              WeakAlgoWeekly weak, DomainExpiryWeekly dom,
                                              WeeklyAvailabilityMail.Insights ins) {
        return WeeklyAvailabilityMail.build(new WeeklyAvailabilityMail.Input(teamName, weekLabel, rows, s, att, ps, dep, weak, dom,
                ins, liveBaseUrl(), nowStamp())).html();
    }

    // ── Aylık sertifika envanteri raporu ─────────────────────────────────────

    /**
     * Aylık sertifika envanteri raporu — düzen {@link com.sitemonitor.service.mail.CertInventoryMail}'de (yeniden tasarım
     * 2026-09-28: hüküm satırı, KPI kutuları, kalan süre çubuğu, "Önümüzdeki 30 gün", hijyen kartları, kırılımlar, ekler).
     * Burada yalnız CANLI taban adres ({@code site.monitor.app.base-url}) ve oluşturma damgası eklenir.
     */
    public String buildCertInventoryReportHtml(com.sitemonitor.service.mail.CertInventoryMail.Report report) {
        return com.sitemonitor.service.mail.CertInventoryMail.build(report, liveBaseUrl(), nowStamp()).html();
    }

    private static String nzText(String s) { return s == null || s.isBlank() ? "—" : s; }

    // pctColor / pctText: haftalık e-postayla birlikte WeeklyAvailabilityMail'e taşındı (2026-09-28).

    // ── Olay & Hata bildirimi (manuel SRE kaydı) ─────────────────────────────

    /** 4-arg: mail gönderimi için (forEmail=true → görseller CID inline). */
    public String buildIncidentNotificationHtml(Map<String, Object> inc, String managerName,
                                                String kind, String ctaUrl) {
        return buildIncidentNotificationHtml(inc, managerName, kind, ctaUrl, true);
    }

    /**
     * Olay & Hata bildirimi — önem rozeti, künye, RCA / iş etkisi / çözüm bölümleri ve olayı açma
     * CTA'sı. {@code inc} = controller dto (snake_case alanlar). forEmail=false: UI önizlemesi (iframe) —
     * markdown görselleri /api/incidents/images/{id} URL'siyle kalır (CID'e çevrilmez).
     */
    public String buildIncidentNotificationHtml(Map<String, Object> inc, String managerName,
                                                String kind, String ctaUrl, boolean forEmail) {
        boolean resolved = "RESOLVED".equals(kind);
        boolean isNew = "NEW".equals(kind);
        String sev = str(inc.get("severity"));
        String title = str(inc.get("title"));
        String teamName = str(inc.get("team_name"));
        String status = str(inc.get("status"));
        String eyebrow = resolved ? "Olay Çözüldü" : isNew ? "Yeni Olay Bildirimi" : "Olay Güncellendi";
        Tone sevTone = switch (sev) {
            case "CRITICAL" -> Tone.DESTRUCTIVE;
            case "HIGH", "MEDIUM" -> Tone.WARNING;
            case "LOW" -> Tone.NEUTRAL;
            default -> Tone.INFO;
        };

        MailDoc d = MailDoc.create("[Site Monitor] " + eyebrow + " — " + title).wide()
                .preheader(sevBadgeText(sev) + " · " + teamName + " — " + title)
                .kicker(eyebrow);
        d.badges(sevTone == Tone.DESTRUCTIVE ? Badge.solid(sevBadgeText(sev), sevTone) : Badge.tint(sevBadgeText(sev), sevTone),
                Badge.tint(statusText(status), statusTone(status)));
        d.title(title, sevBadgeText(sev) + " · " + teamName);
        String manager = managerName != null && !managerName.isBlank() ? managerName : "Yetkili";
        d.paragraphHtml("<strong>Sayın " + esc(manager) + ",</strong>", "Sayın " + manager + ",");
        d.paragraphHtml(resolved ? "Ekibinize ait bir olay/hata kaydı <strong>çözüldü</strong>. Çözüm özeti aşağıdadır."
                        : isNew ? "Ekibinize ait yeni bir olay/hata kaydı oluşturuldu. Yönetici özeti aşağıdadır."
                        : "Ekibinize ait bir olay/hata kaydı güncellendi. Güncel yönetici özeti aşağıdadır.",
                resolved ? "Ekibinize ait bir olay/hata kaydı çözüldü. Çözüm özeti aşağıdadır."
                        : isNew ? "Ekibinize ait yeni bir olay/hata kaydı oluşturuldu. Yönetici özeti aşağıdadır."
                        : "Ekibinize ait bir olay/hata kaydı güncellendi. Güncel yönetici özeti aşağıdadır.");
        if (resolved) {
            String resolvedAt = fmtOrDash(formatIstanbul(str(inc.get("resolved_at"))));
            String dur = inc.get("duration_minutes") != null ? inc.get("duration_minutes") + " dk" : "—";
            d.alertHtml(Tone.SUCCESS, "Bu olay çözüldü",
                    "Çözülme: <strong>" + esc(resolvedAt) + "</strong> &middot; Süre: <strong>" + esc(dur) + "</strong>",
                    "Bu olay çözüldü — Çözülme: " + resolvedAt + " · Süre: " + dur);
        }
        d.button(ctaUrl, "Olay kaydını açmak için tıklayınız →");

        List<Row> facts = new ArrayList<>();
        facts.add(Row.of("Önem", sevBadgeText(sev)));
        facts.add(new Row("Durum", MailKit.strong(statusText(status), statusColor(status)), statusText(status)));
        facts.add(Row.of("Takım", nzText(teamName)));
        facts.add(Row.of("Kanal", nzText(str(inc.get("channel")))));
        facts.add(Row.of("Servis / Domain", nzText(str(inc.get("service")))));
        facts.add(Row.of("Kategori", nzText(str(inc.get("category")))));
        facts.add(Row.of("Oluş Zamanı", fmtOrDash(formatIstanbul(str(inc.get("occurred_at"))))));
        facts.add(Row.of("Tespit Zamanı", fmtOrDash(formatIstanbul(str(inc.get("detected_at"))))));
        facts.add(Row.of("Çözülme Zamanı", fmtOrDash(formatIstanbul(str(inc.get("resolved_at"))))));
        if (inc.get("duration_minutes") != null) facts.add(Row.of("Süre", inc.get("duration_minutes") + " dk"));
        if (Boolean.TRUE.equals(inc.get("sla_breached"))) facts.add(new Row("SLA", MailKit.strong("İHLAL EDİLDİ", Tone.DESTRUCTIVE.strong), "İHLAL EDİLDİ"));
        if (inc.get("error_budget_burn_pct") != null) facts.add(Row.of("Error Budget Tüketimi", inc.get("error_budget_burn_pct") + "%"));
        d.card("Olay Künyesi", null, MailKit.keyValue(facts), kvText(facts));

        incidentSection(d, "Kök Neden (RCA)", str(inc.get("rca_summary")), forEmail, true);
        incidentSection(d, "Teknik Açıklama", str(inc.get("description")), forEmail, false);
        incidentSection(d, "İş Etkisi", str(inc.get("business_impact")), forEmail, true);
        incidentSection(d, "Çözüm / Müdahale Adımları", str(inc.get("resolution_steps")), forEmail, true);
        d.button(ctaUrl, "Olay kaydını açmak için tıklayınız →");
        d.footerMeta("Site Monitor — Olay & Hata Bildirimi", "Oluşturuldu: " + nowStamp());
        return d.html();
    }

    private static String kvText(List<Row> rows) {
        StringBuilder t = new StringBuilder();
        for (Row r : rows) t.append(r.label()).append(": ").append(r.text()).append('\n');
        return t.toString();
    }

    /** Olay bölüm kartı (markdown gövde); {@code always=false} ise boş gövdede kart çizilmez. */
    private void incidentSection(MailDoc d, String title, String md, boolean forEmail, boolean always) {
        String body = textBlock(md, forEmail);
        if (body.isBlank() && !always) return;
        d.card(title, null, body.isBlank() ? MailKit.note("—") : body, md == null || md.isBlank() ? "—" : md);
    }

    /** Markdown metin → e-posta-güvenli paragraf: görsel sözdizimini at, escape + satır sonu→&lt;br&gt;. */
    private static final Pattern INC_CID_IMG = Pattern.compile("<img src=\"cid:incimg(\\d+)\"");
    private static final Pattern INC_API_IMG = Pattern.compile("<img src=\"(/api/incidents/images/\\d+)\"");
    /** Olay görseli tavanı: 640 rapor kartı − 2×24 − bölüm kartı boşlukları. */
    private static final int INC_IMG_MAX_WIDTH = 556;

    /** Olay markdown alanı → HTML: TAM markdown (GFM tablo/liste/kalın/görev kutusu) + gömülü görseller.
     *  forEmail=true → /api/incidents/images/{id} CID inline (mail; InlineImage'ları IncidentNotificationService
     *  yükler). forEmail=false → /api URL korunur (iframe önizleme, oturum çerezi ile yüklenir).
     *  Outlook head&lt;style&gt;'ı yok saydığından blok stilleri inline edilir. escapeHtml=true → ham HTML güvenli. */
    private String textBlock(String md, boolean forEmail) {
        if (md == null || md.isBlank()) return "";
        String src = forEmail
                ? md.replaceAll("\\]\\(/api/incidents/images/(\\d+)\\)", "](cid:incimg$1)")
                : md;
        String html = taskCheckboxesToSymbols(MD_RENDERER.render(MD_PARSER.parse(src)));
        html = forEmail
                ? INC_CID_IMG.matcher(html).replaceAll(
                    "<img width=\"" + INC_IMG_MAX_WIDTH + "\" border=\"0\" alt=\"Olay görseli\" style=\"display:block;width:100%;max-width:"
                    + INC_IMG_MAX_WIDTH + "px;height:auto;border-radius:8px;margin:8px 0;border:1px solid " + MailTokens.BORDER + "\" src=\"cid:incimg$1\"")
                : INC_API_IMG.matcher(html).replaceAll(
                    "<img style=\"display:block;max-width:100%;height:auto;border-radius:8px;margin:8px 0;"
                    + "border:1px solid " + MailTokens.BORDER + "\" src=\"$1\"");
        return inlineBlockStyles(html);
    }

    private static String fmtOrDash(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    private static String sevBadgeText(String sev) {
        if (sev == null || sev.isBlank()) return "—";
        return switch (sev) {
            case "CRITICAL" -> "KRİTİK"; case "HIGH" -> "YÜKSEK";
            case "MEDIUM"   -> "ORTA";   case "LOW"  -> "DÜŞÜK";
            default -> sev;
        };
    }

    private static String statusText(String st) {
        if (st == null || st.isBlank()) return "—";
        return switch (st) {
            case "OPEN" -> "Açık"; case "INVESTIGATING" -> "İnceleniyor";
            case "MITIGATED" -> "Hafifletildi"; case "RESOLVED" -> "Çözüldü";
            default -> st;
        };
    }

    private static Tone statusTone(String st) {
        if (st == null) return Tone.NEUTRAL;
        return switch (st) {
            case "RESOLVED"      -> Tone.SUCCESS;
            case "OPEN"          -> Tone.DESTRUCTIVE;
            case "INVESTIGATING", "MITIGATED" -> Tone.WARNING;
            default              -> Tone.NEUTRAL;
        };
    }

    /** Durum rengi (künye "Durum" hücresi) — severity renk deseninin eşi. */
    private static String statusColor(String st) {
        return statusTone(st).strong;
    }

    // ── Haftalık rapor özet bloğu (KPI / skor / izleme göstergeleri / alan adı koruması) ──

    /** Özet + KPI + izleme göstergeleri + alan adı koruması — "Haftalık Özet ve Göstergeler" kartının gövdesi.
     *  İçerik yoksa (eski/veri-yok) boş döner → kart hiç çizilmez. */
    private Part weeklyOverview(Map<String, Object> k) {
        return join(weeklySummaryBlock(k), weeklyKpiBlock(k), weeklyMonitoringBlock(k), weeklyDomainProtectionBlock(k));
    }

    /** Haftalık rapor KPI özeti — canlı cert/alarm/uptime (WeeklyReportKpiService.current).
     *  Anahtarlar: total_certs, expiring, alarms, critical, uptime_pct. null/boş → hiç gösterilmez. */
    private Part weeklyKpiBlock(Map<String, Object> k) {
        if (k == null || k.isEmpty()) return Part.EMPTY;
        Object up = k.get("uptime_pct");
        String uptime = up instanceof Number n ? String.format(java.util.Locale.US, "%.2f%%", n.doubleValue()) : "—";
        Part stats = statsPart(List.of(
                numStat("Toplam Sertifika", k.get("total_certs"), null),
                numStat("Bu Hafta Dolan", k.get("expiring"), Tone.WARNING.strong),
                numStat("Açılan Alarm", k.get("alarms"), Tone.DESTRUCTIVE.strong),
                numStat("Kritik ≤7", k.get("critical"), Tone.DESTRUCTIVE.strong),
                new Stat("Uptime", uptime, Tone.SUCCESS.strong, null)));
        return new Part(subHead("Haftalık Özet") + stats.html(), "Haftalık Özet: " + stats.text());
    }

    /** İç başlık (kart içinde küçük bölüm etiketi). */
    private static String subHead(String t) {
        return "<p style=\"margin:10px 0 6px;font-size:12px;line-height:16px;font-weight:600;letter-spacing:0.04em;color:"
                + MailTokens.MUTED + "\">" + esc(t.toUpperCase(java.util.Locale.forLanguageTag("tr"))) + "</p>";
    }

    /**
     * Alan adı koruması — kara listede / transfer kilidi olmayan domain sayısı.
     *
     * <p>"Doğrulanamadı" AYRI yazılır: kilidi doğrulayamamak ile kilidin olmaması aynı şey
     * değildir ve tek rakama katmak yönetime yanlış bir tablo gösterirdi. Sorun yoksa bölüm
     * yine çizilir — "her şey yolunda" da bir bilgidir.
     */
    private Part weeklyDomainProtectionBlock(Map<String, Object> k) {
        Object p = k == null ? null : k.get("domain_protection");
        if (!(p instanceof Map<?, ?> prot)) return Part.EMPTY;
        int total = prot.get("total") instanceof Number n ? n.intValue() : 0;
        if (total == 0) return Part.EMPTY;
        int listed = prot.get("listed") instanceof Number n ? n.intValue() : 0;
        int unlocked = prot.get("unlocked") instanceof Number n ? n.intValue() : 0;
        int unverified = prot.get("lock_unverified") instanceof Number n ? n.intValue() : 0;
        List<Row> rows = new ArrayList<>();
        rows.add(protRow("İzlenen alan adı", total, MailTokens.FG));
        rows.add(protRow("Kara listede", listed, listed > 0 ? Tone.DESTRUCTIVE.strong : Tone.SUCCESS.strong));
        rows.add(protRow("Transfer kilidi yok", unlocked, unlocked > 0 ? Tone.DESTRUCTIVE.strong : Tone.SUCCESS.strong));
        if (unverified > 0) rows.add(protRow("Kilit doğrulanamadı", unverified, MailTokens.MUTED));
        return new Part(subHead("Alan Adı Koruması") + MailKit.keyValue(rows), "Alan Adı Koruması:\n" + kvText(rows));
    }

    private static Row protRow(String label, int value, String color) {
        return new Row(label, MailKit.strong(String.valueOf(value), color), String.valueOf(value));
    }

    /** İzleme göstergeleri — tür başına tek satır (tür · izleme · erişim% · sorun).
     *  Anahtar: monitoring (List&lt;Map&gt;: type/active/success_pct/alarms). İzlemesi 0 olan tür satırı gizlenir; hiç yoksa boş. */
    private Part weeklyMonitoringBlock(Map<String, Object> k) {
        Object mon = k == null ? null : k.get("monitoring");
        if (!(mon instanceof List<?> list) || list.isEmpty()) return Part.EMPTY;
        // Etiketler KANONİK katalogdan. Buradaki yerel kopyada "scripted" ve "page" yoktu; o türler
        // e-postada ham anahtarıyla ("scripted") yazılıyordu — bkz. MonitorTypeCatalog.
        Map<String, String> labels = MonitorTypeCatalog.LABELS_TR;
        List<List<Cell>> rows = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            int active = row.get("active") instanceof Number n ? n.intValue() : 0;
            if (active == 0) continue;
            String label = labels.getOrDefault(String.valueOf(row.get("type")), String.valueOf(row.get("type")));
            Object rate = row.get("success_pct");
            String rateStr = rate instanceof Number rn ? String.format(java.util.Locale.US, "%.1f%%", rn.doubleValue()) : "—";
            int alarms = row.get("alarms") instanceof Number an ? an.intValue() : 0;
            rows.add(List.of(Cell.of(label), Cell.of(String.valueOf(active)), Cell.of(rateStr, null, true),
                    Cell.of(String.valueOf(alarms), alarms > 0 ? Tone.DESTRUCTIVE.strong : MailTokens.MUTED, alarms > 0)));
        }
        if (rows.isEmpty()) return Part.EMPTY;
        StringBuilder t = new StringBuilder("İzleme Göstergeleri:\n");
        for (List<Cell> r : rows) t.append("- ").append(r.get(0).text()).append(" · İzleme: ").append(r.get(1).text())
                .append(" · Erişim: ").append(r.get(2).text()).append(" · Sorun: ").append(r.get(3).text()).append('\n');
        return new Part(subHead("İzleme Göstergeleri") + MailKit.space(MailKit.dataTable(
                List.of(Col.of("Tür"), Col.num("İzleme"), Col.num("Erişim"), Col.num("Sorun")), rows, false), 8), t.toString());
    }

    /** Executive özet bloğu — sağlık skoru + yönetici paragrafı + iki kompakt tablo (aksiyon, 30 gün).
     *  Anahtarlar: score/score_band/manager_text/actions/lookahead. Skor yoksa (eski/veri-yok) → boş. */
    @SuppressWarnings("unchecked")
    private Part weeklySummaryBlock(Map<String, Object> k) {
        if (k == null || k.get("score") == null) return Part.EMPTY;
        int score = ((Number) k.get("score")).intValue();
        String band = String.valueOf(k.getOrDefault("score_band", "red"));
        String bandColor = "green".equals(band) ? Tone.SUCCESS.strong : "amber".equals(band) ? Tone.WARNING.strong : Tone.DESTRUCTIVE.strong;
        String paraText = String.valueOf(k.getOrDefault("manager_text", ""));
        // Skor + paragraf: masaüstünde yan yana, telefonda alt alta (.stack).
        String head = "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
            + "<td class=\"stack\" valign=\"top\" width=\"140\" style=\"width:140px;padding:0 16px 12px 0\">"
            + MailKit.metric(String.valueOf(score), "/100", bandColor)
            + "<p style=\"margin:4px 0 0;font-size:12px;line-height:16px;font-weight:500;color:" + MailTokens.MUTED + "\">Haftalık Sağlık Skoru</p></td>"
            + "<td class=\"stack\" valign=\"top\" style=\"padding:0 0 12px;font-size:14px;line-height:22px;color:" + MailTokens.FG
            + ";word-break:break-word;overflow-wrap:anywhere\">" + esc(paraText) + "</td>"
            + "</tr></table>";
        // Önümüzdeki 30 Gün: kayıt yoksa bölüm hiç eklenmez (kullanıcı isteği) — Aksiyon tablosu ise boşken "Kayıt yok" gösterir.
        List<Map<String, Object>> lookahead = (List<Map<String, Object>>) k.get("lookahead");
        Part actions = summaryActionTable("Aksiyon Gerektirenler", (List<Map<String, Object>>) k.get("actions"));
        Part ahead = lookahead == null || lookahead.isEmpty() ? Part.EMPTY : summaryActionTable("Önümüzdeki 30 Gün", lookahead);
        return join(new Part(head, "Haftalık Sağlık Skoru: " + score + "/100\n" + paraText), actions, ahead);
    }

    /** Özet aksiyon/30-gün tablosu — tier rengi nokta (sol şerit DEĞİL), sağa hizalı gün. Boş → "Kayıt yok". */
    private Part summaryActionTable(String title, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return new Part(subHead(title) + MailKit.space(MailKit.note("Kayıt yok"), 8), title + ": Kayıt yok");
        }
        List<List<Cell>> rows = new ArrayList<>();
        StringBuilder t = new StringBuilder(title).append(":\n");
        for (Map<String, Object> a : items) {
            String type = "domain".equals(a.get("type")) ? "Domain" : "Sertifika";
            Object days = a.get("days_left");
            String name = String.valueOf(a.get("name"));
            String dot = "<span style=\"color:" + tierColor(a.get("tier")) + ";font-size:14px\">&#9679;</span>&nbsp;";
            rows.add(List.of(Cell.html(dot + esc(name) + " <span style=\"font-size:12px;font-weight:400;color:" + MailTokens.MUTED + "\">"
                            + type + "</span>", name + " (" + type + ")"),
                    Cell.of(days != null ? days + " gün" : "—", null, true)));
            t.append("- ").append(name).append(" (").append(type).append("): ").append(days != null ? days + " gün" : "—").append('\n');
        }
        return new Part(subHead(title) + MailKit.space(MailKit.dataTable(List.of(Col.of("Kayıt"), Col.num("Kalan")), rows, false), 8), t.toString());
    }

    private static String tierColor(Object tier) {
        int t = tier instanceof Number n ? n.intValue() : 0;
        return switch (t) { case 1 -> Tone.DESTRUCTIVE.strong; case 2 -> Tone.WARNING.strong; case 3 -> Tone.INFO.strong; default -> "#a1a1aa"; };
    }

    /** Hex rengi beyazla harmanlar (ratio=renk payı) → düz açık ton. 8-haneli
     *  alfa hex yerine her istemcide çalışan gerçek katı renk. */
    static String tint(String hex, double ratio) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        r = (int) Math.round(r * ratio + 255 * (1 - ratio));
        g = (int) Math.round(g * ratio + 255 * (1 - ratio));
        b = (int) Math.round(b * ratio + 255 * (1 - ratio));
        return String.format("#%02x%02x%02x", r, g, b);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : "";
    }

    /** JSON sayı/metin değerini int'e çevirir (null/parse edilemez → 0). */
    private static int intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return 0;
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }

    /** Kesinti süresi (createdAt→resolvedAt) TR formatında: "2 saat 14 dakika". */
    private String formatOutageDuration(String createdAt, String resolvedAt) {
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
            LocalDateTime a = LocalDateTime.parse(createdAt, f);
            LocalDateTime b = LocalDateTime.parse(resolvedAt, f);
            long mins = java.time.Duration.between(a, b).toMinutes();
            if (mins < 1) return "1 dakikadan az";
            long days = mins / 1440, hours = (mins % 1440) / 60, rem = mins % 60;
            StringBuilder sb = new StringBuilder();
            if (days > 0)  sb.append(days).append(" gün ");
            if (hours > 0) sb.append(hours).append(" saat ");
            if (rem > 0)   sb.append(rem).append(" dakika");
            return sb.toString().trim();
        } catch (Exception e) {
            return "—";
        }
    }

    /** Kesinti süresini StatusCake tarzı kompakt saat biçiminde döner: {@code HHH:MM:SS} (ör. 000:05:25). null → yok. */
    private String formatOutageClock(String createdAt, String resolvedAt) {
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
            long secs = java.time.Duration.between(
                    LocalDateTime.parse(createdAt, f), LocalDateTime.parse(resolvedAt, f)).getSeconds();
            if (secs < 0) secs = 0;
            return String.format("%03d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
        } catch (Exception e) {
            return null;
        }
    }

    /** Okunur Türkçe süre + kompakt saat: "5 dakika · 000:05:25". Saat üretilemezse yalnız metin. */
    private String outageDurationDisplay(String createdAt, String resolvedAt) {
        String words = formatOutageDuration(createdAt, resolvedAt);
        String clock = formatOutageClock(createdAt, resolvedAt);
        return (clock == null || "—".equals(words)) ? words : words + " · " + clock;
    }

    private static String nzs(String s) { return s == null ? "" : s; }

    /** TEK kaçış kaynağı {@link MailKit#esc} (beş karakter: href/src öznitelikleri tek ve çift tırnakla
     *  yazılıyor; içinde tek tırnak geçen geçerli bir URL özniteliği kapatıp enjeksiyona açık bırakıyordu). */
    private static String esc(String s) {
        return MailKit.esc(s);
    }
}
