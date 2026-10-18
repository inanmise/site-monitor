package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.BrandMailAssets;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.TechErrorMailImages;
import com.sitemonitor.service.mail.TechErrorReportMail;
import com.sitemonitor.service.report.TechErrorReportPdfWriter;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Teknik hata raporunu PDF eki olarak TAKIMIN e-posta adresine gönderir (2026-10-13, kullanıcı isteği: "raporun pdf
 * olarak raporu oluşturan ekibin mailine iletilmesini sağlamalıyız").
 *
 * <p>Akış: alıcı denetimi (takım adresi yoksa 409 {@code TEAM_EMAIL_MISSING}, hiçbir şey yazılmaz) → TALEP (koşullu
 * UPDATE, {@code SENDING}; aynı rapor başka sekmede / podda gönderiliyorsa 409 {@code SEND_IN_PROGRESS}) → PDF → posta →
 * SONUÇ (SENT / QUEUED / SKIPPED / FAILED). Talepten sonra ne olursa olsun satır son durumuna yazılır (try/finally);
 * pod gönderim ortasında ölürse {@code SENDING} 10 dakika sonra elle yeniden gönderilebilir. Her karar bir
 * {@code notification_logs} satırı bırakır (tetikleyici {@code TECH_ERROR_REPORT}). Zamanlanmış iş yok; sistem bakımı
 * sessize alması uygulanmaz (elle, kullanıcı isteğiyle gönderilen rapor — diğer raporlarla aynı).
 */
@Slf4j
@Service
public class TechErrorReportSender {

    public static final String TRIGGER = "TECH_ERROR_REPORT";
    static final Duration STALE_CLAIM = Duration.ofMinutes(10);
    /**
     * Kuyruktaki (421) bir gönderimin sonucunun hâlâ gelebileceği süre: posta servisi 90 / 180 / 360 sn arayla yeniden dener
     * (≈ 10,5 dk) ve yeniden deneme yalnız gönderen podun belleğindedir — pod yeniden başlarsa sonuç HİÇ gelmez. Bu süreden
     * eski kuyruk, üzerine yapılan başarısız denemede korunmaz (rapor sonsuza dek "kuyrukta" kalmasın — inceleme 2026-10-15).
     */
    static final java.time.Duration QUEUED_RESULT_WINDOW = java.time.Duration.ofMinutes(20);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern DELIVERABLE = Pattern.compile("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]{2,}");

    private final TechErrorReportService service;
    private final TechErrorReportRepository reports;
    private final EmailNotificationService email;
    private final NotificationLogRepository logs;
    private final AppSettingsService appSettings;

    /** Pasif kullanıcı adres süzgeci (yoksa — dilim testleri — kayıt tüm adresleri listeler). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.service.InactiveRecipientGuard inactiveGuard;

    /**
     * E-postaya eklenecek PDF'in üst sınırı (kurumsal posta sınırı; aşarsa PDF indirilip elle gönderilir). ÖNYÜKLEME değeri:
     * Ayarlar → Teknik hata raporu'ndan kaydedilen değer (DB override) yeniden başlatmadan onu ezer ({@link #liveMaxMailMb},
     * 12. sürüm, E21). Testler alanı doğrudan yazar.
     */
    @Value("${site.monitor.tech-error-report.max-mail-mb:15}")
    int maxMailMb = 15;
    /**
     * Gövdeye gömülen görsellerin toplam bütçesi (MB) — 2026-10-17, S5. PDF eki + gömülü görseller BİRLİKTE
     * posta sınırını aşamaz: aşarsa görseller gövdeye girmez (yuvalar "PDF ekinde" kutusu alır), posta yine gider.
     * Önyükleme değeri; canlı değer {@link #liveInlineMb}.
     */
    @Value("${site.monitor.tech-error-report.mail-inline-mb:6}")
    int mailInlineMb = 6;

    /** Etkin posta sınırı (MB): ekrandan kaydedilen değer, yoksa önyükleme değeri — her gönderimde okunur. */
    public int liveMaxMailMb() {
        return TechErrorReportSettings.liveInt(appSettings, TechErrorReportSettings.MAX_MAIL_MB, maxMailMb);
    }

    /** Etkin gövde görsel bütçesi (MB). */
    public int liveInlineMb() {
        return TechErrorReportSettings.liveInt(appSettings, TechErrorReportSettings.MAIL_INLINE_MB, mailInlineMb);
    }

    /** E-posta eki üst sınırı (bayt) — onay isteği postası da aynı sınırı uygular. */
    public long maxMailBytes() {
        return Math.max(1, liveMaxMailMb()) * 1024L * 1024L;
    }

    /** Görsel hazırlayıcı (test dikişi: hazırlama hatası postayı durdurmamalı). */
    java.util.function.BiFunction<TechErrorPdfInput, Long, TechErrorMailImages.Result> mailImagePreparer = TechErrorMailImages::prepare;

    public TechErrorReportSender(TechErrorReportService service, TechErrorReportRepository reports,
                                 EmailNotificationService email, NotificationLogRepository logs, AppSettingsService appSettings) {
        this.service = service;
        this.reports = reports;
        this.email = email;
        this.logs = logs;
        this.appSettings = appSettings;
    }

    /**
     * Gönderim sonucu: {@code state} SENT | QUEUED | SKIPPED | FAILED; {@code to} gerçek alıcılar; {@code cc} postanın Bilgi
     * listesi (takım adresi + bu gönderime eklenen ek Bilgi — R18 / C13; yoksa boş).
     */
    public record Result(String state, String status, List<String> to, int pdfBytes, String subject, List<String> cc) {
        public Result(String state, String status, List<String> to, int pdfBytes, String subject) {
            this(state, status, to, pdfBytes, subject, List.of());
        }
    }

    /** Bir gönderime eklenebilecek ek Bilgi (CC) adresi sayısı (R18 / C13). */
    public static final int MAX_EXTRA_CC = 10;
    /** Ek Bilgi girişinin üst uzunluğu (karakter) — ayrıştırmadan ÖNCE denetlenir (sınırsız girdi taranmaz). */
    public static final int MAX_EXTRA_CC_INPUT = 4000;
    /** Ek Bilgi girişinin ayraçları: virgül, noktalı virgül, boşluk / satır sonu (doğrusal tarama). */
    private static final Pattern EXTRA_CC_SPLIT = Pattern.compile("[,;\\s]+");

    /**
     * Bu gönderime EK Bilgi (CC) adresleri (R18 / C13, 2026-10-18 — kullanıcı onayı): {@code raw} virgülle ayrılmış adresler.
     * Kurallar: her öğe teslim edilebilir biçimde ({@link #isDeliverable}) — değilse 400 {@code extra_cc} adresi adıyla söyler;
     * birbirinin tekrarı (büyük / küçük harf) tek sayılır; en çok {@value #MAX_EXTRA_CC} farklı adres; Kime ya da mevcut
     * Bilgi'de zaten olan adres eklenmez (iki kez gitmez); mevcut Bilgi + ek ≤ {@value #MAX_RECIPIENTS} — aşarsa 400 (asla
     * sessiz düşmez). Boş giriş = ek yok. Kanalda SAKLANMAZ; yalnız bu gönderimin postasına, günlük satırının Bilgi'sine ve
     * geçmiş olayına ({@code extra_cc}) girer. Pasif kullanıcı adresleri posta hunisinde düşer.
     */
    public static List<String> extraCc(String raw, List<String> to, List<String> cc) {
        if (raw == null || raw.isBlank()) return List.of();
        if (raw.length() > MAX_EXTRA_CC_INPUT) {
            throw new FieldValidationException("extra_cc", Msg.t(
                    "Ek Bilgi (CC) alanı çok uzun (en çok " + MAX_EXTRA_CC_INPUT + " karakter). En çok " + MAX_EXTRA_CC
                            + " adresi virgülle ayırarak yazın.",
                    "The extra CC field is too long (at most " + MAX_EXTRA_CC_INPUT + " characters). Enter at most " + MAX_EXTRA_CC
                            + " addresses separated by commas."));
        }
        List<String> entered = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String part : EXTRA_CC_SPLIT.split(raw)) {
            String e = part.trim();
            if (e.isEmpty()) continue;
            if (!isDeliverable(e)) {
                String shown = e.length() > 80 ? e.substring(0, 80) + "…" : e;
                throw new FieldValidationException("extra_cc", Msg.t(
                        "«" + shown + "» geçerli bir e-posta adresi değil. Ek Bilgi (CC) adreslerini virgülle ayırın, ör. ad.soyad@kurum.com.tr.",
                        "“" + shown + "” isn't a valid e-mail address. Separate the extra CC addresses with commas, e.g. name.surname@company.com."));
            }
            if (seen.add(e.toLowerCase(Locale.ROOT))) entered.add(e);
        }
        if (entered.size() > MAX_EXTRA_CC) {
            throw new FieldValidationException("extra_cc", Msg.t(
                    "Bir gönderime en çok " + MAX_EXTRA_CC + " ek Bilgi (CC) adresi eklenebilir; " + entered.size()
                            + " adres yazdınız. Fazlasını çıkarın.",
                    "At most " + MAX_EXTRA_CC + " extra CC addresses can be added to one send; you entered " + entered.size()
                            + ". Remove the extra ones."));
        }
        Set<String> existing = new java.util.HashSet<>();
        for (String e : to == null ? List.<String>of() : to) existing.add(e.toLowerCase(Locale.ROOT));
        int base = 0;
        for (String e : cc == null ? List.<String>of() : cc) if (existing.add(e.toLowerCase(Locale.ROOT))) base++;
        List<String> add = new ArrayList<>();
        for (String e : entered) if (existing.add(e.toLowerCase(Locale.ROOT))) add.add(e);
        if (base + add.size() > MAX_RECIPIENTS) {
            throw new FieldValidationException("extra_cc", Msg.t(
                    "Bilgi (CC) listesi en çok " + MAX_RECIPIENTS + " adres olabilir: takımın Bilgi listesinde " + base + " adres var, "
                            + add.size() + " ek adresle " + (base + add.size()) + " oluyor. Ek adresleri " + Math.max(0, MAX_RECIPIENTS - base)
                            + " adrese indirin.",
                    "The CC list can have at most " + MAX_RECIPIENTS + " addresses: the team's CC list has " + base + ", and with "
                            + add.size() + " extra addresses it would be " + (base + add.size()) + ". Reduce the extra addresses to "
                            + Math.max(0, MAX_RECIPIENTS - base) + "."));
        }
        return add;
    }

    /**
     * Kime gider (2026-10-17, S6): TEAM = takımın adresi (eski akış, byte-identical); EXECUTIVE = kanalın yönetici
     * alıcıları, takım adresi BİLGİDE (cc) — PO onayından sonra ya da onaylı raporun yeniden gönderiminde.
     */
    public enum Delivery { TEAM, EXECUTIVE }

    /** Bir rapor postasının en çok kaç adrese gideceği (takım / yönetici listesi); fazlası {@link Addresses#dropped()}. */
    public static final int MAX_RECIPIENTS = 20;

    /** Adres listesi (virgül / noktalı virgül ayraçlı): biçimsizler atılır, tekrarlar tekilleşir, en çok 20. */
    public static List<String> parseAddresses(String raw) {
        return parseAddressesDetailed(raw).addresses();
    }

    /**
     * Çözülen adresler + sınırın DIŞINDA kalan teslim edilebilir adres sayısı (A3, inceleme 2026-10-17: takım adresindeki
     * 21. ve sonraki alıcılar sessizce düşüyordu — önizleme uyarır, gönderim WARN + geçmişe yazar). Admin → Takımlar'daki
     * adres doğrulaması değişmez (aynı alanı başka postalar da kullanır); {@link #parseAddresses} byte-identical.
     */
    public record Addresses(List<String> addresses, int dropped) { }

    public static Addresses parseAddressesDetailed(String raw) {
        List<String> all = allAddresses(raw);
        if (all.size() <= MAX_RECIPIENTS) return new Addresses(all, 0);
        return new Addresses(new ArrayList<>(all.subList(0, MAX_RECIPIENTS)), all.size() - MAX_RECIPIENTS);
    }

    /** Teslim edilebilir, tekilleşmiş adreslerin TAMAMI (sınırsız — sınır çağıranda; liste takım / kanal alanından, sınırlı). */
    private static List<String> allAddresses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split("[,;]")) {
            String e = part.trim();
            if (e.isEmpty() || e.length() > 254 || !DELIVERABLE.matcher(e).matches()) continue;
            if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        return out;
    }

    /**
     * Yöneticilere gönderimin BİLGİ (CC) listesi (inceleme 2026-10-17): takım adresi eksi Kime'deki yönetici adresleri (aynı
     * kutu iki kez gitmez), en çok {@link #MAX_RECIPIENTS}; {@link Addresses#dropped()} = sınırın gerçekten düşürdüğü takım
     * adresi sayısı (Kime'de zaten olan adres düşmüş sayılmaz). Önceden fazlalık sessizce düşüyordu — önizleme uyarır
     * ({@code cc_dropped}), gönderim WARN + geçmiş olayına {@code dropped_cc} yazar.
     */
    public static Addresses ccAddresses(Team team, List<String> to) {
        Set<String> skip = new java.util.HashSet<>();
        for (String e : to) skip.add(e.toLowerCase(Locale.ROOT));
        List<String> cc = new ArrayList<>();
        int dropped = 0;
        for (String e : allAddresses(team == null ? null : team.getEmail())) {
            if (!skip.add(e.toLowerCase(Locale.ROOT))) continue;
            if (cc.size() < MAX_RECIPIENTS) cc.add(e);
            else dropped++;
        }
        return new Addresses(cc, dropped);
    }

    /** Tek adres teslim edilebilir biçimde mi (boşluk / ayraç yok, ≤ 254)? */
    public static boolean isDeliverable(String e) {
        if (e == null) return false;
        String t = e.trim();
        return !t.isEmpty() && t.length() <= 254 && DELIVERABLE.matcher(t).matches();
    }

    /** Takım adresinden gönderilebilir adresler. */
    public static List<String> recipients(Team team) {
        return parseAddresses(team == null ? null : team.getEmail());
    }

    /** Kanalın yönetici (executive) alıcıları (S6). */
    public static List<String> executiveRecipients(TechErrorChannel channel) {
        return parseAddresses(channel == null ? null : channel.getExecutiveEmails());
    }

    public Result send(TechErrorReport report, TechErrorChannel channel, Team team, Long by) {
        return send(report, channel, team, by, Delivery.TEAM);
    }

    /**
     * E-posta ÖNİZLEMESİ (10. sürüm, 2026-10-17 — kullanıcı isteği: "mail göndermeden önce overview edebilmeliyiz").
     * Gönderimle AYNI gövde (aynı girdi, aynı görsel hazırlama, aynı dosya adı), ama talep yok, posta yok, günlük yok,
     * içerik sayacı değişmez. Alıcılar gönderimdeki gibi hesaplanır; boşsa fırlatılmaz, {@code problem} kodu döner
     * (TEAM_EMAIL_MISSING / EXEC_RECIPIENTS_MISSING) — gövde yine görülebilir. Gömülü görseller tarayıcıda görünsün diye
     * {@code cid:} başvuruları yalnız önizlemede data: URI'ye çevrilir. Görseller PDF izni altında yüklenir ({@code withPdfPermit}).
     */
    /**
     * {@code problem}: alıcı sorunu önde (gönderim de önce onu söyler), yoksa PDF_TOO_LARGE. {@code pdfTooLarge} AYRI bayrak
     * (inceleme 2026-10-17): alıcı sorunu da varken {@code problem} PDF'i söylemez — test postası düğmesi bu bayrağa bakar.
     * {@code toDropped} / {@code ccDropped}: Kime / Bilgi kaynağında sınırın (20) dışında kalan, postayı ALMAYAN adres sayısı.
     */
    public record Preview(String subject, List<String> to, List<String> cc, String role, String html, String text, String fileName,
                          int embeddedImages, int skippedImages, String problem, TechErrorPdfInput.Provenance provenance,
                          long pdfBytes, int maxMailMb, int toDropped, int ccDropped, boolean pdfTooLarge) {}

    public Preview preview(TechErrorReport report, TechErrorChannel channel, Team team, Delivery delivery) {
        Rendering r = render(report, channel, team, delivery, null);
        // Alıcı sorunu önde (gönderim de önce onu söyler), yoksa PDF sınırı
        String problem = r.recipientProblem() != null ? r.recipientProblem() : r.pdfTooLarge() ? "PDF_TOO_LARGE" : null;
        TechErrorReportMail.Built built = r.mail();
        return new Preview(TechErrorReportMail.subject(r.in()), r.to(), r.cc(), r.role(), inlineBrandLogo(inlineCids(built.mail().html(), built.inline())),
                built.mail().text(), r.fileName(), built.embeddedImages(), built.skippedImages(), problem, r.in().provenance(), r.pdf().length, r.maxMailMb(),
                r.toDropped(), r.ccDropped(), r.pdfTooLarge());
    }

    /**
     * Önizleme ve test gönderiminin ortak üretimi: alıcılar teslim yoluna göre, PDF (gönderimle aynı merdiven ve izin —
     * 429 {@link TechErrorReportService.PdfBusyException}), görseller izin içinde, posta sınırı PDF + gömülü görsellerin
     * TOPLAMI (sığmazsa görseller yalnız PDF'te), gövde {@link TechErrorReportMail#buildFull} ile ({@code intro} = banddan
     * sonraki ek blok, null = yok). Talep / günlük / içerik sayacı yok — çağıran karar verir.
     */
    record Rendering(TechErrorPdfInput in, byte[] pdf, TechErrorReportMail.Built mail, String fileName, List<String> to, List<String> cc,
                     String role, String recipientProblem, boolean pdfTooLarge, int toDropped, int ccDropped,
                     int maxMailMb) { }

    private Rendering render(TechErrorReport report, TechErrorChannel channel, Team team, Delivery delivery, TechErrorReportMail.Intro intro) {
        List<String> to;
        List<String> cc = List.of();
        String role;
        String problem = null;
        int ccDropped = 0;
        // A3: Kime listesinin kaynağında sınırın (20) dışında kalan teslim edilebilir adres sayısı — pencere uyarır
        Addresses toSource = delivery == Delivery.EXECUTIVE
                ? parseAddressesDetailed(channel == null ? null : channel.getExecutiveEmails())
                : parseAddressesDetailed(team == null ? null : team.getEmail());
        if (delivery == Delivery.EXECUTIVE) {
            to = executiveRecipients(channel);
            if (to.isEmpty()) problem = "EXEC_RECIPIENTS_MISSING";
            // Bilgi = takım adresi eksi yöneticiler, en çok 20; fazlası sayılır (inceleme 2026-10-17 — sessiz düşüyordu)
            Addresses ccSource = ccAddresses(team, to);
            cc = List.copyOf(ccSource.addresses());
            ccDropped = ccSource.dropped();
            role = "EXECUTIVE";
        } else {
            to = recipients(team);
            if (to.isEmpty()) problem = "TEAM_EMAIL_MISSING";
            role = "TEAM";
        }
        // Sınır BİR kez okunur (inceleme 2026-10-18): PDF merdiveni, görsel toplamı ve PDF_TOO_LARGE metni aynı değeri kullanır
        int maxMb = liveMaxMailMb();
        long max = Math.max(1, maxMb) * 1024L * 1024L;
        long inlineBudget = Math.max(0, liveInlineMb()) * 1024L * 1024L;
        // Gönderimle AYNI yol (inceleme 2026-10-17): PDF de üretilir (aynı merdiven, aynı izin — 429 PdfBusyException), görseller
        // izin içinde hazırlanır; posta sınırı PDF + gömülü görsellerin TOPLAMI: sığmazsa görseller yalnız PDF'te ("PDF ekinde"
        // kutuları), PDF tek başına sığmazsa pdfTooLarge (önizlemede gövde yine görülür; gönderim / test 409 ile durur)
        TechErrorMailImages.Result[] mailImages = { TechErrorMailImages.Result.empty() };
        TechErrorMailImages.Result[] mailFallback = { TechErrorMailImages.Result.empty() };
        TechErrorReportService.Rendered rendered = service.renderPdf(() -> {
            TechErrorPdfInput loaded = service.pdfInput(report, channel, team.getName());
            mailFallback[0] = TechErrorMailImages.allSkipped(loaded);
            try {
                mailImages[0] = mailImagePreparer.apply(loaded, inlineBudget);
            } catch (RuntimeException e) {
                log.warn("Teknik hata raporu görselleri önizleme için hazırlanamadı (rapor {}): {}", report.getId(), e.toString());
                mailImages[0] = mailFallback[0];
            }
            return loaded;
        }, max);
        TechErrorPdfInput in = rendered.meta();
        byte[] pdf = rendered.pdf();
        boolean pdfTooLarge = pdf.length > max;
        if (mailImages[0].bytes() > 0 && pdf.length + mailImages[0].bytes() > max) mailImages[0] = mailFallback[0];
        String fileName = TechErrorReportPdfWriter.fileName(channel.getName(), report.getReportYear(), report.getReportMonth(),
                in.template().lang());
        TechErrorReportMail.Built built = TechErrorReportMail.buildFull(in, mailImages[0], baseUrl(), fileName, intro, audienceOf(delivery));
        return new Rendering(in, pdf, built, fileName, List.copyOf(to), cc, role, problem, pdfTooLarge, toSource.dropped(), ccDropped, maxMb);
    }

    /** Test gönderiminin konu öneki (dilden bağımsız) ve günlük tetikleyicisi. */
    public static final String TEST_SUBJECT_PREFIX = "[TEST] ";
    public static final String TEST_TRIGGER = "TECH_ERROR_REPORT_TEST";

    /**
     * Önizlenen postayı YALNIZ çağıranın kendisine gönderir (R13 / B5, 2026-10-17 — kullanıcı isteği: "bana test gönder").
     * Gövde gönderimle aynı ({@link #render}: aynı girdi, aynı görseller, aynı dosya adı, aynı dipnot kitlesi), konu
     * {@value #TEST_SUBJECT_PREFIX} önekli, banddan hemen sonra "bu bir TEST gönderimidir" kutusu ({@code lang} = okuyanın
     * arayüz dili; gövdenin kalanı şablon dilinde). Talep, içerik sayacı, geçmiş olayı, {@code last_sent_*} ve onay denetimi
     * YOK — rapor "gönderildi" olmaz. Her deneme için BİR {@code notification_logs} satırı (tetikleyici {@value #TEST_TRIGGER},
     * rol {@code SELF}, alıcı = kullanıcının adresi): posta servisinin cevabı (SENT / FAILED / SKIPPED / QUEUED_RETRY… —
     * kuyruk yalnız kaydedilir, sonuçlandırma yok), PDF sırası dolu → SKIPPED, PDF sınırı aşıyor → FAILED + 409
     * {@code PDF_TOO_LARGE} (gönderimle aynı metin), beklenmeyen hata → FAILED sınıf adıyla.
     */
    public Result sendTest(TechErrorReport report, TechErrorChannel channel, Team team, Delivery delivery, String toEmail, String lang) {
        if (!isDeliverable(toEmail)) throw noEmail();
        String to = toEmail.trim();
        String subject = null;
        String status = "FAILED: gönderim tamamlanamadı";
        try {
            Rendering r;
            try {
                r = render(report, channel, team, delivery, testIntro(lang));
            } catch (TechErrorReportService.PdfBusyException busy) {
                status = "SKIPPED: PDF sırası dolu, gönderilmedi";
                throw busy;
            }
            subject = TEST_SUBJECT_PREFIX + TechErrorReportMail.subject(r.in());
            if (r.pdfTooLarge()) {
                status = "FAILED: PDF e-posta sınırını aşıyor (" + mb(r.pdf().length) + " MB > " + r.maxMailMb() + " MB)";
                throw pdfTooLarge(r.pdf().length, r.maxMailMb());
            }
            MailDoc.Mail mail = r.mail().mail();
            String answer = email.sendHtml(new String[]{ to }, null, subject, mail.html(), mail.text(), r.mail().inline(), false,
                    List.of(new EmailNotificationService.MailAttachment(r.fileName(), r.pdf(), "application/pdf")));
            status = answer == null ? "FAILED: sonuç yok" : answer;
            return new Result(classify(status), status, List.of(to), r.pdf().length, subject);
        } catch (RuntimeException e) {
            if (!(e instanceof CodedConflictException) && !(e instanceof TechErrorReportService.PdfBusyException)) {
                log.warn("Teknik hata raporu test postası gönderilemedi (rapor {}): {}", report.getId(), e.toString());
                if (status.startsWith("FAILED: gönderim tamamlanamadı")) status = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
            }
            throw e;
        } finally {
            saveLog(TEST_TRIGGER, team, List.of(to), null, "SELF", subject != null ? subject : TEST_SUBJECT_PREFIX + channel.getName(), status,
                    report);
        }
    }

    /** Oturum kullanıcısının hesabında teslim edilebilir adres yok — test postası gidemez (409 {@code NO_EMAIL}). */
    public static CodedConflictException noEmail() {
        return new CodedConflictException("NO_EMAIL", Msg.t(
                "Hesabınızda e-posta adresi yok; test postası gönderilemez. Yöneticinizden adres eklemesini isteyin.",
                "Your account has no e-mail address, so the test mail can't be sent. Ask your administrator to add one."));
    }

    /** Banddan sonraki test kutusu: okuyanın dilinde (postanın kalanı şablon dilinde kalır). */
    static TechErrorReportMail.Intro testIntro(String lang) {
        boolean en = "en".equalsIgnoreCase(lang);
        return d -> d.alert(com.sitemonitor.service.mail.MailTokens.Tone.WARNING,
                en ? "Test send" : "Test gönderimi",
                en ? "This is a TEST send — it went only to you; it was not sent to the team / executives."
                        : "Bu bir TEST gönderimidir — yalnız size gitti; takıma / yöneticilere gönderilmedi.");
    }

    /** PDF tek başına posta sınırını aşıyor (gönderim ve test gönderimi aynı metinle). */
    private CodedConflictException pdfTooLarge(long pdfBytes, int maxMailMb) {
        return new CodedConflictException("PDF_TOO_LARGE", Msg.t(
                "PDF " + mb(pdfBytes) + " MB; e-posta sınırı " + maxMailMb + " MB — görseller en düşük baskı çözünürlüğünde "
                        + "bile sığmıyor. Bazı görselleri daha az alan kaplayan ekran görüntüleriyle değiştirin ya da PDF'i "
                        + "indirip elle gönderin.",
                "The PDF is " + mb(pdfBytes) + " MB; the e-mail limit is " + maxMailMb + " MB — the images don't fit even at "
                        + "the lowest print resolution. Replace some images with screenshots of a smaller area, or download "
                        + "the PDF and send it yourself."),
                java.util.Map.of("max_mb", maxMailMb));
    }

    /** Dipnotun "neden aldınız" cümlesi teslim yoluna göre (inceleme 2026-10-17: yöneticiler "takım adresi olduğunuz için" okuyordu). */
    static TechErrorReportMail.Audience audienceOf(Delivery delivery) {
        return delivery == Delivery.EXECUTIVE ? TechErrorReportMail.Audience.EXECUTIVE : TechErrorReportMail.Audience.TEAM;
    }

    /**
     * Başlıktaki marka logosu gönderimde CID eki olarak eklenir ({@code EmailNotificationService} → {@code BrandMailAssets.addInline},
     * varyant {@code ok}); önizlemede (duman testi 2026-10-17: iframe'de kırık görsel) aynı baytlar data: URI olur.
     */
    static String inlineBrandLogo(String html) {
        if (html == null || !html.contains("cid:" + BrandMailAssets.CID)) return html;
        byte[] bytes = BrandMailAssets.logoBytes("ok");
        if (bytes == null) return html;
        return html.replace("cid:" + BrandMailAssets.CID + "\"", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes) + "\"");
    }

    /** Önizleme için {@code cid:} görsel başvuruları data: URI olur (posta istemcisi değil tarayıcı çizer). */
    static String inlineCids(String html, List<EmailNotificationService.InlineImage> inline) {
        if (html == null || inline == null || inline.isEmpty()) return html;
        String out = html;
        for (EmailNotificationService.InlineImage img : inline) {
            if (img == null || img.cid() == null || img.data() == null) continue;
            String uri = "data:" + (img.contentType() == null ? "image/png" : img.contentType()) + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(img.data());
            // Kapanış tırnağıyla değiştir (inceleme 2026-10-17): "te-img-tech-ratio" "te-img-tech-ratio-monthly"nin ÖNEKİ —
            // ayraçsız değişim ikinci görselin src'ini bozuyordu
            out = out.replace("cid:" + img.cid() + "\"", uri + "\"");
        }
        return out;
    }

    public Result send(TechErrorReport report, TechErrorChannel channel, Team team, Long by, Delivery delivery) {
        return send(report, channel, team, by, delivery, null);
    }

    /**
     * {@code extraCcRaw}: bu gönderime EK Bilgi (CC) adresleri (R18 / C13; null / boş = yok — eski akış byte-identical).
     * Doğrulama ({@link #extraCc}) TALEPTEN ÖNCE: geçersiz giriş 400 {@code extra_cc}, hiçbir şey yazılmaz. Takıma gönderimde
     * Bilgi = ek adresler; yöneticilere gönderimde takım adresinin ardına eklenir. Onay sonrası otomatik gönderim
     * ({@code TechErrorApprovalService.decide}) ve kendine test postası ek Bilgi kullanmaz.
     */
    public Result send(TechErrorReport report, TechErrorChannel channel, Team team, Long by, Delivery delivery, String extraCcRaw) {
        return send(report, channel, team, by, delivery, extraCcRaw, Set.of());
    }

    /**
     * Push bildirimleri (2026-10-18, {@link TechErrorPushService}): gönderim SONUCU yazıldıktan sonra — SENT → takım bildirimi
     * (gönderen ve {@code alsoNotified} hariç: bu olay için zaten doğrudan push alanlar, ör. onay kararını alan isteyen);
     * FAILED → gönderene; kuyruktaki eski gönderimin yerine geçilip sonucu hiç okunmayacaksa ve bu deneme de ulaşmadıysa eski
     * gönderene "sonucu alınamadı". QUEUED push'u kuyruk sonuçlanınca ({@code TechErrorReportService.reconcileQueued}).
     * Push hatası gönderim sonucunu DEĞİŞTİRMEZ.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    TechErrorPushService pushes;

    /** Test dikişi. */
    void setPushes(TechErrorPushService p) {
        this.pushes = p;
    }

    /** {@code alsoNotified}: bu gönderim olayı için zaten doğrudan push alan kullanıcı kimlikleri (takım bildiriminden düşülür). */
    public Result send(TechErrorReport report, TechErrorChannel channel, Team team, Long by, Delivery delivery, String extraCcRaw,
                       java.util.Collection<Long> alsoNotified) {
        List<String> to;
        String[] cc = null;
        String role;
        // A3: Kime kaynağında sınırın (20) dışında kalan adresler sessiz düşmez — WARN + geçmiş olayına `dropped_recipients`
        final int droppedRecipients = (delivery == Delivery.EXECUTIVE
                ? parseAddressesDetailed(channel == null ? null : channel.getExecutiveEmails())
                : parseAddressesDetailed(team == null ? null : team.getEmail())).dropped();
        if (droppedRecipients > 0) {
            log.warn("Teknik hata raporu #{} ({}): alıcı kaynağında {} adres sınırın (en çok {}) dışında kaldı, bu adreslere posta GİTMEZ",
                    report.getId(), delivery, droppedRecipients, MAX_RECIPIENTS);
        }
        int ccDroppedCount = 0;
        if (delivery == Delivery.EXECUTIVE) {
            to = executiveRecipients(channel);
            if (to.isEmpty()) {
                throw new CodedConflictException("EXEC_RECIPIENTS_MISSING", Msg.t(
                        "Bu kanalın yönetici (executive) alıcısı tanımlı değil; rapor gönderilmedi. Takım yöneticisi kanal ayarlarından alıcı eklesin.",
                        "This channel has no executive recipients, so the report wasn't sent. A team manager can add them in the channel settings."));
            }
            // Takım adresi bilgide; yönetici listesinde zaten olan adres iki kez gitmez; Bilgi de en çok 20 — fazlası sessiz
            // düşmez (inceleme 2026-10-17): WARN + geçmiş olayına `dropped_cc`
            Addresses ccSource = ccAddresses(team, to);
            cc = ccSource.addresses().isEmpty() ? null : ccSource.addresses().toArray(new String[0]);
            ccDroppedCount = ccSource.dropped();
            if (ccDroppedCount > 0) {
                log.warn("Teknik hata raporu #{} (EXECUTIVE): takım adresinde (Bilgi) {} adres sınırın (en çok {}) dışında kaldı, bu adreslere posta GİTMEZ",
                        report.getId(), ccDroppedCount, MAX_RECIPIENTS);
            }
            role = "EXECUTIVE";
        } else {
            to = recipients(team);
            role = "TEAM";
        }
        if (to.isEmpty()) {
            throw new CodedConflictException("TEAM_EMAIL_MISSING", Msg.t(
                    "Takımın kayıtlı bir e-posta adresi yok. Yöneticiniz Admin → Takımlar'da adres ekledikten sonra tekrar gönderin; "
                            + "o zamana kadar PDF'i indirip elle iletebilirsiniz.",
                    "The team has no e-mail address on record. Send again after an administrator adds one under Admin → Teams; "
                            + "until then you can download the PDF and forward it yourself."));
        }
        // R18 / C13: bu gönderime ek Bilgi — doğrulama talepten ÖNCE (geçersiz giriş hiçbir şey yazmaz); Kime / Bilgi'deki
        // adres tekrar eklenmez, toplam Bilgi ≤ 20 (aşarsa 400, sessiz düşmez)
        final List<String> requestedExtras = extraCc(extraCcRaw, to, cc == null ? List.of() : List.of(cc));
        // İnceleme (2026-10-18): ek Bilgi TEK BAŞINA bir raporu "gönderildi" yapmaz. Kime ve mevcut Bilgi'de etkin (pasif
        // olmayan) alıcı kalmadıysa ek adresler postaya EKLENMEZ — gönderim ek Bilgi hiç yazılmamış gibi sonuçlanır (takım
        // adresinin hepsi pasif → posta hunisi "SKIPPED: pasif kullanıcı" → 409 MAIL_DISABLED). Geçmiş olayı istenen ek
        // adresleri (gönderilmedi) taşır.
        final boolean extrasApplied = !requestedExtras.isEmpty() && baseReachable(to, cc);
        final List<String> extras = extrasApplied ? requestedExtras : List.of();
        if (!requestedExtras.isEmpty() && !extrasApplied) {
            log.warn("Teknik hata raporu #{} ({}): Kime / Bilgi'de etkin alıcı yok — {} ek Bilgi adresi eklenmedi (ek Bilgi tek başına gönderim yapmaz)",
                    report.getId(), delivery, requestedExtras.size());
        }
        if (!extras.isEmpty()) {
            List<String> merged = new ArrayList<>(cc == null ? List.of() : List.of(cc));
            merged.addAll(extras);
            cc = merged.toArray(new String[0]);
        }
        final int droppedCc = ccDroppedCount;
        Instant claimedAt = service.now();
        // Kuyruktaki bir gönderimin üzerine yeniden gönderim (inceleme 2026-10-14): talep kuyruktaki gönderimi bitirir, ilk
        // gönderimin sonucu artık hiç okunmaz — geçmişe "sonucu bilinmiyor" yazılır. Karar talepten SONRA verilir (finally):
        // talep bırakılırsa (PDF sırası dolu) kuyruktaki gönderim yaşar; talepten önce sonuçlandıysa (başka sekme) yazılmaz.
        // Önce kuyruğu sonuçlandır (düzeltme incelemesi 2026-10-15): posta servisi teslim ettiyse rapor hâlâ QUEUED görünebilir
        // (gönderim yolu okuma ucu çağırmaz) — o zaman sonuç kendi SEND_RESULT'uyla yazılır, "bilinmiyor" denmez
        if (report.getChannelId() != null) service.reconcileQueued(List.of(report.getChannelId()));
        // Konusu silinmiş (sahipsiz) konu görselleri gönderimden önce süpürülür (inceleme 2026-10-18 — yarışta ya da eski bir
        // pod'un kaydında kalmış olabilir; PDF / posta onları zaten basmaz). Süpürme gönderimi asla durdurmaz
        try {
            int swept = service.sweepOrphanIssueImages(report.getId());
            if (swept > 0) log.info("Teknik hata raporu #{}: gönderim öncesi {} sahipsiz konu görseli silindi", report.getId(), swept);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} sahipsiz konu görselleri süpürülemedi (gönderim sürer): {}", report.getId(), e.toString());
        }
        TechErrorReport before = reports.findById(report.getId()).orElse(null);
        String stateBefore = before == null ? null : before.getSendState();
        // Talep bırakılırsa önceki talep zamanı geri yüklenir: kuyruktaki gönderim sonuçlanınca "gönderim anı" odur
        Instant prevClaimedAt = before == null ? null : before.getSendClaimedAt();
        if (reports.claimSend(report.getId(), claimedAt, claimedAt.minus(STALE_CLAIM)) == 0) {
            if (!reports.existsById(report.getId())) throw TechErrorReportService.reportNotFound();
            throw new CodedConflictException("SEND_IN_PROGRESS", Msg.t(
                    "Bu rapor şu anda gönderiliyor (başka bir sekmede ya da başka biri tarafından). Bir dakika sonra durumu yenileyin.",
                    "This report is being sent right now (in another tab or by someone else). Refresh its status in a minute."));
        }
        String subject = null;
        String finalState = "FAILED";
        String finalStatus = "FAILED: gönderim tamamlanamadı";
        boolean released = false;
        int pdfBytes = 0;
        Long sentRev = null;
        List<String> active = to;
        List<String> activeTo = null;      // posta servisi döndükten sonra: Kime'nin pasif hesaplar düşülmüş hâli (önce: bilinmiyor)
        List<String> activeCc = null;      // posta servisi döndükten sonra: Bilgi'nin pasif hesaplar düşülmüş hâli (önce: bilinmiyor)
        Boolean queuedAtClaim = null;      // talepten sonraki okuma: kuyruktaki gönderim hâlâ sonuçlanmamış mıydı?
        boolean mailAttempted = false;     // posta servisine bir şey verildi mi?
        boolean mailReturned = false;      // posta servisi bir sonuç DÖNDÜ mü (istisna değil)?
        try {
            // Talepten SONRA taze oku (inceleme 2026-10-13): çağıranın elindeki satır talepten önce okunmuştu; arada
            // kaydedilen değerler PDF'e girmez ama "gönderildi" görünürdü.
            TechErrorReport fresh = service.report(report.getId());
            // Talep son durumu değiştirmez; QUEUED_RETRY hâlâ duruyorsa kuyruktaki gönderim talep anında SONUÇLANMAMIŞTI
            // (confirmQueued / failQueued son durumu yazar ve yalnız QUEUED iken çalışır)
            queuedAtClaim = fresh.getLastSendStatus() != null && fresh.getLastSendStatus().startsWith("QUEUED_RETRY");
            // PDF bu okumanın içeriği: "gönderimden sonra değişti" bu sayaçla karşılaştırılır
            sentRev = fresh.getContentRev() == null ? 0L : fresh.getContentRev();
            // Yöneticilere gönderim yalnız onaylı ve onaydan beri DEĞİŞMEMİŞ içerikle (inceleme 2026-10-17): onay ile
            // talep arasına giren otomatik kayıt PO'nun görmediği içeriği gönderemez — talep bırakılır, iz "atlandı"
            if (delivery == Delivery.EXECUTIVE && (!"APPROVED".equals(fresh.getApprovalState()) || fresh.getApprovalRev() == null
                    || fresh.getApprovalRev().longValue() != sentRev)) {
                finalState = "SKIPPED";
                finalStatus = "SKIPPED: onay eskidi, içerik onaydan sonra değişti — yeniden onay gerekir";
                released = true;
                throw new CodedConflictException("APPROVAL_STALE", Msg.t(
                        "Rapor onaydan sonra değişti; bu hâliyle yöneticilere gönderilemez. Raporu yeniden onaya gönderin.",
                        "The report changed after the approval, so this version can't be sent to the executives. Submit it for approval again."));
            }
            TechErrorReportService.Rendered rendered;
            // Görseller gövdede de (2026-10-17, S5): ≤ 1000 px kopyaları PDF İZNİNİN İÇİNDE, görseller bellekteyken
            // hazırlanır (ikinci okuma yok; baytlar izin dışında tutulmaz). Hazırlama hatası postayı durdurmaz: görseli
            // olan her yuva "PDF ekinde" kutusu alır
            // Sınırlar CANLI (E21): bu gönderim için bir kez okunur — PDF, görsel bütçesi ve hata iletisi aynı değeri kullanır
            // (inceleme 2026-10-18: PDF merdiveni de AYNI değeri alır — izin beklenirken değişen ayar ikisini ayırmasın)
            int maxMailMb = liveMaxMailMb();
            long max = Math.max(1, maxMailMb) * 1024L * 1024L;
            long inlineBudget = Math.max(0, liveInlineMb()) * 1024L * 1024L;
            TechErrorMailImages.Result[] mailImages = { TechErrorMailImages.Result.empty() };
            // "Hepsi PDF ekinde" yedeği de izin içinde alınır: renderPdf görselsiz girdi döndürür, sonradan türetilemez
            TechErrorMailImages.Result[] mailFallback = { TechErrorMailImages.Result.empty() };
            try {
                rendered = service.renderPdf(() -> {
                    TechErrorPdfInput loaded = service.pdfInput(fresh, channel, team.getName());
                    mailFallback[0] = TechErrorMailImages.allSkipped(loaded);
                    try {
                        mailImages[0] = mailImagePreparer.apply(loaded, inlineBudget);
                    } catch (RuntimeException e) {
                        log.warn("Teknik hata raporu görselleri posta için hazırlanamadı (rapor {}): {}", report.getId(), e.toString());
                        mailImages[0] = mailFallback[0];
                    }
                    return loaded;
                }, max);
            } catch (TechErrorReportService.PdfBusyException busy) {
                // Hiçbir şey denenmedi: talep bırakılır, FAILED yazılmaz; iz "atlandı" olarak kalır
                finalState = "SKIPPED";
                finalStatus = "SKIPPED: PDF sırası dolu, gönderilmedi";
                released = true;
                throw busy;
            }
            TechErrorPdfInput in = rendered.meta();
            byte[] pdf = rendered.pdf();
            subject = TechErrorReportMail.subject(in);
            pdfBytes = pdf.length;
            if (pdf.length > max) {
                finalStatus = "FAILED: PDF e-posta sınırını aşıyor (" + mb(pdf.length) + " MB > " + maxMailMb + " MB)";
                throw pdfTooLarge(pdf.length, maxMailMb);
            }
            String fileName = TechErrorReportPdfWriter.fileName(channel.getName(), fresh.getReportYear(), fresh.getReportMonth(),
                    in.template().lang());
            // Posta sınırı PDF + gömülü görsellerin TOPLAMI için (inceleme 2026-10-17): sınıra yakın bir PDF'in yanında
            // 6 MB görsel kurumsal ağ geçidinden dönerdi — görseller düşer, PDF gider, yuvalar "PDF ekinde" der
            if (mailImages[0].bytes() > 0 && pdf.length + mailImages[0].bytes() > max) {
                log.info("Teknik hata raporu {}: PDF {} MB + gömülü görseller {} MB posta sınırını ({} MB) aşıyor; görseller yalnız PDF'te",
                        report.getId(), mb(pdf.length), mb(mailImages[0].bytes()), maxMailMb);
                mailImages[0] = mailFallback[0];
            }
            TechErrorReportMail.Built built = TechErrorReportMail.buildFull(in, mailImages[0], baseUrl(), fileName, null, audienceOf(delivery));
            MailDoc.Mail mail = built.mail();
            mailAttempted = true;
            String status = email.sendHtml(to.toArray(new String[0]), cc, subject, mail.html(), mail.text(), built.inline(), false,
                    List.of(new EmailNotificationService.MailAttachment(fileName, pdf, "application/pdf")));
            mailReturned = true;
            finalStatus = status == null ? "FAILED: sonuç yok" : status;
            finalState = classify(finalStatus);
            // Posta servisi yalnız pasif kullanıcılara ait adresleri düşürür — "kime gitti" kaydı da onları saymaz (Kime VE
            // Bilgi; günlük satırı, yanıt ve geçmiş aynı listeyi yazar — inceleme 2026-10-18)
            active = activeRecipients(to);
            activeTo = active;
            activeCc = cc == null ? List.of() : activeRecipients(List.of(cc));
            return new Result(finalState, finalStatus, active, pdfBytes, subject, activeCc);
        } catch (RuntimeException e) {
            if (!released && !(e instanceof CodedConflictException)) {
                log.warn("Teknik hata raporu gönderilemedi (rapor {}): {}", report.getId(), e.toString());
                if (finalStatus.startsWith("FAILED: gönderim tamamlanamadı")) {
                    finalStatus = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
                }
            }
            if (!released) finalState = "FAILED";
            throw e;
        } finally {
            Instant at = service.now();
            // Günlük satırı ÖNCE: kuyruktaki gönderim bu satırla sonuçlandırılır (posta servisi SENT / FAILED yazar). Kime ve
            // Bilgi aynı kuralla: posta servisi döndüyse pasif hesaplarınkiler düşülür; hepsi pasifse (gönderim atlandı) istenen
            // liste "neden gitmedi"yi gösterir; servise ulaşmadıysa istenen liste
            Long logId = saveLog(TRIGGER, team, loggedTo(to, activeTo, activeCc), loggedCc(cc, activeCc, activeTo), role,
                    subject != null ? subject : channel.getName(), finalStatus, report);
            // Kuyruktaki bir gönderimin üzerine yapılan deneme HİÇBİR ŞEY teslim etmediyse ya da kuyruğa almadıysa (posta servisine
            // ulaşmadı: PDF çok büyük / beklenmeyen hata; ya da servis SKIPPED / FAILED döndü) kuyruktaki gönderim yaşamaya devam
            // eder: talep bırakılır (durum satırın kendi son sonucundan), "bitirildi" ve "sonucu bilinmiyor" yazılmaz — servisin
            // yeniden denemesi sonucu ESKİ günlük satırına yazar ve okuma uçları onu sonuçlandırır (düzeltme incelemesi 2026-10-15).
            // Servisin kendisi istisna attıysa teslim bilinmez → eski davranış (bitir + SUPERSEDED).
            boolean nothingDelivered = !mailAttempted
                    || (mailReturned && ("SKIPPED".equals(finalState) || "FAILED".equals(finalState)));
            // Kuyruktaydı mı: talepten SONRAKİ okuma belirleyicidir (yarım kalmış bir talebin devralınmasında ön okuma SENDING
            // der ama kuyruk sürüyor olabilir); okunamadıysa ön okuma
            boolean wasQueued = queuedAtClaim != null ? queuedAtClaim : "QUEUED".equals(stateBefore);
            boolean resultMayLand = prevClaimedAt != null && prevClaimedAt.isAfter(claimedAt.minus(QUEUED_RESULT_WINDOW));
            boolean keepQueued = !released && nothingDelivered && wasQueued && resultMayLand;
            int finished = 0;                // bu talebin sonucu yazıldı mı (push yalnız kazanan yazımdan sonra)
            try {
                int n;
                if (released || keepQueued) {
                    n = reports.releaseClaim(report.getId(), claimedAt, prevClaimedAt);
                } else if ("SENT".equals(finalState)) {
                    n = reports.finishSent(report.getId(), claimedAt, clip(finalStatus, 255), at, by,
                            clip(String.join(", ", active), 500), sentRev);
                } else if ("QUEUED".equals(finalState)) {
                    // Posta servisi yeniden deniyor, teslim BİLİNMİYOR — "gönderildi" yazılmaz; okuma uçları günlük
                    // satırının sonucuyla sonuçlandırır (reconcileQueued)
                    // Bu gönderim için zaten push alan kişi (onay kararının push'u isteyene "kuyrukta" dedi) kuyrukla saklanır:
                    // teslim edilince takımın "gönderildi" bildiriminden düşülür (2026-10-18, son inceleme — aynı teslim için
                    // iki push yok). Yoksa eski yazım (kolon temizlenir)
                    Long notified = firstId(alsoNotified);
                    n = notified == null
                            ? reports.finishQueued(report.getId(), claimedAt, clip(finalStatus, 255), at, logId, by,
                                    clip(String.join(", ", active), 500), sentRev)
                            : reports.finishQueuedNotified(report.getId(), claimedAt, clip(finalStatus, 255), at, logId, by,
                                    clip(String.join(", ", active), 500), sentRev, notified);
                } else {
                    // SKIPPED / FAILED: gönderilmedi
                    n = reports.finishNotSent(report.getId(), claimedAt, finalState, clip(finalStatus, 255), at);
                }
                if (n == 0) log.warn("Teknik hata raporu gönderim sonucu yazılamadı (rapor {}): talep başka bir gönderime geçmiş", report.getId());
                finished = n;
            } catch (RuntimeException ex) {
                log.warn("Teknik hata raporu gönderim sonucu yazılamadı (rapor {}): {}", report.getId(), ex.toString());
            }
            // Rapor geçmişine: talepten sonraki HER deneme (PDF sırası dolu / PDF çok büyük / beklenmeyen hata dahil —
            // inceleme 2026-10-14). recordSend kendi işleminde ve hatasını yutar: gönderim sonucu değişmez.
            TechErrorHistoryService h = service.history();
            if (h != null && !released && !keepQueued && wasQueued) {
                h.recordSend(report, "SEND_RESULT", null, "SUPERSEDED", null, null, claimedAt);
            }
            if (h != null) {
                String reason = "SENT".equals(finalState) || "QUEUED".equals(finalState) ? null
                        : TechErrorHistoryService.sendReason(finalStatus);
                List<String> recorded = "SENT".equals(finalState) || "QUEUED".equals(finalState) ? active : null;
                // A3: sınır dışında kalan alıcı sayısı geçmişte görünür (yalnız varsa — olay ayrıntısı aksi hâlde eskisiyle aynı);
                // Kime ve Bilgi ayrı anahtarlarla (`dropped_recipients` / `dropped_cc`). R18 / C13: bu gönderimin ek Bilgi
                // adresleri `extra_cc` (gönderildiyse pasif hesaplarınkiler hariç; gönderilmediyse istenen liste — eklenmemiş
                // olsa da: etkin alıcı yoktu). Gönderildi ama ek adresler eklenmediyse (süzgeç anlık görüntüsü arada değişti)
                // `extra_cc` yazılmaz — o adreslere posta gitmedi
                if (droppedRecipients > 0 || droppedCc > 0 || !requestedExtras.isEmpty()) {
                    java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
                    if (droppedRecipients > 0) extra.put("dropped_recipients", droppedRecipients);
                    if (droppedCc > 0) extra.put("dropped_cc", droppedCc);
                    if (!requestedExtras.isEmpty()) {
                        boolean delivered = "SENT".equals(finalState) || "QUEUED".equals(finalState);
                        List<String> extraRecorded = !delivered ? requestedExtras : extrasApplied ? activeRecipients(extras) : List.of();
                        if (!extraRecorded.isEmpty()) extra.put("extra_cc", List.copyOf(extraRecorded));
                    }
                    h.recordSend(report, "SEND", by, finalState, recorded, reason, at, extra);
                } else {
                    h.recordSend(report, "SEND", by, finalState, recorded, reason, at);
                }
            }
            // Push (2026-10-18): yalnız bu talebin sonucu GERÇEKTEN yazıldıysa (koşullu UPDATE kazandı — iki pod / iki sekme
            // aynı sonucu iki kez bildirmez); bırakılan / yaşatılan kuyruk talebinde push yok
            if (!released && !keepQueued && finished > 0) {
                Long oldSender = before == null ? null : before.getQueuedById();
                boolean superseded = wasQueued;
                pushOutcome(report, channel, delivery, by, finalState, finalStatus, sentRev, claimedAt, alsoNotified,
                        superseded ? oldSender : null, prevClaimedAt);
            }
        }
    }

    /**
     * Gönderim sonucunun push'u ({@link TechErrorPushService}); hiçbir hata yayılmaz. {@code supersededSender}: yerine geçilen
     * kuyruktaki gönderimin sahibi (sonucu artık okunmayacak) — bu deneme de ulaşmadıysa ve başka bir kişiyse "sonucu alınamadı".
     */
    private void pushOutcome(TechErrorReport report, TechErrorChannel channel, Delivery delivery, Long by, String finalState,
                             String finalStatus, Long sentRev, Instant claimedAt, java.util.Collection<Long> alsoNotified,
                             Long supersededSender, Instant supersededClaimedAt) {
        TechErrorPushService p = pushes;
        if (p == null) return;
        try {
            TechErrorPushService.Ctx c = TechErrorPushService.Ctx.of(report, channel);
            boolean exec = delivery == Delivery.EXECUTIVE;
            if ("SENT".equals(finalState)) {
                p.delivered(c, exec, by, service.displayName(by), sentRev, claimedAt, alsoNotified);
            } else if ("FAILED".equals(finalState)) {
                p.sendFailed(c, exec, by, TechErrorPushService.Failure.FAILED, TechErrorHistoryService.sendReason(finalStatus), claimedAt);
            }
            boolean reached = "SENT".equals(finalState) || "QUEUED".equals(finalState);
            if (!reached && supersededSender != null && !supersededSender.equals(by)) {
                p.sendFailed(c, exec, supersededSender, TechErrorPushService.Failure.UNKNOWN, null,
                        supersededClaimedAt == null ? claimedAt : supersededClaimedAt);
            }
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} gönderim push'u kuyruğa alınamadı (gönderim etkilenmedi): {}", report.getId(), e.toString());
        }
    }

    /**
     * Ek Bilgi olmadan da etkin bir alıcı var mı (inceleme 2026-10-18): Kime'de ya da mevcut Bilgi'de (yöneticilere gönderimde
     * takım adresi) pasif olmayan bir adres. Yoksa ek Bilgi postaya eklenmez — tek başına raporu "gönderildi" yapmasın.
     */
    private boolean baseReachable(List<String> to, String[] cc) {
        if (!activeRecipients(to).isEmpty()) return true;
        return cc != null && cc.length > 0 && !activeRecipients(List.of(cc)).isEmpty();
    }

    /**
     * Günlük satırının Bilgi listesi — Kime ile AYNI kural (inceleme 2026-10-18; Kime pasifleri düşürüp Bilgi düşürmüyordu):
     * posta servisine ulaşmadıysa ({@code activeCc == null}) istenen liste; ulaştıysa pasif hesaplarınkiler düşülmüş liste;
     * Bilgi'nin hepsi pasifse yalnız Kime de tümüyle pasifken (gönderim atlandı) istenen liste, aksi hâlde Bilgi yok.
     */
    static String[] loggedCc(String[] cc, List<String> activeCc, List<String> activeTo) {
        if (cc == null || cc.length == 0) return null;
        if (activeCc == null) return cc;
        if (!activeCc.isEmpty()) return activeCc.toArray(new String[0]);
        return activeTo == null || activeTo.isEmpty() ? cc : null;
    }

    /**
     * Günlük satırının Kime listesi — {@link #loggedCc} ile AYNI kural (inceleme 2026-10-18, 2. tur: yöneticilerin hepsi pasif,
     * takım Bilgi'si etkinken satır SENT ama Kime pasif adresleri listeliyordu): posta servisine ulaşmadıysa
     * ({@code activeTo == null}) istenen liste; ulaştıysa pasif hesaplarınkiler düşülmüş liste; Kime'nin hepsi pasifse yalnız
     * Bilgi de tümüyle pasifken (gönderim atlandı — "neden gitmedi") istenen liste, aksi hâlde Kime BOŞ (posta Bilgi'ye gitti).
     */
    static List<String> loggedTo(List<String> to, List<String> activeTo, List<String> activeCc) {
        if (to == null || to.isEmpty()) return List.of();
        if (activeTo == null) return to;
        if (!activeTo.isEmpty()) return activeTo;
        return activeCc == null || activeCc.isEmpty() ? to : List.of();
    }

    /** Yalnız pasif kullanıcılara ait adresler çıkarılmış alıcılar (koruma yoksa liste aynen). */
    List<String> activeRecipients(List<String> to) {
        if (inactiveGuard == null) return to;
        List<String> out = new ArrayList<>();
        for (String e : to) {
            try {
                if (!inactiveGuard.isInactiveOnlyEmail(e)) out.add(e);
            } catch (RuntimeException ex) {
                out.add(e);   // okunamazsa adres kalır (yalnız kayıt amaçlı)
            }
        }
        return out;
    }

    static String classify(String status) {
        if ("SENT".equals(status)) return "SENT";
        if (status.startsWith("QUEUED_RETRY")) return "QUEUED";
        if (status.startsWith("SKIPPED")) return "SKIPPED";
        return "FAILED";
    }

    /**
     * Kararın günlük satırı, verilen tetikleyiciyle ({@link #TRIGGER} gönderim, {@link #TEST_TRIGGER} kendine test); kimliği
     * döner (yazılamazsa null — karar yine de sonlanır). Satır raporun kanalına / raporuna bağlanır (R18 / E22, kanalın
     * gönderim günlüğü — {@link TechErrorSendLogService#link}).
     */
    private Long saveLog(String trigger, Team team, List<String> to, String[] cc, String role, String subject, String status,
                         TechErrorReport report) {
        try {
            NotificationLog n = new NotificationLog();
            TechErrorSendLogService.link(n, report == null ? null : report.getChannelId(), report == null ? null : report.getId());
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL)
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientName(clip(team.getName() == null ? "" : team.getName(), 255));
            n.setRecipientEmail(String.join(", ", to));     // varlık kolona sığdırır (NotificationLog.fitColumns)
            if (cc != null && cc.length > 0) n.setCc(String.join(", ", cc));
            n.setRecipientRole(role);
            n.setSubject(clip(subject, 500));
            n.setEmailStatus(clip(status, 250));
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(trigger);
            n.setEmailFrom(email.fromAddress());
            NotificationLog saved = logs.save(n);
            return saved == null ? null : saved.getId();
        } catch (Exception e) {
            log.warn("Teknik hata raporu gönderim günlüğü yazılamadı: {}", e.getMessage());
            return null;
        }
    }

    private String baseUrl() {
        try {
            String url = appSettings.getString("site.monitor.app.base-url", "");
            return url == null || url.isBlank() ? null : url.trim().replaceAll("/+$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Zaten push alanlardan ilki (bugün yalnız onaya gönderen kişi geçer); yoksa null. */
    static Long firstId(java.util.Collection<Long> ids) {
        if (ids == null) return null;
        for (Long id : ids) if (id != null) return id;
        return null;
    }
}
