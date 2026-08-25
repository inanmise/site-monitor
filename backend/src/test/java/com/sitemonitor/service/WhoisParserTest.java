package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** WHOIS metin ayrıştırma: tarih normalizasyonu + genel/gTLD ve .tr (nic.tr) parser. */
class WhoisParserTest {

    @Test
    @DisplayName("parseDate çeşitli formatları ISO'ya normalize eder; olmazsa null")
    void parseDate() {
        assertThat(WhoisParser.parseDate("2025-08-14")).isEqualTo("2025-08-14");
        assertThat(WhoisParser.parseDate("14-Aug-2025")).isEqualTo("2025-08-14");
        assertThat(WhoisParser.parseDate("2025-Aug-14")).isEqualTo("2025-08-14");
        assertThat(WhoisParser.parseDate("2025-08-14T04:00:00Z")).isEqualTo("2025-08-14");
        assertThat(WhoisParser.parseDate("2026-Jan-15.")).isEqualTo("2026-01-15");   // trailing nokta
        assertThat(WhoisParser.parseDate("garbage")).isNull();
        assertThat(WhoisParser.parseDate(null)).isNull();
    }

    @Test
    @DisplayName("DefaultWhoisParser gTLD çıktısını ayrıştırır")
    @SuppressWarnings("unchecked")
    void defaultParser() {
        String raw = "Domain Name: EXAMPLE.COM\n"
                + "Registrar: MarkMonitor Inc.\n"
                + "Registry Expiry Date: 2026-08-13T04:00:00Z\n"
                + "Creation Date: 1995-08-14T04:00:00Z\n"
                + "Domain Status: clientTransferProhibited https://icann.org/epp#clientTransferProhibited\n"
                + "Name Server: A.IANA-SERVERS.NET\n"
                + "Name Server: B.IANA-SERVERS.NET\n";
        Map<String, Object> r = new DefaultWhoisParser().parse(raw);
        assertThat(r.get("expiry_date")).isEqualTo("2026-08-13");
        assertThat(r.get("registration_date")).isEqualTo("1995-08-14");
        assertThat(r.get("registrar")).isEqualTo("MarkMonitor Inc.");
        assertThat((List<String>) r.get("status_codes")).contains("clienttransferprohibited");
        assertThat((List<String>) r.get("nameservers")).contains("a.iana-servers.net", "b.iana-servers.net");
    }

    @Test
    @DisplayName("TrWhoisParser gerçek whois.trabis.gov.tr çıktısını ayrıştırır (wingscard.com.tr formatı)")
    @SuppressWarnings("unchecked")
    void trParser() {
        // whois.trabis.gov.tr'nin wingscard.com.tr için gerçek yanıt biçimi (Turkish char'lar ASCII'lendi).
        String raw = "** Domain Name: wingscard.com.tr\n"
                + "Domain Status: Active\n"
                + "Frozen Status: -\n"
                + "Transfer Status: The domain is LOCKED to transfer.\n\n"
                + "** Registrant:\n"
                + "EXAMPLE A.S.\n"
                + "Hidden upon user request\n\n"
                + "** Registrar:\n"
                + "NIC Handle\t\t: itt46\n"
                + "Organization Name\t: IHS KURUMSAL TEKNOLOJI HIZMETLERI A.S.\n"
                + "Address\t\t\t: Kosuyolu Mah.\n\n"
                + "** Domain Servers:\n"
                + "ns1.example.com.tr\n"
                + "srv.example.com.tr\n"
                + "ns12.example.com.tr\n\n"
                + "** Additional Info:\n"
                + "Created on..............: 2006-Oct-27.\n"
                + "Expires on..............: 2029-Oct-26.\n";
        Map<String, Object> r = new TrWhoisParser().parse(raw);
        assertThat(r.get("expiry_date")).isEqualTo("2029-10-26");
        assertThat(r.get("registration_date")).isEqualTo("2006-10-27");
        assertThat((String) r.get("registrar")).contains("KURUMSAL");
        assertThat((List<String>) r.get("nameservers")).contains("ns1.example.com.tr", "srv.example.com.tr", "ns12.example.com.tr");
        assertThat((List<String>) r.get("status_codes")).contains("clientTransferProhibited");
    }
}
