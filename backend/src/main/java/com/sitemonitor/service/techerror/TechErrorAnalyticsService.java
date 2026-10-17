package com.sitemonitor.service.techerror;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Kanallar arası / aylar arası analiz (2026-10-18, R22 — kullanıcı onayı: "takımın kanallarında aynı kimlikli alanlar
 * (error-rate, total-tx, sla-rate) 12 ay × kanal tablo ve grafik"). YAZMA YOK; yalnız raporlara GİRİLMİŞ (ve hesaplanıp
 * SAKLANMIŞ) değerler okunur — eski aylar yeniden hesaplanmaz.
 *
 * <p><b>Kurallar.</b>
 * <ul>
 *   <li>Pencere: İstanbul'a göre BİTMİŞ son {@code months} ay (6 / 12 / 24), önceki takvim ayıyla biter (çağıran verir).</li>
 *   <li>Yalnız AKTİF kanallar; takım süzgeci yoksa okunabilen bütün takımların kanalları.</li>
 *   <li>Referans tür: alanı sayı / yüzde olarak tanımlayan İLK kanalın GÜNCEL şablonundaki tür; hiçbir kanalda yoksa
 *       varsayılan şablondaki. Bir ayın değeri yalnız o raporun KENDİ kopyalanmış şablonunda alan AYNI kimlik VE AYNI
 *       türdeyse alınır (trend kuralı, {@link TechErrorTrend}); tür farkı, muaf alan ({@code skip_if_no} "Hayır"),
 *       raporu olmayan ay ve değeri olmayan alan {@code null} — asla 0 değil.</li>
 *   <li>Maliyet SABİT: bir kanal sorgusu + pencerenin bütün raporları için bir sorgu (kanal sayısından bağımsız); her
 *       rapor bir kez çözülür (aynı şablon metni bir kez). Hücre sınırı {@link #MAX_CELLS} (kanal × ay): aşılırsa en YENİ
 *       aylar kalır ve {@code truncated} işaretlenir.</li>
 *   <li>Sonuç {@link #CACHE_TTL} boyunca (okunabilen takım kümesi, takım, ay, alanlar, bitiş ayı) anahtarıyla hafızada;
 *       okuma hatası hafızaya alınmaz (çağıran hatayı görür — "veri yok" denmez).</li>
 * </ul>
 */
@Service
public class TechErrorAnalyticsService {

    public static final List<Integer> MONTH_CHOICES = List.of(6, 12, 24);
    public static final int DEFAULT_MONTHS = 12;
    public static final int MAX_FIELDS = 4;
    /** Kanal × ay hücre sınırı — aşılırsa en yeni aylar kalır. */
    public static final int MAX_CELLS = 1200;
    /** Alan seçilmemişse (ve kanallarda varsa) bu ikisi. */
    public static final List<String> DEFAULT_FIELDS = List.of("error-rate", "total-tx");
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);
    /** Şablon alan kimliği kuralı (TechErrorTemplate ile aynı). */
    public static final Pattern FIELD_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,39}$");

    private final TechErrorChannelRepository channels;
    private final TechErrorReportRepository reports;
    private final Cache<String, Result> memo = Caffeine.newBuilder().maximumSize(256).expireAfterWrite(CACHE_TTL).build();

    public TechErrorAnalyticsService(TechErrorChannelRepository channels, TechErrorReportRepository reports) {
        this.channels = channels;
        this.reports = reports;
    }

    /** Seçilen / çizilen alan (referans tür ve etiket). */
    public record FieldRef(String id, String label, String type) { }

    /** Seçilebilir alan: kanalların GÜNCEL şablonlarındaki sayı / yüzde alanları; {@code channels} = aynı türle tanımlayan kanal sayısı. */
    public record FieldOption(String id, String label, String type, int channels) { }

    /** Bir ayın raporu (yoksa ayın yeri {@code null}). */
    public record ReportRef(long id, boolean sent) { }

    /** Kanalın serileri: alan → ay ay değerler (eskiden yeniye, boş ay null) + ay ay rapor. */
    public record ChannelSeries(long id, String name, long teamId, Map<String, List<Double>> values, List<ReportRef> reports) { }

    /**
     * @param months           pencere (eskiden yeniye; kesildiyse yalnız en yeni aylar)
     * @param monthsRequested  istenen ay sayısı
     * @param channelCount     kapsamdaki aktif kanal sayısı (kesilmeden önce)
     * @param teamsWithChannels okunabilen ve aktif kanalı olan takımlar (takım seçici için; ad sırasıyla)
     */
    public record Result(List<YearMonth> months, int monthsRequested, List<FieldRef> fields, List<FieldOption> available,
                         List<ChannelSeries> channels, int channelCount, boolean truncated, List<Long> teamsWithChannels) { }

    /** Hafızayı boşaltır (testler). */
    public void invalidate() {
        memo.invalidateAll();
    }

    /**
     * @param readableTeamIds okuyucunun bu modülde okuyabildiği takımlar (kapsam + modül görünürlüğü çağıranda)
     * @param teamFilter      tek takım (null = okunabilen hepsi); okunabilen kümede olmalı (çağıran denetler)
     * @param months          {@link #MONTH_CHOICES}'tan biri
     * @param requested       istenen alan kimlikleri (null / boş = varsayılan), ≤ {@link #MAX_FIELDS}
     * @param end             pencerenin son ayı (önceki takvim ayı, İstanbul)
     * @param teamNames       sıralama için takım adları (sonuca girmez)
     */
    public Result analytics(Collection<Long> readableTeamIds, Long teamFilter, int months, List<String> requested,
                            YearMonth end, Map<Long, String> teamNames) {
        if (!MONTH_CHOICES.contains(months)) throw new IllegalArgumentException("months");
        Set<Long> teams = new TreeSet<>();
        for (Long t : readableTeamIds == null ? List.<Long>of() : readableTeamIds) if (t != null) teams.add(t);
        List<String> fields = requested == null ? List.of() : List.copyOf(new LinkedHashSet<>(requested));
        if (fields.size() > MAX_FIELDS) throw new IllegalArgumentException("fields");
        String key = "t=" + teams + "|f=" + teamFilter + "|m=" + months + "|fl=" + String.join(",", fields) + "|e=" + end;
        Map<Long, String> names = teamNames == null ? Map.of() : teamNames;
        return memo.get(key, k -> compute(teams, teamFilter, months, fields, end, names));
    }

    private Result compute(Set<Long> teams, Long teamFilter, int months, List<String> requested, YearMonth end,
                           Map<Long, String> teamNames) {
        if (teams.isEmpty()) return new Result(window(end, months), months, List.of(), List.of(), List.of(), 0, false, List.of());
        // 1. sorgu: okunabilen takımların kanalları (takım seçici + kapsam)
        Collator col = Collator.getInstance(Locale.forLanguageTag("tr"));
        col.setStrength(Collator.SECONDARY);
        List<TechErrorChannel> active = new ArrayList<>();
        for (TechErrorChannel ch : channels.findByTeamIdIn(teams)) if (ch.isActive() && teams.contains(ch.getTeamId())) active.add(ch);
        active.sort(Comparator.<TechErrorChannel, String>comparing(ch -> teamNames.getOrDefault(ch.getTeamId(), ""), col)
                .thenComparing(TechErrorChannel::getTeamId)
                .thenComparing(ch -> ch.getSortOrder() == null ? Integer.MAX_VALUE : ch.getSortOrder())
                .thenComparing(ch -> ch.getName() == null ? "" : ch.getName(), col)
                .thenComparing(TechErrorChannel::getId));
        List<Long> teamsWithChannels = new ArrayList<>();
        for (TechErrorChannel ch : active) if (!teamsWithChannels.contains(ch.getTeamId())) teamsWithChannels.add(ch.getTeamId());
        List<TechErrorChannel> scope = new ArrayList<>();
        for (TechErrorChannel ch : active) if (teamFilter == null || teamFilter.equals(ch.getTeamId())) scope.add(ch);

        // Seçilebilir alanlar: kapsamdaki kanalların GÜNCEL şablonları (ilk tanımlayan kanal etiketi ve türü verir)
        Map<String, FieldOption> options = new LinkedHashMap<>();
        String firstLang = null;
        for (TechErrorChannel ch : scope) {
            Template t = TechErrorTemplate.fromStored(ch.getTemplateJson());
            if (firstLang == null) firstLang = t.lang();
            Set<String> seen = new LinkedHashSet<>();
            for (Field f : t.allFields()) {
                if (!TechErrorTemplate.trendable(f) || !seen.add(f.id())) continue;
                FieldOption o = options.get(f.id());
                if (o == null) options.put(f.id(), new FieldOption(f.id(), f.label(), f.type(), 1));
                else if (o.type().equals(f.type())) options.put(f.id(), new FieldOption(o.id(), o.label(), o.type(), o.channels() + 1));
            }
        }
        List<FieldRef> fields = resolveFields(requested, options, firstLang == null ? "tr" : firstLang);

        // Hücre sınırı: kanal × ay > MAX_CELLS → en yeni aylar kalır (kanal sayısı tek başına sınırı aşarsa ilk kanallar)
        int channelCount = scope.size();
        boolean truncated = false;
        int kept = months;
        if (channelCount > MAX_CELLS) {
            scope = new ArrayList<>(scope.subList(0, MAX_CELLS));
            truncated = true;
        }
        if (!scope.isEmpty() && (long) scope.size() * months > MAX_CELLS) {
            kept = Math.max(1, MAX_CELLS / scope.size());
            truncated = true;
        }
        List<YearMonth> window = window(end, kept);
        int from = period(window.get(0));
        int to = period(end);

        Map<Long, ChannelSeries> byId = new LinkedHashMap<>();
        for (TechErrorChannel ch : scope) {
            Map<String, List<Double>> values = new LinkedHashMap<>();
            for (FieldRef f : fields) values.put(f.id(), new ArrayList<>(Collections.nCopies(kept, (Double) null)));
            byId.put(ch.getId(), new ChannelSeries(ch.getId(), ch.getName(), ch.getTeamId(), values,
                    new ArrayList<>(Collections.nCopies(kept, (ReportRef) null))));
        }
        // 2. sorgu: penceredeki bütün raporlar (kanal başına sorgu yok); alan yoksa okunacak değer de yok
        if (!byId.isEmpty() && !fields.isEmpty()) {
            Map<String, Template> templates = new HashMap<>();
            Map<String, Map<String, Field>> fieldMaps = new HashMap<>();
            for (TechErrorReportRepository.AnalyticsRow row : reports.findAnalyticsRows(byId.keySet(), from, to)) {
                ChannelSeries s = byId.get(row.getChannelId());
                if (s == null || row.getReportYear() == null || row.getReportMonth() == null) continue;
                int idx = row.getReportYear() * 12 + row.getReportMonth() - from;
                if (idx < 0 || idx >= kept) continue;
                s.reports().set(idx, new ReportRef(row.getId(), row.getLastSentAt() != null));
                String tj = row.getTemplateJson() == null ? "" : row.getTemplateJson();
                Template tpl = templates.computeIfAbsent(tj, TechErrorTemplate::fromStored);
                Map<String, Field> own = fieldMaps.computeIfAbsent(tj, x -> {
                    Map<String, Field> m = new HashMap<>();
                    for (Field f : tpl.allFields()) m.putIfAbsent(f.id(), f);
                    return m;
                });
                Values vals = TechErrorValues.fromStored(row.getValuesJson(), tpl);
                for (FieldRef ref : fields) {
                    Field f = own.get(ref.id());
                    if (f == null || !f.type().equals(ref.type()) || TechErrorTemplate.exempt(f, vals.fields())) continue;
                    if (vals.fields().get(ref.id()) instanceof Number n && Double.isFinite(n.doubleValue())) {
                        s.values().get(ref.id()).set(idx, n.doubleValue());
                    }
                }
            }
        }
        List<ChannelSeries> out = new ArrayList<>(byId.size());
        for (ChannelSeries s : byId.values()) {
            Map<String, List<Double>> frozen = new LinkedHashMap<>();
            s.values().forEach((k, v) -> frozen.put(k, Collections.unmodifiableList(v)));
            out.add(new ChannelSeries(s.id(), s.name(), s.teamId(), Collections.unmodifiableMap(frozen),
                    Collections.unmodifiableList(s.reports())));
        }
        return new Result(window, months, List.copyOf(fields), List.copyOf(options.values()), List.copyOf(out), channelCount,
                truncated, List.copyOf(teamsWithChannels));
    }

    /**
     * İstenen alanları referansa çevirir: kanallarda tanımlıysa ilk tanımlayanın türü / etiketi, değilse varsayılan
     * şablondaki (sayı / yüzde ise); ikisinde de yoksa düşer. İstek boşsa {@link #DEFAULT_FIELDS} (kanallarda varsa),
     * onlar da yoksa seçilebilir ilk iki alan.
     */
    static List<FieldRef> resolveFields(List<String> requested, Map<String, FieldOption> options, String lang) {
        List<FieldRef> out = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            for (String id : DEFAULT_FIELDS) {
                FieldOption o = options.get(id);
                if (o != null) out.add(new FieldRef(o.id(), o.label(), o.type()));
            }
            if (out.isEmpty()) {
                for (FieldOption o : options.values()) {
                    if (out.size() >= DEFAULT_FIELDS.size()) break;
                    out.add(new FieldRef(o.id(), o.label(), o.type()));
                }
            }
            return out;
        }
        Map<String, Field> defaults = new HashMap<>();
        for (Field f : TechErrorTemplate.defaults("en".equals(lang) ? "en" : "tr").allFields()) defaults.putIfAbsent(f.id(), f);
        for (String id : requested) {
            FieldOption o = options.get(id);
            if (o != null) {
                out.add(new FieldRef(o.id(), o.label(), o.type()));
                continue;
            }
            Field d = defaults.get(id);
            if (TechErrorTemplate.trendable(d)) out.add(new FieldRef(d.id(), d.label(), d.type()));
        }
        return out;
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
