package com.sitemonitor.service.noc;

import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.MailKit;
import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Btn;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Row;
import com.sitemonitor.service.mail.MailKit.Variant;
import com.sitemonitor.service.mail.MailTokens.Tone;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 7/24 İzleme Ekibi (NOC) e-postaları — YALNIZ {@link MailDoc}/{@link MailKit} ile (Outlook-güvenli, mobil duyarlı,
 * sol şerit YOK, serbest renk YOK). Saf fonksiyonlar: Spring bağımlılığı yok, galeri ve sözleşme testi
 * ({@code EmailSamples} / {@code EmailResponsiveContractTest}) aynı kurucuyu çağırır.
 *
 * <p>NOC e-postası TAKIM e-postasından farklı bir soruya cevap verir: "şimdi KİMİ arayayım?" Bu yüzden gövdenin
 * merkezinde SIRALI arama listesi ({@code tel:} bağlantılı telefon) durur; liste yoksa Takım Müdürü ve açık bir
 * "arama listesi tanımlanmamış" uyarısı. Kullanıcı metni (arama talimatı, hata, ad/unvan) her yerde kaçırılır.
 */
public final class NocMailComposer {

    private NocMailComposer() {}

    public static final String KICKER = "7/24 İzleme Ekibi";
    public static final String FOOTER = "Bu e-posta 7/24 izleme ekibi için gönderildi";
    private static final ZoneId ORG_ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter IN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Aranacak kişi. {@code phone} ham AD değeri — yalnız bu e-postaya girer. */
    public record Person(String name, String title, String phone) {}

    /** Eskalasyon kişisi (ad, rol etiketi, e-posta). */
    public record Contact(String name, String role, String email) {}

    /** Takım bölümü: sıralı arama listesi, "liste tanımlı mı", Takım Müdürü, eskalasyon kişileri. */
    public record TeamBlock(String teamName, List<Person> callList, boolean callListDefined, Person manager,
                            List<Contact> escalation) {
        public static TeamBlock none() { return new TeamBlock(null, List.of(), false, null, List.of()); }
    }

    /** Tekil alarm açılışı. {@code since}/{@code resolvedAt} UTC ISO ({@code yyyy-MM-dd'T'HH:mm:ss}). */
    public record AlarmInfo(String level, String typeLabel, String problem, String target, String monitorName,
                            String since, String error, TeamBlock team, String callInstructions,
                            String monitorUrl, String alertUrl, String callLogUrl, List<String> groupNames) {}

    public record ResolvedInfo(String typeLabel, String problem, String target, String monitorName, String since,
                               String resolvedAt, String resolvedBy, TeamBlock team,
                               String monitorUrl, String alertUrl) {}

    /** Fırtına e-postasında bir satır; {@code callLogUrl} = o uyarı için "Arama kaydı ekle" (boşsa sütun boş). */
    public record Member(String target, String typeLabel, String teamName, String monitorUrl, String callLogUrl) {}

    /** "Arama kaydı ekle" düğmesi / bağlantısı (EN: "Log a call") — 7/24 arama kaydı (sözleşme ek bölümü). */
    public static final String CALL_LOG_LABEL = "Arama kaydı ekle";

