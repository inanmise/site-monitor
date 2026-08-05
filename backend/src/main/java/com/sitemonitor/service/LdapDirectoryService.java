package com.sitemonitor.service;

import com.sitemonitor.model.LdapSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime LDAP / Active Directory operations driven entirely by the current
 * {@link LdapSettings} (no Spring beans wired at startup → fully reconfigurable
 * without a restart). Built on the JDK's JNDI LDAP provider, so no extra
 * dependency is required. Supports LDAPS (direct TLS), StartTLS, skip-cert
 * verification, and an optional internal-CA PEM trust bundle.
 *
 * <p>Phase 1 exposes only {@link #testConnection()} (service-account bind) and
 * {@link #queryUser(String)} (the "what does AD return for this user" inspector).
 * User authentication + provisioning land in a later phase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LdapDirectoryService {

    private static final String CTX_FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    /** Cap for multi-match searches (e.g. memberOf group membership). */
    private static final int MAX_MATCHES = 50;

    private final LdapSettingsService settingsService;

    /** Trust-all uyarısı için kısma penceresi — her bağlantıda değil, en fazla 10 dk'da bir loglanır. */
    private static final long TRUST_ALL_WARN_INTERVAL_MS = 600_000L;
    private final java.util.concurrent.atomic.AtomicLong lastTrustAllWarnAt =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // ── Public operations ────────────────────────────────────────────────────

    /** Binds with the service account against the current settings. */
    public Map<String, Object> testConnection() {
        return testConnection(false);
    }

    /**
     * Bind testi. {@code forceVerify=true} ise KAYITLI ayar değiştirilmeden sertifika doğrulaması
     * AÇIK (yüklü CA PEM ile) denenir — admin, "sertifika doğrulamasını atla" seçeneğini kapatmadan
     * önce bağlantının gerçekten kurulacağını görebilsin diye. Yeşilse ayar güvenle kapatılabilir.
     */
    public Map<String, Object> testConnection(boolean forceVerify) {
        LdapSettings s = settingsService.getOrDefaults();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.getHost() == null || s.getHost().isBlank()) {
            out.put("success", false);
            out.put("error", "LDAP host yapılandırılmamış");
            return out;
        }
        if (forceVerify) out.put("verified", true);
        long start = System.currentTimeMillis();
        try (LdapConn conn = open(s, forceVerify)) {   // forceVerify: kayıtlı ayar değişmeden doğrulama açık
            // A trivial read proves the bind + transport actually work.
            conn.ctx.getAttributes("", new String[]{"namingContexts"});
            out.put("success", true);
            out.put("message", "Bind başarılı (" + url(s) + ")");
            out.put("elapsed_ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", rootMessage(e));
        }
        return out;
    }

    /**
     * Searches the directory for {@code username} and returns the raw attribute
     * set of the first match plus resolved group memberships — the inspector the
     * admin uses to decide field mappings before provisioning is wired in.
     */
    public Map<String, Object> queryUser(String value, String searchAttr) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Arama değeri boş olamaz");
        }
        LdapSettings s = settingsService.getOrDefaults();
        if (s.getHost() == null || s.getHost().isBlank()) {
            throw new IllegalArgumentException("LDAP host yapılandırılmamış");
        }
        if (s.getBaseDn() == null || s.getBaseDn().isBlank()) {
            throw new IllegalArgumentException("Base DN yapılandırılmamış");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        String filter = buildUserFilter(s, value.trim(), searchAttr);
        out.put("filter", filter);
        try (LdapConn conn = open(s)) {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setCountLimit(MAX_MATCHES);   // memberOf/group searches can match many
            controls.setTimeLimit(READ_TIMEOUT_MS);
            controls.setReturningAttributes(null); // all attributes

            List<Map<String, Object>> matches = new ArrayList<>();
            SearchResult firstResult = null;
            Map<String, Object> firstAttrs = null;
            NamingEnumeration<SearchResult> results = conn.ctx.search(s.getBaseDn(), filter, controls);
            try {
                while (results.hasMore()) {
                    SearchResult r = results.next();
                    Map<String, Object> attrs = readAttributes(r.getAttributes());
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("dn", r.getNameInNamespace());
                    summary.put("username", firstAttr(attrs, s.getUserAttribute(), null));
                    summary.put("displayName", firstAttr(attrs, s.getDisplayAttribute(), null));
                    summary.put("email", firstAttr(attrs, s.getEmailAttribute(), null));
                    matches.add(summary);
                    if (firstResult == null) { firstResult = r; firstAttrs = attrs; }
                }
            } catch (PartialResultException | javax.naming.SizeLimitExceededException ignored) {
                // AD referral chasing / size cap — return what we collected so far.
            }

            out.put("found", !matches.isEmpty());
            out.put("count", matches.size());
            out.put("matches", matches);
            // Single hit → also include the full attribute set + resolved groups.
            if (matches.size() == 1 && firstResult != null) {
                String dn = firstResult.getNameInNamespace();
                out.put("dn", dn);
                out.put("attributes", firstAttrs);
                out.put("groups", resolveGroups(conn.ctx, s, dn, firstAttrs));
            }
            return out;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }
    }

    /** Identity returned by a successful {@link #authenticate}. */
    /** Authenticated identity + the full AD attribute map (incl. memberOf) for provisioning. */
    public record LdapUser(String username, String dn, Map<String, Object> attributes) {}

    /**
     * Verifies AD credentials: finds the user with the service account, then binds
     * as that user's DN with the supplied password. Returns the user's directory
     * identity on success, {@link Optional#empty()} on wrong password / user-not-found.
     * Throws {@link IllegalStateException} on configuration / connectivity errors.
     */
    public Optional<LdapUser> authenticate(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            return Optional.empty();
        }
        LdapSettings s = settingsService.getOrDefaults();
        if (s.getHost() == null || s.getHost().isBlank()
                || s.getBaseDn() == null || s.getBaseDn().isBlank()) {
            throw new IllegalStateException("LDAP yapılandırması eksik (host/base DN)");
        }

        String userDn;
        Map<String, Object> attrs;
        String filter = buildUserFilter(s, username.trim(), null);
        try (LdapConn conn = open(s)) {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setCountLimit(2);
            controls.setTimeLimit(READ_TIMEOUT_MS);
            controls.setReturningAttributes(null);
            SearchResult first = firstResult(conn.ctx.search(s.getBaseDn(), filter, controls));
            if (first == null) return Optional.empty(); // not in directory
            userDn = first.getNameInNamespace();
            attrs = readAttributes(first.getAttributes());
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }

        if (!bindAs(s, userDn, rawPassword)) {
            return Optional.empty(); // wrong password
        }
        String sam = firstAttr(attrs, s.getUserAttribute(), username.trim());
        return Optional.of(new LdapUser(sam, userDn, attrs));
    }

    /**
     * Service-account lookup of a single directory entry by an attribute
     * (e.g. {@code cn=<sicil>}). Returns the entry's attributes (with its DN under
     * key {@code "_dn"}), or empty if not found / on error.
     */
    public Optional<Map<String, Object>> findOne(String attr, String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        LdapSettings s = settingsService.getOrDefaults();
        if (s.getHost() == null || s.getHost().isBlank()
                || s.getBaseDn() == null || s.getBaseDn().isBlank()) {
            return Optional.empty();
        }
        String filter = buildUserFilter(s, value.trim(), attr);
        try (LdapConn conn = open(s)) {
            SearchControls c = new SearchControls();
            c.setSearchScope(SearchControls.SUBTREE_SCOPE);
            c.setCountLimit(2);
            c.setTimeLimit(READ_TIMEOUT_MS);
            c.setReturningAttributes(null);
            SearchResult first = firstResult(conn.ctx.search(s.getBaseDn(), filter, c));
            if (first == null) return Optional.empty();
            Map<String, Object> a = readAttributes(first.getAttributes());
            a.put("_dn", first.getNameInNamespace());
            return Optional.of(a);
        } catch (Exception e) {
            log.debug("findOne {}={} failed: {}", attr, value, rootMessage(e));
            return Optional.empty();
        }
    }

    /** Reads the {@code mail} attribute of a group entry by its full DN (service account). */
    public Optional<String> groupMail(String groupDn) {
        if (groupDn == null || groupDn.isBlank()) return Optional.empty();
        LdapSettings s = settingsService.getOrDefaults();
        if (s.getHost() == null || s.getHost().isBlank()) return Optional.empty();
        try (LdapConn conn = open(s)) {
            Attributes a = conn.ctx.getAttributes(groupDn, new String[]{"mail"});
            Attribute m = (a != null) ? a.get("mail") : null;
            if (m != null && m.size() > 0 && m.get() != null) {
                return Optional.of(m.get().toString());
            }
        } catch (Exception e) {
            log.debug("groupMail {} failed: {}", groupDn, rootMessage(e));
        }
        return Optional.empty();
    }

    /** Binds as a specific user DN to verify their password. true=ok, false=bad credentials. */
    private boolean bindAs(LdapSettings s, String userDn, String password) {
        boolean startTls = Boolean.TRUE.equals(s.getStartTls());
        boolean ldaps = Boolean.TRUE.equals(s.getUseLdaps()) && !startTls;
        boolean skipVerify = effectiveSkipVerify(s, false);
        boolean customTls = ldaps && needsCustomTls(s, skipVerify);

        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, CTX_FACTORY);
        env.put(Context.PROVIDER_URL, url(s));
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(CONNECT_TIMEOUT_MS));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(READ_TIMEOUT_MS));

        DirContext ctx = null;
        StartTlsResponse tls = null;
        try {
            if (ldaps) {
                env.put(Context.SECURITY_PROTOCOL, "ssl");
                if (customTls) {
                    ConfigurableSslSocketFactory.set(buildSslSocketFactory(s, skipVerify));
                    env.put("java.naming.ldap.factory.socket", ConfigurableSslSocketFactory.class.getName());
                }
            }
            if (startTls) {
                LdapContext lc = new InitialLdapContext(env, null);
                tls = (StartTlsResponse) lc.extendedOperation(new StartTlsRequest());
                if (skipVerify) {
                    tls.setHostnameVerifier(ACCEPT_ALL_HOSTS);
                    tls.negotiate(buildSslSocketFactory(s, true));
                } else if (s.getCaCertPem() != null && !s.getCaCertPem().isBlank()) {
                    tls.negotiate(buildSslSocketFactory(s, false));
                } else {
                    tls.negotiate();
                }
                lc.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
                lc.addToEnvironment(Context.SECURITY_PRINCIPAL, userDn);
                lc.addToEnvironment(Context.SECURITY_CREDENTIALS, password);
                lc.reconnect(null); // throws AuthenticationException on bad creds
                ctx = lc;
            } else {
                env.put(Context.SECURITY_AUTHENTICATION, "simple");
                env.put(Context.SECURITY_PRINCIPAL, userDn);
                env.put(Context.SECURITY_CREDENTIALS, password);
                ctx = new InitialDirContext(env);
            }
            return true;
        } catch (AuthenticationException ae) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        } finally {
            if (tls != null) try { tls.close(); } catch (Exception ignored) {}
            if (ctx != null) try { ctx.close(); } catch (Exception ignored) {}
            if (customTls) ConfigurableSslSocketFactory.clear();
        }
    }

    /** Case-insensitive single-value attribute read from a {@link #readAttributes} map. */
    static String firstAttr(Map<String, Object> attrs, String name, String fallback) {  // package-private: birim testi
        if (attrs == null || name == null) return fallback;
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                Object v = e.getValue();
                String val = (v instanceof List<?> list && !list.isEmpty())
                        ? String.valueOf(list.get(0)) : String.valueOf(v);
                if (val != null && !val.isBlank() && !"null".equals(val)) return val;
            }
        }
        return fallback;
    }

    // ── Connection handling ──────────────────────────────────────────────────

    private LdapConn open(LdapSettings s) throws Exception {
        return open(s, false);
    }

    /** {@code forceVerify=true}: kayıtlı "doğrulamayı atla" ayarı GEÇİCİ olarak yok sayılır (yalnız test yolu). */
    private LdapConn open(LdapSettings s, boolean forceVerify) throws Exception {
        boolean startTls = Boolean.TRUE.equals(s.getStartTls());
        boolean ldaps = Boolean.TRUE.equals(s.getUseLdaps()) && !startTls;
        boolean skipVerify = effectiveSkipVerify(s, forceVerify);

        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, CTX_FACTORY);
        env.put(Context.PROVIDER_URL, url(s));
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(CONNECT_TIMEOUT_MS));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(READ_TIMEOUT_MS));

        boolean customTls = ldaps && needsCustomTls(s, skipVerify);
        try {
            if (ldaps) {
                env.put(Context.SECURITY_PROTOCOL, "ssl");
                if (customTls) {
                    ConfigurableSslSocketFactory.set(buildSslSocketFactory(s, skipVerify));
                    env.put("java.naming.ldap.factory.socket", ConfigurableSslSocketFactory.class.getName());
                }
            }

            if (startTls) {
                // StartTLS: connect anonymously in the clear, negotiate TLS, then bind.
                LdapContext ctx = new InitialLdapContext(env, null);
                StartTlsResponse tls = null;
                try {
                    tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
                    if (skipVerify) {
                        tls.setHostnameVerifier(ACCEPT_ALL_HOSTS);
                        tls.negotiate(buildSslSocketFactory(s, true));
                    } else if (s.getCaCertPem() != null && !s.getCaCertPem().isBlank()) {
                        tls.negotiate(buildSslSocketFactory(s, false));
                    } else {
                        tls.negotiate();
                    }
                    applyBind(ctx, s);
                    ctx.reconnect(null);
                    return new LdapConn(ctx, tls, false);
                } catch (Exception inner) {
                    // negotiate/bind/reconnect başarısızsa yarı-açık ctx+tls sızmasın:
                    // LdapConn dönmediği için çağırandaki try-with-resources close() çalışmaz.
                    try { if (tls != null) tls.close(); } catch (Exception ignored) {}
                    try { ctx.close(); } catch (Exception ignored) {}
                    throw inner;
                }
            }

            applyBindEnv(env, s);
            LdapContext ctx = new InitialLdapContext(env, null);
            return new LdapConn(ctx, null, customTls);
        } catch (Exception e) {
            // Bağlantı/bind kurulamazsa LdapConn dönmez → çağırandaki try-with-resources
            // close()'u çağrılamaz → ThreadLocal SSL factory havuzdaki thread'de sızar.
            // Hata yolunda da temizle (yalnız customTls set ettiyse).
            if (customTls) ConfigurableSslSocketFactory.clear();
            throw e;
        }
    }

    private void applyBindEnv(Hashtable<String, Object> env, LdapSettings s) {
        if (s.getBindDn() != null && !s.getBindDn().isBlank()) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, s.getBindDn());
            String pw = settingsService.decryptedBindPassword();
            env.put(Context.SECURITY_CREDENTIALS, pw != null ? pw : "");
        } else {
            env.put(Context.SECURITY_AUTHENTICATION, "none");
        }
    }

    private void applyBind(LdapContext ctx, LdapSettings s) throws NamingException {
        if (s.getBindDn() != null && !s.getBindDn().isBlank()) {
            ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
            ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, s.getBindDn());
            String pw = settingsService.decryptedBindPassword();
            ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, pw != null ? pw : "");
        } else {
            ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "none");
        }
    }

    static boolean needsCustomTls(LdapSettings s, boolean skipVerify) {   // package-private: test görsün
        return skipVerify || (s.getCaCertPem() != null && !s.getCaCertPem().isBlank());
    }

    /**
     * Geçerli "doğrulamayı atla" değeri + UYARI. Atlama açıkken sertifika zinciri ve hostname HİÇ
     * doğrulanmaz; bind parolası ve tüm kullanıcı parolaları bu kanaldan geçtiği için LDAPS yalnız
     * şifreleme sağlar, kimlik doğrulama sağlamaz (aktif MITM mümkün). CA PEM yüklüyse de kullanılmaz.
     * {@code forceVerify} test yolundan gelir: kayıtlı ayarı değiştirmeden doğrulamalı deneme yapılır.
     */
    boolean effectiveSkipVerify(LdapSettings s, boolean forceVerify) {   // package-private: test görsün
        boolean skip = !forceVerify && Boolean.TRUE.equals(s.getSkipCertVerification());
        if (skip) warnTrustAllThrottled(s);
        return skip;
    }

    private void warnTrustAllThrottled(LdapSettings s) {
        long now = System.currentTimeMillis();
        long last = lastTrustAllWarnAt.get();
        if (now - last < TRUST_ALL_WARN_INTERVAL_MS || !lastTrustAllWarnAt.compareAndSet(last, now)) return;
        boolean pemLoaded = s.getCaCertPem() != null && !s.getCaCertPem().isBlank();
        log.warn("LDAP sertifika doğrulaması KAPALI (skip_cert_verification=true) — bind ve kullanıcı "
                + "parolaları doğrulanmamış TLS üzerinden geçiyor{}. Yönetim → LDAP ekranından "
                + "\"CA ile doğrulayarak test et\" yeşil dönüyorsa bu ayarı kapatın.",
                pemLoaded ? "; yüklü CA sertifikası KULLANILMIYOR" : "");
    }

    private SSLSocketFactory buildSslSocketFactory(LdapSettings s, boolean skipVerify) throws Exception {
        if (skipVerify) {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            return sc.getSocketFactory();
        }
        if (s.getCaCertPem() != null && !s.getCaCertPem().isBlank()) {
            return socketFactoryFromPem(s.getCaCertPem());
        }
        return (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    private SSLSocketFactory socketFactoryFromPem(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs =
                cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (Certificate c : certs) {
            ks.setCertificateEntry("ca-" + (i++), c);
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tmf.getTrustManagers(), new SecureRandom());
        return sc.getSocketFactory();
    }

    // ── Search helpers ─────────────────────────────────────────────────────────

    /**
     * Builds the user search filter.
     * <ul>
     *   <li>{@code searchAttr == "_raw_"} → {@code value} is used as a raw LDAP filter.</li>
     *   <li>{@code searchAttr} given (mail/cn/displayName/…) → {@code (&(<baseFilter>)(<attr>=<value>))}.</li>
     *   <li>blank → configured {@code userAttribute} / {{username}} template (default behaviour).</li>
     * </ul>
     */
    String buildUserFilter(LdapSettings s, String value, String searchAttr) {  // package-private: birim testi
        if ("_raw_".equals(searchAttr)) {
            return value; // admin-supplied raw LDAP filter
        }
        String esc = escapeFilter(value);
        String base = s.getUserSearchFilter();

        if (searchAttr != null && !searchAttr.isBlank()) {
            // Değer escapeFilter'lı ama attribute ADI da filtreye gömülüyor → LDAP filtre injection'ı
            // önlemek için yalnız geçerli attribute adı kabul et (admin-gated uçta savunma derinliği).
            if (!searchAttr.matches("[a-zA-Z][a-zA-Z0-9-]*")) {
                throw new IllegalArgumentException("Geçersiz arama attribute adı: " + searchAttr);
            }
            String inner = "(" + searchAttr + "=" + esc + ")";
            // Combine with a plain base filter (skip when base is a {{username}} template).
            if (base != null && !base.isBlank() && !base.contains("{{username}}")) {
                return "(&" + base + inner + ")";
            }
            return inner;
        }

        if (base != null && base.contains("{{username}}")) {
            return base.replace("{{username}}", esc);
        }
        String attr = (s.getUserAttribute() != null && !s.getUserAttribute().isBlank())
                ? s.getUserAttribute() : "sAMAccountName";
        String inner = "(" + attr + "=" + esc + ")";
        if (base != null && !base.isBlank()) {
            return "(&" + base + inner + ")";
        }
        return inner;
    }

    private List<String> resolveGroups(LdapContext ctx, LdapSettings s, String userDn,
                                       Map<String, Object> attrs) {
        // Prefer memberOf returned on the user entry…
        if (!Boolean.TRUE.equals(s.getSkipMemberOf())) {
            Object memberOf = attrs.get("memberOf");
            if (memberOf instanceof List<?> list && !list.isEmpty()) {
                List<String> g = new ArrayList<>();
                for (Object o : list) g.add(String.valueOf(o));
                return g;
            }
            if (memberOf instanceof String one && !one.isBlank()) {
                List<String> g = new ArrayList<>();
                g.add(one);
                return g;
            }
        }
        // …otherwise (or when skipping memberOf) do a separate group search.
        if (s.getGroupSearchBase() != null && !s.getGroupSearchBase().isBlank()) {
            try {
                String gf = (s.getGroupFilter() != null && !s.getGroupFilter().isBlank())
                        ? s.getGroupFilter() : "(objectclass=group)";
                String filter = "(&" + gf + "(member=" + escapeFilter(userDn) + "))";
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                controls.setReturningAttributes(new String[]{"cn", "distinguishedName"});
                List<String> groups = new ArrayList<>();
                NamingEnumeration<SearchResult> results = ctx.search(s.getGroupSearchBase(), filter, controls);
                try {
                    while (results.hasMore()) {
                        groups.add(results.next().getNameInNamespace());
                    }
                } catch (PartialResultException ignored) {
                    // AD referral chasing — stop at what we have.
                }
                return groups;
            } catch (NamingException e) {
                log.debug("Group search failed: {}", e.getMessage());
            }
        }
        return new ArrayList<>();
    }

    private SearchResult firstResult(NamingEnumeration<SearchResult> results) throws NamingException {
        try {
            if (results.hasMore()) return results.next();
        } catch (PartialResultException ignored) {
            // No real match before a referral — treat as not found.
        }
        return null;
    }

    private Map<String, Object> readAttributes(Attributes attributes) throws NamingException {
        Map<String, Object> map = new LinkedHashMap<>();
        if (attributes == null) return map;
        NamingEnumeration<? extends Attribute> all = attributes.getAll();
        while (all.hasMore()) {
            Attribute attr = all.next();
            String id = attr.getID();
            if (attr.size() == 1) {
                map.put(id, renderValue(attr.get()));
            } else {
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < attr.size(); i++) values.add(renderValue(attr.get(i)));
                map.put(id, values);
            }
        }
        return map;
    }

    private Object renderValue(Object v) {
        if (v == null) return null;
        if (v instanceof byte[] bytes) {
            // Binary attributes (objectSid, objectGUID…) — show as base64 so they're readable.
            return "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
        return v.toString();
    }

    private static String url(LdapSettings s) {
        boolean ldaps = Boolean.TRUE.equals(s.getUseLdaps()) && !Boolean.TRUE.equals(s.getStartTls());
        String scheme = ldaps ? "ldaps" : "ldap";
        int port = s.getPort() != null ? s.getPort() : (ldaps ? 636 : 389);
        return scheme + "://" + s.getHost() + ":" + port;
    }

    /** RFC 4515 filter escaping. */
    static String escapeFilter(String v) {  // package-private: birim testi
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cur.getClass().getSimpleName();
    }

    // ── Supporting types ─────────────────────────────────────────────────────

    private record LdapConn(LdapContext ctx, StartTlsResponse tls, boolean customTls) implements AutoCloseable {
        @Override
        public void close() {
            try { if (tls != null) tls.close(); } catch (Exception ignored) {}
            try { if (ctx != null) ctx.close(); } catch (Exception ignored) {}
            if (customTls) ConfigurableSslSocketFactory.clear();
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private static final HostnameVerifier ACCEPT_ALL_HOSTS = (hostname, session) -> true;

    /**
     * SSLSocketFactory whose behaviour is supplied per-call via a ThreadLocal,
     * so JNDI (which only accepts a factory <em>class name</em>) can use a
     * dynamically-built SSLContext (skip-cert or custom-CA). JNDI instantiates
     * it through the static {@link #getDefault()}.
     */
    public static final class ConfigurableSslSocketFactory extends SSLSocketFactory {
        private static final ThreadLocal<SSLSocketFactory> DELEGATE = new ThreadLocal<>();

        public static void set(SSLSocketFactory f) { DELEGATE.set(f); }
        public static void clear() { DELEGATE.remove(); }

        public static SocketFactory getDefault() { return new ConfigurableSslSocketFactory(); }

        private SSLSocketFactory delegate() {
            SSLSocketFactory f = DELEGATE.get();
            return f != null ? f : (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        @Override public String[] getDefaultCipherSuites() { return delegate().getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate().getSupportedCipherSuites(); }

        @Override public java.net.Socket createSocket(java.net.Socket sck, String host, int port, boolean autoClose) throws java.io.IOException {
            return delegate().createSocket(sck, host, port, autoClose);
        }
        @Override public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return delegate().createSocket(host, port);
        }
        @Override public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
            return delegate().createSocket(host, port, localHost, localPort);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return delegate().createSocket(host, port);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
            return delegate().createSocket(address, port, localAddress, localPort);
        }
    }
}
