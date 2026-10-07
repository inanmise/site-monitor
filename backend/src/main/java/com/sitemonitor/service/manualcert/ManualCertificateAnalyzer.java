package com.sitemonitor.service.manualcert;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.service.CertificateFacts;
import com.sitemonitor.service.CertificateHealthRules;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.manualcert.CertificateFileParser.ParsedCert;
import com.sitemonitor.service.manualcert.CertificateFileParser.Warning;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Yüklenen sertifika dosyasının ANALİZİ (2026-10-06) — ayrıştırma ({@link CertificateFileParser}) + girdi başına zincir,
 * durum, uyarı, zayıflık, güven, önerilen takip adı ve mevcut kayıtlarla eşleşme.
 *
 * <p><b>Zincir başına TEK girdi (2026-10-07, kullanıcı isteği):</b> dosyadaki yaprak + ara + kök gibi parçalar ayrı ayrı
 * takip kartı olmaz. Her tekil sertifikanın zinciri kurulur; BAŞKA bir sertifikanın zincirinde (veren / ara / kök
 * olarak) yer alan sertifika ayrı girdi DEĞİLDİR, o zincirin başına katlanır. Girdiler = zincir başları:
 * yaprak + ara + kök → 1 (yaprak); ara + kök → 1 (ara); ortak arayı paylaşan iki yaprak → 2; ilgisiz iki kök → 2;
 * tek sertifika → 1. Döngülü (çapraz imzalı) bir küme hiç baş bırakmazsa dosya sırasındaki ilk kapsanmayan sertifika
 * baş olur — hiçbir sertifika kaybolmaz. Katlanan sertifika {@code ref} olarak seçilemez ({@link Analysis#headOf}).
 *
 * <p>Salt okuma: veritabanına YAZMAZ. Eşleşme sorguları TOPLUdur (girdi sayısından bağımsız, en çok beş sorgu).
 * Ayrıştırma {@value #PARSE_TIMEOUT_SECONDS} sn ile zaman kutusundadır (küçük, sınırlı bir iş havuzunda).
 */
@Slf4j
@Service
public class ManualCertificateAnalyzer {

    public static final int PARSE_TIMEOUT_SECONDS = 10;

    private final TrustEvaluator trustEvaluator;
    private final ManualCertificateVersionRepository versionRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;

    @Value("${site.monitor.warning-days:30}")
    private int warningDays = 30;

    /** Ayrıştırma havuzu: 2 iş parçacığı + 8 bekleyen; dolarsa {@link BusyException} (429). */
    private final ThreadPoolExecutor parsePool;

    public ManualCertificateAnalyzer(TrustEvaluator trustEvaluator,
                                     ManualCertificateVersionRepository versionRepo,
                                     CertificateInventoryRepository inventoryRepo,
                                     LatestCheckRepository latestCheckRepo) {
        this.trustEvaluator = trustEvaluator;
        this.versionRepo = versionRepo;
        this.inventoryRepo = inventoryRepo;
        this.latestCheckRepo = latestCheckRepo;
        AtomicInteger n = new AtomicInteger();
        this.parsePool = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> {
            Thread t = new Thread(r, "manual-cert-parse-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        parsePool.shutdownNow();
    }

    /** Ayrıştırma süre sınırını aştı. */
    public static class TimeoutExceededException extends RuntimeException {
        public TimeoutExceededException() { super("parse timeout"); }
    }

    /** Ayrıştırma havuzu dolu. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("parser busy"); }
    }

    /**
     * Ham yükleme özel anahtar / anahtar deposu taşıyor (2026-10-08): sunucu bunu AÇMAZ ve kabul etmez — çağıran 400
     * {@code PRIVATE_KEY_NOT_ACCEPTED} döner. {@link #kind()} yalnız tür ({@code PKCS12}, {@code JKS}, {@code PRIVATE_KEY} …).
     */
    public static class PrivateMaterialRejected extends RuntimeException {
        private final String kind;
        public PrivateMaterialRejected(String kind) {
            super("private material rejected", null, false, false);
            this.kind = kind;
        }
        public String kind() { return kind; }
    }

    // ── Sonuç modeli ─────────────────────────────────────────────────────────

    /** Bir girdi (tekilleştirilmiş sertifika). */
    public static final class Entry {
        public final X509Certificate cert;
        public final List<X509Certificate> chain;    // sertifikanın kendisi HARİÇ
        public final String ref;                       // SHA-256 parmak izi
        public final String alias;
        public final boolean keyEntry;
        public final boolean isCa;
        public final boolean selfSigned;
        public final ManualCertificateChains.Trust trust;
        String suggestedKey;
        final List<Warning> warnings = new ArrayList<>();
        Map<String, Object> alreadyTracked;
        final List<Map<String, Object>> sameSubject = new ArrayList<>();
        final List<Map<String, Object>> networkMonitored = new ArrayList<>();
        final Instant now;

        Entry(X509Certificate cert, List<X509Certificate> chain, String ref, String alias, boolean keyEntry,
              ManualCertificateChains.Trust trust, Instant now) {
            this.cert = cert;
            this.chain = chain;
            this.ref = ref;
            this.alias = alias;
            this.keyEntry = keyEntry;
            this.isCa = ManualCertificateChains.isCa(cert);
            this.selfSigned = ManualCertificateChains.isSelfSigned(cert);
            this.trust = trust;
            this.now = now;
        }

        public String suggestedKey() { return suggestedKey; }
        public List<Warning> warnings() { return warnings; }
        public Map<String, Object> alreadyTracked() { return alreadyTracked; }

        public String cn() {
            String cn = CertificateFacts.extractCn(cert.getSubjectX500Principal().getName());
            return "Unknown".equals(cn) ? null : cn;
        }

        public String issuerName() {
            String dn = cert.getIssuerX500Principal().getName();
            String cn = CertificateFacts.extractCn(dn);
            if (!"Unknown".equals(cn)) return cn;
            String o = CertificateFacts.extractField(dn, "O");
            return "Unknown".equals(o) ? dn : o;
        }

        public long daysRemaining() {
            return CertificateFacts.daysRemaining(cert.getNotAfter().toInstant(), now);
        }

        public String status(int warningDays) {
            if (now.isBefore(cert.getNotBefore().toInstant())) return "not_yet_valid";
            long d = daysRemaining();
            if (d < 0) return "expired";
            if (d <= warningDays) return "warning";
            return "valid";
        }

        Map<String, Object> toJson(int warningDays) {
            List<String> san = CertificateFacts.extractSan(cert);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ref);
            m.put("alias", alias);
            m.put("is_key_entry", keyEntry);
            m.put("is_ca", isCa);
            m.put("self_signed", selfSigned);
            String cn = cn();
            m.put("subject", cn != null ? cn : cert.getSubjectX500Principal().getName());
            m.put("subject_dn", cert.getSubjectX500Principal().getName());
            m.put("cn", cn);
            m.put("issuer", issuerName());
            m.put("issuer_dn", cert.getIssuerX500Principal().getName());
            m.put("serial_number", CertificateFacts.serialHex(cert));
            m.put("not_before", CertificateFacts.iso(cert.getNotBefore().toInstant()));
            m.put("not_after", CertificateFacts.iso(cert.getNotAfter().toInstant()));
            m.put("days_remaining", (int) daysRemaining());
            m.put("status", status(warningDays));
            m.put("san", san);
            m.put("key_alg", cert.getPublicKey().getAlgorithm());
            int ks = CertificateFacts.publicKeySize(cert.getPublicKey());
            m.put("key_size", ks > 0 ? ks : null);
            m.put("signature_algorithm", cert.getSigAlgName());
            m.put("key_usage", CertificateFacts.keyUsageList(cert.getKeyUsage()));
            m.put("ext_key_usage", CertificateFacts.extKeyUsageList(cert));
            m.put("cert_type", CertificateFacts.certType(cert, san));
            List<Map<String, Object>> chainJson = new ArrayList<>();
            for (X509Certificate c : chain) {
                Map<String, Object> cm = new LinkedHashMap<>();
                String ccn = CertificateFacts.extractCn(c.getSubjectX500Principal().getName());
                cm.put("subject", "Unknown".equals(ccn) ? c.getSubjectX500Principal().getName() : ccn);
                String icn = CertificateFacts.extractCn(c.getIssuerX500Principal().getName());
                cm.put("issuer", "Unknown".equals(icn) ? c.getIssuerX500Principal().getName() : icn);
                cm.put("not_after", CertificateFacts.iso(c.getNotAfter().toInstant()));
                cm.put("fingerprint", fingerprintOf(c));
                cm.put("is_ca", ManualCertificateChains.isCa(c));
                cm.put("days_remaining", (int) CertificateFacts.daysRemaining(c.getNotAfter().toInstant(), now));
                chainJson.add(cm);
            }
            m.put("chain", chainJson);
            m.put("chain_complete", trust.chainComplete());
            m.put("trust_status", trust.status());
            Map<String, Object> weak = new LinkedHashMap<>();
            weak.put("signature", weakSignature() ? "WEAK" : "OK");
            weak.put("key_size", weakKey() ? "WEAK" : "OK");
            m.put("weak", weak);
            m.put("suggested_key", suggestedKey);
            m.put("warnings", warnings.stream().map(Warning::toJson).toList());
            Map<String, Object> matches = new LinkedHashMap<>();
            matches.put("already_tracked", alreadyTracked);
            matches.put("same_subject", sameSubject);
            matches.put("network_monitored", networkMonitored);
            m.put("matches", matches);
            return m;
        }

        boolean weakSignature() {
            // Kendinden imzalı kökün imzası doğrulamada kullanılmaz (güven deposundan gelir) — gürültü olmasın.
            if (isCa && selfSigned) return false;
            return CertificateHealthRules.signatureStatus(cert.getSigAlgName()) == CertificateHealthRules.Status.FAIL;
        }

        boolean weakKey() {
            int ks = CertificateFacts.publicKeySize(cert.getPublicKey());
            return CertificateHealthRules.keySizeStatus(cert.getPublicKey().getAlgorithm(), ks > 0 ? ks : null)
                    == CertificateHealthRules.Status.FAIL;
        }

        private String fingerprintOf(X509Certificate c) {
            return ManualCertificateAnalyzer.fingerprint(c);
        }
    }

    /** Analiz sonucu. */
    public static final class Analysis {
        public final CertificateFileParser.Result parsed;
        /** Zincir başları — takip edilebilecek girdiler (dosya sırasıyla). */
        public final List<Entry> entries;
        public final List<Warning> warnings;
        public final String defaultRef;
        /** Dosyadaki TEKİL sertifika sayısı (zincir üyeleri dahil). */
        public final int certificateCount;
        /** Bir başın zincirine katlanan sertifikanın parmak izi → o başın parmak izi. */
        final Map<String, String> folded;
        final int warningDays;

        Analysis(CertificateFileParser.Result parsed, List<Entry> entries, List<Warning> warnings, String defaultRef,
                 int warningDays) {
            this(parsed, entries, warnings, defaultRef, entries.size(), Map.of(), warningDays);
        }

        Analysis(CertificateFileParser.Result parsed, List<Entry> entries, List<Warning> warnings, String defaultRef,
                 int certificateCount, Map<String, String> folded, int warningDays) {
            this.parsed = parsed;
            this.entries = entries;
            this.warnings = warnings;
            this.defaultRef = defaultRef;
            this.certificateCount = certificateCount;
            this.folded = folded;
            this.warningDays = warningDays;
        }

        /** Seçilebilir girdi (zincir başı); katlanan zincir üyesi için null — bkz. {@link #headOf}. */
        public Entry find(String ref) {
            if (ref == null) return null;
            for (Entry e : entries) if (e.ref.equalsIgnoreCase(ref.trim())) return e;
            return null;
        }

        /**
         * {@code ref} bir başın zincirine katlanmış (ara / kök) sertifikaysa o baş; değilse null. Oluşturma / toplu /
         * yenileme bu durumda açık bir alan hatası döner ("zincirin başını seçin").
         */
        public Entry headOf(String ref) {
            if (ref == null) return null;
            String head = folded.get(ref.trim().toUpperCase(Locale.ROOT));
            return head == null ? null : find(head);
        }

        public String format() { return parsed.format(); }
        public String fileName() { return parsed.fileName(); }

        public Map<String, Object> toJson() {
            return toJson(null);
        }

        /**
         * @param preview girdi başına SSL sekmesiyle AYNI biçimli sonuç haritası (çevrim-dışı değerlendirme; arayüz zinciri
         *                ağ sertifikasının zincir görünümüyle çizer) — null ise {@code preview} anahtarı yazılmaz
         */
        public Map<String, Object> toJson(java.util.function.Function<Entry, Map<String, Object>> preview) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", parsed.format());
            m.put("file_name", parsed.fileName());
            m.put("size_bytes", parsed.sizeBytes());
            // Parola artık yalnız tarayıcıda (2026-10-08) — alanlar geriye uyum için sabit false
            m.put("needs_password", false);
            m.put("password_error", false);
            m.put("warnings", warnings.stream().map(Warning::toJson).toList());
            m.put("csr", parsed.csr() == null ? null : parsed.csr().toJson());
            m.put("certificate_count", certificateCount);
            List<Map<String, Object>> list = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                Map<String, Object> ej = e.toJson(warningDays);
                if (preview != null) ej.put("preview", safePreview(preview, e));
                list.add(ej);
            }
            m.put("entries", list);
            m.put("default_ref", defaultRef);
            return m;
        }

        private static Map<String, Object> safePreview(java.util.function.Function<Entry, Map<String, Object>> preview, Entry e) {
            try {
                return preview.apply(e);
            } catch (Exception ex) {
                log.debug("Manuel sertifika önizlemesi kurulamadı: {}", ex.toString());
                return null;
            }
        }
    }

    /** Zincir gruplaması: başlar (dosya sırası) + her tekilin zinciri + katlanan üye → baş eşlemesi. */
    record Grouping(List<String> heads, Map<String, List<X509Certificate>> chains, Map<String, String> folded) { }

    /**
     * Tekilleştirilmiş sertifikalardan zincir başlarını seçer (sınıf açıklamasındaki kural).
     *
     * @param unique parmak izi (büyük harf) → sertifika, dosya sırasıyla
     */
    static Grouping groupChains(LinkedHashMap<String, X509Certificate> unique) {
        List<X509Certificate> pool = new ArrayList<>(unique.values());
        Map<String, List<X509Certificate>> chains = new LinkedHashMap<>();
        Map<String, List<String>> chainFps = new LinkedHashMap<>();
        Set<String> members = new HashSet<>();
        for (Map.Entry<String, X509Certificate> e : unique.entrySet()) {
            List<X509Certificate> chain = ManualCertificateChains.buildChain(e.getValue(), pool);
            chains.put(e.getKey(), chain);
            List<String> fps = new ArrayList<>(chain.size());
            for (X509Certificate c : chain) {
                String fp = fingerprint(c);
                if (fp != null && !fp.equals(e.getKey())) fps.add(fp);
            }
            chainFps.put(e.getKey(), fps);
            members.addAll(fps);
        }
        List<String> heads = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (String fp : unique.keySet()) {
            if (members.contains(fp)) continue;
            heads.add(fp);
            covered.add(fp);
            covered.addAll(chainFps.get(fp));
        }
        // Döngü koruması (çapraz imza): hiçbir başın zincirinde olmayan sertifika kendisi baş olur — kaybolmaz.
        for (String fp : unique.keySet()) {
            if (covered.contains(fp)) continue;
            heads.add(fp);
            covered.add(fp);
            covered.addAll(chainFps.get(fp));
        }
        Set<String> headSet = new HashSet<>(heads);
        Map<String, String> folded = new HashMap<>();
        for (String h : heads) {
            for (String m : chainFps.get(h)) {
                if (!headSet.contains(m)) folded.putIfAbsent(m, h);
            }
        }
        // Sıra: dosya sırası (döngü korumasıyla eklenen baş da kendi yerinde)
        List<String> ordered = new ArrayList<>(heads.size());
        for (String fp : unique.keySet()) if (headSet.contains(fp)) ordered.add(fp);
        return new Grouping(ordered, chains, folded);
    }

    // ── Analiz ───────────────────────────────────────────────────────────────

    /**
     * HAM yüklemeyi (API istemcileri: anahtarsız PEM / DER / PKCS7 / ZIP) ayrıştırır (zaman kutulu) ve analiz eder.
     * Parola parametresi YOK (2026-10-08): anahtar deposu / PKCS#12 / özel anahtar yalnız tanınır ve reddedilir.
     *
     * @throws TimeoutExceededException {@value #PARSE_TIMEOUT_SECONDS} sn aşıldı
     * @throws BusyException            ayrıştırma havuzu dolu
     * @throws PrivateMaterialRejected  yükleme özel anahtar / anahtar deposu taşıyor
     */
    public Analysis analyze(byte[] data, String fileName, boolean pasted) {
        CertificateFileParser.Result parsed = parseTimed(data, fileName, pasted);
        if (parsed.privateMaterial() != null) throw new PrivateMaterialRejected(parsed.privateMaterial());
        return analyzeParsed(parsed);
    }

    /**
     * Tarayıcıda ayıklanmış yükleme ({@link ExtractedUpload#parse} ile doğrulanmış AÇIK sertifikalar) — 2026-10-08. Ayrıştırma
     * yok (sertifikalar doğrulamada çözüldü); analiz ham yolla birebir aynı (zincir gruplama, anahtar girdisi tercihi,
     * eşleşmeler, öneriler).
     */
    public Analysis analyzeExtracted(CertificateFileParser.Result extracted) {
        return analyzeParsed(extracted);
    }

    CertificateFileParser.Result parseTimed(byte[] data, String fileName, boolean pasted) {
        Future<CertificateFileParser.Result> f;
        try {
            f = parsePool.submit(() -> CertificateFileParser.parse(data, fileName, pasted));
        } catch (RejectedExecutionException e) {
            throw new BusyException();
        }
        try {
            return f.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new TimeoutExceededException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new TimeoutExceededException();
        } catch (ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            log.warn("Sertifika dosyası ayrıştırılamadı: {}", c.getClass().getSimpleName());
            return CertificateFileParser.parse(new byte[0], fileName, pasted);
        }
    }

    Analysis analyzeParsed(CertificateFileParser.Result parsed) {
        Instant now = Instant.now();
        List<Warning> fileWarnings = new ArrayList<>(parsed.warnings());

        // 1) Parmak izine göre tekilleştir; anahtar girdisi bilgisi korunur.
        LinkedHashMap<String, ParsedCert> unique = new LinkedHashMap<>();
        int duplicates = 0;
        for (ParsedCert pc : parsed.certs()) {
            String fp = fingerprint(pc.cert());
            if (fp == null) continue;
            ParsedCert prev = unique.get(fp);
            if (prev == null) {
                unique.put(fp, pc);
            } else {
                duplicates++;
                if (pc.keyEntry() && !prev.keyEntry()) unique.put(fp, pc);
                else if (prev.alias() == null && pc.alias() != null)
                    unique.put(fp, new ParsedCert(prev.cert(), pc.alias(), prev.keyEntry(), prev.source()));
            }
        }
        if (duplicates > 0) fileWarnings.add(new Warning("DUPLICATE_IN_FILE", CertificateFileParser.SEV_INFO,
                Map.of("count", duplicates)));

        LinkedHashMap<String, X509Certificate> certs = new LinkedHashMap<>();
        for (Map.Entry<String, ParsedCert> e : unique.entrySet()) certs.put(e.getKey(), e.getValue().cert());

        // 2) Girdiler = zincir başları (yaprak + ara + kök → tek girdi); zincir + güven yalnız başlar için.
        Grouping g = groupChains(certs);
        List<Entry> entries = new ArrayList<>();
        for (String fp : g.heads()) {
            ParsedCert pc = unique.get(fp);
            List<X509Certificate> chain = g.chains().get(fp);
            ManualCertificateChains.Trust trust = ManualCertificateChains.trust(trustEvaluator, pc.cert(), chain);
            entries.add(new Entry(pc.cert(), chain, fp, pc.alias(), pc.keyEntry(), trust, now));
        }

        // Birden çok bağımsız UÇ sertifika (CA olmayan zincir başı)
        long leaves = entries.stream().filter(en -> !en.isCa).count();
        if (leaves > 1) fileWarnings.add(new Warning("MULTIPLE_LEAVES", CertificateFileParser.SEV_INFO,
                Map.of("count", (int) leaves)));

        // 3) Girdi uyarıları.
        for (Entry en : entries) addEntryWarnings(en);

        // 4) Eşleşmeler + önerilen takip adı (TOPLU sorgular).
        if (!entries.isEmpty()) {
            applyMatches(entries);
            applySuggestions(entries);
        }

        return new Analysis(parsed, entries, fileWarnings, defaultRef(entries), certs.size(), g.folded(), warningDays);
    }

    private void addEntryWarnings(Entry en) {
        X509Certificate c = en.cert;
        long days = en.daysRemaining();
        if (en.now.isBefore(c.getNotBefore().toInstant())) {
            en.warnings.add(new Warning("NOT_YET_VALID", CertificateFileParser.SEV_WARN,
                    Map.of("date", CertificateFacts.iso(c.getNotBefore().toInstant()))));
        }
        if (days < 0) {
            en.warnings.add(new Warning("EXPIRED", CertificateFileParser.SEV_ERROR, Map.of("days", (int) Math.abs(days))));
        } else if (days <= warningDays) {
            en.warnings.add(new Warning("EXPIRES_SOON", CertificateFileParser.SEV_WARN, Map.of("days", (int) days)));
        }
        if (en.selfSigned) {
            en.warnings.add(new Warning("SELF_SIGNED", en.isCa ? CertificateFileParser.SEV_INFO : CertificateFileParser.SEV_WARN,
                    Map.of()));
        }
        if (!en.trust.chainComplete() && !en.selfSigned) {
            en.warnings.add(new Warning("CHAIN_INCOMPLETE", CertificateFileParser.SEV_INFO,
                    Map.of("missing_issuer", en.trust.missingIssuer() == null ? "" : en.trust.missingIssuer())));
        }
        for (X509Certificate cc : en.chain) {
            if (CertificateFacts.daysRemaining(cc.getNotAfter().toInstant(), en.now) < 0) {
                String cn = CertificateFacts.extractCn(cc.getSubjectX500Principal().getName());
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("subject", "Unknown".equals(cn) ? cc.getSubjectX500Principal().getName() : cn);
                p.put("date", CertificateFacts.iso(cc.getNotAfter().toInstant()));
                en.warnings.add(new Warning("CHAIN_EXPIRED_INTERMEDIATE", CertificateFileParser.SEV_WARN, p));
            }
        }
        if (en.weakSignature()) {
            en.warnings.add(new Warning("WEAK_SIGNATURE", CertificateFileParser.SEV_WARN,
                    Map.of("algorithm", c.getSigAlgName())));
        }
        if (en.weakKey()) {
            int ks = CertificateFacts.publicKeySize(c.getPublicKey());
            en.warnings.add(new Warning("WEAK_KEY", CertificateFileParser.SEV_WARN,
                    Map.of("algorithm", c.getPublicKey().getAlgorithm(), "size", ks)));
        }
        if (en.isCa) en.warnings.add(new Warning("CA_CERTIFICATE", CertificateFileParser.SEV_INFO, Map.of()));
    }

    /** already_tracked / same_subject / network_monitored — en çok beş toplu sorgu. */
    private void applyMatches(List<Entry> entries) {
        Set<String> fps = new LinkedHashSet<>();
        Set<String> dns = new LinkedHashSet<>();
        for (Entry e : entries) {
            fps.add(e.ref);
            dns.add(e.cert.getSubjectX500Principal().getName());
        }
        List<ManualCertificateVersion> byFp = safe(() -> versionRepo.findByCurrentTrueAndFingerprintIn(fps));
        List<ManualCertificateVersion> bySubject = safe(() -> versionRepo.findByCurrentTrueAndSubjectDnIn(dns));
        Set<Long> invIds = new HashSet<>();
        for (ManualCertificateVersion v : byFp) invIds.add(v.getInventoryId());
        for (ManualCertificateVersion v : bySubject) invIds.add(v.getInventoryId());
        Map<Long, CertificateInventory> manualRows = new HashMap<>();
        if (!invIds.isEmpty()) {
            for (CertificateInventory inv : safe(() -> inventoryRepo.findAllById(invIds))) {
                if (inv.getId() != null && inv.isManual() && inv.getDeletedAt() == null) manualRows.put(inv.getId(), inv);
            }
        }
        Map<String, List<String>> networkByFp = new HashMap<>();
        List<LatestCheck> served = safe(() -> latestCheckRepo.findByFingerprintIn(fps));
        if (!served.isEmpty()) {
            Set<String> domains = new LinkedHashSet<>();
            for (LatestCheck lc : served) if (lc.getDomain() != null) domains.add(lc.getDomain());
            Map<String, CertificateInventory> invByDomain = new HashMap<>();
            for (CertificateInventory inv : safe(() -> inventoryRepo.findByDomainIn(domains))) {
                if (inv.getDomain() != null) invByDomain.put(inv.getDomain(), inv);
            }
            for (LatestCheck lc : served) {
                CertificateInventory inv = invByDomain.get(lc.getDomain());
                if (inv == null || inv.isManual() || inv.getDeletedAt() != null || lc.getFingerprint() == null) continue;
                networkByFp.computeIfAbsent(lc.getFingerprint().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(lc.getDomain());
            }
        }
        for (Entry e : entries) {
            for (ManualCertificateVersion v : byFp) {
                CertificateInventory inv = manualRows.get(v.getInventoryId());
                if (inv != null && e.ref.equalsIgnoreCase(v.getFingerprint())) {
                    Map<String, Object> at = new LinkedHashMap<>();
                    at.put("inventory_id", inv.getId());
                    at.put("domain", inv.getDomain());
                    e.alreadyTracked = at;
                    e.warnings.add(new Warning("ALREADY_TRACKED", CertificateFileParser.SEV_WARN,
                            Map.of("domain", inv.getDomain())));
                    break;
                }
            }
            String dn = e.cert.getSubjectX500Principal().getName();
            Set<Long> seen = new HashSet<>();
            for (ManualCertificateVersion v : bySubject) {
                CertificateInventory inv = manualRows.get(v.getInventoryId());
                if (inv == null || !dn.equals(v.getSubjectDn()) || e.ref.equalsIgnoreCase(v.getFingerprint())
                        || !seen.add(inv.getId())) continue;
                Map<String, Object> ss = new LinkedHashMap<>();
                ss.put("inventory_id", inv.getId());
                ss.put("domain", inv.getDomain());
                ss.put("not_after", v.getNotAfter());
                e.sameSubject.add(ss);
                e.warnings.add(new Warning("SAME_SUBJECT_TRACKED", CertificateFileParser.SEV_INFO,
                        Map.of("domain", inv.getDomain())));
            }
            for (String d : networkByFp.getOrDefault(e.ref.toUpperCase(Locale.ROOT), List.of())) {
                e.networkMonitored.add(Map.<String, Object>of("domain", d));
                e.warnings.add(new Warning("NETWORK_MONITORED", CertificateFileParser.SEV_INFO, Map.of("domain", d)));
            }
        }
    }

    /** Önerilen takip adı: CN/ilk SAN; envanterde (canlı kayıt — silinen kayıt adı tutmaz, 2026-10-07) varsa {@code -manuel}, {@code -manuel-2} … */
    private void applySuggestions(List<Entry> entries) {
        Map<Entry, List<String>> cands = new LinkedHashMap<>();
        Set<String> all = new LinkedHashSet<>();
        for (Entry e : entries) {
            String base = ManualCertificateKeys.baseSuggestion(e.cn(), CertificateFacts.extractSan(e.cert));
            List<String> c = ManualCertificateKeys.candidates(base, 9);
            cands.put(e, c);
            all.addAll(c);
        }
        Set<String> taken = new HashSet<>(safe(() -> inventoryRepo.findExistingDomainsLower(all)));
        Set<String> used = new HashSet<>();
        for (Map.Entry<Entry, List<String>> ce : cands.entrySet()) {
            String pick = null;
            for (String c : ce.getValue()) {
                if (!taken.contains(c) && !used.contains(c)) { pick = c; break; }
            }
            if (pick == null) {
                String base = ce.getValue().get(0);
                for (int i = 10; i < 1000 && pick == null; i++) {
                    String c = base + ManualCertificateKeys.SUFFIX + "-" + i;
                    if (!used.contains(c) && inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(c)
                            .filter(row -> row.getDeletedAt() == null).isEmpty()) pick = c;
                }
            }
            if (pick != null) used.add(pick);
            ce.getKey().suggestedKey = pick;
        }
    }

    /** Önerilen girdi: tek zincir başı varsa o; anahtar girdisinin yaprağı; yoksa (tek) uç sertifika; yoksa tek sertifika. */
    static String defaultRef(List<Entry> entries) {
        if (entries.size() == 1) return entries.get(0).ref;
        for (Entry e : entries) if (e.keyEntry && !e.isCa) return e.ref;
        for (Entry e : entries) if (e.keyEntry) return e.ref;
        List<Entry> leaves = entries.stream().filter(e -> !e.isCa).toList();
        if (!leaves.isEmpty()) {
            // Birden çok uç sertifika: zinciri en uzun olan (ara sertifikaları dosyada bulunan) önerilir.
            Entry best = leaves.get(0);
            for (Entry e : leaves) if (e.chain.size() > best.chain.size()) best = e;
            return best.ref;
        }
        return entries.size() == 1 ? entries.get(0).ref : null;
    }

    public static String fingerprint(X509Certificate c) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> List<T> safe(java.util.function.Supplier<? extends Collection<T>> q) {
        try {
            Collection<T> r = q.get();
            return r == null ? List.of() : new ArrayList<>(r);
        } catch (Exception e) {
            log.debug("Manuel sertifika eşleşme sorgusu atlandı: {}", e.toString());
            return List.of();
        }
    }

    int warningDays() { return warningDays; }
}
