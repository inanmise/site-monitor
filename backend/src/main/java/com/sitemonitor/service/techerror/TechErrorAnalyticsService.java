package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import com.sitemonitor.util.TtlMemo;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Kanallar arası / aylar arası analiz (2026-10-18, R22 — kullanıcı onayı: "takımın kanallarında aynı kimlikli alanlar
 * (error-rate, total-tx, sla-rate) 12 ay × kanal tablo ve grafik"). YAZMA YOK.
 *
 * <p><b>Kurallar.</b>
 * <ul>
 *   <li>Pencere: İstanbul'a göre BİTMİŞ son {@code months} ay (6 / 12 / 24), önceki takvim ayıyla biter (çağıran verir).</li>
 *   <li>Kanallar (2026-10-18, kullanıcı kararı: "pasife alınmış kanalları geçmiş aylarıyla birlikte gösterelim"): kapsamın
 *       AKTİF kanalları her zaman (raporu olmasa da), PASİF kanalları yalnız penceredeki aylardan en az birinde raporu varsa
 *       (boş satır yok) — {@link ChannelSeries#active()}. Sıra: önce aktifler (takım adı, sıra numarası, ad), sonra
 *       pasifler aynı sırayla. Takım süzgeci yoksa okunabilen bütün takımların kanalları. Pasif kanalın listelenip
 *       listelenmeyeceği AYNI rapor okumasından anlaşılır (kanal başına sorgu yok).</li>
 *   <li>Referans tür: kapsamın AKTİF kanallarının GÜNCEL şablonlarında alanı sayı / yüzde olarak tanımlayanların ÇOĞUNLUĞU
 *       (eşitlikte kanal sırasında ilk tanımlayan); hiçbir aktif kanal tanımlamıyorsa listelenen PASİF kanalların güncel
 *       şablonlarındaki çoğunluk (aynı kural); hiçbirinde yoksa varsayılan şablondaki. Alanı başka türle
 *       tanımlayan kanallar {@code typeMismatch}'te listelenir (arayüz o kanalda "güncel şablonda alan türü farklı; yalnız
 *       aynı türdeki aylar" der). Bir ayın değeri
 *       yalnız o raporun KENDİ kopyalanmış şablonunda alan AYNI kimlik VE AYNI türdeyse alınır (trend kuralı,
 *       {@link TechErrorTrend}); tür farkı, muaf alan ({@code skip_if_no} "Hayır"), raporu olmayan ay ve değeri olmayan
 *       alan {@code null} — asla 0 değil.</li>
 *   <li>Hesaplanan alanlar (oran / değişim — {@link TechErrorCalc}) rapor ekranı ve PDF gibi CANLI hesaplanır: geçen ay,
 *       aynı kanalın bir önceki TAKVİM ayının raporu (aynı kimlik + tür, {@code sameTypeFields} kuralı); pencerenin ilk
 *       ayının geçen ayı için okuma penceresi bir ay geriden başlar (kanal başına ek sorgu yok). Geçen ay düzeltildiyse
 *       analiz de güncel.</li>
 *   <li>Maliyet SABİT: bir kanal sorgusu + raporlar kimlik sırasıyla {@link #CHUNK} satırlık dilimlerde (anahtar kümesi).
 *       Hücre sınırı {@link #MAX_CELLS} (LİSTELENEN kanal — aktif + raporu olan pasif — × ay): aşılırsa en YENİ aylar kalır
 *       ve {@code truncated} işaretlenir; aktiflerin okunan satırı ≤ kanal × (ay + 1) ≤ 2 × {@link #MAX_CELLS} → en çok
 *       {@link #MAX_CHUNKS} dilim; pasif adaylar (en çok {@link #MAX_INACTIVE_READ}) yalnız penceredeki GERÇEK raporları
 *       kadar satır ekler (dilim sınırı okunan kanal × (ay + 1)'den hesaplanır — sessiz kesilme yok). Bellekte aynı anda yalnız bir
 *       dilimin JSON metinleri durur; her satırdan yalnız sayı / evet-hayır değerleri ve hedefler tutulur. Aynı şablon
 *       metni (aynı kanal sürümünden kopyalanmış raporlar) bir kez çözülür — farklı metin sayısı kullanılan şablon
 *       sürümleriyle sınırlı.</li>
 *   <li>Sonuç {@link #CACHE_TTL} boyunca (okunabilen takım kümesi, takım, ay, alanlar, bitiş ayı) anahtarıyla
 *       {@link TtlMemo}'da (eşzamanlı ıskalamalar tek hesabı paylaşır); okuma hatası saklanmaz (çağıran hatayı görür —
 *       "veri yok" denmez). {@code fresh} (Yenile) belleği ancak kayıt {@link #FRESH_MIN_MS}'den eskiyse atlar.</li>
 *   <li>Etiketler: {@code {kanal}} yalnız kapsamda tek kanal varsa onun adıyla, {@code {ay}} / {@code {yil}} kapsamın en
 *       yeni raporunun ayıyla çözülür; uygulanamıyorsa yer tutucu düşer (sade etiket).</li>
 * </ul>
 */
@Service
public class TechErrorAnalyticsService {

    public static final List<Integer> MONTH_CHOICES = List.of(6, 12, 24);
    public static final int DEFAULT_MONTHS = 12;
    public static final int MAX_FIELDS = 4;
    /** Kanal × ay hücre sınırı — aşılırsa en yeni aylar kalır. */
    public static final int MAX_CELLS = 1200;
    /** Rapor okuma dilimi (satır). */
    public static final int CHUNK = 200;
    /** Dilim sayısı üst sınırı (yalnız aktif kanallar okunurken): okunan satır ≤ kanal × (ay + 1) ≤ 2 × MAX_CELLS (+ bir boş son dilim). */
    public static final int MAX_CHUNKS = (2 * MAX_CELLS) / CHUNK + 2;
    /**
     * Aynı okumaya katılan pasif kanal adayı üst sınırı (IN listesi): önce kapsamın pasifleri, sonra — takım seçiliyken —
     * yalnız takım seçici için okunan pasifler. Sınırı aşan kapsam pasifi {@code truncated}; seçici adayının takımı
     * bilinmediği için seçicide KALIR (seçilebilir takım gizlenmez).
     */
    public static final int MAX_INACTIVE_READ = MAX_CELLS;
    /** Alan seçilmemişse (ve kanallarda varsa) bu ikisi. */
    public static final List<String> DEFAULT_FIELDS = List.of("error-rate", "total-tx");
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);
    /** Yenile ({@code fresh}) belleği ancak kayıt bu yaştan eskiyse atlar — düğmeye art arda basmak DB'ye binmesin. */
    public static final long FRESH_MIN_MS = 5_000;
    /** Şablon alan kimliği kuralı (TechErrorTemplate ile aynı). */
    public static final Pattern FIELD_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,39}$");

    private final TechErrorChannelRepository channels;
    private final TechErrorReportRepository reports;
    private TtlMemo<Result> memo = new TtlMemo<>(256);
    /**
     * Bellek kuşağı (inceleme 2026-10-18, 2. tur): {@link #invalidate} artırır ve anahtara girer — boşaltmadan ÖNCE başlamış
     * bir hesap eski veriyi eski kuşağın anahtarına yazar, kimse onu okumaz (yalnız {@code clear} bu yarışı kaçırırdı).
     */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    /** Dilim boyu — testler küçültür (dilimleme gerçekten koşsun). */
    int chunkSize = CHUNK;

    public TechErrorAnalyticsService(TechErrorChannelRepository channels, TechErrorReportRepository reports) {
        this.channels = channels;
        this.reports = reports;
    }

    /** Test kancası: saat enjekte edilmiş bellek (Yenile sel koruması). */
    void useClock(LongSupplier clock) {
        this.memo = new TtlMemo<>(256, clock);
    }

    /**
     * Seçilen / çizilen alan (referans tür ve etiket). {@code typeMismatch}: alanı GÜNCEL şablonunda başka türle tanımlayan
     * kapsam kanalları — arayüz kanalın yanında not gösterir; değerler ay ay raporun KENDİ şablonundan okunur, o kanalın
     * referans türdeki ESKİ raporları da değer verir ("yalnız aynı türdeki aylar", inceleme 2026-10-18, 2. tur).
     */
    public record FieldRef(String id, String label, String type, List<Long> typeMismatch) {
        public FieldRef(String id, String label, String type) {
            this(id, label, type, List.of());
        }
    }

    /** Seçilebilir alan: kanalların GÜNCEL şablonlarındaki sayı / yüzde alanları; {@code channels} = referans türle tanımlayan kanal sayısı. */
    public record FieldOption(String id, String label, String type, int channels) { }

    /**
     * Bir ayın raporu (yoksa ayın yeri {@code null}). {@code queued}: hiç gönderilmemiş ama gönderimi posta kuyruğunda —
     * sonucu henüz bilinmiyor ("gönderilmedi" denmez; liste ekranının {@code queued} durumu).
     */
    public record ReportRef(long id, boolean sent, boolean queued) {
        public ReportRef(long id, boolean sent) {
            this(id, sent, false);
        }
    }

    /**
     * Kanalın serileri: alan → ay ay değerler (eskiden yeniye, boş ay null) + ay ay rapor. {@code active}: kanal aktif mi —
     * pasif kanal yalnız penceresinde raporu varsa listelenir, aktiflerin ardından (arayüz "Pasif" rozeti).
     */
    public record ChannelSeries(long id, String name, long teamId, boolean active, Map<String, List<Double>> values,
                                List<ReportRef> reports) {
        public ChannelSeries(long id, String name, long teamId, Map<String, List<Double>> values, List<ReportRef> reports) {
            this(id, name, teamId, true, values, reports);
        }
    }

    /**
     * @param months            pencere (eskiden yeniye; kesildiyse yalnız en yeni aylar)
     * @param monthsRequested   istenen ay sayısı
     * @param channelCount      kapsamın aktif kanalları + penceresinde raporu olan pasif kanalları (kesilmeden önce)
     * @param teamsWithChannels okunabilen ve aktif kanalı ya da okunan pencerede raporu olan pasif kanalı bulunan takımlar
     *                          (takım seçici için; ad sırasıyla)
     * @param dropped           istenip bu kapsamda bulunamayan alan kimlikleri
     * @param fieldsFallback    istenen alanların HİÇBİRİ bulunamadı → varsayılan alanlar gösteriliyor
     */
    public record Result(List<YearMonth> months, int monthsRequested, List<FieldRef> fields, List<FieldOption> available,
                         List<ChannelSeries> channels, int channelCount, boolean truncated, List<Long> teamsWithChannels,
                         List<String> dropped, boolean fieldsFallback) {
        public Result(List<YearMonth> months, int monthsRequested, List<FieldRef> fields, List<FieldOption> available,
                      List<ChannelSeries> channels, int channelCount, boolean truncated, List<Long> teamsWithChannels) {
            this(months, monthsRequested, fields, available, channels, channelCount, truncated, teamsWithChannels, List.of(), false);
        }
    }

    /**
     * Belleği boşaltır: rapor değerleri kaydedilince, rapor açılınca / silinince, kanal / şablon değişince
     * ({@code TechErrorReportService.analyticsChanged}, işlem işlendikten sonra) ve testler. Kuşak artar — süren eski hesap
     * yeni anahtara yazmaz.
     */
    public void invalidate() {
        generation.incrementAndGet();
        memo.clear();
    }

    /**
     * @param readableTeamIds okuyucunun bu modülde okuyabildiği takımlar (kapsam + modül görünürlüğü çağıranda)
     * @param teamFilter      tek takım (null = okunabilen hepsi); okunabilen kümede olmalı (çağıran denetler)
     * @param months          {@link #MONTH_CHOICES}'tan biri
     * @param requested       istenen alan kimlikleri (null / boş = varsayılan), ≤ {@link #MAX_FIELDS}
     * @param end             pencerenin son ayı (önceki takvim ayı, İstanbul)
     * @param teamNames       sıralama için takım adları (sonuca girmez)
     * @param fresh           Yenile: bellekteki kayıt {@link #FRESH_MIN_MS}'den eskiyse yeniden hesaplanır
     */
    public Result analytics(Collection<Long> readableTeamIds, Long teamFilter, int months, List<String> requested,
                            YearMonth end, Map<Long, String> teamNames, boolean fresh) {
        if (!MONTH_CHOICES.contains(months)) throw new IllegalArgumentException("months");
        Set<Long> teams = new TreeSet<>();
        for (Long t : readableTeamIds == null ? List.<Long>of() : readableTeamIds) if (t != null) teams.add(t);
        List<String> fields = requested == null ? List.of() : List.copyOf(new LinkedHashSet<>(requested));
        if (fields.size() > MAX_FIELDS) throw new IllegalArgumentException("fields");
        String key = TtlMemo.scopeKey(false, teams) + "|f=" + teamFilter + "|m=" + months + "|fl=" + String.join(",", fields)
                + "|e=" + end + "|g=" + generation.get();
        Map<Long, String> names = teamNames == null ? Map.of() : teamNames;
        long ttl = CACHE_TTL.toMillis();
        if (fresh) ttl = Math.min(ttl, FRESH_MIN_MS);
        return memo.get(key, ttl, false, () -> compute(teams, teamFilter, months, fields, end, names));
    }

    /**
     * Alanın kapsamdaki tanımları: AKTİF kanallar ve listelenen PASİF kanallar ayrı sayılır — referans tür önce aktiflerin
     * çoğunluğu, hiçbir aktif kanal alanı sayı / yüzde olarak tanımlamıyorsa pasiflerinki.
     */
    private static final class FieldStats {
        final String id;
        final Tally active = new Tally();
        final Tally inactive = new Tally();
        /** Kanal → bu kanalın şablonundaki tür (sayı / yüzde dışı türler de — tür farkı işareti için). */
        final Map<Long, String> byChannel = new HashMap<>();

        FieldStats(String id) { this.id = id; }

        /** Referans türün kaynağı: aktiflerde sayı / yüzde tanımı varsa onlar, yoksa listelenen pasifler. */
        Tally source() {
            return active.refType() != null ? active : inactive;
        }

        /** Referans türü tanımlayan kanal sayısı (aktif + listelenen pasif). */
        int channels(String type) {
            return active.counts.getOrDefault(type, 0) + inactive.counts.getOrDefault(type, 0);
        }
    }

    /** Bir kanal kümesinde tür → kanal sayısı (ilk görülen sıra), tür → ilk etiket / dil. */
    private static final class Tally {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, String> labels = new HashMap<>();
        final Map<String, String> langs = new HashMap<>();

        /** Çoğunluk türü (yalnız sayı / yüzde); eşitlikte ilk görülen; hiç yoksa null. */
        String refType() {
            String best = null;
            int n = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > n) { best = e.getKey(); n = e.getValue(); }
            }
            return best;
        }
    }

    /** Çözümlenen alan (iç): etiket kaynağının dili ile. */
    record Ref(String id, String label, String type, String lang) { }

    /** Çözülmüş bir şablon (aynı metin bir kez). */
    private record Parsed(Template tpl, Map<String, Field> byId) { }

    /** Bir rapordan tutulan özet: yalnız sayı / evet-hayır değerleri ve hedefler (metinler atılır). */
    private record Cell(long id, boolean sent, boolean queued, Parsed parsed, Map<String, Object> fields,
                        Map<String, Object> targets) { }

    /** Kanalın GÜNCEL şablonundaki alanları sayar (aktif ya da pasif kümeye); şablonun dilini döner. */
    private static String addStats(Map<String, FieldStats> stats, TechErrorChannel ch, boolean active) {
        Template t = TechErrorTemplate.fromStored(ch.getTemplateJson());
        Set<String> seen = new LinkedHashSet<>();
        for (Field f : t.allFields()) {
            if (!seen.add(f.id())) continue;
            FieldStats st = stats.computeIfAbsent(f.id(), FieldStats::new);
            st.byChannel.put(ch.getId(), f.type());
            if (!TechErrorTemplate.trendable(f)) continue;
            Tally tally = active ? st.active : st.inactive;
            tally.counts.merge(f.type(), 1, Integer::sum);
            tally.labels.putIfAbsent(f.type(), f.label());
            tally.langs.putIfAbsent(f.type(), t.lang());
        }
        return t.lang();
    }

    /** Seçilebilir alanlar (ilk görülen sıra: önce aktiflerin alanları); {@code counts} doluysa alan → kanal sayısı. */
    private static Map<String, Ref> options(Map<String, FieldStats> stats, Map<String, Integer> counts) {
        Map<String, Ref> out = new LinkedHashMap<>();
        for (FieldStats st : stats.values()) {
            Tally src = st.source();
            String type = src.refType();
            if (type == null) continue;
            out.put(st.id, new Ref(st.id, src.labels.get(type), type, src.langs.get(type)));
            if (counts != null) counts.put(st.id, st.channels(type));
        }
        return out;
    }

    private Result compute(Set<Long> teams, Long teamFilter, int months, List<String> requested, YearMonth end,
                           Map<Long, String> teamNames) {
        if (teams.isEmpty()) return new Result(window(end, months), months, List.of(), List.of(), List.of(), 0, false, List.of());
        Collator col = Collator.getInstance(Locale.forLanguageTag("tr"));
        col.setStrength(Collator.SECONDARY);
        Comparator<TechErrorChannel> order = Comparator.<TechErrorChannel, String>comparing(ch -> teamNames.getOrDefault(ch.getTeamId(), ""), col)
                .thenComparing(TechErrorChannel::getTeamId)
                .thenComparing(ch -> ch.getSortOrder() == null ? Integer.MAX_VALUE : ch.getSortOrder())
                .thenComparing(ch -> ch.getName() == null ? "" : ch.getName(), col)
                .thenComparing(TechErrorChannel::getId);
        // 1. sorgu: okunabilen takımların kanalları — aktif ve pasif ayrı, ikisi de aynı sırayla
        List<TechErrorChannel> active = new ArrayList<>();
        List<TechErrorChannel> inactive = new ArrayList<>();
        for (TechErrorChannel ch : channels.findByTeamIdIn(teams)) {
            if (ch == null || ch.getId() == null || ch.getTeamId() == null || !teams.contains(ch.getTeamId())) continue;
            (ch.isActive() ? active : inactive).add(ch);
        }
        active.sort(order);
        inactive.sort(order);
        Set<Long> activeTeams = new HashSet<>();
        for (TechErrorChannel ch : active) activeTeams.add(ch.getTeamId());
        List<TechErrorChannel> scope = new ArrayList<>();
        for (TechErrorChannel ch : active) if (teamFilter == null || teamFilter.equals(ch.getTeamId())) scope.add(ch);
        // Pasif adaylar: kapsamın pasifleri (penceresinde raporu varsa listelenir) ve — takım seçiliyken — aktif kanalı
        // OLMAYAN öteki takımların pasifleri (yalnız takım seçici için: seçim değişince seçicideki takımlar değişmesin)
        List<TechErrorChannel> inactiveScope = new ArrayList<>();
        List<TechErrorChannel> pickerOnly = new ArrayList<>();
        for (TechErrorChannel ch : inactive) {
            if (teamFilter == null || teamFilter.equals(ch.getTeamId())) inactiveScope.add(ch);
            else if (!activeTeams.contains(ch.getTeamId())) pickerOnly.add(ch);
        }

        // Seçilebilir alanlar: önce kapsamın AKTİF kanallarının GÜNCEL şablonları (referans tür onların çoğunluğu)
        Map<String, FieldStats> stats = new LinkedHashMap<>();
        String firstLang = null;
        for (TechErrorChannel ch : scope) {
            String l = addStats(stats, ch, true);
            if (firstLang == null) firstLang = l;
        }

        // Hücre sınırı, 1. adım (aktifler — eski kural): kanal × ay > MAX_CELLS → en yeni aylar (kanal sayısı tek başına
        // sınırı aşarsa ilk kanallar). Okuma penceresi bu ay sayısıyla; pasifler 2. adımda yalnız daraltabilir.
        int activeCount = scope.size();
        boolean truncated = false;
        if (activeCount > MAX_CELLS) {
            scope = new ArrayList<>(scope.subList(0, MAX_CELLS));
            truncated = true;
        }
        int readMonths = months;
        if (!scope.isEmpty() && (long) scope.size() * months > MAX_CELLS) readMonths = Math.max(1, MAX_CELLS / scope.size());
        // Pasif aday sınırı: önce kapsamın pasifleri, sonra yalnız seçici için okunanlar
        int room = MAX_INACTIVE_READ;
        List<TechErrorChannel> inactiveRead = new ArrayList<>(inactiveScope.subList(0, Math.min(room, inactiveScope.size())));
        room -= inactiveRead.size();
        List<TechErrorChannel> pickerRead = new ArrayList<>(pickerOnly.subList(0, Math.min(room, pickerOnly.size())));
        // Sınır dışında kalan adayın raporu bilinmiyor: kapsamdaysa kesildi, takımı seçicide KALIR (seçilebilir takım gizlenmez)
        Set<Long> unknownTeams = new HashSet<>();
        if (inactiveRead.size() < inactiveScope.size()) {
            truncated = true;
            for (TechErrorChannel ch : inactiveScope.subList(inactiveRead.size(), inactiveScope.size())) unknownTeams.add(ch.getTeamId());
        }
        for (TechErrorChannel ch : pickerOnly.subList(pickerRead.size(), pickerOnly.size())) unknownTeams.add(ch.getTeamId());

        List<YearMonth> readWindow = window(end, readMonths);
        // Okuma penceresi bir ay geriden başlar: pencerenin ilk ayının "geçen ay"ı (canlı değişim oranı) — dilim 0
        int from = period(readWindow.get(0)) - 1;
        int to = period(end);
        Map<Long, Cell[]> slots = new LinkedHashMap<>();
        for (TechErrorChannel ch : scope) slots.put(ch.getId(), new Cell[readMonths + 1]);
        for (TechErrorChannel ch : inactiveRead) slots.put(ch.getId(), new Cell[readMonths + 1]);
        Map<Long, Long> pickerChannelTeam = new HashMap<>();
        for (TechErrorChannel ch : pickerRead) pickerChannelTeam.put(ch.getId(), ch.getTeamId());
        Set<Long> pickerTeamsWithReports = new HashSet<>();
        // Aktiflerin alanı yoksa ve pasif aday da yoksa okunacak değer yok (eski davranış: tek cümle). Pasif aday varsa
        // okunur — listelenip listelenmeyeceği ve alanları raporlarından anlaşılır.
        boolean candidates = !inactiveRead.isEmpty() || !pickerRead.isEmpty();
        boolean activeFields = !scope.isEmpty()
                && !resolveFields(requested, options(stats, null), firstLang == null ? "tr" : firstLang).fields().isEmpty();
        // 2. sorgu (dilimler): penceredeki bütün raporlar (kanal başına sorgu yok)
        if (candidates || activeFields) {
            List<Long> ids = new ArrayList<>(slots.keySet());
            ids.addAll(pickerChannelTeam.keySet());
            Map<String, Parsed> templates = new HashMap<>();
            long after = 0;
            int size = Math.max(1, chunkSize);
            // Satır ≤ okunan kanal × (ay + 1) (kanal × dönem tekil) — yalnız aktifler okunurken ≤ 2 × MAX_CELLS (MAX_CHUNKS)
            long maxChunks = (long) ids.size() * (readMonths + 1) / size + 2;
            for (long chunk = 0; chunk < maxChunks; chunk++) {
                List<TechErrorReportRepository.AnalyticsRow> page =
                        reports.findAnalyticsRows(ids, from, to, after, PageRequest.of(0, size));
                for (TechErrorReportRepository.AnalyticsRow row : page) {
                    if (row.getId() != null && row.getId() > after) after = row.getId();
                    if (row.getReportYear() == null || row.getReportMonth() == null || row.getId() == null) continue;
                    int idx = row.getReportYear() * 12 + row.getReportMonth() - from;
                    if (idx < 0 || idx > readMonths) continue;
                    Long pickerTeam = pickerChannelTeam.get(row.getChannelId());
                    if (pickerTeam != null) {                 // yalnız takım seçici için: değer çözülmez
                        if (idx >= 1) pickerTeamsWithReports.add(pickerTeam);
                        continue;
                    }
                    Cell[] cs = slots.get(row.getChannelId());
                    if (cs == null) continue;
                    String tj = row.getTemplateJson() == null ? "" : row.getTemplateJson();
                    Parsed parsed = templates.computeIfAbsent(tj, TechErrorAnalyticsService::parse);
                    Values vals = TechErrorValues.fromStored(row.getValuesJson(), parsed.tpl());
                    Map<String, Object> compact = new HashMap<>();
                    vals.fields().forEach((k, v) -> { if (v instanceof Number || v instanceof Boolean) compact.put(k, v); });
                    boolean sent = row.getLastSentAt() != null;
                    boolean queued = !sent && "QUEUED".equals(row.getSendState());
                    cs[idx] = new Cell(row.getId(), sent, queued, parsed, compact,
                            vals.targets() == null ? Map.of() : vals.targets());
                }
                if (page.size() < size) break;
            }
        }

        // Pasif kanalın okunan penceredeki en yeni raporunun dilimi (0 = raporu yok → listelenmez)
        Map<Long, Integer> newestOf = new HashMap<>();
        for (TechErrorChannel ch : inactiveRead) {
            Cell[] cs = slots.get(ch.getId());
            int n = 0;
            for (int i = readMonths; i >= 1; i--) {
                if (cs[i] != null) { n = i; break; }
            }
            newestOf.put(ch.getId(), n);
        }
        // Hücre sınırı, 2. adım: LİSTELENEN kanal (aktif + gösterilen aylarda raporu olan pasif) × ay ≤ MAX_CELLS olan en
        // büyük ay sayısı (ay azaldıkça listelenen pasif azalır — tekdüze, en çok readMonths adım)
        int kept = readMonths;
        while (kept > 1 && (long) kept * (scope.size() + countListed(inactiveRead, newestOf, readMonths - kept)) > MAX_CELLS) kept--;
        if (kept < months) truncated = true;
        int offset = readMonths - kept;                       // gösterilen ay i → okunan dilim offset + i + 1
        List<TechErrorChannel> listedInactive = new ArrayList<>();
        for (TechErrorChannel ch : inactiveRead) if (newestOf.getOrDefault(ch.getId(), 0) > offset) listedInactive.add(ch);
        int channelCount = activeCount + listedInactive.size();
        if (scope.size() + listedInactive.size() > MAX_CELLS) {              // tek ayda bile sığmıyor: önce aktifler
            listedInactive = new ArrayList<>(listedInactive.subList(0, Math.max(0, MAX_CELLS - scope.size())));
            truncated = true;
        }
        List<TechErrorChannel> listed = new ArrayList<>(scope);
        listed.addAll(listedInactive);

        // Pasiflerin güncel şablonları: aktiflerin tanımlamadığı alanlar için referans tür + seçilebilir alan
        for (TechErrorChannel ch : listedInactive) {
            String l = addStats(stats, ch, false);
            if (firstLang == null) firstLang = l;
        }
        Map<String, Integer> optionCounts = new HashMap<>();
        Map<String, Ref> options = options(stats, optionCounts);
        String lang = firstLang == null ? "tr" : firstLang;
        Resolution res = resolveFields(requested, options, lang);
        List<Ref> fields = res.fields();

        // Değerler: raporun kendi şablonu + CANLI hesap (geçen ay = bir önceki dilim, aynı kimlik + tür)
        List<YearMonth> window = window(end, kept);
        int newest = 0;
        List<ChannelSeries> out = new ArrayList<>(listed.size());
        for (TechErrorChannel ch : listed) {
            Cell[] cs = slots.get(ch.getId());
            Map<String, List<Double>> values = new LinkedHashMap<>();
            for (Ref f : fields) values.put(f.id(), new ArrayList<>(Collections.nCopies(kept, (Double) null)));
            List<ReportRef> reps = new ArrayList<>(Collections.nCopies(kept, (ReportRef) null));
            for (int i = 0; i < kept; i++) {
                Cell c = cs[offset + i + 1];
                if (c == null) continue;
                newest = Math.max(newest, i + 1);
                reps.set(i, new ReportRef(c.id(), c.sent(), c.queued()));
                Cell prev = cs[offset + i];
                Map<String, Object> previous = prev == null ? null : sameTypeFields(prev, c.parsed());
                Values applied = TechErrorCalc.apply(c.parsed().tpl(),
                        new Values(c.fields(), Map.of(), Map.of(), Map.of(), c.targets()), previous);
                for (Ref ref : fields) {
                    Field f = c.parsed().byId().get(ref.id());
                    if (f == null || !f.type().equals(ref.type()) || TechErrorTemplate.exempt(f, c.fields())) continue;
                    if (applied.fields().get(ref.id()) instanceof Number n && Double.isFinite(n.doubleValue())) {
                        values.get(ref.id()).set(i, n.doubleValue());
                    }
                }
            }
            Map<String, List<Double>> frozen = new LinkedHashMap<>();
            values.forEach((k, v) -> frozen.put(k, Collections.unmodifiableList(v)));
            out.add(new ChannelSeries(ch.getId(), ch.getName(), ch.getTeamId(), ch.isActive(), Collections.unmodifiableMap(frozen),
                    Collections.unmodifiableList(reps)));
        }

        // Takım seçici: aktif kanalı olan, okunan pencerede raporu olan pasif kanalı bulunan ya da raporu bilinmeyen
        // (aday sınırı) takımlar — ad sırasıyla
        Set<Long> pick = new HashSet<>(activeTeams);
        for (TechErrorChannel ch : inactiveRead) if (newestOf.getOrDefault(ch.getId(), 0) >= 1) pick.add(ch.getTeamId());
        pick.addAll(pickerTeamsWithReports);
        pick.addAll(unknownTeams);
        List<Long> teamsWithChannels = new ArrayList<>(pick);
        teamsWithChannels.sort(Comparator.<Long, String>comparing(id -> teamNames.getOrDefault(id, ""), col)
                .thenComparing(Comparator.<Long>naturalOrder()));

        // Etiketler: {kanal} tek kanallı kapsamda onun adı, {ay} / {yil} en yeni raporun ayı; uygulanamıyorsa düşer
        String single = listed.size() == 1 ? listed.get(0).getName() : null;
        YearMonth newestMonth = newest >= 1 ? window.get(newest - 1) : null;
        List<FieldRef> fieldRefs = new ArrayList<>();
        for (Ref f : fields) {
            List<Long> mismatch = new ArrayList<>();
            FieldStats st = stats.get(f.id());
            if (st != null) {
                for (TechErrorChannel ch : listed) {
                    String type = st.byChannel.get(ch.getId());
                    if (type != null && !type.equals(f.type())) mismatch.add(ch.getId());
                }
            }
            fieldRefs.add(new FieldRef(f.id(), label(f.label(), f.id(), single, newestMonth, f.lang()), f.type(), List.copyOf(mismatch)));
        }
        List<FieldOption> available = new ArrayList<>();
        for (Ref o : options.values()) {
            available.add(new FieldOption(o.id(), label(o.label(), o.id(), single, newestMonth, o.lang()), o.type(),
                    optionCounts.getOrDefault(o.id(), 0)));
        }
        return new Result(window, months, List.copyOf(fieldRefs), List.copyOf(available), List.copyOf(out), channelCount,
                truncated, List.copyOf(teamsWithChannels), res.dropped(), res.fallback());
    }

    /** Gösterilen aylarda (okunan dilim {@code offset}'ten sonra) raporu olan pasif aday sayısı. */
    private static int countListed(List<TechErrorChannel> inactiveRead, Map<Long, Integer> newestOf, int offset) {
        int n = 0;
        for (TechErrorChannel ch : inactiveRead) if (newestOf.getOrDefault(ch.getId(), 0) > offset) n++;
        return n;
    }

    private static Parsed parse(String templateJson) {
        Template t = TechErrorTemplate.fromStored(templateJson);
        Map<String, Field> m = new HashMap<>();
        for (Field f : t.allFields()) m.putIfAbsent(f.id(), f);
        return new Parsed(t, Map.copyOf(m));
    }

    /**
     * Geçen ayın değerlerinden, BU raporun şablonundaki alanlarla AYNI kimlik ve türdekiler — {@link
     * TechErrorReportService#sameTypeFields} kuralı (metin alanları zaten tutulmaz).
     */
    private static Map<String, Object> sameTypeFields(Cell prev, Parsed current) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : current.tpl().allFields()) {
            Object v = prev.fields().get(f.id());
            Field pf = prev.parsed().byId().get(f.id());
            if (v != null && pf != null && f.type().equals(pf.type())) out.put(f.id(), v);
        }
        return out;
    }

    /** İstenen alanların çözümü: alanlar, bulunamayanlar, hiçbiri bulunamadığı için varsayılana düşüldü mü. */
    record Resolution(List<Ref> fields, List<String> dropped, boolean fallback) { }

    /**
     * İstenen alanları referansa çevirir: kanallarda tanımlıysa çoğunluk türü / etiketi, değilse varsayılan şablondaki
     * (sayı / yüzde ise); ikisinde de yoksa düşer ({@code dropped}). İstek boşsa — ya da istenenlerin HİÇBİRİ bulunamadıysa
     * (takım değişti, eski paylaşılan bağlantı; {@code fallback}) — {@link #DEFAULT_FIELDS} (kanallarda varsa), onlar da
     * yoksa seçilebilir ilk iki alan.
     */
    static Resolution resolveFields(List<String> requested, Map<String, Ref> options, String lang) {
        List<Ref> out = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        if (requested != null && !requested.isEmpty()) {
            Map<String, Field> defaults = new HashMap<>();
            String dl = "en".equals(lang) ? "en" : "tr";
            for (Field f : TechErrorTemplate.defaults(dl).allFields()) defaults.putIfAbsent(f.id(), f);
            for (String id : requested) {
                Ref o = options.get(id);
                if (o != null) {
                    out.add(o);
                    continue;
                }
                Field d = defaults.get(id);
                if (TechErrorTemplate.trendable(d)) out.add(new Ref(d.id(), d.label(), d.type(), dl));
                else dropped.add(id);
            }
            if (!out.isEmpty()) return new Resolution(List.copyOf(out), List.copyOf(dropped), false);
        }
        for (String id : DEFAULT_FIELDS) {
            Ref o = options.get(id);
            if (o != null) out.add(o);
        }
        if (out.isEmpty()) {
            for (Ref o : options.values()) {
                if (out.size() >= DEFAULT_FIELDS.size()) break;
                out.add(o);
            }
        }
        return new Resolution(List.copyOf(out), List.copyOf(dropped), !dropped.isEmpty());
    }

    /**
     * Etiketin yer tutucuları: {@code channel} null ise {@code {kanal}} / {@code {channel}}, {@code ym} null ise ay / yıl
     * yer tutucuları DÜŞER (çok kanallı kapsamda tek kanal adı, raporu olmayan kapsamda bir ay yanıltırdı); kalanlar
     * rapor ekranıyla aynı çözümle ({@link TechErrorTemplate#resolve}). Boş kalırsa alan kimliği.
     */
    static String label(String raw, String id, String channel, YearMonth ym, String lang) {
        if (raw == null || raw.isBlank()) return id;
        String s = raw;
        boolean stripped = false;
        if (channel == null && (s.contains("{kanal}") || s.contains("{channel}"))) {
            s = s.replace("{kanal}", "").replace("{channel}", "");
            stripped = true;
        }
        if (ym == null && (s.contains("{ay}") || s.contains("{month}") || s.contains("{yil}") || s.contains("{yıl}") || s.contains("{year}"))) {
            s = s.replace("{ay}", "").replace("{month}", "").replace("{yil}", "").replace("{yıl}", "").replace("{year}", "");
            stripped = true;
        }
        s = TechErrorTemplate.resolve(s, channel == null ? "" : channel, ym == null ? 2000 : ym.getYear(),
                ym == null ? 1 : ym.getMonthValue(), "en".equals(lang) ? "en" : "tr");
        if (stripped) s = tidy(s);
        return s.isBlank() ? id : s;
    }

    /** Düşen yer tutucunun bıraktığı boş parantezi, çift boşluğu ve uçtaki ayıracı temizler (doğrusal tarama). */
    static String tidy(String s) {
        String t = s.replace("()", "").replace("( )", "");
        StringBuilder b = new StringBuilder(t.length());
        boolean space = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) {
                space = true;
                continue;
            }
            if (space && b.length() > 0) b.append(' ');
            space = false;
            b.append(c);
        }
        int start = 0;
        int stop = b.length();
        while (start < stop && isEdgeSeparator(b.charAt(start))) start++;
        while (stop > start && isEdgeSeparator(b.charAt(stop - 1))) stop--;
        return b.substring(start, stop).trim();
    }

    private static boolean isEdgeSeparator(char c) {
        return c == ' ' || c == '-' || c == '–' || c == '—' || c == '·' || c == ':' || c == ',' || c == '/';
    }

    /** {@code end}'le biten {@code n} ay, eskiden yeniye. */
    static List<YearMonth> window(YearMonth end, int n) {
        List<YearMonth> out = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) out.add(end.minusMonths(i));
        return List.copyOf(out);
    }

    static int period(YearMonth ym) {
        return ym.getYear() * 12 + ym.getMonthValue();
    }
}
