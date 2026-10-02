# BUG RAPORU 6 — Uçtan Uca Kod İncelemesi · 2026-09-09 (v20.50.28 üzerinde)

**Yöntem.** Altı eksende paralel, salt-okuma kod denetimi (E1 eşzamanlılık/sızıntı · E2 güvenlik
IDOR/SSRF/yetki · E3 veri katmanı · E4 sayısal/zaman · E5 alarm/eskalasyon · E6 React durum/render)
+ yerel örnekte tarayıcı güdümlü QA (34 sekme, konsol hataları, çökme metni). Her YÜKSEK/ORTA bulgu
kaynakta dosya:satır ile doğrulandı; yanlış-pozitifler elendi. **Bu turda bulgular yalnız
raporlanmadı, düzeltildi ve her düzeltme yeni testle kapılandı.** Aşağıdaki liste, düzeltilen hâliyle
"ne bozuktu → ne yapıldı" biçimindedir.

Tarayıcı QA'sında iki sekmenin (Expiry Forecast, Audit Log) ErrorBoundary'ye düşmesi (`Cannot read
properties of null (reading 'useContext')`) **kod hatası değil**, uzun süredir çalışan Vite dev
sunucusunun bayat HMR modülüydü; Vite yeniden başlatılınca her iki sekme de sağlıklı çizildi. Üretim
derlemesini etkilemez.

---

## KRİTİK — düzeltildi

| # | Yer | Bug | Neden bug | Çözüm |
|---|---|---|---|---|
| K1 | `AdminController` `POST/PUT /api/admin/users` | Kapsamlı müdür (AD-kaynaklı ADMIN, `viewTeamIds` dolu) **global ADMIN yaratabiliyordu** | Rol/takım kısıtı yalnız `isTeamAdmin()` dalındaydı; ADMIN rolü `system.global_admin` iznini taşıdığından müdür o daldan geçmiyor, `system_role=ADMIN` + `team_ids=[]` ile kendi seçtiği parolayla sınırsız yerel admin üretebiliyordu | Kısıt `!isAdmin(session)` (global olmayan HER yazar) için uygulanır: ADMIN/AUDIT atanamaz, hedef takımlar yönetim kapsamında olmalı; ek olarak ADMIN/AUDIT hesaplarına (düzenleme, parola sıfırlama, silme) yalnız global admin dokunur (`requireCanAdministerTarget`) |

## YÜKSEK — düzeltildi

