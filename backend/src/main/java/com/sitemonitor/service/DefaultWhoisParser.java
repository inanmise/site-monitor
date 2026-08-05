package com.sitemonitor.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Yaygın (thin/thick gTLD + çoğu ccTLD) WHOIS formatları için genel parser. */
public class DefaultWhoisParser implements WhoisParser {

    @Override
    public Map<String, Object> parse(String raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expiry_date", WhoisParser.parseDate(WhoisParser.firstValue(raw,
                "Registry Expiry Date", "Registrar Registration Expiration Date", "Expiration Date",
                "Expiry Date", "Expiry date", "Expires On", "Expires on", "expire", "expires",
                "paid-till", "renewal date", "Valid Until")));
        out.put("registration_date", WhoisParser.parseDate(WhoisParser.firstValue(raw,
                "Creation Date", "Created On", "Created", "created", "Registered on", "Registration Time", "Domain Registration Date")));
        out.put("last_changed", WhoisParser.parseDate(WhoisParser.firstValue(raw,
                "Updated Date", "Last Updated", "Last update", "last-update", "changed", "Modified")));
        out.put("registrar", WhoisParser.firstValue(raw,
                "Registrar", "Sponsoring Registrar", "Registrar Name"));
        out.put("status_codes", WhoisParser.allFirstTokens(raw, "Domain Status", "Status", "status"));
        out.put("nameservers", WhoisParser.allFirstTokens(raw, "Name Server", "Nameserver", "nserver", "Name servers"));
        // DNSSEC — "DNSSEC: signedDelegation" / "unsigned" / "yes" / "no"
        String dnssecRaw = WhoisParser.firstValue(raw, "DNSSEC", "dnssec");
        if (dnssecRaw != null && !dnssecRaw.isBlank()) {
            String d = dnssecRaw.toLowerCase(java.util.Locale.ROOT);
            if (d.contains("unsign") || d.equals("no")) out.put("dnssec", "unsigned");
            else if (d.contains("sign") || d.equals("yes")) out.put("dnssec", "signed");
        }
        return out;
    }
}
