package com.sitemonitor.service;

import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.noc.NocMailComposer;
import com.sitemonitor.service.noc.NocMailComposer.AlarmInfo;
import com.sitemonitor.service.noc.NocMailComposer.Contact;
import com.sitemonitor.service.noc.NocMailComposer.Person;
import com.sitemonitor.service.noc.NocMailComposer.TeamBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7/24 (NOC) e-posta İÇERİĞİ: arama listesi sırası, {@code tel:} bağlantıları, liste yokken Takım Müdürü yedeği,
 * telefonu olmayan kişi, kullanıcı metninin kaçırılması ve duyarlılık sözleşmesi. Galeri/sözleşme testi aynı
 * kurucuyu {@link EmailSamples} üzerinden de dolaşır; burada KURALLAR tek tek pinlenir.
 */
class NocMailComposerTest {

    private static final String BASE = "https://sitemonitor.example.com";

    private static TeamBlock team(boolean withList) {
        List<Person> list = withList
                ? List.of(new Person("Kişi A", "Uzman", "+90 555 000 00 00"),
                          new Person("Kişi B", "Kıdemli Uzman", "0555 000 00 01"),
                          new Person("Kişi C", null, null))
                : List.of();
        return new TeamBlock("Takım A", list, withList, new Person("Kişi M", "Müdür", "+90 555 000 00 09"),
                List.of(new Contact("Kişi M", "Müdür", "kisi.m@example.com")));
    }

    private static MailDoc.Mail open(TeamBlock t, String instructions) {
        return NocMailComposer.open(new AlarmInfo("CRITICAL", "HTTP / Web", "HTTP/Web erişilemez",
                "www.example.com", "Portal", "2026-01-10T21:05:00", "connect timed out", t, instructions,
                BASE + "/?tab=http&monitor=42", BASE + "/?tab=incidents&incident=7",
                BASE + "/?tab=alerthistory&alert=7&n_call=1", List.of("NOC Ana")));
    }

    @Test
    @DisplayName("konu: [Site Monitor] [7/24] <SEVİYE> — <hedef> — <Takım>")
    void subjectFormat() {
        assertThat(NocMailComposer.openSubject("CRITICAL", "www.example.com", "Takım A"))
                .isEqualTo("[Site Monitor] [7/24] KRİTİK — www.example.com — Takım A");
        assertThat(NocMailComposer.openSubject("HIGH", "x.example.com", null))
                .isEqualTo("[Site Monitor] [7/24] YÜKSEK — x.example.com");
        assertThat(NocMailComposer.resolvedSubject("x.example.com", "Takım A"))
                .isEqualTo("[Site Monitor] [7/24] ÇÖZÜLDÜ — x.example.com — Takım A");
    }

    @Test
    @DisplayName("arama listesi SIRAYLA ve telefonlar tel: bağlantılı (yalnız rakam + baştaki +)")
    void callListOrderAndTelLinks() {
        String html = open(team(true), null).html();
        int a = html.indexOf("Kişi A"), b = html.indexOf("Kişi B"), c = html.indexOf("Kişi C");
        assertThat(a).isPositive();
        assertThat(b).isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
        assertThat(html).contains("href=\"tel:+905550000000\"").contains("href=\"tel:05550000001\"");
        assertThat(html.indexOf("tel:+905550000000")).isLessThan(html.indexOf("tel:05550000001"));
        assertThat(html).contains("Aranacak kişiler — Takım A");
        assertThat(html).contains(NocMailComposer.FOOTER);
    }

    @Test
    @DisplayName("telefonu olmayan kişide bağlantı YOK, 'Telefon kayıtlı değil' yazar")
    void personWithoutPhone() {
        TeamBlock t = new TeamBlock("Takım A", List.of(new Person("Kişi C", null, null)), true, null, List.of());
        String html = open(t, null).html();
        assertThat(html).contains("Kişi C").contains("Telefon kayıtlı değil").doesNotContain("tel:");
    }

