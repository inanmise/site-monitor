package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportImage;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportImageRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.report.TechErrorReportPdfWriter;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Kanal teknik hata raporu iş kuralları (2026-10-13). Kapsam (kim hangi takımı görür / yazar) UÇTA denetlenir
 * ({@code TechErrorReportController}); burada veri kuralları, eşzamanlılık ve PDF üretimi var.
 *
 * <ul>
 *   <li>Kanal adı takım içinde tekil (büyük/küçük harf duyarsız, Türkçe); takım başına en çok 50 kanal.</li>
 *   <li>Şablon koşullu UPDATE ile yazılır (okunan sürüm değiştiyse 409); kanal adı/açıklaması şablon sürümünü artırmaz.</li>
 *   <li>Rapor dönemi yalnız BİTMİŞ bir ay olabilir (Europe/Istanbul); kanal × ay tekil.</li>
 *   <li>Değer kaydı {@code @Version} ile korunur; görsel yükleme sürümü artırmaz, "içerik değişti" damgasını vurur.</li>
 *   <li>Görsel: yuva başına bir, PNG/JPEG, ≤ 5 MB; rapordaki görsellerin toplamı ≤ 40 MB.</li>
 *   <li>PDF üretimi pod başına en çok 2 eşzamanlı (büyük görseller belleği zorlamasın); sıra 15 sn'de gelmezse 429.</li>
 * </ul>
 */
@Slf4j
@Service
public class TechErrorReportService {

    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    public static final int MAX_CHANNELS_PER_TEAM = 50;
    public static final int MAX_NAME = 120;
    public static final int MAX_DESCRIPTION = 500;
    /** Kanal sıra numarasının üst sınırı (PUT doğrulaması ve yeni kanalın "en büyük + 1" değeri). */
    public static final int MAX_SORT_ORDER = 10_000;
    public static final long MAX_REPORT_IMAGE_BYTES = 40L * 1024 * 1024;
    static final int PDF_CONCURRENCY = 2;

    private final TechErrorChannelRepository channels;
    private final TechErrorReportRepository reports;
    private final TechErrorReportImageRepository images;
    private final AppUserRepository users;
    private final TechErrorHistoryService history;
    private final Semaphore pdfSlots = new Semaphore(PDF_CONCURRENCY, true);

    /** Testler sabit "şimdi" verir ({@link #useClock}; alan doğrudan yazılırsa işlem vekili hedefe ulaşmaz). */
    private volatile Clock clock = Clock.systemUTC();

    public void useClock(Clock c) {
        this.clock = c == null ? Clock.systemUTC() : c;
    }