    /** Alarm türünün NOC için kısa, düz Türkçe karşılığı ("ne oldu"). */
    public static String problemLabel(String alertType, Integer days) {
        if (alertType == null) return "İzleme sorunu";
        String d = days == null ? "" : " (" + days + " gün kaldı)";
        return switch (alertType) {
            case "ACCESSIBILITY" -> "Erişim kesintisi";
            case "HTTP_DOWN" -> "HTTP/Web erişilemez";
            case "PORT_DOWN" -> "Port yanıt vermiyor";
            case "PING_DOWN" -> "Ping yanıtı yok";
            case "DNS_FAILURE" -> "DNS çözümlenemiyor";
            case "KEYWORD" -> "İçerik doğrulaması başarısız";
            case "PAGE_DOWN" -> "Sayfa yüklenemiyor";
            case "PAGE_INTEGRITY" -> "Sayfada kırık kaynak / karışık içerik";
            case "SCRIPTED_FAIL" -> "Sentetik senaryo başarısız";
            case "PAGESPEED_DOWN" -> "Sayfa hızı ölçülemiyor";
            case "PORT_SLOW", "PING_SLOW", "DNS_SLOW", "KEYWORD_SLOW", "SCRIPTED_SLOW" -> "Yavaş yanıt";
            case "PAGESPEED_SLOW" -> "Sayfa hızı eşiği aşıldı";
            case "DNS_CHANGED" -> "DNS kaydı değişti";
            case "DNS_UNEXPECTED" -> "DNS beklenmeyen değer döndürüyor";
            case "DNS_INCONSISTENT" -> "DNS çözümleyicileri tutarsız";
            case "HTTP_SSL", "KEYWORD_SSL" -> "SSL sertifika sorunu";
            case "DOMAIN_EXPIRY", "DOMAINMON_EXPIRY", "KEYWORD_DOMAIN_EXPIRY" -> "Alan adı süresi doluyor" + d;
            case "DOMAINMON_UNKNOWN" -> "Alan adı kayıt verisi alınamıyor";
            case "DOMAINMON_STATUS" -> "Alan adı durum kodu uyarısı";
            case "DOMAINMON_CHANGED" -> "Alan adı kaydı değişti";
            case "DOMAINMON_TRANSFER_LOCK" -> "Alan adı transfer kilidi kapalı";
            case "DOMAINMON_BLACKLIST" -> "Alan adı kara listede";
            case "EXPIRY" -> "Sertifika süresi doluyor" + d;
            case "REVOKED" -> "Sertifika iptal edildi";
            case "CHAIN_BROKEN" -> "Sertifika zinciri kırık";
            case "MISMATCH" -> "Sertifika dağıtım uyuşmazlığı";
            case "HOSTNAME_MISMATCH" -> "Sertifika alan adıyla uyuşmuyor";
            case "UNTRUSTED_CA" -> "Güvenilmeyen sertifika";
            default -> alertType;
        };
    }

    // ── Konu satırları ──────────────────────────────────────────────────────

