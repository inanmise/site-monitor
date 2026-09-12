package com.sitemonitor.service;

/**
 * Alan adı doğrulama — TEK KAYNAK (2026-09-12, envanter içe aktarma).
 *
 * <p>{@code AdminController.validateDomain} ile içe aktarma servisi aynı kuralı paylaşır: URL
 * yapıştırılırsa host'a indirgenir ({@link PublicSuffixService#extractHost}), uzunluk 253, etiket
 * biçimi RFC-1123 (tek etiketli iç host'lar da kabul). Kural iki yerde yazılsaydı biri diğerinden
 * ayrışacaktı (ör. içe aktarma "www.a.com/" kabul ederken form reddederdi).
 */
public final class DomainNames {
    private DomainNames() {}

    public static String validate(String domain) {
        if (domain == null || domain.isBlank())
            throw new IllegalArgumentException("Domain cannot be blank");
        String host = PublicSuffixService.extractHost(domain);
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("Domain cannot be blank");
        if (host.length() > 253)
            throw new IllegalArgumentException("Domain name too long");
        if (!host.matches("^(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$")
                && !host.matches("^[a-zA-Z0-9\\-]{1,63}$")) {
            throw new IllegalArgumentException("Invalid domain format: " + domain);
        }
        return host;
    }
}
