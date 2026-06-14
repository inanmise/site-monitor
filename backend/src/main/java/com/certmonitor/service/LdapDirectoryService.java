package com.certmonitor.service;

import com.certmonitor.model.LdapSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
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
public class LdapDirectoryService {

    private static final String CTX_FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final LdapSettingsService settingsService;

    public LdapDirectoryService(LdapSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ── Public operations ────────────────────────────────────────────────────

    /** Binds with the service account against the current settings. */
    public Map<String, Object> testConnection() {
        LdapSettings s = settingsService.getOrDefaults();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.getHost() == null || s.getHost().isBlank()) {
            out.put("success", false);
            out.put("error", "LDAP host yapılandırılmamış");
            return out;
        }
        long start = System.currentTimeMillis();
        try (LdapConn conn = open(s)) {
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
    public Map<String, Object> queryUser(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Kullanıcı adı boş olamaz");
        }
        LdapSettings s = settingsService.getOrDefaults();
        if (s.getHost() == null || s.getHost().isBlank()) {
            throw new IllegalArgumentException("LDAP host yapılandırılmamış");
        }
        if (s.getBaseDn() == null || s.getBaseDn().isBlank()) {
            throw new IllegalArgumentException("Base DN yapılandırılmamış");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        String filter = buildUserFilter(s, username.trim());
        try (LdapConn conn = open(s)) {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setCountLimit(2);
            controls.setTimeLimit(READ_TIMEOUT_MS);
            controls.setReturningAttributes(null); // all attributes

            SearchResult first = firstResult(conn.ctx.search(s.getBaseDn(), filter, controls));
            if (first == null) {
                out.put("found", false);
                out.put("filter", filter);
                return out;
            }
            String dn = first.getNameInNamespace();
            Map<String, Object> attrs = readAttributes(first.getAttributes());

            out.put("found", true);
            out.put("dn", dn);
            out.put("filter", filter);
            out.put("attributes", attrs);
            out.put("groups", resolveGroups(conn.ctx, s, dn, attrs));
            return out;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }
    }

    // ── Connection handling ──────────────────────────────────────────────────

    private LdapConn open(LdapSettings s) throws Exception {
        boolean startTls = Boolean.TRUE.equals(s.getStartTls());
        boolean ldaps = Boolean.TRUE.equals(s.getUseLdaps()) && !startTls;

        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, CTX_FACTORY);
        env.put(Context.PROVIDER_URL, url(s));
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(CONNECT_TIMEOUT_MS));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(READ_TIMEOUT_MS));

        boolean customTls = ldaps && needsCustomTls(s);
        if (ldaps) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
            if (customTls) {
                ConfigurableSslSocketFactory.set(buildSslSocketFactory(s));
                env.put("java.naming.ldap.factory.socket", ConfigurableSslSocketFactory.class.getName());
            }
        }

        if (startTls) {
            // StartTLS: connect anonymously in the clear, negotiate TLS, then bind.
            LdapContext ctx = new InitialLdapContext(env, null);
            StartTlsResponse tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
            if (Boolean.TRUE.equals(s.getSkipCertVerification())) {
                tls.setHostnameVerifier(ACCEPT_ALL_HOSTS);
                tls.negotiate(buildSslSocketFactory(s));
            } else if (s.getCaCertPem() != null && !s.getCaCertPem().isBlank()) {
                tls.negotiate(buildSslSocketFactory(s));
            } else {
                tls.negotiate();
            }
            applyBind(ctx, s);
            ctx.reconnect(null);
            return new LdapConn(ctx, tls, false);
        }

        applyBindEnv(env, s);
        LdapContext ctx = new InitialLdapContext(env, null);
        return new LdapConn(ctx, null, customTls);
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

    private static boolean needsCustomTls(LdapSettings s) {
        return Boolean.TRUE.equals(s.getSkipCertVerification())
                || (s.getCaCertPem() != null && !s.getCaCertPem().isBlank());
    }

    private SSLSocketFactory buildSslSocketFactory(LdapSettings s) throws Exception {
        if (Boolean.TRUE.equals(s.getSkipCertVerification())) {
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

    private String buildUserFilter(LdapSettings s, String username) {
        String esc = escapeFilter(username);
        String base = s.getUserSearchFilter();
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
    private static String escapeFilter(String v) {
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