| # | Yer | Bug | Neden bug | Çözüm |
|---|---|---|---|---|
| Y1 | 8 ayar controller'ı (`Ldap/Smtp/General/Secret/Branding/DatabaseInfo/LoginAnomaly/StormSettings`) | Kapsamlı müdür LDAP bind / SMTP host'unu değiştirip "test" ile saklı kimlik bilgisini alabiliyordu | Kapı yalnız matris izniydi; ADMIN rolü her izni taşır | `SessionScope.requireNotScopedAdmin` + matris izni (çift kapı); kapı `SessionScopeTest` |
| Y2 | `WeeklyReportService.Actor.isAdmin()` | Müdür başka takımın haftalık raporunu okuyor/onaylıyor/siliyor/**transfer ediyordu** | `"ADMIN".equals(systemRole)` rol dizesine bakıyordu | `Actor` `globalAdmin` bileşeni aldı (controller `SessionScope.isGlobalAdmin` geçer); 5-arg kurucu geriye uyumlu |
| Y3 | `WeeklyReportController` | `weekly_reports.read/.crud` matris izinleri HİÇ sorulmuyordu (matriste kapatmak etkisizdi) | Katalogda tanımlı, uçta çağrı yok | Her GET `read/view`, her yazma `crud/edit` ister |
| Y4 | `StormService.tryLock`, `MonitoringOutageService.withLock` | Dağıtık kilit geçici DB hatasında **fail-open** → çok-pod'da çift fırtına maili / çift alarm | `SchedulerService`'te kapatılan D2 kuralı iki kopyaya süpürülmemişti | Tipli `DuplicateKeyException`/`BadSqlGrammarException`; diğer istisnada tur ATLANIR |
| Y5 | `EscalationService.processResults` | Sertifika alarmlarına takım damgalanmıyordu → **çözüm push'u hiç gitmiyordu** (`SKIPPED_NO_RECIPIENTS`) | `enqueueResolve` yalnız `event.teamId` okur | Yeni olaya `setTeamId(domainTeamId)`, eski damgasız olaylar geri doldurulur |
| Y6 | `EscalationService.processConfirmedOutage` | ACK'li izleme alarmı seviye yükselince **hiç eskalasyon almıyor**, ack sonsuza kadar susturuyordu | Terfi bloğu `else if (!acknowledged)` dalının içindeydi | Terfi ack kapısından ÖNCE: kalıcılaşır, ack düşer, ESCALATION gönderilir (sertifika yoluyla parite) |
| Y7 | `StormService.resolveStorm` + `unlinkFromStorm` | Fırtına çözülünce hâlâ-down üyeler 24 saat sessiz kalıyor, recovery mailinde adsızdı | Attach anında `lastReAlertAt` damgalanmış; unlink sıfırlamıyordu | Unlink `lastReAlertAt=null` da yapar (sonraki sweep INITIAL'ı hemen gönderir); recovery maili/webhook hâlâ-down hedefleri ADIYLA listeler |
| Y8 | `ChainValidationService` (Y19), `RdapDomainExpiryService.daysUntil` | 24 saatten az süre önce dolmuş ara sertifika/domain **0 gün** görünüp `expired=false` kalıyordu | Düz `/` ve `ChronoUnit.DAYS` sıfıra kırpar | `Math.floorDiv` (proje kuralı) |
| Y9 | `AdminController.updateInventory` | Harf-farklı düzenleme (`Example.COM`) geçmişsiz kayıt / ikinci satır üretiyordu | Güncelleme yolu `validateDomain` normalizasyonunu atlıyordu; `existsByDomain` exact | Normalize + `existsByDomainIgnoreCase` |

## ORTA — düzeltildi

| # | Yer | Bug | Çözüm |
|---|---|---|---|
| O1 | `SqlPlaygroundService` | `setQueryTimeout(30)` PAYLAŞILAN `JdbcTemplate`'e uygulanıyordu → ilk oyun alanı sorgusundan sonra retention/scheduler DELETE'leri 30 sn tavana takılıyordu | Servise özel, tembel kurulan ayrı `JdbcTemplate`; paylaşılan bean'e dokunulmaz |
| O2 | `PortCheckerService.connectAny`, `NetworkResolver.connectFirstReachable(host)` | Tek-adres dallarında connect hatasında soket kapatılmıyordu (DOWN port monitörü başına 30 sn'de bir FD sızıntısı) | `NetworkResolver.connectSingle` (hatada kapat + yeniden fırlat) |
| O3 | `MonitoringController` 5 okuma ucu (`port/dns history`, `response-series`, `dns details`) | Envanter-türevi satırlarda bayat `team_id` ile yetkilendirme → transferden sonra eski takım okumaya devam ediyor, yeni takım 403 alıyordu | Yazma kardeşleri gibi `effectiveTeam(...)` |
| O4 | `CertificateController /check-preview/{domain}` | Oturum/takım kontrolü YOKTU; her rol başka takımın canlı TLS sonucunu (port/proxy/TLS modu) okuyabiliyordu | `inventory.list/view` + envanterdeyse `requireViewableDomain` |
| O5 | `MonitoringController create/updatePort` | Mükerrer guard `findFirst…ByIdAsc` en ESKİ (soft-delete) satırı görüp yeni aktif kopyayı kaçırıyordu; host harf-duyarlıydı | `existsByHostAndPortAndActiveTrue[AndIdNot]`; host/DNS domain küçük harfe normalize |
| O6 | `AdminController.purge*` | Kalıcı purge notları ve revizyonlarını bırakıyordu; domain yeniden eklenince "diriliyordu" | `purgeDomainNotes` (notlar + revizyonlar, aynı transaction) |
| O7 | `EscalationService.processResults` | Tek bir zaman aşımı (`status=error`) açık REVOKED/MISMATCH/CHAIN_BROKEN/HOSTNAME_MISMATCH alarmlarını "✅ çözüldü" ile kapatıyordu | `status=error` ise stale-çözüm adımı atlanır (hiçbir şey doğrulanmadı) |
| O8 | `EscalationService` çözüm konusu | DOMAINMON_TRANSFER_LOCK / BLACKLIST "Sertifika Süre Bitişi sorunu giderildi" yazıyordu | `resolvedTypeLabel` ortak yardımcı + tüm tipleri tarayan kapı testi |
| O9 | `EscalationService.resolveOpenAlertsSilently / closeOpenAlerts` | Bakım penceresi kurtarma / silme / duraklatma kapanışlarında çözüm PUSH'u hiç gitmiyordu | `enqueueResolve` (simetri kuralı push katmanında) |
| O10 | `MonitoringOutageService.changeCtx` (DNS_CHANGED) | Monitörün "E-posta/Webhook" bayrakları yok sayılıyordu | `DnsChange` bayrakları taşır; `chanCtx` uygulanır (günlük re-alert dâhil) |
| O11 | `MonitoringController` 9 izleme türü `active=false` | Duraklatılan monitörün açık alarmı SONSUZA kadar açık kalıyordu (sweep'ler `findByActiveTrue`) | `closeAlertsOnPause` → sessiz kapanış (envanterin `closeAlertsOnDeactivate` eşleniği) |
| O12 | `IncidentController /options` | `"ADMIN".equals(systemRole)` rol dizesi | `SessionScope.isGlobalAdmin` |
| O13 | `UserService.updateTeam` | Mevcut ada rename DB UNIQUE'e çarpıp 500; harf-farklı ikiz takım | `existsByNameIgnoreCase` kontrolü |
| O14 | `UserPushService.quietHoursBlock` | `start == end` 24 saat susturuyordu | Sıfır uzunluklu pencere = pencere yok |
| O15 | Frontend `AlertHistory` bildirim modalı | Ağ hatasında spinner sonsuza kadar dönüyordu (`.catch/.finally` yok) | catch + finally + hata metni |
| O16 | Frontend `CertificatesTable`, `LoginIssueReports`, `IncidentHistoryPage`, `MonitorChangesConsole`, `AlertHistory` | Tuş başına sunucu isteği + fetch yarışı (bayat yanıt yeni listeyi eziyor) | 300 ms debounce + `seq` guard |
| O17 | Frontend i18n | `InventoryFormModal` "Evet/Hayır" sabit; 7 kapat düğmesinde sabit `aria-label` | `t('inv.yes'/'inv.no')`, `t('app.close')` |
| O18 | `AdminController /diagnostics/proxy-ca-chain` | Kardeş tanılama uçlarının `requireAdminOrMonitoredDomain` kapısı yoktu | Aynı kapı eklendi |

## AÇIK bırakılanlar (bu turun kapsamı dışında, bilinçli)

- **E2#8** `GET /monitoring/confirmations` takım süzgeci yok (yalnız "hangi host şu an teyitte" sızar; anahtar
  türe göre URL/host/domain olduğundan doğru takım çözümü ayrı iş).
- **E3#4** `NotificationGroup` takım-içi ad benzersizliği yalnız uygulama katmanında (eşzamanlı POST'ta ikiz);
  DB `UNIQUE` patch'i + `DataIntegrityViolationException` yakalama ayrı şema işi.
- **E3#7** `WeeklyAvailabilityReportService` envanter satırı başına `latestCheckRepo.findById` (N+1; arka plan işi).
- **E5#9–11** Fırtına alıcı çözümünde `mail_disabled` damgası; sertifika yolunda seviye DÜŞÜŞÜ kalıcılaşmıyor;
  manuel resolve'da storm üyesi bireysel mail — DÜŞÜK.
- **E1#7** `CompletableFuture.join()` zaman aşımsız (soket seviyesinde sınırlı; savunma derinliği).
- **E6#8–10** `t('inc.cat'+x) || fallback` ölü dal; App.jsx global poll `useVisibleInterval` kullanmıyor;
  efektten `.then` yükleyicilerde `.catch` yok (yükleme bayrağı sızmıyor).

## Doğru bulunanlar (tarandı, temiz)

`response-series` hunisi ve chart `ts` guard'ları; executor `@PreDestroy`'ları; tüm `HttpClient` tekilleri;
`SafeRedirect` + `SsrfGuard` her dış istemcide; `RepositoryWriteTransactionGuardTest` allow-list'i çağıranlarıyla
uyumlu; `applySchemaPatches` ↔ `@Table` adları; create/update setter paritesi 9 türde; `Boolean` unbox NPE yok;
retention çocuk-önce sıralaması; `PermissionCatalog` tek-satır çok-eylem kalmadı; e-posta HTML `esc()`;
zaman dilimi (`LocalDate.now()` çıplak yok, ISO leksikografik karşılaştırmalar 19-karakter UTC); ISO hafta;
frontend sayfalama 0↔1 dönüşümleri; timer temizlikleri; hook sırası.

## Test kapısı (bu tur eklenenler)

`EscalationServiceTest` (+7), `MonitoringOutageServiceTest` (+2), `StormServiceTest` (+1),
`SqlPlaygroundServiceTest` (+2), `UserPushServiceTest` (+1), `RdapDomainExpiryServiceTest` (+1),
`ChainValidationServiceTest` (+1), `WeeklyReportServiceTest` (+1), `SessionScopeTest` (+1),
`AdminControllerTest` (+5), `MonitoringControllerTest` (+3), `CertificateControllerTest` (+2),
frontend `CertificatesTable.test.jsx` (+1).
