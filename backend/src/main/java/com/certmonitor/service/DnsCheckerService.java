package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Type;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * DNS sorgu servisi (dnsjava tabanlı). JNDI versiyonundan geçildi ki TTL,
 * yanıt süresi, SOA ve authoritative nameserver bilgileri yakalanabilsin.
 */
@Slf4j
@Service
public class DnsCheckerService {

    private static final String[] STANDARD_TYPES = {"A", "AAAA", "CNAME", "MX", "TXT", "NS"};

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String domain, String recordType) {
        return CompletableFuture.completedFuture(check(domain, recordType));
    }

    /**
     * Tek kayıt tipi sorgusu. Sonuç:
     *   success: boolean
     *   values: List<String> (sıralı)
     *   ttl: Long (en küçük TTL, null mümkün)
     *   response_ms: Long (sorgu süresi)
     *   error: String (success=false ise)
     */
    public Map<String, Object> check(String domain, String recordType) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            int type = typeOf(recordType);
            Lookup lookup = new Lookup(domain, type);
            Record[] records = lookup.run();
            long responseMs = (System.nanoTime() - start) / 1_000_000L;

            List<String> values = new ArrayList<>();
            Long minTtl = null;
            if (records != null) {
                for (Record r : records) {
                    values.add(rdataAsString(r));
                    long t = r.getTTL();
                    if (minTtl == null || t < minTtl) minTtl = t;
                }
                Collections.sort(values);
            }

            boolean ok = lookup.getResult() == Lookup.SUCCESSFUL && records != null;
            result.put("success", ok);
            result.put("values", values);
            result.put("ttl", minTtl);
            result.put("response_ms", responseMs);
            if (!ok) {
                result.put("error", lookup.getErrorString());
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("values", List.of());
            result.put("response_ms", (System.nanoTime() - start) / 1_000_000L);
            result.put("error", e.getMessage());
            log.debug("DNS check failed for {} {}: {}", recordType, domain, e.getMessage());
        }
        return result;
    }

    /**
     * Tek bir domain için tüm temel kayıt tiplerini + SOA + authoritative NS'leri tek payload'da döner.
     * Detail modal'da kullanılır.
     */
    public Map<String, Object> enrichedQuery(String domain) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> records = new LinkedHashMap<>();
        for (String type : STANDARD_TYPES) {
            records.put(type, check(domain, type));
        }
        result.put("records", records);

        // Authoritative servers — NS kayıt değerleri
        Object nsValues = records.get("NS").get("values");
        result.put("authoritative_servers", nsValues);

        // SOA
        result.put("soa", querySoa(domain));

        return result;
    }

    public Map<String, Object> querySoa(String domain) {
        Map<String, Object> soa = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            Lookup lookup = new Lookup(domain, Type.SOA);
            Record[] records = lookup.run();
            long responseMs = (System.nanoTime() - start) / 1_000_000L;
            soa.put("response_ms", responseMs);
            if (records != null && records.length > 0 && records[0] instanceof SOARecord r) {
                soa.put("primary_ns",  r.getHost().toString());
                soa.put("admin_email", r.getAdmin().toString());
                soa.put("serial",      r.getSerial());
                soa.put("refresh",     r.getRefresh());
                soa.put("retry",       r.getRetry());
                soa.put("expire",      r.getExpire());
                soa.put("minimum_ttl", r.getMinimum());
                soa.put("ttl",         r.getTTL());
                soa.put("success",     true);
            } else {
                soa.put("success", false);
                soa.put("error", lookup.getErrorString());
            }
        } catch (Exception e) {
            soa.put("success", false);
            soa.put("error", e.getMessage());
        }
        return soa;
    }

    private static int typeOf(String recordType) {
        if (recordType == null) return Type.A;
        return switch (recordType.toUpperCase(Locale.ROOT)) {
            case "A"     -> Type.A;
            case "AAAA"  -> Type.AAAA;
            case "CNAME" -> Type.CNAME;
            case "MX"    -> Type.MX;
            case "TXT"   -> Type.TXT;
            case "NS"    -> Type.NS;
            case "SOA"   -> Type.SOA;
            case "PTR"   -> Type.PTR;
            case "SRV"   -> Type.SRV;
            case "CAA"   -> Type.CAA;
            default       -> Type.A;
        };
    }

    private static String rdataAsString(Record r) {
        return r.rdataToString();
    }
}
