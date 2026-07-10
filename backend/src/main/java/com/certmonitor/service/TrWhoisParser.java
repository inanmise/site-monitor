package com.certmonitor.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * whois.nic.tr (.tr — TRABIS) çıktısı için özel parser. .tr WHOIS'in RDAP'ı yoktur ve formatı
 * standart gTLD'den farklıdır: bölümler "** Domain Servers:" gibi başlıklarla, tarihler
 * "Expires on..............: 2025-Aug-14." biçiminde. (Gerçek nic.tr çıktısına göre ince ayar
 * gerekebilir — pluggable tasarım bunu kolaylaştırır.)
 */
public class TrWhoisParser implements WhoisParser {

    @Override
    public Map<String, Object> parse(String raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expiry_date", WhoisParser.parseDate(WhoisParser.firstValue(raw,
                "Expires on", "Expiry Date", "Expiration Date")));
        out.put("registration_date", WhoisParser.parseDate(WhoisParser.firstValue(raw,
                "Created on", "Creation Date", "Registration Date")));
        out.put("last_changed", WhoisParser.parseDate(WhoisParser.firstValue(raw,
                "Updated on", "Last Update", "Modified")));
        out.put("registrar", WhoisParser.firstValue(raw,
                "Organization Name", "Registrar", "Registrar Name"));
        // .tr status: "Domain Status: Active" + "Transfer Status: ... LOCKED ..." → transfer kilidi EPP koduna eşlenir.
        List<String> codes = new ArrayList<>(WhoisParser.allFirstTokens(raw, "Domain Status", "Status"));
        String transfer = WhoisParser.firstValue(raw, "Transfer Status");
        if (transfer != null) {
            String tl = transfer.toLowerCase(Locale.ROOT);
            if (tl.contains("locked") && !tl.contains("unlock")) codes.add("clientTransferProhibited");
        }
        out.put("status_codes", codes);
        // nic.tr "** Domain Servers:" başlığından sonra çıplak host satırları listeler.
        out.put("nameservers", parseTrNameservers(raw));
        return out;
    }

    /** "** Domain Servers:" başlığından sonraki çıplak host satırlarını toplar (bir sonraki "**" bölümüne kadar). */
    private static List<String> parseTrNameservers(String raw) {
        List<String> ns = new ArrayList<>();
        if (raw == null) return ns;
        boolean in = false;
        for (String line : raw.split("\\r?\\n")) {
            String l = line.trim();
            if (l.toLowerCase(Locale.ROOT).startsWith("** domain servers") || l.toLowerCase(Locale.ROOT).startsWith("** nameservers")) { in = true; continue; }
            if (in) {
                if (l.startsWith("**") || l.isEmpty()) { in = false; continue; }
                String host = l.split("\\s+")[0].toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
                if (host.contains(".") && !ns.contains(host)) ns.add(host);
            }
        }
        // Fallback: "Name Server:" satırları
        if (ns.isEmpty()) ns.addAll(WhoisParser.allFirstTokens(raw, "Name Server", "nserver"));
        return ns;
    }
}
