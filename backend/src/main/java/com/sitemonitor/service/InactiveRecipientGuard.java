package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.AppUserRepository;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pasif kullanıcıya bildirim GİTMEZ (2026-10-02, kullanıcı kararı: "pasif kullanıcıya hiçbir eskalasyon / alarm /
 * bildirim gitmemeli"). İki katman, tek önbellek:
 *
 * <ul>
 *   <li><b>Kişi düzeyi</b> — {@code user_id}'si pasif bir kullanıcıya bağlı eskalasyon kişisi alıcı listesinden düşer
 *       ({@link #withoutInactive}); e-postası da Teams/Slack webhook'u da gitmez. Alarm / yeniden uyarı / çözüm / elle
 *       gönderim / fırtına / haftalık rapor / 7/24 blokları kişi listesini çözdüğü yerde bunu çağırır. Zamana bağlı
 *       eskalasyon adımı pasif kişiyi {@code ESCALATION_STEP + SKIPPED: pasif kullanıcı} iziyle atlar.</li>
 *   <li><b>Merkezî e-posta ağı</b> — her giden e-posta {@code EmailNotificationService.doSend}'den geçer;
 *       {@link #filter(MimeMessage)} YALNIZ pasif kullanıcılara ait adresleri (aynı adresi aktif bir kullanıcı da
 *       kullanıyorsa düşmez; takım kutusu / grup adresi gibi kullanıcıya ait olmayan adreslere dokunulmaz) TO/CC/BCC'den
 *       çıkarır. Hepsi düşerse gönderim yapılmaz ve durum {@link #STATUS_SKIPPED} döner — çağıranın
 *       {@code notification_logs} satırı "neden gitmedi"yi söyler; kısmi düşüş WARN satırı bırakır.</li>
 * </ul>
 *
 * <p><b>Maliyet.</b> Pasif kullanıcıların kimliği + "yalnız pasife ait" adresleri {@code site.monitor.notify.inactive-cache-ms}
 * (varsayılan 60 sn) boyunca bellekte tutulur — e-posta / kişi başına sorgu YOK (tazelemede iki hafif projeksiyon sorgusu).
 * Aktiflik değişince bu pod'da {@link #evict()} (UserService) anında tazeler; diğer pod'lar en geç TTL sonra görür.
 *
 * <p><b>Hiç pasif kullanıcı yokken</b> her çağrı aynı listeyi / mesajı DEĞİŞTİRMEDEN döner — bildirim yolları bayt bayt
 * bugünküyle aynıdır. Okuma hatası = süzgeç yok (fail-open): bir DB hıçkırığı alarm bildirimini düşürmemeli.
 */
@Slf4j
@Service
public class InactiveRecipientGuard {

    /** Tüm alıcılar pasif olduğu için GÖNDERİLMEYEN bildirimin durumu ({@code SKIPPED: alıcı yok} deseni). */
    public static final String STATUS_SKIPPED = "SKIPPED: pasif kullanıcı";
    /** Eskalasyon adımı atlama nedeni (günlükte {@code SKIPPED: pasif kullanıcı}). */
    public static final String SKIP_REASON = "pasif kullanıcı";
    /** IN sorgusunun dilim boyutu. */
    static final int CHUNK = 500;

    private final AppUserRepository userRepo;

    @Value("${site.monitor.notify.inactive-cache-ms:60000}")
    private long cacheMs = 60_000L;

    /** Tazeleme anı (ms) + pasif kimlikler + yalnız pasife ait adresler (küçük harf, kırpılmış). */
    record Snapshot(Set<Long> ids, Set<String> emails, long atMs) {
        static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of(), 0L);
        boolean none() { return ids.isEmpty() && emails.isEmpty(); }
    }

    private volatile Snapshot snapshot;

    public InactiveRecipientGuard(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /** Önbelleği düşürür — sonraki çağrı DB'den tazeler (aktiflik değişti). */
    public void evict() {
        snapshot = null;
    }

    Snapshot snapshot() {
        Snapshot s = snapshot;
        long now = System.currentTimeMillis();
        if (s != null && now - s.atMs() < cacheMs) return s;
        synchronized (this) {
            s = snapshot;
            if (s != null && now - s.atMs() < cacheMs) return s;
            Snapshot fresh = load(now, s);
            snapshot = fresh;
            return fresh;
        }
    }

    private Snapshot load(long now, Snapshot previous) {
        try {
            Set<Long> ids = new HashSet<>();
            Set<String> emails = new HashSet<>();
            List<Object[]> rows = userRepo.findInactiveIdsAndEmails();
            if (rows != null) {
                for (Object[] r : rows) {
                    if (r == null || r.length < 2) continue;
                    if (r[0] instanceof Number n) ids.add(n.longValue());
                    String e = norm(r[1] == null ? null : r[1].toString());
                    if (e != null) emails.add(e);
                }
            }
            if (!emails.isEmpty()) {
                List<String> all = new ArrayList<>(emails);
                for (int i = 0; i < all.size(); i += CHUNK) {
                    List<String> shared = userRepo.findActiveEmailsLowerIn(all.subList(i, Math.min(all.size(), i + CHUNK)));
                    if (shared != null) for (String a : shared) { String n = norm(a); if (n != null) emails.remove(n); }
                }
            }
            return new Snapshot(Set.copyOf(ids), Set.copyOf(emails), now);
        } catch (Exception e) {
            log.warn("Pasif kullanıcı listesi okunamadı — bildirim süzgeci bu tur uygulanmıyor: {}", e.toString());
            // Bir önceki anlık görüntü varsa onunla sür; yoksa süzgeçsiz (fail-open). Kısa süre sonra yeniden denenir.
            Snapshot keep = previous != null ? previous : Snapshot.EMPTY;
            return new Snapshot(keep.ids(), keep.emails(), now - Math.max(0L, cacheMs - 5_000L));
        }
    }

    static String norm(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase(Locale.ROOT);
        return e.isEmpty() ? null : e;
    }

    /** Kullanıcı (kimlik) pasif mi? null kimlik → false. */
    public boolean isInactiveUser(Long userId) {
        return userId != null && snapshot().ids().contains(userId);
    }

    /** Kişi pasif bir kullanıcıya bağlı mı ({@code user_id})? Bağsız (serbest metin) kişi → false. */
    public boolean isInactiveContact(EscalationContact c) {
        return c != null && isInactiveUser(c.getUserId());
    }

    /** Adres YALNIZ pasif kullanıcılara mı ait (aktif biri de kullanıyorsa false)? */
    public boolean isInactiveOnlyEmail(String email) {
        String e = norm(email);
        return e != null && snapshot().emails().contains(e);
    }

    /**
     * Pasif kullanıcıya bağlı kişileri listeden çıkarır. Düşen yoksa AYNI liste nesnesi döner (bayt bayt bugünkü yol);
     * düşen varsa WARN satırı + {@link FilteredContacts} (düşen sayısını taşır — "alıcı yok" yerine "pasif kullanıcı" izi).
     *
     * @param where günlük satırı için bağlam (ör. "alarm olay=12")
     */
    public List<EscalationContact> withoutInactive(List<EscalationContact> contacts, String where) {
        if (contacts == null || contacts.isEmpty()) return contacts;
        Snapshot s = snapshot();
        if (s.ids().isEmpty()) return contacts;
        FilteredContacts out = null;
        List<String> dropped = null;
        for (int i = 0; i < contacts.size(); i++) {
            EscalationContact c = contacts.get(i);
            boolean inactive = c != null && c.getUserId() != null && s.ids().contains(c.getUserId());
            if (inactive && out == null) {
                out = new FilteredContacts(contacts.size());
                for (int j = 0; j < i; j++) out.add(contacts.get(j));
                dropped = new ArrayList<>();
            }
            if (inactive) {
                out.dropped++;
                dropped.add("#" + c.getId() + " (user " + c.getUserId() + ")");
            } else if (out != null) {
                out.add(c);
            }
        }
        if (out == null) return contacts;
        log.warn("Pasif kullanıcıya bağlı {} eskalasyon kişisi alıcılardan çıkarıldı ({}): {}",
                out.dropped, where == null ? "-" : where, dropped);
        return out;
    }

    /** Listeden pasif kişi düşmüş mü — {@link #withoutInactive} çıktısının taşıdığı sayı (yoksa 0). */
    public static int droppedCount(List<EscalationContact> contacts) {
        return contacts instanceof FilteredContacts f ? f.dropped : 0;
    }

    /** Pasif kişi düşürülmüş kişi listesi — düşen sayısını taşır (liste davranışı ArrayList ile aynı). */
    public static final class FilteredContacts extends ArrayList<EscalationContact> {
        private int dropped;
        FilteredContacts(int cap) { super(cap); }
        public int dropped() { return dropped; }
    }

    /** {@link #filter(MimeMessage)} sonucu: {@code skipStatus} doluysa gönderim YAPILMAZ. */
    public record MailFilterResult(String skipStatus, int dropped, String remainingTo) {
        static final MailFilterResult NONE = new MailFilterResult(null, 0, null);
    }

    /**
     * MERKEZÎ e-posta ağı: mesajın TO/CC/BCC alıcılarından yalnız pasife ait adresleri çıkarır. Pasif adres yoksa mesaja
     * dokunulmaz ({@link MailFilterResult#NONE}). Hepsi düşerse {@code skipStatus = STATUS_SKIPPED}. Hata = dokunma.
     */
    public MailFilterResult filter(MimeMessage msg) {
        if (msg == null) return MailFilterResult.NONE;
        Snapshot s = snapshot();
        if (s.emails().isEmpty()) return MailFilterResult.NONE;
        try {
            Message.RecipientType[] types = { Message.RecipientType.TO, Message.RecipientType.CC, Message.RecipientType.BCC };
            List<Address[]> kept = new ArrayList<>(3);
            int dropped = 0, remaining = 0;
            List<String> droppedAddrs = new ArrayList<>();
            List<String> remainingTo = new ArrayList<>();
            for (Message.RecipientType type : types) {
                Address[] in = msg.getRecipients(type);
                if (in == null) { kept.add(null); continue; }
                List<Address> keep = new ArrayList<>(in.length);
                for (Address a : in) {
                    String addr = a instanceof InternetAddress ia ? ia.getAddress() : String.valueOf(a);
                    String n = norm(addr);
                    if (n != null && s.emails().contains(n)) {
                        dropped++;
                        droppedAddrs.add(addr);
                    } else {
                        keep.add(a);
                        remaining++;
                        if (type == Message.RecipientType.TO) remainingTo.add(addr);
                    }
                }
                kept.add(keep.toArray(new Address[0]));
            }
            if (dropped == 0) return MailFilterResult.NONE;
            String subject = safeSubject(msg);
            if (remaining == 0) {
                log.warn("E-posta GÖNDERİLMEDİ — tüm alıcılar pasif kullanıcı ({} adres: {}) | KONU={}",
                        dropped, SecretMask.maskEmails(droppedAddrs), subject);
                return new MailFilterResult(STATUS_SKIPPED, dropped, null);
            }
            for (int i = 0; i < types.length; i++) {
                Address[] k = kept.get(i);
                if (k == null) continue;
                msg.setRecipients(types[i], k.length == 0 ? null : k);
            }
            log.warn("Pasif kullanıcıya ait {} adres alıcılardan çıkarıldı ({}), kalan {} alıcı | KONU={}",
                    dropped, SecretMask.maskEmails(droppedAddrs), remaining, subject);
            return new MailFilterResult(null, dropped, String.join(",", remainingTo));
        } catch (Exception e) {
            log.warn("Pasif alıcı süzgeci uygulanamadı — e-posta süzgeçsiz gidiyor: {}", e.toString());
            return MailFilterResult.NONE;
        }
    }

    private static String safeSubject(MimeMessage msg) {
        try { return msg.getSubject(); } catch (Exception e) { return "?"; }
    }

    /** Test kancası: TTL (ms). */
    void setCacheMs(long ms) {
        this.cacheMs = ms;
    }
}
