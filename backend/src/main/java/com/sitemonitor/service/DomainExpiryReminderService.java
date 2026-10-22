package com.sitemonitor.service;

import com.sitemonitor.model.DomainExpiryReminder;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.DomainExpiryReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Alan adı süre-bitişi hatırlatmaları (2026-09-22, denetim madde E / bulgu F1).
 *
 * <p>Sözleşme: izlemenin {@code thresholdsCsv} eşiklerinden (vars. 60,30,14,7,3,1) kalan günün altına indiği her eşik
 * için <b>bir kez</b> e-posta + push. Aynı turda birden çok eşik birden aşılmışsa (izleme 5 gün kala kurulduysa 60/30/
 * 14/7 aynı anda "aşılmış" olur) yalnız <b>en sıkı</b> eşik gönderilir, diğerleri COVERED olarak işaretlenir — dört mail
 * peş peşe gürültü olurdu. Bitiş tarihi ileri gidince (yenileme) seri sıfırdan başlar. Açık DOMAINMON_EXPIRY alarmından
 * BAĞIMSIZ: alarm "eşiğin altındasın" der ve bir kez açılır, hatırlatma ise takvimdir.
 *
 * <p>Kanallar izlemenin kendi tercihini izler: {@code notifyEmail} kapalıysa mail yok, {@code notifyWebhook} kapalıysa
 * push yok; ikisi de kapalıysa kayıt SKIPPED_CHANNELS_OFF ile yine yazılır (bir daha denenmez — "kapalı" kapalıdır).
 * Alıcılar sertifika/izleme alarmlarıyla AYNI zincir: bildirim grubu → takım varsayılan grubu → takım e-postası.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainExpiryReminderService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final String DEFAULT_THRESHOLDS = "60,30,14,7,3,1";
    public static final String PUSH_TRIGGER = "DOMAIN_EXPIRY_REMINDER";

    private final DomainExpiryReminderRepository repo;
    private final EscalationService escalation;
    private final EmailNotificationService email;
    private final UserPushService push;
    private final ActivityLogService activityLog;

    /**
     * MP-45 (2026-10-23, çok pod R2a · D2) — hatırlatma GÖNDERİMDEN ÖNCE sahiplenilir. Eskiden "var mı → gönder → kaydet"
     * idi: 16:00 kritik alan adı turu ile saatlik alan adı turu (lider pod'da, AYRI kilitlerle) ya da devir anında eski ve yeni
     * lider aynı izlemeyi aynı anda değerlendirince ikisi de "gönderilmemiş" görüp İKİ e-posta atıyordu. Artık eşik satırı
     * {@value #STATUS_SENDING} olarak önce eklenir (tekil indeks {@code ux_der_mon_exp_thr} — {@code DeferredIndexBuilder}
     * kataloğu, PostgreSQL, YALNIZ {@code claim_token} dolu = bu sürümün satırları); ihlal = "başka tur gönderiyor / gönderdi"
     * → bu tur göndermez. Sonuç gönderimden sonra koşullu
     * yazılır. Hiçbir şey gönderilmeden düşen sahiplenme geri verilir (sonraki tur yeniden dener); gönderim sırasında ölen
     * pod'un satırı {@link #STALE_SENDING_MS} sonra {@value #STATUS_INTERRUPTED} olur — YENİDEN gönderilmez (en çok bir kez).
     */
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    static final long STALE_SENDING_MS = 60L * 60_000L;

    /** Çağıran bir işlemin içindeyse sahiplenme AYRI işlemde eklenir (PostgreSQL'de tekil ihlal çağıranın işlemini bozardı). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * Aynı pod'da aynı izleme × bitiş şu an değerlendiriliyor mu — iki tur aynı JVM'deyken (kritik + saatlik tur, ikisi de
     * liderde) tekil indeks henüz kurulmamış olsa da (açılıştan sonra ertelenmiş kurulum, H2) ikinci tur birinciyi beklemeden
     * atlar. Küme genelinde son söz tekil indeksindir.
     */
    private final java.util.Set<String> deciding = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Test dikişi: "var mı" denetimi ile sahiplenme arası (yarış testinde iki tur aynı anda buraya varır). */
    volatile Runnable beforeClaim;

    /** Test dikişi: saat (epoch ms). */
    java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** CSV eşikleri → azalan sıralı, tekil, pozitif tam sayılar. Bozuk/boş girdi → varsayılan liste. */
    public static List<Integer> parseThresholds(String csv) {
        TreeSet<Integer> set = new TreeSet<>(java.util.Comparator.reverseOrder());
        String src = csv == null || csv.isBlank() ? DEFAULT_THRESHOLDS : csv;
        for (String p : src.split("[,;\\s]+")) {
            try { int v = Integer.parseInt(p.trim()); if (v >= 0) set.add(v); } catch (NumberFormatException ignored) { /* bozuk parça atlanır */ }
        }
        if (set.isEmpty()) return parseThresholds(DEFAULT_THRESHOLDS);
        return new ArrayList<>(set);
    }

    /**
     * Kontrol sonucunu değerlendirir; gerekiyorsa hatırlatma gönderir. Hata sweep'i asla kırmaz.
     *
     * @return gönderilen hatırlatmanın kaydı (yoksa null)
     */
    public DomainExpiryReminder evaluate(DomainMonitor m, Map<String, Object> result) {
        try {
            if (m == null || m.getId() == null || result == null) return null;
            // Yarıda kalmış sahiplenmeler eşik denetiminden BAĞIMSIZ kapanır (inceleme J-3): yenilenmiş (yeni bitiş) ya da eşiği
            // değişmiş serinin SENDING satırı da INTERRUPTED olur. İzleme başına sınırlı tek dizinli okuma.
            expireStaleSending(m);
            if (!Boolean.TRUE.equals(m.getActive())) return null;
            Integer days = result.get("days_remaining") instanceof Number n ? n.intValue() : null;
            String expiry = result.get("expiry_date") instanceof String s ? s : null;
            if (days == null || expiry == null || expiry.isBlank()) return null;   // veri yok → hatırlatma yok (UNKNOWN alarmı ayrı)

            List<Integer> crossed = new ArrayList<>();
            for (Integer t : parseThresholds(m.getThresholdsCsv())) if (days <= t) crossed.add(t);
            if (crossed.isEmpty()) return null;
            String key = m.getId() + "|" + expiry;
            if (!deciding.add(key)) {
                log.debug("Alan adı hatırlatması bu pod'da başka turda değerlendiriliyor: {} — atlandı", m.getDomain());
                return null;
            }
            try {
                // Daha SIKI bir eşiğin satırı varsa (gönderildi, gönderiliyor ya da yarıda kaldı) daha GEVŞEK eşik artık gönderilmez:
                // başka pod sıkı eşiği sahiplenip gevşek eşikleri henüz COVERED yazmadıysa bu tur "30 gün" hatırlatmasını "7 gün"
                // hatırlatmasının ardından atardı.
                List<Integer> unsent = new ArrayList<>();
                Integer tightestRecorded = null;
                for (Integer t : crossed) {
                    if (repo.existsByMonitorIdAndExpiryDateAndThresholdDays(m.getId(), expiry, t)) tightestRecorded = t;
                    else unsent.add(t);
                }
                if (tightestRecorded != null) {
                    final int floor = tightestRecorded;
                    unsent.removeIf(t -> t > floor);
                }
                if (unsent.isEmpty()) return null;

                int tightest = unsent.get(unsent.size() - 1);   // azalan sıralı → sonuncusu en küçük
                String now = ISO.format(Instant.ofEpochMilli(clock.getAsLong()));
                Runnable hook = beforeClaim;
                if (hook != null) hook.run();
                DomainExpiryReminder claim = row(m, expiry, tightest, days, now, STATUS_SENDING);
                if (!insertClaim(claim)) {
                    log.info("Alan adı hatırlatması başka turda gönderiliyor / gönderildi: {} eşik={} — bu tur göndermedi",
                            m.getDomain(), tightest);
                    return null;
                }
                // Daha gevşek eşikler hemen COVERED (gönderimden ÖNCE): gönderim sırasında pod ölse bile sonraki tur sıkı eşikten
                // sonra gevşek eşiğin hatırlatmasını atmaz.
                for (Integer t : unsent) {
                    if (t == tightest) continue;
                    insertClaim(row(m, expiry, t, days, now, "COVERED"));   // ihlal = zaten var
                }
                return send(m, result, days, expiry, tightest, claim);
            } finally {
                deciding.remove(key);
            }
        } catch (Exception e) {
            log.warn("Alan adı hatırlatması değerlendirilemedi: {} — {}", m != null ? m.getDomain() : "?", e.getMessage());
            return null;
        }
    }

    private static DomainExpiryReminder row(DomainMonitor m, String expiry, int threshold, int days, String now, String status) {
        DomainExpiryReminder r = new DomainExpiryReminder();
        r.setMonitorId(m.getId()); r.setExpiryDate(expiry); r.setThresholdDays(threshold);
        r.setDaysRemaining(days); r.setSentAt(now); r.setStatus(status);
        r.setClaimToken(java.util.UUID.randomUUID().toString());   // 36 karakter; tekil indeks yalnız dolu satırları kapsar
        return r;
    }

    /**
     * Eşik satırını ekler; {@code false} = aynı (izleme, bitiş, eşik) satırı zaten var (tekil ihlal — başka tur) ya da ekleme
     * düştü (bu tur göndermez; sonraki tur yeniden dener).
     */
    boolean insertClaim(DomainExpiryReminder r) {
        try {
            org.springframework.transaction.PlatformTransactionManager tm = txManager;
            if (tm != null && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionTemplate tt = new org.springframework.transaction.support.TransactionTemplate(tm);
                tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tt.executeWithoutResult(st -> repo.save(r));
            } else {
                repo.save(r);
            }
            return true;
        } catch (Exception e) {
            if (UserPushService.isUniqueViolation(e)) return false;
            log.warn("Alan adı hatırlatması eşik satırı yazılamadı (izleme {}, eşik {}): {} — bu tur göndermedi",
                    r.getMonitorId(), r.getThresholdDays(), e.toString());
            return false;
        }
    }

    /**
     * Bu izlemenin {@link #STALE_SENDING_MS}'den eski {@value #STATUS_SENDING} satırları (gönderirken ölen pod) →
     * {@value #STATUS_INTERRUPTED}, YENİDEN gönderilmez (posta gitmiş olabilir). Koşullu: iki pod aynı satırı işaretlerse yalnız
     * biri yazar ve etkinlik satırını o atar.
     */
    private void expireStaleSending(DomainMonitor m) {
        try {
            List<DomainExpiryReminder> rows = repo.findTop20ByMonitorIdAndStatusOrderBySentAtAsc(m.getId(), STATUS_SENDING);
            if (rows == null || rows.isEmpty()) return;
            String cutoff = ISO.format(Instant.ofEpochMilli(clock.getAsLong() - STALE_SENDING_MS));
            for (DomainExpiryReminder r : rows) {
                if (r.getId() == null || r.getSentAt() == null || r.getSentAt().compareTo(cutoff) >= 0) continue;
                if (repo.markIfStatus(r.getId(), STATUS_SENDING, STATUS_INTERRUPTED) != 1) continue;
                log.warn("Alan adı hatırlatması yarıda kalmış (sahiplenme {}): {} eşik={} — sonuç bilinmiyor, yeniden gönderilmedi",
                        r.getSentAt(), m.getDomain(), r.getThresholdDays());
                activityLog.recordLifecycle(ActivityLogService.DOMAIN, m.getId(), m.getName(), m.getDomain(), m.getTeamId(),
                        "EXPIRY_REMINDER", "scheduler", "eşik " + r.getThresholdDays()
                                + " gün · yarıda kaldı (gönderen pod durdu; sonuç bilinmiyor, yeniden gönderilmedi)");
            }
        } catch (Exception e) {
            log.debug("Yarıda kalmış alan adı hatırlatmaları okunamadı ({}): {}", m.getDomain(), e.getMessage());
        }
    }

    private DomainExpiryReminder send(DomainMonitor m, Map<String, Object> result, int days, String expiry, int threshold,
                                      DomainExpiryReminder r) {
        boolean mailOn = !Boolean.FALSE.equals(m.getNotifyEmail());
        boolean pushOn = !Boolean.FALSE.equals(m.getNotifyWebhook());

        List<String> recipients;
        try {
            recipients = mailOn ? escalation.teamEmailsForMonitor(m.getTeamId(), m.getNotificationGroupId()) : List.of();
        } catch (RuntimeException e) {
            // Hiçbir şey gönderilmedi: sahiplenme geri verilir, sonraki tur yeniden dener.
            releaseQuietly(r);
            throw e;
        }
        if (!mailOn && !pushOn) {
            r.setStatus("SKIPPED_CHANNELS_OFF");
        } else if (mailOn && recipients.isEmpty() && !pushOn) {
            r.setStatus("SKIPPED_NO_RECIPIENT");
        } else {
            // Bir kanala teslim BAŞLADIYSA (posta servisi ya da push kuyruğu çağrıldı) sahiplenme geri VERİLMEZ — sonraki tur
            // ikinci postayı atardı; başlamadan düşen hata (gövde kurulamadı) sahiplenmeyi geri verir, sonraki tur yeniden dener.
            boolean handedOver = false;
            try {
                String level = days < 0 || days <= (m.getCriticalDays() != null ? m.getCriticalDays() : 7) ? "CRITICAL"
                        : days <= (m.getWarningDays() != null ? m.getWarningDays() : 30) ? "WARNING" : "INFO";
                String registrar = result.get("registrar") instanceof String s ? s : null;
                String name = m.getName() != null && !m.getName().isBlank() ? m.getName() : m.getDomain();
                if (mailOn && !recipients.isEmpty()) {
                    String subject = "[Site Monitor] " + name + " — alan adı bitişine " + Math.max(days, 0) + " gün (hatırlatma · " + threshold + " gün eşiği)";
                    String html = email.buildDomainExpiryReminderHtml(name, m.getDomain(), days, expiry, threshold, registrar, level, m.getId());
                    handedOver = true;
                    String status = email.sendHtml(recipients.toArray(new String[0]), null, subject, html, null);
                    log.info("Alan adı hatırlatması: {} eşik={} kalan={} alıcı={} durum={}", m.getDomain(), threshold, days, SecretMask.maskEmails(recipients), status);
                    r.setRecipients(String.join(",", recipients));
                }
                if (pushOn) {
                    String msg = name + " alan adının kaydı " + (days < 0 ? Math.abs(days) + " gün önce doldu" : days + " gün sonra doluyor")
                            + " (" + threshold + " gün eşiği hatırlatması)";
                    // İngilizce eşi (2026-10-04, öneri 5) — push kişinin dilinde; Türkçe metin bugünküyle aynı.
                    String msgEn = "Domain registration of " + name + (days < 0 ? " expired " + Math.abs(days) + " days ago"
                            : " expires in " + days + " days") + " (" + threshold + "-day threshold reminder)";
                    handedOver = true;
                    Map<String, Object> pr = push.enqueueTeamNoticeLocalized(m.getTeamId(), PUSH_TRIGGER, level, "DOMAIN", name,
                            new UserPushService.LocalizedText(msg, msgEn),
                            "domain-reminder:" + m.getId() + ":" + expiry + ":" + threshold, java.util.Set.of());
                    r.setPushQueued(pr.get("queued") instanceof Number n ? n.intValue() : 0);
                }
            } catch (RuntimeException e) {
                if (!handedOver) {
                    releaseQuietly(r);
                    throw e;
                }
                log.warn("Alan adı hatırlatması teslimden sonra hata verdi ({} eşik={}): {} — yeniden gönderilmez",
                        m.getDomain(), threshold, e.toString());
            }
            r.setStatus("SENT");
        }
        finish(r);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, m.getId(), m.getName(), m.getDomain(), m.getTeamId(),
                "EXPIRY_REMINDER", "scheduler", "eşik " + threshold + " gün · kalan " + days + " gün · " + r.getStatus());
        return r;
    }

    /** Sahiplenilmiş satıra sonucu yazar (yalnız hâlâ bu turun {@value #STATUS_SENDING} satırıysa). */
    private void finish(DomainExpiryReminder r) {
        try {
            if (r.getId() != null && repo.finishClaim(r.getId(), STATUS_SENDING, r.getStatus(), r.getRecipients(), r.getPushQueued()) == 0) {
                log.warn("Alan adı hatırlatması sonucu yazılamadı (satır {} artık {} değil) — durum: {}", r.getId(), STATUS_SENDING,
                        r.getStatus());
            }
        } catch (Exception e) {
            log.warn("Alan adı hatırlatması sonucu yazılamadı (satır {}): {} — satır {} kaldı, 1 saat sonra yarıda kaldı sayılır",
                    r.getId(), e.getMessage(), STATUS_SENDING);
        }
    }

    private void releaseQuietly(DomainExpiryReminder r) {
        try {
            if (r.getId() != null) repo.releaseClaim(r.getId(), STATUS_SENDING);
        } catch (Exception e) {
            log.warn("Alan adı hatırlatması sahiplenmesi geri verilemedi (satır {}): {}", r.getId(), e.getMessage());
        }
    }

    /** Detay penceresi: gönderilen hatırlatmalar (yeni önce). */
    public List<Map<String, Object>> history(Long monitorId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DomainExpiryReminder r : repo.findTop50ByMonitorIdOrderBySentAtDesc(monitorId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId()); row.put("threshold_days", r.getThresholdDays()); row.put("days_remaining", r.getDaysRemaining());
            row.put("expiry_date", r.getExpiryDate()); row.put("status", r.getStatus()); row.put("recipients", r.getRecipients());
            row.put("push_queued", r.getPushQueued()); row.put("sent_at", r.getSentAt());
            out.add(row);
        }
        return out;
    }
}
