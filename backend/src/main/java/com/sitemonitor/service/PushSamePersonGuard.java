package com.sitemonitor.service;

import com.sitemonitor.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Aynı kişinin iki hesabına İKİ push gitmesin (2026-10-21, prod alarm #628 "OCPAA - Servis Yanıt Süresi Anomalisi"): sekiz
 * alıcılı tek teslimatta aynı kişi iki hesapla ("… MP60146" ve "… N60146") listedeydi; ağ geçidi iki sicili aynı telefona
 * eşlediği için kişi aynı push'u iki kez gördü. Alıcı kurucuları yalnız KULLANICI ADINA göre tekilleştiriyordu.
 *
 * <p><b>Kim aynı kişi (kullanıcı kararı + inceleme 2026-10-21):</b> e-posta adresi AYNI (kırpılmış,
 * {@code toLowerCase(Locale.ROOT)}) olan AKTİF hesaplar — ve iki hesabın görünen adı da aynı kişiyi gösteriyorsa
 * ({@link #personName}: Türkçe harf / aksan katlanır, parantezli kısım ve rakam içeren sözcükler — sicil — atılır;
 * "Emre Gökoğlu MP60146" = "Emre Gökoğlu N60146"). Aynı posta kutusunu paylaşan FARKLI kişiler ("Ayşe Yılmaz" + "Mehmet
 * Demir") birleşmez. Görünen adı boş olan hesap yalnız e-postayla eşleşir — adres altında tek bir ad varsa ona katılır, birden
 * çok farklı ad varsa hiçbirine katılmaz ({@link #groupPersons}). Boş / null adres asla birleşmez; pasif hesap katılmaz.
 *
 * <p><b>Seçim (deterministik):</b>
 * <ol>
 *   <li>Bu bildirimin AYNISINI (aynı yinelenmezlik anahtarı) zaten ALMIŞ hesap — satırı SENT / PENDING (atlama satırı kanıt
 *   değildir); teslimat dışındaki kardeş hesap da sayılır (nöbetçi + takım, müdür + takım, aynı anahtarla iki çağrı).</li>
 *   <li>Yoksa yalnız GÖNDERİLEBİLİR hesaplar adaydır (kişisel atlama nedeni yok — opt-out, seviye, susturma, aile, sessiz
 *   saat): önce saat tavanının altındakiler, sonra aynı mantıksal olayda push ALMIŞ olan (önce SENT, sonra PENDING — çözüm /
 *   eskalasyon / günlük tekrar açılışı alan hesaba gider, {@code enqueueResolve} simetrisi korunur), sonra en son giriş
 *   ({@code last_login_at}), sonra en küçük kimlik.</li>
 *   <li>Kişinin hiçbir hesabı gönderilebilir değilse birleştirme YOK: her hesap kendi dürüst atlama nedenini yazar.</li>
 * </ol>
 * Seçilen hesabın KENDİ kuralları (dil, saat tavanı) aynen uygulanır. Diğerleri {@value #SKIPPED_SAME_PERSON} satırı alır
 * (satırda seçilen hesabın kullanıcı adı — e-posta ASLA; metni "karar o hesap için verildi, bu hesaba ayrıca gönderilmedi" der,
 * gönderildiğini iddia etmez); bu satırlar saat tavanına sayılmaz.
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

    /** Aynı kişinin başka hesabı için karar verildi; bu hesaba ayrıca gönderilmedi ({@code user_push_deliveries.status}). */
    public static final String SKIPPED_SAME_PERSON = "SKIPPED_SAME_PERSON";
    /**
     * Aynı kişinin başka hesabı bu bildirimden BİLEREK çıkarıldı (takım bildiriminde müdür push'unu alan, raporu gönderen) —
     * kişi dışarıda tutuldu; bu hesaba da gönderilmedi.
     */
    public static final String SKIPPED_SAME_PERSON_EXCLUDED = "SKIPPED_SAME_PERSON_EXCLUDED";

    /** Bir teslimat satırının push'u gerçekten ALDIĞINI / alacağını gösteren durumlar (atlama, tavan, hata değil). */
    public static boolean delivering(String status) {
        return status != null && ("SENT".equals(status) || "PENDING".equals(status) || status.startsWith("QUEUED"));
    }

    private final AppUserRepository userRepo;

    public PushSamePersonGuard(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /** Bir kişinin bir hesabı (aktif, e-postalı). {@code displayName}: kişi eşleşmesi için görünen ad (boş olabilir). */
    public record Account(Long id, String username, String email, Instant lastLogin, String displayName) {
        public Account(Long id, String username, String email, Instant lastLogin) {
            this(id, username, email, lastLogin, null);
        }
    }

    /** Bu teslimatta bir hesabın durumu (aday mı): gönderilebilir, saat tavanında, kişisel neden yüzünden gönderilemez. */
    public enum Readiness { DELIVERABLE, OVER_CAP, BLOCKED }

    /**
     * Bir hesabın önceki satır kanıtı — YALNIZ push'u alan satırlar ({@link #delivering}); atlama satırları kanıt değildir.
     * {@code exact}: bu bildirimin aynısını almış; {@code sent}: aynı mantıksal olay için SENT; {@code received}: aynı
     * mantıksal olay için SENT ya da PENDING.
     */
    public record Evidence(boolean exact, boolean sent, boolean received) {
        public static final Evidence NONE = new Evidence(false, false, false);
        public Evidence or(Evidence o) {
            return o == null ? this : new Evidence(exact || o.exact, sent || o.sent, received || o.received);
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

    private static final Locale TR = Locale.forLanguageTag("tr");
    /** Görünen ad tavanı — kolon 200; doğrusal tarama için yine sınırlanır. */
    private static final int NAME_MAX = 500;

    /**
     * Görünen ad → kişi anahtarı (null = boş). Parantezli kısımlar ("( … )", iç içe) atılır; Türkçe harf / aksan katlanır
     * (İ I ı i → i, ş → s, ç → c, ğ → g, ö → o, ü → u — arayüzün {@code foldSearchText}'iyle aynı); sözcük kenarındaki harf
     * olmayan işaretler kırpılır; rakam içeren sözcükler (sicil: MP60146, N60146) ve harfsiz sözcükler atılır; boşluklar teke
     * iner. Regex yok, doğrusal.
     */
    public static String personName(String displayName) {
        if (displayName == null) return null;
        String raw = displayName.length() > NAME_MAX ? displayName.substring(0, NAME_MAX) : displayName;
        StringBuilder noParen = new StringBuilder(raw.length());
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '(') { depth++; noParen.append(' '); continue; }
            if (c == ')') { if (depth > 0) depth--; noParen.append(' '); continue; }
            if (depth == 0) noParen.append(c);
        }
        String folded = noParen.toString().toLowerCase(TR).replace('ı', 'i');
        folded = Normalizer.normalize(folded, Normalizer.Form.NFD);
        StringBuilder plain = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c >= '\u0300' && c <= '\u036f') continue;   // birleşik aksan işaretleri (foldSearchText ile aynı aralık)
            plain.append(c);
        }
        StringBuilder out = new StringBuilder();
        int i = 0, n = plain.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(plain.charAt(i))) i++;
            int start = i;
            while (i < n && !Character.isWhitespace(plain.charAt(i))) i++;
            if (start == i) continue;
            int s = start, e = i;
            while (s < e && !Character.isLetterOrDigit(plain.charAt(s))) s++;
            while (e > s && !Character.isLetterOrDigit(plain.charAt(e - 1))) e--;
            boolean digit = false, letter = false;
            for (int k = s; k < e; k++) {
                char c = plain.charAt(k);
                if (Character.isDigit(c)) digit = true;
                if (Character.isLetter(c)) letter = true;
            }
            if (s == e || digit || !letter) continue;
            if (out.length() > 0) out.append(' ');
            out.append(plain, s, e);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * Hesaplar → kişiler (yalnız birden çok hesabı olan kişiler). Aynı e-posta + aynı kişi adı ({@link #personName}); adı boş
     * hesap, adres altında TEK bir ad varsa ona katılır (ad yoksa boş adlılar kendi aralarında birleşir); birden çok farklı ad
     * varsa hiçbirine katılmaz. Sıra korunur. Saf işlev — gönderim, önizleme ve "Kim alır?" aynı kuralı buradan okur.
     */
    public static List<List<Account>> groupPersons(Collection<Account> accounts) {
        Map<String, List<Account>> byEmail = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Account a : accounts == null ? List.<Account>of() : accounts) {
            if (a == null || a.username() == null || a.username().isBlank()) continue;
            String ek = emailKey(a.email());
            if (ek == null || !seen.add(key(a.username()))) continue;
            byEmail.computeIfAbsent(ek, k -> new ArrayList<>()).add(a);
        }
        List<List<Account>> out = new ArrayList<>();
        for (List<Account> sameEmail : byEmail.values()) {
            if (sameEmail.size() < 2) continue;
            Map<String, List<Account>> byName = new LinkedHashMap<>();
            List<Account> blank = new ArrayList<>();
            for (Account a : sameEmail) {
                String pn = personName(a.displayName());
                if (pn == null) blank.add(a);
                else byName.computeIfAbsent(pn, k -> new ArrayList<>()).add(a);
            }
            if (byName.isEmpty()) {
                out.add(blank);
            } else if (byName.size() == 1) {
                List<Account> person = new ArrayList<>(byName.values().iterator().next());
                person.addAll(blank);
                if (person.size() > 1) out.add(person);
            } else {
                for (List<Account> person : byName.values()) if (person.size() > 1) out.add(person);
            }
        }
        return out;
    }

    /** {@link #plan(Collection, Function, Prior)} — her aday gönderilebilir sayılır (testler, kanıt yalnız). */
    public Plan plan(Collection<String> candidates, Prior prior) {
        return plan(candidates, u -> Readiness.DELIVERABLE, prior);
    }

    /**
     * Teslimatın alıcıları (sıra korunur; sistem satırı {@code "-"} ve boş ad yok sayılır) için karar.
     *
     * @param candidates bu teslimatın satır yazılacak alıcıları (kişisel atlama nedeni olanlar dahil)
     * @param readiness  hesabın bu teslimattaki durumu (yalnız birden çok hesabı olan kişilerin hesapları için sorulur)
     * @param prior      önceki satır kanıtı (yalnız birden çok hesabı olan kişiler için çağrılır)
     */
    public Plan plan(Collection<String> candidates, Function<String, Readiness> readiness, Prior prior) {
        Map<String, String> cands = new LinkedHashMap<>();
        if (candidates != null) {
            for (String c : candidates) {
                if (c == null || c.isBlank() || "-".equals(c.trim())) continue;
                cands.putIfAbsent(key(c), c.trim());
            }
        }
        if (cands.isEmpty()) return Plan.NONE;
        List<List<Account>> persons = personsList(cands.keySet());
        if (persons.isEmpty()) return Plan.NONE;
        Map<String, String> skips = new LinkedHashMap<>();
        for (List<Account> group : persons) {
            List<Account> inDelivery = group.stream().filter(a -> cands.containsKey(key(a.username()))).toList();
            if (inDelivery.isEmpty()) continue;
            Account chosen = choose(group, cands.keySet(), evidence(prior, group), readinessOf(readiness));
            if (chosen == null) continue;
            for (Account a : inDelivery) {
                if (key(a.username()).equals(key(chosen.username()))) continue;
                skips.put(key(a.username()), chosen.username());
            }
        }
        return skips.isEmpty() ? Plan.NONE : new Plan(skips);
    }

    /**
     * Bir kişinin hesapları arasından seçim (saf). (1) Bu bildirimi zaten almış hesap (teslimat dışı da olabilir); (2) yoksa
     * teslimattaki gönderilebilir hesaplar: tavan altı → olayda SENT → olayda PENDING → son giriş → en küçük kimlik. Teslimatta
     * tek hesap varsa ya da hiçbiri gönderilebilir değilse null (birleştirme yok).
     *
     * @param inDeliveryKeys teslimattaki hesapların {@link #key} anahtarları
     */
    public static Account choose(List<Account> group, Set<String> inDeliveryKeys, Map<String, Evidence> ev,
                                 Function<String, Readiness> readiness) {
        if (group == null || group.isEmpty()) return null;
        Map<String, Evidence> evidence = ev == null ? Map.of() : ev;
        List<Account> covered = group.stream().filter(a -> evidence.getOrDefault(key(a.username()), Evidence.NONE).exact()).toList();
        if (!covered.isEmpty()) return best(covered, evidence, null);
        List<Account> inDelivery = group.stream()
                .filter(a -> inDeliveryKeys != null && inDeliveryKeys.contains(key(a.username()))).toList();
        if (inDelivery.size() < 2) return null;
        Function<String, Readiness> rd = readinessOf(readiness);
        List<Account> eligible = inDelivery.stream().filter(a -> rd.apply(a.username()) != Readiness.BLOCKED).toList();
        if (eligible.isEmpty()) return null;
        return best(eligible, evidence, rd);
    }

    /** Verilen hesapların (anahtar {@link #key}) kişileri — sorgu düşerse boş (birleştirme yok). */
    private List<List<Account>> personsList(Collection<String> upperUsernames) {
        if (upperUsernames == null || upperUsernames.isEmpty()) return List.of();
        List<Object[]> rows;
        try {
            rows = userRepo.findSamePersonAccounts(new ArrayList<>(upperUsernames));
        } catch (RuntimeException e) {
            log.warn("push aynı kişi denetimi yapılamadı (birleştirme yok, bugünkü davranış): {}", e.toString());
            return List.of();
        }
        List<Account> accounts = new ArrayList<>();
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            Account a = account(r);
            if (a != null) accounts.add(a);
        }
        return groupPersons(accounts);
    }

    /**
     * Verilen hesapların (anahtar {@link #key}) BAŞKA aktif hesapları (aynı kişi) → o hesabın kullanıcı adı (verilen biçimiyle).
     * Takım bildiriminden düşülen kişinin (ör. müdür push'unu alan) kardeş hesabı da bildirimi ikinci kez almasın diye.
     */
    public Map<String, String> siblingsOf(Collection<String> usernames) {
        Map<String, String> given = new LinkedHashMap<>();
        if (usernames != null) for (String u : usernames) if (u != null && !u.isBlank()) given.putIfAbsent(key(u), u.trim());
        if (given.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (List<Account> group : personsList(given.keySet())) {
            String owner = null;
            for (Account a : group) if (given.containsKey(key(a.username()))) { owner = given.get(key(a.username())); break; }
            if (owner == null) continue;
            for (Account a : group) if (!given.containsKey(key(a.username()))) out.putIfAbsent(key(a.username()), owner);
        }
        return out;
    }

    /**
     * Verilen hesapların kişileri: hesap anahtarı ({@link #key}) → kişinin TÜM aktif hesapları (yalnız birden çok hesabı olan
     * kişiler). Sorgu düşerse boş.
     */
    public Map<String, List<Account>> persons(Collection<String> upperUsernames) {
        Map<String, List<Account>> out = new LinkedHashMap<>();
        for (List<Account> person : personsList(upperUsernames))
            for (Account a : person) out.put(key(a.username()), person);
        return out;
    }

    /** Hesaplar arasından seçim — kanıt ve durum yokken: en son giriş, sonra en küçük kimlik ("Kim alır?", özet). */
    public static Account chooseByLogin(List<Account> accounts) {
        return accounts == null || accounts.isEmpty() ? null : best(accounts, Map.of(), null);
    }

    static Account best(List<Account> accounts, Map<String, Evidence> ev, Function<String, Readiness> readiness) {
        Comparator<Account> cmp = Comparator
                .comparing((Account a) -> readiness != null && readiness.apply(a.username()) == Readiness.OVER_CAP)
                .thenComparing(a -> !ev.getOrDefault(key(a.username()), Evidence.NONE).sent())
                .thenComparing(a -> !ev.getOrDefault(key(a.username()), Evidence.NONE).received())
                .thenComparing(Account::lastLogin, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Account::id, Comparator.nullsLast(Comparator.naturalOrder()));
        return accounts.stream().min(cmp).orElse(null);
    }

    /** Durum işlevi hiç düşmesin: hata = gönderilebilir sayılmaz (BLOCKED) — yanlış hesaba "karar verildi" yazılmasın. */
    private static Function<String, Readiness> readinessOf(Function<String, Readiness> readiness) {
        Map<String, Readiness> memo = new HashMap<>();
        return u -> memo.computeIfAbsent(key(u), k -> {
            if (readiness == null) return Readiness.DELIVERABLE;
            try {
                Readiness r = readiness.apply(u);
                return r == null ? Readiness.BLOCKED : r;
            } catch (RuntimeException e) {
                log.warn("push aynı kişi: hesap durumu okunamadı ({}): {}", u, e.toString());
                return Readiness.BLOCKED;
            }
        });
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
            log.warn("push aynı kişi kanıtı okunamadı (yalnız durum ve giriş zamanına göre seçiliyor): {}", e.toString());
            return Map.of();
        }
    }

    /** Sorgu satırı {@code [id, username, email, lastLoginAt, displayName]} → hesap (eski 4 sütunlu satır da okunur). */
    private static Account account(Object[] r) {
        if (r == null || r.length < 4 || r[1] == null) return null;
        Long id = r[0] instanceof Number n ? Long.valueOf(n.longValue()) : null;
        String username = r[1].toString().trim();
        if (username.isEmpty()) return null;
        String email = r[2] == null ? null : r[2].toString();
        Instant last = r[3] == null ? null : PushText.parseStoredUtc(r[3].toString());
        String display = r.length > 4 && r[4] != null ? r[4].toString() : null;
        return new Account(id, username, email, last, display);
    }
}