    public static String levelTr(String level) {
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH" -> "YÜKSEK";
            case "INFO", "LOW" -> "BİLGİ";
            default -> "ORTA";
        };
    }

    /** {@code [Site Monitor] [7/24] <SEVİYE> — <hedef> — <Takım>} (sözleşme §5). */
    public static String openSubject(String level, String target, String teamName) {
        return "[Site Monitor] [7/24] " + levelTr(level) + " — " + nz(target, "—")
                + (teamName == null || teamName.isBlank() ? "" : " — " + teamName);
    }

    public static String resolvedSubject(String target, String teamName) {
        return "[Site Monitor] [7/24] ÇÖZÜLDÜ — " + nz(target, "—")
                + (teamName == null || teamName.isBlank() ? "" : " — " + teamName);
    }

    public static String stormSubject(int count) {
        return "[Site Monitor] [7/24] KRİTİK — ALARM FIRTINASI — " + count + " izleme erişilemez";
    }

    public static String stormResolvedSubject(int recovered) {
        return "[Site Monitor] [7/24] ÇÖZÜLDÜ — Alarm fırtınası sona erdi (" + recovered + " izleme düzeldi)";
    }

    public static String testSubject(String groupName) {
        return "[Site Monitor] [7/24] Test e-postası — " + nz(groupName, "7/24 grubu");
    }

    // ── Gövdeler ─────────────────────────────────────────────────────────────

    public static MailDoc.Mail open(AlarmInfo a) {
        String subject = openSubject(a.level(), a.target(), a.team() == null ? null : a.team().teamName());
        boolean critical = "CRITICAL".equalsIgnoreCase(a.level());
        boolean hasList = a.team() != null && a.team().callListDefined() && !a.team().callList().isEmpty();
        MailDoc d = MailDoc.create(subject)
                .preheader(nz(a.problem(), "Sorun") + " — " + nz(a.target(), "")
                        + (hasList ? ". Aranacak kişiler aşağıda." : ". Arama listesi yok — Takım Müdürü'nü arayın."))
                .kicker(KICKER)
                .badges(critical ? Badge.solid(levelTr(a.level()), Tone.DESTRUCTIVE)
                                 : Badge.tint(levelTr(a.level()), "HIGH".equalsIgnoreCase(a.level()) ? Tone.WARNING : Tone.INFO),
                        Badge.tint("7/24", Tone.INFO),
                        Badge.outline(nz(a.typeLabel(), "İzleme")))
                .title(nz(a.target(), "—"), nz(a.problem(), "İzleme sorun bildiriyor")
                        + (hasList ? ". Lütfen aşağıdaki sırayla arayın." : ". Arama listesi tanımlı değil; Takım Müdürü'nü arayın."));
        d.keyValue("Ne oldu", MailDoc.rows(
                Row.of("Tür", nz(a.typeLabel(), "—")),
                new Row("Hedef", MailKit.mono(nz(a.target(), "—")), nz(a.target(), "—")),
                blank(a.monitorName()) || a.monitorName().equals(a.target()) ? null : Row.of("İzleme", a.monitorName()),
                Row.of("Durum", nz(a.problem(), "—")),
                Row.of("Ne zamandan beri", fmt(a.since())),
                blank(a.error()) ? null : Row.of("Hata", clip(a.error(), 500)),
                Row.of("Sahibi takım", a.team() == null ? "—" : nz(a.team().teamName(), "—"))));
        appendTeam(d, a.team());
        appendInstructions(d, a.callInstructions());
        List<Btn> btns = new ArrayList<>();
        if (!blank(a.monitorUrl())) btns.add(new Btn(a.monitorUrl(), "İzlemeyi aç", Variant.PRIMARY));
        if (!blank(a.alertUrl())) btns.add(new Btn(a.alertUrl(), "Alarmı görüntüle", Variant.OUTLINE));
        // İkincil eylem: aramadan sonra kaydı uyarının üzerinden girmek (kim/ne zaman/sonuç). Taban adres yoksa
        // bağlantı hiç üretilmez (çağıran null geçer); şeması izinli değilse MailDoc.buttons zaten atlar (safeHref).
        if (!blank(a.callLogUrl())) btns.add(new Btn(a.callLogUrl(), CALL_LOG_LABEL, Variant.SECONDARY));
        if (!btns.isEmpty()) d.buttons(null, btns);
        d.footerMeta(FOOTER, groupsMeta(a.groupNames()), "Site Monitor");
        return d.build();
    }

    public static MailDoc.Mail resolved(ResolvedInfo r) {
        String subject = resolvedSubject(r.target(), r.team() == null ? null : r.team().teamName());
        MailDoc d = MailDoc.create(subject)
                .preheader(nz(r.target(), "") + " — sorun giderildi. Arama gerekmiyor.")
                .kicker(KICKER)
                .badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS), Badge.tint("7/24", Tone.INFO),
                        Badge.outline(nz(r.typeLabel(), "İzleme")))
                .title(nz(r.target(), "—"), "Sorun giderildi. Bu alarm için arama yapmanıza gerek yok.");
        d.alert(Tone.SUCCESS, "Alarm kapandı", "Daha önce bildirilen sorun artık görülmüyor.");
        d.keyValue("Özet", MailDoc.rows(
                Row.of("Tür", nz(r.typeLabel(), "—")),
                new Row("Hedef", MailKit.mono(nz(r.target(), "—")), nz(r.target(), "—")),
                blank(r.monitorName()) || r.monitorName().equals(r.target()) ? null : Row.of("İzleme", r.monitorName()),
                Row.of("Sorun", nz(r.problem(), "—")),
                Row.of("Başlangıç", fmt(r.since())),
                Row.of("Çözülme", fmt(r.resolvedAt())),
                blank(r.resolvedBy()) ? null : Row.of("Kapatan", r.resolvedBy()),
                Row.of("Sahibi takım", r.team() == null ? "—" : nz(r.team().teamName(), "—"))));
        List<Btn> btns = new ArrayList<>();
        if (!blank(r.monitorUrl())) btns.add(new Btn(r.monitorUrl(), "İzlemeyi aç", Variant.OUTLINE));
        if (!blank(r.alertUrl())) btns.add(new Btn(r.alertUrl(), "Alarmı görüntüle", Variant.OUTLINE));
        if (!btns.isEmpty()) d.buttons(null, btns);
        d.footerMeta(FOOTER, "Site Monitor");
        return d.build();
    }

    /** Fırtına açılışı: TEK toplu e-posta — etkilenen izlemeler + her takımın arama listesi. */
    public static MailDoc.Mail storm(int totalDown, String scopeLabel, String rootCause, String since,
                                     List<Member> members, List<TeamBlock> teams, String callInstructions,
                                     String coverageUrl, List<String> groupNames) {
        MailDoc d = MailDoc.create(stormSubject(totalDown))
                .preheader(totalDown + " izleme aynı anda erişilemez. Etkilenen takımların arama listeleri aşağıda.")
                .kicker(KICKER)
                .badges(Badge.solid("KRİTİK", Tone.DESTRUCTIVE), Badge.tint("7/24", Tone.INFO), Badge.outline("Alarm fırtınası"))
                .title(totalDown + " izleme aynı anda erişilemez",
                        "Toplu kesinti: tek tek alarm yerine bu özet gönderildi. Takımları aşağıdaki sırayla arayın.");
        d.keyValue(MailDoc.rows(
                Row.of("Kapsam", nz(scopeLabel, "Tüm izlemeler")),
                Row.of("Kök neden", nz(rootCause, "Kesinti")),
                Row.of("Başlangıç", fmt(since)),
                Row.of("7/24'e bildirilen", members.size() + " izleme")));
        d.heading("Etkilenen izlemeler");
        memberTable(d, members);
        for (TeamBlock t : teams) appendTeam(d, t);
        appendInstructions(d, callInstructions);
        if (!blank(coverageUrl)) d.button(coverageUrl, "7/24 kapsamını aç", Variant.OUTLINE);
        d.footerMeta(FOOTER, groupsMeta(groupNames), "Site Monitor");
        return d.build();
    }

    public static String stormUpdateSubject(int newCount) {
        return "[Site Monitor] [7/24] KRİTİK — ALARM FIRTINASI GÜNCELLEMESİ — " + newCount + " yeni izleme erişilemez";
    }

    /**
     * Fırtına sürerken KATILAN ve 7/24 kapsamındaki izlemeler için toplu güncelleme ("toplu kesinti güncellemesi") —
     * fırtına açılış e-postasından SONRA düşen izlemeler bireysel alarm üretmediği için (fırtına bastırır) NOC'a
     * bu yoldan ulaşır. Yalnız YENİ üyeler ve onların takımlarının arama listeleri.
     */
    public static MailDoc.Mail stormUpdate(int totalDown, String scopeLabel, String since, List<Member> members,
                                           List<TeamBlock> teams, String callInstructions, String coverageUrl,
                                           List<String> groupNames) {
        MailDoc d = MailDoc.create(stormUpdateSubject(members.size()))
                .preheader(members.size() + " izleme daha erişilemez (fırtına sürüyor). Arama listeleri aşağıda.")
                .kicker(KICKER)
                .badges(Badge.solid("KRİTİK", Tone.DESTRUCTIVE), Badge.tint("7/24", Tone.INFO), Badge.outline("Fırtına güncellemesi"))
                .title(members.size() + " yeni izleme erişilemez",
                        "Toplu kesinti sürüyor ve bu izlemeler önceki e-postadan SONRA düştü. Takımları aşağıdaki sırayla arayın.");
        d.keyValue(MailDoc.rows(
                Row.of("Kapsam", nz(scopeLabel, "Tüm izlemeler")),
                Row.of("Fırtına başlangıcı", fmt(since)),
                Row.of("Şu an erişilemeyen", totalDown + " izleme")));
        d.heading("Yeni etkilenen izlemeler");
        memberTable(d, members);
        for (TeamBlock t : teams) appendTeam(d, t);
        appendInstructions(d, callInstructions);
        if (!blank(coverageUrl)) d.button(coverageUrl, "7/24 kapsamını aç", Variant.OUTLINE);
        d.footerMeta(FOOTER, groupsMeta(groupNames), "Site Monitor");
        return d.build();
    }

    /** Fırtına üye tablosu: hedef (izleme bağlantısı), tür, takım ve — varsa — uyarı başına "Arama kaydı ekle". */
    private static void memberTable(MailDoc d, List<Member> members) {
        List<List<Cell>> rows = new ArrayList<>();
        boolean anyCallLog = false;
        for (Member m : members) anyCallLog |= !blank(m.callLogUrl()) && MailKit.safeHref(m.callLogUrl());
        for (Member m : members) {
            List<Cell> row = new ArrayList<>(List.of(
                    blank(m.monitorUrl()) ? Cell.of(nz(m.target(), "—"))
                            : Cell.html(MailKit.link(m.monitorUrl(), nz(m.target(), "—")), nz(m.target(), "—")),
                    Cell.of(nz(m.typeLabel(), "—")),
                    Cell.of(nz(m.teamName(), "—"))));
            if (anyCallLog) {
                // Uyarı başına "Arama kaydı ekle" — MailKit.link izinsiz şemada bağlantı kurmaz (safeHref).
                row.add(blank(m.callLogUrl()) ? Cell.of("—")
                        : Cell.html(MailKit.link(m.callLogUrl(), CALL_LOG_LABEL), CALL_LOG_LABEL + ": " + m.callLogUrl()));
            }
            rows.add(row);
        }
        d.table(anyCallLog ? List.of(Col.of("Hedef"), Col.nw("Tür"), Col.of("Takım"), Col.nw("Arama"))
                           : List.of(Col.of("Hedef"), Col.nw("Tür"), Col.of("Takım")), rows);
    }

    public static MailDoc.Mail stormResolved(List<Member> recovered, List<Member> stillDown, String since, String resolvedAt) {
        MailDoc d = MailDoc.create(stormResolvedSubject(recovered.size()))
                .preheader("Alarm fırtınası sona erdi — " + recovered.size() + " izleme düzeldi.")
                .kicker(KICKER)
                .badges(Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS), Badge.tint("7/24", Tone.INFO), Badge.outline("Alarm fırtınası"))
                .title("Alarm fırtınası sona erdi", recovered.size() + " izleme düzeldi.");
        d.keyValue(MailDoc.rows(Row.of("Başlangıç", fmt(since)), Row.of("Bitiş", fmt(resolvedAt))));
        d.heading("Düzelen izlemeler");
        d.table(List.of(Col.of("Hedef"), Col.nw("Tür"), Col.of("Takım")), memberRows(recovered));
        if (!stillDown.isEmpty()) {
            d.alert(Tone.WARNING, "Hâlâ erişilemeyen izlemeler var",
                    stillDown.size() + " izleme hâlâ erişilemiyor; bunlar artık tek tek bildirilecek.");
            d.table(List.of(Col.of("Hedef"), Col.nw("Tür"), Col.of("Takım")), memberRows(stillDown));
        }
        d.footerMeta(FOOTER, "Site Monitor");
        return d.build();
    }

    public static MailDoc.Mail test(String groupName, String callInstructions) {
        MailDoc d = MailDoc.create(testSubject(groupName))
                .preheader("7/24 izleme ekibi test e-postası — bu adres alarm bildirimlerini alacak.")
                .kicker(KICKER)
                .badges(Badge.tint("TEST", Tone.INFO), Badge.tint("7/24", Tone.INFO))
                .title("7/24 test e-postası",
                        "Bu adres, Site Monitor'deki \"" + nz(groupName, "7/24") + "\" grubuna tanımlı. Gerçek alarm "
                                + "e-postaları, sorunun ne olduğunu ve takımda kimi hangi sırayla arayacağınızı gösterir.");
        d.alert(Tone.SUCCESS, "Bağlantı çalışıyor", "Bu e-postayı aldıysanız adres doğru tanımlanmıştır; işlem gerekmez.");
        appendInstructions(d, callInstructions);
        d.footerMeta(FOOTER, "Site Monitor");
        return d.build();
    }

    // ── Bölümler ─────────────────────────────────────────────────────────────

    private static void appendTeam(MailDoc d, TeamBlock t) {
        if (t == null || blank(t.teamName())) return;
        String team = t.teamName();
        if (t.callListDefined() && !t.callList().isEmpty()) {
            d.heading("Aranacak kişiler — " + team, "Sırayla arayın; ulaşamazsanız bir sonrakine geçin.");
            List<List<Cell>> rows = new ArrayList<>();
            int i = 1;
            for (Person p : t.callList()) rows.add(personRow(i++, p));
            d.table(List.of(Col.num("#"), Col.of("Ad"), Col.opt("Unvan"), Col.nw("Telefon")), rows);
        } else {
            d.alert(Tone.WARNING, team + " için arama listesi tanımlanmamış",
                    "Takım bir arama listesi belirlemedi. Takım Müdürü'nü arayın.");
        }
        if (t.manager() != null) {
            Person m = t.manager();
            d.keyValue("Takım Müdürü", MailDoc.rows(
                    Row.of("Ad", nz(m.name(), "—")),
                    blank(m.title()) ? null : Row.of("Unvan", m.title()),
                    phoneRow(m.phone())));
        } else if (!t.callListDefined()) {
            d.note("Takım Müdürü de belirlenemedi; takımın e-posta adresine ya da eskalasyon kişilerine başvurun.");
        }
        if (t.escalation() != null && !t.escalation().isEmpty()) {
            List<List<Cell>> rows = new ArrayList<>();
            for (Contact c : t.escalation()) {
                String email = nz(c.email(), "");
                rows.add(List.of(Cell.of(nz(c.name(), "—")), Cell.of(nz(c.role(), "—")),
                        email.isEmpty() ? Cell.of("—") : Cell.html(MailKit.link("mailto:" + email, email), email)));
            }
            d.heading("Eskalasyon kişileri — " + team);
            d.table(List.of(Col.of("Ad"), Col.nw("Rol"), Col.of("E-posta")), rows);
        }
    }

    private static void appendInstructions(MailDoc d, String instructions) {
        if (blank(instructions)) return;
        // MailDoc.alert gövdeyi escBr ile kaçırır — kullanıcı metni HTML olarak yorumlanmaz.
        d.alert(Tone.INFO, "Arama talimatı", clip(instructions, NocConfigService.MAX_INSTRUCTIONS));
    }

    private static List<Cell> personRow(int n, Person p) {
        return List.of(Cell.of(String.valueOf(n)), Cell.of(nz(p.name(), "—")), Cell.of(nz(p.title(), "")), phoneCell(p.phone()));
    }

    private static Cell phoneCell(String phone) {
        // Posta günlüğü kopyası (redactForLog): maskeli numara DÜZ metin — bağlantı yok, son iki hane dışında rakam yok.
        if (NocLogRedaction.isMasked(phone)) return Cell.of(phone);
        if (telHref(phone) == null) return Cell.of("Telefon kayıtlı değil");
        return Cell.html(MailKit.telLink(phone), phone.trim());
    }

    private static Row phoneRow(String phone) {
        if (NocLogRedaction.isMasked(phone)) return Row.of("Telefon", phone);
        if (telHref(phone) == null) return Row.of("Telefon", "Telefon kayıtlı değil");
        return new Row("Telefon", MailKit.telLink(phone), phone.trim());
    }

    /** {@code tel:} hedefi — kural {@link MailKit#telHref}'te (yalnız rakam + baştaki {@code +}; 3 haneden kısa → null). */
    public static String telHref(String phone) {
        return MailKit.telHref(phone);
    }

    // ── Posta günlüğü kopyası ────────────────────────────────────────────────

    /**
     * Posta günlüğüne yazılacak MASKELİ takım bölümü: telefonlar yalnız son iki hane ({@link NocLogRedaction#maskPhone}),
     * {@code tel:} bağlantısı YOK. Gövdenin geri kalanı (ne oldu, kimler, sıra) aynen kalır — operasyon ne gittiğini
     * görür; alarmı görebilen takım üyesi ise 7/24 arama listesinin numaralarını görmez.
     */
    public static TeamBlock redactForLog(TeamBlock t) {
        if (t == null) return null;
        List<Person> calls = new ArrayList<>();
        for (Person p : t.callList()) calls.add(redact(p));
        return new TeamBlock(t.teamName(), calls, t.callListDefined(), redact(t.manager()), t.escalation());
    }

    public static AlarmInfo redactForLog(AlarmInfo a) {
        return new AlarmInfo(a.level(), a.typeLabel(), a.problem(), a.target(), a.monitorName(), a.since(), a.error(),
                redactForLog(a.team()), a.callInstructions(), a.monitorUrl(), a.alertUrl(), a.callLogUrl(), a.groupNames());
    }

    private static Person redact(Person p) {
        return p == null ? null : new Person(p.name(), p.title(), NocLogRedaction.maskPhone(p.phone()));
    }

    private static List<List<Cell>> memberRows(List<Member> list) {
        List<List<Cell>> rows = new ArrayList<>();
        for (Member m : list) rows.add(List.of(Cell.of(nz(m.target(), "—")), Cell.of(nz(m.typeLabel(), "—")), Cell.of(nz(m.teamName(), "—"))));
        return rows;
    }

    private static String groupsMeta(List<String> names) {
        return names == null || names.isEmpty() ? null : "Grup: " + String.join(", ", names);
    }

    /** UTC ISO → İstanbul "dd.MM.yyyy HH:mm"; çözülemezse olduğu gibi. */
    static String fmt(String isoUtc) {
        if (blank(isoUtc)) return "—";
        try {
            String s = isoUtc.length() > 19 ? isoUtc.substring(0, 19) : isoUtc;
            return LocalDateTime.parse(s, IN).atOffset(ZoneOffset.UTC).atZoneSameInstant(ORG_ZONE).format(OUT);
        } catch (Exception e) {
            return isoUtc;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