    @Test
    @DisplayName("arama listesi yoksa: 'tanımlanmamış' uyarısı + Takım Müdürü ve telefonu")
    void fallbackToManager() {
        MailDoc.Mail m = open(team(false), null);
        assertThat(m.html()).contains("Takım A için arama listesi tanımlanmamış")
                .contains("Takım Müdürü").contains("Kişi M").contains("href=\"tel:+905550000009\"")
                .doesNotContain("Aranacak kişiler");
        assertThat(m.text()).contains("arama listesi tanımlanmamış").contains("Kişi M");
    }

    @Test
    @DisplayName("ne liste ne müdür: yine de açık not (sessizce boş bölüm yok)")
    void noListNoManager() {
        TeamBlock t = new TeamBlock("Takım B", List.of(), false, null, List.of());
        assertThat(open(t, null).html()).contains("arama listesi tanımlanmamış").contains("Takım Müdürü de belirlenemedi");
    }

    @Test
    @DisplayName("arama talimatı ve ad/unvan KAÇIRILIR — HTML olarak yorumlanmaz")
    void userTextIsEscaped() {
        TeamBlock t = new TeamBlock("Takım <A>", List.of(new Person("<img src=x onerror=alert(1)>", "\"Unvan\"", "05550000000")),
                true, null, List.of());
        String html = open(t, "Önce ara <script>alert(1)</script>\nSonra & bekle").html();
        assertThat(html).doesNotContain("<script>").doesNotContain("<img src=x")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("Sonra &amp; bekle").contains("Takım &lt;A&gt;");
        assertThat(html).contains("Arama talimatı");
    }

    @Test
    @DisplayName("açılış e-postasında 'Arama kaydı ekle' (ikincil) düğmesi uyarının arama kaydı derin bağlantısına gider")
    void callLogCtaOnOpen() {
        MailDoc.Mail m = open(team(true), null);
        assertThat(m.html()).contains(NocMailComposer.CALL_LOG_LABEL)
                .contains("href=\"" + BASE + "/?tab=alerthistory&amp;alert=7&amp;n_call=1\"");
        assertThat(m.text()).contains(NocMailComposer.CALL_LOG_LABEL + ": " + BASE + "/?tab=alerthistory&alert=7&n_call=1");
    }

    @Test
    @DisplayName("taban adres yoksa (null) ya da şema izinsizse arama kaydı düğmesi HİÇ çizilmez")
    void callLogCtaOmittedWithoutBase() {
        MailDoc.Mail none = NocMailComposer.open(new AlarmInfo("CRITICAL", "Ping", "Ping yanıtı yok", "h.example.com", null,
                "2026-01-10T21:05:00", null, team(true), null, null, null, null, List.of()));
        assertThat(none.html()).doesNotContain(NocMailComposer.CALL_LOG_LABEL).doesNotContain("n_call=1");
        MailDoc.Mail bad = NocMailComposer.open(new AlarmInfo("CRITICAL", "Ping", "Ping yanıtı yok", "h.example.com", null,
                "2026-01-10T21:05:00", null, team(true), null, null, null, "javascript:alert(1)", List.of()));
        assertThat(bad.html()).doesNotContain("javascript:").doesNotContain(NocMailComposer.CALL_LOG_LABEL);
    }

    @Test
    @DisplayName("fırtına e-postasında arama kaydı bağlantısı UYARI BAŞINA satırda; hiçbir satırda yoksa sütun da yok")
    void callLogPerStormLine() {
        List<NocMailComposer.Member> members = List.of(
                new NocMailComposer.Member("a.example.com:443", "Port", "Takım A", null, BASE + "/?tab=alerthistory&alert=11&n_call=1"),
                new NocMailComposer.Member("b.example.com:443", "Port", "Takım A", null, BASE + "/?tab=alerthistory&alert=12&n_call=1"));
        String html = NocMailComposer.storm(2, "Tüm izlemeler", "Port Kesintisi", "2026-01-10T21:05:00", members,
                List.of(), null, null, List.of()).html();
        assertThat(html).contains("alert=11&amp;n_call=1").contains("alert=12&amp;n_call=1").contains(">Arama<");
        String noLinks = NocMailComposer.storm(1, "Tüm izlemeler", "Port Kesintisi", "2026-01-10T21:05:00",
                List.of(new NocMailComposer.Member("a.example.com:443", "Port", "Takım A", null, null)),
                List.of(), null, null, List.of()).html();
        assertThat(noLinks).doesNotContain(NocMailComposer.CALL_LOG_LABEL).doesNotContain(">Arama<");
    }

