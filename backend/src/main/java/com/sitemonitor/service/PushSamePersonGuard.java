package com.sitemonitor.service;

import com.sitemonitor.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aynı kişinin iki hesabına İKİ push gitmesin (2026-10-21, prod alarm #628 "OCPAA - Servis Yanıt Süresi Anomalisi"): sekiz
 * alıcılı tek teslimatta aynı kişi iki hesapla ("… MP60146" ve "… N60146") listedeydi; ağ geçidi iki sicili aynı telefona
 * eşlediği için kişi aynı push'u iki kez gördü. Alıcı kurucuları yalnız KULLANICI ADINA göre tekilleştiriyordu.
 *
 * <p><b>Kural (kullanıcı kararı):</b> e-posta adresi AYNI olan AKTİF hesaplar aynı kişidir ve TEK push alır.
 * <ul>
 *   <li>Adres kırpılıp {@code toLowerCase(Locale.ROOT)} ile karşılaştırılır; boş / null adresli hesap hiçbir zaman birleşmez;
 *   pasif hesap hiç katılmaz (sorgu yalnız aktifleri döndürür).</li>
 *   <li>Seçim (deterministik): (0) bu bildirimin AYNISINI (aynı yinelenmezlik anahtarı) zaten almış bir hesap — o zaman
 *   teslimattaki diğer hesaplar yazılmaz, kişi bu bildirimi almıştır (teslimat dışındaki kardeş hesap da sayılır: iki takım /
 *   nöbetçi + takım / müdür + takım yolları); (1) aynı MANTIKSAL olay için satırı olan hesap (önce SENT, sonra herhangi bir
 *   satır — çözüm / eskalasyon / günlük tekrar / fırtına üyesi açılışı alan hesaba gider, {@code enqueueResolve} simetrisi
 *   korunur); (2) en son giriş yapan ({@code last_login_at}); (3) en küçük kimlik.</li>
 *   <li>Seçilen hesabın KENDİ tercihleri (opt-out, seviye, susturma, aile, sessiz saat, dil, saat tavanı) bugünkü gibi
 *   uygulanır. Diğerleri {@value #SKIPPED_SAME_PERSON} satırı alır (satırda seçilen hesabın kullanıcı adı — e-posta ASLA);
 *   bu satırlar saat tavanına sayılmaz. Bilinçli sonuç: seçilen hesap push istemiyorsa (opt-out) kişi push almaz — diğer
 *   hesap yine {@value #SKIPPED_SAME_PERSON} yazılır (kişi başına tek karar).</li>
 * </ul>
 *
 * <p>Kodla giriş push'u ({@code UserPushService.sendDirect}) bu kurala TABİ DEĞİLDİR: kod onu isteyen hesaba gider.
 *
 * <p>Maliyet: teslimat başına bir hafif izdüşüm sorgusu (aynı e-postayı paylaşan aktif hesaplar); önceki satır kanıtı yalnız
 * gerçekten birden çok hesabı olan bir kişi varsa okunur. Sorgu düşerse birleştirme YAPILMAZ (bugünkü davranış — push
 * kaybolmaz, en kötü ihtimalle eski gibi iki kez gider).
 */
@Slf4j
@Service
public class PushSamePersonGuard {

    /** Aynı kişiye başka hesapla gönderildi — teslimat satırı durumu ({@code user_push_deliveries.status}). */
    public static final String SKIPPED_SAME_PERSON = "SKIPPED_SAME_PERSON";

    private final AppUserRepository userRepo;

    public PushSamePersonGuard(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /** Bir kişinin bir hesabı (aktif, e-postalı). */
    public record Account(Long id, String username, String email, Instant lastLogin) { }

    /**
     * Bir hesabın bu bildirim için önceki satır kanıtı. {@code exact}: bu bildirimin AYNISININ satırı var (aynı anahtar);
     * {@code sent}: aynı mantıksal olay için SENT satırı; {@code any}: aynı mantıksal olay için herhangi bir satırı.
     * {@value #SKIPPED_SAME_PERSON} satırları kanıt DEĞİLDİR (yalnız asıl alıcı sayılır).
     */
    public record Evidence(boolean exact, boolean sent, boolean any) {
        public static final Evidence NONE = new Evidence(false, false, false);
        public Evidence or(Evidence o) {
            return o == null ? this : new Evidence(exact || o.exact, sent || o.sent, any || o.any);
        }
    }

    /** Kişinin hesaplarının kullanıcı adları → kanıt (anahtar {@link #key} biçiminde). Hata = kanıt yok. */
    @FunctionalInterface
    public interface Prior {
        Map<String, Evidence> of(Collection<String> usernames);

        Prior NONE = names -> Map.of();
    }

    /** Teslimatın kararı: atlanacak hesap ({@link #key}) → seçilen hesabın kullanıcı adı. Boş = hiçbir şey birleşmedi. */
    public record Plan(Map<String, String> skips) {
        public static final Plan NONE = new Plan(Map.of());

        /** Bu hesap aynı kişinin başka hesabı yüzünden atlanacaksa seçilen hesabın kullanıcı adı, aksi hâlde null. */
        public String chosenFor(String username) {
            return username == null || skips.isEmpty() ? null : skips.get(key(username));
        }

        public boolean isEmpty() {
            return skips.isEmpty();
        }
    }

    /** Kullanıcı adı karşılaştırma anahtarı (veritabanı {@code UPPER(username)} eşleşmesinin aynası). */
    public static String key(String username) {
        return username == null ? "" : username.trim().toUpperCase(Locale.ROOT);
    }

    /** E-posta karşılaştırma anahtarı; boş adres null (hiç birleşmez). */
    public static String emailKey(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase(Locale.ROOT);
        return e.isEmpty() ? null : e;
    }

    /**
     * Teslimatın alıcıları (sıra korunur; sistem satırı {@code "-"} ve boş ad yok sayılır) için karar.
     *
     * @param candidates bu teslimatın satır yazılacak alıcıları (kişisel atlama nedeni olanlar dahil)
     * @param prior      önceki satır kanıtı (yalnız birden çok hesabı olan kişiler için çağrılır)
     */
    public Plan plan(Collection<String> candidates, Prior prior) {
        Map<String, String> cands = new LinkedHashMap<>();
        if (candidates != null) {
            for (String c : candidates) {
                if (c == null || c.isBlank() || "-".equals(c.trim())) continue;
                cands.putIfAbsent(key(c), c.trim());
            }
        }
        if (cands.isEmpty()) return Plan.NONE;
        Map<String, List<Account>> persons = persons(cands.keySet());
        if (persons.isEmpty()) return Plan.NONE;
        Map<String, String> skips = new LinkedHashMap<>();
        for (List<Account> group : persons.values()) {
            List<Account> inDelivery = group.stream().filter(a -> cands.containsKey(key(a.username()))).toList();
            if (inDelivery.isEmpty()) continue;
            Map<String, Evidence> ev = evidence(prior, group);
            List<Account> covered = group.stream().filter(a -> ev.getOrDefault(key(a.username()), Evidence.NONE).exact()).toList();
            Account chosen;
            if (!covered.isEmpty()) chosen = best(covered, ev);
            else if (inDelivery.size() < 2) continue;
            else chosen = best(inDelivery, ev);
            for (Account a : inDelivery) {
                if (key(a.username()).equals(key(chosen.username()))) continue;
                skips.put(key(a.username()), chosen.username());
            }
        }
        return skips.isEmpty() ? Plan.NONE : new Plan(skips);
    }

    /**
     * Verilen hesapların (anahtar {@link #key}) BAŞKA aktif hesapları (aynı e-posta) → o hesabın kullanıcı adı (verilen biçimiyle).
     * Takım bildiriminden düşülen kişinin (ör. müdür push'unu alan) kardeş hesabı da bildirimi ikinci kez almasın diye.
     */
    public Map<String, String> siblingsOf(Collection<String> usernames) {
        Map<String, String> given = new LinkedHashMap<>();
        if (usernames != null) for (String u : usernames) if (u != null && !u.isBlank()) given.putIfAbsent(key(u), u.trim());
        if (given.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (List<Account> group : persons(given.keySet()).values()) {
            String owner = null;
            for (Account a : group) if (given.containsKey(key(a.username()))) { owner = given.get(key(a.username())); break; }
            if (owner == null) continue;
            for (Account a : group) if (!given.containsKey(key(a.username()))) out.putIfAbsent(key(a.username()), owner);
        }
        return out;
    }

    /**
     * Verilen hesapların kişileri: e-posta anahtarı → kişinin TÜM aktif hesapları (yalnız birden çok hesabı olan kişiler).
     * Sorgu düşerse boş (birleştirme yok).
     */
    public Map<String, List<Account>> persons(Collection<String> upperUsernames) {
        if (upperUsernames == null || upperUsernames.isEmpty()) return Map.of();
        List<Object[]> rows;
        try {
            rows = userRepo.findSamePersonAccounts(new ArrayList<>(upperUsernames));
        } catch (RuntimeException e) {
            log.warn("push aynı kişi denetimi yapılamadı (birleştirme yok, bugünkü davranış): {}", e.toString());
            return Map.of();
        }
        Map<String, Map<String, Account>> byEmail = new LinkedHashMap<>();
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            Account a = account(r);
            if (a == null) continue;
            String ek = emailKey(a.email());
            if (ek == null) continue;
            byEmail.computeIfAbsent(ek, k -> new LinkedHashMap<>()).putIfAbsent(key(a.username()), a);
        }
        Map<String, List<Account>> out = new LinkedHashMap<>();
        byEmail.forEach((ek, accs) -> { if (accs.size() > 1) out.put(ek, new ArrayList<>(accs.values())); });
        return out;
    }

    /** Hesaplar arasından seçim — kanıt yokken: en son giriş, sonra en küçük kimlik (önizleme / senaryo / özet). */
    public static Account chooseByLogin(List<Account> accounts) {
        return accounts == null || accounts.isEmpty() ? null : best(accounts, Map.of());
    }

    static Account best(List<Account> accounts, Map<String, Evidence> ev) {
        Comparator<Account> cmp = Comparator
                .comparing((Account a) -> !ev.getOrDefault(key(a.username()), Evidence.NONE).sent())
                .thenComparing(a -> !ev.getOrDefault(key(a.username()), Evidence.NONE).any())
                .thenComparing(Account::lastLogin, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Account::id, Comparator.nullsLast(Comparator.naturalOrder()));
        return accounts.stream().min(cmp).orElse(null);
    }

    private static Map<String, Evidence> evidence(Prior prior, List<Account> group) {
        if (prior == null) return Map.of();
        try {
            Map<String, Evidence> raw = prior.of(group.stream().map(Account::username).toList());
            if (raw == null || raw.isEmpty()) return Map.of();
            Map<String, Evidence> out = new HashMap<>();
            raw.forEach((k, v) -> out.merge(key(k), v == null ? Evidence.NONE : v, Evidence::or));
            return out;
        } catch (RuntimeException e) {
            log.warn("push aynı kişi kanıtı okunamadı (yalnız giriş zamanına göre seçiliyor): {}", e.toString());
            return Map.of();
        }
    }

    /** Sorgu satırı {@code [id, username, email, lastLoginAt]} → hesap. */
    private static Account account(Object[] r) {
        if (r == null || r.length < 4 || r[1] == null) return null;
        Long id = r[0] instanceof Number n ? Long.valueOf(n.longValue()) : null;
        String username = r[1].toString().trim();
        if (username.isEmpty()) return null;
        String email = r[2] == null ? null : r[2].toString();
        Instant last = r[3] == null ? null : PushText.parseStoredUtc(r[3].toString());
        return new Account(id, username, email, last);
    }
}
