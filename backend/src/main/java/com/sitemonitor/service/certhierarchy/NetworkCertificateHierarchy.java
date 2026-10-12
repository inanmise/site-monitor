package com.sitemonitor.service.certhierarchy;

import com.sitemonitor.service.CertificateFacts;
import com.sitemonitor.service.manualcert.ManualCertificateChains;
import com.sitemonitor.service.manualcert.ManualCertificateHierarchy;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ağdan kontrol edilen sertifikanın TARAYICI GİBİ hiyerarşisi (2026-10-12, kullanıcı isteği: "otomatik çekilen
 * sertifikalarda da Root / Intermediate / Leaf net görünsün, her birinin bitiş tarihi ayrı ayrı izlensin"). Elle yüklenen
 * sertifikanın görünümüyle ({@link ManualCertificateHierarchy}) AYNI düğüm biçimi — istemci aynı ağacı çizer.
 *
 * <p><b>Yol (tarayıcı / PKIX gibi)</b>: yapraktan yukarı, her adımda sırayla:
 * <ol>
 *   <li>güven deposunda bu sertifikayı imzalayan KENDİNDEN İMZALI kök varsa yol orada BİTER (çapraz imzalı köklerde
 *       tarayıcının gösterdiği kök; sunucunun gönderdiği çapraz imza "fazladan" olur). Sunucu aynı kökü göndermişse
 *       sunucunun kopyası kullanılır;</li>
 *   <li>yoksa sunucunun gönderdikleri arasında veren (ad + İMZA; sıra önemsiz);</li>
 *   <li>yoksa güven deposundaki ARA sertifika (kurumsal paket ara CA da taşıyabilir) — yürüyüş oradan sürer.</li>
 * </ol>
 * Birden çok aday imzalıyorsa şu an GEÇERLİ olan, sonra en geç biten seçilir. Döngü ve derinlik korumalı
 * ({@value #MAX_DEPTH}); girdi en çok {@value #MAX_PRESENTED} sertifika.
 *
 * <p><b>Kök</b>: {@code root_source} yalnız tepe KENDİNDEN İMZALI ise dolar ({@code trust_store | server | self_signed});
 * değilse {@code null} + en üst düğümde {@code issuer_missing} — {@code missing_kind}: {@code intermediate} (sunucu yalnız
 * yaprağı gönderdi, ara eksik), {@code anchor} (zincir depodaki bir ARA sertifikaya bağlanıyor, kök depoda yok),
 * {@code root} (kök bulunamadı), {@code wrong_intermediate} (sunucu verenle AYNI ADDA bir sertifika gönderdi ama o bunu
 * imzalamıyor ya da CA değil — yenilenmiş / farklı anahtarlı eski ara sertifika). {@code root_trusted}: tepe kök güven
 * deposunda (aynı sertifika ya da aynı ad + anahtar) — sunucunun gönderdiği ama depoda olmayan kök "güvenilir" SAYILMAZ.
 *
 * <p><b>Veren olabilir mi</b> ({@link #canIssue}, 2026-10-12): ad + imza YETMEZ — veren CA olmalı (basicConstraints;
 * uzantısız eski v1 kök yalnız kendinden imzalıysa) ve keyUsage varsa keyCertSign taşımalı. Tarayıcı / PKIX son kullanıcı
 * sertifikasının imzaladığı zinciri reddeder; ağaç onu "güven deposundan kök" diye yeşil göstermemeli.
 *
 * <p>Saf fonksiyon: ağ yok, yazma yok.
 */
public final class NetworkCertificateHierarchy {

    private NetworkCertificateHierarchy() { }

    /** Sunucunun gönderdiği listeden en çok bu kadarı işlenir (gerçek zincirler 2–5; tavan kötü niyetli uca karşı). */
    static final int MAX_PRESENTED = 20;
    /** Yola girmeyen sertifikalardan en çok bu kadarı listelenir. */
    static final int MAX_EXTRAS = 10;
    /** Yolun en çok uzunluğu (yaprak hariç adım). */
    static final int MAX_DEPTH = 10;

    public static final String SOURCE_SERVER = "server";
    public static final String SOURCE_TRUST_STORE = "trust_store";
    public static final String STORE_SYSTEM = "system";
    public static final String STORE_CORPORATE = "corporate";
    /** Kök kaynağı: güven deposundan bulundu · sunucu gönderdi · yaprak kendinden imzalı · (null) kök yok. */
    public static final String ROOT_SERVER = "server";
    public static final String ROOT_TRUST_STORE = "trust_store";
    public static final String ROOT_SELF_SIGNED = "self_signed";
    /** Kök yoksa ne eksik. */
    public static final String MISSING_INTERMEDIATE = "intermediate";
    public static final String MISSING_ANCHOR = "anchor";
    public static final String MISSING_ROOT = "root";
    public static final String MISSING_WRONG_INTERMEDIATE = "wrong_intermediate";

    private record Found(X509Certificate cert, String store) { }

    /**
     * @param presented        sunucunun sunduğu zincir (yaprak ilk); null / boş → boş görünüm
     * @param systemAnchors    JVM güven deposu
     * @param corporateAnchors admin kurumsal CA paketi
     */
    public static Map<String, Object> build(X509Certificate[] presented, Collection<X509Certificate> systemAnchors,
                                            Collection<X509Certificate> corporateAnchors, Instant now) {
        List<X509Certificate> sent = new ArrayList<>();
        if (presented != null) {
            for (X509Certificate c : presented) {
                if (c == null) continue;
                if (sent.size() >= MAX_PRESENTED) break;
                sent.add(c);
            }
        }
        Collection<X509Certificate> system = systemAnchors == null ? List.of() : systemAnchors;
        Collection<X509Certificate> corporate = corporateAnchors == null ? List.of() : corporateAnchors;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("presented_count", sent.size());
        if (sent.isEmpty()) {
            out.put("root_source", null);
            out.put("trust_store", null);
            out.put("root_trusted", false);
            out.put("missing_kind", null);
            out.put("order_ok", true);
            out.put("extra_certificates", List.of());
            out.putAll(ManualCertificateHierarchy.view(List.of(), now));
            return out;
        }

        X509Certificate leaf = sent.get(0);
        List<X509Certificate> pool = sent.subList(1, sent.size());
        List<X509Certificate> chain = new ArrayList<>();
        List<String> stores = new ArrayList<>();          // zincir konumu başına: null = sunucudan, değilse depo adı
        chain.add(leaf);
        stores.add(null);
        boolean rootTrusted = false;
        X509Certificate cur = leaf;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (ManualCertificateChains.isSelfSigned(cur)) {
                rootTrusted = rootTrusted || isAnchor(cur, system) || isAnchor(cur, corporate);
                break;
            }
            // 1) kendinden imzalı güven kökü → yol burada biter (tarayıcı gibi)
            Found root = bestIssuer(cur, system, true, now, STORE_SYSTEM);
            if (root == null) root = bestIssuer(cur, corporate, true, now, STORE_CORPORATE);
            if (root != null) {
                X509Certificate sentCopy = sameIn(pool, root.cert());
                chain.add(sentCopy != null ? sentCopy : root.cert());
                stores.add(sentCopy != null ? null : root.store());
                rootTrusted = true;
                break;
            }
            // 2) sunucunun gönderdikleri arasında veren
            X509Certificate next = bestIssuerAmong(cur, pool, chain, now);
            String store = null;
            // 3) güven deposundaki ara sertifika
            if (next == null) {
                Found mid = bestIssuer(cur, system, false, now, STORE_SYSTEM);
                if (mid == null) mid = bestIssuer(cur, corporate, false, now, STORE_CORPORATE);
                if (mid != null && !containsSame(chain, mid.cert())) {
                    next = mid.cert();
                    store = mid.store();
                }
            }
            if (next == null) break;
            chain.add(next);
            stores.add(store);
            cur = next;
        }

        int last = chain.size() - 1;
        X509Certificate top = chain.get(last);
        boolean topSelfSigned = ManualCertificateChains.isSelfSigned(top);
        String rootSource = null;
        String rootStore = null;
        String missingKind = null;
        if (topSelfSigned) {
            rootStore = stores.get(last);
            rootSource = rootStore != null ? ROOT_TRUST_STORE : (last == 0 ? ROOT_SELF_SIGNED : ROOT_SERVER);
        } else if (stores.get(last) != null) {
            missingKind = MISSING_ANCHOR;
        } else if (sentSameNameAsIssuer(top, sent, chain)) {
            missingKind = MISSING_WRONG_INTERMEDIATE;
        } else {
            // Yalnız sunucu sertifikası (CA bayraklı olsa da ağaçta YAPRAK) → eksik olan veren ara sertifika
            missingKind = last > 0 && ManualCertificateChains.isCa(top) ? MISSING_ROOT : MISSING_INTERMEDIATE;
        }

        // Bir bağ yalnız ADLA kabul edildiyse (imza algoritması bu JVM'de doğrulanamıyor) zincir gösterilir ama kök
        // "güvenilir" SAYILMAZ (inceleme 6): bilinmeyen yeşil olmasın — arayüz kökü "bilinmiyor" gösterir.
        boolean unverified = hasUnverifiedLink(chain);

        Map<String, Object> view = ManualCertificateHierarchy.view(chain, now);
        annotateSources(view, stores);
        leafRole(view);

        out.put("root_source", rootSource);
        out.put("trust_store", rootStore);
        out.put("root_trusted", topSelfSigned && rootTrusted && !unverified);
        out.put("signature_unverified", unverified);
        out.put("missing_kind", missingKind);
        out.put("order_ok", orderOk(distinctPool(leaf, pool), serverPart(chain, stores)));
        out.put("extra_certificates", extras(sent, chain, now));
        out.putAll(view);
        return out;
    }

    /**
     * {@code cert}'i imzalayan en iyi aday (ad eşit + imza doğrulanıyor): şu an geçerli olan önce, sonra en geç biten.
     * {@code selfSignedOnly} → yalnız kendinden imzalı adaylar.
     */
    private static Found bestIssuer(X509Certificate cert, Collection<X509Certificate> anchors, boolean selfSignedOnly,
                                    Instant now, String store) {
        X509Certificate best = null;
        for (X509Certificate cand : anchors) {
            if (cand == null || !signs(cand, cert)) continue;
            if (selfSignedOnly && !ManualCertificateChains.isSelfSigned(cand)) continue;
            // Depodaki KENDİNDEN İMZALI kök bir güven çapasıdır: PKIX çapanın uzantılarına bakmaz (inceleme 5) — uzantısız
            // ya da keyCertSign'sız kurumsal kök bulunmamazlık etmesin. Depodaki ARA sertifika (yol sürer) CA olmalı.
            if (!selfSignedOnly && !canIssue(cand)) continue;
            if (best == null || better(cand, best, now)) best = cand;
        }
        return best == null ? null : new Found(best, store);
    }

    /** Sunucunun gönderdikleri arasında (zincirde olmayan) en iyi veren. */
    private static X509Certificate bestIssuerAmong(X509Certificate cert, List<X509Certificate> pool,
                                                   List<X509Certificate> chain, Instant now) {
        X509Certificate best = null;
        for (X509Certificate cand : pool) {
            if (containsSame(chain, cand) || !canIssue(cand) || !signs(cand, cert)) continue;
            if (best == null || better(cand, best, now)) best = cand;
        }
        return best;
    }

    /**
     * Sertifika VEREN olabilir mi: CA (basicConstraints) — uzantısız eski v1 sertifika yalnız kendinden imzalı kökse — ve
     * keyUsage varsa keyCertSign. Son kullanıcı sertifikası (ya da CA olmayan "ara") zincire veren olarak giremez.
     */
    static boolean canIssue(X509Certificate c) {
        boolean ca = c.getBasicConstraints() >= 0
                || (c.getVersion() < 3 && ManualCertificateChains.isSelfSigned(c));
        if (!ca) return false;
        boolean[] ku = c.getKeyUsage();
        return ku == null || (ku.length > 5 && ku[5]);
    }

    /**
     * Sunucu, yolun tepesindeki sertifikanın VERENİYLE AYNI ADDA bir sertifika gönderdi mi (yola giremeyen: imzalamıyor ya
     * da CA değil) — "ara sertifika eksik" değil "yanlış ara sertifika" tanısı için.
     */
    private static boolean sentSameNameAsIssuer(X509Certificate top, List<X509Certificate> sent, List<X509Certificate> chain) {
        for (X509Certificate c : sent) {
            if (containsSame(chain, c)) continue;
            if (c.getSubjectX500Principal().equals(top.getIssuerX500Principal())) return true;
        }
        return false;
    }

    /**
     * {@code issuer}, {@code cert}'i imzalamış mı (yol kurmak için): konu adı = veren adı VE imza bu anahtarla doğrulanıyor.
     * İmza algoritması bu JVM'de doğrulanamıyorsa (inceleme 5) ad eşleşmesi yeter — "imzalamıyor" denirse doğru ara
     * sertifika "yanlış ara sertifika" görünürdü ({@code ManualCertificateChains.isSelfSigned} ile aynı kural). Böyle bir
     * bağ GÜVEN kararına giremez ({@link #signatureCheck}, inceleme 6).
     */
    static boolean signs(X509Certificate issuer, X509Certificate cert) {
        Boolean r = signatureCheck(issuer, cert);
        return r == null || r;
    }

    /** Yaprak → kök yolunda imzası bu JVM'de doğrulanamayan (yalnız adla kabul edilen) bir bağ var mı. */
    static boolean hasUnverifiedLink(List<X509Certificate> chain) {
        for (int i = 0; i + 1 < chain.size(); i++) {
            if (signatureCheck(chain.get(i + 1), chain.get(i)) == null) return true;
        }
        return false;
    }

    /** Üç durumlu imza denetimi: TRUE doğrulandı, FALSE imzalamıyor / ad farklı, null bu JVM'de doğrulanamıyor. */
    static Boolean signatureCheck(X509Certificate issuer, X509Certificate cert) {
        if (!issuer.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) return Boolean.FALSE;
        try {
            cert.verify(issuer.getPublicKey());
            return Boolean.TRUE;
        } catch (java.security.NoSuchAlgorithmException | java.security.NoSuchProviderException unsupported) {
            return null;
        } catch (Exception e) {
            return Boolean.FALSE;
        }
    }

    private static boolean better(X509Certificate a, X509Certificate b, Instant now) {
        boolean av = validAt(a, now);
        boolean bv = validAt(b, now);
        if (av != bv) return av;
        return a.getNotAfter().after(b.getNotAfter());
    }

    private static boolean validAt(X509Certificate c, Instant now) {
        return !now.isBefore(c.getNotBefore().toInstant()) && !now.isAfter(c.getNotAfter().toInstant());
    }

    /** Güven deposunda mı: aynı sertifika ya da aynı ad + aynı açık anahtar (yeniden düzenlenmiş kök kopyası). */
    static boolean isAnchor(X509Certificate c, Collection<X509Certificate> anchors) {
        for (X509Certificate a : anchors) {
            if (a == null) continue;
            if (a.equals(c)) return true;
            if (a.getSubjectX500Principal().equals(c.getSubjectX500Principal())
                    && Arrays.equals(a.getPublicKey().getEncoded(), c.getPublicKey().getEncoded())) return true;
        }
        return false;
    }

    private static X509Certificate sameIn(List<X509Certificate> list, X509Certificate c) {
        for (X509Certificate x : list) if (x.equals(c)) return x;
        return null;
    }

    private static boolean containsSame(List<X509Certificate> list, X509Certificate c) {
        return sameIn(list, c) != null;
    }

    /**
     * Sunucunun SUNDUĞU sertifika her zaman yapraktır (2026-10-12): {@link ManualCertificateHierarchy#role} truststore
     * yüklemeleri için CA başını "ara" sayar; ağdaki sunucu sertifikası CA bayrağı taşısa da (bazı cihazlar) ağaçta yaprak
     * görünür — aksi hâlde ağaçta yaprak hiç olmazdı.
     */
    @SuppressWarnings("unchecked")
    private static void leafRole(Map<String, Object> view) {
        Object nodes = view.get("nodes");
        if (!(nodes instanceof List<?> list)) return;
        for (Object o : list) {
            if (o instanceof Map<?, ?> raw && Integer.valueOf(0).equals(raw.get("position"))
                    && ManualCertificateHierarchy.ROLE_INTERMEDIATE.equals(raw.get("role"))) {
                ((Map<String, Object>) raw).put("role", ManualCertificateHierarchy.ROLE_LEAF);
            }
        }
    }

    /**
     * Sıra denetimi için gönderilenler: yaprağın tekrarı ve aynı sertifikanın ikinci kopyası düşer (ör. Apache'de
     * {@code cert.pem} + {@code fullchain.pem} → [L, L, I]). Tekrar "yanlış sıra" DEĞİLDİR — kullanıcıya görünmeyen bir
     * nedenle uyarı çıkmasın.
     */
    private static List<X509Certificate> distinctPool(X509Certificate leaf, List<X509Certificate> pool) {
        List<X509Certificate> out = new ArrayList<>();
        for (X509Certificate c : pool) {
            if (c.equals(leaf) || containsSame(out, c)) continue;
            out.add(c);
        }
        return out;
    }

    /** Yolun sunucudan gelen kısmı (yaprak hariç, sırayla). */
    private static List<X509Certificate> serverPart(List<X509Certificate> chain, List<String> stores) {
        List<X509Certificate> out = new ArrayList<>();
        for (int i = 1; i < chain.size(); i++) if (stores.get(i) == null) out.add(chain.get(i));
        return out;
    }

    /**
     * Düğümlere kaynak ekler: {@code position} (saklanan sıradaki konum; 0 = yaprak) depodan eklendiyse
     * {@code trust_store} + depo adı, değilse {@code server}.
     */
    @SuppressWarnings("unchecked")
    private static void annotateSources(Map<String, Object> view, List<String> stores) {
        Object nodes = view.get("nodes");
        if (!(nodes instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> node = (Map<String, Object>) raw;
            String store = node.get("position") instanceof Integer p && p >= 0 && p < stores.size() ? stores.get(p) : null;
            node.put("source", store != null ? SOURCE_TRUST_STORE : SOURCE_SERVER);
            node.put("trust_store", store);
        }
    }

    /** Sunucu zinciri doğru sırayla mı göndermiş: yaprağın ardından yolun sunucu sertifikaları aynı sırayla mı geliyor. */
    static boolean orderOk(List<X509Certificate> pool, List<X509Certificate> serverPath) {
        if (serverPath.size() > pool.size()) return false;
        for (int i = 0; i < serverPath.size(); i++) if (!Objects.equals(pool.get(i), serverPath.get(i))) return false;
        return true;
    }

    /** Sunucunun gönderip yola girmeyen sertifikaları (en çok {@value #MAX_EXTRAS}, tekrarsız). */
    private static List<Map<String, Object>> extras(List<X509Certificate> sent, List<X509Certificate> chain, Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<X509Certificate> listed = new ArrayList<>();
        for (X509Certificate c : sent) {
            if (containsSame(chain, c) || containsSame(listed, c)) continue;   // aynı sertifika iki kez gönderildiyse bir kez
            if (out.size() >= MAX_EXTRAS) break;
            listed.add(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject_dn", c.getSubjectX500Principal().getName());
            m.put("issuer_dn", c.getIssuerX500Principal().getName());
            m.put("not_after", CertificateFacts.iso(c.getNotAfter().toInstant()));
            m.put("days_remaining", CertificateFacts.daysRemaining(c.getNotAfter().toInstant(), now));
            m.put("expired", now.isAfter(c.getNotAfter().toInstant()));
            out.add(m);
        }
        return out;
    }
}
