package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YÖNETİCİ ÖZETİ (2026-10-14, kullanıcı isteği: "raporun başında o kanalın teknik hata oranı, map yapılmamış ve kota
 * hatası adetleri, SLA taahhüdü verilir"). Şablondaki cümle kalıpları girilen rakamlarla dolar:
 * <ul>
 *   <li>{@code {f:<alan>}} → alanın biçimli değeri (sayı / yüzde KALIN yazılır);</li>
 *   <li>{@code {f:<evet/hayır alanı>?evet metni|hayır metni}} → cevaba göre metin;</li>
 *   <li>{@code {kanal}} / {@code {ay}} / {@code {yil}} → kanal ve dönem.</li>
 * </ul>
 * Değeri boş, muaf ("kesinti yok") ya da şablonda olmayan bir alana dayanan CÜMLE kendiliğinden düşer — yanlış ya da
 * yarım cümle yönetime gitmez. Düz cümleler tek paragrafta akar; madde işaretiyle başlayan satır ayrı satırdır.
 * Kullanıcı metni elle düzenlediyse ({@code texts[<bölüm>]}) o metin kullanılır; boş elle metin otomatik sayılır.
 * Çıktı {@link TechErrorRichText} işaretlemesidir; uygulama aynı kuralı {@code techErrorModel.summaryAutoText} /
 * {@code summaryEffective} ile uygular.
 */
public final class TechErrorSummary {

    private TechErrorSummary() {}

    /** Bir cümlede en çok bu kadar başvuru okunur: 500 karakterlik cümleye sığabilecek en çok "{f:a}" kadar. */
    static final int MAX_REFS = 100;

    /**
     * Başvuru türleri (2026-10-17, 4b): {@code f} değer ({@code {f:id}} / {@code {f:id?evet|hayır}}), {@code d} önceki aya
     * göre fark ({@code {d:id}}), {@code t} alanın etkin hedefi ({@code {t:id}}), {@code dt} değerin hedefe uzaklığı
     * ({@code {dt:id}}). Yalnız {@code f} seçim ({@code ?…|…}) alır.
     */
    static final List<String> KINDS = List.of("f", "d", "t", "dt");

    /** Bir başvuru; {@code kind} {@link #KINDS}, {@code start}/{@code end} kalıptaki konum ({@code end} hariç). */
    public record Ref(String kind, String id, String yes, String no, int start, int end) { }

    /** Kalıbın {@code i} konumundaki "{" bir başvuru öneki mi: "f" / "d" / "t" / "dt", değilse null. */
    private static String kindAt(String line, int i) {
        for (String k : KINDS) if (line.startsWith("{" + k + ":", i)) return k;
        return null;
    }

    /** Doğrusal tarama: "{f:" / "{d:" / "{t:" / "{dt:" … "}" (iç içe yok; kapanmayan başvuru düz metin sayılır). */
    public static List<Ref> refs(String line) {
        List<Ref> out = new ArrayList<>();
        if (line == null) return out;
        int i = line.indexOf('{');
        while (i >= 0 && out.size() < MAX_REFS) {
            String kind = kindAt(line, i);
            if (kind == null) {
                i = line.indexOf('{', i + 1);
                continue;
            }
            int bodyStart = i + kind.length() + 2;
            int close = line.indexOf('}', bodyStart);
            if (close < 0) break;
            String body = line.substring(bodyStart, close);
            // İç içe süslü parantez okunamaz ("{f:x?{ay} ayında|…}" ilk "}"de biterdi ve bozuk basılırdı): başvuru
            // sayılmaz → malformed() true → şablon kaydı reddeder
            if (body.indexOf('{') >= 0) {
                i = line.indexOf('{', close + 1);
                continue;
            }
            int q = "f".equals(kind) ? body.indexOf('?') : -1;
            String id = TechErrorRichText.strip(q < 0 ? body : body.substring(0, q));
            String yes = null, no = null;
            if (q >= 0) {
                String choice = body.substring(q + 1);
                int bar = choice.indexOf('|');
                // Kesilmez: başvuru gövdeleri en çok 500 karakterlik cümlenin ayrık parçalarıdır (sınır zaten var)
                yes = bar < 0 ? choice : choice.substring(0, bar);
                no = bar < 0 ? "" : choice.substring(bar + 1);
            }
            if (TechErrorTemplate.ID.matcher(id).matches()) out.add(new Ref(kind, id, yes, no, i, close + 1));
            i = line.indexOf('{', close + 1);
        }
        return out;
    }

    /** Kalıpta çözülemeyen bir başvuru öneki var mı (kapanmamış, iç içe ya da geçersiz kimlik)? Şablon kaydında reddedilir. */
    public static boolean malformed(String line) {
        if (line == null) return false;
        int count = 0;
        for (int i = line.indexOf('{'); i >= 0; i = line.indexOf('{', i + 1)) if (kindAt(line, i) != null) count++;
        return count != refs(line).size();
    }

    /**
     * Özetin GÖRÜNEN metni: elle düzenlenmişse o (yer tutucular çözülür), değilse kalıplardan üretilen. BOŞ elle metin
     * otomatik sayılır (inceleme 2026-10-14: boş özetle "elle düzenle" özeti kalıcı olarak boş bırakıyordu).
     */
    public static String effective(Section s, Template t, Values v, String channel, int year, int month) {
        return effective(s, t, v, null, channel, year, month);
    }

    /** {@link #effective(Section, Template, Values, String, int, int)} + geçen ayın değerleri ({@code {d:…}} farkları). */
    public static String effective(Section s, Template t, Values v, Map<String, Object> previous, String channel, int year, int month) {
        String override = v.texts().get(s.id());
        if (override != null && !TechErrorRichText.blank(override)) return TechErrorTemplate.resolveText(override, channel, year, month, t.lang());
        return auto(s, t, v.fields(), channel, year, month, previous, v.targets(), v.units());
    }

    /**
     * Kalıplardan üretilen metin (rich text). Hiç cümle kalmadıysa boş. Bir cümlenin madde olup olmadığına KALIBI
     * karar verir (değer ya da kanal adı "1. …" ile başlasa da paragraf akışı bozulmaz). Metin sınırı aşılınca sonraki
     * cümleler üretilmez — sonuç zaten sınırda kesilir (inceleme 2026-10-14: her PDF / e-postada megabaytlarca metin
     * kurulmasın).
     */
    public static String auto(Section s, Template t, Map<String, Object> fields, String channel, int year, int month) {
        return auto(s, t, fields, channel, year, month, null, Map.of());
    }

    /** {@link #auto(Section, Template, Map, String, int, int)} + geçen ayın değerleri ve raporun hedefleri (2026-10-17, 4b). */
    public static String auto(Section s, Template t, Map<String, Object> fields, String channel, int year, int month,
                              Map<String, Object> previous, Map<String, Object> targets) {
        return auto(s, t, fields, channel, year, month, previous, targets, Map.of());
    }

    /**
     * {@link #auto(Section, Template, Map, String, int, int, Map, Map)} + kısaltma üsleri (2026-10-18): kısaltmayla girilmiş
     * sayı cümlede de kısaltmasıyla ("4,4G"), farkı kısa ({@link TechErrorFormat#delta(Field, Object, Object, String, Integer)}).
     */
    public static String auto(Section s, Template t, Map<String, Object> fields, String channel, int year, int month,
                              Map<String, Object> previous, Map<String, Object> targets, Map<String, Integer> units) {
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        StringBuilder out = new StringBuilder();
        boolean inParagraph = false;
        for (String line : s.lines()) {
            if (out.length() > TechErrorTemplate.MAX_RESOLVED_TEXT) break;
            String r = line(line, byId, fields, previous, targets, units, t.lang(), channel, year, month);
            if (r == null || TechErrorRichText.blank(r)) continue;
            boolean item = isItem(line);
            // Madde kalıbı boş gövdeye çözüldüyse ("- {f:x?Var|}" → "-") cümle düşer: tek başına işaret basılmaz
            if (item && !isItem(r)) continue;
            // Paragraf başındaki düz cümle değer ya da kanal adıyla "1. " / "- " / "* " ile başlıyorsa işaretten sonraki
            // boşluk bölünmez boşluk olur: PDF / e-posta / önizleme onu madde olarak yeniden okumasın
            if (!item && !inParagraph) r = keepParagraph(r);
            if (item) {
                if (!out.isEmpty()) out.append('\n');
                out.append(r);
                inParagraph = false;
            } else if (inParagraph) {
                out.append(' ').append(r);
            } else {
                if (!out.isEmpty()) out.append("\n\n");
                out.append(r);
                inParagraph = true;
            }
        }
        return TechErrorTemplate.capResolved(out.toString(), TechErrorTemplate.MAX_RESOLVED_TEXT);
    }

    // ── Özet blokları (2026-10-17): gömülü bölümlerin değer ve açıklamaları ilgili cümlenin altında ─────────────────

    /**
     * Özetin çizim parçası: {@code text} (zengin metin paragrafı / maddeleri) ya da {@code note} — özete GÖMÜLÜ bir gösterge
     * bölümünün dolu uzun açıklama alanı ({@code section} / {@code field} kimlikleri, {@code label} alanın çözülmüş adı —
     * çizimde notun başlığı —, {@code text} saklı değerin kendisi). Metin bloğunda kimlik ve ad {@code null}.
     */
    public record Block(String kind, String text, String section, String field, String label) {
        public static Block text(String t) { return new Block("text", t, null, null, null); }

        public static Block note(String section, String field, String label, String text) {
            return new Block("note", text, section, field, label);
        }

        public boolean note() { return "note".equals(kind); }
    }

    /** {@link #blocks(Section, Template, Map, Map, String, int, int)} saklı değerlerle (hesaplar uygulanmış olmalı). */
    public static List<Block> blocks(Section s, Template t, Values v, String channel, int year, int month) {
        return blocks(s, t, v.fields(), v.texts(), channel, year, month, null, v.targets(), v.units());
    }

    /** Saklı değerler + geçen ayın değerleri ({@code {d:…}} farkları; 2026-10-17, 4b). */
    public static List<Block> blocks(Section s, Template t, Values v, Map<String, Object> previous, String channel, int year, int month) {
        return blocks(s, t, v.fields(), v.texts(), channel, year, month, previous, v.targets(), v.units());
    }

    public static List<Block> blocks(Section s, Template t, Map<String, Object> fields, Map<String, String> texts,
                                     String channel, int year, int month) {
        return blocks(s, t, fields, texts, channel, year, month, null, Map.of());
    }

    /**
     * Özetin BLOKLARI (2026-10-17, kullanıcı kararı: "map yapılmamış / kota açıklaması ilgili cümlenin hemen altında
     * görünsün"). PDF, e-posta ve rapor düzenleyicisinin önizlemesi bunu çizer; istemcide aynı algoritma
     * {@code techErrorModel.summaryBlocks} (ortak doğruluk tablosu {@code TechErrorSummaryBlocksTest.BLOCKS_TABLE}).
     * <ul>
     *   <li>Not: özete gömülü bir gösterge bölümünün ({@link TechErrorTemplate#embeddedSections}) dolu, muaf olmayan
     *       {@code textarea} alanı; başlığı alanın adıdır.</li>
     *   <li>Söylenmemiş değer ({@link #facts}): gömülü bölümün dolu, muaf olmayan sayı / yüzde / evet-hayır / kısa metin
     *       alanı, TUTULAN hiçbir cümle ona başvurmuyorsa — "Ad: **değer**" satırı (sayı / yüzde kalın). Cümlesi (eksik başka
     *       bir rakam yüzünden) düşen bir adet PDF'ten sessizce kaybolmaz (inceleme 2026-10-17).</li>
     *   <li>Elle metin varsa (boş olmayan {@code texts[s.id]}): [çözülmüş elle metin] + bütün notlar (şablon sırasıyla) en
     *       sonda — söylenmemiş değer eklenmez ("elle düzenle" yalnız {@link #auto} metnini kopyalar; adetleri metne yazan
     *       kullanıcıdır).</li>
     *   <li>Otomatik: cümleler {@link #auto} ile AYNI kurallarla işlenir. Tutulan bir cümle gömülü bir bölümün HERHANGİ bir
     *       alanına başvuruyorsa (henüz yerleşmemiş bölümler, şablon sırasıyla) bölüm oraya yerleşir: eklenecek değer ya da
     *       not VARSA o ana kadarki metin bir blok olur, bölümün söylenmemiş değerleri (tek metin bloğu) ve notları ardına
     *       eklenir ve SONRAKİ cümle yeni paragraf başlatır; eklenecek bir şey yoksa paragraf bölünmez (inceleme 2026-10-17:
     *       bütün adetleri girilmiş notsuz varsayılan rapor tek paragraf kalır, 20.139 ile aynı). Sonda yerleşmemiş bölümler
     *       aynı biçimde en alta eklenir.</li>
     *   <li>Hiç gömülü bölüm yoksa tek metin bloğu = {@link #auto}. Metin sınırı ({@link TechErrorTemplate#MAX_RESOLVED_TEXT})
     *       yalnız METNE uygulanır ve {@link #auto} ile aynı ölçülür: metin blokları aralarına "\n\n" konarak birleşik sayılır;
     *       sınır aşılırsa kesim noktasından sonra başlayan metin blokları düşer, kalan son metin bloğu kesim noktasında
     *       "…" ile biter. Notlar sınıra girmez ve hiç düşmez. Sınırdan sonraki cümleler üretilmez ve "söylenmiş" sayılmaz
     *       (değerleri satır olarak basılır — kaybolmaz).</li>
     * </ul>
     */
    public static List<Block> blocks(Section s, Template t, Map<String, Object> fields, Map<String, String> texts,
                                     String channel, int year, int month, Map<String, Object> previous, Map<String, Object> targets) {
        return blocks(s, t, fields, texts, channel, year, month, previous, targets, Map.of());
    }

    /** {@link #blocks(Section, Template, Map, Map, String, int, int, Map, Map)} + kısaltma üsleri (2026-10-18). */
    public static List<Block> blocks(Section s, Template t, Map<String, Object> fields, Map<String, String> texts,
                                     String channel, int year, int month, Map<String, Object> previous, Map<String, Object> targets,
                                     Map<String, Integer> units) {
        Map<String, Object> vals = fields == null ? Map.of() : fields;
        List<Section> embedded = TechErrorTemplate.embeddedSections(t, s.id());
        List<Block> out = new ArrayList<>();
        String override = texts == null ? null : texts.get(s.id());
        if (override != null && !TechErrorRichText.blank(override)) {
            out.add(Block.text(TechErrorTemplate.resolveText(override, channel, year, month, t.lang())));
            for (Section e : embedded) out.addAll(notes(e, t, vals, channel, year, month));
            return out;
        }
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        Told pass = told(s, byId, vals, previous, targets, units, t.lang(), channel, year, month);
        List<String> kept = pass.kept();
        Set<String> told = pass.told();
        // 2. geçiş: paragraf akışı auto ile aynı; gömülü bölüm ilk başvurulduğu cümlenin ardına yerleşir
        boolean[] placed = new boolean[embedded.size()];
        StringBuilder buf = new StringBuilder();
        int flushed = 0;               // blok olmuş metnin birleşik uzunluğu ("\n\n" ayraçlar dahil)
        boolean inParagraph = false;
        for (int i = 0; i < kept.size(); i++) {
            if (joined(flushed, buf) > TechErrorTemplate.MAX_RESOLVED_TEXT) break;
            String r = kept.get(i);
            if (r == null) continue;
            String line = s.lines().get(i);
            boolean item = isItem(line);
            if (!item && !inParagraph) r = keepParagraph(r);
            if (item) {
                if (!buf.isEmpty()) buf.append('\n');
                buf.append(r);
                inParagraph = false;
            } else if (inParagraph) {
                buf.append(' ').append(r);
            } else {
                if (!buf.isEmpty()) buf.append("\n\n");
                buf.append(r);
                inParagraph = true;
            }
            if (embedded.isEmpty()) continue;
            Set<String> ids = new HashSet<>();
            for (Ref ref : refs(line)) ids.add(ref.id());
            for (int k = 0; k < embedded.size(); k++) {
                if (placed[k] || !referencesAny(embedded.get(k), ids)) continue;
                placed[k] = true;
                List<Block> add = embeddedBlocks(embedded.get(k), t, vals, told, units, channel, year, month);
                if (add.isEmpty()) continue;                      // eklenecek bir şey yok: paragraf bölünmez
                flushed = flush(out, buf, flushed);
                flushed = append(out, add, flushed);
                inParagraph = false;
            }
        }
        flushed = flush(out, buf, flushed);
        for (int k = 0; k < embedded.size(); k++) {
            if (!placed[k]) flushed = append(out, embeddedBlocks(embedded.get(k), t, vals, told, units, channel, year, month), flushed);
        }
        return capText(out);
    }

    /** 1. geçişin sonucu: kalıp satırı başına tutulan cümle ({@code null} = düşen) ve "söylenen" alanlar. */
    private record Told(List<String> kept, Set<String> told) { }

    /**
     * 1. geçiş: tutulan cümleler (null = düşen) ve tutulan cümlelerin başvurduğu alanlar ("söylenenler"). Metin sınırı
     * {@link #auto} ile aynı ölçüyle burada da işler: sınırdan sonraki cümle üretilmez, alanları söylenmiş sayılmaz.
     * {@link #blocks} (söylenmemiş değerler) ve {@link #shownValues} (gösterge bölümünde tekrar basılmayacaklar) AYNI
     * sonucu kullanır — iki kural ayrışamaz.
     */
    private static Told told(Section s, Map<String, Field> byId, Map<String, Object> vals, Map<String, Object> previous,
                             Map<String, Object> targets, Map<String, Integer> units, String lang, String channel, int year, int month) {
        List<String> kept = new ArrayList<>(s.lines().size());
        Set<String> told = new HashSet<>();
        int len = 0;
        boolean par = false;
        for (String line : s.lines()) {
            if (len > TechErrorTemplate.MAX_RESOLVED_TEXT) {
                kept.add(null);
                continue;
            }
            String r = line(line, byId, vals, previous, targets, units, lang, channel, year, month);
            // Madde kalıbı boş gövdeye çözüldüyse ("- {f:x?Var|}" → "-") cümle düşer (auto ile aynı)
            boolean ok = r != null && !TechErrorRichText.blank(r) && (!isItem(line) || isItem(r));
            kept.add(ok ? r : null);
            if (!ok) continue;
            // Yalnız DEĞERİ yazan başvuru alanı "söyler"; fark / hedef / uzaklık ({d:} {t:} {dt:}) değeri yazmaz (inceleme 2026-10-17)
            for (Ref ref : refs(line)) if ("f".equals(ref.kind())) told.add(ref.id());
            boolean item = isItem(line);
            len += (len > 0 ? (item ? 1 : par ? 1 : 2) : 0) + r.length();
            par = !item;
        }
        return new Told(kept, told);
    }

    // ── Veri tekrarı yok (2026-10-18) ────────────────────────────────────────────────────────────────

    /**
     * Yönetici özetinde DEĞERİ gösterilen alanlar (2026-10-18, kullanıcı isteği: "kullanıcının elle girdiği verileri tekrar
     * etmeden Yönetici Özeti kısmında gösterelim; aynı veriyi birden fazla kez göstermeyelim"). Raporun HER özet bölümü için:
     * <ul>
     *   <li>kutucuk şeridinde ÇİZİLEN alanlar ({@link TechErrorTiles#build} — şablonda olmayan / sayı-yüzde olmayan / muaf
     *       kutucuk çizilmez, sayılmaz; boş değer "—" ile çizilir, sayılır);</li>
     *   <li>özet otomatikse: TUTULAN (düşmeyen) bir cümlede {@code {f:<alan>}} ile değeri yazılan alanlar — söylenmemiş değer
     *       kuralının ({@link #blocks}) aynı 1. geçişi; {@code {d:}} / {@code {t:}} / {@code {dt:}} değeri yazmaz, sayılmaz;
     *       metin sınırından sonraki cümle sayılmaz;</li>
     *   <li>özet elle düzenlendiyse ({@code texts[<özet>]} boş değil): YALNIZ kutucuklar (elle metnin içeriği bilinmez).</li>
     * </ul>
     * PDF ve e-posta, özete GÖMÜLÜ olmayan gösterge bölümlerinde bu alanların sayı / yüzde / evet-hayır değerini tekrar
     * basmaz ({@link #shownInSummary}). Özet bölümü olmayan ya da hiçbir şey göstermeyen özetin kümesi boştur — çıktı aynı.
     */
    public static Set<String> shownValues(Template t, Values v, Map<String, Object> previous, String channel, int year, int month) {
        Set<String> out = new HashSet<>();
        if (t == null || v == null) return out;
        Map<String, Object> vals = v.fields() == null ? Map.of() : v.fields();
        Map<String, Field> byId = null;
        for (Section s : t.sections()) {
            if (!"summary".equals(s.kind())) continue;
            for (TechErrorTiles.Tile tile : TechErrorTiles.build(t, s, v, previous, channel, year, month)) out.add(tile.id());
            String override = v.texts() == null ? null : v.texts().get(s.id());
            if (override != null && !TechErrorRichText.blank(override)) continue;
            if (byId == null) {
                byId = new HashMap<>();
                for (Field f : t.allFields()) byId.put(f.id(), f);
            }
            out.addAll(told(s, byId, vals, previous, v.targets(), v.units(), t.lang(), channel, year, month).told());
        }
        return out;
    }

    /**
     * Gösterge bölümünde bu alan tekrar basılmaz mı: değeri özette gösterilen ({@link #shownValues}) sayı / yüzde /
     * evet-hayır alanı. Uzun açıklama ({@code textarea}) ve kısa metin hiç düşmez — mevcut kurallarla basılır.
     */
    public static boolean shownInSummary(Field f, Set<String> shown) {
        if (f == null || shown == null || shown.isEmpty() || !shown.contains(f.id())) return false;
        return "number".equals(f.type()) || "percent".equals(f.type()) || "yesno".equals(f.type());
    }

    /** Birleşik metnin uzunluğu: blok olmuş kısım + (ikisi de doluysa ayraç) + tampon. */
    private static int joined(int flushed, StringBuilder buf) {
        return flushed + (flushed > 0 && !buf.isEmpty() ? 2 : 0) + buf.length();
    }

    /** Tampondaki metni (doluysa) blok yapar; dönüş: birleşik metnin yeni uzunluğu. */
    private static int flush(List<Block> out, StringBuilder buf, int flushed) {
        if (buf.isEmpty()) return flushed;
        int len = joined(flushed, buf);
        out.add(Block.text(buf.toString()));
        buf.setLength(0);
        return len;
    }

    /** Blokları ekler; metin blokları birleşik uzunluğa sayılır. Dönüş: yeni birleşik uzunluk. */
    private static int append(List<Block> out, List<Block> add, int flushed) {
        for (Block b : add) {
            if (!b.note()) flushed += (flushed > 0 ? 2 : 0) + b.text().length();
            out.add(b);
        }
        return flushed;
    }

    /** Gömülü bölümün yerleşince ekleyeceği bloklar: önce söylenmemiş değerleri (tek metin bloğu), sonra notları. */
    private static List<Block> embeddedBlocks(Section e, Template t, Map<String, Object> vals, Set<String> told,
                                              Map<String, Integer> units, String channel, int year, int month) {
        List<Block> out = new ArrayList<>();
        String facts = facts(e, t, vals, told, units, channel, year, month);
        if (facts != null) out.add(Block.text(facts));
        out.addAll(notes(e, t, vals, channel, year, month));
        return out;
    }

    private static boolean referencesAny(Section e, Set<String> ids) {
        for (Field f : e.fields()) if (ids.contains(f.id())) return true;
        return false;
    }

    /**
     * Gömülü bölümün SÖYLENMEMİŞ değerleri: sayı / yüzde / evet-hayır / kısa metin alanı, dolu, muaf değil ve tutulan hiçbir
     * cümle ona başvurmuyor → her biri kendi satırında "Ad: **değer**" (sayı / yüzde kalın; evet / hayır ve kısa metin düz —
     * kısa metnin yıldız dizisi {@link #neutral} ile teke iner; soru işaretiyle biten ad iki nokta almaz: "Kota aşımı oldu mu?
     * Evet"), tek metin bloğu; satır başı maddeye benzerse {@link #keepParagraph}. Yoksa null. İstemcide aynı kural:
     * {@code techErrorModel.summaryFacts}.
     */
    static String facts(Section e, Template t, Map<String, Object> vals, Set<String> told, String channel, int year, int month) {
        return facts(e, t, vals, told, Map.of(), channel, year, month);
    }

    /** {@link #facts(Section, Template, Map, Set, String, int, int)} + kısaltma üsleri (2026-10-18). */
    static String facts(Section e, Template t, Map<String, Object> vals, Set<String> told, Map<String, Integer> units,
                        String channel, int year, int month) {
        StringBuilder b = new StringBuilder();
        for (Field f : e.fields()) {
            boolean yesno = "yesno".equals(f.type());
            boolean text = "text".equals(f.type());
            if (!yesno && !text && !"number".equals(f.type()) && !"percent".equals(f.type())) continue;
            if (told.contains(f.id()) || TechErrorTemplate.exempt(f, vals)) continue;
            Object v = vals.get(f.id());
            if (v == null || (yesno && !(v instanceof Boolean))) continue;
            String value;
            if (text) {
                if (!(v instanceof String sv) || TechErrorRichText.blank(sv)) continue;
                value = neutral(sv);
            } else {
                value = TechErrorFormat.value(f, v, t.lang(), units == null ? null : units.get(f.id()));
            }
            String label = TechErrorTemplate.resolve(f.label() == null ? "" : f.label(), channel, year, month, t.lang());
            if (label == null) label = "";
            boolean bold = !yesno && !text;
            String shown = bold ? "**" + value + "**" : value;
            String fact = label.endsWith("?") ? label + " " + shown : label + ": " + shown;
            if (!b.isEmpty()) b.append('\n');
            b.append(keepParagraph(fact));
        }
        return b.isEmpty() ? null : b.toString();
    }

    /** Gömülü bölümün notları: dolu, muaf olmayan uzun açıklama alanları (alan sırasıyla); başlık = alanın çözülmüş adı. */
    private static List<Block> notes(Section e, Template t, Map<String, Object> vals, String channel, int year, int month) {
        List<Block> out = new ArrayList<>();
        for (Field f : e.fields()) {
            if (!"textarea".equals(f.type())) continue;
            if (TechErrorTemplate.exempt(f, vals)) continue;
            if (!(vals.get(f.id()) instanceof String v) || TechErrorRichText.blank(v)) continue;
            out.add(Block.note(e.id(), f.id(), TechErrorTemplate.resolve(f.label(), channel, year, month, t.lang()), v));
        }
        return out;
    }

    /**
     * Metin sınırı, {@link #auto} ile aynı ölçüyle: metin blokları "\n\n" ile birleşik sayılır; birleşik metin sınırı
     * aşarsa kesim noktası {@code capResolved} ile aynı ({@code MAX − 1}, vekil çiftinin ilk yarısında bir eksik). Kesim
     * noktasında ya da sonrasında başlayan metin blokları düşer; kalan son metin bloğu kesim noktasına kadar kalır + "…".
     * Notlar sınıra girmez, hiç düşmez. İstemcide aynı kural: {@code techErrorModel.capTextBlocks}.
     */
    static List<Block> capText(List<Block> blocks) {
        StringBuilder joined = new StringBuilder();
        for (Block b : blocks) {
            if (b.note()) continue;
            if (!joined.isEmpty()) joined.append("\n\n");
            joined.append(b.text());
        }
        int max = TechErrorTemplate.MAX_RESOLVED_TEXT;
        if (joined.length() <= max) return blocks;
        int end = max - 1;
        if (Character.isHighSurrogate(joined.charAt(end - 1))) end--;
        List<Block> out = new ArrayList<>(blocks.size());
        int start = 0;
        int last = -1;
        int lastStart = 0;
        for (Block b : blocks) {
            if (b.note()) {
                out.add(b);
                continue;
            }
            if (start < end) {
                out.add(b);
                last = out.size() - 1;
                lastStart = start;
            }
            start += b.text().length() + 2;
        }
        if (last >= 0) {
            String txt = out.get(last).text();
            out.set(last, Block.text(txt.substring(0, Math.min(txt.length(), end - lastStart)) + "…"));
        }
        return out;
    }

    /**
     * Tek cümle; dayandığı bir değer yoksa {@code null} (cümle düşer). Uzun açıklama ({@code textarea}) alanına
     * başvuran cümle de düşer: çok satırlı not tek cümleye sığmaz ve 100 başvuru × 10 000 karakter bellek şişirirdi
     * (düzenleyici bu alanları sunmaz, elle yazılırsa uyarır).
     */
    static String line(String pattern, Map<String, Field> byId, Map<String, Object> fields, String lang, String channel,
                       int year, int month) {
        return line(pattern, byId, fields, null, Map.of(), lang, channel, year, month);
    }

    /**
     * {@link #line(String, Map, Map, String, String, int, int)} + geçen ayın değerleri ve raporun hedefleri (2026-10-17, 4b):
     * {@code {d:x}} önceki aya göre fark (geçen ay yoksa cümle düşer — kullanıcı kararı "geçen ay verisi yoksa hiç
     * gösterilmez"), {@code {t:x}} etkin hedef, {@code {dt:x}} değerin hedefe uzaklığı (puan). Sayı / yüzde kalın; "değişim
     * yok" düz.
     */
    static String line(String pattern, Map<String, Field> byId, Map<String, Object> fields, Map<String, Object> previous,
                       Map<String, Object> targets, String lang, String channel, int year, int month) {
        return line(pattern, byId, fields, previous, targets, Map.of(), lang, channel, year, month);
    }

    /**
     * {@link #line(String, Map, Map, Map, Map, String, String, int, int)} + kısaltma üsleri (2026-10-18): {@code {f:x}}
     * kısaltmayla girilmiş sayıyı kısaltmasıyla yazar ("**4,4G**"), {@code {d:x}} bu ayın değeri kısaltmalıysa farkı kısa
     * yazar ("**+50M (+%1,15)**"). Üssü olmayan değer eskisiyle bayt bayt aynı.
     */
    static String line(String pattern, Map<String, Field> byId, Map<String, Object> fields, Map<String, Object> previous,
                       Map<String, Object> targets, Map<String, Integer> units, String lang, String channel, int year, int month) {
        if (pattern == null || TechErrorRichText.blank(pattern)) return null;
        boolean en = "en".equals(lang);
        List<Ref> refs = refs(pattern);
        StringBuilder b = new StringBuilder();
        int pos = 0;
        for (Ref r : refs) {
            b.append(literal(pattern.substring(pos, r.start()), channel, year, month, lang));
            Field f = byId.get(r.id());
            if (f == null || "textarea".equals(f.type()) || TechErrorTemplate.exempt(f, fields)) return null;
            if (!"f".equals(r.kind())) {
                boolean numeric = "number".equals(f.type()) || "percent".equals(f.type());
                if ("d".equals(r.kind())) {
                    if (!numeric) return null;
                    TechErrorFormat.Delta d = TechErrorFormat.delta(f, previous == null ? null : previous.get(f.id()), fields.get(f.id()), lang,
                            units == null ? null : units.get(f.id()));
                    if (d == null) return null;
                    boolean plain = "flat".equals(d.dir()) && !"percent".equals(f.type());
                    b.append(plain ? d.text() : "**" + d.text() + "**");
                } else {
                    Double target = TechErrorTemplate.effectiveTarget(f, targets);
                    if (target == null) return null;
                    if ("t".equals(r.kind())) {
                        b.append("**").append(TechErrorFormat.percent(target, en)).append("**");
                    } else {
                        Double cur = TechErrorValues.number(fields.get(f.id()));
                        if (cur == null) return null;
                        b.append("**").append(TechErrorFormat.points(cur - target, en).text()).append("**");
                    }
                }
                pos = r.end();
                continue;
            }
            Object v = fields.get(f.id());
            if (v == null) return null;
            if (r.yes() != null) {
                if (!(v instanceof Boolean yes)) return null;
                b.append(literal(yes ? r.yes() : r.no(), channel, year, month, lang));
            } else if ("number".equals(f.type()) || "percent".equals(f.type())) {
                b.append("**").append(TechErrorFormat.value(f, v, lang, units == null ? null : units.get(f.id()))).append("**");
            } else {
                b.append(neutral(TechErrorFormat.value(f, v, lang)));
            }
            pos = r.end();
        }
        b.append(literal(pattern.substring(pos), channel, year, month, lang));
        return TechErrorRichText.strip(b.toString().replaceAll(" {2,}", " "));
    }

    private static String literal(String s, String channel, int year, int month, String lang) {
        return TechErrorTemplate.resolveText(s, channel, year, month, lang);
    }

    /**
     * Kullanıcının yazdığı değer işaretlemeyi bozmasın: iki ve daha uzun her yıldız dizisi TEK yıldıza iner (inceleme
     * 2026-10-14: "4543 **** 1234" eskiden "**" bırakıp cümlenin geri kalanını kalın yapıyordu), satır sonu boşluğa döner.
     */
    static String neutral(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') { b.append(' '); continue; }
            if (c == '*' && i > 0 && s.charAt(i - 1) == '*') continue;
            b.append(c);
        }
        return b.toString();
    }

    /** Satır başında madde gibi okunacak düz cümle: işaretten sonraki boşluk U+00A0 olur (madde olmaz). */
    static String keepParagraph(String r) {
        if (!isItem(r)) return r;
        int k = 0;
        while (k < r.length() && k < 3 && r.charAt(k) >= '0' && r.charAt(k) <= '9') k++;
        k = k > 0 ? k + 1 : 1;                            // "12." / "3)" ya da "-" / "*" / "•"
        return k < r.length() && r.charAt(k) == ' ' ? r.substring(0, k) + '\u00A0' + r.substring(k + 1) : r;
    }

    private static boolean isItem(String s) {
        List<TechErrorRichText.Block> b = TechErrorRichText.parse(s);
        return !b.isEmpty() && b.get(0).item();
    }
}
