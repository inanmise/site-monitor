package com.sitemonitor.service;

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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final EmailTemplateBuilder templateBuilder;   // executive-premium alarm şablonu (tek merkez)

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

    /** Kart üstü marka barı — TÜM şablon aileleri bunu kullanır (BRAND.md §5.1 lockup + ENTERPRISE).
     *  Mail başına TEK logo kuralının uygulandığı yer: lockup yalnız bu barda geçer. */
    private static String brandBar() {
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#0F1B2D' style='background-color:#0F1B2D'>"
                + "<tr><td align='left' style='padding:12px 20px'>" + BrandMailAssets.headerLockup() + "</td>"
                + "<td align='right' style='padding:12px 20px;font-size:10px;font-weight:700;letter-spacing:.18em;color:#8DA2BF'>ENTERPRISE</td>"
                + "</tr></table>";
    }

    public String sendAlert(String to, String subject, String message) {
        return sendAlert(to, subject, message, null, null, null, null, null);
    }

    public String sendAlert(String to, String subject, String message,
                            String domain, String level, String alertType,
                            Integer daysRemaining, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", to, subject);
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
            String html = buildAlertEmailHtml(subject, message, domain, level, alertType, daysRemaining, certContext);
            String text = buildAlertEmailText(subject, message, domain, level, alertType, daysRemaining, certContext);
            helper.setText(text, html);   // multipart/alternative (plain + HTML)
            BrandMailAssets.addInline(helper, html, variant);   // CID inline marka logosu (setText SONRASI)
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendAlert(String[] toAddresses, String subject, String message,
                            String domain, String level, String alertType,
                            Integer daysRemaining, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", Arrays.toString(toAddresses), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            applyFrom(helper);
            helper.setSubject(subject);
            String variant = BrandMailAssets.variantForLevel(level);
            String html = buildAlertEmailHtml(subject, message, domain, level, alertType, daysRemaining, certContext);
            helper.setText(
                    buildAlertEmailText(subject, message, domain, level, alertType, daysRemaining, certContext),
                    html);
            BrandMailAssets.addInline(helper, html, variant);   // CID inline marka logosu (setText SONRASI)
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", Arrays.toString(toAddresses), e.getMessage(), e);
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
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", Arrays.toString(toAddresses));
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            applyFrom(helper);
            helper.setSubject(subject);
            // Çözülme maili DAİMA "ok" varyantı — "sorun çözüldü, yapraklar yeşile döndü" (BRAND.md)
            String html = buildResolutionEmailHtml(domain, alertType, alertLevel,
                    daysRemaining, resolvedBy, resolvedAt, createdAt, certContext, teamNames, uptime);
            helper.setText(
                    buildResolutionEmailText(domain, alertType, resolvedBy, resolvedAt, createdAt, certContext),
                    html);
            BrandMailAssets.addInline(helper, html, "ok");
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", Arrays.toString(toAddresses), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    private String doSend(String to, MimeMessage msg, int attempt) {
        long t0 = System.currentTimeMillis();
        if (MAIL_LOG.isTraceEnabled()) {
            MAIL_LOG.trace("→ SMTP gönderim: TO={} | deneme={}/{} | {} | {}",
                    to, attempt, MAX_SEND_ATTEMPTS, describeMessage(msg), smtpContext());
        }
        try {
            currentSender().send(msg);
            long ms = System.currentTimeMillis() - t0;
            if (attempt == 1) {
                log.info("✓ E-posta gönderildi: TO={}", to);
            } else {
                log.info("✓ E-posta gönderildi (retry #{}): TO={}", attempt - 1, to);
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
                        attempt, MAX_SEND_ATTEMPTS, delay, to);
                if (MAIL_LOG.isTraceEnabled()) {
                    // Son arg `e` (Throwable) → TRACE'te tam stack de basılır.
                    MAIL_LOG.trace("⏳ SMTP 421 ayrıntı: TO={} | süre={}ms | {} | kök sebep={}",
                            to, ms, smtpContext(), rootMessage(e), e);
                }
                // Bellek tavanı: kuyruk doluysa retry PLANLAMA (bkz. MAX_PENDING_RETRIES).
                if (pendingRetries.get() >= MAX_PENDING_RETRIES) {
                    log.error("✗ 421 retry kuyruğu dolu ({} bekleyen) — retry PLANLANMADI, e-posta düşürüldü: TO={}",
                            MAX_PENDING_RETRIES, to);
                    if (attempt > 1) writeBackRetryStatus(msg, "FAILED: retry kuyruğu dolu");
                    return "FAILED: retry kuyruğu dolu";
                }
                // Caller'ı bloke etme; retry'ı ayrı thread'de tetikle.
                pendingRetries.incrementAndGet();
                mailRetryExecutor.schedule(
                    () -> {
                        try { doSend(to, msg, attempt + 1); }
                        catch (Exception ex) {
                            log.error("✗ Async retry başarısız: TO={} | HATA={}", to, ex.getMessage(), ex);
                        } finally {
                            pendingRetries.decrementAndGet();
                        }
                    },
                    delay, TimeUnit.MILLISECONDS);
                // İlk denemenin sonucu caller'a döner (sonraki retry'lar async, sonucu yutulur).
                return attempt == 1 ? "QUEUED_RETRY: " + err : "QUEUED_RETRY";
            }
            if (errFull.contains("421")) {
                log.error("✗ E-posta {} denemede de 421 rate limit ile gönderilemedi: TO={}", MAX_SEND_ATTEMPTS, to);
            }
            // Son arg `e` (Throwable) → SLF4J tam stack trace'i ERROR'a HER ZAMAN basar
            // (TRACE açmaya gerek yok). SMTP bağlamı + mesaj ayrıntısı ek olarak TRACE'te.
            log.error("✗ E-posta gönderilemedi: TO={} | süre={}ms | HATA={}", to, ms, err, e);
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
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            applyFrom(helper);
            helper.setSubject(subject);
            String html = buildResolutionEmailHtml(domain, alertType, alertLevel,
                    daysRemaining, resolvedBy, resolvedAt, createdAt, certContext);
            helper.setText(
                    buildResolutionEmailText(domain, alertType, resolvedBy, resolvedAt, createdAt, certContext),
                    html);
            BrandMailAssets.addInline(helper, html, "ok");   // dizi-overload ile simetri (çözülme = ok)
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
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
     */
    private String sendFramedHtml(String[] to, String subject, String html, String variant) throws Exception {
        MimeMessage msg = currentSender().createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setTo(to);
        applyFrom(helper);
        helper.setSubject(subject);
        helper.setText(html, true);
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
            log.info("⚠ Email devre dışı — yeniden gönderim atlandı: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{to}, subject, html, logoVariant);
        } catch (Exception e) {
            log.error("✗ Yeniden gönderim hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
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
            log.info("⚠ Email devre dışı — şifre sıfırlama: TO={}", toAddress);
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{toAddress}, "[Site Monitor] Şifreniz sıfırlandı — lütfen güncelleyin",
                    buildPasswordResetHtml(username, displayName, tempPassword), "ok");
        } catch (Exception e) {
            log.error("✗ Şifre sıfırlama e-postası hazırlanamadı: TO={} | HATA={}", toAddress, e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /**
     * "Yeni bir cihazdan giriş yapıldı" bilgi e-postası (E1).
     *
     * <p>Tier-3 Outlook-güvenli çerçeveyi kullanır (td bgcolor, düz hex, MSO/VML, LIGHT_SCHEME_META)
     * — şifre sıfırlama e-postasıyla AYNI iskelet, ayrı bir HTML yazılmadı.
     *
     * <p>Ton bilinçli olarak SAKİN: bu bir alarm değil bilgilendirmedir; girişi yapan çoğu zaman
     * kullanıcının kendisidir. "Bu sen değilsen" yolu net ama panik yaratmadan verilir.
     */
    public String sendNewDeviceEmail(String toAddress, String displayName, String deviceSummary,
                                     String ip, String location, String whenIso) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — yeni cihaz bildirimi: TO={}", toAddress);
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{toAddress}, "[Site Monitor] Hesabınıza yeni bir cihazdan giriş yapıldı",
                    buildNewDeviceHtml(displayName, deviceSummary, ip, location, whenIso), "ok");
        } catch (Exception e) {
            log.error("✗ Yeni cihaz e-postası hazırlanamadı: TO={} | HATA={}", toAddress, e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    private String buildNewDeviceHtml(String displayName, String deviceSummary,
                                      String ip, String location, String whenIso) {
        StringBuilder rows = new StringBuilder();
        rows.append(newDeviceRow("Cihaz", deviceSummary));
        if (whenIso != null && !whenIso.isBlank()) rows.append(newDeviceRow("Zaman", whenIso));
        if (location != null && !location.isBlank()) rows.append(newDeviceRow("Konum", location));
        if (ip != null && !ip.isBlank()) rows.append(newDeviceRow("IP", ip));

        return simpleFrameOpen(560)
            + "<h2 style='color:#4f46e5;margin:0 0 12px;font-size:20px'>Yeni cihazdan giriş</h2>"
            + "<p style='margin:0 0 10px'>Sayın <strong>" + escHtml(displayName) + "</strong>,</p>"
            + "<p style='margin:0 0 10px'>Site Monitor hesabınıza daha önce görmediğimiz bir cihazdan "
            + "giriş yapıldı.</p>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' "
            + "style='border-collapse:collapse;margin:14px 0;font-size:14px'>"
            + rows
            + "</table>"
            + "<p style='margin:0 0 10px'><strong>Bu sizseniz</strong>, yapmanız gereken bir şey yok.</p>"
            + "<p style='margin:0 0 10px'><strong>Bu siz değilseniz</strong>, parolanızı değiştirin ve "
            + "\"Etkinliklerim → Cihaz Geçmişi\" ekranından hatırlanan cihazları iptal edin.</p>"
            + simpleFrameClose();
    }

    private String newDeviceRow(String label, String value) {
        return "<tr>"
             + "<td bgcolor='#ffffff' style='padding:4px 12px 4px 0;color:#6b7280'>" + escHtml(label) + "</td>"
             + "<td bgcolor='#ffffff' style='padding:4px 0;font-weight:600'>" + escHtml(value) + "</td>"
             + "</tr>";
    }

    /** Tier-3 (sistem/admin) e-postaları için sade Outlook-güvenli çerçeve: dış bgcolor tablo → ortalanmış
     *  sabit-genişlik beyaz kart → padding TD'de (Outlook div padding'ini ve max-width'i yok sayar).
     *  İç içerik (h2/p/tablo/ul) olduğu gibi bu td'ye yerleştirilir; rich builder'lardaki gibi hep-açık. */
    private String simpleFrameOpen(int maxWidth) {
        // Outlook-güvenli standart (BRAND.md §5.1): <style> bloğu YOK, marka barı kart üstünde.
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->"
            + "</head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:\"Segoe UI\",Arial,sans-serif;color:#1f2937'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9' style='background:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td align='center' style='padding:24px 10px'>"
            + "<table role='presentation' width='" + maxWidth + "' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='width:" + maxWidth + "px;max-width:" + maxWidth + "px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden'>"
            + "<tr><td style='padding:0'>" + brandBar() + "</td></tr>"
            + "<tr><td bgcolor='#ffffff' style='padding:24px'>";
    }

    private String simpleFrameClose() {
        return "</td></tr></table></td></tr></table></body></html>";
    }

    private String buildPasswordResetHtml(String username, String displayName, String tempPwd) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : username;
        return simpleFrameOpen(560)
            + "<h2 style='color:#4f46e5;margin:0 0 12px;font-size:20px'>Şifreniz sıfırlandı</h2>"
            + "<p style='margin:0 0 10px'>Sayın <strong>" + escHtml(name) + "</strong>,</p>"
            + "<p style='margin:0 0 10px'>Site Monitor hesabınızın şifresi bir yönetici tarafından sıfırlandı.</p>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:14px 0;font-size:14px'>"
            + "<tr><td style='padding:4px 12px 4px 0;color:#64748b'>Kullanıcı adı:</td>"
            + "<td style='padding:4px 0;font-family:Consolas,\"Courier New\",monospace;font-weight:600'>" + escHtml(username) + "</td></tr>"
            + "<tr><td style='padding:4px 12px 4px 0;color:#64748b;vertical-align:top'>Geçici şifre:</td>"
            + "<td style='padding:4px 0;font-family:Consolas,\"Courier New\",monospace;font-weight:700;letter-spacing:.04em;font-size:16px'>" + escHtml(tempPwd) + "</td></tr>"
            + "</table>"
            + "<p style='margin:0 0 10px'><strong>Bu şifre 24 saat geçerlidir.</strong> Bu süre içinde giriş yapmazsanız geçici şifreniz devre dışı kalır ve yeni bir sıfırlama talep etmeniz gerekir.</p>"
            + "<p style='margin:0 0 10px'>İlk girişinizde sistem sizden kalıcı bir şifre belirlemenizi isteyecektir.</p>"
            + "<p style='font-size:13px;color:#64748b;margin:0'>Bu işlemi siz başlatmadıysanız lütfen sistem yöneticinizle iletişime geçin.</p>"
            + simpleFrameClose();
    }

    // ── System admin — sürüm geçişi bildirimi (E3, opt-in) ───────────────────

    /** Dağıtım bildirimi verisi — {@code DeploymentNotifyService} toplar, burada yalnız çizilir. */
    public record DeploymentNotice(String kind, String environment, String fromVersion, String toVersion,
                                   String commitShort, String startedAt, List<String> highlights, boolean breaking) {}

    /** UPGRADE → yeşil (ok) logo, ROLLBACK → kırmızı (critical) logo; çerçeve şifre-sıfırlama ile aynı (Tier-3). */
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
            log.error("✗ Dağıtım bildirimi hazırlanamadı: TO={} | HATA={}", Arrays.toString(recipients), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /** Outlook-güvenli: td bgcolor, düz hex, MSO font fallback (simpleFrameOpen); TR ana metin + EN alt satır. */
    public String buildDeploymentNoticeHtml(DeploymentNotice n) {
        boolean rollback = "ROLLBACK".equals(n.kind());
        String color = rollback ? "#b91c1c" : "#15803d";
        String titleTr = rollback ? "Geri alma yapıldı" : "Yeni sürüm devreye alındı";
        String titleEn = rollback ? "Rollback deployed" : "New version deployed";
        StringBuilder rows = new StringBuilder();
        rows.append(newDeviceRow("Ortam / Environment", n.environment()));
        rows.append(newDeviceRow("Sürüm / Version", n.fromVersion() + " → " + n.toVersion()));
        rows.append(newDeviceRow("Tür / Kind", rollback ? "ROLLBACK (geri alma)" : "UPGRADE (yükseltme)"));
        if (n.commitShort() != null && !n.commitShort().isBlank()) rows.append(newDeviceRow("Commit", n.commitShort()));
        if (n.startedAt() != null && !n.startedAt().isBlank()) rows.append(newDeviceRow("Başlangıç / Started", n.startedAt()));
        StringBuilder hl = new StringBuilder();
        if (n.highlights() != null && !n.highlights().isEmpty()) {
            hl.append("<p style='margin:14px 0 6px;font-weight:700'>Öne çıkanlar / Highlights</p>")
              .append("<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;font-size:13px'>");
            for (String h : n.highlights()) {
                hl.append("<tr><td bgcolor='#ffffff' style='padding:3px 8px 3px 0;color:#64748b;vertical-align:top'>•</td>")
                  .append("<td bgcolor='#ffffff' style='padding:3px 0'>").append(escHtml(h)).append("</td></tr>");
            }
            hl.append("</table>");
        }
        String breaking = n.breaking()
                ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:12px 0'><tr>"
                  + "<td bgcolor='#fef2f2' style='background:#fef2f2;border-left:4px solid #dc2626;padding:8px 12px;font-size:13px;color:#991b1b'>"
                  + "<strong>Kırıcı değişiklik içerir / Contains breaking changes.</strong></td></tr></table>"
                : "";
        return simpleFrameOpen(560)
            + "<h2 style='color:" + color + ";margin:0 0 4px;font-size:20px'>" + escHtml(titleTr) + "</h2>"
            + "<p style='margin:0 0 12px;font-size:13px;color:#64748b'>" + escHtml(titleEn) + "</p>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:14px 0;font-size:14px'>"
            + rows
            + "</table>"
            + breaking
            + hl
            + "<p style='font-size:13px;color:#64748b;margin:14px 0 0'>Ayrıntı: Sistem Sağlığı → Sürüm &amp; Dağıtım. "
            + "Bu bildirim <em>site.monitor.deploy.notify.enabled</em> ayarı ile açılıp kapatılır.</p>"
            + simpleFrameClose();
    }

    // ── System admin — network outage notifications ──────────────────────────

    public String sendSystemAdminNetworkAlert(String to, String detectedAt,
                                              int networkErrors, int total,
                                              double errorRate, double threshold) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — admin network alert: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{to}, "[Site Monitor] ⚠ Ağ Erişim Sorunu Tespit Edildi",
                    buildAdminNetworkAlertHtml(detectedAt, networkErrors, total, errorRate, threshold), "critical");
        } catch (Exception e) {
            log.error("✗ Admin network alert hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendSystemAdminNetworkResolved(String to, String detectedAt, String resolvedAt,
                                                 long durationMs, int networkErrors, int total,
                                                 double errorRate) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — admin network resolved: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            return sendFramedHtml(new String[]{to}, "[Site Monitor] ✅ Ağ Erişim Sorunu Çözüldü",
                    buildAdminNetworkResolvedHtml(detectedAt, resolvedAt, durationMs, networkErrors, total, errorRate), "ok");
        } catch (Exception e) {
            log.error("✗ Admin network resolved hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
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

    private String buildLoginAnomalyHtml(FailedLoginAnomalyService.AnomalyReport r, String triggerLabel) {
        StringBuilder sb = new StringBuilder(simpleFrameOpen(640));
        sb.append("<h2 style='color:#dc2626;margin:0 0 4px;font-size:20px'>⚠ Başarısız login anomalisi</h2>");
        if (triggerLabel != null && !triggerLabel.isBlank())
            sb.append("<p style='margin:0 0 14px;color:#64748b;font-size:12px;text-transform:uppercase;letter-spacing:.05em'>")
              .append(escHtml(triggerLabel)).append("</p>");

        sb.append("<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:0 0 16px;font-size:14px'>");
        sb.append(adminRow("Zaman penceresi", escHtml(r.windowStart()) + " → " + escHtml(r.windowEnd()) + " (UTC)"));
        sb.append(adminRow("Toplam başarısız login", String.valueOf(r.total())));
        sb.append(adminRow("Önceki dönem ort.", r.baselineAvgPerWindow() + " / " + r.windowMinutes() + " dk pencere"));
        sb.append("</table>");

        int n = (r.hits() == null) ? 0 : r.hits().size();
        sb.append(laSectionTitle("Tetiklenen kurallar (" + n + ")"));
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:0 0 16px;font-size:13px'>");
        sb.append("<tr>").append(laTh("Kural")).append(laTh("Eşik")).append(laTh("Gerçekleşen")).append("</tr>");
        if (r.hits() != null) for (FailedLoginAnomalyService.RuleHit h : r.hits()) {
            sb.append("<tr>")
              .append("<td style='padding:6px 10px;border:1px solid #e5e7eb'>").append(escHtml(laRuleLabel(h.code())))
              .append("<br><span style='color:#94a3b8;font-size:11px'>").append(escHtml(h.detail())).append("</span></td>")
              .append("<td style='padding:6px 10px;border:1px solid #e5e7eb'>≥ ").append(h.threshold()).append("</td>")
              .append("<td style='padding:6px 10px;border:1px solid #e5e7eb;font-weight:700;color:#dc2626'>").append(h.actual()).append("</td>")
              .append("</tr>");
        }
        sb.append("</table>");

        sb.append(laKvSection("En çok hedeflenen hesaplar", r.topAccounts(), "deneme"));
        sb.append(laKvSection("En aktif kaynak IP'ler", r.topIps(), "deneme"));
        sb.append(laKvSection("IP → farklı kullanıcı (credential stuffing)", r.stuffingIps(), "kullanıcı"));
        sb.append(laReasonSection(r.reasonDistribution()));

        String cta = loginAnomalyCtaUrl(r.windowStart());
        if (!cta.isEmpty())
            sb.append("<div style='margin:18px 0 4px'>").append(ctaButton(cta, "Denetim kaydını aç", "#dc2626")).append("</div>");

        sb.append(simpleFrameClose());
        return sb.toString();
    }

    private String buildLoginAnomalyResolvedHtml(String openedAt, String resolvedAt, long peakTotal) {
        return simpleFrameOpen(560)
            + "<h2 style='color:#16a34a;margin:0 0 12px;font-size:20px'>✅ Login anomalisi normale döndü</h2>"
            + "<p style='margin:0 0 10px'>Takip eden kontrolde başarısız-login hacmi eşiklerin altına indi.</p>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:8px 0;font-size:14px'>"
            + adminRow("Başlangıç", escHtml(openedAt) + " (UTC)")
            + adminRow("Çözülme", escHtml(resolvedAt) + " (UTC)")
            + adminRow("Zirve hacim", String.valueOf(peakTotal))
            + "</table>"
            + simpleFrameClose();
    }

    private String laSectionTitle(String t) {
        return "<div style='font-size:12px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#64748b;margin:0 0 6px'>"
                + escHtml(t) + "</div>";
    }

    private String laTh(String t) {
        return "<td bgcolor='#f8fafc' style='background-color:#f8fafc;padding:6px 10px;font-weight:600;border:1px solid #e5e7eb'>"
                + escHtml(t) + "</td>";
    }

    private String laKvSection(String title, java.util.List<FailedLoginAnomalyService.KV> items, String unit) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(laSectionTitle(title));
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:0 0 16px;font-size:13px'>");
        int i = 0;
        for (FailedLoginAnomalyService.KV kv : items) {
            if (i++ >= 5) break;
            String zebra = (i % 2 == 0) ? "#f8fafc" : "#ffffff";
            sb.append("<tr><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:6px 10px;border-top:1px solid #f1f5f9;word-break:break-all'>").append(escHtml(kv.key()))
              .append("</td><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:6px 10px;border-top:1px solid #f1f5f9;text-align:right;font-weight:600'>")
              .append(kv.count()).append(' ').append(escHtml(unit)).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String laReasonSection(java.util.Map<String, Long> dist) {
        if (dist == null || dist.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(laSectionTitle("Başarısızlık nedeni dağılımı"));
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:0 0 16px;font-size:13px'>");
        int i = 0;
        for (java.util.Map.Entry<String, Long> e : dist.entrySet()) {
            String zebra = (++i % 2 == 0) ? "#f8fafc" : "#ffffff";
            sb.append("<tr><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:6px 10px;border-top:1px solid #f1f5f9'>").append(escHtml(e.getKey()))
              .append("</td><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:6px 10px;border-top:1px solid #f1f5f9;text-align:right;font-weight:600'>")
              .append(e.getValue()).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
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
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        String base = (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
        if (base.isEmpty()) return "";
        String since = java.net.URLEncoder.encode(windowStart == null ? "" : windowStart, java.nio.charset.StandardCharsets.UTF_8);
        return base + "/?tab=system&a_eventType=LOGIN_FAILED&a_since=" + since;
    }

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
        StringBuilder sb = new StringBuilder(simpleFrameOpen(640));
        sb.append("<h2 style='color:#b91c1c;margin:0 0 12px;font-size:20px'>🐞 Uygulama Hatası Bildirimi</h2>")
          .append("<p style='margin:0 0 14px'>Uygulama içinde bir ekran hatası (çökme) yakalandı ve otomatik olarak bildirildi:</p>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;font-size:14px'>")
          .append(adminRow("Referans Numarası", "<strong>" + escHtml(refCode) + "</strong>"))
          .append(adminRow("Kullanıcı", "<strong>" + escHtml(username) + "</strong>"))
          .append(adminRow("Bildirim Zamanı", escHtml(formatIso(reportedAt))))
          .append(adminRow("IP Adresi", escHtml(clientIp != null ? clientIp : "—")))
          .append(adminRow("Tarayıcı", escHtml(userAgent != null ? userAgent : "—")))
          .append("</table>");
        sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
          .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Sayfa / Bağlam</div>")
          .append("<div style='font-size:14px;line-height:1.6;white-space:pre-wrap'>").append(escHtml(message)).append("</div></div>");
        if (errorText != null && !errorText.isBlank()) {
            sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Hata Ayrıntısı (Stack)</div>")
              .append("<div style='font-family:Consolas,\"Courier New\",monospace;font-size:12px;line-height:1.5;white-space:pre-wrap;word-break:break-word'>")
              .append(escHtml(errorText)).append("</div></div>");
        }
        sb.append("<p style='font-size:13px;color:#64748b;margin:16px 0 0'>Bu bildirim uygulamanın hata yakalayıcısı (ErrorBoundary) tarafından otomatik gönderilmiştir; kullanıcı ayrıca bir açıklama girmemiştir.</p>")
          .append(simpleFrameClose());
        String html = sb.toString();
        String subject = "[Site Monitor] 🐞 Uygulama Hatası — " + refCode +
                (username != null && !username.isBlank() ? " · " + username : "");
        String status = sendHtml(new String[]{ to }, null, subject, html, List.of(), force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    /** Kullanıcı-tetiklemeli sorun bildirimi (USER_REPORT) — Sistem Yöneticisi'ne. Alarm DEĞİL:
     *  nötr ton, kullanıcının açıklaması odakta; otomatik bağlam ayrı blokta. Görseller CID inline. */
    public LoginIssueMailResult sendUserIssueReport(String to, String refCode, String username, String reporterEmail,
                                       String category, String message, String errorText, String linkedReference,
                                       String tabKey, String appVersion, List<InlineImage> images,
                                       String clientIp, String userAgent, String reportedAt, boolean force) {
        List<InlineImage> inline = images != null ? images : List.of();
        StringBuilder sb = new StringBuilder(simpleFrameOpen(640));
        sb.append("<h2 style='color:#1d4ed8;margin:0 0 12px;font-size:20px'>📝 Sorun Bildirimi</h2>")
          .append("<p style='margin:0 0 14px'>Bir kullanıcı uygulama içinden sorun bildirdi:</p>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;font-size:14px'>")
          .append(adminRow("Referans Numarası", "<strong>" + escHtml(refCode) + "</strong>"))
          .append(adminRow("Kullanıcı", "<strong>" + escHtml(username) + "</strong>"));
        if (reporterEmail != null && !reporterEmail.isBlank()) sb.append(adminRow("E-posta", escHtml(reporterEmail)));
        if (category != null && !category.isBlank()) sb.append(adminRow("Önem", escHtml(labelForCategory(category))));
        if (tabKey != null && !tabKey.isBlank()) sb.append(adminRow("Ekran/Sekme", escHtml(tabKey)));
        if (appVersion != null && !appVersion.isBlank()) sb.append(adminRow("Uygulama Sürümü", escHtml(appVersion)));
        if (linkedReference != null && !linkedReference.isBlank())
            sb.append(adminRow("Bağlı Çökme Kaydı", escHtml(linkedReference)));
        sb.append(adminRow("Bildirim Zamanı", escHtml(formatIso(reportedAt))))
          .append(adminRow("IP Adresi", escHtml(clientIp != null ? clientIp : "—")))
          .append(adminRow("Tarayıcı", escHtml(userAgent != null ? userAgent : "—")))
          .append("</table>");
        sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
          .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Kullanıcının Açıklaması</div>")
          .append("<div style='font-size:14px;line-height:1.6;white-space:pre-wrap'>").append(escHtml(message)).append("</div></div>");
        if (errorText != null && !errorText.isBlank()) {
            sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Ekrandaki Hata</div>")
              .append("<div style='font-family:Consolas,\"Courier New\",monospace;font-size:12px;line-height:1.5;white-space:pre-wrap;word-break:break-word'>")
              .append(escHtml(errorText)).append("</div></div>");
        }
        if (!inline.isEmpty()) {
            sb.append("<div style='margin:16px 0 0'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Ekran Görüntüleri (")
              .append(inline.size()).append(")</div>");
            int i = 1;
            for (InlineImage img : inline) {
                sb.append("<div style='margin:0 0 10px'><img src='cid:").append(escHtml(img.cid()))
                  .append("' alt='Ekran görüntüsü ").append(i++)
                  .append("' width='592' style='max-width:100%;width:592px;height:auto;border:1px solid #e5e7eb;border-radius:8px;display:block' /></div>");
            }
            sb.append("</div>");
        }
        sb.append("<p style='font-size:13px;color:#64748b;margin:16px 0 0'>Ayrıntılı otomatik bağlam (tema/dil/son başarısız istekler) Yönetim &gt; Sorun Bildirimleri ekranındadır.</p>")
          .append(simpleFrameClose());
        String html = sb.toString();
        String subject = "[Site Monitor] 📝 Sorun Bildirimi — " + refCode +
                (username != null && !username.isBlank() ? " · " + username : "");
        String status = sendHtml(new String[]{ to }, null, subject, html, inline, force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    private static String labelForCategory(String category) {
        return switch (category) {
            case "BLOCKER"    -> "Engelliyor";
            case "ANNOYANCE"  -> "Rahatsız ediyor";
            case "SUGGESTION" -> "Öneri";
            default           -> category;
        };
    }

    /** Günlük özet (digest) — {@code site.monitor.issue-reports.daily-digest} açıkken tekil mailler yerine
     *  son 24 saatin USER_REPORT bildirimleri tek mailde. Satır başına referans + kullanıcı + özet. */
    public LoginIssueMailResult sendIssueDigest(String to, List<Map<String, String>> items, String periodLabel, boolean force) {
        StringBuilder sb = new StringBuilder(simpleFrameOpen(640));
        sb.append("<h2 style='color:#1d4ed8;margin:0 0 12px;font-size:20px'>📝 Sorun Bildirimleri — Günlük Özet</h2>")
          .append("<p style='margin:0 0 14px'>").append(escHtml(periodLabel)).append(" döneminde ")
          .append(items.size()).append(" bildirim alındı:</p>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;font-size:13px'>");
        for (Map<String, String> it : items) {
            sb.append(adminRow(escHtml(it.getOrDefault("refCode", "—")),
                    "<strong>" + escHtml(it.getOrDefault("username", "—")) + "</strong> · "
                    + escHtml(it.getOrDefault("summary", ""))));
        }
        sb.append("</table>")
          .append("<p style='font-size:13px;color:#64748b;margin:16px 0 0'>Ayrıntılar Yönetim &gt; Sorun Bildirimleri ekranındadır.</p>")
          .append(simpleFrameClose());
        String html = sb.toString();
        String subject = "[Site Monitor] 📝 Sorun Bildirimleri Özeti — " + items.size() + " yeni bildirim";
        String status = sendHtml(new String[]{ to }, null, subject, html, List.of(), force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
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

        StringBuilder sb = new StringBuilder(simpleFrameOpen(640));
        sb.append("<h2 style='color:#15803d;margin:0 0 12px;font-size:20px'>✅ Sorun çözümlendi</h2>")
          .append("<p style='margin:0 0 14px'><strong>").append(escHtml(refCode))
          .append("</strong> referans numaralı giriş sorunu bildirimi çözümlenmiştir.</p>")
          .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;font-size:14px'>")
          .append(adminRow("Referans Numarası", "<strong>" + escHtml(refCode) + "</strong>"))
          .append(adminRow("Kullanıcı Adı", "<strong>" + escHtml(username != null && !username.isBlank() ? username : "—") + "</strong>"));
        if (reporterEmail != null && !reporterEmail.isBlank()) {
            sb.append(adminRow("E-posta", escHtml(reporterEmail)));
        }
        sb.append(adminRow("Bildirim Zamanı", escHtml(formatIso(reportedAt))))
          .append(adminRow("Çözülme Zamanı", escHtml(formatIso(resolvedAt))))
          .append("</table>");
        // Orijinal sorun — alınan hata mesajı (varsa) + iletilen açıklama.
        if (errorText != null && !errorText.isBlank()) {
            sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Alınan Hata Mesajı</div>")
              .append("<div style='font-family:Consolas,\"Courier New\",monospace;font-size:13px;line-height:1.5;white-space:pre-wrap'>")
              .append(escHtml(errorText)).append("</div></div>");
        }
        if (message != null && !message.isBlank()) {
            sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>İletilen Açıklama</div>")
              .append("<div style='font-size:14px;line-height:1.6;white-space:pre-wrap'>").append(escHtml(message)).append("</div></div>");
        }
        // Ekran görüntüleri (CID inline) — bildirimdekiyle aynı, çözümlendi mailine de eklenir.
        if (!inline.isEmpty()) {
            sb.append("<div style='margin:16px 0 0'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Ekran Görüntüleri (")
              .append(inline.size()).append(")</div>");
            int i = 1;
            for (InlineImage img : inline) {
                sb.append("<div style='margin:0 0 10px'>")
                  .append("<div style='font-size:11px;color:#9ca3af;margin:0 0 4px'>Görsel ").append(i++).append("</div>")
                  .append("<img src='cid:").append(escHtml(img.cid()))
                  .append("' alt='Ekran görüntüsü ").append(i - 1)
                  .append("' width='592' style='max-width:100%;width:592px;height:auto;border:1px solid #e5e7eb;border-radius:8px;display:block' />")
                  .append("</div>");
            }
            sb.append("</div>");
        }
        // Çözüm notu.
        if (resolutionNote != null && !resolutionNote.isBlank()) {
            sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Çözüm Notu</div>")
              .append("<div style='font-size:14px;line-height:1.6;white-space:pre-wrap'>").append(escHtml(resolutionNote)).append("</div></div>");
        }
        sb.append("<p style='font-size:13px;color:#64748b;margin:16px 0 0'>Sorun devam ediyorsa lütfen tekrar bildiriniz. Bu e-posta otomatik gönderilmiştir.</p>")
          .append(simpleFrameClose());

        String html = sb.toString();
        String subject = "[Site Monitor] ✅ Giriş sorunu çözümlendi — " + refCode;
        String status = sendHtml(toArr, ccArr, subject, html, inline, force);
        return new LoginIssueMailResult(status, currentFrom(), subject, html);
    }

    // NOT: login-issue mailleri artık LoginIssueMailService üzerinden ASYNC gönderilir + login_issue_mail_logs'a
    // loglanır (mail geçmişi). Eski buradaki @Async sarmalayıcılar oraya taşındı.

    /** Login sorun bildirimi HTML'i — admin (forReporter=false: IP/UA + kimliksiz uyarısı) ve bildiren
     *  (forReporter=true: takip metni, IP/UA gizli) için ortak; her ikisinde referans no + hata + görseller. */
    private String buildLoginIssueHtml(String refCode, String username, String reporterEmail, String errorText, String message,
                                       List<InlineImage> images,
                                       String clientIp, String userAgent, String reportedAt, boolean forReporter) {
        StringBuilder sb = new StringBuilder(simpleFrameOpen(640));
        if (forReporter) {
            sb.append("<h2 style='color:#15803d;margin:0 0 12px;font-size:20px'>✅ Sorun bildiriminiz alındı</h2>")
              .append("<p style='margin:0 0 14px'>Bildiriminiz kaydedildi. Aşağıdaki referans numarasıyla durumu takip edebilir, "
                      + "bizimle iletişimde bu numarayı belirtebilirsiniz. Sorun çözüldüğünde bu e-posta adresine bilgi verilecektir.</p>");
        } else {
            sb.append("<h2 style='color:#b45309;margin:0 0 12px;font-size:20px'>🛟 Giriş Sorunu Bildirimi</h2>")
              .append("<p style='margin:0 0 14px'>Bir kullanıcı login sayfasından giriş sorunu bildirdi:</p>");
        }
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;font-size:14px'>")
          .append(adminRow("Referans Numarası", "<strong>" + escHtml(refCode) + "</strong>"))
          .append(adminRow("Kullanıcı Adı", "<strong>" + escHtml(username) + "</strong>"));
        // Bildirenin e-posta adresi — admin varyantında göster (yöneticinin iletişim için ihtiyacı var).
        if (!forReporter && reporterEmail != null && !reporterEmail.isBlank()) {
            sb.append(adminRow("E-posta", escHtml(reporterEmail)));
        }
        sb.append(adminRow("Bildirim Zamanı", escHtml(formatIso(reportedAt))));
        if (!forReporter) {
            sb.append(adminRow("IP Adresi", escHtml(clientIp != null ? clientIp : "—")))
              .append(adminRow("Tarayıcı", escHtml(userAgent != null ? userAgent : "—")));
        }
        sb.append("</table>");
        if (errorText != null && !errorText.isBlank()) {
            sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Alınan Hata Mesajı</div>")
              .append("<div style='font-family:Consolas,\"Courier New\",monospace;font-size:13px;line-height:1.5;white-space:pre-wrap'>")
              .append(escHtml(errorText)).append("</div></div>");
        }
        sb.append("<div style='margin:16px 0 0;padding:12px 14px;border:1px solid #e5e7eb;border-radius:8px'>")
          .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>")
          .append(forReporter ? "İlettiğiniz Açıklama" : "Kullanıcının Açıklaması").append("</div>")
          .append("<div style='font-size:14px;line-height:1.6;white-space:pre-wrap'>").append(escHtml(message)).append("</div>")
          .append("</div>");
        if (images != null && !images.isEmpty()) {
            sb.append("<div style='margin:16px 0 0'>")
              .append("<div style='font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280;margin:0 0 6px'>Ekran Görüntüleri (")
              .append(images.size()).append(")</div>");
            int i = 1;
            for (InlineImage img : images) {
                sb.append("<div style='margin:0 0 10px'>")
                  .append("<div style='font-size:11px;color:#9ca3af;margin:0 0 4px'>Görsel ").append(i++).append("</div>")
                  .append("<img src='cid:").append(escHtml(img.cid()))
                  .append("' alt='Ekran görüntüsü ").append(i - 1)
                  .append("' width='592' style='max-width:100%;width:592px;height:auto;border:1px solid #e5e7eb;border-radius:8px;display:block' />")
                  .append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("<p style='font-size:13px;color:#64748b;margin:16px 0 0'>")
          .append(forReporter
                  ? "Bu e-posta Site Monitor tarafından otomatik gönderilmiştir. Yanıtlamayınız."
                  : "Bu bildirim login sayfasındaki \"sorun bildir\" bağlantısından, kimlik doğrulaması yapılmadan gönderilmiştir — içeriği buna göre değerlendirin.")
          .append("</p>")
          .append(simpleFrameClose());
        return sb.toString();
    }

    /** Admin bilgi tablosu satırı — etiket hücresi bgcolor'lı (Outlook-güvenli), değer hücresi düz. */
    private String adminRow(String label, String value) {
        return "<tr>"
            + "<td bgcolor='#f3f4f6' style='background-color:#f3f4f6;text-align:left;padding:7px 10px;border:1px solid #e5e7eb;font-weight:700;white-space:nowrap'>" + escHtml(label) + "</td>"
            + "<td style='padding:7px 10px;border:1px solid #e5e7eb'>" + value + "</td></tr>";
    }

    private String buildAdminNetworkAlertHtml(String detectedAt, int networkErrors, int total,
                                              double errorRate, double threshold) {
        String ratePct = String.format("%.0f%%", errorRate * 100);
        String threshPct = String.format("%.0f%%", threshold * 100);
        return simpleFrameOpen(640)
                + "<h2 style='color:#b91c1c;margin:0 0 12px;font-size:20px'>⚠ Site Monitor — Ağ Erişim Sorunu Tespit Edildi</h2>"
                + "<p style='font-size:14px;line-height:1.55;margin:0 0 12px'>Site Monitor host'unun bir veya daha fazla sertifika kontrolünü tamamlayamadığı tespit edildi. "
                + "Tarama turunda <strong>" + networkErrors + " / " + total + "</strong> domain ağ-class hatasıyla düştü "
                + "(oran: <strong>" + ratePct + "</strong>, eşik: " + threshPct + "). "
                + "Bu, host'un outbound bağlantısında bir problem olabileceğini gösteriyor.</p>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:16px 0;font-size:13px'>"
                + adminRow("Tespit Zamanı", formatIso(detectedAt))
                + adminRow("Etkilenen Domain", networkErrors + " / " + total)
                + adminRow("Hata Oranı", ratePct)
                + adminRow("Eşik", threshPct)
                + "</table>"
                + "<h3 style='color:#374151;font-size:15px;margin:20px 0 8px'>Sistemin Aksiyonu</h3>"
                + "<ul style='font-size:13px;line-height:1.6;margin:0 0 8px;padding-left:20px'>"
                + "<li>Yeni alarm üretimi <strong>geçici olarak duraklatıldı</strong></li>"
                + "<li>Auto-resolve işlemi <strong>askıya alındı</strong> (sahte resolved e-posta yağmuru engellenir)</li>"
                + "<li>Dashboard'da operatörlere uyarı banner'ı gösterildi</li>"
                + "</ul>"
                + "<h3 style='color:#374151;font-size:15px;margin:20px 0 8px'>Önerilen Kontroller</h3>"
                + "<ul style='font-size:13px;line-height:1.6;margin:0 0 8px;padding-left:20px'>"
                + "<li>Host'un internet bağlantısı (modem/router)</li>"
                + "<li>Outbound proxy ayarları</li>"
                + "<li>Kurumsal firewall/NAT politikaları</li>"
                + "<li>DNS sunucu erişilebilirliği</li>"
                + "</ul>"
                + "<p style='font-size:13px;color:#6b7280;margin:24px 0 0'>Ağ erişimi normale döner dönmez ayrıca bir <strong>\"Çözüldü\"</strong> e-postası alacaksınız.</p>"
                + "<hr style='border:none;border-top:1px solid #e5e7eb;margin:24px 0 12px' />"
                + "<p style='font-size:11px;color:#9ca3af;margin:0'>Site Monitor — System Admin Notification</p>"
                + simpleFrameClose();
    }

    private String buildAdminNetworkResolvedHtml(String detectedAt, String resolvedAt, long durationMs,
                                                 int networkErrors, int total, double errorRate) {
        long durationMin = durationMs / 60000;
        long durationSec = (durationMs / 1000) % 60;
        String durationStr = durationMin + " dk " + durationSec + " sn";
        String ratePct = String.format("%.0f%%", errorRate * 100);
        return simpleFrameOpen(640)
                + "<h2 style='color:#15803d;margin:0 0 12px;font-size:20px'>✅ Site Monitor — Ağ Erişim Sorunu Çözüldü</h2>"
                + "<p style='font-size:14px;line-height:1.55;margin:0 0 12px'>Site Monitor host'unun outbound bağlantı sorunu çözüldü. "
                + "Sertifika kontrolleri normal işleyişe döndü.</p>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:16px 0;font-size:13px'>"
                + adminRow("Tespit Zamanı", formatIso(detectedAt))
                + adminRow("Çözüm Zamanı", formatIso(resolvedAt))
                + adminRow("Toplam Süre", durationStr)
                + adminRow("Tespit Anında Etkilenen", networkErrors + " / " + total + " (" + ratePct + ")")
                + "</table>"
                + "<h3 style='color:#374151;font-size:15px;margin:20px 0 8px'>Sistemin Aksiyonu</h3>"
                + "<ul style='font-size:13px;line-height:1.6;margin:0 0 8px;padding-left:20px'>"
                + "<li>Yeni alarm üretimi <strong>yeniden aktif</strong></li>"
                + "<li>Auto-resolve işlemi <strong>yeniden aktif</strong></li>"
                + "<li>Dashboard uyarı banner'ı kaldırıldı</li>"
                + "</ul>"
                // Renkli not: <p background> Outlook'ta beyaza düşer → accent-şeritli tablo (td bgcolor)
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:16px 0 0;border-radius:8px;overflow:hidden'><tr>"
                + "<td width='4' bgcolor='#16a34a' style='background-color:#16a34a;width:4px;font-size:0;line-height:0'>&nbsp;</td>"
                + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:10px 14px;font-size:13px;color:#15803d'>"
                + "<em>Not: Outage süresince üretilebilecek sahte alarmlar bastırıldığı için ekibinize ÇÖZÜLDÜ e-posta yağmuru gönderilmedi.</em></td></tr></table>"
                + "<hr style='border:none;border-top:1px solid #e5e7eb;margin:24px 0 12px' />"
                + "<p style='font-size:11px;color:#9ca3af;margin:0'>Site Monitor — System Admin Notification</p>"
                + simpleFrameClose();
    }

    // ── Public HTML accessors (used to store sent HTML in notification log) ──

    private static final java.util.Set<String> MONITORING_OUTAGE_TYPES =
            java.util.Set.of("ACCESSIBILITY", "PORT_DOWN", "DNS_FAILURE");

    /** Süre-bitişi ailesi (alan adı + sertifika) executive-premium şablondan (EmailTemplateBuilder) geçer;
     *  izleme-kesintisi tipleri (uptime/port/dns/keyword/ping/dns-changed) tip-özel zengin şablonlarını korur. */
    public String buildAlertEmailHtml(String subject, String message,
                                       String domain, String level, String alertType,
                                       Integer daysRemaining, Map<String, Object> certContext) {
        if (alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType)) {
            return buildRichMonitoringOutageAlertHtml(message, domain, alertType, certContext);
        }
        if ("KEYWORD".equals(alertType)) return buildRichKeywordAlertHtml(message, domain, level, certContext);
        if ("PING_DOWN".equals(alertType)) return buildRichPingAlertHtml(message, domain, level, certContext);
        if ("DNS_CHANGED".equals(alertType)) return buildRichDnsChangedAlertHtml(message, domain, certContext);
        if (domain == null) return buildSimpleAlertHtml(subject, message);
        // Kalan hepsi = süre-bitişi ailesi (DOMAINMON_*, DOMAIN_EXPIRY, sertifika EXPIRY/REVOKED/MISMATCH/CHAIN) → executive
        return templateBuilder.buildHtml(new EmailTemplateBuilder.AlertMail(
                alertType, level, domain, message, daysRemaining, certContext, teamNameOf(certContext)));
    }

    /** Alarm e-postasının plain-text (multipart) alternatifi. */
    public String buildAlertEmailText(String subject, String message,
                                      String domain, String level, String alertType,
                                      Integer daysRemaining, Map<String, Object> certContext) {
        return templateBuilder.buildText(new EmailTemplateBuilder.AlertMail(
                alertType, level, domain, message, daysRemaining, certContext, teamNameOf(certContext)));
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
        if ("KEYWORD".equals(alertType)) return buildRichKeywordResolvedHtml(domain, certContext, resolvedBy, resolvedAt, createdAt, teamNames, uptime);
        if ("PING_DOWN".equals(alertType)) return buildRichPingResolvedHtml(domain, certContext, resolvedBy, resolvedAt, createdAt, teamNames, uptime);
        if ((alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType)) || "DNS_CHANGED".equals(alertType)) {
            return buildRichMonitoringResolvedHtml(domain, alertType, resolvedBy, resolvedAt, createdAt, teamNames, uptime, certContext);
        }
        // domain + sertifika → executive "çözüldü" (yenilenen bitiş/registrar bağlamıyla zenginleştirilir)
        return templateBuilder.buildResolvedHtml(domain, alertType, resolvedBy, resolvedAt, createdAt, certContext, teamNames);
    }

    /** Çözüm e-postasının plain-text (multipart) alternatifi. */
    public String buildResolutionEmailText(String domain, String alertType, String resolvedBy, String resolvedAt) {
        return templateBuilder.buildResolvedText(domain, alertType, resolvedBy, resolvedAt);
    }

    /** Çözüm plain-text — alan/sertifika bağlamıyla zenginleştirilmiş sürüm. */
    public String buildResolutionEmailText(String domain, String alertType, String resolvedBy, String resolvedAt,
                                           String createdAt, Map<String, Object> certContext) {
        return templateBuilder.buildResolvedText(domain, alertType, resolvedBy, resolvedAt, createdAt, certContext);
    }

    private static String teamNameOf(Map<String, Object> ctx) {
        if (ctx == null) return null;
        Object v = ctx.get("team_name");
        return v == null ? null : String.valueOf(v);
    }

    private String tableRow2col(String label, String value) {
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:600;color:#1e293b;word-break:break-all'>" + value + "</td>"
            + "</tr>";
    }

    // ── DNS ESKİ→YENİ fark tablosu ────────────────────────────────────────────
    /** Tabloda gösterilecek en fazla değer satırı; fazlası "…ve N tane daha" ile özetlenir. */
    private static final int DNS_MAX_VALUE_ROWS = 6;
    /** Tek bir değerin en fazla karakteri (uzun TXT/DKIM kayıtları düzeni bozmasın). */
    private static final int DNS_MAX_VALUE_LEN = 160;

    /**
     * ESKİ→YENİ değerleri SATIR-KİLİTLİ tek tabloda gösterir: i. eski ile i. yeni değer AYNI
     * {@code <tr>}'de durur, aralarında yön oku. Eskiden iki BAĞIMSIZ iç tablo vardı; satır
     * sayıları farklı olunca (2 eski → 1 yeni gibi tipik durumda) eşleşme kayıyor ve kolonlar
     * hizasız görünüyordu. Değerler 11px gri yerine 15px kalın; silinen değer üstü çizik.
     * Tamamen tablo tabanlı + td bgcolor + düz hex → Outlook güvenli, {@code <style>} bloğu YOK.
     */
    private String dnsDiffTable(List<String> oldValues, List<String> newValues, String purple) {
        List<String> olds = oldValues == null ? List.of() : oldValues;
        List<String> news = newValues == null ? List.of() : newValues;
        int rows = Math.max(olds.size(), news.size());
        int shown = Math.min(rows, DNS_MAX_VALUE_ROWS);

        StringBuilder body = new StringBuilder();
        if (rows == 0) {
            body.append("<tr><td colspan='3' bgcolor='#f8fafc' style='background-color:#f8fafc;"
                    + "border-top:1px solid #e2e8f0;padding:14px;font-size:13px;font-style:italic;"
                    + "color:#64748b;text-align:center'>Değişiklik ayrıntısı bu bildirimde taşınmıyor.</td></tr>");
        }
        for (int i = 0; i < shown; i++) {
            String o = i < olds.size() ? olds.get(i) : null;
            String n = i < news.size() ? news.get(i) : null;
            body.append("<tr>")
                .append(dnsValueCell(o, true, "#f8fafc", olds.isEmpty() ? "kayıt yoktu" : "—"))
                .append("<td align='center' valign='top' bgcolor='#ffffff' style='background-color:#ffffff;"
                        + "border-top:1px solid #e2e8f0;padding:12px 2px'>"
                        + "<span style='font-size:17px;font-weight:700;color:" + purple + "'>&#8594;</span></td>")
                .append(dnsValueCell(n, false, "#faf5ff", news.isEmpty() ? "kayıt kalmadı" : "—"))
                .append("</tr>");
        }
        if (rows > shown) {
            body.append("<tr><td colspan='3' bgcolor='#f8fafc' style='background-color:#f8fafc;"
                    + "border-top:1px solid #e2e8f0;padding:9px 14px;font-size:12px;font-style:italic;"
                    + "color:#64748b;text-align:center'>…ve ").append(rows - shown).append(" tane daha</td></tr>");
        }

        return "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden;border-collapse:collapse'>"
            + "<tr>"
            + "<td width='46%' bgcolor='#475569' style='width:46%;background-color:#475569;padding:9px 14px;"
            + "font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>ESKİ DEĞERLER &#183; " + olds.size() + "</td>"
            + "<td width='8%' bgcolor='#334155' style='width:8%;background-color:#334155'>&nbsp;</td>"
            + "<td width='46%' bgcolor='" + purple + "' style='width:46%;background-color:" + purple + ";padding:9px 14px;"
            + "font-size:11px;font-weight:700;letter-spacing:.1em;color:#f3e8ff'>YENİ DEĞERLER &#183; " + news.size() + "</td>"
            + "</tr>" + body + "</table>";
    }

    /** Fark tablosunun tek değer hücresi. {@code removed=true} → solgun + üstü çizik (span'de:
     *  Word, text-decoration'ı hücreden miras almıyor). Değer yoksa {@code emptyText} basılır. */
    private static String dnsValueCell(String value, boolean removed, String bg, String emptyText) {
        String color = removed ? "#64748b" : "#581c87";
        String td = "<td valign='top' bgcolor='" + bg + "' style='background-color:" + bg + ";"
                + "border-top:1px solid #e2e8f0;padding:12px 14px;font-family:Consolas,\"Courier New\",monospace;"
                + "word-break:break-all;mso-line-height-rule:exactly;";
        if (value == null || value.isBlank()) {
            return td + "font-size:13px;font-style:italic;color:#94a3b8'>" + escHtml(emptyText) + "</td>";
        }
        String shown = escHtml(truncValue(value));
        String inner = removed ? "<span style='text-decoration:line-through'>" + shown + "</span>" : shown;
        return td + "font-size:" + dnsValueFontPx(value) + "px;font-weight:" + (removed ? "700" : "800")
                + ";color:" + color + "'>" + inner + "</td>";
    }

    /** Çok uzun tek değeri (DKIM TXT vb.) kırpar — düzen bozulmasın. */
    private static String truncValue(String v) {
        return v.length() <= DNS_MAX_VALUE_LEN ? v : v.substring(0, DNS_MAX_VALUE_LEN) + "…";
    }

    /** Uzun değerlerde punto düşür (CSS medya sorgusu kullanılamıyor; Java'da hesaplanır). */
    private static int dnsValueFontPx(String v) {
        return v.length() > 40 ? 13 : 15;
    }

    private String statusRow2col(String label, String value) {
        boolean ok      = value.startsWith("✓");
        boolean neutral = value.startsWith("~") || value.startsWith("—");
        String bgAttr  = ok || neutral ? "" : " bgcolor='#fef2f2'";
        String bgStyle = ok || neutral ? "" : "background-color:#fef2f2;";
        String valueColor = ok ? "#15803d" : neutral ? "#6b7280" : "#dc2626";
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%'" + bgAttr + " style='" + bgStyle + "padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td" + bgAttr + " style='" + bgStyle + "padding:9px 13px;font-size:13px;font-weight:700;color:" + valueColor + "'>" + value + "</td>"
            + "</tr>";
    }

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

    /** Apple Mail/iOS dark-mode oto-inversiyonunu kapatır (beyaz kartlar kararmaz → hep-açık WebKit preview'a uyum).
     *  Tüm builder head'lerinde viewport meta'sından sonra aynı iki satır kullanılır. */
    private static final String LIGHT_SCHEME_META =
            "<meta name='color-scheme' content='light only'><meta name='supported-color-schemes' content='light'>";

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

    private String buildSimpleAlertHtml(String subject, String message) {
        return buildSimpleAlertHtml(subject, message, null, null);
    }

    /** Outlook (Word/VML v:roundrect — yuvarlak köşe + TAM-ALAN tıklanır) + diğer istemciler (HTML &lt;a&gt;) için
     *  çift "bulletproof" CTA. mso/non-mso koşullu yorumlarıyla her istemci yalnız kendi sürümünü görür.
     *  [if !mso] dalı eski çıktının bayt-bayt aynısı → Outlook-dışı (Apple Mail/Gmail/preview) SIFIR regresyon.
     *  href + w:anchorlock roundrect ELEMENTİNDE → eski "bazı Outlook'ta tıklanmıyordu" sorunu çözülür
     *  (aynı desen buildWeeklyReportReminderHtml'de kanıtlı çalışıyor). Genişlik VML'de sabit olmalı → vmlButtonWidth. */
    private String ctaButton(String url, String label, String accent) {
        if (url == null || url.isBlank()) return "";
        String safe = escHtml(url);
        int w = vmlButtonWidth(label);
        return "<!--[if mso]>"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0' align='center' style='margin:0 auto'><tr><td align='center'>"
            + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\""
            + " href=\"" + safe + "\" style=\"height:44px;v-text-anchor:middle;width:" + w + "px;\""
            + " arcsize=\"16%\" strokecolor=\"" + accent + "\" fillcolor=\"" + accent + "\">"
            + "<w:anchorlock/>"
            + "<center style=\"color:#ffffff;font-family:'Segoe UI',Tahoma,Arial,sans-serif;font-size:15px;font-weight:bold;\">"
            + label + "</center>"
            + "</v:roundrect>"
            + "</td></tr></table>"
            + "<![endif]-->"
            + "<!--[if !mso]><!-->"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0' align='center' style='margin:0 auto'><tr>"
            + "<td align='center' bgcolor='" + accent + "' style='background:" + accent + ";border-radius:8px;mso-padding-alt:14px 32px'>"
            + "<a href='" + safe + "' target='_blank' style='display:inline-block;padding:14px 32px;"
            + "color:#ffffff;text-decoration:none;font-size:15px;font-weight:bold;border-radius:8px;"
            + "font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>" + label + "</a>"
            + "</td></tr></table>"
            + "<!--<![endif]-->";
    }

    /** VML v:roundrect auto-size yapamaz → görünür etiket uzunluğundan px genişlik türet
     *  (HTML entity'ler ve olası etiketler ~1 karakter sayılır). Fazla tahmin butonu genişletir, asla kırpmaz. */
    private static int vmlButtonWidth(String label) {
        String visible = label == null ? "" : label
                .replaceAll("&[a-zA-Z]+;|&#\\d+;", "x")   // &rarr; &nbsp; &#183; → tek karakter
                .replaceAll("<[^>]+>", "");                // olası inline etiket
        int w = visible.length() * 9 + 56;
        return Math.max(200, Math.min(600, w));
    }

    private String buildSimpleAlertHtml(String subject, String message, String ctaUrl, String ctaLabel) {
        String now = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String color = subject.contains("KRİTİK") || subject.contains("CRITICAL") ? "#dc2626"
                : subject.contains("YÜKSEK") || subject.contains("HIGH") ? "#ea580c" : "#d97706";
        // Outlook-güvenli standart (BRAND.md §5.1): <style> bloğu YOK, marka barı üstte.
        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<div style='text-align:center;margin-top:20px'>" + ctaButton(ctaUrl, ctaLabel, "#15803d") + "</div>"
            : "";
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" + LIGHT_SCHEME_META + "</head>"
            + "<body style='margin:0;padding:0;background:#f3f4f6;font-family:\"Segoe UI\",Arial,sans-serif'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='background:#f3f4f6;padding:24px 10px'>"
            + "<tr><td align='center'>"
            + "<table width='600' cellpadding='0' cellspacing='0' border='0'"
            + " style='width:600px;max-width:600px;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.12)'>"
            + "<tr><td style='padding:0'>" + brandBar() + "</td></tr>"
            + "<tr><td bgcolor='" + color + "' style='background-color:" + color + ";padding:20px 24px;color:#fff'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#ffffff'>BİLDİRİM</div>"
            + "<div style='font-size:18px;font-weight:800;margin-top:4px;word-break:break-word'>" + escHtml(subject) + "</div>"
            + "</td></tr>"
            + "<tr><td class='em-body' style='padding:24px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:0 8px 8px 0;overflow:hidden'><tr>"
            + "<td width='4' bgcolor='" + color + "' style='background-color:" + color + ";width:4px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fffbeb' style='background-color:#fffbeb;padding:14px 16px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + escHtml(message) + "</td></tr></table>"
            + cta
            + "<div style='text-align:center;color:#94a3b8;font-size:11px;margin-top:20px;"
            + "padding-top:16px;border-top:1px solid #f1f5f9'>Site Monitor &nbsp;·&nbsp; " + now + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    /**
     * İzleme kesintisi alarm maili — ACCESSIBILITY / PORT_DOWN / DNS_FAILURE
     * için ortak şablon; etiketler tipe göre çözülür. ctx,
     * MonitoringOutageService'in ürettiği kesinti bağlamıdır (port/protocol/
     * record_type, first_failure_at, last_error, confirm_attempts,
     * confirm_delay_ms, confirm_attempt_count). "Tekrar Bildir" yolu eksik
     * context geçirebileceğinden TÜM okumalar null-toleranslıdır.
     */
    @SuppressWarnings("unchecked")
    private String buildRichMonitoringOutageAlertHtml(String message, String domain,
                                                      String alertType, Map<String, Object> ctx) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String red = "#dc2626";

        String kicker = switch (alertType) {
            case "PORT_DOWN"   -> "PORT İZLEME";
            case "DNS_FAILURE" -> "DNS İZLEME";
            default            -> "ERİŞİLEBİLİRLİK İZLEME";
        };
        String heroTitle = switch (alertType) {
            case "PORT_DOWN"   -> "PORT ERİŞİLEMEZ";
            case "DNS_FAILURE" -> "DNS ÇÖZÜLEMİYOR";
            default            -> "SİTE ERİŞİLEMEZ";
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
        String attemptCount   = ctxStr(ctx, "confirm_attempt_count");
        String delayMs        = ctxStr(ctx, "confirm_delay_ms");
        String endpoint = "DNS_FAILURE".equals(alertType)
                ? escHtml(domain) + (!recordType.isEmpty() ? " · " + escHtml(recordType) + " kaydı" : "")
                : escHtml(domain) + (!port.isEmpty() ? ":" + port : "");
        String attemptsLabel  = !attemptCount.isEmpty() ? attemptCount : "Ardışık";
        String delayLabel     = !delayMs.isEmpty()
                ? (Long.parseLong(delayMs) / 1000) + " sn arayla " : "";

        List<Map<String, Object>> attempts = (ctx != null && ctx.get("confirm_attempts") instanceof List<?> l)
                ? (List<Map<String, Object>>) l : List.of();

        // E-posta CTA: port/dns alarmında monitör detay deep-link'i (?tab=&monitor=<id>);
        // accessibility (uptime) monitör-id taşımaz → CTA boş kalır.
        String outageTab = "PORT_DOWN".equals(alertType) ? "port" : "DNS_FAILURE".equals(alertType) ? "dns" : null;
        String ctaHtml = (outageTab != null && !monitorCtaUrl(outageTab, ctx).isBlank())
                ? "<div style='text-align:center;margin-bottom:20px'>"
                  + ctaButton(monitorCtaUrl(outageTab, ctx), "Monitörü Aç &rarr;", "#1e293b") + "</div>"
                : "";

        // ── Hero — kesinti bildirimi ──
        String hero = "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='margin:20px 0;border-radius:14px;overflow:hidden;border:2px solid #fecaca'>"
            + "<tr>"
            + "<td class='em-hero-l' align='center' valign='middle' width='38%'"
            + " bgcolor='" + red + "' style='background-color:" + red + ";padding:22px 14px'>"
            + "<div style='color:#fff;font-size:52px;line-height:1'>🔴</div>"
            + "<div style='color:#fff;font-size:15px;font-weight:800;margin-top:8px;letter-spacing:.06em'>" + heroTitle + "</div>"
            + "<div style='color:#ffe4e6;font-size:12px;margin-top:8px;padding:0 6px'>⚠ Acil müdahale gerekli</div>"
            + "</td>"
            + "<td class='em-hero-r' valign='middle' bgcolor='#fef2f2' style='background-color:#fef2f2;padding:20px 22px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8;text-transform:uppercase;margin-bottom:10px'>İlk Hata Zamanı</div>"
            + "<div style='font-size:24px;font-weight:900;color:" + red + ";letter-spacing:-.5px'>" + formatIso(firstFailureAt) + "</div>"
            + "<div style='font-size:13px;color:#475569;margin-top:6px;line-height:1.6'>" + formatIsoFull(firstFailureAt) + "</div>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='margin-top:12px'><tr>"
            + "<td bgcolor='" + red + "' style='background-color:" + red + ";border-radius:6px;padding:6px 12px;font-size:12px;font-weight:800;color:#fff'>"
            + "🔁 " + attemptsLabel + " doğrulama denemesi " + delayLabel + "— tümü başarısız</td>"
            + "</tr></table>"
            + "</td>"
            + "</tr></table>";

        // ── Sol kolon: kesinti bilgileri ──
        StringBuilder outageRows = new StringBuilder();
        outageRows.append(tableRow2col("🌐 Uç Nokta", endpoint));
        if ("PORT_DOWN".equals(alertType) && !protocol.isEmpty()) {
            outageRows.append(tableRow2col("🔌 Protokol", escHtml(protocol)));
        }
        if (!firstFailureAt.isEmpty()) {
            outageRows.append(tableRow2col("🕐 İlk Hata", formatIso(firstFailureAt)));
        }
        if (!lastError.isEmpty()) {
            String shortErr = lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError;
            outageRows.append(tableRow2col("⚠ Son Hata", escHtml(shortErr)));
        }
        for (Map<String, Object> a : attempts) {
            String at  = String.valueOf(a.getOrDefault("checked_at", ""));
            String err = String.valueOf(a.getOrDefault("error", ""));
            String time = formatIso(at); // her zaman yerel saat (Europe/Istanbul)
            String detail = !err.isEmpty() && !"null".equals(err)
                    ? time + " — " + escHtml(err.length() > 60 ? err.substring(0, 60) + "…" : err)
                    : time + " — yanıt yok";
            outageRows.append(tableRow2col("🔁 Deneme " + a.getOrDefault("attempt", "?"), detail));
        }

        // ── Sağ kolon: durum özeti ──
        String statusRows = statusRow2col(accessLabel,  "✗ ERİŞİLEMİYOR")
            + statusRow2col("Doğrulama",  "✗ " + (attempts.isEmpty() ? "Başarısız" : attempts.size() + "/" + attempts.size() + " deneme başarısız"))
            + statusRow2col("Seviye",     "✗ KRİTİK")
            + statusRow2col("İzleme",     "✓ Devam ediyor");

        String twoColSection =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px'><tr>"
            + "<td class='em-col-l' valign='top' width='55%' style='width:55%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#1e293b' style='background-color:#1e293b;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>KESİNTİ BİLGİLERİ</td></tr>"
            + outageRows + "</table></td>"
            + "<td class='em-col-r' valign='top' width='45%' style='width:45%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>DURUM ÖZETİ</td></tr>"
            + statusRows + "</table></td>"
            + "</tr></table>";

        // Outlook-güvenli standart (BRAND.md §5.1): <style> bloğu YOK, kart 600px, marka barı üstte.
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif !important}</style><![endif]-->"
            + "</head>"
            + "<body bgcolor='#f1f5f9' style='margin:0;padding:0;background-color:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif;"
            + "-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%'>"

            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#f1f5f9' style='background-color:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td align='center' style='padding:24px 10px'>"

            + "<table role='presentation' width='600' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:600px;max-width:600px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "border:1px solid #d7dde5'>"

            // ── Marka barı + Top bar (kırmızı) ──
            + "<tr><td style='padding:0'>" + brandBar() + "</td></tr>"
            + "<tr><td bgcolor='" + red + "' style='background-color:" + red + ";padding:22px 24px'>"
            + "<div style='color:#ffe4e6;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            + "<div class='em-domain' style='color:#ffffff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🌐 " + endpoint + "</div>"
            + "<div style='color:#ffe4e6;font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>KRİTİK &nbsp;&#183;&nbsp; " + typeTrLabel + "</div>"
            + "</td></tr>"

            // ── Body ──
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"
            + hero
            + twoColSection

            // Alarm detayı (sol aksan-şeritli tablo — border-left div değil)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:14px'><tr>"
            + "<td width='5' bgcolor='" + red + "' style='background-color:" + red + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fef2f2' style='background-color:#fef2f2;padding:14px 18px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + red + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</td></tr></table>"

            // Otomatik kapanış notu
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>"
            + "ℹ Sorun düzeldiğinde bu alarm otomatik kapatılır ve çözüm e-postası gönderilir. "
            + "Alarmı Site Monitor &rarr; Uyarılar &rarr; Alarm Geçmişi ekranından onaylayabilir veya kapatabilirsiniz."
            + "</div>"

            + ctaHtml
            // Olay aksiyonları — ctaHtml'DEN BAĞIMSIZ: ACCESSIBILITY dalında birincil CTA hiç
            // basılmıyor (outageTab null), aksiyonlar yine de görünmeli.
            + incidentActionsRow(ctx, "#1e293b")

            // Footer
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</td></tr>"

            + "</table>"             // em-card
            + "</td></tr></table>"   // dış sarmalayıcı
            + "</body></html>";
    }

    /** İzleme çözüm maili — süre createdAt→resolvedAt'ten hesaplanır; etiketler tipe göre. */
    private String buildRichMonitoringResolvedHtml(String domain, String alertType, String resolvedBy,
                                                   String resolvedAt, String createdAt,
                                                   String teamNames, UptimeSummary uptime,
                                                   Map<String, Object> ctx) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String green = "#16a34a";
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)";
        String duration = outageDurationDisplay(createdAt, resolvedAt);
        boolean dnsChanged = "DNS_CHANGED".equals(alertType);

        String kicker = switch (alertType != null ? alertType : "") {
            case "PORT_DOWN"                 -> "PORT İZLEME";
            case "DNS_FAILURE", "DNS_CHANGED" -> "DNS İZLEME";
            default                          -> "ERİŞİLEBİLİRLİK İZLEME";
        };
        String heroLine = switch (alertType != null ? alertType : "") {
            case "PORT_DOWN"   -> "Port Yeniden Açıldı";
            case "DNS_FAILURE" -> "DNS Çözümleme Düzeldi";
            case "DNS_CHANGED" -> "DNS Değişikliği Alarmı Kapatıldı";
            default            -> "Erişim Yeniden Sağlandı";
        };
        String typeTrLabel = switch (alertType != null ? alertType : "") {
            case "PORT_DOWN"   -> "Port Kesintisi";
            case "DNS_FAILURE" -> "DNS Çözümleme Hatası";
            case "DNS_CHANGED" -> "DNS Değişikliği";
            default            -> "Erişim Kesintisi";
        };
        String levelTrLabel = dnsChanged ? "YÜKSEK" : "KRİTİK";
        String levelColor   = dnsChanged ? "#9333ea" : "#dc2626";
        String durationLabel = dnsChanged ? "⏱ Alarm Süresi" : "⏱ Toplam Kesinti Süresi";
        // Kesinti başlangıç→bitiş net çifti (StatusCake gibi); DNS-changed'de "alarm" terminolojisi korunur.
        String startLabel = dnsChanged ? "📅 Alarm Başlangıcı" : "🔻 Kesinti Başlangıcı";
        String endLabel   = dnsChanged ? "🕐 Çözülme Zamanı"    : "🔺 Yeniden Ulaşılabilir";

        String resolverRows = tableRow2col("👤 Çözen",     escHtml(by))
            + tableRow2col(endLabel,   fmtOrDash(formatIstanbul(resolvedAt)))
            + tableRow2col(startLabel, fmtOrDash(formatIstanbul(createdAt)));

        String outageRows = tableRow2col("🌐 Alan Adı",   escHtml(domain))
            + tableRow2col("⚠ Alarm Tipi",  typeTrLabel)
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>🔴 Seviye</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:700;color:" + levelColor + "'>" + levelTrLabel + "</td>"
            + "</tr>"
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + durationLabel + "</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:14px;font-weight:800;color:" + green + "'>" + duration + "</td>"
            + "</tr>";

        String twoColSection =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px'><tr>"
            + "<td class='em-col-l' valign='top' width='50%' style='width:50%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#15803d' style='background-color:#15803d;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#dcfce7'>ÇÖZÜM BİLGİSİ</td></tr>"
            + resolverRows + "</table></td>"
            + "<td class='em-col-r' valign='top' width='50%' style='width:50%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#94a3b8'>KESİNTİ DETAYI</td></tr>"
            + outageRows + "</table></td>"
            + "</tr></table>";

        // Outlook-güvenli standart (BRAND.md §5.1): <style> bloğu YOK, kart 600px, marka barı üstte.
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif !important}</style><![endif]-->"
            + "</head>"
            + "<body bgcolor='#f1f5f9' style='margin:0;padding:0;background-color:#f1f5f9;"
            + "font-family:\"Segoe UI\",Tahoma,Arial,sans-serif;-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%'>"

            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#f1f5f9' style='background-color:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td align='center' style='padding:24px 10px'>"

            + "<table role='presentation' width='600' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:600px;max-width:600px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "border:1px solid #d7dde5'>"

            // ── Marka barı + Top bar (yeşil) — bgcolor'lı <td> (Outlook-safe) ──
            + "<tr><td style='padding:0'>" + brandBar() + "</td></tr>"
            + "<tr><td bgcolor='" + green + "' style='background-color:" + green + ";padding:22px 24px'>"
            + "<div style='color:#dcfce7;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            + "<div class='em-domain' style='color:#ffffff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>&#9989; " + escHtml(domain) + "</div>"
            + "<div style='color:#eafff1;font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>" + heroLine + " &nbsp;&#183;&nbsp; " + typeTrLabel + "</div>"
            + "</td></tr>"

            // ── Hero ✓ (daire = nested <td bgcolor>, div+border-radius değil) ──
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px 6px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr><td align='center'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center'><tr>"
            + "<td width='80' height='80' align='center' valign='middle' bgcolor='#dcfce7'"
            + " style='background-color:#dcfce7;border-radius:40px;border:3px solid " + green + ";"
            + "color:#15803d;font-size:42px;line-height:80px;text-align:center'>&#10003;</td>"
            + "</tr></table>"
            + "<div style='margin-top:14px;font-size:20px;font-weight:800;color:#15803d;letter-spacing:-.3px'>" + heroLine + "</div>"
            + "<div style='margin-top:6px;font-size:13px;color:#64748b'>Alarm kapatıldı. İzleme devam etmektedir.</div>"
            + "</td></tr></table></td></tr>"

            // ── İki kolon detay ──
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:18px 24px 4px'>" + twoColSection + "</td></tr>"

            // ── Bilgi kutusu (sol aksan-şeritli tablo — border-left div değil) ──
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:0 24px 22px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden'><tr>"
            + "<td width='5' bgcolor='" + green + "' style='background-color:" + green + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:14px 18px;color:#14532d;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + green + ";margin-bottom:6px'>BİLGİ</div>"
            + "<strong>" + escHtml(domain) + "</strong> için açık olan <strong>" + typeTrLabel
            + "</strong> alarmı kapatıldı. "
            + (dnsChanged ? "Alarm süresi: " : "Toplam kesinti süresi: ")
            + "<strong>" + duration + "</strong>."
            + "</td></tr></table></td></tr>"

            // ── Erişilebilirlik özeti (son 24s/7g) — yalnız HTTP uptime verisi olan tiplerde ──
            + uptimeSummaryRow(uptime)
            // ── "Neden bu e-postayı aldınız?" — alıcı şeffaflığı ──
            + whyReceivingRow(teamNames)
            // ── Olay aksiyonları — bu şablon ROW bağlamında (em-card <tr> dizisi) kurulduğu için sarılır ──
            + rowWrap(incidentActionsRow(ctx, "#1f3864"))

            // ── Footer ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:0 24px 18px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;"
            + "font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table></td></tr>"

            + "</table>"             // em-card
            + "</td></tr></table>"   // dış sarmalayıcı
            + "</body></html>";
    }

    // ── Alarm Fırtınası (Alert Storm) — çok monitör birden düştüğünde TEK toplu bildirim ──
    // Outlook-safe: td+bgcolor (div bg değil), solid hex, LIGHT_SCHEME_META, mso font fallback, ctaButton VML.

    /** CANLI base-url (sondaki bölü işaretleri atılmış); @Value yalnız fallback. */
    private String liveBaseUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        return (url == null || url.isBlank()) ? "" : url.replaceAll("/+$", "");
    }

    /** Olay aksiyon butonları (detay + yorum) — ctx'te alert_event_id yoksa "" (çıktı değişmez). */
    private String incidentActionsRow(Map<String, Object> ctx, String accent) {
        return MailCta.incidentActionRow(liveBaseUrl(), ctx == null ? null : ctx.get("alert_event_id"), accent);
    }

    /** Blok içeriği em-card <tr> dizisine sarar (row-bağlamlı şablonlar için); boşsa "" kalır. */
    private static String rowWrap(String blockHtml) {
        return (blockHtml == null || blockHtml.isEmpty()) ? ""
            : "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:0 24px 8px'>" + blockHtml + "</td></tr>";
    }

    /** CANLI base-url'den olay (incidents) ekranına deep-link — reminder/approve/incident ile AYNI kaynak. */
    private String stormCtaUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        String base = (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
        return base.isEmpty() ? "" : base + "/?tab=incidents";
    }

    private String stormTargetRows(List<String> targets, int truncatedExtra, String zebra) {
        StringBuilder sb = new StringBuilder();
        for (String t : targets) {
            sb.append("<tr><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:8px 14px;font-size:13px;color:#1c1917;border-top:1px solid #f1f5f9;word-break:break-all'>")
              .append("🔴 ").append(escHtml(t)).append("</td></tr>");
        }
        if (truncatedExtra > 0) {
            sb.append("<tr><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:8px 14px;font-size:12px;color:#64748b;border-top:1px solid #f1f5f9;font-style:italic'>")
              .append("+ ").append(truncatedExtra).append(" monitör daha…</td></tr>");
        }
        return sb.toString();
    }

    /**
     * Toplu alarm fırtınası e-postası — {@code monitorCount} monitör birden erişilemez.
     * StormService promotion (INITIAL) + günlük toplu re-alert (DAILY_REALERT) bunu kullanır.
     */
    public String buildStormAlertHtml(int monitorCount, String scopeLabel, String rootCauseLabel,
                                      String startedAt, List<String> sampleTargets, int truncatedExtra) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String red = "#dc2626";
        String ctaUrl = stormCtaUrl();
        String cta = (!ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 18px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Olayları Aç &rarr;", "#1e293b") + "</td></tr></table>"
            : "";

        String infoRows = tableRow2col("🌐 Kapsam",       escHtml(scopeLabel))
            + tableRow2col("🧭 Ortak Kök-Neden", escHtml(rootCauseLabel))
            + tableRow2col("🕐 Başlangıç",       fmtOrDash(formatIstanbul(startedAt)))
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' bgcolor='#fef2f2' style='background-color:#fef2f2;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>📉 Etkilenen Monitör</td>"
            + "<td bgcolor='#fef2f2' style='background-color:#fef2f2;padding:9px 13px;font-size:16px;font-weight:800;color:" + red + "'>" + monitorCount + "</td>"
            + "</tr>";

        String listSection =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#1e293b' style='background-color:#1e293b;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>ETKİLENEN MONİTÖRLER</td></tr>"
            + stormTargetRows(sampleTargets, truncatedExtra, "#ffffff")
            + "</table>";

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:14px!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9'"
            + " style='background-color:#f1f5f9'><tr><td align='center' style='padding:24px 10px'>"
            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15)'>"
            // Marka barı (BRAND.md §5.1) + Top bar
            + "<tr><td style='padding:0'>" + brandBar() + "</td></tr>"
            + "<tr><td bgcolor='" + red + "' style='background-color:" + red + ";padding:22px 24px'>"
            + "<div style='color:#ffe4e6;font-size:11px;font-weight:700;letter-spacing:.12em'>İZLEME</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>🌩 ALARM FIRTINASI</div>"
            + "<div style='color:#ffe4e6;font-size:15px;font-weight:700;margin-top:8px'>KRİTİK &nbsp;&#183;&nbsp; " + monitorCount + " monitör birden erişilemez</div>"
            + "</td></tr>"
            // Body
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:16px'><tr>"
            + "<td width='5' bgcolor='" + red + "' style='background-color:" + red + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fffbeb' style='background-color:#fffbeb;padding:14px 18px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + "Kısa bir zaman penceresinde çok sayıda monitör birden erişilemez oldu — olası paylaşılan sunucu / ağ / veri merkezi kesintisi. "
            + "Bireysel alarmlar bu TEK toplu bildirimde gruplandı; sorunlar giderildikçe tek bir toplu \"çözüldü\" e-postası gönderilecektir."
            + "</td></tr></table>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>FIRTINA ÖZETİ</td></tr>"
            + infoRows + "</table>"
            + listSection
            + cta
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table>"
            + "</td></tr></table></td></tr></table></body></html>";
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
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String green = "#16a34a";
        String duration = formatOutageDuration(startedAt, resolvedAt);
        String ctaUrl = stormCtaUrl();
        String cta = (!ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 18px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Olayları Aç &rarr;", "#15803d") + "</td></tr></table>"
            : "";

        String infoRows = tableRow2col("🌐 Kapsam",         escHtml(scopeLabel))
            + tableRow2col("📅 Başlangıç",     fmtOrDash(formatIstanbul(startedAt)))
            + tableRow2col("🕐 Çözülme",       fmtOrDash(formatIstanbul(resolvedAt)))
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>⏱ Toplam Süre</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:14px;font-weight:800;color:" + green + "'>" + duration + "</td>"
            + "</tr>"
            + tableRow2col("✅ Kurtarılan",    recoveredCount + " monitör")
            + (stillDownCount > 0 ? tableRow2col("⚠ Hâlâ İzlemede", stillDownCount + " monitör (bireysel alarma döndü)") : "");

        String listSection =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#15803d' style='background-color:#15803d;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#dcfce7'>&#10003; KURTARILAN MONİTÖRLER</td></tr>"
            + stormRecoveredRows(sampleTargets, truncatedExtra)
            + "</table>"
            + (stillDownTargets == null || stillDownTargets.isEmpty() ? "" :
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #fecaca;border-radius:10px;overflow:hidden'>"
                + "<tr><td bgcolor='#b91c1c' style='background-color:#b91c1c;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#fee2e2'>&#9888; HÂLÂ ERİŞİLEMEYEN (bireysel alarma döndü)</td></tr>"
                + stormTargetRows(stillDownTargets, Math.max(0, stillDownCount - stillDownTargets.size()), "#ffffff")
                + "</table>");

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:14px!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#eef2f6;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#eef2f6'"
            + " style='background-color:#eef2f6'><tr><td align='center' style='padding:24px 10px'>"
            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15)'>"
            + "<tr><td style='padding:0'>" + brandBar() + "</td></tr>"   // BRAND.md §5.1 lockup
            + "<tr><td bgcolor='" + green + "' style='background-color:" + green + ";padding:22px 24px'>"
            + "<div style='color:#dcfce7;font-size:11px;font-weight:700;letter-spacing:.12em'>İZLEME</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>✅ ALARM FIRTINASI SONA ERDİ</div>"
            + "<div style='color:#dcfce7;font-size:15px;font-weight:700;margin-top:8px'>" + recoveredCount + " monitör kurtarıldı</div>"
            + "</td></tr>"
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>FIRTINA ÖZETİ</td></tr>"
            + infoRows + "</table>"
            + listSection
            + cta
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table>"
            + "</td></tr></table></td></tr></table></body></html>";
    }

    private String stormRecoveredRows(List<String> targets, int truncatedExtra) {
        StringBuilder sb = new StringBuilder();
        for (String t : targets) {
            sb.append("<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:8px 14px;font-size:13px;color:#1c1917;border-top:1px solid #f1f5f9;word-break:break-all'>")
              .append("&#10003; ").append(escHtml(t)).append("</td></tr>");
        }
        if (truncatedExtra > 0) {
            sb.append("<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:8px 14px;font-size:12px;color:#64748b;border-top:1px solid #f1f5f9;font-style:italic'>")
              .append("+ ").append(truncatedExtra).append(" monitör daha…</td></tr>");
        }
        return sb.toString();
    }

    // ── Keyword / Ping izleme — kendine ÖZGÜ executive alarm + çözüm şablonları ──
    // Cert/expiry şablonuyla hiçbir alan paylaşmaz; ortak yalnız kart/CSS iskeleti (aşağıdaki frame helper'ları).

    /** Ortak executive alarm kartı (keyword/ping) — kimlik (accent/ikon/başlık) ve içerik dışarıdan gelir. */
    /** Monitör detayına deep-link CTA URL'si (?tab=<tab>&monitor=<id>); base/monitor yoksa boş. */
    private String monitorCtaUrl(String tab, Map<String, Object> ctx) {
        Object mid = ctx != null ? ctx.get("monitor_id") : null;
        if (mid == null) return "";
        // CANLI okunur (Genel Ayarlar'dan değişebilir); @Value yalnız fallback — reminder/approve/incident ile AYNI kaynak.
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        String base = (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
        return base + "/?tab=" + tab + "&monitor=" + mid;
    }

    /** Header'da izlenen hedefi beyaz stille gösterir. Gerçek URL ise (http/https) tıklanabilir &lt;a&gt;;
     *  çıplak host (ping/port) ise şemasız kırık link yerine stillendirilmiş &lt;span&gt; (Outlook auto-link engeli korunur). */
    private String endpointLink(String value) {
        String esc = escHtml(value);
        boolean url = value != null && (value.startsWith("http://") || value.startsWith("https://"));
        return url
            ? "<a href='" + esc + "' target='_blank' style='color:#ffffff;text-decoration:none'>" + esc + "</a>"
            : "<span style='color:#ffffff'>" + esc + "</span>";
    }

    private String monitoringTypedAlert(String accent, String kicker, String emoji,
            String heroTitle, String heroSub, String endpoint, String typeBadge,
            String firstFailureAt, String attemptsLabel, String delayLabel,
            String leftRows, String rightRows, String extraBox, String message, String infoNote, String ctaUrl,
            String actionsHtml) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 16px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Monitörü Aç &rarr;", accent) + "</td></tr></table>"
            : "";

        String hero = "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='margin:18px 0;border-radius:14px;overflow:hidden;border:1px solid #e2e8f0'><tr>"
            + "<td class='em-hero-l' align='center' valign='middle' width='38%' bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 14px'>"
            + "<div style='color:#fff;font-size:46px;line-height:1'>" + emoji + "</div>"
            + "<div style='color:#fff;font-size:14px;font-weight:800;margin-top:8px;letter-spacing:.04em'>" + heroTitle + "</div>"
            + "<div style='color:#ffffff;font-size:12px;margin-top:8px;padding:0 6px'>" + heroSub + "</div>"
            + "</td>"
            + "<td class='em-hero-r' valign='middle' bgcolor='#f8fafc' style='background:#f8fafc;padding:20px 22px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8;text-transform:uppercase;margin-bottom:10px'>İlk Hata Zamanı</div>"
            + "<div style='font-size:24px;font-weight:900;color:" + accent + ";letter-spacing:-.5px'>" + formatIso(firstFailureAt) + "</div>"
            + "<div style='font-size:13px;color:#475569;margin-top:6px;line-height:1.6'>" + formatIsoFull(firstFailureAt) + "</div>"
            + "<div style='margin-top:12px;font-size:12px;font-weight:700;color:#475569'>"
            + "🔁 " + attemptsLabel + " doğrulama denemesi " + delayLabel + "— tümü başarısız</div>"
            + "</td></tr></table>";

        // Tek-kolon (alt alta) — Outlook'ta yan-yana kolonlar kayıyordu; tam genişlik bölümler kaymaz.
        String twoCol = "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#1f3864' style='background:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>İZLEME BİLGİLERİ</td></tr>"
            + leftRows + "</table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#1f3864' style='background:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>DOĞRULAMA ÖZETİ</td></tr>"
            + rightRows + "</table>";

        // Outlook-güvenli standart (BRAND.md §5.1): <style> bloğu YOK (Outlook/Gmail kırpar — tüm
        // stiller zaten inline; mobil medya-iyileştirmesi bilinçli feda edildi), kart 600px.
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]--></head>"
            // Dış arka plan (bgcolor attr) + tablo lspace/rspace sıfır (Outlook tabloya boşluk eklemesin → kayma);
            // dış padding TD'de (Outlook tablo padding'ini yok sayar); kart bgcolor='#ffffff' → dış gri içeri sızmaz.
            + "<body bgcolor='#f1f5f9' style='margin:0;padding:0;background:#f1f5f9;-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9' style='background:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td align='center' style='padding:24px 10px'>"
            + "<table width='600' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='width:600px;max-width:600px;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15);mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td style='padding:0'>"
            + brandBar()
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + accent + "' style='background-color:" + accent + "'><tr><td style='padding:22px 24px'>"
            + "<div style='color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            // URL explicit beyaz <a> içinde — Outlook çıplak URL'yi otomatik linkleyip mavi yapıyor (navy zeminde okunmaz).
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;margin-top:10px;word-break:break-all;line-height:1.25'>" + emoji + " " + endpointLink(endpoint) + "</div>"
            + "<div style='color:#ffffff;font-size:15px;font-weight:700;margin-top:8px;letter-spacing:.02em'>KRİTİK &nbsp;&#183;&nbsp; " + typeBadge + "</div>"
            + "</td></tr></table>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='background-color:#ffffff'><tr><td class='em-body' style='padding:22px 24px'>"
            // Sorunu en üstte, sade ve net (executive) — kırmızı şeritli uyarı kutusu
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:2px 0 16px'><tr>"
            + "<td bgcolor='#fef2f2' style='background:#fef2f2;border-left:5px solid #dc2626;padding:15px 18px'>"
            + "<div style='font-size:12px;font-weight:800;letter-spacing:.07em;color:#dc2626;margin-bottom:6px'>⚠ SORUN TESPİT EDİLDİ</div>"
            + "<div style='font-size:15px;font-weight:600;color:#1c1917;line-height:1.6'>" + escHtml(message) + "</div>"
            + "</td></tr></table>"
            + hero + twoCol + extraBox + cta + actionsHtml
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>" + infoNote + "</div>"
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table></td></tr></table></td></tr></table></body></html>";
    }

    /** Ortak attempt satırları — keyword/ping alarm sol kolonunda teyit denemeleri. */
    @SuppressWarnings("unchecked")
    private String attemptRows(Map<String, Object> ctx) {
        List<Map<String, Object>> attempts = (ctx != null && ctx.get("confirm_attempts") instanceof List<?> l)
                ? (List<Map<String, Object>>) l : List.of();
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> a : attempts) {
            String at = String.valueOf(a.getOrDefault("checked_at", ""));
            String err = String.valueOf(a.getOrDefault("error", ""));
            String detail = !err.isEmpty() && !"null".equals(err)
                    ? formatIso(at) + " — " + escHtml(err.length() > 60 ? err.substring(0, 60) + "…" : err)
                    : formatIso(at) + " — doğrulanamadı";
            sb.append(tableRow2col("🔁 Deneme " + a.getOrDefault("attempt", "?"), detail));
        }
        return sb.toString();
    }

    private static int attemptCount(Map<String, Object> ctx) {
        return (ctx != null && ctx.get("confirm_attempts") instanceof List<?> l) ? l.size() : 0;
    }

    /** Keyword izleme alarmı — kurumsal lacivert, adet/operatör koşulu alanları. */
    private String buildRichKeywordAlertHtml(String message, String url, String level, Map<String, Object> ctx) {
        String accent = "#1f3864";   // Akbank kurumsal lacivert
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
        String ac = ctxStr(ctx, "confirm_attempt_count");
        String delayMs = ctxStr(ctx, "confirm_delay_ms");
        String attemptsLabel = !ac.isEmpty() ? ac : "Ardışık";
        String delayLabel    = !delayMs.isEmpty() ? (Long.parseLong(delayMs) / 1000) + " sn arayla " : "";
        int n = attemptCount(ctx);

        StringBuilder left = new StringBuilder();
        left.append(tableRow2col("🌐 Adres", escHtml(url)));
        left.append(tableRow2col("🔎 Aranan kelime", escHtml(keyword)));
        left.append(tableRow2col("⚙ Beklenen koşul", condPhrase + " bulunmalı"));
        if (!occ.isEmpty()) left.append(tableRow2col("🔢 Bulunan adet", occ + " kez"));
        if (!httpStatus.isEmpty()) left.append(tableRow2col("📡 HTTP durumu", escHtml(httpStatus)));
        if (!responseMs.isEmpty()) left.append(tableRow2col("⏱ Yanıt süresi", escHtml(responseMs) + " ms"));
        if (!firstFailureAt.isEmpty()) left.append(tableRow2col("🕐 İlk hata", formatIso(firstFailureAt)));
        if (!lastError.isEmpty() && !"null".equalsIgnoreCase(lastError))
            left.append(tableRow2col("⚠ Son hata", escHtml(lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError)));

        String wordStatus = absent ? "✗ İstenmeyen ifade var"
                : (!occ.isEmpty() ? "✗ " + occ + " kez · gerekli: " + condPhrase : "✗ Koşul sağlanmadı");
        String right = statusRow2col("Kelime durumu", wordStatus)
            + statusRow2col("Doğrulama", "✗ " + (n == 0 ? "Başarısız" : n + "/" + n + " başarısız"))
            + statusRow2col("Seviye", "✗ KRİTİK")
            + statusRow2col("İzleme", "✓ Devam ediyor");

        String extraBox = (absent && !snippet.isEmpty())
            ? "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:14px'><tr>"
              + "<td bgcolor='#f8fafc' style='background-color:#f8fafc;border-left:4px solid " + accent + ";padding:12px 16px'>"
              + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + accent + ";margin-bottom:6px'>EŞLEŞME BAĞLAMI</div>"
              + "<div style='font-size:13px;color:#1c1917;font-family:Consolas,monospace;word-break:break-word'>…" + escHtml(snippet) + "…</div></td></tr></table>"
            : "";

        return monitoringTypedAlert(accent, "İÇERİK (KEYWORD) İZLEME", "🔎",
                absent ? "İSTENMEYEN İFADE BULUNDU" : "KOŞUL SAĞLANMADI",
                "⚠ İçerik doğrulaması başarısız", escHtml(url), "İçerik Doğrulama",
                firstFailureAt, attemptsLabel, delayLabel, left.toString(), right, extraBox, message,
                "ℹ Koşul yeniden sağlandığında bu alarm otomatik kapatılır ve çözüm e-postası gönderilir.",
                monitorCtaUrl("keyword", ctx), incidentActionsRow(ctx, accent));
    }

    /** Ping (ICMP) izleme alarmı — kurumsal lacivert, erişilebilirlik alanları. */
    private String buildRichPingAlertHtml(String message, String host, String level, Map<String, Object> ctx) {
        String accent = "#1f3864";
        boolean na = "true".equalsIgnoreCase(ctxStr(ctx, "na"));
        String ipVersion  = ctxStr(ctx, "ip_version");
        String packetLoss = ctxStr(ctx, "packet_loss");
        String rttMs      = ctxStr(ctx, "rtt_ms");
        String firstFailureAt = ctxStr(ctx, "first_failure_at");
        String lastError      = ctxStr(ctx, "last_error");
        String ac = ctxStr(ctx, "confirm_attempt_count");
        String delayMs = ctxStr(ctx, "confirm_delay_ms");
        String attemptsLabel = !ac.isEmpty() ? ac : "Ardışık";
        String delayLabel    = !delayMs.isEmpty() ? (Long.parseLong(delayMs) / 1000) + " sn arayla " : "";
        String lossLabel     = !packetLoss.isEmpty() ? "%" + packetLoss : "%100";
        int n = attemptCount(ctx);

        StringBuilder left = new StringBuilder();
        left.append(tableRow2col("🖥️ Host", escHtml(host)));
        left.append(tableRow2col("🔢 IP sürümü", ipVersion.isEmpty() || "auto".equals(ipVersion) ? "Otomatik" : escHtml(ipVersion.toUpperCase())));
        left.append(tableRow2col("📉 Paket kaybı", lossLabel));
        if (!rttMs.isEmpty()) left.append(tableRow2col("⏱ RTT", escHtml(rttMs) + " ms"));
        if (!firstFailureAt.isEmpty()) left.append(tableRow2col("🕐 İlk hata", formatIso(firstFailureAt)));
        if (!lastError.isEmpty() && !"null".equalsIgnoreCase(lastError))
            left.append(tableRow2col("⚠ Son hata", escHtml(lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError)));

        String right = statusRow2col("Ping durumu", na ? "✗ ICMP kullanılamıyor" : "✗ Yanıt yok")
            + statusRow2col("Paket kaybı", "✗ " + lossLabel)
            + statusRow2col("Doğrulama", "✗ " + (n == 0 ? "Başarısız" : n + "/" + n + " başarısız"))
            + statusRow2col("Seviye", "✗ KRİTİK")
            + statusRow2col("İzleme", "✓ Devam ediyor");

        return monitoringTypedAlert(accent, "PİNG (ICMP) İZLEME", "🖥️",
                na ? "ICMP KULLANILAMIYOR" : "HOST YANIT VERMİYOR",
                "⚠ Erişilebilirlik kaybı", escHtml(host), "Erişilebilirlik (Ping)",
                firstFailureAt, attemptsLabel, delayLabel, left.toString(), right, "", message,
                "ℹ Host yeniden yanıt verdiğinde bu alarm otomatik kapatılır ve çözüm e-postası gönderilir.",
                monitorCtaUrl("ping", ctx), incidentActionsRow(ctx, accent));
    }

    /** Ortak executive çözüm (yeşil) kartı — keyword/ping kimliğiyle. */
    private String monitoringTypedResolved(String domain, String kicker, String heroLine,
            String typeTrLabel, String emoji, String detailRows, String ctaUrl,
            String resolvedBy, String resolvedAt, String createdAt,
            String teamNames, UptimeSummary uptime, String actionsHtml) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String green = "#16a34a";
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)";
        String duration = outageDurationDisplay(createdAt, resolvedAt);
        String detailSection = (detailRows != null && !detailRows.isBlank())
            ? "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
              + "<tr><td bgcolor='#1f3864' style='background-color:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>ÇÖZÜLEN ALARM DETAYI</td></tr>"
              + detailRows + "</table>"
            : "";
        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Monitörü Aç &rarr;", "#1f3864") + "</td></tr></table>"
            : "";

        String resolverRows = tableRow2col("👤 Çözen", escHtml(by))
            + tableRow2col("🔺 Yeniden Ulaşılabilir", fmtOrDash(formatIstanbul(resolvedAt)))
            + tableRow2col("🔻 Kesinti Başlangıcı", fmtOrDash(formatIstanbul(createdAt)));
        String outageRows = tableRow2col("🌐 İzlenen", escHtml(domain))
            + tableRow2col("⚠ Alarm Tipi", typeTrLabel)
            + "<tr style='border-top:1px solid #e2e8f0'><td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>🔴 Seviye</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:700;color:#dc2626'>KRİTİK</td></tr>"
            // Vurgu satırı: <tr background> Outlook'ta beyaza düşer → her td'ye bgcolor (kardeş buildRichMonitoringResolvedHtml deseni)
            + "<tr style='border-top:1px solid #e2e8f0'><td width='1%' bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>⏱ Toplam Kesinti Süresi</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:14px;font-weight:800;color:" + green + "'>" + duration + "</td></tr>";

        // Tek-kolon (alt alta) — Outlook'ta yan-yana kolonlar kayıyordu; tam genişlik bölümler kaymaz.
        String twoCol = "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#15803d' style='background:#15803d;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#dcfce7'>ÇÖZÜM BİLGİSİ</td></tr>"
            + resolverRows + "</table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#1f3864' style='background:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>KESİNTİ DETAYI</td></tr>"
            + outageRows + "</table>";

        // Outlook-güvenli standart (BRAND.md §5.1): <style> bloğu YOK, kart 600px, marka barı üstte.
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]--></head>"
            // Dış arka plan (bgcolor attr) + tablo lspace/rspace sıfır (Outlook tabloya boşluk eklemesin → kayma);
            // dış padding TD'de (Outlook tablo padding'ini yok sayar); kart bgcolor='#ffffff' → dış gri içeri sızmaz.
            + "<body bgcolor='#f1f5f9' style='margin:0;padding:0;background:#f1f5f9;-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9' style='background:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td align='center' style='padding:24px 10px'>"
            + "<table width='600' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='width:600px;max-width:600px;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15);mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td style='padding:0'>"
            + brandBar()
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + green + "' style='background-color:" + green + "'><tr><td style='padding:22px 24px'>"
            + "<div style='color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            // URL explicit beyaz <a> içinde — Outlook çıplak URL'yi otomatik linkleyip mavi yapıyor (yeşil zeminde okunmaz).
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;margin-top:10px;word-break:break-all;line-height:1.25'>✅ " + endpointLink(domain) + "</div>"
            + "<div style='color:#ffffff;font-size:15px;font-weight:700;margin-top:8px;letter-spacing:.02em'>" + heroLine + " &nbsp;&#183;&nbsp; " + typeTrLabel + "</div>"
            + "</td></tr></table>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='background-color:#ffffff'><tr><td class='em-body' style='padding:22px 24px'>"
            + "<div style='text-align:center;margin:14px 0 22px'>"
            + "<div style='font-size:54px;line-height:1;color:" + green + "'>✓</div>"
            + "<div style='margin-top:8px;font-size:20px;font-weight:800;color:#15803d'>" + heroLine + "</div>"
            + "<div style='margin-top:6px;font-size:13px;color:#64748b'>Alarm kapatıldı. İzleme devam etmektedir.</div></div>"
            + twoCol
            + detailSection
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:20px'><tr>"
            + "<td width='5' bgcolor='" + green + "' style='background-color:" + green + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:14px 18px;color:#14532d;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + green + ";margin-bottom:6px'>BİLGİ</div>"
            + "<strong>" + escHtml(domain) + "</strong> için açık olan <strong>" + typeTrLabel + "</strong> alarmı kapatıldı. Toplam kesinti süresi: <strong>" + duration + "</strong>.</td></tr></table>"
            + uptimeSummaryBlock(uptime)
            + whyReceivingBlock(teamNames)
            + cta + actionsHtml
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table></td></tr></table></td></tr></table></body></html>";
    }

    private String buildRichKeywordResolvedHtml(String url, Map<String, Object> ctx, String resolvedBy, String resolvedAt,
                                                String createdAt, String teamNames, UptimeSummary uptime) {
        StringBuilder d = new StringBuilder();
        if (ctx != null) {
            String kw = ctxStr(ctx, "keyword");
            String op = ctxStr(ctx, "operator"); if (op.isEmpty()) op = "GTE";
            int n = ctx.get("match_count") instanceof Number mn ? mn.intValue() : 1;
            String occ = ctxStr(ctx, "occurrences");
            if (!kw.isEmpty()) d.append(tableRow2col("🔎 Aranan kelime", escHtml(kw)));
            d.append(tableRow2col("⚙ Koşul", KeywordCheckerService.opPhrase(op, n) + " bulunmalı"));
            if (!occ.isEmpty()) d.append(tableRow2col("🔢 Alarm anı bulunan", occ + " kez"));
        }
        return monitoringTypedResolved(url, "İÇERİK (KEYWORD) İZLEME",
                "İçerik Doğrulaması Yeniden Başarılı", "İçerik Doğrulama", "🔎",
                d.toString(), monitorCtaUrl("keyword", ctx), resolvedBy, resolvedAt, createdAt, teamNames, uptime,
                incidentActionsRow(ctx, "#1f3864"));
    }

    private String buildRichPingResolvedHtml(String host, Map<String, Object> ctx, String resolvedBy, String resolvedAt,
                                             String createdAt, String teamNames, UptimeSummary uptime) {
        StringBuilder d = new StringBuilder();
        if (ctx != null) {
            String ipv = ctxStr(ctx, "ip_version");
            d.append(tableRow2col("🖥️ Host", escHtml(host)));
            if (!ipv.isEmpty() && !"auto".equals(ipv)) d.append(tableRow2col("🔢 IP sürümü", escHtml(ipv.toUpperCase())));
        }
        return monitoringTypedResolved(host, "PİNG (ICMP) İZLEME",
                "Host Yeniden Yanıt Veriyor", "Erişilebilirlik (Ping)", "🖥️",
                d.toString(), monitorCtaUrl("ping", ctx), resolvedBy, resolvedAt, createdAt, teamNames, uptime,
                incidentActionsRow(ctx, "#1f3864"));
    }

    /**
     * DNS kayıt değişikliği alarm maili (YÜKSEK, mor) — ESKİ | YENİ değerler
     * iki kolon halinde. Teyit denemeleri bölümü yoktur (değişiklik başarılı
     * sorgudan pozitif gözlemdir); alarm otomatik kapanmaz. ctx okumaları
     * null-toleranslıdır ("Tekrar Bildir" yolu eksik context geçirebilir).
     */
    private String buildRichDnsChangedAlertHtml(String message, String domain, Map<String, Object> ctx) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String purple = "#9333ea";

        String recordType = ctxStr(ctx, "record_type");
        String changedAt  = ctxStr(ctx, "changed_at");
        List<String> oldValues = ctxList(ctx, "old_values");
        List<String> newValues = ctxList(ctx, "new_values");

        return buildDnsChangedHtmlInternal(message, domain, recordType, changedAt, oldValues, newValues, purple,
                generatedAt, incidentActionsRow(ctx, purple));
    }

    @SuppressWarnings("unchecked")
    private List<String> ctxList(Map<String, Object> ctx, String key) {
        if (ctx == null) return List.of();
        Object v = ctx.get(key);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    private String buildDnsChangedHtmlInternal(String message, String domain, String recordType,
                                               String changedAt, List<String> oldValues,
                                               List<String> newValues, String purple, String generatedAt,
                                               String actionsHtml) {
        String twoColSection = dnsDiffTable(oldValues, newValues, purple);

        // Outlook-güvenli standart (BRAND.md §5.1): <style> bloğu YOK, kart 600px, marka barı üstte.
        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<meta name='color-scheme' content='light only'>"
            + "<meta name='supported-color-schemes' content='light'>"
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + "</head>"
            + "<body style='margin:0;padding:0;background-color:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#f1f5f9' style='background-color:#f1f5f9;mso-table-lspace:0;mso-table-rspace:0'>"
            + "<tr><td align='center' style='padding:24px 10px'>"

            + "<table role='presentation' width='600' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:600px;max-width:600px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'>"

            // ── Marka barı + Top bar (mor) — bgcolor'lı <td> ──
            + "<tr><td style='padding:0'>" + brandBar() + "</td></tr>"
            + "<tr><td bgcolor='" + purple + "' style='background-color:" + purple + ";padding:22px 24px'>"
            + "<div style='color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.12em'>DNS İZLEME</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🔀 " + escHtml(domain)
            + (!recordType.isEmpty() ? " <span style='font-size:15px;font-weight:700'>· " + escHtml(recordType) + " kaydı</span>" : "")
            + "</div>"
            + "<div style='color:#ffffff;font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>YÜKSEK &nbsp;&#183;&nbsp; DNS Değişikliği</div>"
            + "</td></tr>"

            // ── Body ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"

            // Hero (mor pill — bgcolor'lı <td>, inline-block div değil)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:16px 0 22px'><tr><td align='center'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center'><tr>"
            + "<td bgcolor='" + purple + "' style='background-color:" + purple + ";border-radius:12px;padding:14px 32px;font-size:17px;font-weight:800;letter-spacing:.02em;color:#fff'>"
            + "🔀 DNS KAYDI DEĞİŞTİ</td>"
            + "</tr></table>"
            + (!changedAt.isEmpty()
                ? "<div style='margin-top:10px;font-size:13px;color:#64748b'>Tespit zamanı: " + formatIsoFull(changedAt) + "</div>" : "")
            + "</td></tr></table>"

            + twoColSection

            // Alarm detayı (sol aksan-şeritli tablo)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:14px'><tr>"
            + "<td width='5' bgcolor='" + purple + "' style='background-color:" + purple + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#faf5ff' style='background-color:#faf5ff;padding:14px 18px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + purple + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</td></tr></table>"

            // Manuel kapanış notu
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>"
            + "ℹ Bu alarm otomatik kapanmaz. Değişiklik planlı ise Site Monitor &rarr; Uyarılar &rarr; "
            + "Alarm Geçmişi ekranından alarmı onaylayın ve kapatın. Beklenmedik bir değişiklikse "
            + "(olası domain hijack / hatalı migrasyon) derhal ağ ekibiyle iletişime geçin."
            + "</div>"

            + actionsHtml

            // Footer
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</td></tr>"

            + "</table>"
            + "</td></tr></table>"
            + "</body></html>";
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
    private static final HtmlRenderer MD_RENDERER = HtmlRenderer.builder()
            .extensions(MD_EXTENSIONS).escapeHtml(true).softbreak("<br />\n").build();

    /**
     * Genel amaçlı HTML mail — CC ve inline CID görsel desteğiyle.
     * ÖNEMLİ: setText(html, true) addInline'dan ÖNCE çağrılmalıdır
     * (MimeMessageHelper related multipart sıralaması).
     */
    /** Gönderen adres — rapor gönderim geçmişi kayıtları için (haftalık + aylık envanter). */
    public String fromAddress() {
        return currentFrom();
    }

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

    public String sendHtml(String[] to, String[] cc, String subject, String html,
                           List<InlineImage> inline, boolean force, List<MailAttachment> attachments) {
        if (!force && !isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} CC={} | KONU={}",
                    Arrays.toString(to), Arrays.toString(cc != null ? cc : new String[0]), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    msg, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setTo(to);
            if (cc != null && cc.length > 0) helper.setCc(cc);
            applyFrom(helper);
            helper.setSubject(subject);
            helper.setText(html, true);
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
            return doSend(Arrays.toString(to), msg, 1);
        } catch (Exception e) {
            log.error("✗ HTML e-posta hazırlanamadı: TO={} | HATA={}", Arrays.toString(to), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /** Markdown → HTML (GFM tabloları destekli, raw HTML escape'li).
     *  forEmail=true: /api/weekly-reports/images/{id} → cid:img{id}. */
    private String mdToHtml(String md, boolean forEmail, Map<Long, Integer> imageWidths, int maxWidth) {
        if (md == null || md.isBlank()) return "";
        String src = forEmail
                ? md.replaceAll("\\]\\(/api/weekly-reports/images/(\\d+)\\)", "](cid:img$1)")
                : md;
        String html = MD_RENDERER.render(MD_PARSER.parse(src));
        html = inlineImageStyles(taskCheckboxesToSymbols(html), forEmail, imageWidths, maxWidth);
        // Outlook <head><style>'ı yok sayar; markdown bloklarının .wr-md stillerini
        // (liste girintisi, paragraf, tablo) maile inline et → Outlook = önizleme.
        return forEmail ? inlineBlockStyles(html) : html;
    }

    /** Markdown çıktısındaki blok elemanlara .wr-md ile birebir aynı inline stilleri
     *  ekler (yalnız mail yolu). Yalnız markdown HTML'ine uygulanır — chip/section
     *  tabloları ayrı üretildiğinden etkilenmez. GFM tablo align attr'ı korunur. */
    private String inlineBlockStyles(String html) {
        if (html == null || html.isEmpty()) return html;
        return html
            .replace("<ul>", "<ul style=\"margin:6px 0;padding-left:22px;font-size:14px;color:#1e293b\">")
            .replace("<ol>", "<ol style=\"margin:6px 0;padding-left:22px;font-size:14px;color:#1e293b\">")
            .replace("<p>",  "<p style=\"margin:6px 0;font-size:14px;line-height:1.6;color:#1e293b\">")
            .replace("<table>", "<table style=\"border-collapse:collapse;width:100%;margin:8px 0\">")
            .replaceAll("<th(\\s|>)", "<th style=\"border:1px solid #e2e8f0;padding:6px 10px;font-size:13px;text-align:left;background:#f8fafc;font-weight:700\"$1")
            .replaceAll("<td(\\s|>)", "<td style=\"border:1px solid #e2e8f0;padding:6px 10px;font-size:13px;text-align:left\"$1");
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
     *  taşmasını ancak inline stil + açık width attribute engeller. Görsel
     *  genişliği bulunduğu bölümün maxWidth'i ile sınırlanır (konuma göre
     *  dinamik); width attr → Outlook, width:100%/max-width → modern, display:block
     *  → Gmail alt boşluğu. */
    /* package */ static final int MAIL_IMG_MAX_WIDTH = 720; // madde 1-3: kart 850 − iç boşluklar
    /* package */ static final int MAIL_IMG_MAX_WIDTH_CHANNEL = 660; // madde 4 kanal alt-kartı (ekstra padding)
    private static final Pattern CID_IMG = Pattern.compile("<img src=\"cid:img(\\d+)\"");
    private static final Pattern API_IMG = Pattern.compile("<img src=\"(/api/weekly-reports/images/\\d+)\"");

    private String inlineImageStyles(String html, boolean forEmail, Map<Long, Integer> imageWidths, int maxWidth) {
        if (!forEmail) {
            return API_IMG.matcher(html).replaceAll(
                    "<img style=\"max-width:100%;height:auto;border-radius:8px;margin:6px 0\" src=\"$1\"");
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

    /**
     * Haftalık rapor onay maili / önizleme HTML'i. contentJson şeması için
     * bkz. WeeklyReportService.DEFAULT_TEMPLATE_JSON. forEmail=false UI
     * önizlemesi içindir (görsel URL'leri /api olarak kalır).
     */
    /** PO onay-bekleyen mailinde rapor içeriğinin üstüne/altına eklenen onay CTA bloğu
     *  (yeşil banner + bulletproof "Onayla" butonu). url boşsa boş döner. */
    private String approveCtaBlock(String approveUrl) {
        if (approveUrl == null || approveUrl.isBlank()) return "";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 18px'>"
            + "<tr><td align='center' bgcolor='#ecfdf5' style='background:#ecfdf5;border:1px solid #a7f3d0;"
            + "border-radius:10px;padding:16px'>"
            + "<div style='font-size:14px;font-weight:700;color:#065f46;margin-bottom:10px'>"
            + "Bu rapor onayınızı bekliyor</div>"
            + ctaButton(approveUrl, "✅ Raporu onaylamak için tıklayınız &rarr;", "#15803d")
            + "</td></tr></table>";
    }

    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail, null);
    }

    /** imageWidths: görsel id → gösterim genişliği px (Outlook width attr için);
     *  null/eksik girişlerde 560 fallback. */
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail,
                imageWidths, null, null, null);
    }

    /** Footer'a onay bilgisi ekler: approverName + approvedAtIso + sentAtIso (UTC ISO,
     *  Europe/Istanbul'a çevrilir). null olanlar gizlenir (örn. DRAFT önizleme). */
    @SuppressWarnings("unchecked")
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

    /** {@code approveCtaUrl} doluysa (PO onay-bekleyen maili): rapor içeriğinin üstüne ve altına "Raporu onayla"
     *  CTA bloğu eklenir. {@code kpiSummary} doluysa hero altında canlı KPI özet satırı (toplam/dolan/alarm/kritik/uptime)
     *  gösterilir — WeeklyReportKpiService'ten (read-only). */
    @SuppressWarnings("unchecked")
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso,
                                        String approveCtaUrl, Map<String, Object> kpiSummary) {
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        // Footer sağ sütun — onay bilgisi (varsa) + oluşturma zamanı; hepsi Europe/Istanbul
        StringBuilder footerRight = new StringBuilder();
        if (approverName != null && !approverName.isBlank())
            footerRight.append("Onaylayan: ").append(escHtml(approverName)).append("<br>");
        String approvedIst = formatIstanbul(approvedAtIso);
        if (approvedIst != null) footerRight.append("Onay: ").append(approvedIst).append("<br>");
        String sentIst = formatIstanbul(sentAtIso);
        if (sentIst != null) footerRight.append("Gönderim: ").append(sentIst).append("<br>");
        footerRight.append("Oluşturuldu: ").append(generatedAt);
        // Executive lacivert palet — arkaplanlar Outlook (Word motoru) için
        // table/td + bgcolor ATTRIBUTE ile verilir; style yalnız yedektir.
        String accent = "#1f3864";
        String outerBg = "#f4f6f8";

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

        // ── Madde 1 — sayı chip'leri + durum + takip linki ──
        String item1Body =
            numChipRow(
                numChip("Toplam", i1.get("total"), "#334155"),
                numChip("Acil",   i1.get("urgent"), "#dc2626"),
                numChip("Yüksek", i1.get("high"),   "#ea580c"),
                numChip("Orta",   i1.get("medium"), "#d97706"),
                numChip("Düşük",  i1.get("low"),    "#16a34a"))
            + metaLine("Durum", str(i1.get("status_text")))
            + linkLine("Proaktif İyileştirme kayıtlarına erişmek için tıklayınız", str(i1.get("tracking_url")))
            + mdToHtml(str(i1.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH);

        // ── Madde 2 ──
        // Üç sayı da 0 ise otomatik vurgulu "kayıt yok" notu (manuel yazıma gerek kalmaz)
        boolean noItem2Records = intVal(i2.get("open_incidents")) == 0
                && intVal(i2.get("problem_records")) == 0
                && intVal(i2.get("postmortems")) == 0;
        String item2AutoNote = noItem2Records
            // Renkli kutu: <p background> Outlook'ta beyaza düşer → accent-şeritli tablo (td bgcolor)
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:8px 0;border-radius:8px;overflow:hidden'><tr>"
              + "<td width='4' bgcolor='#16a34a' style='background-color:#16a34a;width:4px;font-size:0;line-height:0'>&nbsp;</td>"
              + "<td bgcolor='#e7f6ec' style='background-color:#e7f6ec;padding:8px 12px;font-size:13px;font-weight:700;color:#14532d'>"
              + "✔ Bu hafta aşım yaşanan olay, problem veya açık postmortem kaydı bulunmamaktadır.</td></tr></table>"
            : "";
        String item2Body =
            numChipRow(
                numChip("Açık Olay",   i2.get("open_incidents"),  "#dc2626"),
                numChip("Problem",     i2.get("problem_records"), "#ea580c"),
                numChip("Postmortem",  i2.get("postmortems"),     "#7c3aed"))
            + item2AutoNote
            + linkLine("Açık olay kayıtları için tıklayınız", str(i2.get("incidents_url")))
            + linkLine("Problem kayıtları için tıklayınız", str(i2.get("problems_url")))
            + linkLine("Postmortem kayıtları için tıklayınız", str(i2.get("postmortems_url")))
            + linkLine("İlgili kayıtlar için tıklayınız", str(i2.get("tracking_url"))) // eski raporlardaki genel link
            + mdToHtml(str(i2.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH);

        // ── Madde 3 ──
        String item3Body = mdToHtml(str(i3.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH);

        // ── Madde 4 — kanal alt-kartları ──
        StringBuilder item4Body = new StringBuilder();
        Object channelsObj = i4.get("channels");
        if (channelsObj instanceof List<?> channels) {
            for (Object chObj : channels) {
                Map<String, Object> ch = asMap(chObj);
                item4Body.append("<div style='border:1px solid #e2e8f0;border-radius:10px;margin-bottom:10px;overflow:hidden'>")
                    .append("<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>")
                    .append("<td bgcolor='#eef1f5' style='background:#eef1f5;padding:8px 14px;")
                    .append("font-size:13px;font-weight:800;color:#334155'>")
                    .append(escHtml(str(ch.get("name")))).append("</td></tr></table>")
                    .append("<div style='padding:10px 14px'>")
                    .append(mdToHtml(str(ch.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH_CHANNEL))
                    .append("</div></div>");
            }
        }

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + ".wr-md table{border-collapse:collapse;width:100%;margin:8px 0}"
            + ".wr-md th,.wr-md td{border:1px solid #e2e8f0;padding:6px 10px;font-size:13px;text-align:left}"
            + ".wr-md th{background:#f8fafc;font-weight:700}"
            + ".wr-md img{max-width:100%;height:auto;border-radius:8px;margin:6px 0}"
            + ".wr-md p{margin:6px 0;font-size:14px;line-height:1.6;color:#1e293b}"
            + ".wr-md ul,.wr-md ol{margin:6px 0;padding-left:22px;font-size:14px;color:#1e293b}"
            + "@media only screen and (max-width:870px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + css + "</head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";padding:24px 10px'>"
            + "<tr><td align='center' bgcolor='" + outerBg + "'>"

            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar (availability deseni)
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.10)'><tr><td bgcolor='#ffffff' style='padding:0'>"
            + brandBar()   // BRAND.md §5.1 lockup

            // ── Üst bar — div shading Outlook'ta güvenilmez; td + bgcolor attr ──
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#aebed8;font-size:11px;font-weight:700;letter-spacing:.12em'>Site Monitor — Haftalık Rapor</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>📋 "
            + escHtml(teamName) + "</div>"
            + "<div style='color:#dbe3ef;font-size:15px;font-weight:700;margin-top:8px'>"
            + escHtml(weekLabel) + "</div>"
            + "</td></tr></table>"

            // ── Gövde ──
            // Gövde — div padding'i Outlook (Word) yok sayar → td padding'i (em-body class'ı td'de)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"

            // Hitap + giriş
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın "
            + escHtml(managerName != null && !managerName.isBlank() ? managerName : "Yönetici") + ",</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 18px'>"
            + escHtml(teamName) + " ekibi olarak <strong>" + escHtml(weekLabel)
            + "</strong> haftası raporumuzu aşağıda paylaşıyoruz.</p>"

            // Onay CTA — hitabın hemen ardında, rapor içeriğinin ÜSTÜNDE (PO kaydırmadan görsün);
            // gövde ortasında (özet ile bölümler arasında) kalmasın.
            + approveCtaBlock(approveCtaUrl)

            + weeklyOverviewSection(kpiSummary, accent)

            + reportSection("1. Proaktif Servis İyileştirme Kayıtları", item1Body, accent)
            + reportSection("2. Aşım Yaşanan Olay / Problem ve Açık Postmortem Kayıtları", item2Body, accent)
            + reportSection("3. Haftalık Katılım Sağlanan Çalışmalar", item3Body, accent)
            + reportSection("4. Domain Bazlı Kritik İşlerin Durumu", item4Body.toString(), accent)

            + approveCtaBlock(approveCtaUrl)

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor — Haftalık Rapor</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8;line-height:1.7'>"
            + footerRight + "</td></tr></table>"

            + "</td></tr></table>"   // em-body td + gövde tablosu
            + "</td></tr></table>"   // kart iç td + kart tablosu
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table>"   // dış (wrap) td + tablo
            + "</body></html>";
    }

    /** PO'ya onay bekleyen rapor bilgilendirmesi. */
    public String buildWeeklyReportSubmittedHtml(String teamName, String weekLabel, String submittedBy,
                                                 String approveUrl) {
        return buildSimpleAlertHtml(
                "[Site Monitor] " + teamName + " — " + weekLabel + " raporu onayınızı bekliyor",
                teamName + " ekibinin " + weekLabel + " haftalık raporu "
                + (submittedBy != null ? submittedBy : "ekip üyesi")
                + " tarafından onayınıza sunuldu. Aşağıdaki butonla (giriş yapmadan) doğrudan "
                + "onaylayabilir ya da Site Monitor → Raporlar → Haftalık Raporlar ekranından "
                + "inceleyip düzeltme talebiyle iade edebilirsiniz.",
                approveUrl, "✅ Raporu onaylamak için tıklayınız →");
    }

    /** Takıma iade/düzeltme talebi bildirimi. */
    public String buildWeeklyReportRejectedHtml(String teamName, String weekLabel,
                                                String note, String rejectedBy) {
        return buildSimpleAlertHtml(
                "[Site Monitor] " + teamName + " — " + weekLabel + " raporu iade edildi",
                weekLabel + " haftalık raporunuz "
                + (rejectedBy != null ? rejectedBy : "PO")
                + " tarafından düzeltme talebiyle iade edildi.\n\nDüzeltme notu: "
                + (note != null ? note : "—")
                + "\n\nRaporu güncelleyip tekrar onaya gönderebilirsiniz.");
    }

    /** Cuma hatırlatma maili — executive lacivert şablon. Henüz raporunu girmemiş
     *  SY takımlarına, bugün 15:00 son giriş hatırlatması + "nasıl girilir" kısa kılavuz
     *  + doğrudan Haftalık Raporlar'a giden CTA link. Mail her zaman TR. */
    public String buildWeeklyReportReminderHtml(String teamName, String weekLabel, String reportUrl) {
        return buildWeeklyReportReminderHtml(teamName, weekLabel, reportUrl, "bugün saat 15:00");
    }

    /** {@code deadlineText} = "bugün saat 15:00" / "Perşembe saat 17:00" — canlı ayardan (2026-09-12). */
    public String buildWeeklyReportReminderHtml(String teamName, String weekLabel, String reportUrl, String deadlineText) {
        String accent = "#1f3864";
        String outerBg = "#f4f6f8";
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        // CTA buton — "bulletproof": Outlook (Word) için VML v:roundrect (yuvarlak köşe +
        // sabit genişlik), diğer istemciler için HTML <td>+<a> (beyaz metin <span> ile sabit).
        // mso/non-mso koşullu yorumlarıyla her istemci yalnız kendi sürümünü görür.
        String btnLabel = "📝 Haftalık raporu girmek için tıklayınız &rarr;";
        String cta = (reportUrl != null && !reportUrl.isBlank())
            ? "<div style='margin:4px 0 20px'>"
              + "<!--[if mso]>"
              + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\""
              + " href=\"" + escHtml(reportUrl) + "\" style=\"height:48px;v-text-anchor:middle;width:380px;\""
              + " arcsize=\"16%\" strokecolor=\"" + accent + "\" fillcolor=\"" + accent + "\">"
              + "<w:anchorlock/>"
              + "<center style=\"color:#ffffff;font-family:'Segoe UI',Tahoma,Arial,sans-serif;font-size:15px;font-weight:bold;\">"
              + btnLabel + "</center>"
              + "</v:roundrect>"
              + "<![endif]-->"
              + "<!--[if !mso]><!-->"
              + "<table role='presentation' border='0' cellspacing='0' cellpadding='0'><tr>"
              + "<td align='center' bgcolor='" + accent + "' style='background:" + accent + ";border-radius:8px;"
              + "padding:13px 26px;color:#ffffff'>"
              + "<a href='" + escHtml(reportUrl) + "' target='_blank' style='color:#ffffff;text-decoration:none;"
              + "font-size:15px;font-weight:800;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
              + "<span style='color:#ffffff'>" + btnLabel + "</span></a>"
              + "</td></tr></table>"
              + "<!--<![endif]-->"
              + "</div>"
            : "";

        // "Nasıl girilir?" — sabit (güvenilir) HTML; <strong> kaçırılmaz
        String stepsBody =
            "<ol style='margin:0;padding-left:20px;font-size:14px;line-height:1.8;color:#1e293b'>"
            + "<li>Sol menüden <strong>Raporlar → Haftalık Raporlar</strong>'a gidin.</li>"
            + "<li><strong>Yeni Hafta Raporu</strong> ile yıl/hafta seçip <strong>Oluştur</strong>'a tıklayın.</li>"
            + "<li>Dört maddeyi doldurun: Proaktif İyileştirmeler · Olay/Problem/Postmortem · Katılımlar · Domain bazlı kritik işler.</li>"
            + "<li><strong>Kaydet</strong>; hazır olunca <strong>Onaya Gönder</strong>.</li>"
            + "<li>PO onayından sonra rapor müdüre otomatik iletilir.</li>"
            + "</ol>";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<style>@media only screen and (max-width:870px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";padding:24px 10px'>"
            + "<tr><td align='center' bgcolor='" + outerBg + "'>"

            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar (availability deseni)
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.10)'><tr><td bgcolor='#ffffff' style='padding:0'>"
            // ── Marka barı (BRAND.md §5.1 lockup) + Üst bar ──
            + brandBar()
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#aebed8;font-size:11px;font-weight:700;letter-spacing:.12em'>HAFTALIK RAPOR HATIRLATMASI</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>⏰ "
            + escHtml(teamName) + "</div>"
            + "<div style='color:#dbe3ef;font-size:15px;font-weight:700;margin-top:8px'>"
            + escHtml(weekLabel) + "</div>"
            + "</td></tr></table>"

            // ── Gövde ── (div padding'i Outlook yok sayar → td-tabanlı em-body)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın " + escHtml(teamName) + " ekibi,</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 14px'>"
            + "Bu haftanın (<strong>" + escHtml(weekLabel) + "</strong>) haftalık raporu sistemde henüz görünmüyor. "
            + "Mesai başlangıcıyla birlikte raporunuzu hatırlatmak isteriz.</p>"

            // Son giriş uyarısı (vurgulu)
            // Renkli kutu: <p background> Outlook'ta beyaza düşer → accent-şeritli tablo (td bgcolor)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 4px;border-radius:8px;overflow:hidden'><tr>"
            + "<td width='4' bgcolor='#dc2626' style='background-color:#dc2626;width:4px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fef2f2' style='background-color:#fef2f2;padding:10px 14px;font-size:14px;font-weight:700;color:#991b1b'>"
            + "⏰ Son giriş <strong>" + escHtml(deadlineText) + "</strong> — lütfen bu haftanın raporunu Site Monitor üzerinden zamanında giriniz.</td></tr></table>"

            + cta

            + reportSection("Haftalık Rapor Nasıl Girilir?", stepsBody, accent)

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor — Otomatik Hatırlatma</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8;line-height:1.7'>"
            + "Oluşturuldu: " + generatedAt + "</td></tr></table>"

            + "</td></tr></table>"   // em-body td + gövde tablosu
            + "</td></tr></table>"   // kart iç td + kart tablosu
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table>"   // dış (wrap) td + tablo
            + "</body></html>";
    }

    // ── Alan adı süre-bitişi hatırlatması (2026-09-22, madde E) ─────────────────────

    /**
     * Eşik hatırlatması: "X alan adının kaydı N gün sonra doluyor" — Outlook-güvenli desen (td bgcolor, düz hex,
     * LIGHT_SCHEME_META, MSO ghost-table, VML CTA), haftalık rapor hatırlatmasıyla aynı iskelet. CTA izleme
     * detayına derin bağlantı (?tab=domain&monitor=id); base URL canlı ayardan.
     */
    public String buildDomainExpiryReminderHtml(String name, String domain, int days, String expiryIso, int threshold,
                                                String registrar, String level, Long monitorId) {
        boolean critical = "CRITICAL".equals(level);
        boolean expired = days < 0;
        String accent = critical ? "#991b1b" : "WARNING".equals(level) ? "#b45309" : "#1f3864";
        String outerBg = "#f4f6f8";
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String expiryText = expiryIso == null ? "—" : expiryIso.length() >= 10 ? expiryIso.substring(8, 10) + "." + expiryIso.substring(5, 7) + "." + expiryIso.substring(0, 4) : expiryIso;
        String headline = expired
                ? "⚠ " + escHtml(name) + " — alan adı kaydı " + Math.abs(days) + " gün önce DOLDU"
                : "⏰ " + escHtml(name) + " — alan adı bitişine " + days + " gün";
        String url = liveBaseUrl().isBlank() || monitorId == null ? "" : liveBaseUrl() + "/?tab=domain&monitor=" + monitorId;
        String btnLabel = "Alan adı izlemesini aç &rarr;";
        String cta = url.isBlank() ? "" :
              "<div style='margin:4px 0 20px'>"
            + "<!--[if mso]>"
            + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\""
            + " href=\"" + escHtml(url) + "\" style=\"height:44px;v-text-anchor:middle;width:300px;\""
            + " arcsize=\"16%\" strokecolor=\"" + accent + "\" fillcolor=\"" + accent + "\">"
            + "<w:anchorlock/>"
            + "<center style=\"color:#ffffff;font-family:'Segoe UI',Tahoma,Arial,sans-serif;font-size:15px;font-weight:bold;\">"
            + btnLabel + "</center>"
            + "</v:roundrect>"
            + "<![endif]-->"
            + "<!--[if !mso]><!-->"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0'><tr>"
            + "<td align='center' bgcolor='" + accent + "' style='background:" + accent + ";border-radius:8px;padding:12px 24px;color:#ffffff'>"
            + "<a href='" + escHtml(url) + "' target='_blank' style='color:#ffffff;text-decoration:none;font-size:15px;font-weight:800;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<span style='color:#ffffff'>" + btnLabel + "</span></a>"
            + "</td></tr></table>"
            + "<!--<![endif]-->"
            + "</div>";

        String facts =
              "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='font-size:14px;color:#1e293b;line-height:1.7'>"
            + "<tr><td width='160' style='color:#64748b;padding:2px 0'>Alan adı</td><td style='padding:2px 0'><strong>" + escHtml(domain) + "</strong></td></tr>"
            + "<tr><td style='color:#64748b;padding:2px 0'>Bitiş tarihi</td><td style='padding:2px 0'><strong>" + escHtml(expiryText) + "</strong></td></tr>"
            + "<tr><td style='color:#64748b;padding:2px 0'>Kalan gün</td><td style='padding:2px 0'><strong style='color:" + accent + "'>" + (expired ? Math.abs(days) + " gün önce doldu" : days + " gün") + "</strong></td></tr>"
            + "<tr><td style='color:#64748b;padding:2px 0'>Registrar</td><td style='padding:2px 0'>" + escHtml(registrar == null || registrar.isBlank() ? "—" : registrar) + "</td></tr>"
            + "<tr><td style='color:#64748b;padding:2px 0'>Hatırlatma eşiği</td><td style='padding:2px 0'>" + threshold + " gün</td></tr>"
            + "</table>";

        String stepsBody =
              "<ol style='margin:0;padding-left:20px;font-size:14px;line-height:1.8;color:#1e293b'>"
            + "<li>Registrar panelinde alan adını yenileyin (otomatik yenileme açıksa ödeme yöntemini doğrulayın).</li>"
            + "<li>Yenileme sonrası Site Monitor'de <strong>Şimdi Kontrol Et</strong> ile bitiş tarihinin ileri gittiğini görün — bu hatırlatma serisi yeni bitiş için sıfırdan başlar.</li>"
            + "<li>Transfer kilidi yoksa (kartta \"Kilit yok\") registrar'dan kilidi açtırın.</li>"
            + "</ol>";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<style>@media only screen and (max-width:870px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";padding:24px 10px'>"
            + "<tr><td align='center' bgcolor='" + outerBg + "'>"
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.10)'><tr><td bgcolor='#ffffff' style='padding:0'>"
            + brandBar()
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#e2e8f0;font-size:11px;font-weight:700;letter-spacing:.12em'>ALAN ADI SÜRE BİTİŞİ HATIRLATMASI</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>" + headline + "</div>"
            + "<div style='color:#e2e8f0;font-size:14px;font-weight:600;margin-top:8px'>" + escHtml(domain) + " · bitiş " + escHtml(expiryText) + "</div>"
            + "</td></tr></table>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 14px'>"
            + "Bu ileti izlemenin <strong>" + threshold + " gün</strong> hatırlatma eşiği için <strong>bir kez</strong> gönderilir; "
            + "sonraki eşiklerde (" + "daha az gün kala" + ") yeniden hatırlatılır. Alan adı yenilenince seri kendiliğinden sıfırlanır.</p>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 14px;border-radius:8px;overflow:hidden'><tr>"
            + "<td width='4' bgcolor='" + accent + "' style='background-color:" + accent + ";width:4px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#f8fafc' style='background-color:#f8fafc;padding:10px 14px'>" + facts + "</td></tr></table>"
            + cta
            + reportSection("Ne yapmalı?", stepsBody, accent)
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor — Otomatik Hatırlatma</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8;line-height:1.7'>"
            + "Oluşturuldu: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table>"
            + "</body></html>";
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

    /** Sertifika sahibi takıma haftalık erişilebilirlik özeti (executive). rows en kötü
     *  availability üstte sıralı gelir; down domainler ayrı vurgulanır.
     *  {@code att} doluysa gövdeye ek duyuru bandı eklenir. */
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
        String accent = "#1f3864";
        String outerBg = "#f4f6f8";
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        // KPI bandı (4 kart) — td+bgcolor (Outlook uyumlu)
        String avgTxt = pctText(s.avgAvailabilityPct());
        String kpi =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 18px;border-collapse:separate;border-spacing:8px 0'><tr>"
            + kpiCard("İZLENEN DOMAIN", String.valueOf(s.domainCount()), "#1e293b", "#f8fafc")
            + kpiCard("ORT. ERİŞİLEBİLİRLİK", avgTxt, pctColor(s.avgAvailabilityPct()), "#f8fafc")
            + kpiCard("KESİNTİ YAŞAYAN", String.valueOf(s.downDomainCount()),
                      s.downDomainCount() > 0 ? "#dc2626" : "#16a34a", "#f8fafc")
            + kpiCard("EN YAKIN SERTİFİKA", s.nearestCertDays() != null ? s.nearestCertDays() + " gün" : "—",
                      s.nearestCertDays() != null && s.nearestCertDays() <= 30 ? "#dc2626" : "#1e293b", "#f8fafc")
            + "</tr></table>";

        // Ek duyuru bandı — ekin ADI ve İÇERİĞİ gövdede yazılı olmazsa çoğu okuyucu eki kaçırır.
        // Outlook-güvenli: td + bgcolor, düz hex (rgba yok), div arka planı yok.
        String attachSection = "";
        if (att != null) {
            String what = att.alarmCount() == 0
                    ? "Bu hafta kesinti yaşanmadı; ek, kapsam ve yöntem notunu içerir."
                    : att.alarmCount() + " alarmın tamamı — izleme türü bazında gruplanmış detay, "
                      + "kesinti zaman çizelgesi, gün/saat yoğunluğu ve erişilebilirlik tabloları. "
                      + (att.stillOpenCount() > 0
                         ? "<strong>" + att.stillOpenCount() + " alarm hâlâ açık.</strong>" : "");
            attachSection =
                "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 18px'><tr>"
                + "<td bgcolor='#eff6ff' style='background:#eff6ff;border-left:4px solid #2563eb;"
                + "border-radius:0 8px 8px 0;padding:12px 16px;font-size:13px;color:#1e293b'>"
                + "<span style='font-weight:800;color:#1d4ed8'>📎 Ek: ayrıntılı kesinti raporu (PDF)</span><br>"
                + "<span style='font-size:12px;color:#475569'>" + escHtml(att.fileName()) + "</span><br>"
                + "<span style='font-size:12px;color:#334155'>" + what + "</span><br>"
                + "<span style='font-size:11px;color:#64748b'>Ek, bu e-postadan DAHA GENİŞ bir kapsamı "
                + "raporlar: " + att.monitorTypeCount() + " izleme türünün alarmları. Bu gövde ise yalnız "
                + "sertifika envanterindeki domainlerin HTTP erişilebilirliğini gösterir — iki yerdeki "
                + "sayılar bu yüzden birbirini tutmaz.</span>"
                + "</td></tr></table>";
        }

        // Kesinti bölümü — varsa kırmızı liste, yoksa yeşil "kesinti yok" bandı (mail her durumda gider)
        StringBuilder down = new StringBuilder();
        for (AvailabilityRow r : rows) {
            if (r.outageCount() > 0) {
                down.append("<tr style='border-top:1px solid #fecaca'>")
                    .append("<td style='padding:7px 12px;font-size:13px;font-weight:700;color:#991b1b'>").append(escHtml(r.domain())).append("</td>")
                    .append("<td style='padding:7px 12px;font-size:13px;color:#b91c1c;white-space:nowrap'>").append(pctText(r.availabilityPct())).append("</td>")
                    .append("<td style='padding:7px 12px;font-size:13px;color:#b91c1c;white-space:nowrap'>")
                    .append(r.outageCount()).append(" kesinti · ").append(r.downtimeMinutes()).append(" dk</td>")
                    .append("</tr>");
            }
        }
        String downSection = s.downDomainCount() > 0
            ? "<div style='margin:0 0 18px'>"
              + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
              + "<td bgcolor='#dc2626' style='background:#dc2626;color:#fff;border-radius:8px 8px 0 0;padding:9px 14px;font-size:13px;font-weight:800'>"
              + "⚠ Bu hafta kesinti yaşayan domainler (" + s.downDomainCount() + ")</td></tr></table>"
              + "<table width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#fef2f2' style='background:#fef2f2;border:1px solid #fecaca;border-top:none;border-radius:0 0 8px 8px'>"
              + down + "</table></div>"
            : "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 18px'><tr>"
              + "<td bgcolor='#ecfdf5' style='background:#ecfdf5;border-left:4px solid #16a34a;border-radius:0 8px 8px 0;"
              + "padding:12px 16px;font-size:14px;font-weight:700;color:#15803d'>✓ Bu hafta hiçbir domain kesinti yaşamadı 🎉</td>"
              + "</tr></table>";

        // ── Sayfa Hızı bölümü (opsiyonel) ────────────────────────────────────────────────
        // Outlook-güvenli: td + bgcolor, düz hex (rgba yok), div arka planı yok.
        // Bölüm KESİNTİ DEĞİL performans anlatır — başlık ve dip not bunu açıkça söyler ki
        // okuyan yavaşlığı erişilebilirlik rakamlarıyla karıştırmasın.
        String pageSpeedSection = "";
        if (ps != null && !ps.slowest().isEmpty()) {
            StringBuilder psBody = new StringBuilder();
            psBody.append("<tr>")
                  .append(thCell("Sayfa", "left")).append(thCell("Ort. yükleme", "left"))
                  .append(thCell("Geçen haftaya göre", "left")).append(thCell("Eşik aşımı", "left"))
                  .append("</tr>");
            for (PageSpeedWeeklyRow r : ps.slowest()) {
                String load = r.avgLoadMs() != null ? r.avgLoadMs() + " ms" : "—";
                String trend = "—";
                String trendColor = "#64748b";
                if (r.avgLoadMs() != null && r.prevAvgLoadMs() != null && r.prevAvgLoadMs() > 0) {
                    long diff = r.avgLoadMs() - r.prevAvgLoadMs();
                    long pct = Math.round(100.0 * diff / r.prevAvgLoadMs());
                    if (pct > 0) { trend = "▲ %" + pct + " yavaşladı"; trendColor = "#b91c1c"; }
                    else if (pct < 0) { trend = "▼ %" + Math.abs(pct) + " hızlandı"; trendColor = "#15803d"; }
                    else { trend = "değişmedi"; }
                }
                psBody.append("<tr style='border-top:1px solid #e2e8f0'>")
                      .append("<td style='padding:9px 13px;font-size:13px;font-weight:600;color:#1e293b;word-break:break-all'>")
                      .append(escHtml(r.name())).append("</td>")
                      .append("<td style='padding:9px 13px;font-size:14px;font-weight:800;color:#1e293b;white-space:nowrap'>")
                      .append(load).append("</td>")
                      .append("<td style='padding:9px 13px;font-size:13px;font-weight:700;color:").append(trendColor)
                      .append(";white-space:nowrap'>").append(trend).append("</td>")
                      .append("<td style='padding:9px 13px;font-size:13px;color:")
                      .append(r.breachedChecks() > 0 ? "#b91c1c" : "#64748b").append(";white-space:nowrap'>")
                      .append(r.breachedChecks() > 0 ? r.breachedChecks() + " ölçüm" : "—").append("</td>")
                      .append("</tr>");
            }
            pageSpeedSection =
                "<div style='margin:0 0 18px'>"
                + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
                + "<td bgcolor='" + accent + "' style='background:" + accent + ";color:#fff;border-radius:8px 8px 0 0;"
                + "padding:9px 14px;font-size:13px;font-weight:800'>Sayfa Hızı — en yavaş "
                + ps.slowest().size() + " sayfa"
                + (ps.breachedMonitorCount() > 0
                   ? " · " + ps.breachedMonitorCount() + " izlemede eşik aşıldı" : "")
                + "</td></tr></table>"
                + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;"
                + "border-top:none;border-collapse:collapse'>" + psBody + "</table>"
                + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
                + "<td bgcolor='#f8fafc' style='background:#f8fafc;border:1px solid #e2e8f0;border-top:none;"
                + "border-radius:0 0 8px 8px;padding:8px 14px;font-size:11px;color:#64748b'>"
                + "Bu bölüm PERFORMANSI anlatır, kesintiyi değil: yavaş bir sayfa yukarıdaki "
                + "erişilebilirlik yüzdesini düşürmez. Ölçüm sunucudan çekilen HTML ve alt kaynaklarla "
                + "yapılır; tarayıcı çalıştırılmadığı için JavaScript ile sonradan yüklenen kaynaklar "
                + "sayıma girmez.</td></tr></table></div>";
        }

        // ── Sürüm & Dağıtım satırı (E2) — Outlook-güvenli: td + bgcolor, düz hex ────────────────
        // Okuyan "bu hafta ne değişti?" sorusunu kesinti tablosunun yanında görsün diye; geri alma
        // varsa kenar çizgisi kırmızıya döner. TR ana satır + EN alt satır (rapor dili sabit TR).
        String deploySection = "";
        if (dep != null) {
            String range = (dep.fromVersion() != null && dep.toVersion() != null && !dep.fromVersion().equals(dep.toVersion()))
                    ? " (v" + dep.fromVersion() + " → v" + dep.toVersion() + ")"
                    : (dep.toVersion() != null ? " (v" + dep.toVersion() + ")" : "");
            String tr = dep.deployments() == 0
                    ? "Bu hafta dağıtım yapılmadı; " + dep.restarts() + " yeniden başlatma, " + dep.rollbacks() + " geri alma"
                    : "Bu hafta " + dep.deployments() + " dağıtım" + range + ", " + dep.restarts()
                      + " yeniden başlatma, " + dep.rollbacks() + " geri alma";
            String en = dep.deployments() == 0
                    ? "No deployments this week; " + dep.restarts() + " restart(s), " + dep.rollbacks() + " rollback(s)"
                    : dep.deployments() + " deployment(s)" + range + ", " + dep.restarts()
                      + " restart(s), " + dep.rollbacks() + " rollback(s) this week";
            String edge = dep.rollbacks() > 0 ? "#dc2626" : "#64748b";
            deploySection =
                "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 18px'><tr>"
                + "<td bgcolor='#f8fafc' style='background:#f8fafc;border-left:4px solid " + edge + ";"
                + "border-radius:0 8px 8px 0;padding:10px 16px;font-size:13px;color:#1e293b'>"
                + "<span style='font-weight:800;color:#334155'>🚀 Sürüm &amp; Dağıtım</span><br>"
                + "<span style='font-size:13px;color:#334155'>" + escHtml(tr) + "</span><br>"
                + "<span style='font-size:11px;color:#64748b'>" + escHtml(en) + "</span>"
                + "</td></tr></table>";
        }

        // Zayıf algoritma bandı (2026-09-12): "temiz" raporun da kanıtı olsun — denetim/uyum ekibi
        // "0 (tarandı: 212)" satırını görür; zayıf varsa kenar çizgisi kırmızı.
        String weakSection = "";
        if (weak != null) {
            String edge = weak.weak() > 0 ? "#dc2626" : "#16a34a";
            String tr = weak.weak() == 0
                    ? "Zayıf algoritmalı sertifika yok (tarandı: " + weak.scanned() + " alan)"
                    : weak.weak() + " sertifika zayıf imza/anahtar kullanıyor (tarandı: " + weak.scanned() + " alan) — yenileme planı gerekli";
            String en = weak.weak() == 0
                    ? "No weak-algorithm certificates (" + weak.scanned() + " domains scanned)"
                    : weak.weak() + " certificate(s) use a weak signature/key (" + weak.scanned() + " domains scanned) — renewal plan needed";
            weakSection =
                "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 18px'><tr>"
                + "<td bgcolor='#f8fafc' style='background:#f8fafc;border-left:4px solid " + edge + ";"
                + "border-radius:0 8px 8px 0;padding:10px 16px;font-size:13px;color:#1e293b'>"
                + "<span style='font-weight:800;color:#334155'>🔐 Zayıf Algoritma</span><br>"
                + "<span style='font-size:13px;color:#334155'>" + escHtml(tr) + "</span><br>"
                + "<span style='font-size:11px;color:#64748b'>" + escHtml(en) + "</span>"
                + "</td></tr></table>";
        }

        // Domain tablosu (en kötü üstte — servis sıralar)
        StringBuilder body = new StringBuilder();
        body.append("<tr>")
            .append(thCell("Domain", "left")).append(thCell("Erişilebilirlik", "left"))
            .append(thCell("Kesinti", "left")).append(thCell("Yanıt (ort/p95)", "left"))
            .append(thCell("Sertifika", "left")).append("</tr>");
        for (AvailabilityRow r : rows) {
            String pc = pctColor(r.availabilityPct());
            String resp = (r.avgMs() != null)
                ? r.avgMs() + " / " + (r.p95Ms() != null ? r.p95Ms() : "—") + " ms" : "—";
            String certTxt = r.certDaysRemaining() != null ? r.certDaysRemaining() + " gün" : "—";
            String certColor = r.certDaysRemaining() != null && r.certDaysRemaining() <= 30 ? "#dc2626"
                             : r.certDaysRemaining() != null && r.certDaysRemaining() <= 60 ? "#d97706" : "#475569";
            body.append("<tr style='border-top:1px solid #e2e8f0'>")
                .append("<td style='padding:9px 13px;font-size:13px;font-weight:600;color:#1e293b;word-break:break-all'>").append(escHtml(r.domain())).append("</td>")
                .append("<td style='padding:9px 13px;font-size:14px;font-weight:800;color:").append(pc).append(";white-space:nowrap'>").append(pctText(r.availabilityPct())).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:").append(r.outageCount() > 0 ? "#b91c1c" : "#64748b").append(";white-space:nowrap'>")
                .append(r.outageCount() > 0 ? r.outageCount() + " · " + r.downtimeMinutes() + " dk" : "—").append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:#475569;white-space:nowrap'>").append(resp).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;font-weight:600;color:").append(certColor).append(";white-space:nowrap'>").append(certTxt).append("</td>")
                .append("</tr>");
        }
        String table =
            "<div style='margin:0 0 18px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";color:#fff;border-radius:8px 8px 0 0;padding:9px 14px;font-size:13px;font-weight:800'>Domain Erişilebilirlik Detayı</td></tr></table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-top:none;border-radius:0 0 8px 8px;border-collapse:collapse'>"
            + body + "</table></div>";

        String bestWorst = (s.bestDomain() != null && s.worstDomain() != null && s.withDataCount() > 0)
            ? "<p style='font-size:13px;color:#475569;margin:0 0 14px;line-height:1.7'>"
              + "En yüksek: <strong style='color:#15803d'>" + escHtml(s.bestDomain()) + "</strong> (" + pctText(s.bestPct()) + ") · "
              + "En düşük: <strong style='color:" + pctColor(s.worstPct()) + "'>" + escHtml(s.worstDomain()) + "</strong> (" + pctText(s.worstPct()) + ")</p>"
            : "";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + LIGHT_SCHEME_META
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            // Outlook (Word motoru) yazı tipi fallback'i — Segoe UI yoksa Arial
            + "<!--[if mso]><style>table,td,div,p{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->"
            + "<style>@media only screen and (max-width:870px){"
            + ".em-pad{padding:16px 0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            // Dış padding TABLO style'ında değil merkez TD'sinde — Outlook tablo padding'ini yok sayar
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td align='center' class='em-pad' bgcolor='" + outerBg + "' style='padding:24px 10px'>"
            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.10)'>"
            + "<tr><td bgcolor='#ffffff' style='padding:0'>"
            // Marka barı (BRAND.md §5.1) + Üst bar
            + brandBar()
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#aebed8;font-size:11px;font-weight:700;letter-spacing:.12em'>HAFTALIK ERİŞİLEBİLİRLİK</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>📊 " + escHtml(teamName) + "</div>"
            + "<div style='color:#dbe3ef;font-size:15px;font-weight:700;margin-top:8px'>" + escHtml(weekLabel) + "</div>"
            + "</td></tr></table>"
            // Gövde — div padding'i Outlook yok sayar → td padding'i (em-body class'ı td'de)
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın " + escHtml(teamName) + " ekibi,</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 14px'>"
            + "Aşağıda sahip olduğunuz domainlerin geçen haftaya (<strong>" + escHtml(weekLabel) + "</strong>) ait erişilebilirlik özeti yer almaktadır.</p>"
            + kpi
            + attachSection
            + downSection
            + bestWorst
            + table
            + pageSpeedSection
            + deploySection
            + weakSection
            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor — Otomatik Haftalık Rapor</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Oluşturuldu: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table>"   // em-body td + gövde tablosu
            + "</td></tr></table>"   // kart iç td + kart tablosu
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table></body></html>";   // merkez td + dış tablo
    }

    // ── Aylık sertifika envanteri raporu ─────────────────────────────────────

    /** Rapor tablosunun bir satırı — kalan süre görünümü. */
    public record InventoryReportRow(String domain, String teamName, Integer tier,
                                     Integer daysRemaining, String notAfter, String status) { }

    /** Hijyen bulgu grubu (InventoryHygieneService.Group'un mail-katmanı karşılığı). */
    public record InventoryFindingGroup(String title, int total, List<String[]> samples, int hidden) { }

    /**
     * Aylık sertifika envanteri raporu — 850px "haftalık aile" düzeni.
     * Sıra: marka barı → başlık → KPI bandı (Aktif/Pasif/Silinmiş/Toplam) → kalan süre tablosu
     * → hijyen bulguları → ek notu → CTA → footer.
     */
    public String buildCertInventoryReportHtml(String monthLabel, Map<String, Integer> counts,
                                               List<InventoryReportRow> rows,
                                               List<InventoryFindingGroup> findings,
                                               List<String> attachmentNames) {
        String accent = "#1f3864";
        String outerBg = "#eef2f7";
        String generatedAt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Istanbul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        // Silinmiş kayıtlar raporda YOK — sahibinden aksiyon beklenmeyen satırlar gürültü yapıyordu.
        String kpi = "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='margin:4px 0 18px;border-collapse:separate;border-spacing:8px 0'><tr>"
            + kpiCard("AKTİF", String.valueOf(counts.getOrDefault("active", 0)), "#16a34a", "#f0fdf4")
            + kpiCard("PASİF", String.valueOf(counts.getOrDefault("passive", 0)), "#64748b", "#f8fafc")
            + kpiCard("TOPLAM", String.valueOf(counts.getOrDefault("total", 0)), "#0f172a", "#f1f5f9")
            + "</tr></table>";

        StringBuilder body = new StringBuilder();
        body.append("<tr>")
            .append(thCell("Domain", "left")).append(thCell("Takım", "left")).append(thCell("Tier", "left"))
            .append(thCell("Kalan Gün", "left")).append(thCell("Bitiş", "left")).append(thCell("Durum", "left"))
            .append("</tr>");
        for (InventoryReportRow r : rows) {
            String color = daysColor(r.daysRemaining());
            body.append("<tr>")
                .append("<td style='padding:9px 13px;font-size:13px;font-weight:600;color:#0f172a'>").append(escHtml(r.domain())).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:#475569'>").append(escHtml(nzText(r.teamName()))).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:#475569;white-space:nowrap'>")
                .append(r.tier() == null ? "—" : "T" + r.tier()).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;font-weight:700;color:").append(color).append(";white-space:nowrap'>")
                .append(r.daysRemaining() == null ? "veri yok" : r.daysRemaining() + " gün").append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:#475569;white-space:nowrap'>")
                .append(escHtml(nzText(r.notAfter()))).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:#475569'>").append(escHtml(nzText(r.status()))).append("</td>")
                .append("</tr>");
        }
        String table =
            "<div style='margin:0 0 18px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";color:#fff;border-radius:8px 8px 0 0;"
            + "padding:9px 14px;font-size:13px;font-weight:800'>Sertifika Kalan Süreleri</td></tr></table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;"
            + "border-top:none;border-radius:0 0 8px 8px;border-collapse:collapse'>" + body + "</table></div>";

        StringBuilder hy = new StringBuilder();
        if (findings == null || findings.isEmpty()) {
            hy.append("<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>")
              .append("<td bgcolor='#f0fdf4' style='background:#f0fdf4;border:1px solid #bbf7d0;border-radius:8px;")
              .append("padding:12px 14px;font-size:13px;color:#15803d;font-weight:700'>")
              .append("✓ Envanterde eksik, hatalı veya güncel olmayan kayıt bulunmadı.</td></tr></table>");
        } else {
            for (InventoryFindingGroup g : findings) {
                StringBuilder lines = new StringBuilder();
                for (String[] s : g.samples()) {
                    lines.append("<tr><td style='padding:6px 13px;font-size:13px;font-weight:600;color:#0f172a;")
                         .append("border-top:1px solid #f1f5f9;white-space:nowrap'>").append(escHtml(s[0])).append("</td>")
                         .append("<td style='padding:6px 13px;font-size:13px;color:#b45309;border-top:1px solid #f1f5f9'>")
                         .append(escHtml(s[1])).append("</td></tr>");
                }
                if (g.hidden() > 0) {
                    lines.append("<tr><td colspan='2' style='padding:6px 13px;font-size:12px;color:#94a3b8;")
                         .append("border-top:1px solid #f1f5f9'>+").append(g.hidden())
                         .append(" kayıt daha — tamamı ekteki dosyalarda</td></tr>");
                }
                hy.append(reportSection(g.title() + " (" + g.total() + ")",
                        "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse'>"
                        + lines + "</table>", "#b45309"));
            }
        }

        // Her dosya adı AYRI escape edilir; ayırıcı işaretleme escape'in DIŞINDA kalmalı —
        // eskiden tüm birleşik metin escape edilince kullanıcı ham "</strong>, <strong>" görüyordu.
        String attachNote = (attachmentNames == null || attachmentNames.isEmpty()) ? ""
            : "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
              + "<td bgcolor='#f8fafc' style='background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;"
              + "padding:12px 14px;font-size:13px;color:#334155'>"
              + "📎 Envanterin tamamı ektedir: "
              + attachmentNames.stream().map(n -> "<strong>" + escHtml(n) + "</strong>")
                    .collect(java.util.stream.Collectors.joining(", "))
              + "</td></tr></table>";

        // Sertifika Envanteri ekranının sekmesi "domains" (App.jsx) — "inventory" diye bir sekme yok.
        String cta = ctaButton(appSettings.getString("site.monitor.app.base-url", appBaseUrl) + "/?tab=domains",
                "Sertifika Envanterini Görüntüle", accent);

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + LIGHT_SCHEME_META
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            + "<!--[if mso]><style>table,td,div,p{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->"
            + "<style>@media only screen and (max-width:870px){"
            + ".em-pad{padding:16px 0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";-webkit-text-size-adjust:100%;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td align='center' class='em-pad' bgcolor='" + outerBg + "' style='padding:24px 10px'>"
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.10)'>"
            + "<tr><td bgcolor='#ffffff' style='padding:0'>"
            + brandBar()
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#aebed8;font-size:11px;font-weight:700;letter-spacing:.12em'>AYLIK SERTİFİKA ENVANTERİ</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>Envanter Raporu</div>"
            + "<div style='color:#dbe3ef;font-size:15px;font-weight:700;margin-top:8px'>" + escHtml(monthLabel) + "</div>"
            + "</td></tr></table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın Sertifika Ekibi,</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 14px'>"
            + "Aşağıda sertifika envanterinin <strong>" + escHtml(monthLabel) + "</strong> dönemi özeti yer almaktadır. "
            + "Eksik, hatalı veya güncel olmayan kayıtlar varsa lütfen envanterden güncelleyiniz.</p>"
            + kpi
            + table
            + hy
            + attachNote
            + "<div style='margin:18px 0 4px'>" + cta + "</div>"
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Site Monitor — Otomatik Aylık Envanter Raporu</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Oluşturuldu: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table></body></html>";
    }

    /** Kalan güne göre renk — EmailTemplateBuilder.urgencyColor ile aynı skala. */
    private static String daysColor(Integer days) {
        if (days == null) return "#94a3b8";
        if (days < 0) return "#7B241C";
        if (days <= 7) return "#C0392B";
        if (days <= 15) return "#CA6F1E";
        if (days <= 30) return "#D68910";
        return "#1E8449";
    }

    private static String nzText(String s) { return s == null || s.isBlank() ? "—" : s; }

    private String kpiCard(String label, String value, String valueColor, String bg) {
        return "<td width='25%' bgcolor='" + bg + "' style='background:" + bg + ";border:1px solid #e2e8f0;"
            + "border-radius:10px;padding:12px 14px' valign='top'>"
            + "<div style='font-size:22px;font-weight:900;color:" + valueColor + ";line-height:1.1'>" + escHtml(value) + "</div>"
            + "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#94a3b8;margin-top:5px'>" + escHtml(label) + "</div>"
            + "</td>";
    }

    private String thCell(String label, String align) {
        return "<td bgcolor='#1e293b' align='" + align + "' style='background:#1e293b;padding:9px 13px;"
            + "font-size:11px;font-weight:700;letter-spacing:.06em;color:#cbd5e1;white-space:nowrap'>" + escHtml(label) + "</td>";
    }

    /** Availability %'sine göre renk: ≥99.9 yeşil, ≥99 amber, <99 kırmızı, null gri. */
    private static String pctColor(Double pct) {
        if (pct == null) return "#94a3b8";
        if (pct >= 99.9) return "#16a34a";
        if (pct >= 99.0) return "#d97706";
        return "#dc2626";
    }
    private static String pctText(Double pct) {
        if (pct == null) return "veri yok";
        return String.format(java.util.Locale.US, "%.2f%%", pct);
    }

    private String reportSection(String title, String bodyHtml, String accent) {
        return "<div style='margin-bottom:20px'>"
            // Başlık şeridi: div shading yerine td + bgcolor (Outlook uyumu)
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";color:#ffffff;"
            + "border-radius:8px 8px 0 0;padding:9px 14px;font-size:13px;font-weight:800;"
            + "letter-spacing:.02em'>" + title + "</td></tr></table>"
            // Gövde kabı TABLO+TD: Word <div> padding'i yok sayar, <td> padding'ini onurlandırır
            // → iç boşluk (başlık ile içerik arası dahil) Outlook'ta da render olur.
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse'><tr>"
            + "<td class='wr-md' style='border:1px solid #e2e8f0;border-top:none;border-radius:0 0 8px 8px;"
            + "padding:14px'>"
            + (bodyHtml == null || bodyHtml.isBlank()
                ? "<p style='color:#94a3b8;font-size:13px;margin:0'>—</p>" : bodyHtml)
            + "</td></tr></table></div>";
    }

    /**
     * Olay & Hata bildirimi — executive Outlook/Mac-safe şablon (table + bgcolor attr,
     * inline stil; div shading'e güvenmez). Önem'e göre renklenen başlık, künye tablosu,
     * RCA / iş etkisi / çözüm bölümleri ve olayı açma CTA'sı. {@code inc} = controller dto
     * (snake_case alanlar). Mevcut buildWeeklyReportHtml deseniyle birebir uyumlu.
     */
    /** 4-arg: mail gönderimi için (forEmail=true → görseller CID inline). */
    public String buildIncidentNotificationHtml(Map<String, Object> inc, String managerName,
                                                String kind, String ctaUrl) {
        return buildIncidentNotificationHtml(inc, managerName, kind, ctaUrl, true);
    }

    /** forEmail=false: UI önizlemesi (iframe) — markdown görselleri /api/incidents/images/{id} URL'siyle
     *  kalır (CID'e çevrilmez), iframe oturum çerezi ile yükler. */
    public String buildIncidentNotificationHtml(Map<String, Object> inc, String managerName,
                                                String kind, String ctaUrl, boolean forEmail) {
        boolean resolved = "RESOLVED".equals(kind);
        boolean isNew = "NEW".equals(kind);
        String sev = str(inc.get("severity"));
        // Çözüldüde önem rengi yerine YEŞİL (iyi haber); aksi halde önem rengi.
        String accent = resolved ? "#15803d" : switch (sev == null ? "" : sev) {
            case "CRITICAL" -> "#b91c1c";
            case "HIGH"     -> "#c2410c";
            case "MEDIUM"   -> "#b45309";
            case "LOW"      -> "#15803d";
            default          -> "#1f3864";
        };
        String outerBg = "#f4f6f8";
        String title = str(inc.get("title"));
        String teamName = str(inc.get("team_name"));
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String eyebrow = resolved ? "Site Monitor — Olay Çözüldü ✓"
                       : isNew    ? "Site Monitor — Yeni Olay Bildirimi"
                                  : "Site Monitor — Olay Güncellendi";

        StringBuilder facts = new StringBuilder()
            .append(kvRow("Önem", sevBadgeText(sev)))
            .append(kvRow("Durum", statusText(str(inc.get("status"))), statusColor(str(inc.get("status")))))
            .append(kvRow("Takım", teamName))
            .append(kvRow("Kanal", str(inc.get("channel"))))
            .append(kvRow("Servis / Domain", str(inc.get("service"))))
            .append(kvRow("Kategori", str(inc.get("category"))))
            .append(kvRow("Oluş Zamanı", fmtOrDash(formatIstanbul(str(inc.get("occurred_at"))))))
            .append(kvRow("Tespit Zamanı", fmtOrDash(formatIstanbul(str(inc.get("detected_at"))))))
            .append(kvRow("Çözülme Zamanı", fmtOrDash(formatIstanbul(str(inc.get("resolved_at"))))));
        if (inc.get("duration_minutes") != null) facts.append(kvRow("Süre", inc.get("duration_minutes") + " dk"));
        if (Boolean.TRUE.equals(inc.get("sla_breached"))) facts.append(kvRow("SLA", "İHLAL EDİLDİ"));
        if (inc.get("error_budget_burn_pct") != null) facts.append(kvRow("Error Budget Tüketimi", inc.get("error_budget_burn_pct") + "%"));

        String factsTable = "<table width='100%' cellpadding='0' cellspacing='0' border='0' "
            + "style='border-collapse:collapse'>" + facts + "</table>";

        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:2px 0 18px'>"
              + "<tr><td align='center'>" + ctaButton(ctaUrl, "Olay kaydını açmak için tıklayınız &rarr;", accent)
              + "</td></tr></table>"
            : "";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<style>body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + ".inc-md p{margin:0;font-size:14px;line-height:1.7;color:#1e293b}"
            + "@media only screen and (max-width:870px){.em-wrap{padding:0!important}"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + outerBg
            + "' style='background:" + outerBg + ";padding:24px 10px'><tr><td align='center' bgcolor='" + outerBg + "'>"
            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar (availability deseni)
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' "
            + "style='max-width:850px;width:100%;background:#ffffff;border:1px solid #d7dde5;border-radius:14px;"
            + "overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.10)'><tr><td bgcolor='#ffffff' style='padding:0'>"
            + brandBar()   // BRAND.md §5.1 lockup
            // ── Başlık (önem rengi) ──
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#ffffff;opacity:.78;font-size:11px;font-weight:700;letter-spacing:.12em'>"
            + escHtml(eyebrow) + "</div>"
            + "<div style='color:#ffffff;font-size:21px;font-weight:900;margin-top:10px;line-height:1.3'>"
            + escHtml(title) + "</div>"
            + "<div style='color:#ffffff;opacity:.92;font-size:14px;font-weight:700;margin-top:8px'>"
            + escHtml(sevBadgeText(sev)) + " &middot; " + escHtml(teamName) + "</div>"
            + "</td></tr></table>"
            // ── Gövde ── (div padding'i Outlook yok sayar → td-tabanlı em-body)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın "
            + escHtml(managerName != null && !managerName.isBlank() ? managerName : "Yetkili") + ",</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 18px'>"
            + (resolved ? "Ekibinize ait bir olay/hata kaydı <strong>çözüldü</strong>. Çözüm özeti aşağıdadır."
                     : isNew ? "Ekibinize ait yeni bir olay/hata kaydı oluşturuldu. Yönetici özeti aşağıdadır."
                     : "Ekibinize ait bir olay/hata kaydı güncellendi. Güncel yönetici özeti aşağıdadır.")
            + "</p>"
            + (resolved ? resolvedBanner(inc) : "")
            + cta
            + reportSection("Olay Künyesi", factsTable, accent)
            + reportSection("Kök Neden (RCA)", textBlock(str(inc.get("rca_summary")), forEmail), accent)
            + incidentSectionOpt("Teknik Açıklama", str(inc.get("description")), accent, forEmail)
            + reportSection("İş Etkisi", textBlock(str(inc.get("business_impact")), forEmail), accent)
            + reportSection("Çözüm / Müdahale Adımları", textBlock(str(inc.get("resolution_steps")), forEmail), accent)
            + cta
            // ── Footer ──
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "Site Monitor — Olay & Hata Bildirimi</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;"
            + "color:#94a3b8'>Oluşturuldu: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table></td></tr></table>"
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table></body></html>";
    }

    /** Çözüldü banner'ı — yeşil başarı kutusu + çözülme zamanı/süre. */
    private String resolvedBanner(Map<String, Object> inc) {
        String resolvedAt = fmtOrDash(formatIstanbul(str(inc.get("resolved_at"))));
        String dur = inc.get("duration_minutes") != null ? inc.get("duration_minutes") + " dk" : "—";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px'>"
            + "<tr><td bgcolor='#ecfdf5' style='background:#ecfdf5;border:1px solid #a7f3d0;border-radius:10px;padding:14px 16px'>"
            + "<div style='font-size:15px;font-weight:800;color:#065f46'>&#10003; Bu olay çözüldü</div>"
            + "<div style='font-size:13px;color:#047857;margin-top:6px'>Çözülme: <strong>" + escHtml(resolvedAt)
            + "</strong> &middot; Süre: <strong>" + escHtml(dur) + "</strong></div>"
            + "</td></tr></table>";
    }

    /** Künye satırı (label/value) — Outlook-safe td+bgcolor. */
    private String kvRow(String label, String value) {
        String v = (value == null || value.isBlank()) ? "—" : escHtml(value);
        return "<tr>"
            + "<td bgcolor='#f8fafc' style='background:#f8fafc;border:1px solid #e2e8f0;padding:8px 12px;"
            + "font-size:13px;font-weight:700;color:#475569;width:38%;vertical-align:top'>" + escHtml(label) + "</td>"
            + "<td style='border:1px solid #e2e8f0;padding:8px 12px;font-size:13px;color:#0f172a;"
            + "vertical-align:top;word-break:break-word'>" + v + "</td></tr>";
    }

    /** Künye satırı — renkli + kalın değer (örn. Durum: Çözüldü=yeşil). */
    private String kvRow(String label, String value, String valueColor) {
        String v = (value == null || value.isBlank()) ? "—" : escHtml(value);
        return "<tr>"
            + "<td bgcolor='#f8fafc' style='background:#f8fafc;border:1px solid #e2e8f0;padding:8px 12px;"
            + "font-size:13px;font-weight:700;color:#475569;width:38%;vertical-align:top'>" + escHtml(label) + "</td>"
            + "<td style='border:1px solid #e2e8f0;padding:8px 12px;font-size:13px;color:" + valueColor + ";"
            + "font-weight:700;vertical-align:top;word-break:break-word'>" + v + "</td></tr>";
    }

    /** Markdown metin → e-posta-güvenli paragraf: görsel sözdizimini at, escape + satır sonu→&lt;br&gt;. */
    private static final Pattern INC_CID_IMG = Pattern.compile("<img src=\"cid:incimg(\\d+)\"");
    private static final Pattern INC_API_IMG = Pattern.compile("<img src=\"(/api/incidents/images/\\d+)\"");

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
                    "<img width=\"680\" border=\"0\" alt=\"Olay görseli\" style=\"display:block;width:100%;max-width:680px;height:auto;"
                    + "border-radius:8px;margin:8px 0;border:1px solid #e2e8f0\" src=\"cid:incimg$1\"")
                : INC_API_IMG.matcher(html).replaceAll(
                    "<img style=\"display:block;max-width:100%;height:auto;border-radius:8px;margin:8px 0;"
                    + "border:1px solid #e2e8f0\" src=\"$1\"");
        return inlineBlockStyles(html);
    }

    /** Boş değilse bölüm kutusu üretir (boş markdown alanında boş kutu render etmemek için). */
    private String incidentSectionOpt(String title, String md, String accent, boolean forEmail) {
        String body = textBlock(md, forEmail);
        return body.isBlank() ? "" : reportSection(title, body, accent);
    }

    private static String fmtOrDash(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    private static String sevBadgeText(String sev) {
        if (sev == null) return "—";
        return switch (sev) {
            case "CRITICAL" -> "KRİTİK"; case "HIGH" -> "YÜKSEK";
            case "MEDIUM"   -> "ORTA";   case "LOW"  -> "DÜŞÜK";
            default -> sev;
        };
    }

    private static String statusText(String st) {
        if (st == null) return "—";
        return switch (st) {
            case "OPEN" -> "Açık"; case "INVESTIGATING" -> "İnceleniyor";
            case "MITIGATED" -> "Hafifletildi"; case "RESOLVED" -> "Çözüldü";
            default -> st;
        };
    }

    /** Durum rengi (künye "Durum" hücresi) — severity renk deseninin eşi. */
    private static String statusColor(String st) {
        if (st == null) return "#6b7280";
        return switch (st) {
            case "RESOLVED"      -> "#15803d";  // yeşil
            case "OPEN"          -> "#dc2626";  // kırmızı
            case "INVESTIGATING" -> "#ea580c";  // turuncu
            case "MITIGATED"     -> "#f59e0b";  // amber
            default              -> "#6b7280";  // gri
        };
    }

    /** Sayı rozeti — tek tablo HÜCRESİ. E-posta-güvenli: inline-block/margin/
     *  border-radius/8-haneli-hex YOK (Outlook/Apple Mail bunları bozar). Düz
     *  açık zemin (bgcolor attribute) + tam renk kenarlık. numChipRow ile sarılır. */
    private String numChip(String label, Object value, String color) {
        String v = value != null ? String.valueOf(value) : "0";
        String bg = tint(color, 0.12);
        return "<td bgcolor='" + bg + "' style='background:" + bg + ";border:1px solid " + color
            + ";padding:8px 13px;font-size:14px;font-weight:700;color:" + color
            + ";white-space:nowrap;text-align:center'>" + escHtml(label) + ": " + escHtml(v) + "</td>";
    }

    /** Rozet hücrelerini tek satırlık tabloya sarar. Yerleşim INLINE: Outlook (Word)
     *  &lt;head&gt;&lt;style&gt; sınıf kurallarını (.wr-md table) yok sayar, önizleme
     *  (iframe/tarayıcı) onurlandırır. Aynı görünüm için width:100% + border-collapse
     *  + eşit kolon genişlikleri satır-içinde verilir. */
    private String numChipRow(String... cells) {
        int n = cells.length;
        String w = (n > 0 ? Math.round(100.0 / n) : 100) + "%";
        StringBuilder tds = new StringBuilder();
        for (String c : cells) {
            // Her hücreye eşit yüzde: Word otomatik dağıtım yerine eşit kolon kullansın
            tds.append(c.replaceFirst("<td ", "<td width='" + w + "' "));
        }
        // Üst boşluk artık bölüm gövdesi <td> padding'inden gelir (Outlook+önizleme aynı);
        // chip tablosunun üst marjı 0 — yoksa önizlemede çift boşluk olurdu (Word marjı yok sayar).
        return "<table role='presentation' border='0' cellspacing='0' cellpadding='0' width='100%'"
            + " style='width:100%;border-collapse:collapse;margin:0 0 10px'><tr>"
            + tds + "</tr></table>";
    }

    /** Haftalık rapor e-postası hero KPI özeti — canlı cert/alarm/uptime (WeeklyReportKpiService.current).
     *  Anahtarlar: total_certs, expiring, alarms, critical, uptime_pct. null/boş → hiç gösterilmez. */
    private String weeklyKpiBlock(Map<String, Object> k) {
        if (k == null || k.isEmpty()) return "";
        Object up = k.get("uptime_pct");
        String uptime = up instanceof Number n ? String.format(java.util.Locale.US, "%.2f%%", n.doubleValue()) : "—";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px'>"
            + "<tr><td style='font-size:11px;font-weight:700;letter-spacing:.08em;color:#64748b;"
            + "padding-bottom:8px;text-transform:uppercase'>Haftalık Özet</td></tr>"
            + "<tr><td>"
            + numChipRow(
                numChip("Toplam Sertifika", k.get("total_certs"), "#334155"),
                numChip("Bu Hafta Dolan",   k.get("expiring"),    "#d97706"),
                numChip("Açılan Alarm",     k.get("alarms"),      "#dc2626"),
                numChip("Kritik ≤7",   k.get("critical"),    "#ea580c"),
                numChip("Uptime",           uptime,               "#16a34a"))
            + "</td></tr></table>";
    }

    /** Özet + KPI şeridini "Haftalık Özet ve Göstergeler" başlıklı rapor bölümüne sarar (ekrandaki akordeonla tutarlı).
     *  İçerik yoksa (eski/veri-yok) boş bölüm ÇİZMEZ. */
    private String weeklyOverviewSection(Map<String, Object> kpiSummary, String accent) {
        String body = weeklySummaryBlock(kpiSummary) + weeklyKpiBlock(kpiSummary)
                + weeklyMonitoringBlock(kpiSummary) + weeklyDomainProtectionBlock(kpiSummary);
        return body.isBlank() ? "" : reportSection("Haftalık Özet ve Göstergeler", body, accent);
    }

    /**
     * Alan adı koruması — kara listede / transfer kilidi olmayan domain sayısı. Outlook-safe
     * (satır içi hex, rgba yok, tablo düzeni).
     *
     * <p>"Doğrulanamadı" AYRI yazılır: kilidi doğrulayamamak ile kilidin olmaması aynı şey
     * değildir ve tek rakama katmak yönetime yanlış bir tablo gösterirdi. Sorun yoksa bölüm
     * yine çizilir — "her şey yolunda" da bir bilgidir.
     */
    @SuppressWarnings("unchecked")
    private String weeklyDomainProtectionBlock(Map<String, Object> k) {
        Object p = k == null ? null : k.get("domain_protection");
        if (!(p instanceof Map<?, ?> prot)) return "";
        int total = prot.get("total") instanceof Number n ? n.intValue() : 0;
        if (total == 0) return "";
        int listed = prot.get("listed") instanceof Number n ? n.intValue() : 0;
        int unlocked = prot.get("unlocked") instanceof Number n ? n.intValue() : 0;
        int unverified = prot.get("lock_unverified") instanceof Number n ? n.intValue() : 0;

        String head = "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#64748b;"
                + "text-transform:uppercase;margin:12px 0 4px'>Alan Adı Koruması</div>";
        StringBuilder rows = new StringBuilder();
        rows.append(protRow("İzlenen alan adı", total, "#334155"));
        rows.append(protRow("Kara listede", listed, listed > 0 ? "#dc2626" : "#16a34a"));
        rows.append(protRow("Transfer kilidi yok", unlocked, unlocked > 0 ? "#dc2626" : "#16a34a"));
        if (unverified > 0) rows.append(protRow("Kilit doğrulanamadı", unverified, "#64748b"));
        return head + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' "
                + "style='margin:0 0 12px;border-collapse:collapse'>" + rows + "</table>";
    }

    private String protRow(String label, int value, String color) {
        return "<tr>"
             + "<td style='padding:5px 8px;font-size:13px;color:#334155;border-bottom:1px solid #eef1f4'>"
             + escHtml(label) + "</td>"
             + "<td align='right' style='padding:5px 8px;font-size:13px;font-weight:700;color:" + color
             + ";border-bottom:1px solid #eef1f4'>" + value + "</td>"
             + "</tr>";
    }

    /** İzleme göstergeleri — tür başına tek satır (tür · izleme · erişim% · sorun), Outlook-safe.
     *  Anahtar: monitoring (List&lt;Map&gt;: type/active/success_pct/alarms). İzlemesi 0 olan tür satırı gizlenir; hiç yoksa boş. */
    @SuppressWarnings("unchecked")
    private String weeklyMonitoringBlock(Map<String, Object> k) {
        Object mon = k == null ? null : k.get("monitoring");
        if (!(mon instanceof List<?> list) || list.isEmpty()) return "";
        // Etiketler KANONİK katalogdan. Buradaki yerel kopyada "scripted" ve "page" yoktu; o türler
        // e-postada ham anahtarıyla ("scripted") yazılıyordu — bkz. MonitorTypeCatalog.
        Map<String, String> labels = MonitorTypeCatalog.LABELS_TR;
        StringBuilder rows = new StringBuilder();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            int active = row.get("active") instanceof Number n ? n.intValue() : 0;
            if (active == 0) continue;
            String label = labels.getOrDefault(String.valueOf(row.get("type")), String.valueOf(row.get("type")));
            Object rate = row.get("success_pct");
            String rateStr = rate instanceof Number rn ? String.format(java.util.Locale.US, "%.1f%%", rn.doubleValue()) : "—";
            int alarms = row.get("alarms") instanceof Number an ? an.intValue() : 0;
            String alarmColor = alarms > 0 ? "#dc2626" : "#64748b";
            rows.append("<tr>")
              .append("<td style='padding:5px 8px;font-size:13px;color:#334155;border-bottom:1px solid #eef1f4'>").append(escHtml(label)).append("</td>")
              .append("<td align='right' style='padding:5px 8px;font-size:13px;color:#334155;border-bottom:1px solid #eef1f4'>").append(active).append("</td>")
              .append("<td align='right' style='padding:5px 8px;font-size:13px;font-weight:700;color:#334155;border-bottom:1px solid #eef1f4'>").append(rateStr).append("</td>")
              .append("<td align='right' style='padding:5px 8px;font-size:13px;font-weight:700;color:").append(alarmColor).append(";border-bottom:1px solid #eef1f4'>").append(alarms).append("</td>")
              .append("</tr>");
        }
        if (rows.length() == 0) return "";
        String head = "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#64748b;text-transform:uppercase;margin:12px 0 4px'>İzleme Göstergeleri</div>";
        String th = "<tr>"
              + "<td style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>Tür</td>"
              + "<td align='right' style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>İzleme</td>"
              + "<td align='right' style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>Erişim</td>"
              + "<td align='right' style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>Sorun</td>"
              + "</tr>";
        return head + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border-collapse:collapse'>"
                + th + rows + "</table>";
    }

    /** Executive özet bloğu (e-posta) — sağlık skoru + yönetici paragrafı + iki kompakt tablo (aksiyon, 30 gün).
     *  Anahtarlar: score/score_band/manager_text/actions/lookahead. Skor yoksa (eski/veri-yok) → boş. Outlook-safe. */
    @SuppressWarnings("unchecked")
    private String weeklySummaryBlock(Map<String, Object> k) {
        if (k == null || k.get("score") == null) return "";
        int score = ((Number) k.get("score")).intValue();
        String band = String.valueOf(k.getOrDefault("score_band", "red"));
        String bandColor = "green".equals(band) ? "#16a34a" : "amber".equals(band) ? "#d97706" : "#dc2626";
        String para = escHtml(String.valueOf(k.getOrDefault("manager_text", "")));
        String head =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 14px'><tr>"
            + "<td valign='top' width='120' style='padding:0 16px 0 0;white-space:nowrap'>"
            + "<div style='font-size:34px;font-weight:800;color:" + bandColor + ";line-height:1'>" + score
            + "<span style='font-size:15px;color:#94a3b8'>/100</span></div>"
            + "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#94a3b8;text-transform:uppercase;margin-top:2px'>Haftalık Sağlık Skoru</div>"
            + "</td>"
            + "<td valign='top' style='font-size:14px;line-height:1.7;color:#334155'>" + para + "</td>"
            + "</tr></table>";
        // Önümüzdeki 30 Gün: kayıt yoksa bölüm hiç eklenmez (kullanıcı isteği) — Aksiyon tablosu ise boşken "Kayıt yok" gösterir.
        List<Map<String, Object>> lookahead = (List<Map<String, Object>>) k.get("lookahead");
        return head
            + summaryActionTable("Aksiyon Gerektirenler", (List<Map<String, Object>>) k.get("actions"))
            + (lookahead == null || lookahead.isEmpty() ? "" : summaryActionTable("Önümüzdeki 30 Gün", lookahead));
    }

    /** Özet aksiyon/14-gün tablosu — td/bgcolor tier şeridi, sağa hizalı gün (Outlook-safe). Boş → "Kayıt yok". */
    private String summaryActionTable(String title, List<Map<String, Object>> items) {
        String hdr = "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#64748b;"
                + "text-transform:uppercase;margin:8px 0 4px'>" + escHtml(title) + "</div>";
        if (items == null || items.isEmpty()) {
            return hdr + "<div style='font-size:13px;color:#94a3b8;margin-bottom:10px'>Kayıt yok</div>";
        }
        StringBuilder sb = new StringBuilder(hdr);
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border-collapse:collapse'>");
        for (Map<String, Object> a : items) {
            String stripe = tierColor(a.get("tier"));
            String type = "domain".equals(a.get("type")) ? "Domain" : "Sertifika";
            Object days = a.get("days_left");
            sb.append("<tr>")
              .append("<td width='4' bgcolor='").append(stripe).append("' style='background-color:").append(stripe)
              .append(";width:4px;font-size:0;line-height:0'>&nbsp;</td>")
              .append("<td style='padding:6px 10px;font-size:13px;color:#334155;border-bottom:1px solid #eef1f4'>")
              .append(escHtml(String.valueOf(a.get("name")))).append(" <span style='color:#94a3b8;font-size:11px'>").append(type).append("</span></td>")
              .append("<td align='right' style='padding:6px 10px;font-size:13px;font-weight:700;color:#334155;border-bottom:1px solid #eef1f4;white-space:nowrap'>")
              .append(days != null ? days + " gün" : "—").append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    private static String tierColor(Object tier) {
        int t = tier instanceof Number n ? n.intValue() : 0;
        return switch (t) { case 1 -> "#dc2626"; case 2 -> "#d97706"; case 3 -> "#2563eb"; default -> "#94a3b8"; };
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

    private String metaLine(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<p style='font-size:13px;color:#334155;margin:4px 0'><strong>" + label + ":</strong> "
            + escHtml(value) + "</p>";
    }

    /** Takip linki satırı — URL açık yazılmaz; tıklanabilir metin etikettir
     *  (kullanıcı isteği: mailde çıplak URL paylaşılmasın). */
    private String linkLine(String label, String url) {
        if (url == null || url.isBlank()) return "";
        return "<p style='font-size:13px;margin:4px 0'>🔗 <a href='" + escHtml(url)
            + "' style='color:#1f3864;font-weight:700'>" + escHtml(label) + "</a></p>";
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

    // ── Recovery-mail zenginleştirme yardımcıları (iç tablo + iki bağlam sarmalayıcısı) ──

    /** "Neden bu e-postayı aldınız?" iç tablosu (alıcı şeffaflığı; Outlook-güvenli). */
    private String whyReceivingInner(String teamNames) {
        String phrase = (teamNames != null && !teamNames.isBlank())
                ? "<strong>" + escHtml(teamNames) + "</strong> ekibine" : "ilgili izleme grubuna";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f8fafc' style='background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td style='padding:12px 16px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#64748b;margin-bottom:5px'>Neden bu e-postayı aldınız?</div>"
            + "<div style='font-size:13px;line-height:1.55;color:#1f2937'>Bu bildirim " + phrase
            + " tanımlı bir izleme için gönderildi. Site Monitor otomatik bir izleme sistemidir; bildirim tercihleri için sistem yöneticinize başvurun.</div>"
            + "</td></tr></table>";
    }

    /** Erişilebilirlik özeti iç tablosu (son 24s/7g uptime% + kesinti sayısı). Veri yoksa boş. */
    private String uptimeSummaryInner(UptimeSummary u) {
        if (u == null || u.pct24h() == null) return "";
        String p24 = String.format(java.util.Locale.US, "%.2f", u.pct24h());
        String p7 = u.pct7d() != null ? String.format(java.util.Locale.US, "%.2f", u.pct7d()) : "—";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden;mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td colspan='2' bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>ERİŞİLEBİLİRLİK ÖZETİ</td></tr>"
            + tableRow2col("📈 Son 24 saat", p24 + "% uptime · " + u.outages24h() + " kesinti")
            + tableRow2col("🗓 Son 7 gün", p7 + "% uptime · " + u.outages7d() + " kesinti")
            + "</table>";
    }

    /** Row-bağlam (em-card <tr> dizisi) sarmalayıcıları — buildRichMonitoringResolvedHtml için. */
    private String uptimeSummaryRow(UptimeSummary u) {
        String inner = uptimeSummaryInner(u);
        return inner.isEmpty() ? "" : "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:0 24px 14px'>" + inner + "</td></tr>";
    }
    private String whyReceivingRow(String teamNames) {
        return "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:0 24px 18px'>" + whyReceivingInner(teamNames) + "</td></tr>";
    }
    /** Block-bağlam (tek gövde <td>'si içinde ardışık tablolar) — monitoringTypedResolved için (alt boşluklu). */
    private String uptimeSummaryBlock(UptimeSummary u) {
        String inner = uptimeSummaryInner(u);
        return inner.isEmpty() ? "" : "<div style='margin:0 0 14px'>" + inner + "</div>";
    }
    private String whyReceivingBlock(String teamNames) {
        return "<div style='margin:0 0 14px'>" + whyReceivingInner(teamNames) + "</div>";
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