    /** Kuyruktaki gönderimin günlük satırını okumak için (yoksa — dilim testleri — kuyruk sonuçlandırılmaz). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.repository.NotificationLogRepository notificationLogs;

    /** E-posta eki sınırı (MB) — PDF bunu aşarsa görseller daha düşük katla gömülerek yeniden üretilir (gönderen de denetler). */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.tech-error-report.max-mail-mb:15}")
    int maxMailMb = 15;

    /** Canlı posta sınırı (12. sürüm, E21): Ayarlar'dan kaydedilen değer yeniden başlatmadan PDF merdivenini de yönetir (yoksa — dilim testleri — önyükleme değeri). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.service.AppSettingsService appSettings;

    /** Hatırlatma talepleri (12. sürüm): kanal silinince aynı işlemde silinir (yoksa — dilim testleri — saklama kuralı temizler). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.repository.TechErrorReminderRepository reminders;

    /** Kuyruk sonucu ile geçmiş olayını aynı işlemde yazmak için (dilim testlerinde yok → işlemsiz). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    org.springframework.transaction.PlatformTransactionManager txManager;

    /** PO onayı bağlantı belirteçleri (2026-10-17, S6): rapor / kanal silinince aynı işlemde silinir (yoksa — dilim testleri — atlanır). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.repository.TechErrorApprovalTokenRepository approvalTokens;

    public TechErrorReportService(TechErrorChannelRepository channels, TechErrorReportRepository reports,
                                  TechErrorReportImageRepository images, AppUserRepository users,
                                  TechErrorHistoryService history) {
        this.channels = channels;
        this.reports = reports;
        this.images = images;
        this.users = users;
        this.history = history;
    }

    public TechErrorHistoryService history() {
        return history;
    }

    /**
     * Kanal analizinin pod belleği (R22; inceleme 2026-10-18, 2. tur): bir raporun değerleri kaydedilince, rapor açılınca /
     * silinince, şablon uygulanınca ve kanal / şablon değişince boşaltılır — düzeltilen değer ya da silinen rapor 60 sn
     * bayat görünmesin. Yoksa (dilim testleri) atlanır.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    TechErrorAnalyticsService analytics;

    /**
     * Analiz belleğini işlem İŞLENDİKTEN sonra boşaltır (işlem yoksa hemen): işlem sürerken boşaltılsaydı araya giren bir
     * analiz okuması eski veriyi yeniden belleğe yazardı. Ucuz (yalnız bellek); hata yazma akışını bozmaz.
     */
    void analyticsChanged() {
        TechErrorAnalyticsService a = analytics;
        if (a == null) return;
        Runnable clear = () -> {
            try {
                a.invalidate();
            } catch (RuntimeException ignored) {
                // yalnız bellek — yazma akışı etkilenmez (en geç TTL sonunda tazelenir)
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            clear.run();
                        }
                    });
        } else {
            clear.run();
        }
    }

    // ── Dönem ────────────────────────────────────────────────────────────────────────────────────────

    /** Varsayılan rapor dönemi: İstanbul saatine göre bir önceki ay (Ocak'ta önceki yılın Aralık'ı). */
    public YearMonth defaultPeriod() {
        return YearMonth.now(clock.withZone(ZONE)).minusMonths(1);
    }

    /** Ay BİTTİ mi (İstanbul)? İçinde bulunulan ay ve gelecek reddedilir. */
    public boolean isClosedMonth(int year, int month) {
        if (month < 1 || month > 12 || year < 2000 || year > 2100) return false;
        return YearMonth.of(year, month).isBefore(YearMonth.now(clock.withZone(ZONE)));
    }

    public Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    // ── Kanallar ─────────────────────────────────────────────────────────────────────────────────────

    /** {@code teamIds == null} → tüm kanallar (kurum geneli görüntüleyici). Sıra: takım, sıra no, ad (Türkçe). */
    public List<TechErrorChannel> channels(Collection<Long> teamIds) {
        List<TechErrorChannel> list = teamIds == null ? channels.findAll()
                : teamIds.isEmpty() ? List.of() : channels.findByTeamIdIn(teamIds);
        List<TechErrorChannel> out = new ArrayList<>(list);
        java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
        col.setStrength(java.text.Collator.SECONDARY);
        out.sort(Comparator.comparing(TechErrorChannel::getTeamId)
                .thenComparing(ch -> ch.getSortOrder() == null ? Integer.MAX_VALUE : ch.getSortOrder())
                .thenComparing(TechErrorChannel::getName, col));
        return out;
    }

    public TechErrorChannel channel(Long id) {
        return channels.findById(id).orElseThrow(TechErrorReportService::channelNotFound);
    }

    @Transactional
    public TechErrorChannel createChannel(Long teamId, String rawName, String rawDescription, Template template, Long by) {
        return createChannel(teamId, rawName, rawDescription, template, by, null);
    }

    /**
     * Kanal açılışındaki şablonun KAYNAĞI (2026-10-18, D17) — kanalın ilk sürümüne yazılır: {@code library} (takım
     * kütüphanesi, {@code name} kaydın adı), {@code copy} (başka kanalın kopyası; ad YAZILMAZ), {@code default}.
     */
    public record TemplateSource(String kind, String name) {
        public static TemplateSource library(String name) { return new TemplateSource("library", name); }
        public static TemplateSource copy() { return new TemplateSource("copy", null); }
        public static TemplateSource defaults() { return new TemplateSource("default", null); }
    }

    /** {@link #createChannel(Long, String, String, Template, Long)} + şablonun kaynağı (sürüm geçmişinin ilk satırı). */
    @Transactional
    public TechErrorChannel createChannel(Long teamId, String rawName, String rawDescription, Template template, Long by,
                                          TemplateSource source) {
        String name = channelName(rawName);
        String key = nameKey(name);
        if (channels.existsByTeamIdAndNameKey(teamId, key)) throw channelExists(name);
        if (channels.countByTeamId(teamId) >= MAX_CHANNELS_PER_TEAM) {
            throw new CodedConflictException("CHANNEL_LIMIT", Msg.t(
                    "Bir takımın en çok 50 kanalı olabilir. Kullanılmayan bir kanalı silip tekrar deneyin.",
                    "A team can have at most 50 channels. Delete a channel you no longer use and try again."));
        }
        Instant now = now();
        TechErrorChannel ch = new TechErrorChannel();
        ch.setTeamId(teamId);
        ch.setName(name);
        ch.setNameKey(key);
        ch.setDescription(description(rawDescription));
        ch.setActive(true);
        ch.setSortOrder(nextSortOrder(teamId));
        ch.setTemplateJson(TechErrorTemplate.toJson(template));
        ch.setTemplateVersion(1);
        ch.setTemplateUpdatedAt(now);
        ch.setTemplateUpdatedById(by);
        ch.setCreatedAt(now);
        ch.setCreatedById(by);
        ch.setUpdatedAt(now);
        ch.setUpdatedById(by);
        TechErrorChannel saved;
        try {
            saved = channels.saveAndFlush(ch);
        } catch (DataIntegrityViolationException e) {
            throw channelExists(name);   // eşzamanlı aynı ad (tekillik kısıtı)
        }
        history.recordTemplateVersion(saved, 1, saved.getTemplateJson(), now, by, null,
                source == null ? null : source.kind(), source == null ? null : source.name());
        analyticsChanged();   // yeni kanal analizdeki kanal listesini değiştirir (3. tur inceleme)
        return saved;
    }

    /** YALNIZ gönderilen alanlar: name, description, active, sort_order. Gönderilmeyen değişmez. */
    @Transactional
    public TechErrorChannel updateChannel(Long id, Map<String, Object> body, Long by) {
        TechErrorChannel ch = channel(id);
        boolean changed = false;
        if (body.containsKey("name")) {
            String name = channelName(body.get("name") == null ? null : String.valueOf(body.get("name")));
            if (!name.equals(ch.getName())) {
                String key = nameKey(name);
                if (channels.existsByTeamIdAndNameKeyAndIdNot(ch.getTeamId(), key, ch.getId())) throw channelExists(name);
                ch.setName(name);
                ch.setNameKey(key);
                changed = true;
            }
        }
        if (body.containsKey("description")) {
            String d = description(body.get("description") == null ? null : String.valueOf(body.get("description")));
            if (!Objects.equals(d, ch.getDescription())) {
                ch.setDescription(d);
                changed = true;
            }
        }
        if (body.containsKey("active")) {
            Object a = body.get("active");
            if (!(a instanceof Boolean b)) throw new FieldValidationException("active", Msg.t(
                    "Kanal durumu açık ya da kapalı olmalı.", "The channel status must be on or off."));
            if (b != ch.isActive()) {
                ch.setActive(b);
                changed = true;
            }
        }
        if (body.containsKey("executive_emails")) {
            // Yönetici alıcıları (S6): boş = onay akışı yok; doluysa her parça teslim edilebilir bir adres olmalı (≤ 20)
            Object raw = body.get("executive_emails");
            String text = raw == null ? "" : String.valueOf(raw).trim();
            String normalized = null;
            if (!text.isEmpty()) {
                int given = 0;
                for (String p : text.split("[,;]")) {
                    if (p.isBlank()) continue;
                    given++;
                    if (!TechErrorReportSender.isDeliverable(p)) throw new FieldValidationException("executive_emails", Msg.t(
                            "Yönetici alıcı listesinde geçersiz bir adres var: \"" + p.trim() + "\". Adresleri virgülle ayırın (ör. ad.soyad@kurum.com).",
                            "The executive recipient list has an invalid address: \"" + p.trim() + "\". Separate addresses with commas (e.g. name@company.com)."));
                }
                List<String> parsed = TechErrorReportSender.parseAddresses(text);   // tekrarlar tekilleşir
                if (given > 20 || parsed.size() > 20) throw new FieldValidationException("executive_emails", Msg.t(
                        "En çok 20 yönetici alıcısı tanımlanabilir.", "At most 20 executive recipients can be set."));
                normalized = parsed.isEmpty() ? null : String.join(", ", parsed);
            }
            if (!Objects.equals(normalized, ch.getExecutiveEmails())) {
                ch.setExecutiveEmails(normalized);
                changed = true;
            }
        }
        if (body.containsKey("sort_order")) {
            Object so = body.get("sort_order");
            Integer v = so == null ? null : so instanceof Number n ? Integer.valueOf(n.intValue()) : null;
            if (so != null && v == null) throw new FieldValidationException("sort_order", Msg.t(
                    "Sıra numarası bir sayı olmalı.", "The order must be a number."));
            if (v != null && (v < 0 || v > MAX_SORT_ORDER)) throw new FieldValidationException("sort_order", Msg.t(
                    "Sıra numarası 0 ile 10 000 arasında olmalı.", "The order must be between 0 and 10,000."));
            if (!Objects.equals(v, ch.getSortOrder())) {
                ch.setSortOrder(v);
                changed = true;
            }
        }
        if (!changed) return ch;
        ch.setUpdatedAt(now());
        ch.setUpdatedById(by);
        analyticsChanged();
        try {
            return channels.saveAndFlush(ch);
        } catch (DataIntegrityViolationException e) {
            throw channelExists(ch.getName());
        }
    }

    /**
     * Yeni kanalın sırası (R14 inceleme 2026-10-17): "Sıra" sıralamasında EN SONA gelsin. Takımda sırasız (null) kanal
     * varsa null kalır — sırasızlar zaten en sondadır ve ad sırasıyla dizilir; 1 vermek yeni kanalı onların ÖNÜNE alırdı.
     * Aksi en büyük + 1 (üst sınırda kalır); boş takımda 1. Mevcut satırlara dokunulmaz.
     */
    private Integer nextSortOrder(Long teamId) {
        if (channels.countByTeamIdAndSortOrderIsNull(teamId) > 0) return null;
        Integer max = channels.maxSortOrder(teamId);
        return max == null ? 1 : Math.min(max + 1, MAX_SORT_ORDER);
    }

    /** Toplu sıra sonucu: önceki ve yeni dizilim (kanal kimlikleri) + gerçekten değişen satır sayısı. */
    public record ReorderResult(List<Long> before, List<Long> after, int changed) { }

    /**
     * Takımın kanal sırasını TEK işlemde yazar (R14 inceleme 2026-10-17): {@code ids} takımın TÜM kanallarının istenen
     * dizilimidir; eksik / fazla / tekrar eden / başka takıma ait kimlik → 400 {@code ids} (liste arada değişti). Sıra 1…n
     * olur; yalnız değeri değişen satıra hedefli UPDATE gider (varlık kaydı yok — ad / aktiflik / alıcılar geri sarılmaz).
     * Yazdıktan sonra takımın sıraları aynı işlemde yeniden okunur: eşzamanlı başka bir sıra kaydı (ya da yeni kanal) karışık
     * bir dizilim bıraktıysa 409 {@code CONCURRENT_MODIFICATION} → işlem geri alınır, hiçbir şey yarım kalmaz.
     */
    @Transactional
    public ReorderResult reorderChannels(Long teamId, List<Long> ids, Long by) {
        List<TechErrorChannel> team = channels(List.of(teamId));          // o anki "Sıra": sıra no artan, boş en sonda, ad
        List<Long> before = new ArrayList<>(team.size());
        Map<Long, TechErrorChannel> byId = new HashMap<>();
        for (TechErrorChannel ch : team) {
            before.add(ch.getId());
            byId.put(ch.getId(), ch);
        }
        List<Long> given = ids == null ? List.of() : ids;
        Set<Long> seen = new HashSet<>();
        boolean valid = given.size() == team.size();
        for (Long id : given) {
            if (id == null || !byId.containsKey(id) || !seen.add(id)) { valid = false; break; }
        }
        if (!valid) {
            throw new FieldValidationException("ids", Msg.t(
                    "Kanal listesi siz sıralarken değişti (kanal eklendi, silindi ya da başka bir takımın kanalı gönderildi). Listeyi yenileyip tekrar deneyin.",
                    "The channel list changed while you were reordering it (a channel was added or deleted, or another team's channel was sent). Reload the list and try again."));
        }
        Instant now = now();
        int changed = 0;
        for (int i = 0; i < given.size(); i++) {
            TechErrorChannel ch = byId.get(given.get(i));
            int order = i + 1;
            if (ch.getSortOrder() != null && ch.getSortOrder() == order) continue;
            changed += channels.updateSortOrder(ch.getId(), teamId, order, now, by);
        }
        if (changed > 0) {
            Map<Long, Integer> stored = new HashMap<>();
            for (Object[] row : channels.orderRows(teamId)) {
                stored.put(((Number) row[0]).longValue(), row[1] == null ? null : ((Number) row[1]).intValue());
            }
            boolean intact = stored.size() == given.size();
            for (int i = 0; intact && i < given.size(); i++) intact = Integer.valueOf(i + 1).equals(stored.get(given.get(i)));
            if (!intact) {
                throw new CodedConflictException("CONCURRENT_MODIFICATION", Msg.t(
                        "Kanal sırası siz kaydederken başka biri tarafından değiştirildi; sıra kaydedilmedi. Listeyi yenileyip tekrar deneyin.",
                        "Someone else changed the channel order while you were saving it, so the order wasn't saved. Reload the list and try again."));
            }
        }
        if (changed > 0) analyticsChanged();   // sıra, grafikteki ilk 8 kanalı belirler (3. tur inceleme)
        return new ReorderResult(before, List.copyOf(given), changed);
    }

    /**
     * Varsayılan dönemin kanal sayımları (Kişisel Panom "Dönem raporu eksik kanal" paneli, R14 inceleme 2026-10-17) — genel
     * bakışın {@code channels} listesinden istemcinin {@code channelListModel.channelCounts} ile bulduğu sayılarla AYNI
     * anlam: pasif kanal {@code inactive}; aktif kanalın dönem raporu durumu (raporStatus) {@code sent} → gönderildi, başka
     * bir durum (taslak / değişti / kuyrukta / gönderiliyor) → {@code open}, rapor yok → {@code missing}; {@code active} =
     * sent + open + missing. Şablon okunmaz, kuyruk sonuçlandırılmaz (genel bakış ve rapor okumaları yapar — kuyruktaki
     * gönderim sonucu okunana dek "açık" sayılır). {@code teamIds} = görünür ve VAR olan takımlar (silinmiş takımın kanalı
     * genel bakışta da listelenmez).
     */
    public PeriodSummary periodSummary(Collection<Long> teamIds, YearMonth ym) {
        if (teamIds == null || teamIds.isEmpty()) return new PeriodSummary(0, 0, 0, 0, 0);
        List<Long> teamList = new ArrayList<>(teamIds);
        List<Long> activeIds = new ArrayList<>();
        int inactive = 0;
        for (int i = 0; i < teamList.size(); i += 500) {
            for (Object[] row : channels.summaryRows(teamList.subList(i, Math.min(teamList.size(), i + 500)))) {
                if (Boolean.TRUE.equals(row[2])) activeIds.add(((Number) row[0]).longValue());
                else inactive++;
            }
        }
        Map<Long, TechErrorReportRepository.Row> period = new HashMap<>();
        for (int i = 0; i < activeIds.size(); i += 500) {
            period.putAll(periodRows(activeIds.subList(i, Math.min(activeIds.size(), i + 500)), ym));
        }
        Instant now = now();
        int sent = 0, open = 0, missing = 0;
        for (Long id : activeIds) {
            TechErrorReportRepository.Row r = period.get(id);
            if (r == null) missing++;
            else if ("sent".equals(reportStatus(r, now))) sent++;
            else open++;
        }
        return new PeriodSummary(activeIds.size(), sent, open, missing, inactive);
    }

    /** Dönem özeti: aktif kanal (= sent + open + missing), gönderildi, açık, başlanmamış, pasif. */
    public record PeriodSummary(int active, int sent, int open, int missing, int inactive) { }

    /**
     * Raporun liste durumu: draft | queued | sending | sent | changed (gönderimden sonra değişti). Tek kaynak — denetleyicinin
     * satır DTO'su ve dönem özeti aynı kuralı okur.
     */
    public static String reportStatus(TechErrorReportRepository.Row r, Instant now) {
        if ("SENDING".equals(r.getSendState()) && r.getSendClaimedAt() != null
                && r.getSendClaimedAt().isAfter(now.minus(java.time.Duration.ofMinutes(10)))) return "sending";
        if (r.getLastSentAt() == null) return "QUEUED".equals(r.getSendState()) ? "queued" : "draft";
        return changedAfterSend(r) ? "changed" : "sent";
    }

    /**
     * "Gönderimden sonra değişti" kuralının tek kaynağı ({@link #reportStatus} de okur): en az bir kez gönderilmiş ve içerik
     * sayacı gönderilen revizyonu geçmiş ({@code content_rev > sent_rev}); sayaçtan önceki gönderimde ({@code sent_rev} boş)
     * zaman damgası ({@code content_changed_at > last_sent_at}). Taze bir gönderim talebi (durum "sending") sürerken de
     * doğrudur — durum o an "sending" der ama rapor yine gönderilen PDF'ten farklıdır (değer dışa aktarımı ayrı okur).
     */
    public static boolean changedAfterSend(TechErrorReportRepository.Row r) {
        if (r == null || r.getLastSentAt() == null) return false;
        if (r.getSentRev() != null) {
            long content = r.getContentRev() == null ? 0L : r.getContentRev();
            return content > r.getSentRev();
        }
        // Sayaçtan önceki gönderim: zaman damgası
        return r.getContentChangedAt() != null && r.getContentChangedAt().isAfter(r.getLastSentAt());
    }

    /** Kanalı, raporlarını ve görsellerini KALICI siler (tek işlem). Silinen rapor sayısını döner. */
    @Transactional
    public int deleteChannel(Long id) {
        TechErrorChannel ch = channel(id);
        if (reports.countSendingForChannel(ch.getId(), now().minus(TechErrorReportSender.STALE_CLAIM)) > 0) {
            throw new CodedConflictException("REPORT_SENT_OR_SENDING", Msg.t(
                    "Bu kanalın bir raporu şu anda gönderiliyor; kanal silinmedi. Gönderim bitince tekrar deneyin.",
                    "One of this channel's reports is being sent right now, so the channel wasn't deleted. Try again when the send finishes."));
        }
        List<Long> ids = reports.idsForChannel(ch.getId());
        if (!ids.isEmpty()) {
            for (int i = 0; i < ids.size(); i += 500) images.deleteAllForReports(ids.subList(i, Math.min(ids.size(), i + 500)));
            history.deleteForReports(ids);
            if (approvalTokens != null) for (Long rid : ids) approvalTokens.deleteByReportId(rid);
        }
        history.deleteForChannel(ch.getId());
        if (reminders != null) reminders.deleteByChannel(ch.getId());
        // Karar silme anında: arada bir gönderim talebi alındıysa (ya da yeni rapor açıldıysa) sayılar tutmaz → geri al
        int n = reports.deleteAllForChannelUnlessSending(ch.getId(), now().minus(TechErrorReportSender.STALE_CLAIM));
        if (n != ids.size()) {
            throw new CodedConflictException("REPORT_SENT_OR_SENDING", Msg.t(
                    "Bu kanalın raporları siz silerken değişti (biri gönderiliyor ya da yeni rapor açıldı); kanal silinmedi. Sayfayı yenileyip tekrar deneyin.",
                    "This channel's reports changed while you were deleting it (one is being sent or a new one was opened), so the channel wasn't deleted. Reload the page and try again."));
        }
        channels.delete(ch);
        channels.flush();
        analyticsChanged();
        return n;
    }

    /**
     * Şablonu yazar; okunan sürüm eskiyse {@link OptimisticLockingFailureException} → 409. Yeni sürümü döner. Sürüm
     * geçmişine yeni sürüm yazılır; bu özellikten önceki kanalda o anki (değişen) sürüm de taban olarak yazılır. Koşullu
     * UPDATE ÖNCE koşar: satırı kilitler, aynı sürüme yarışan ikinci kayıt 409 alır ve geçmişe hiçbir şey yazmaz.
     */
    @Transactional
    public int saveTemplate(Long channelId, int expectedVersion, Template template, Long by) {
        return saveTemplate(channelId, expectedVersion, template, by, null);
    }

    /** {@code actorName}: sistem yazdıysa sürüm geçmişinde görünen ad (kişi değil). */
    @Transactional
    public int saveTemplate(Long channelId, int expectedVersion, Template template, Long by, String actorName) {
        TechErrorChannel before = channels.findById(channelId).orElseThrow(TechErrorReportService::channelNotFound);
        String prevJson = before.getTemplateJson();
        int prevVersion = before.getTemplateVersion();
        Instant prevAt = before.getTemplateUpdatedAt();
        Long prevBy = before.getTemplateUpdatedById();
        Instant now = now();
        String json = TechErrorTemplate.toJson(template);
        int n = channels.saveTemplate(channelId, expectedVersion, json, now, by);
        if (n == 0) {
            if (!channels.existsById(channelId)) throw channelNotFound();
            throw new OptimisticLockingFailureException("tech_error_channel template version");
        }
        // Okunan hâl kilitli satırın değiştirilen sürümüyse taban olarak yazılır (sürüm tekdüze artar: UPDATE başarılıysa
        // okunan sürüm == beklenen sürüm olan JSON, o sürümün gerçek içeriğidir)
        if (prevVersion == expectedVersion) history.recordTemplateVersion(before, prevVersion, prevJson, prevAt, prevBy);
        history.recordTemplateVersion(before, expectedVersion + 1, json, now, by, actorName);
        analyticsChanged();
        return expectedVersion + 1;
    }

    // ── Raporlar ─────────────────────────────────────────────────────────────────────────────────────

    public TechErrorReport report(Long id) {
        return reports.findById(id).orElseThrow(TechErrorReportService::reportNotFound);
    }

    public List<TechErrorReportRepository.Row> reportRows(Long channelId) {
        List<TechErrorReportRepository.Row> rows = reports.findByChannelIdOrderByReportYearDescReportMonthDesc(channelId);
        return rows.size() > 600 ? rows.subList(0, 600) : rows;   // 50 yıl — fiilen sınırsız, ama sınırlı
    }

    public Map<Long, TechErrorReportRepository.Row> periodRows(Collection<Long> channelIds, YearMonth ym) {
        Map<Long, TechErrorReportRepository.Row> out = new HashMap<>();
        if (channelIds.isEmpty()) return out;
        for (var r : reports.findByChannelIdInAndReportYearAndReportMonth(channelIds, ym.getYear(), ym.getMonthValue())) {
            out.put(r.getChannelId(), r);
        }
        return out;
    }

    /**
     * Kanalın raporlarının kopyaladıkları şablon sürümüne göre sayısı (2026-10-18, D19): TEK gruplu sorgu. Anahtar sürüm,
     * {@code null} = sürümü bilinmeyen raporlar (bu özellikten önce açılmış, geçmişten bulunamamış).
     */
    public Map<Integer, Long> templateVersionCounts(Long channelId) {
        Map<Integer, Long> out = new HashMap<>();
        for (Object[] row : reports.countByTemplateVersion(channelId)) {
            Integer v = row[0] == null ? null : Integer.valueOf(((Number) row[0]).intValue());
            out.merge(v, ((Number) row[1]).longValue(), Long::sum);
        }
        return out;
    }

    /** Bu şablon sürümüyle açılmış / güncellenmiş en yeni 24 rapor (dönem azalan) — sürüm ayrıntısı. */
    public List<TechErrorReportRepository.Row> reportsWithTemplateVersion(Long channelId, int version) {
        return reports.findTop24ByChannelIdAndTemplateVersionOrderByReportYearDescReportMonthDesc(channelId, version);
    }

    public Map<Long, Long> reportCounts(Collection<Long> channelIds) {
        Map<Long, Long> out = new HashMap<>();
        if (channelIds.isEmpty()) return out;
        for (Object[] row : reports.countByChannel(channelIds)) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    @Transactional
    public TechErrorReport createReport(TechErrorChannel ch, int year, int month, Long by) {
        if (!isClosedMonth(year, month)) {
            throw new FieldValidationException("month", Msg.t(
                    "Rapor yalnız bitmiş bir ay için açılabilir (içinde bulunulan ay ve sonrası olmaz).",
                    "A report can only be opened for a month that has ended (not the current or a future month)."));
        }
        if (!ch.isActive()) {
            throw new CodedConflictException("CHANNEL_INACTIVE", Msg.t(
                    "Bu kanal pasif; yeni rapor açmak için önce kanalı etkinleştirin.",
                    "This channel is inactive; turn it back on before opening a new report."));
        }
        Optional<TechErrorReport> existing = reports.findByChannelIdAndReportYearAndReportMonth(ch.getId(), year, month);
        if (existing.isPresent()) throw reportExists(existing.get().getId());
        Template t = TechErrorTemplate.fromStored(ch.getTemplateJson());
        Instant now = now();
        TechErrorReport r = new TechErrorReport();
        r.setChannelId(ch.getId());
        r.setTeamId(ch.getTeamId());
        r.setReportYear(year);
        r.setReportMonth(month);
        r.setTemplateJson(TechErrorTemplate.toJson(t));
        r.setTemplateVersion(ch.getTemplateVersion());      // kopyalanan şablonun sürümü (aynı varlık okumasından, D19)
        stampTitle(r, t);
        r.setValuesJson(TechErrorValues.toJson(Values.empty()));
        r.setCreatedAt(now);
        r.setCreatedById(by);
        r.setUpdatedAt(now);
        r.setUpdatedById(by);
        r.setContentChangedAt(now);
        TechErrorReport saved;
        try {
            saved = reports.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // Aynı anda iki kişi aynı ayı açtı. PostgreSQL'de işlem artık bozuk (25P02): aynı işlemde kazananı okumak
            // 500 verirdi (inceleme 2026-10-13) — kimliksiz 409; istemci listeyi yenileyip mevcut raporu gösterir.
            throw reportExists(null);
        }
        history.record(saved, "CREATE", by, saved.getVersion(), Map.of("template_version", ch.getTemplateVersion()), now);
        analyticsChanged();
        return saved;
    }

    /** Değerleri kaydeder; {@code expectedVersion} okunan sürüm — farklıysa 409. */
    @Transactional
    public TechErrorReport saveValues(Long reportId, Long expectedVersion, Object raw, Long by) {
        return saveValues(reportId, expectedVersion, raw, by, false).report();
    }

    /** Kayıt sonucu: kaydedilen rapor + otomatik kaydın geçmişte önceki otomatik olayla birleşip birleşmediği. */
    public record SaveOutcome(TechErrorReport report, boolean mergedHistory) { }

    /**
     * Değerleri kaydeder. {@code auto} (2026-10-16, otomatik kayıt — istemci 30 sn'de bir): geçmişte aynı kişinin 15 dk
     * içindeki otomatik kayıtları tek olayda birleşir ({@link TechErrorHistoryService#recordValues(TechErrorReport,
     * Template, Values, Values, String, Long, Instant, boolean)}); sürüm denetimi, hesaplanan oranlar ve içerik sayacı
     * elle kayıtla aynıdır.
     */
    @Transactional
    public SaveOutcome saveValues(Long reportId, Long expectedVersion, Object raw, Long by, boolean auto) {
        TechErrorReport r = report(reportId);
        requireVersion(r, expectedVersion);
        Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        // Otomatik hesaplanan alanlar (2026-10-16) SUNUCUDA hesaplanır: istemcinin gönderdiği oran yok sayılır, girilen
        // adetlerden (ve geçen ayın raporundan) yeniden bulunur
        // Anomali konusunun tarihi raporun ayı içinde olmalı (2026-10-18) — dönem doğrulamaya verilir
        Values v = TechErrorCalc.apply(t, TechErrorValues.normalize(raw, t, YearMonth.of(r.getReportYear(), r.getReportMonth())),
                previousFields(r, t));
        String json = TechErrorValues.toJson(v);
        if (json.equals(r.getValuesJson())) return new SaveOutcome(r, false);   // değişiklik yok → sürüm artmaz, yazılmaz, olay yok
        Values before = TechErrorValues.fromStored(r.getValuesJson(), t);
        Instant now = now();
        r.setValuesJson(json);
        r.setUpdatedAt(now);
        r.setUpdatedById(by);
        r.setContentChangedAt(now);
        TechErrorReport saved = reports.saveAndFlush(r);
        reports.touchContent(saved.getId(), now);      // içerik sayacı (atomik, aynı işlem)
        boolean merged = history.recordValues(saved, t, before, v, channelName(saved.getChannelId()), by, now, auto);
        // Silinen anomali konusunun görselleri konuyla gider (2026-10-18): aynı işlemde, her biri geçmişte konunun adıyla
        dropOrphanIssueImages(saved, t, before, v, by, now);
        analyticsChanged();
        return new SaveOutcome(saved, merged);
    }

    public record RefreshResult(TechErrorReport report, int removedValues, int removedImages) { }

    /**
     * Taslağa kanal şablonunun SON hâlini uygular: aynı kimlikli alanların değerleri ve yuvaların görselleri korunur;
     * şablondan çıkarılanlar silinir (istemci onaydan önce sayıları gösterir — {@link #refreshPreview}).
     */
    @Transactional
    public RefreshResult refreshTemplate(Long reportId, Long expectedVersion, Long by) {
        TechErrorReport r = report(reportId);
        requireVersion(r, expectedVersion);
        TechErrorChannel ch = channel(r.getChannelId());
        Template next = TechErrorTemplate.fromStored(ch.getTemplateJson());
        Template prev = TechErrorTemplate.fromStored(r.getTemplateJson());
        Values before = TechErrorValues.fromStored(r.getValuesJson(), prev);
        Values carried = carry(before, prev, next);
        Values after = TechErrorCalc.apply(next, carried, previousFields(r, next));
        // Kayıp yalnız şablon farkından sayılır: yeni hesaplanan oranlar kaybı gizlemesin (inceleme 2026-10-16)
        int removedValues = countValues(before) - countValues(carried);
        Set<String> keep = keepSlots(next, carried);
        int removedImages = keep.isEmpty() ? images.deleteAllForReport(r.getId()) : images.deleteSlotsNotIn(r.getId(), keep);
        Instant now = now();
        r.setTemplateJson(TechErrorTemplate.toJson(next));
        r.setTemplateVersion(ch.getTemplateVersion());      // uygulanan kanal şablonunun sürümü (D19)
        stampTitle(r, next);
        r.setValuesJson(TechErrorValues.toJson(after));
        r.setUpdatedAt(now);
        r.setUpdatedById(by);
        r.setContentChangedAt(now);
        TechErrorReport saved = reports.saveAndFlush(r);
        reports.touchContent(saved.getId(), now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("template_version", ch.getTemplateVersion());
        detail.put("removed_values", Math.max(0, removedValues));
        detail.put("removed_images", removedImages);
        history.record(saved, "TEMPLATE_REFRESH", by, saved.getVersion(), detail, now);
        analyticsChanged();
        return new RefreshResult(saved, Math.max(0, removedValues), removedImages);
    }

    /** Şablonun son hâli uygulanırsa kaybolacak değer ve görsel sayısı + şablon farklı mı (salt okuma). */
    public Map<String, Object> refreshPreview(TechErrorReport r, TechErrorChannel ch) {
        Template next = TechErrorTemplate.fromStored(ch.getTemplateJson());
        Template prev = TechErrorTemplate.fromStored(r.getTemplateJson());
        Values before = TechErrorValues.fromStored(r.getValuesJson(), prev);
        Values carried = carry(before, prev, next);
        Values after = TechErrorCalc.apply(next, carried, previousFields(r, next));
        Set<String> keep = keepSlots(next, carried);
        int lostImages = 0;
        for (var m : images.findByReportIdOrderByIdAsc(r.getId())) if (!keep.contains(m.getSlotId())) lostImages++;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("differs", !TechErrorTemplate.toJson(next).equals(TechErrorTemplate.toJson(prev)));
        out.put("lost_values", Math.max(0, countValues(before) - countValues(carried)));
        out.put("lost_images", lostImages);
        // Girilmiş bir değerin yerine hesaplanan (ya da adet eksik olduğu için boşalan) oran sayısı — onayda ayrıca söylenir
        int recalculated = 0;
        for (var f : next.allFields()) {
            if (f.calc() == null) continue;
            Object was = carried.fields().get(f.id());
            if (was != null && !java.util.Objects.equals(was, after.fields().get(f.id()))) recalculated++;
        }
        out.put("recalculated", recalculated);
        return out;
    }

    /**
     * Raporu ve görsellerini siler. {@code onlyUnsent}: üye yalnız hiç gönderilmemiş taslağı siler. Karar SİLME anında
     * veritabanında verilir (koşullu DELETE): arada biten bir gönderim ya da süren bir talep varsa 409, hiçbir şey silinmez.
     */
    @Transactional
    public boolean deleteReport(Long reportId, boolean onlyUnsent) {
        TechErrorReport r = report(reportId);
        boolean wasSent = r.getLastSentAt() != null;
        images.deleteAllForReport(r.getId());
        history.deleteForReport(r.getId());
        if (approvalTokens != null) approvalTokens.deleteByReportId(r.getId());
        int n = reports.deleteIfAllowed(r.getId(), onlyUnsent, now().minus(TechErrorReportSender.STALE_CLAIM));
        if (n == 0) {
            // İşlem geri alınır (görseller de) — sebep: gönderildi ya da gönderiliyor
            throw new CodedConflictException("REPORT_SENT_OR_SENDING", Msg.t(
                    "Rapor şu anda gönderiliyor ya da az önce gönderildi; silinmedi. Sayfayı yenileyin; gönderilmiş raporu yalnız takım yöneticisi silebilir.",
                    "The report is being sent or was just sent, so it wasn't deleted. Reload the page; only a team manager can delete a sent report."));
        }
        analyticsChanged();
        return wasSent;
    }

    /**
     * Kuyruğa alınmış gönderimleri SONUÇLANDIRIR (okuma uçları çağırır): posta servisi yeniden denemeyi bitirince
     * gönderimin {@code notification_logs} satırını SENT / FAILED yapar; burada rapor buna göre yazılır (koşullu,
     * yalnız aynı günlük satırı ve QUEUED iken). Bir sorgu + kuyrukta olan her satır için en çok bir UPDATE; okuma
     * hatası okumayı bozmaz (kuyrukta görünmeye devam eder). Sonuçlanan rapor sayısını döner.
     */
    public int reconcileQueued(Collection<Long> channelIds) {
        if (notificationLogs == null || channelIds == null || channelIds.isEmpty()) return 0;
        try {
            List<Object[]> rows = reports.queuedForChannels(channelIds);
            if (rows.isEmpty()) return 0;
            Set<Long> logIds = new HashSet<>();
            for (Object[] r : rows) if (r[1] != null) logIds.add(((Number) r[1]).longValue());
            Map<Long, String> status = new HashMap<>();
            for (var l : notificationLogs.findAllById(logIds)) status.put(l.getId(), l.getEmailStatus());
            int changed = 0;
            for (Object[] r : rows) {
                Long id = ((Number) r[0]).longValue();
                Long logId = ((Number) r[1]).longValue();
                String st = status.get(logId);
                if (!"SENT".equals(st) && (st == null || !st.startsWith("FAILED"))) continue;
                try {
                    changed += inNewTx(() -> finishQueuedWithEvent(id, logId, st));
                } catch (RuntimeException e) {
                    // Bu satır sonraki okumada yeniden denenir (koşullu UPDATE geri alındı); diğerleri sürer
                    log.warn("Kuyruktaki teknik hata raporu #{} sonuçlandırılamadı: {}", id, e.toString());
                }
            }
            return changed;
        } catch (RuntimeException e) {
            log.warn("Kuyruktaki teknik hata raporu gönderimleri sonuçlandırılamadı: {}", e.toString());
            return 0;
        }
    }

    /**
     * Koşullu sonuçlandırma + geçmiş olayı (sistem). Olay yalnız UPDATE bu çağrıda kazandıysa yazılır — iki pod aynı sonucu
     * iki kez yazmaz; olay yazılamazsa işlem geri alınır ve sonuç bir sonraki okumada yeniden denenir. Olayın zamanı sonucun
     * OKUNDUĞU an (posta servisi teslim anını saklamaz) — arayüz bunu söyler.
     */
    private int finishQueuedWithEvent(Long id, Long logId, String st) {
        boolean sent = "SENT".equals(st);
        int c = sent ? reports.confirmQueued(id, logId)
                : reports.failQueued(id, logId, st.length() > 255 ? st.substring(0, 255) : st);
        if (c > 0) {
            TechErrorReport rep = reports.findById(id).orElse(null);
            if (rep != null) {
                history.record(rep, "SEND_RESULT", null, null, TechErrorHistoryService.sendDetail(sent ? "SENT" : "FAILED", null,
                        sent ? null : TechErrorHistoryService.sendReason(st)), now());
            }
        }
        return c;
    }

    /** Yeni bir işlemde çalıştırır (işlem yöneticisi yoksa doğrudan). */
    private <T> T inNewTx(java.util.function.Supplier<T> work) {
        if (txManager == null) return work.get();
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> work.get());
    }

    /** Kanal × ay raporunun kimliği (yoksa null) — eşzamanlı açılış çakışmasından SONRA, yeni bir okumayla. */
    public Long findReportId(Long channelId, int year, int month) {
        return reports.findByChannelIdAndReportYearAndReportMonth(channelId, year, month).map(TechErrorReport::getId).orElse(null);
    }

    /** Aynı kanalın bir önceki ayının raporu (varsa) — "geçen ay" ipuçları için. */
    public Optional<TechErrorReport> previousReport(TechErrorReport r) {
        YearMonth prev = YearMonth.of(r.getReportYear(), r.getReportMonth()).minusMonths(1);
        return reports.findByChannelIdAndReportYearAndReportMonth(r.getChannelId(), prev.getYear(), prev.getMonthValue());
    }

    /**
     * Geçen ayın raporunun, BU şablondaki alanlarla AYNI kimlik ve türdeki değerleri (metin alanları hariç); rapor yoksa
     * null. "Geçen ay" ipuçları ve önceki aya göre değişim hesabı ({@link TechErrorCalc}) bunu okur — kimliği başka bir
     * alana geçmiş ya da türü değişmiş bir alanın değeri kullanılmaz (inceleme 2026-10-13).
     */
    public Map<String, Object> previousFields(TechErrorReport r, Template current) {
        return previousReport(r).map(p -> sameTypeFields(p, current)).orElse(null);
    }

    public static Map<String, Object> sameTypeFields(TechErrorReport prev, Template current) {
        Template pt = TechErrorTemplate.fromStored(prev.getTemplateJson());
        return sameTypeFields(pt, TechErrorValues.fromStored(prev.getValuesJson(), pt), current);
    }

    /**
     * {@link #sameTypeFields(TechErrorReport, Template)}'in ayrıştırılmış hâli: geçen ayın KENDİ şablonu ({@code pt}) ve saklı
     * değerleri ({@code pv}). Değer dışa aktarımı ({@link TechErrorExportService}) şablonları bir kez ayrıştırıp bunu çağırır —
     * kural tek yerde kalır.
     */
    public static Map<String, Object> sameTypeFields(Template pt, Values pv, Template current) {
        Map<String, String> prevTypes = new HashMap<>();
        for (var f : pt.allFields()) prevTypes.put(f.id(), f.type());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var f : current.allFields()) {
            Object v = pv.fields().get(f.id());
            if (v != null && !"textarea".equals(f.type()) && !"text".equals(f.type()) && f.type().equals(prevTypes.get(f.id()))) {
                fields.put(f.id(), v);
            }
        }
        return fields;
    }

    /**
     * Geçen ayın kısaltma üsleri (2026-10-18), yalnız {@link #sameTypeFields} ile seçilmiş alanlar için: "geçen ay" ipucu
     * değeri geçen ayın raporunda nasıl yazıldıysa öyle gösterir ("4,4G"). Anomali / tablo anahtarları taşınmaz.
     */
    public static Map<String, Integer> sameTypeUnits(Values pv, Map<String, Object> fields) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : pv.units().entrySet()) {
            if (e.getKey().indexOf('.') < 0 && fields.get(e.getKey()) instanceof Number) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ── Görseller ────────────────────────────────────────────────────────────────────────────────────

    public List<TechErrorReportImageRepository.Meta> imageMeta(Long reportId) {
        return images.findByReportIdOrderByIdAsc(reportId);
    }

    public Map<Long, long[]> imageCounts(Collection<Long> reportIds) {
        Map<Long, long[]> out = new HashMap<>();
        if (reportIds.isEmpty()) return out;
        for (Object[] row : images.countsByReport(reportIds)) {
            out.put(((Number) row[0]).longValue(), new long[]{ ((Number) row[1]).longValue(), ((Number) row[2]).longValue() });
        }
        return out;
    }

    public TechErrorReportImage image(Long imageId) {
        return images.findById(imageId).orElseThrow(TechErrorReportService::imageNotFound);
    }

    static NoSuchElementException imageNotFound() {
        return new NoSuchElementException(Msg.t(
                "Görsel bulunamadı; silinmiş ya da yerine yenisi yüklenmiş olabilir. Raporu yenileyin.",
                "The image wasn't found; it may have been deleted or replaced. Reload the report."));
    }

    /**
     * Şablon yenilenince KALAN görsel yuvaları: şablonun yuvaları + (2026-10-18) görsele izin veren anomali bölümünde
     * taşınan her konunun iki yuvası ({@code iss:<bölüm>:<konu>:<n>}). Silinen konunun, kapatılan görsel sorusunun ya da
     * kaldırılan bölümün konu görselleri düşer.
     */
    static Set<String> keepSlots(Template next, Values carried) {
        Set<String> keep = new HashSet<>();
        for (var sl : next.allSlots()) keep.add(sl.id());
        for (var s : next.sections()) {
            if (!"issues".equals(s.kind()) || !s.opts().allowImages()) continue;
            for (var i : carried.issues().getOrDefault(s.id(), List.of())) {
                for (int n = 1; n <= TechErrorTemplate.MAX_ISSUE_IMAGES; n++) keep.add(TechErrorTemplate.issueSlotId(s.id(), i.id(), n));
            }
        }
        return keep;
    }

    /**
     * Kayıttan sonra artık konusu olmayan konu görsellerini siler (2026-10-18: "konu silinince görselleri de gider"); her silme
     * geçmişte IMAGE_DELETE olayıdır (konunun adı + {@code reason: topic_removed}). Konu görseli yoksa sorgu dışında iş yok.
     */
    private void dropOrphanIssueImages(TechErrorReport r, Template t, Values before, Values after, Long by, Instant now) {
        // Görsele izin veren anomali bölümü yoksa konu görseli de olamaz (yükleme reddedilir, şablon yenileme siler) — sorgu yok
        if (t.sections().stream().noneMatch(s -> "issues".equals(s.kind()) && s.opts().allowImages())) return;
        List<TechErrorReportImageRepository.Meta> orphans = null;
        for (var m : images.findByReportIdOrderByIdAsc(r.getId())) {
            TechErrorTemplate.IssueSlot is = TechErrorTemplate.parseIssueSlot(m.getSlotId());
            if (is == null) continue;
            TechErrorTemplate.Section s = t.section(is.sectionId());
            boolean alive = s != null && "issues".equals(s.kind()) && s.opts().allowImages()
                    && after.issues().getOrDefault(s.id(), List.of()).stream().anyMatch(i -> i.id().equals(is.issueId()));
            if (alive) continue;
            if (orphans == null) orphans = new ArrayList<>();
            orphans.add(m);
        }
        if (orphans == null) return;
        String channel = channelName(r.getChannelId());
        for (var m : orphans) {
            images.deleteSlot(r.getId(), m.getSlotId());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("slot", issueSlotLabel(t, before, m.getSlotId(), channel, r));
            if (m.getFileName() != null) d.put("file_name", m.getFileName());
            d.put("width", m.getWidth());
            d.put("height", m.getHeight());
            d.put("size_bytes", m.getSizeBytes());
            d.put("reason", "topic_removed");
            history.record(r, "IMAGE_DELETE", by, null, d, now);
        }
    }

    /**
     * Konu görseli yuvasının okunur adı (geçmiş olayı): "&lt;konunun başlığı&gt; — Görsel 1" (şablon dili); konu bulunamazsa
     * "Anomali konusu — Görsel 1".
     */
    static String issueSlotLabel(Template t, Values v, String slotId, String channel, TechErrorReport r) {
        TechErrorTemplate.IssueSlot is = TechErrorTemplate.parseIssueSlot(slotId);
        if (is == null) return slotId;
        boolean en = t.en();
        TechErrorTemplate.Section s = t.section(is.sectionId());
        String name = null;
        if (s != null && v != null) {
            for (var i : v.issues().getOrDefault(s.id(), List.of())) {
                if (!i.id().equals(is.issueId())) continue;
                String title = i.displayTitle(s.itemTitle());
                if (title != null && !title.isBlank()) {
                    name = TechErrorTemplate.resolve(title, channel, r.getReportYear(), r.getReportMonth(), t.lang());
                }
            }
        }
        if (name == null || name.isBlank()) name = en ? "Anomaly topic" : "Anomali konusu";
        return name + " — " + (en ? "Image " : "Görsel ") + is.n();
    }

    /** Yuvaya görsel yükler (varsa eskisinin yerine). Sürüm artmaz; "içerik değişti" damgası vurulur. */
    @Transactional
    public TechErrorReportImage upload(Long reportId, String slotId, byte[] data, String fileName, Long by) {
        TechErrorReport r = report(reportId);
        Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        TechErrorTemplate.IssueSlot issueSlot = TechErrorTemplate.parseIssueSlot(slotId);
        if (issueSlot != null) {
            requireIssueSlot(r, t, issueSlot);
        } else if (slotId == null || t.slot(slotId) == null) {
            throw new FieldValidationException("slot", Msg.t(
                    "Bu görsel yuvası raporun şablonunda yok. Raporu yenileyip tekrar deneyin.",
                    "This image slot isn't in the report's template. Reload the report and try again."));
        }
        TechErrorImageValidator.Checked ck = TechErrorImageValidator.check(data);
        long others = 0;
        boolean replaced = false;
        for (var m : images.findByReportIdOrderByIdAsc(reportId)) {
            if (!m.getSlotId().equals(slotId)) others += m.getSizeBytes();
            else replaced = true;
        }
        if (others + data.length > MAX_REPORT_IMAGE_BYTES) {
            throw new FieldValidationException("file", Msg.t(
                    "Rapordaki görsellerin toplamı 40 MB'ı geçemez. Büyük görselleri küçültüp ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "The images in a report can add up to at most 40 MB. Make the large images smaller or save them as JPEG, then upload again."));
        }
        images.deleteSlot(reportId, slotId);
        Instant now = now();
        TechErrorReportImage img = new TechErrorReportImage();
        img.setReportId(reportId);
        img.setTeamId(r.getTeamId());
        img.setSlotId(slotId);
        img.setContentType(ck.contentType());
        img.setFileName(fileName(fileName));
        img.setWidth(ck.width());
        img.setHeight(ck.height());
        img.setSizeBytes(data.length);
        img.setData(data);
        img.setUploadedAt(now);
        img.setUploadedById(by);
        TechErrorReportImage saved;
        try {
            saved = images.saveAndFlush(img);
        } catch (DataIntegrityViolationException e) {
            throw new OptimisticLockingFailureException("tech_error_report_image slot race", e);
        }
        reports.touchContent(reportId, now);
        history.record(r, replaced ? "IMAGE_REPLACE" : "IMAGE_ADD", by, null, imageDetail(r, t, saved), now);
        return saved;
    }

    @Transactional
    public TechErrorReportImage deleteImage(Long reportId, Long imageId, Long by) {
        TechErrorReportImage img = image(imageId);
        if (!img.getReportId().equals(reportId)) throw imageNotFound();
        TechErrorReport r = report(reportId);
        images.delete(img);
        images.flush();
        Instant now = now();
        reports.touchContent(reportId, now);
        history.record(r, "IMAGE_DELETE", by, null, imageDetail(r, TechErrorTemplate.fromStored(r.getTemplateJson()), img), now);
        return img;
    }

    /**
     * Konu görseli yuvası yalnız: bölüm raporun şablonunda bir anomali bölümü, görsele izin veriyor ve konu KAYITLI değerlerde
     * var (kaydedilmemiş konuya yüklenen görsel sahipsiz kalırdı — istemci önce kaydeder).
     */
    private static void requireIssueSlot(TechErrorReport r, Template t, TechErrorTemplate.IssueSlot is) {
        TechErrorTemplate.Section s = t.section(is.sectionId());
        if (s == null || !"issues".equals(s.kind()) || !s.opts().allowImages()) {
            throw new FieldValidationException("slot", Msg.t(
                    "Bu anomali bölümünde konu görseli yok (şablon görsele izin vermiyor). Raporu yenileyip tekrar deneyin.",
                    "This anomaly section takes no topic images (the template doesn't allow them). Reload the report and try again."));
        }
        Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
        boolean saved = v.issues().getOrDefault(s.id(), List.of()).stream().anyMatch(i -> i.id().equals(is.issueId()));
        if (!saved) {
            throw new FieldValidationException("slot", Msg.t(
                    "Bu konu henüz kaydedilmedi. Önce raporu kaydedin, sonra görseli yükleyin.",
                    "This topic isn't saved yet. Save the report first, then upload the image."));
        }
    }

    /** Görsel olayının ayrıntısı: yuvanın (çözülmüş) adı + dosya bilgisi. */
    private Map<String, Object> imageDetail(TechErrorReport r, Template t, TechErrorReportImage img) {
        Map<String, Object> m = new LinkedHashMap<>();
        TechErrorTemplate.Slot slot = t.slot(img.getSlotId());
        String channel = channelName(r.getChannelId());
        if (TechErrorTemplate.parseIssueSlot(img.getSlotId()) != null) {
            m.put("slot", issueSlotLabel(t, TechErrorValues.fromStored(r.getValuesJson(), t), img.getSlotId(), channel, r));
        } else {
            m.put("slot", slot == null ? img.getSlotId() : TechErrorTemplate.resolve(slot.label(), channel,
                    r.getReportYear(), r.getReportMonth(), t.lang()));
        }
        if (img.getFileName() != null) m.put("file_name", img.getFileName());
        m.put("width", img.getWidth());
        m.put("height", img.getHeight());
        m.put("size_bytes", img.getSizeBytes());
        return m;
    }

    /** Kanal adı (geçmiş etiketlerinin yer tutucuları için); kanal yoksa boş. */
    private String channelName(Long channelId) {
        return channels.findById(channelId).map(TechErrorChannel::getName).orElse("");
    }

    // ── PDF ──────────────────────────────────────────────────────────────────────────────────────────

    public TechErrorPdfInput pdfInput(TechErrorReport r, TechErrorChannel ch, String teamName) {
        Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        // Hesaplanan alanlar PDF / e-posta anında CANLI: geçen ayın rakamı sonradan düzeltildiyse değişim oranı da güncel
        // (ekranda görülenle aynı; saklı değer bir sonraki kayıtta tazelenir)
        Map<String, Object> previous = previousFields(r, t);
        Values v = TechErrorCalc.apply(t, TechErrorValues.fromStored(r.getValuesJson(), t), previous);
        Map<String, TechErrorPdfInput.Image> imgs = new LinkedHashMap<>();
        for (TechErrorReportImage i : images.findAllByReportIdOrderByIdAsc(r.getId())) {
            imgs.put(i.getSlotId(), new TechErrorPdfInput.Image(i.getData(), i.getWidth(), i.getHeight()));
        }
        String prepared = displayName(r.getUpdatedById() != null ? r.getUpdatedById() : r.getCreatedById());
        // Geçen ayın değerleri (2026-10-17, 4b): kutucuk farkları ve {d:…} cümleleri — CANLI (geçen ay düzeltildiyse fark da güncel)
        return new TechErrorPdfInput(t, v, ch.getName(), teamName, r.getReportYear(), r.getReportMonth(), prepared, now(), imgs,
                trend(r, t, v), previous, provenance(r, ch));
    }

    /**
     * Kimlik satırları (2026-10-17, 10. sürüm): oluşturan + zamanı, son düzenleyen + zamanı, onay akışlı kanalda onay durumu ve
     * — onay BU içeriği kapsıyorsa (APPROVED ∧ approval_rev = content_rev) — onaylayan + zamanı. İçerik onaydan sonra değiştiyse
     * {@code stale}: eski onaylayan basılmaz (gönderim de bu durumda APPROVAL_STALE ile durur). Adlar o ANKİ görünen ad (silinmiş
     * kullanıcı "—").
     */
    public TechErrorPdfInput.Provenance provenance(TechErrorReport r, TechErrorChannel ch) {
        String approvalState = null;
        String approvedBy = null;
        Instant approvedAt = null;
        if (TechErrorApprovalService.approvalRequired(ch)) {
            long contentRev = r.getContentRev() == null ? 0L : r.getContentRev();
            String st = r.getApprovalState();
            if (st == null) approvalState = "none";
            else if (r.getApprovalRev() != null && contentRev > r.getApprovalRev()) approvalState = "stale";
            else approvalState = st.toLowerCase(Locale.ROOT);
            if ("approved".equals(approvalState)) {
                approvedBy = displayName(r.getApprovalDecidedById());
                approvedAt = r.getApprovalDecidedAt();
            }
        }
        return new TechErrorPdfInput.Provenance(displayName(r.getCreatedById()), r.getCreatedAt(),
                r.getUpdatedById() == null ? null : displayName(r.getUpdatedById()), r.getUpdatedAt(), approvalState, approvedBy, approvedAt);
    }

    /**
     * Otomatik trend verisi (2026-10-15, Faz C): şablonda trend bölümü yoksa hiç sorgu yok; varsa kanalın rapor
     * listesinden (≤ 600 hafif satır) yalnız pencere içindeki ≤ 11 rapor okunur. Okuma hatası FIRLATILIR: eksik veriyle
     * "en az iki ay gerekir" diyen bir PDF üretilmez (bilinmiyor ≠ yok); rapor ekranı hatayı yakalayıp "okunamadı" der.
     */
    public TechErrorTrend trend(TechErrorReport r, Template t, Values v) {
        if (TechErrorTrend.referencedFields(t).isEmpty()) return TechErrorTrend.empty();
        return trend(r, t, v, reportRows(r.getChannelId()));
    }

    /** Kanalın rapor satırları zaten okunduysa (rapor ekranı: komşu raporlar) aynı liste — ikinci okuma yok. */
    public TechErrorTrend trend(TechErrorReport r, Template t, Values v, List<TechErrorReportRepository.Row> channelRows) {
        if (TechErrorTrend.referencedFields(t).isEmpty()) return TechErrorTrend.empty();
        java.time.YearMonth end = java.time.YearMonth.of(r.getReportYear(), r.getReportMonth());
        java.time.YearMonth start = end.minusMonths(TechErrorTrend.MONTHS - 1);
        List<Long> ids = new ArrayList<>();
        for (TechErrorReportRepository.Row row : channelRows) {
            java.time.YearMonth ym = java.time.YearMonth.of(row.getReportYear(), row.getReportMonth());
            if (!row.getId().equals(r.getId()) && !ym.isBefore(start) && ym.isBefore(end)) ids.add(row.getId());
        }
        List<TechErrorReport> others = ids.isEmpty() ? List.of() : reports.findAllById(ids);
        return TechErrorTrend.build(t, v, r.getReportYear(), r.getReportMonth(), others);
    }

    /** Üretilen PDF + girdinin görselsiz hâli (posta gövdesi / dosya adı için; görsel baytları bellekte tutulmaz). */
    public record Rendered(TechErrorPdfInput meta, byte[] pdf) { }

    /** PDF sırası dolu — gönderim "denendi" sayılmaz (FAILED yazılmaz), istemci 429 alır. */
    public static class PdfBusyException extends org.springframework.web.server.ResponseStatusException {
        public PdfBusyException(String reason) {
            super(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, reason);
        }
    }

    /**
     * PDF üretir. Pod başına en çok {@link #PDF_CONCURRENCY} eşzamanlı üretim; izin, görseller (rapor başına ≤ 40 MB)
     * BELLEĞE YÜKLENMEDEN ÖNCE alınır (inceleme 2026-10-13: bekleyen her istek görselleri tutuyordu). Sıra 15 sn'de
     * gelmezse {@link PdfBusyException} (429).
     */
    public Rendered renderPdf(java.util.function.Supplier<TechErrorPdfInput> loader) {
        // İndirme yolu: sınır burada bir kez okunur (gönderim / önizleme / test / onay postası kendi okuduğu TEK değeri verir)
        return renderPdf(loader, mailLimitBytes());
    }

    /** Etkin e-posta sınırı (bayt): Ayarlar'dan kaydedilen değer, yoksa önyükleme değeri. */
    public long mailLimitBytes() {
        return Math.max(1, TechErrorReportSettings.liveInt(appSettings, TechErrorReportSettings.MAX_MAIL_MB, maxMailMb)) * 1024L * 1024L;
    }

    /**
     * PDF'i verilen e-posta sınırına ({@code limitBytes}) göre üretir. Gönderen sınırı BİR kez okuyup buraya verir
     * (inceleme 2026-10-18): izin için 15 sn beklerken ayar değişse merdiven ile gönderenin "sığıyor mu" denetimi farklı
     * değerlere bakardı — PDF yeni sınıra göre küçültülür, gönderen eskisine göre reddederdi (ya da tersi).
     */
    public Rendered renderPdf(java.util.function.Supplier<TechErrorPdfInput> loader, long limitBytes) {
        boolean acquired;
        try {
            acquired = pdfSlots.tryAcquire(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new PdfBusyException(Msg.t("Şu anda başka raporların PDF'i hazırlanıyor. Birkaç saniye sonra tekrar deneyin.",
                    "Other reports' PDFs are being prepared right now. Try again in a few seconds."));
        }
        TechErrorPdfInput in = null;
        try {
            in = loader.get();
            // Görseller tam çözünürlükte saklanır (2026-10-16): PDF e-posta sınırını aşarsa aynı veriden daha düşük gömme
            // katıyla yeniden üretilir — indirilen PDF ile e-posta eki aynı kalır; en düşük katta da sığmazsa gönderen söyler
            long limit = Math.max(1, limitBytes);
            byte[] pdf = null;
            float used = 0;
            for (float scale : TechErrorReportPdfWriter.EMBED_SCALES) {
                pdf = TechErrorReportPdfWriter.render(in, scale);
                used = scale;
                if (pdf.length <= limit || in.images().isEmpty()) break;
            }
            if (used != TechErrorReportPdfWriter.EMBED_SCALES[0]) {
                log.info("Teknik hata raporu PDF'i e-posta sınırı için görseller {}x gömüldü ({}; {} bayt)", used, in.channelName(), pdf.length);
            }
            return new Rendered(in.withoutImages(), pdf);
        } catch (IOException e) {
            log.warn("Teknik hata raporu PDF'i üretilemedi ({}): {}", in == null ? "?" : in.channelName(), e.toString());
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    Msg.t("PDF hazırlanamadı. Sayfayı yenileyip tekrar deneyin; sorun sürerse sistem yöneticisine bildirin.",
                    "The PDF couldn't be prepared. Reload the page and try again; if it keeps happening, tell a system administrator."));
        } finally {
            pdfSlots.release();
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    /** Kullanıcı kimliği → hesabın e-posta adresi (test postası, R13); yoksa / bulunamazsa null. Teslim edilebilirliği çağıran denetler. */
    public String userEmail(Long userId) {
        if (userId == null) return null;
        return users.findById(userId).map(AppUser::getEmail).orElse(null);
    }

    /** Kullanıcı kimliği → görünen ad (yoksa kullanıcı adı); bulunamazsa null. */
    public String displayName(Long userId) {
        if (userId == null) return null;
        return users.findById(userId).map(TechErrorReportService::nameOf).orElse(null);
    }

    /** Toplu ad çözümü (liste uçları — tek sorgu). */
    public Map<Long, String> displayNames(Collection<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        Set<Long> want = new HashSet<>();
        for (Long id : ids) if (id != null) want.add(id);
        if (want.isEmpty()) return out;
        for (AppUser u : users.findAllById(want)) out.put(u.getId(), nameOf(u));
        return out;
    }

    private static String nameOf(AppUser u) {
        return u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
    }

    /**
     * Değerleri yeni şablona taşır: aynı kimlik + AYNI tür (inceleme 2026-10-13 — kimliği yeniden kullanılmış bir alan
     * eski alanın değerini almasın; sayıdan yüzdeye geçen alanın değeri de taşınmaz). Anomali ve metin bölümleri kimlik
     * ve türle (issues / text) eşleşir.
     */
    static Values carry(Values before, Template prev, Template next) {
        Map<String, String> prevTypes = new HashMap<>();
        for (var f : prev.allFields()) prevTypes.put(f.id(), f.type());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var f : next.allFields()) {
            Object v = before.fields().get(f.id());
            if (v != null && f.type().equals(prevTypes.get(f.id()))) fields.put(f.id(), v);
        }
        // Yeni şablonda "Hayır" cevabıyla muaf kalan alanın değeri taşınmaz (önizleme kaybı doğru saysın)
        for (var f : next.allFields()) if (TechErrorTemplate.exempt(f, fields)) fields.remove(f.id());
        Map<String, String> prevKinds = new HashMap<>();
        for (var s : prev.sections()) prevKinds.put(s.id(), s.kind());
        Map<String, List<TechErrorValues.Issue>> issues = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> tables = new LinkedHashMap<>();
        Map<String, TechErrorTemplate.Section> prevSections = new HashMap<>();
        for (var s : prev.sections()) prevSections.put(s.id(), s);
        for (var s : next.sections()) {
            if (!s.kind().equals(prevKinds.get(s.id()))) continue;
            // Konular yeni bölümün sorularına uydurulur (2026-10-18): kapatılan sorunun cevabı düşer, açılan "müşteri etkisi"
            // girilmiş müşteri sayısından çıkarılır (> 0 → Evet, 0 → Hayır); kimlikler aynı kalır (görseller konuyla taşınır)
            if ("issues".equals(s.kind()) && before.issues().containsKey(s.id())) {
                issues.put(s.id(), TechErrorValues.conform(before.issues().get(s.id()), s));
            }
            if (("text".equals(s.kind()) || "summary".equals(s.kind())) && before.texts().containsKey(s.id())) {
                texts.put(s.id(), before.texts().get(s.id()));
            }
            if ("table".equals(s.kind()) && before.tables().containsKey(s.id())) {
                // Hücre: satır ve sütun kimliği yeni tabloda olmalı, sütun türü değişmemiş olmalı
                Map<String, String> prevCols = new HashMap<>();
                for (var c : prevSections.get(s.id()).columns()) prevCols.put(c.id(), c.type());
                Map<String, Map<String, Object>> old = before.tables().get(s.id());
                Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
                for (var r : s.rows()) {
                    Map<String, Object> cells = old.get(r.id());
                    if (cells == null) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (var c : s.columns()) {
                        Object v = cells.get(c.id());
                        if (v != null && c.type().equals(prevCols.get(c.id()))) row.put(c.id(), v);
                    }
                    if (!row.isEmpty()) rows.put(r.id(), row);
                }
                if (!rows.isEmpty()) tables.put(s.id(), rows);
            }
        }
        // O aya özel hedef (2026-10-17, 4b): aynı kimlik, yeni şablonda da hedefi olan yüzde alanı
        Map<String, Object> targets = new LinkedHashMap<>();
        for (var f : next.allFields()) {
            Object tv = before.targets().get(f.id());
            if (tv != null && "percent".equals(f.type()) && f.target() != null && "percent".equals(prevTypes.get(f.id()))) targets.put(f.id(), tv);
        }
        // Kısaltma üssü (2026-10-18): yalnız değeri taşınan sayı yerinde kalır (aynı kimlik + tür; muaf / düşen değerle düşer)
        Values carried = new Values(fields, issues, texts, tables, targets);
        return new Values(fields, issues, texts, tables, targets, TechErrorValues.units(before.units(), next, carried));
    }

    /** Liste başlığı için kopyanın başlık kalıbı + dili (sütun sınırına sığdırılır). */
    static void stampTitle(TechErrorReport r, Template t) {
        String title = t.title() == null ? "" : t.title();
        r.setTitlePattern(title.length() > 250 ? title.substring(0, 250) : title);
        r.setTemplateLang(t.lang());
    }

    private static void requireVersion(TechErrorReport r, Long expected) {
        if (expected == null) {
            throw new FieldValidationException("version", Msg.t(
                    "Raporun okunan sürümü gönderilmedi. Sayfayı yenileyip tekrar deneyin.",
                    "The report version you read wasn't sent. Reload the page and try again."));
        }
        if (!expected.equals(r.getVersion())) throw new OptimisticLockingFailureException("tech_error_report version");
    }

    private static int countValues(Values v) {
        // O aya özel hedefler de kaybolabilir (yeni şablon alanın hedefini kaldırdı — inceleme 2026-10-17)
        int n = v.fields().size() + v.texts().size() + v.targets().size();
        // Konu + (2026-10-18) cevapları: tarih, saat aralığı, kesinti, Kesinti ID, görsel açıklamaları — sorusu kaldırılan
        // şablon uygulanınca kaybolanlar da sayılsın (müşteri etkisi sayılmaz: eski raporda müşteri sayısından çıkarılır)
        for (var l : v.issues().values()) {
            for (var i : l) {
                n += 1 + (i.date() != null ? 1 : 0) + (i.start() != null ? 1 : 0) + (i.outage() != null ? 1 : 0)
                        + (i.outageId() != null ? 1 : 0) + i.captions().size();
            }
        }
        for (var t : v.tables().values()) for (var r : t.values()) n += r.size();
        return n;
    }

    static String channelName(String raw) {
        String n = TechErrorTemplate.line(raw);
        if (n.isEmpty()) throw new FieldValidationException("name", Msg.t("Kanal adı boş olamaz.", "The channel name can't be empty."));
        if (n.length() > MAX_NAME) throw new FieldValidationException("name", Msg.t(
                "Kanal adı en çok 120 karakter olabilir.", "The channel name can be at most 120 characters."));
        return n;
    }

    static String nameKey(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.forLanguageTag("tr"));
    }

    static String description(String raw) {
        String d = TechErrorTemplate.line(raw);
        if (d.length() > MAX_DESCRIPTION) throw new FieldValidationException("description", Msg.t(
                "Açıklama en çok 500 karakter olabilir.", "The description can be at most 500 characters."));
        return d.isEmpty() ? null : d;
    }

    static String fileName(String raw) {
        if (raw == null) return null;
        String n = raw.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = TechErrorTemplate.line(n);
        if (n.isEmpty()) return null;
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static CodedConflictException channelExists(String name) {
        return new CodedConflictException("CHANNEL_EXISTS", Msg.t(
                "Bu takımda “" + name + "” adlı bir kanal zaten var. Başka bir ad seçin.",
                "This team already has a channel named “" + name + "”. Choose another name."));
    }

    private static CodedConflictException reportExists(Long id) {
        Map<String, Object> extra = new HashMap<>();
        if (id != null) extra.put("report_id", id);
        return new CodedConflictException("REPORT_EXISTS", Msg.t(
                "Bu kanalın bu ay için raporu zaten açılmış; mevcut raporu açabilirsiniz.",
                "A report for this channel and month already exists; you can open the existing one."), extra);
    }

    static NoSuchElementException channelNotFound() {
        return new NoSuchElementException(Msg.t(
                "Kanal bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                "The channel wasn't found; it may have been deleted or you can't see it. Refresh the list."));
    }

    static NoSuchElementException reportNotFound() {
        return new NoSuchElementException(Msg.t(
                "Rapor bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                "The report wasn't found; it may have been deleted or you can't see it. Refresh the list."));
    }
}