    @Test
    @DisplayName("günlük kopyası (redactForLog): ad/unvan/sıra aynen; telefon yalnız son iki hane, tel: bağlantısı YOK")
    void redactForLogMasksPhones() {
        AlarmInfo a = new AlarmInfo("CRITICAL", "HTTP / Web", "HTTP/Web erişilemez", "www.example.com", "Portal",
                "2026-01-10T21:05:00", null, team(true), null, null, null, null, List.of());
        String html = NocMailComposer.open(NocMailComposer.redactForLog(a)).html();
        assertThat(html).contains("Kişi A").contains("Kişi B").contains("Uzman")
                .contains("••••••••00").contains("••••••••01")
                .doesNotContain("tel:").doesNotContain("555 000 00 00").doesNotContain("5550000000");
        assertThat(html.indexOf("Kişi A")).isLessThan(html.indexOf("Kişi B"));
        String noList = NocMailComposer.open(NocMailComposer.redactForLog(new AlarmInfo("CRITICAL", "Ping", "Ping yanıtı yok",
                "h.example.com", null, null, null, team(false), null, null, null, null, List.of()))).html();
        assertThat(noList).contains("Kişi M").contains("••••••••09").doesNotContain("tel:");
    }

    @Test
    @DisplayName("telHref: yalnız rakam + baştaki +; 3 haneden kısa ya da rakamsız → bağlantı yok")
    void telHrefNormalization() {
        assertThat(NocMailComposer.telHref("+90 (555) 000-00-00")).isEqualTo("tel:+905550000000");
        assertThat(NocMailComposer.telHref("0555 000 00 01")).isEqualTo("tel:05550000001");
        assertThat(NocMailComposer.telHref("555+12")).isEqualTo("tel:55512");
        assertThat(NocMailComposer.telHref("javascript:alert(1)")).isNull();
        assertThat(NocMailComposer.telHref("12")).isNull();
        assertThat(NocMailComposer.telHref("  ")).isNull();
        assertThat(NocMailComposer.telHref(null)).isNull();
    }

    @Test
    @DisplayName("düz metin parçası da arama listesini taşır (HTML'siz istemci)")
    void plainTextCarriesCallList() {
        String text = open(team(true), "Talimat satırı").text();
        assertThat(text).contains("Kişi A").contains("+90 555 000 00 00").contains("Talimat satırı")
                .doesNotContain("<table");
    }

    @Test
    @DisplayName("tüm NOC e-postaları duyarlılık + marka sözleşmesine uyar (sol şerit yok, akışkan kart …)")
    void contractHolds() {
        TeamBlock t = team(true);
        List<MailDoc.Mail> mails = List.of(
                open(t, "x"),
                NocMailComposer.resolved(new NocMailComposer.ResolvedInfo("Port", "Port yanıt vermiyor", "db.example.com:5432",
                        "DB", "2026-01-10T21:05:00", "2026-01-10T21:40:00", "Sistem", t, BASE + "/?tab=port&monitor=1", null)),
                NocMailComposer.storm(12, "Tüm izlemeler", "Port Kesintisi", "2026-01-10T21:05:00",
                        List.of(new NocMailComposer.Member("a.example.com:443", "Port", "Takım A", BASE + "/?tab=port&monitor=1",
                                BASE + "/?tab=alerthistory&alert=9&n_call=1")),
                        List.of(t), "talimat", BASE + "/?tab=noc", List.of("NOC Ana")),
                NocMailComposer.stormResolved(List.of(new NocMailComposer.Member("a.example.com:443", "Port", "Takım A", null, null)),
                        List.of(), "2026-01-10T21:05:00", "2026-01-10T21:45:00"),
                NocMailComposer.test("NOC Ana", null));
        for (MailDoc.Mail m : mails) {
            assertThat(EmailResponsiveContractTest.violations(m.html(), m.text())).isEmpty();
        }
    }

    @Test
    @DisplayName("saat İstanbul'a çevrilir (UTC 21:05 → 00:05 ertesi gün)")
    void timesAreIstanbul() {
        String html = open(team(true), null).html();
        assertThat(html).contains("11.01.2026 00:05");
    }
}
