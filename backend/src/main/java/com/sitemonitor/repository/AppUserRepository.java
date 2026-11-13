package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Kullanıcı dizini satırları (2026-10-09, performans): {@code /api/users/directory} her girişte (ve komut paletinde)
     * çağrılıyor; {@code findAll()} her kullanıcının AD fotoğrafını (base64 TEXT) ve takım koleksiyonunu yüklüyordu, uç
     * yalnız id / kullanıcı adı / görünen ad / e-posta döndürüyor. Sıra: id, username, displayName, firstName, lastName, email,
     * active (2026-10-12: seçiciler pasif hesabı süzebilsin — ad haritası pasifleri de içermeye devam eder).
     */
    @org.springframework.data.jpa.repository.Query("SELECT u.id, u.username, u.displayName, u.firstName, u.lastName, u.email, u.active"
            + " FROM AppUser u WHERE u.username IS NOT NULL")
    List<Object[]> findDirectoryRows();
    // Username eşleştirmesi CASE-INSENSITIVE (DB UPPER): canonical saklama BÜYÜK harf olsa da yazılan/eski
    // satırların case'i ne olursa olsun aynı kullanıcıya çözülür (Melih "N12345"/"n12345" tek satır) +
    // LDAP re-provision eski satırı bulur (dup yaratmaz). DB UPPER iki tarafta → collation-tutarlı.
    @Query("SELECT u FROM AppUser u WHERE UPPER(u.username) = UPPER(:username)")
    Optional<AppUser> findByUsername(@Param("username") String username);
    @Query("SELECT u FROM AppUser u WHERE UPPER(u.username) = UPPER(:username) AND u.active = true")
    Optional<AppUser> findByUsernameAndActiveTrue(@Param("username") String username);

    /** SICAK YOL (her /api/** isteği): tek-oturum supersede kontrolü için YALNIZ activeSessionId
     *  kolonunu çeker — tam AppUser entity'sini hidrate etmez ve EAGER teamIds (app_user_teams)
     *  join'ini tetiklemez. Eski findByUsername iki SELECT'e mal oluyordu; bu tek hafif indexli okuma. */
    @Query("SELECT u.activeSessionId FROM AppUser u WHERE UPPER(u.username) = UPPER(:username)")
    Optional<String> findActiveSessionIdByUsername(@Param("username") String username);

    /** SICAK YOL (her /api/** isteği, 2026-10-02 pasif hesap kapısı): YALNIZ {@code active} kolonu — entity / EAGER
     *  teamIds hidrate edilmez ({@link #findActiveSessionIdByUsername} ile aynı gerekçe). Kullanıcı yoksa boş. */
    @Query("SELECT u.active FROM AppUser u WHERE UPPER(u.username) = UPPER(:username)")
    Optional<Boolean> findActiveFlagByUsername(@Param("username") String username);

    /** Pasif kullanıcıların kimliği + e-postası (bildirim süzgeci, 2026-10-02). Satır: {@code [Long id, String email]}.
     *  Projeksiyon BİLİNÇLİ: foto/teamIds taşınmaz; süzgeç bunu dakikada bir okur. */
    @Query("SELECT u.id, u.email FROM AppUser u WHERE u.active = false")
    List<Object[]> findInactiveIdsAndEmails();

    /** Çevrimiçi kullanıcı sayısı, BİRİNCİL takıma göre (sol üst çevrimiçi göstergesi, 2026-10-02). Satır:
     *  {@code [Long teamId (null = takımsız), Long count]}. "Çevrimiçi" = {@link com.sitemonitor.service.UserService#hasLiveSession}
     *  ile aynı ölçüt: aktif hesap, kayıtlı oturum (TERMINATED nöbetçisi değil) ve eşikten taze lastSeenAt (ISO metin
     *  karşılaştırması, hasLiveSession ile aynı). Tek gruplu sorgu — kullanıcı satırı taşınmaz. */
    @Query("SELECT u.teamId, COUNT(u) FROM AppUser u WHERE u.active = true AND u.activeSessionId IS NOT NULL"
         + " AND u.activeSessionId NOT LIKE 'TERMINATED:%' AND u.lastSeenAt >= :threshold GROUP BY u.teamId")
    List<Object[]> countOnlineByPrimaryTeam(@Param("threshold") String threshold);

    /**
     * Kodla giriş kişi bilgisi KAPSAMI (2026-10-03, Giriş Yöntemleri ayar sayfası) — TEK satır:
     * {@code [Long aktif kullanıcı, Long kayıtlı telefonu olan, Long kayıtlı e-postası olan]}. Tek toplu sorgu: satır /
     * kişi bilgisi taşınmaz. Telefon "kayıtlı" = boş değil ve kırpılmış hâli en az 10 karakter (eşleştirici en az 10
     * hane ister — dahili / kısa numara sayılmaz); e-posta = '@' ilk karakterden sonra. Yaklaşık bir ipucudur.
     */
    @Query("SELECT COUNT(u), "
         + "COALESCE(SUM(CASE WHEN u.phone IS NOT NULL AND LENGTH(TRIM(u.phone)) >= 10 THEN 1 ELSE 0 END), 0), "
         + "COALESCE(SUM(CASE WHEN u.email IS NOT NULL AND LOCATE('@', TRIM(u.email)) > 1 THEN 1 ELSE 0 END), 0) "
         + "FROM AppUser u WHERE u.active = true")
    List<Object[]> contactCoverage();

    /**
     * Giriş istatistikleri dizini (2026-10-03, Giriş Yöntemleri → İstatistikler): {@code [username, displayName, teamId,
     * authSource, active]} — TEK toplu izdüşüm (varlık / ilişki / parola özeti YÜKLENMEZ); kanal sınıflandırması (hesap
     * kaynağı) ve kullanıcı tablosu bu satırlardan kurulur.
     */
    @Query("SELECT u.username, u.displayName, u.teamId, u.authSource, u.active FROM AppUser u")
    List<Object[]> findLoginStatDirectory();

    /** Verilen (küçük harf, kırpılmış) adreslerden AKTİF bir kullanıcıya ait olanlar — "yalnız pasife ait adres"
     *  kararı için (aynı adresi aktif biri de kullanıyorsa adres düşürülmez). */
    @Query("SELECT DISTINCT LOWER(TRIM(u.email)) FROM AppUser u WHERE u.active = true AND u.email IS NOT NULL "
        + "AND LOWER(TRIM(u.email)) IN :emails")
    List<String> findActiveEmailsLowerIn(@Param("emails") Collection<String> emails);

    /**
     * Push "aynı kişi" kuralı (2026-10-21, {@code PushSamePersonGuard}): verilen hesapların (BÜYÜK harf kullanıcı adları)
     * e-posta adresini paylaşan TÜM aktif hesaplar — verilenlerin kendileri dahil. Boş adres hiçbir zaman eşleşmez; pasif
     * hesap dönmez. Satır: {@code [Long id, String username, String email, String lastLoginAt, String displayName]} (hafif
     * izdüşüm — foto / EAGER teamIds yüklenmez; teslimat başına bir kez okunur; görünen ad kişi eşleşmesi içindir — aynı kutuyu
     * paylaşan farklı kişiler birleşmez).
     */
    @Query("SELECT u.id, u.username, u.email, u.lastLoginAt, u.displayName FROM AppUser u WHERE u.active = true AND u.email IS NOT NULL "
        + "AND TRIM(u.email) <> '' AND LOWER(TRIM(u.email)) IN (SELECT LOWER(TRIM(v.email)) FROM AppUser v "
        + "WHERE v.active = true AND v.email IS NOT NULL AND TRIM(v.email) <> '' AND UPPER(v.username) IN :usernames)")
    List<Object[]> findSamePersonAccounts(@Param("usernames") Collection<String> upperUsernames);

    /**
     * Sistem geneli toplu pasife alma (2026-10-02) — aday satırları HAFİF projeksiyonla: foto / EAGER teamIds taşınmaz
     * (tam entity listesi kullanıcı başına base64 foto çekerdi). Satır:
     * {@code [Long id, String username, String displayName, String email, String systemRole, String authSource,
     * String lastLoginAt, String createdAt, Boolean active, Long teamId, String employeeId]}.
     */
    @Query("SELECT u.id, u.username, u.displayName, u.email, u.systemRole, u.authSource, u.lastLoginAt, u.createdAt, "
        + "u.active, u.teamId, u.employeeId FROM AppUser u")
    List<Object[]> findBulkCandidateRows();

    /** Tüm çoklu-takım üyelikleri ({@code app_user_teams}) — satır {@code [Long userId, Long teamId]}. */
    @Query("SELECT u.id, tid FROM AppUser u JOIN u.teamIds tid")
    List<Object[]> findAllTeamMembershipPairs();
    /**
     * Sicil (AD {@code cn}) → kullanıcı(lar). BİLEREK liste döner: eski {@code Optional findByEmployeeId}
     * aynı sicili taşıyan iki satırda (ör. elle açılmış yerel hesap + LDAP hesabı) istisna fırlatıyordu —
     * girişte bu istisna AuthController'da yutulup kullanıcıya "hatalı parola" gibi görünüyordu. Seçim
     * kuralı {@code service.ManagerLookup}'ta. Karşılaştırma boşluk ve harf duyarsız (elle girilen
     * sicilde baş/son boşluk bağlantıyı sessizce koparıyordu); BAŞTAKİ SIFIRLAR normalize EDİLMEZ.
     */
    @Query("SELECT u FROM AppUser u WHERE UPPER(TRIM(u.employeeId)) = UPPER(TRIM(:sicil)) ORDER BY u.id ASC")
    List<AppUser> findAllByEmployeeIdNormalized(@Param("sicil") String sicil);
    List<AppUser> findByTeamIdOrderByUsernameAsc(Long teamId);
    List<AppUser> findAllByOrderByUsernameAsc();
    /** Yalnız AKTİF kullanıcılar — pasifleri hiç kullanmayan çözümleyiciler için (Haftalık Rapor takım bilgisi, 2026-10-01). */
    List<AppUser> findByActiveTrueOrderByUsernameAsc();
    @Query("SELECT COUNT(u) > 0 FROM AppUser u WHERE UPPER(u.username) = UPPER(:username)")
    boolean existsByUsername(@Param("username") String username);
    boolean existsByTeamId(Long teamId);

    // ── Çoklu takım üyeliği (app_user_teams) ──
    /** Belirli bir takıma ÜYE (birincil veya ek) tüm kullanıcılar. */
    @Query("SELECT DISTINCT u FROM AppUser u JOIN u.teamIds tid WHERE tid = :teamId ORDER BY u.username ASC")
    List<AppUser> findByMembershipTeamId(@Param("teamId") Long teamId);

    /** Teslimat günlüğü zenginleştirmesi (2026-09-11): kullanıcı adı → ÜYE olduğu takım id'leri.
     *  Projeksiyon BİLİNÇLİ: entity çekmek her satırda base64 foto taşır, liste yolunda gereksiz yük. */
    @Query("SELECT u.username, tid FROM AppUser u JOIN u.teamIds tid WHERE UPPER(u.username) IN :usernames")
    List<Object[]> findTeamMembershipsByUsernames(@Param("usernames") java.util.Collection<String> usernames);

    /** Verilen takım kümesinden HERHANGİ birine üye kullanıcılar (scope filtresi). */
    @Query("SELECT DISTINCT u FROM AppUser u JOIN u.teamIds tid WHERE tid IN :teamIds ORDER BY u.username ASC")
    List<AppUser> findByAnyTeamId(@Param("teamIds") Collection<Long> teamIds);

    /**
     * Takım kümesinin ÜYELERİ — birincil takım ({@code teamId}) VEYA çoklu üyelik ({@code app_user_teams}).
     * {@link #findByAnyTeamId} yalnız üyelik tablosuna bakar; birincil takımı üyelik satırı olmayan kullanıcı
     * orada görünmez. "Ekip üyelerinin yaptıkları" kapsamı (Monitor Changes / Audit Log, 2026-09-25) ikisini birden ister.
     */
    @Query("SELECT DISTINCT u FROM AppUser u LEFT JOIN u.teamIds tid WHERE u.teamId IN :teamIds OR tid IN :teamIds")
    List<AppUser> findMembersOfTeams(@Param("teamIds") Collection<Long> teamIds);

    /**
     * Ekip kapsamı için üyelerin YALNIZ kimliği + küçük harf kullanıcı adı (regresyon R9): tam {@code AppUser}
     * fotoğraf kolonunu ve EAGER takım koleksiyonunu da çekiyordu — Denetim Logu herkese açıldığı için istek başı
     * maliyet önemli. Satır: {@code [Long id, String lowerUsername, String systemRole]}.
     */
    @Query("SELECT DISTINCT u.id, LOWER(u.username), u.systemRole FROM AppUser u LEFT JOIN u.teamIds tid WHERE u.teamId IN :teamIds OR tid IN :teamIds")
    List<Object[]> findMemberIdentities(@Param("teamIds") Collection<Long> teamIds);

    /**
     * Takım üyeleri penceresinin beyaz-liste satırları (performans 2026-11-06, PF2 #10 — {@code TeamDirectoryController.members}):
     * eskiden TÜM kullanıcılar tam varlık olarak (AD fotoğrafı base64 TEXT + EAGER takım koleksiyonu) okunup Java'da takıma
     * göre süzülüyordu. Üyelik yüklemi aynı: birincil takım VEYA çoklu üyelik ({@code MEMBER OF} → tekrar satır yok). Fotoğrafın
     * kendisi okunmaz; yalnız VAR MI ({@code has_photo}: boş değil — LDAP fotoğrafı base64 metnidir, salt boşluk olmaz).
     * Satır: {@code [Long id, String username, String displayName, String firstName, String lastName, String orgRole,
     * String title, String department, String mudurlukName, String companyLevel, String email, Long managerId,
     * String systemRole, Boolean active, Boolean hasPhoto]}; kimliğe göre sıralı.
     */
    @Query("SELECT u.id, u.username, u.displayName, u.firstName, u.lastName, u.orgRole, u.title, u.department, u.mudurlukName, "
         + "u.companyLevel, u.email, u.managerId, u.systemRole, u.active, "
         + "CASE WHEN u.photoBase64 IS NOT NULL AND LENGTH(TRIM(u.photoBase64)) > 0 THEN true ELSE false END "
         + "FROM AppUser u WHERE u.teamId = :teamId OR :teamId MEMBER OF u.teamIds ORDER BY u.id")
    List<Object[]> findTeamMemberDirectoryRows(@Param("teamId") Long teamId);

    /**
     * Görünen ad + aktiflik — yalnız verilen kimlikler (takım üyeleri penceresinin müdür / lider / eskalasyon kişisi adları; foto
     * ve takım koleksiyonu yüklenmez). Satır: {@code [Long id, String username, String displayName, String firstName,
     * String lastName, Boolean active]}. Çağıran boş koleksiyonla çağırmamalı.
     */
    @Query("SELECT u.id, u.username, u.displayName, u.firstName, u.lastName, u.active FROM AppUser u WHERE u.id IN :ids")
    List<Object[]> findNameRowsByIds(@Param("ids") Collection<Long> ids);

    /**
     * 7/24 izleme ekibi takımlarının AKTİF üyeleri (2026-10-04, {@code NocOperatorService}) — fotoğraf kolonu ve EAGER takım
     * koleksiyonu YÜKLENMEZ (önbellek her ≤ 30 sn'de bir koşar). Üyelik {@code memberTeamIds} ile aynı: birincil takım VEYA
     * çoklu üyelik. Satır başına bir üyelik eşleşmesi: {@code [Long id, String username, String displayName,
     * String firstName, String lastName, Long primaryTeamId, Long membershipTeamId (null olabilir), String systemRole]}.
     */
    @Query("SELECT u.id, u.username, u.displayName, u.firstName, u.lastName, u.teamId, tid, u.systemRole "
         + "FROM AppUser u LEFT JOIN u.teamIds tid "
         + "WHERE u.active = true AND (u.teamId IN :teamIds OR tid IN :teamIds)")
    List<Object[]> findActiveMemberRowsOfTeams(@Param("teamIds") Collection<Long> teamIds);

    /** Kimlik → etkin bayrağı (dar: fotoğraf vb. okunmaz) — eskalasyon kişisi listesinin {@code user_active} alanı. */
    @Query("SELECT u.id, u.active FROM AppUser u WHERE u.id IN :ids")
    List<Object[]> findActiveFlagsByIds(@Param("ids") Collection<Long> ids);

    /** Takım silme guard'ı: takıma üye (birincil veya ek) kullanıcı var mı. */
    @Query("SELECT COUNT(u) > 0 FROM AppUser u JOIN u.teamIds tid WHERE tid = :teamId")
    boolean existsByMembershipTeamId(@Param("teamId") Long teamId);
    long countBySystemRoleAndActiveTrue(String systemRole);

    /** Aktif (pasif olmayan) kullanıcı hesabı sayısı — giriş sayfası kullanım istatistikleri (2026-10-04); tek COUNT. */
    long countByActiveTrue();

    /** Tek-oturum izleme: o an login (aktif oturumu olan) kullanıcılar. Admin "Sonlandır" sonrası
     *  konan sentinel ('TERMINATED:...') aktif sayılmaz, hariç tutulur. */
    @Query("SELECT u FROM AppUser u WHERE u.activeSessionId IS NOT NULL "
        + "AND u.activeSessionId NOT LIKE 'TERMINATED:%' ORDER BY u.username ASC")
    List<AppUser> findAllWithActiveSession();

    /** Açılışta stale tek-oturum işaretlerini topluca temizler — in-memory oturumlar restart'ı
     *  yaşamaz, ama DB'deki activeSessionId kalır; aksi halde restart sonrası aktif sayım şişer ve
     *  login'de yanlış "başka yerde aktif oturum" onayı çıkar. Temizlenen satır sayısını döner.
     *
     *  <p>TERMINATED sentinel'i HARİÇ (:57 ile aynı koruma). Yöneticinin "Oturumu Sonlandır"
     *  kararı gerçek oturumu silmez, sentinel yazar ve kick kullanıcının BİR SONRAKİ isteğinde
     *  {@code AuthInterceptor.isSessionSuperseded} ile uygulanır. Prod'da store-type=jdbc ve
     *  timeout 24 saat, yani oturum pod ölümünden sağ çıkıyor — sentinel de süpürülürse rutin bir
     *  sürüm kick'i sessizce geri alıyordu (istek geçiyor, adoptSessionIfNone oturumu yeniden
     *  sahipleniyordu). Süpürmenin "in-memory oturumlar restart'ı yaşamaz" varsayımı prod için
     *  yanlış; sentinel'i korumak kick'i restart'tan bağımsız kılar. */
    @Modifying
    @Query("UPDATE AppUser u SET u.activeSessionId = null WHERE u.activeSessionId IS NOT NULL "
        + "AND u.activeSessionId NOT LIKE 'TERMINATED:%'")
    int clearAllActiveSessions();

    /**
     * Oturum ping'i, debounce SQL'de (2026-10-23, çok pod Sürüm 2a — MP-51): lastSeenAt yalnız SAKLI değer {@code :stale}'den
     * eskiyse (ya da boşsa) yazılır. Eskiden debounce pod belleğindeydi: kullanıcının son sekmesi kapanınca pod A
     * ({@link #clearLastSeen}) damgayı siliyor, yeniden açılan sayfanın yoklamaları pod B'ye düşüyor ve B'nin 60 sn'lik
     * yerel kaydı yazımı atlıyordu → kişi ≤ 60 sn çevrimdışı görünüyor, başka yerden giriş 409 onayı ALMADAN eski oturumu
     * düşürüyordu. Karar artık satırdan — tüm pod'lar aynı. Tek statement; eşleşmezse 0.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.lastSeenAt = :ts WHERE UPPER(u.username) = UPPER(:username) AND u.activeSessionId = :sid "
        + "AND (u.lastSeenAt IS NULL OR u.lastSeenAt < :stale)")
    int touchLastSeenIfStale(@Param("username") String username, @Param("sid") String sid, @Param("ts") String ts,
                             @Param("stale") String stale);

    // ── Hedefli kullanıcı satırı yazımları (2026-10-23, çok pod Sürüm 2a — MP-51) ──────────────────────────────────
    //
    // Kişinin kendi tercihleri (push, ürün turu, profil e-postası) ve süresi dolmuş kilidin temizliği eskiden işlem DIŞINDA
    // okunmuş (detached) bir AppUser'ı save() ile birleştiriyordu: merge satırı yeniden okur ve ayrık nesnenin TÜM
    // alanlarını üstüne kopyalar → bu arada başka bir istekte (başka pod) yazılan pasifleştirme (active=false +
    // TERMINATED: nöbetçisi), kilit, rol ya da yeni oturum kimliği geri alınıyordu (@DynamicUpdate de bunu önlemez:
    // değişen her sütun "kirli" sayılır). Bu yöntemler YALNIZ ilgili sütunları yazar.

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.pushOptOut = :optOut WHERE u.id = :id")
    int updatePushOptOut(@Param("id") Long id, @Param("optOut") Boolean optOut);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.pushQuietStart = :start, u.pushQuietEnd = :end, u.pushQuietDays = :days, "
        + "u.pushQuietMinLevel = :minLevel WHERE u.id = :id")
    int updatePushQuietHours(@Param("id") Long id, @Param("start") String start, @Param("end") String end,
                             @Param("days") String days, @Param("minLevel") String minLevel);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.pushMinLevel = :minLevel, u.pushFamilies = :families, u.pushLang = :lang WHERE u.id = :id")
    int updatePushPreferences(@Param("id") Long id, @Param("minLevel") String minLevel, @Param("families") String families,
                              @Param("lang") String lang);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.pushSnoozeUntil = :until WHERE u.id = :id")
    int updatePushSnoozeUntil(@Param("id") Long id, @Param("until") String until);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.pushSnoozeUntil = :until, u.pushSnoozeCritical = :critical WHERE u.id = :id")
    int updatePushSnooze(@Param("id") Long id, @Param("until") String until, @Param("critical") Boolean critical);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.tourState = :state WHERE u.id = :id")
    int updateTourState(@Param("id") Long id, @Param("state") String state);

    /** Profil e-postası YALNIZ hâlâ boşsa yazılır (sorun bildiriminde "profile kaydet"; araya giren kayıt ezilmez). */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.email = :email WHERE u.id = :id AND (u.email IS NULL OR TRIM(u.email) = '')")
    int updateEmailIfBlank(@Param("id") Long id, @Param("email") String email);

    /** Süresi dolmuş geçici kilidin temizliği — yalnız okunan değer hâlâ duruyorsa (araya giren yeni kilit korunur). */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.lockoutUntil = null WHERE u.id = :id AND u.lockoutUntil = :seen")
    int clearExpiredLockout(@Param("id") Long id, @Param("seen") String seen);

    /** Sekme/tarayıcı kapandı ("ayrıldım" sinyali, 2026-10-02): yalnız BU oturumun lastSeenAt'i silinir → kullanıcı
     *  çevrimiçi sayımından ve "başka yerde açık oturum" kararından hemen düşer. Kayıtlı oturum başka bir sid ise
     *  (yeni giriş / sentinel) dokunulmaz. Oturum kaydı ({@code activeSessionId}) korunur: aynı çerezle dönen kullanıcı
     *  yeniden giriş yapmadan devam eder, ilk ping'i lastSeenAt'i yeniden yazar. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AppUser u SET u.lastSeenAt = null WHERE UPPER(u.username) = UPPER(:username) AND u.activeSessionId = :sid")
    int clearLastSeen(@Param("username") String username, @Param("sid") String sid);

    /** Restart sonrası yeniden sahiplenme (2026-09-13, QA ISSUE-002): açılış temizliği işareti NULL'a çeker ama JDBC
     *  oturum restart'ı yaşar; ilk ping oturumu geri yazar. Başka bir işaret (canlı sid ya da TERMINATED) varsa
     *  DOKUNMAZ — süpersede/kick semantiği korunur. */
    @Modifying
    @Query("UPDATE AppUser u SET u.activeSessionId = :sid, u.lastSeenAt = :ts WHERE UPPER(u.username) = UPPER(:username) AND u.activeSessionId IS NULL")
    int adoptSessionIfNone(@Param("username") String username, @Param("sid") String sid, @Param("ts") String ts);

    /**
     * Başarısız giriş damgası — TEK atomik statement (entity yükle-kaydet DEĞİL).
     *
     * <p>Gerekçe: brute-force sırasında aynı kullanıcıya paralel denemeler gelir; oku-artır-kaydet
     * yapılsaydı iki denemeden biri diğerinin sayacını ezerdi (lost update) ve sayaç gerçek deneme
     * sayısının altında kalırdı. {@code COALESCE} şart: kolon mevcut satırlarda NULL olabilir
     * (DEFAULT yalnız yeni satırlara uygulanır).
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.failedSinceLogin = COALESCE(u.failedSinceLogin, 0) + 1, "
        + "u.lastFailedLoginAt = :ts, u.lastFailedLoginIp = :ip, u.lastFailedLoginReason = :reason "
        + "WHERE UPPER(u.username) = UPPER(:username)")
    int bumpFailedLogin(@Param("username") String username, @Param("ts") String ts,
                        @Param("ip") String ip, @Param("reason") String reason);

    // ── Faz 3b: manager (müdür) → astları / yönettiği takımlar ──
    List<AppUser> findByManagerId(Long managerId);

    /** Müdür push eşlemesi (2026-09-13): MANAGER kademe kontağının e-postası → aktif uygulama kullanıcısı (küçük harf). */
    @Query("SELECT u FROM AppUser u WHERE u.active = true AND u.email IS NOT NULL AND LOWER(u.email) IN :emails")
    List<AppUser> findActiveByEmailsLower(@Param("emails") Collection<String> emails);

    /**
     * Eskalasyon adımı push'u (2026-10-04): kişinin e-postasıyla eşleşen TÜM kullanıcılar (aktif + pasif, küçük harf) —
     * çağıran "tek aktif eşleşme / belirsiz / yalnız pasif / yok" ayrımını kendisi yapar. {@code email} küçük harf geçilir.
     */
    @Query("SELECT u FROM AppUser u WHERE u.email IS NOT NULL AND LOWER(u.email) = :email")
    List<AppUser> findAllByEmailLower(@Param("email") String email);
    boolean existsByManagerId(Long managerId);

    /** Haftalık rapor PO bildirimi — kontağı olmayan takımlar için fallback. */
    List<AppUser> findByTeamIdAndOrgRoleAndActiveTrue(Long teamId, String orgRole);

    /**
     * Takımın AKTİF üyeleri (birincil ya da ek üyelik) verilen kurum rolüyle — teknik hata raporu PO onaylayıcıları
     * (2026-10-17, S6). {@link #findByTeamIdAndOrgRoleAndActiveTrue} yalnız birincil takıma bakar.
     */
    @Query("SELECT DISTINCT u FROM AppUser u LEFT JOIN u.teamIds tid WHERE u.active = true AND u.orgRole = :role "
            + "AND (u.teamId = :teamId OR tid = :teamId) ORDER BY u.username ASC")
    List<AppUser> findActiveByOrgRoleInTeam(@Param("teamId") Long teamId, @Param("role") String role);

    /**
     * Admin Users dizini — TEK esnek filtreli + sayfalı + SIRALANABİLİR arama (2026-11-12, dizin zenginleştirme).
     * Üç eski sorgunun ({@code findFiltered}/{@code findFilteredDormant}/{@code findFilteredInTeams}) yerine geçer:
     * serbest metin alan-bazlı, hesap durumu, kaynak (LOCAL/LDAP), iletişim (telefon/e-posta/OTP), 7/24 (NOC), çevrimiçi,
     * son-giriş/oluşturulma tarih aralığı. Sıralama Pageable'ın Sort'undan gelir (sorguda ORDER BY YOK; çağıran id ikincil
     * sıralamayı ekler — kararlı sayfalama). null / 0 parametreler ilgili koşulu atlar (AuditLog.findFiltered deseni).
     *
     * <p>Koleksiyon parametreleri ({@code scopeTeams}, {@code nocTeams}) ASLA null/boş geçilmez — kapsam/NOC uygulanmıyorsa
     * nöbetçi {@code List.of(-1L)} + bayrak ({@code scoped=false} / {@code nocMode=0}) geçilir (JPQL boş {@code IN} tuzağı).
     *
     * <p>Kipler: {@code phoneMode}/{@code emailMode}/{@code nocMode}/{@code onlineMode} = 0 herhangi · 1 var/evet/çevrimiçi
     * · 2 yok/hayır/çevrimdışı. {@code status} = active|passive|locked|mustchange. {@code source} = LOCAL|LDAP (LOCAL =
     * authSource null ya da 'LDAP' değil). {@code field} = all|name|username|email|phone|employee. Tarih aralığı GÜN
     * hassasiyetinde — ilk 10 karakter (yyyy-MM-dd), zaman biçiminden bağımsız. {@code nowIso}/{@code onlineThreshold}
     * UserService ISO biçiminde (yyyy-MM-dd'T'HH:mm:ss, sabit genişlik → leksikografik karşılaştırma güvenli).
     */
    @Query("SELECT u FROM AppUser u WHERE "
        + "(:q IS NULL OR "
        +   "(:field = 'username' AND LOWER(u.username) LIKE :q) OR "
        +   "(:field = 'email' AND LOWER(u.email) LIKE :q) OR "
        +   "(:field = 'phone' AND LOWER(u.phone) LIKE :q) OR "
        +   "(:field = 'employee' AND LOWER(u.employeeId) LIKE :q) OR "
        +   "(:field = 'name' AND (LOWER(u.displayName) LIKE :q OR LOWER(u.username) LIKE :q)) OR "
        +   "(:field = 'all' AND (LOWER(u.username) LIKE :q OR LOWER(u.displayName) LIKE :q "
        +                        "OR LOWER(u.email) LIKE :q OR LOWER(u.employeeId) LIKE :q))) AND "
        + "(:systemRole IS NULL OR u.systemRole = :systemRole) AND "
        + "(:orgRole IS NULL OR u.orgRole = :orgRole) AND "
        + "(:teamId IS NULL OR u.teamId = :teamId OR :teamId IN (SELECT tid FROM u.teamIds tid)) AND "
        + "(:scoped = false OR u.teamId IN :scopeTeams OR EXISTS (SELECT ts FROM u.teamIds ts WHERE ts IN :scopeTeams)) AND "
        + "(:status IS NULL OR "
        +   "(:status = 'active' AND u.active = true) OR "
        +   "(:status = 'passive' AND u.active = false) OR "
        +   "(:status = 'locked' AND (u.permanentLock = true OR (u.lockoutUntil IS NOT NULL AND u.lockoutUntil > :nowIso))) OR "
        +   "(:status = 'mustchange' AND u.mustChangePassword = true)) AND "
        + "(:source IS NULL OR "
        +   "(:source = 'LDAP' AND u.authSource = 'LDAP') OR "
        +   "(:source = 'LOCAL' AND (u.authSource IS NULL OR u.authSource <> 'LDAP'))) AND "
        + "(:phoneMode = 0 OR (:phoneMode = 1 AND u.phone IS NOT NULL AND TRIM(u.phone) <> '') "
        +                 "OR (:phoneMode = 2 AND (u.phone IS NULL OR TRIM(u.phone) = ''))) AND "
        + "(:emailMode = 0 OR (:emailMode = 1 AND u.email IS NOT NULL AND TRIM(u.email) <> '') "
        +                 "OR (:emailMode = 2 AND (u.email IS NULL OR TRIM(u.email) = ''))) AND "
        + "(:otpReady = false OR (u.active = true "
        +     "AND (u.permanentLock IS NULL OR u.permanentLock = false) "
        +     "AND (u.lockoutUntil IS NULL OR u.lockoutUntil <= :nowIso) "
        +     "AND (u.mustChangePassword IS NULL OR u.mustChangePassword = false) "
        +     "AND ((u.phone IS NOT NULL AND TRIM(u.phone) <> '') OR (u.email IS NOT NULL AND TRIM(u.email) <> '')))) AND "
        + "(:nocMode = 0 OR "
        +   "(:nocMode = 1 AND u.active = true AND ((u.teamId IS NOT NULL AND u.teamId IN :nocTeams) OR EXISTS (SELECT tn FROM u.teamIds tn WHERE tn IN :nocTeams))) OR "
        +   "(:nocMode = 2 AND NOT (u.active = true AND ((u.teamId IS NOT NULL AND u.teamId IN :nocTeams) OR EXISTS (SELECT tn2 FROM u.teamIds tn2 WHERE tn2 IN :nocTeams))))) AND "
        + "(:onlineMode = 0 OR "
        +   "(:onlineMode = 1 AND u.activeSessionId IS NOT NULL AND u.activeSessionId NOT LIKE 'TERMINATED:%' "
        +                    "AND u.lastSeenAt IS NOT NULL AND u.lastSeenAt >= :onlineThreshold) OR "
        +   "(:onlineMode = 2 AND (u.activeSessionId IS NULL OR u.activeSessionId LIKE 'TERMINATED:%' "
        +                    "OR u.lastSeenAt IS NULL OR u.lastSeenAt < :onlineThreshold))) AND "
        + "(:loginFromDay IS NULL OR (u.lastLoginAt IS NOT NULL AND REPLACE(u.lastLoginAt, ' ', 'T') >= :loginFromDay)) AND "
        + "(:loginToDay IS NULL OR (u.lastLoginAt IS NOT NULL AND REPLACE(u.lastLoginAt, ' ', 'T') < :loginToDay)) AND "
        + "(:createdFromDay IS NULL OR (u.createdAt IS NOT NULL AND REPLACE(u.createdAt, ' ', 'T') >= :createdFromDay)) AND "
        + "(:createdToDay IS NULL OR (u.createdAt IS NOT NULL AND REPLACE(u.createdAt, ' ', 'T') < :createdToDay)) AND "
        + "(:dormantBefore IS NULL OR u.lastLoginAt IS NULL OR u.lastLoginAt < :dormantBefore) AND "
        + "(:neverOnly = false OR u.lastLoginAt IS NULL)")
    Page<AppUser> findDirectory(@Param("q") String q,
                                @Param("field") String field,
                                @Param("systemRole") String systemRole,
                                @Param("orgRole") String orgRole,
                                @Param("teamId") Long teamId,
                                @Param("scoped") boolean scoped,
                                @Param("scopeTeams") Collection<Long> scopeTeams,
                                @Param("status") String status,
                                @Param("source") String source,
                                @Param("phoneMode") int phoneMode,
                                @Param("emailMode") int emailMode,
                                @Param("otpReady") boolean otpReady,
                                @Param("nocMode") int nocMode,
                                @Param("nocTeams") Collection<Long> nocTeams,
                                @Param("onlineMode") int onlineMode,
                                @Param("onlineThreshold") String onlineThreshold,
                                @Param("loginFromDay") String loginFromDay,
                                @Param("loginToDay") String loginToDay,
                                @Param("createdFromDay") String createdFromDay,
                                @Param("createdToDay") String createdToDay,
                                @Param("dormantBefore") String dormantBefore,
                                @Param("neverOnly") boolean neverOnly,
                                @Param("nowIso") String nowIso,
                                Pageable pageable);
}
