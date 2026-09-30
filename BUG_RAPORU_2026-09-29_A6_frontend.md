# BUG RAPORU — Eksen A6 (React state / efekt / render / veri akışı / sunucu sözleşmesi) · 2026-09-29

**Kapsam:** `frontend/src/**` (700 kaynak dosya, test hariç) — `main` 20.91.0 + commit edilmemiş "ortam adı ayarı"
değişikliği (`GeneralSettings.jsx`, `EnvironmentNameField.jsx`, `VersionPopover.jsx`, `DeployBadges.jsx`, `i18n/index.jsx`).
**Yöntem:** salt okuma — kod değiştirilmedi, test/derleme/sunucu koşturulmadı, kilit alınmadı. Öncelik listesi
(App / api / contexts / hooks / kullanıcı-takım yönetimi / izleme ortak parçaları / CheckHistoryTab / CertificateModal /
AlertHistory / Olaylar / Sistem Sağlığı / Envanter / ui/) satır satır okundu; kalan ~500 dosya riskli desen tarayıcılarıyla
(grep + iki küçük node betiği: "meşgul bayrağı try'sız" ve "t() argüman ↔ `{n}` sayısı") süpürüldü. Her YÜKSEK/ORTA
bulgu kaynakta dosya:satır ile teyit edildi; §Yanlış-pozitif listesindeki desenler elendi. Gerçek kişi/kurum/alan adı
yazılmadı.

**Genel değerlendirme:** Sistemik bir açık yok. Yarış koruması (`seq`/`alive`), sızan yükleme bayrağı, StrictMode
çift-kurulum, 0↔1 sayfalama, URL ad alanı, timer/listener temizliği ve snake_case sözleşmesi tarayıcı yüzeylerinin
tamamına yakınında doğru uygulanmış; baseline'daki 12 frontend maddesinin 11'i **ÇÖZÜLMÜŞ**. Kalanlar tekil sapmalar:
1 YÜKSEK (envanter formunda "Çalıştır" formu kapatıp yazılanı düşürüyor), 4 ORTA, 16 DÜŞÜK.

Önem: **KRİTİK 0 · YÜKSEK 1 · ORTA 4 · DÜŞÜK 16.**

---

## YÜKSEK

### Y1 · Envanter düzenleme formunda "Çalıştır" formu KAPATIYOR — yazılıp kaydedilmemiş düzenlemeler onaysız kayboluyor
- `frontend/src/components/inventory/InventoryFormModal.jsx:336-346` (`runNow`): kontrol bitince `onSaved?.()` çağrılıyor.
  Çağıranların ikisi de `onSaved`'ı **"kaydedildi → formu kapat"** diye yorumluyor:
  `frontend/src/components/admin/InventoryManager.jsx:827` (`onSaved={… setFormModal(null) …}`) ve
  `frontend/src/App.jsx:1615` (`onSaved={… setInvForm(null) …}`). Aynı dosyadaki `del()` (:363) `onSaved` + `onClose`'u
  AYRI AYRI çağırıyor — yazar `onSaved`'ın tek başına kapatmadığını varsaymış; test de (`InventoryFormActions.test.jsx:135`)
  yalnız `refreshCertificateHealth`'in kayıtlı adresle çağrıldığını sınıyor, form açık mı bakmıyor.
- **Neden bug:** Kullanıcı bir kaydı Düzenle ile açar, portu/sorumlu ekipleri/açıklamayı değiştirir, "kaydetmeden önce
  kontrol edeyim" diye alt çubuktaki **Çalıştır**'a basar. Kontrol 2–6 sn sürer (bu sırada form etkileşimde kalır,
  `busy` yalnız `saving || firstRun`), bitince "Kontrol koştu" tostu çıkar ve **form kendiliğinden kapanır**;
  yazdığı her şey onaysız gider. Kart yolunda (App) ek olarak `loadData()` + `certModalRefresh` tetiklenir, kullanıcı
  "kaydedildi sandım" tuzağına düşer.
- **Çözüm:** `runNow` yalnız liste tazelemesi istesin: ayrı bir `onRefresh?.()` prop'u (InventoryManager `load()`, App
  `loadData()` + `setCertModalRefresh`), `onSaved` kaydetmeye/silmeye kalsın. Kapı: "Çalıştır sonrası form açık kalır
  ve alanlar yazılan değeri korur" testi (`InventoryFormActions.test.jsx`).

## ORTA

### O1 · Kullanıcı formları AD/LDAP kaynaklı profil alanlarını kilitsiz düzenletiyor — "kaydedildi", sonraki girişte AD eziyor
- `frontend/src/components/admin/UserManager.jsx:748-754, 812-820` (`display_name`, `email`, `employee_id` + sekiz AD alanı:
  `first_name`, `last_name`, `title`, `phone`, `department`, `company_level`, `mudurluk_name`, `manager_sicil`) ve
  `frontend/src/components/admin/UserEditModal.jsx:139-146, 192-200` (aynı alanlar) — `auth_source === 'LDAP'` kaydında hiçbir
  alan `disabled`/uyarılı değil; listede LDAP rozeti var (:470-472) ama formda yok. Kaynak yorumu bile bunu söylüyor
  ("AD'den eşlenen profil alanları — LDAP kullanıcısında bir sonraki login'de tazelenir", :812) ve arka uç
  `UserService.java:832` ("For LDAP users these are refreshed from AD on next login") aynı şeyi yazıyor.
- **Neden bug:** Yönetici bir LDAP kullanıcısının unvanını/telefonunu/müdür sicilini düzeltir, yeşil "Kaydedildi" görür;
  kişinin bir sonraki girişinde AD değerleri geri yazılır — düzeltme sessizce kaybolur, eskalasyon kontağı (`syncPoLeadership`
  / kontak adı-e-postası) yanlış ada döner. Rol/takım/org-rol için kilit rozetleri (`role_locked` …) var, profil alanları için
  yok; kullanıcı "kilit yok = kalıcı" sanır. Sunucu tarafı A2 ekseninde.
- **Çözüm (UI):** `auth_source === 'LDAP'` ise AD alanlarını salt okunur çiz + `Field hint` "AD'den gelir, girişte tazelenir"
  (hintTone warn); yalnız yerel hesapta aç. `usr.ldapBadge` rozetini form başlığına taşı. Kapı: LDAP fixture'lı test —
  alanlar `disabled`, payload'a girmez.

### O2 · "Cihazlarım" paneli ağ hatasında sonsuza kadar "Yükleniyor"
- `frontend/src/components/DeviceHistoryPanel.jsx:92-99` (`loadDevices`): `await api…` try/catch'siz, `setLoading(false)` en sonda;
  `api/client.js request()` ağ hatasında **throw** eder → `loading` hiç düşmez; `:194` `if (loading) return <LoadingBlock/>`
  tüm paneli örter, yeniden dene yolu yok (yalnız remount). Aynı dosyada `loadFailed` (:117-122), `revokeRemembered`
  (:130-146), `logoutOthers` (:148-165), `reportLogin` (:167-186) da try'sız: ağ hatasında ne tost ne geri bildirim.
- **Neden bug:** Etkinliklerim → Cihazlarım (Sheet) ya da yönetici kullanıcı formundaki "Cihaz geçmişi" bölümü; geçici bir
  502/pod restart anında açılırsa spinner kalıcı döner, kullanıcı "cihazlarım gelmiyor" der; "Diğer cihazlardan çıkış"
  düğmesi ağ hatasında hiçbir şey söylemez. `busyFlagFinally` kapısı bu dosyayı yakalamıyor (temizlik gövdede var ama
  `finally`'de değil — kapının kör noktası).
- **Çözüm:** `loadDevices` try/catch/finally + hata bandı ("Cihazlar yüklenemedi — Tekrar dene"); eylemlerde `catch →
  toast.error(e?.message || t('dev.revokeFailed'))`.

### O3 · Veri Saklama: Prova/Şimdi çalıştır/Geriye doldur ağ hatasında düğmeleri KALICI kilitliyor
- `frontend/src/components/admin/RetentionSettings.jsx:151-157` (`dryRun`), `:159-172` (`runNow`), `:176-189` (`backfillHourly`):
  `setBusy('…')` → `await api.admin.retention…()` → `setBusy(null)` — try/finally yok. `request()` throw'unda `busy` düşmez;
  üç düğme de `busy` ile kilitli, ekran yenilenene dek çalışmaz.
- **Neden bug:** Bu uçlar UZUN sürer (`timeoutMs` yok; retention koşumu dakikalar); tam o sırada oturum/proxy koparsa
  yönetici "Şimdi çalıştır" düğmesini bir daha basamaz ve koşumun bitip bitmediğini göremez. `busyFlagFinally` kapısının
  aynı kör noktası (O2).
- **Çözüm:** üçünü `try { … } catch (e) { toast.error(e?.message || t('ret.actionFailed')) } finally { setBusy(null) }` kalıbına al
  (aynı dosyadaki `save` :131-148 zaten böyle). Kapıyı "temizlik finally'de olmalı" diye sıkılaştır.

### O4 · Sertifika silme tostu ham yer tutucu basıyor: "{0} sertifikası silindi"
- `frontend/src/utils/deleteInventory.js:44` — `toast.success(t('inv.deleted'))`, alan adı argümanı YOK; sözlük
  `i18n/index.jsx:5823` `'{0} sertifikası silindi'` / `:18818` `'{0} certificate deleted'`. `useT` (`i18n/index.jsx:26063-26073`)
  boş kalan `{n}`'i silmez. Aynı çağrı `inventory/InventoryFormModal.jsx:363` (form içindeki Sil). `InventoryManager.jsx:566`
  ise doğru (`t('inv.deleted', domain)`).
- **Neden bug:** Genel Bakış kartından, sertifika penceresinden ve envanter formundan yapılan HER silmede (kullanıcıya en çok
  görünen yıkıcı eylem) yeşil tost "{0} sertifikası silindi" yazar — hangi kaydın silindiği de söylenmez. Yanlış gösterim; i18n
  kapıları (parity / used-keys) argüman sayısına bakmadığı için geçiyor.
- **Çözüm:** `t('inv.deleted', domain)` (yardımcıda `domain` zaten elde) ve `t('inv.deleted', record.domain)`; genel kapı: raporun
  ekindeki argüman-sayısı betiği (`{n}` sayısı > argüman sayısı → kırmızı) `src/test`'e alınabilir.

## DÜŞÜK

- **D1 · Sınıf: efekt/eylemde try'sız `await` — ağ hatası = yakalanmamış reddetme, geri bildirim yok.** Genel
  `unhandledrejection` dinleyicisi yok (grep: 0). Örnekler: `App.jsx:521` (60 sn ağ durumu `tick`), `:538` (`getWeakAlgorithms`),
  `:559` (15 sn `sessionPing` — sunucu kapalıyken 15 sn'de bir konsol hatası); `contexts/PermissionsProvider.jsx:18` (60 sn);
  `admin/SystemHealth.jsx:292` (2 sn tarama yoklaması); `admin/AdminPanel.jsx:56`; `admin/InventoryManager.jsx:212`;
  `inventory/InventoryFormModal.jsx:252, 266`; dokuz izleme sayfasında `listGroups`/`getTeams`/`monitorDefaults` `.then`'leri
  (`HttpMonitorPage.jsx:206,215,219` ve kardeşleri) ve düzenleme penceresindeki `del()` (`HttpMonitorPage.jsx:392-398`);
  `admin/TeamManager.jsx:150` (`loadUsers`); `admin/UserManager.jsx:252-272` (`exportCsv` — meşgul bayrağı da yok, çift tık
  = iki dışa aktarma), `:342-378` (`unlock*`, `del`). Kullanıcı eylemlerinde belirti: "tıkladım, hiçbir şey olmadı".
  | **Çözüm:** eylemlerde `catch → toast.error`; yoklamalarda `.catch(() => {})`; `main.jsx`'e tek `unhandledrejection`
  dinleyicisi (yalnız konsol/rapor, ErrorBoundary'ye düşürmeden).
- **D2 · `toast.warning` YOK — kısmi başarısız AD eşitlemesi YEŞİL tost.** `admin/TeamLdapAuditModal.jsx:61`
  `if (failed > 0 && toast.warning) … else toast.success(msg)`; Toast API'si yalnız `success/error/info/dismiss`
  (`ui/Toast.jsx:175-180`) → `failed > 0`'da başarı tostu. `admin/TeamManager.jsx:247` aynı koşulu `toast.error`'a düşürüyor
  (o doğru). | **Çözüm:** `toast.error`/`toast.info`; ya da Toast'a `warning` ekle ve iki yeri güncelle.
- **D3 · `hooks/use-mobile.js:6` `useState(undefined)` → telefonda ilk çizim MASAÜSTÜ.** Tablo/kart kararını
  `useIsMobile()` ile veren her yüzey (CheckHistoryTab `asCards`, ActivityLog, AlertHistory satırları, IncidentsPage,
  UserManager, InventoryManager, StatsView, NocCoveragePage, WarningsPage, ExpiryForecastPage, MonitorPageHeader menüsü)
  önce tabloyu boyar, efektten sonra karta döner: bir karelik titreme + ağaç remount'u. | **Çözüm:** tembel başlangıç
  `useState(() => window.matchMedia(q).matches)` (proje `useIsWide`/`UserManager.useNarrowViewport` zaten böyle).
- **D4 · `ActivityLog.jsx:101` "Bugün" süzgeci UTC gece yarısı, gruplama yerel gün.** `rangeToFromTo('today')`
  `setUTCHours(0)` (İstanbul'da 03:00'te başlar), `groupOf` (:168-171) yerel günü kullanır → 00:00–03:00 arasındaki kayıtlar
  "Bugün" süzgecinde yok ama başlık "Bugün" sayar; kardeş `presetRange` (myactivity) kayan pencere kullandığı için etkilenmez.
  | **Çözüm:** `d.setHours(0,0,0,0)` + `toISOString()` (yerel gün başı → UTC).
- **D5 · `TodayPanel.jsx:71-91` bileşen içinde bileşen tanımı (`const Card = …`).** Her TodayPanel çiziminde (2 dk yoklama,
  App'in her render'ı) yeni bileşen TİPİ → tüm kartlar sökülüp yeniden kurulur; "Tümünü gör"deki odak düşer, gereksiz iş.
  | **Çözüm:** `Card`'ı modül düzeyine çıkar, `t`/`setListModal` prop'la ver.
- **D6 · `App.jsx:1467, 1531` yetkisiz derin bağlantı BOŞ sayfa.** `?tab=settings` (ADMIN değil) / `?tab=sqlplayground`
  (global admin değil) `VALID_TABS`'ten geçer, içerik hiç çizilmez; `weeklyreports` (:1512-1516) aynı durumda StatusBlock
  gösteriyor. | **Çözüm:** iki sekmeye de "bu sayfa için yetkiniz yok" StatusBlock'u (`wracc.hidden*` deseni).
- **D7 · `UserManager.jsx:306-316, 793-801` TEAM_ADMIN düzenlemesi çoklu üyeliği tek takıma indiriyor (doğrulanmalı — A2).**
  Form tek kilitli takım kutusu çizer, `save` `team_ids: [ownTeamId]` gönderir; kullanıcı ikinci bir takımın da üyesiyse
  ekran onu göstermez ve sunucu kabul ederse (`UserService.updateUser` `teamIds != null` → set) diğer üyelikler düşer,
  `team_locked` kilitlenir. | **Çözüm:** TEAM_ADMIN'de `team_ids`'i payload'dan ÇIKAR (`null` → dokunma) ya da mevcut
  listeyi olduğu gibi geri gönder; formda diğer üyelikleri salt okunur rozetle göster.
- **D8 · `ui/NotificationGroupSelect.jsx:53-65` ham `<label>` + legacy `field-hint`, `ui/Field` bağı yok.** ui/ ailesinde
  shadcn dışı tek yüzey; ipucu `aria-describedby` ile bağlı değil, etiket `htmlFor` taşımıyor (tetik `role=combobox`
  olduğundan ad sarmalayıcı label'dan geliyor — çalışıyor ama SHADCN.md §9.1/6 ile çelişir). | **Çözüm:** `Field`
  render-prop'u + `hint`.
- **D9 · "İzlemeyi aç" tam sayfa yeniden yükleme.** `admin/AlertHistory.jsx:451` `window.location.assign(alertHref(a))`,
  `incidents/IncidentDetailSheet.jsx:232-234` `<a href={incidentHref(…)}>` — uygulama içi geçiş için SPA gezinmesi
  (`navigateTo`) yerine tam yükleme: açık süzgeç/sayfa/panel durumu ve 60 sn'lik yoklama önbellekleri gider, yavaş.
  Bağlantıyı kopyala için `href` doğru; tıklama için `spaClick` deseni (`noc/NocStatus.jsx:71-76`) var.
- **D10 · `api/client.js:114-118` 401 → `/?session=expired` derin bağlantıyı düşürüyor.** `?tab=…&monitor=…` ile
  çalışırken oturum süpersede olunca yeniden girişten sonra kullanıcı panoya iner. | **Çözüm:** mevcut `search`'ü koruyup
  `session=expired`'ı ekle (App zaten `session` paramını temizliyor, :900-910).
- **D11 · `ui/DateTimeField.jsx:301-306` yerel `parseIso` negatif ofseti tanımıyor (latent).** `-03:00` biten değere `Z`
  eklenip `Invalid Date` → alan boş; `utils/localDay.toUtc` (:23-30) bu sınıfı zaten çözüyor. | **Çözüm:** `toUtc` kullan.
- **D12 · `ui/Dialog.jsx:456-458` üst üste iki `showConfirm` — ilk promise hiç çözülmez (latent).** İkinci çağrı `dialog`'u
  ezer; ilk çağıran `await`'te asılı kalır (öncesinde meşgul bayrağı yaktıysa kilitli kalır). Bugün tek-tık akışlarında
  ulaşılmıyor. | **Çözüm:** açık pencere varken yeni isteği `resolve(false)` ile reddet ya da kuyrukla.
- **D13 · `admin/TeamManager.jsx:317-330` `toggleWeekly` iyimser güncelleme, ağ hatasında GERİ ALINMIYOR.** `request()`
  throw'unda catch yok → anahtar ekranda çevrilmiş kalır, tost yok; sunucuda değişmemiştir. | **Çözüm:** try/catch ile
  geri al + `toast.error`.
- **D14 · Yerelsiz saat biçimi.** `admin/SmtpLogView.jsx:264`, `admin/PushLogView.jsx:246` `updatedAt.toLocaleTimeString()`
  (tarayıcı yereli; EN arayüzde TR biçimi olabilir) — kardeş `httpmetrics/HttpExplorerToolbar.jsx:68` `dateLocale()` veriyor.
  Ayrıca sayı biçimleri `toLocaleString()` yerelsiz (SystemHealth :361, health/*, sql/*; TR "1.234" / EN "1,234" karışır).
- **D15 · `ui/KebabMenu.jsx:520` `label = 'İşlemler'` sabit Türkçe varsayılan (latent).** Bugün her çağıran `label`
  veriyor; yeni bir çağıran unutursa EN arayüzde Türkçe düğme adı. | **Çözüm:** `useT()` ile `t('tbl.actions')` varsayılanı.
- **D16 · (önceden var, BUG_RAPORU_6 E6#8) `incidenthistory/HistoryDetailSheet.jsx:139`, `HistoryList.jsx:49`
  `t('inc.cat' + r.category) || r.category`** — `t()` eksik anahtarda anahtarın kendisini döndürür, `||` dalı ölü;
  bilinmeyen kategori "inc.catXYZ" olarak basılır. | **Çözüm:** `const k = 'inc.cat'+c; t(k) === k ? c : t(k)`
  (`whonotified/PushDecisions.jsx:32-35 labelOr` deseni).

---

## Baseline RE-CHECK

| Kaynak | Madde | Durum |
|---|---|---|
| BUG_RAPORU_2026-09-23 | O13 SystemHealth watchdog kuşak | **ÇÖZÜLMÜŞ ✓** `SystemHealth.jsx:287-312` (`watchdogRef` + `fastPollRef.current === myTimer`) |
| 〃 | O16 HttpMonitorPage `selCheck` sekmede kalıyor | **ÇÖZÜLMÜŞ ✓** `HttpMonitorPage.jsx:791` |
| 〃 | O17 `aria-label` iç metni eziyor | **ÇÖZÜLMÜŞ ✓** `:821` (tarih + ayrıntı + eylem) |
| 〃 | O18 `.modal-shell-overlay` ölü `--modal-z` | **ÇÖZÜLMÜŞ ✓** kural App.css'te yok; ModalShell derinlik z-index'ini satır içi veriyor (`ModalShell.jsx:164-188`) |
| 〃 | O19 `onClick={h.reload}` | **ÇÖZÜLMÜŞ ✓** `CheckHistoryTab.jsx:675` |
| 〃 | O20 boş durumda dönen spinner | **ÇÖZÜLMÜŞ ✓** `:678-682` StatusBlock |
| 〃 | D3 ForecastDomainsPanel Z'siz `Date.parse` | **ÇÖZÜLMÜŞ ✓** `:98` `addDays` |
| 〃 | D6 `HttpErrorDetail onClose` ölü prop | **ÇÖZÜLMÜŞ ✓** (`:35` notu) |
| 〃 | D7 CRLF hayalet diff | **ÇÖZÜLMÜŞ ✓** dosya LF |
| 〃 | D8 canlı yenileme hatası sessiz | **ÇÖZÜLMÜŞ ✓** `:615-619` "bayat" rozeti |
| BUG_RAPORU_6 | E6#8 `t('inc.cat'+x) \|\| fallback` ölü dal | **AÇIK** → D16 |
| 〃 | E6#9 App.jsx global yoklama `useVisibleInterval` kullanmıyor | **AÇIK** `App.jsx:496, 530, 560` (arka plan sekmede 5 dk / 60 sn / 15 sn yoklama sürer — bilinçli olabilir; sessionPing için görünürlük şart) |
| 〃 | E6#10 efektten `.then` yükleyicilerde `.catch` yok | **AÇIK** → D1 (bayrak sızmıyor, geri bildirim yok) |
| BUG_REGRESYON_2026-09-28e | E1 kontrollü aralıkta sahte kırpma bandı | **ÇÖZÜLMÜŞ ✓** `CheckHistoryTab.jsx:382-386` |
| 〃 | E2 `incident` ≠ `alert` derin bağlantı | **ÇÖZÜLMÜŞ ✓** `OutageTimeline.jsx:83` `alertNavParams` |
| 〃 | E3 yenileme penceresi dar | **ÇÖZÜLMÜŞ ✓** `certHistoryModel.js:210-214` `prevT` |
| 〃 | E4 HistTile yalnız-hover | **ÇÖZÜLMÜŞ ✓** `HistTile.jsx:35-41` HintPopover |
| 〃 | E5 `toFixed(2)%` yerelsiz | **ÇÖZÜLMÜŞ ✓** `formatRatePercent` |
| 〃 | N1 `inv.expiresIn` tekil | **ÇÖZÜLMÜŞ ✓** `inv.expiresInOne` |
| 〃 | N2 `range` iki anlamlı (tasarım kokusu) | **AÇIK (ulaşılamaz)** — `alertHistoryModel.js:35` yalnız `active` kabul ediyor; CheckHistoryTab `range=7|30|custom` yazıyor. Kalıcı çözüm `h_range` öneki |
| BUG_REGRESYON_2026-09-29c | D-c13 sentetik toplu kontrol eşzamanlılığı sabit 1 | **AÇIK** `monitorCheckColumns.jsx:23-25` (kullanılabilirlik, bug değil) |

---

## Doğru bulunan (tarandı, temiz — yanlış pozitif üretilmedi)

- **App.jsx:** hareketsizlik zamanlayıcıları `idleCfg` ile yeniden kurulup temizleniyor; `loadAliveRef` StrictMode'da `[user]`
  efektinde geri kaldırılıyor; `handleTabChange` `PAGE_STATE_PARAMS`/`PREFIXES` süpürmesi + aynı sekmede `sm:tab-params`;
  `popstate`/`sm:navigate` dinleyicileri `[]` ile bir kez kurulup kaldırılıyor; `dashPager`/`useStatusFavicon` auth
  erken-return'lerinin ÜSTÜNDE (hook sırası); `dangerouslySetInnerHTML` yalnız sayısal `countdown`; tüm `useMemo`
  türetmeleri anahtarla bağımlı; `cardActions` memo'lu `openCertModal` `certsRef` üzerinden.
- **api/client.js:** `fetchWithTimeout` yalnız açılış çağrılarında (uzun uçlar etkilenmez); 401 yalnız `sm.session.active`
  varken yönlendiriyor; `withStatus` 409'u satır içi taşıyor; `toUtc` Z/ofset/yalnız-tarih üç durumu doğru;
  `isNocCoverageWrite`/`announceInventoryAdded` yalnız başarılı yazmada.
- **Bağlamlar:** Toast değeri `useMemo`'lu (memo'suz bağımlılık tuzağı yok; CertificateModal ayrıca ref'ten okuyor);
  Dialog odak iadesi `document.contains` korumalı; Permissions/Branding/TeamDirectory/UserDirectory/Toast/Dialog/Lang/Sidebar
  `globalThis` pinli.
- **Kancalar:** `useUrlQuerySync` (debounce + `pendingWrites` flush + söküm temizliği); `usePagination` (değer karşılaştırmalı
  reset, boş listede clamp yok, URL ps doğrulaması); `useServerPagination` (render'da reset, `apiBase` dönüşümü, toplam
  bilinmezken dokunmama); `useCheckRun` (`aliveRef` kurulumda true, `runningRef`, `finally`); `useNewDomainWarmup` (nesil +
  `isAlive`); `useCertDeepLink`/`useMonitorDeepLink` (mount'ta okuma, `seq`, bir-kez); `useVisibleInterval`; `useRunningChecks`;
  `useMonitorResume` (küme); `useDelayedFlag`; `useModalScrollHint`; `useIsWide`; `useSection` (yükleme durumu anahtardan türer).
- **Paylaşılan ui/:** ModalShell (sayaçlı scroll kilidi, derinlik z-index, `closeOnNavigate` yakalama evresi, odak iadesi);
  PaginationBar (0 kayıtta yok, >10 sayfada Git, `aria-current`); SearchableSelect/MultiTeamSelect (basışta seçim + `openRef`
  çift-seçim koruması); DateTimeRangePicker (prop→taslak DEĞER senkronu); TimeRangePicker (`from > to` Uygula kapalı);
  DateTimeField (`clearable` gerçek düğme); HintPopover/HelpTip (dokun-gör, `role=tooltip` sözleşmesi); TeamBadge `as="span"`;
  KebabMenu portal + `onCloseAutoFocus`; CopyButton timer temizliği; SimpleTooltip kendi sağlayıcısı; Progress/LoadingBlock
  `role=status`; PickerPopover Escape katmanı; MaintenanceBadge modül önbelleği; useNocState `useSyncExternalStore` deposu
  (nesil + görünürlük tazeleme).
- **Kullanıcı/takım yönetimi:** UserManager `loadSeq` + 300 ms uygulanan-süzgeç + seçim budama; TeamManager kimlikle müdür
  süzgeci; TeamMembersManager; TeamMembersModal `seq`/`callSeq` + eski veri korunumu; PermissionMatrix (bekleyen küme,
  sıralı PUT, kısmi hata, `beforeunload`, hassas onay); UserDetailPanel bölüm başına hata + yeniden dene; MembershipSource.
- **İzleme ortak parçaları:** MonitorDetail (`DetailTabs` bozuk `mtab` → ilk sekme, `useDetailTabCounts` `alive`);
  MonitorCard stretched-button + `CARD_LAYER`; MonitorCardActions `stopPropagation` sarmalayıcısı içinde Sil; MonitorPageHeader
  saniyelik sayaç yalnız çipte; CheckHistoryTab/useCheckHistory (`seqRef` üç dalda, canlı yenileme yalnız 1. sayfa/ön ayar,
  `reloadSignal` ilk sinyali atlar, `clampedFrom` kontrollü aralık, kart/tablo kap genişliğinden); OutageTimeline;
  HttpMonitorPage `load` try/catch, `save`/`runTest` finally, `deleteMonitor` onay + finally, `checkNow` `track` + işlevsel
  `setSelected` (8 kardeşte aynı iskelet — grep ile teyit).
- **CertificateModal:** T1–T4 tuzaklarının hepsi kapalı (bayrak guard'dan önce, `!domain` öncesi sıfırlama, `toastRef`/`tRef`,
  `sslSeq`, `sslError` döngü kırıcı, `tabCounts` `alive`); `closeOnNavigate`; `statusLabel` süresi dolmuş.
- **AlertHistory:** `loadSeq`, derin bağlantı `linkFetchSeq` + tekil uç, `view` anahtarı (ISSUE-002), 10 sn süre tiki yalnız
  açık alarm varken, gömülü kipte URL yazmıyor, seçim budama, gerekçeli pencere `submit` sözleşmesi. **Olaylar:** `reqIdRef` +
  stale-while-revalidate (`loaded.key`), özet `summarySeqRef`, iyimser yama + geri alma, derin bağlantı `action` tüketimi;
  IncidentDetailSheet `Promise.all(...catch)`; ActionNoteDialog `busyRef` + visualViewport dinleyici temizliği.
- **Sistem Sağlığı / Envanter / diğer sayfalar:** SystemHealth (`allSettled`, `dbSeq`, watchdog kuşağı, söküm temizliği);
  InventoryManager (`loadSeq`, kapsam bekleyen derin bağlantı, `teamsProp` senkronu, sabit `NO_TEAMS`); InventoryFormModal
  (`alive` guard'lar, 409 bandı, `setSaving` finally, ilk kontrol bayrağı ayrı); CertificatesTable (metin süzgeci 300 ms +
  `loadSeq` + `refreshKey` sessiz); UptimePage (`overviewSeq`, kontrollü aralık); CommandPalette (200 ms + `seq`, kapanışta
  `seq++`, kontrollü cmdk seçimi); Login (`lockout` geri sayımı, `readRemembered` try/catch); ActivityLog `loadSeq`; MyAuditLog;
  UserActivityPanel; UserDirectoryModal küme meşgul; NocCoveragePage (`loadSeq++` iyimser yamayı korur, geri alma);
  ExpiryForecastPage; WarningsPage (`allSettled` + `alive` + `seq`); StatsView; TodayPanel `useVisibleInterval`; useInbox;
  WeeklyReportsPage (`liveRef` ile bayat closure yok, `rid` yarışı, `sendBeacon` unlock, `pendingBackup.saved_at` sayı);
  ScriptedMonitorPage taslak zamanlayıcıları temizleniyor; TourOverlay/SecretTools/SqlPlayground/CheckRunShell/RateLimitBanner
  interval'leri temizleniyor.
- **Bugünkü diff (ortam adı):** `GeneralSettings.valueOf` `overridden`/`effective`/`effective_source` alanları arka uçla
  birebir snake_case (`AppSettingsService.java:163-172`); `isValidEnvName` ↔ `BuildInfo.ENV_NAME` deseni; `Field error` +
  `invalid` bağı; kaydette `invalidateVersionCache`; ToggleGroup `role=group` + `ToggleGroupItem role=button aria-pressed`
  (SHADCN.md §8.6 izinli sözleşme); i18n TR/EN 10 anahtar tam.
- **Sınıf taramaları (700 dosya):** `key={i}` yalnız iskelet/sabit listelerde (yeniden sıralanan veri listesi yok);
  `window/document.addEventListener` ↔ `removeEventListener` her dosyada dengeli (AlertHistory/CommandPalette/ActionNoteDialog
  tek satırda çift kaldırma; modül düzeyi `useNocState`/`NocCoverageBanner`/`useNocFormOptions` bilinçli tekil); sabit
  İngilizce `aria-label` 0; etkileşimli `div role=button` 0 (yalnız CopyButton/TeamBadge `span` — izinli); erken return
  sonrası hook 0 (kapsam analizli awk); `new Date(server_ts)` toUtc'siz 0 (Date nesneleri hariç); `useState(prop)`
  türetilmiş-durum yalnız DateTimeRangePicker (senkronlu) ve mount-tek `initial*`; recharts giriş dizileri `Array.isArray`/
  `toUtc`/`typeof` korumalı; ReactMarkdown 6 yüzeyde `rehype-raw` YOK; kartta sol renk şeridi 0 (`border-l-2` yalnız zaman
  çizelgesi ayraçları); dinamik `t(\`prefix.${x}\`)` 70 önekinin hepsi sözlükte; çok argümanlı `t()` çağrılarında TR/EN
  `{n}` paritesi tam (O4 dışında argüman < yer tutucu yok — `.replace('{0}',…)` ile elle dolduranlar dâhil); `whitespace-nowrap`
  yalnız kısa hücrelerde, sabit `min-w-[Npx]` 24 dosyada tablo/menü genişliği (kaydırmalı kapta, taşma üretmiyor —
  jsdom kanıtlamaz, e2e responsive kapısı otoritatif).

---

## Okundu ✓ (dosya bazında)

`App.jsx` ✓ · `main.jsx` ✓ · `api/client.js` ✓ (1-788 tam; 789-1497 grep + son 120 satır) · `components/ErrorBoundary.jsx` ✓ ·
`contexts/PermissionsProvider.jsx` ✓ · `contexts/BrandingProvider.jsx` ✓ · `hooks/*` (21 dosya) ✓ tam ·
`ui/Toast.jsx` ✓ · `ui/Dialog.jsx` ✓ · `ui/ModalShell.jsx` ✓ · `ui/PaginationBar.jsx` ✓ · `ui/HintPopover.jsx` ✓ · `ui/KebabMenu.jsx` ✓ ·
`ui/TeamBadge.jsx` ✓ · `ui/TeamDirectory.jsx` ✓ · `ui/UserDirectory.jsx` ✓ · `ui/SearchableSelect.jsx` ✓ · `ui/DateTimeField.jsx` ✓ ·
`ui/DateTimeRangePicker.jsx` ✓ · `ui/TimeRangePicker.jsx` ✓ · `ui/Field.jsx` ✓ · `ui/CopyButton.jsx` ✓ · `ui/HelpTip.jsx` ✓ ·
`ui/PickerPopover.jsx` ✓ · `ui/NotificationGroupSelect.jsx` ✓ · `ui/MaintenanceBadge.jsx` ✓ · `ui/MonitorModalActions.jsx` ✓ ·
`ui/CheckRunning.jsx` ✓ · `ui/SimpleTooltip.jsx` ✓ · `ui/Progress.jsx` (100-200) · `ui/TagInput.jsx` (1-90) · `ui/MultiTeamSelect.jsx` (1-60) ·
`ui/TeamMembersModal.jsx` (1-200) · `admin/UserManager.jsx` ✓ · `admin/UserEditModal.jsx` ✓ · `admin/MembershipSource.jsx` ✓ ·
`admin/TeamMembersManager.jsx` ✓ · `admin/TeamManager.jsx` ✓ · `admin/PermissionMatrix.jsx` ✓ · `admin/UserDetailPanel.jsx` ✓ ·
`admin/userdetail/{useSection.js,UserDetailHeader.jsx,OverviewTab.jsx,TeamsTab.jsx}` ✓ · `admin/GeneralSettings.jsx` ✓ ·
`admin/EnvironmentNameField.jsx` ✓ · `admin/AlertHistory.jsx` ✓ · `admin/SystemHealth.jsx` ✓ · `admin/InventoryManager.jsx` ✓ ·
`admin/AdminPanel.jsx` (1-80) · `admin/AdminSettings.jsx` (80-140) · `admin/RetentionSettings.jsx` (140-190) ·
`admin/TeamLdapAuditModal.jsx` (20-75) · `admin/NotificationGroups.jsx` (340-375) · `admin/HttpMetricsExplorer.jsx` (1-120) ·
`admin/httpmetrics/HttpExplorerToolbar.jsx` (55-80) · `admin/whonotified/PushDecisions.jsx` (1-120) ·
`admin/useractivity/UserActivityPanel.jsx` (1-140) · `admin/useractivity/UserDirectoryModal.jsx` (1-90) ·
`admin/alerts/AlertDetail.jsx` (1-130) · `admin/alerts/NocCallLog.jsx` (iskelet) · `admin/SecretTools.jsx` (195-230) ·
`admin/SqlPlayground.jsx` (155-185) · `inventory/InventoryFormModal.jsx` ✓ · `history/CheckHistoryTab.jsx` ✓ ·
`history/useCheckHistory.js` ✓ · `history/OutageTimeline.jsx` (70-180) · `CertificateModal.jsx` ✓ · `monitoring/MonitorDetail.jsx` ✓ ·
`monitoring/MonitorCard.jsx` (1-120) · `monitoring/MonitorPageHeader.jsx` (1-120) · `MonitorCardActions.jsx` ✓ · `IncidentsPage.jsx` ✓ ·
`incidents/IncidentDetailSheet.jsx` ✓ · `incidents/ActionNoteDialog.jsx` (70-95, 280-380) · `CommandPalette.jsx` ✓ · `pages/Login.jsx` ✓ ·
`CertificatesTable.jsx` ✓ · `UptimePage.jsx` ✓ · `Nav.jsx` (1-140) · `InboxBell.jsx` ✓ · `inbox/InboxPanel.jsx` (1-80) · `inbox/useInbox.js` ✓ ·
`TodayPanel.jsx` (1-110) · `HelpDrawer.jsx` (1-60) · `HelpPage.jsx` (dinleyiciler) · `ActivityLog.jsx` (1-260) · `DeviceHistoryPanel.jsx` (30-200) ·
`MyAuditLog.jsx` (30-110) · `RenewalPlanModal.jsx` (40-160) · `StatsView.jsx` (1-120) · `pages/WarningsPage.jsx` (60-200) ·
`pages/NocCoveragePage.jsx` (60-200) · `pages/ExpiryForecastPage.jsx` (40-150) · `pages/ForecastDomainsPanel.jsx` (50-98) ·
`HttpMonitorPage.jsx` (120-420) · `WeeklyReportsPage.jsx` (400-520, 540-580, 760) · `ScriptedMonitorPage.jsx` (395-430) ·
`tour/TourOverlay.jsx` (70-140) · `check/CheckRunShell.jsx` (100-125) · `check/monitorCheckColumns.jsx` (18-28) ·
`diagnostics/RateLimitBanner.jsx` ✓ · `noc/NocStatus.jsx` (1-80) · `noc/useNocState.js` ✓ · `utils/navigate.js` ✓ · `utils/localDay.js` ✓ ·
`utils/deleteInventory.js` (40-46) · `i18n/dateLocale.js` ✓ · `i18n/index.jsx` (`t()`/`LangProvider` + bugünkü diff) ·
`VersionPopover.jsx` / `releases/DeployBadges.jsx` (diff) · arka uç yalnız sözleşme teyidi: `AppSettingsService.java:163-172`,
`UserService.java:747-832`.

**Grep/betik ile taranan (satır satır okunmadı):** kalan dokuz izleme sayfası (`save/del/checkNow/runTest` iskeleti +
`.then/.catch` dengesi), `admin/*` ayar sayfaları (busy-bayrağı betiği + then/catch), `dbanalytics/*`, `httpmetrics/*`
(grafik guard'ları), `useractivity/*`, `weekly/*`, `scripted/*`, `stats/*`, `certmodal/*`, `domain/*`, `issues/*`,
`incidenthistory/*`, `renewal/*`, `maintenance/*`, `myactivity/*`, `noc/*`, `tour/*`, `palette/*`, `certcard/*`, `certtable/*`,
`responsechart/*`, `shadcn/*` (yalnız `button-group`/`toggle-group` sınıf taraması).

**Betikler (scratchpad, depoya yazılmadı):** `argcheck.cjs` — dengeli parantezle `t('k', …)` argüman sayısı ↔ TR/EN `{n}`
sayısı (O4'ü buldu; `.replace('{0}',…)` ile elle dolduran 11 çağrı elendi); `busyscan.cjs` — async gövdede `setX(true)…await…setX(false)`
ve `try` yok (O2/O3'ü buldu; zamanlayıcıyla sönen `setCopied` sınıfı elendi).

---

## Önerilen düzeltme sırası

1. **Y1** (`runNow → onSaved` ayrıştır; test): ucuz, veri kaybını kapatır.
2. **O4** (`t('inv.deleted', domain)` iki yer): tek satır, her kullanıcıya görünür.
3. **O2 + O3** (try/finally; kapıyı "temizlik finally'de" diye sıkılaştır): aynı sınıf, iki dosya.
4. **O1** (LDAP alanlarını salt okunur + ipucu): A2'nin sunucu kararıyla birlikte.
5. **D2, D13, D16** (yanlış ton / iyimser geri alma / ölü dal): birer satır.
6. **D3** (`useIsMobile` tembel başlangıç): tek satır, 10+ yüzeyde titremeyi keser.
7. **D1** sınıfı (eylemlere `catch → toast`, `main.jsx` `unhandledrejection`) + **D4, D5, D6, D7(A2 ile), D8, D9, D10, D14, D15**.

| # | Önem | Yer | Sınıf |
|---|---|---|---|
| Y1 | YÜKSEK | `inventory/InventoryFormModal.jsx:341` + `InventoryManager.jsx:827` + `App.jsx:1615` | onaysız form kapanışı / yazılan kaybı |
| O1 | ORTA | `admin/UserManager.jsx:748-820`, `admin/UserEditModal.jsx:139-200` | LDAP alanı düzenlenebilir → sessiz kayıp |
| O2 | ORTA | `DeviceHistoryPanel.jsx:92-99` (+130-186) | sızan yükleme bayrağı (try'sız) |
| O3 | ORTA | `admin/RetentionSettings.jsx:151-189` | sızan meşgul bayrağı (try'sız) |
| O4 | ORTA | `utils/deleteInventory.js:44`, `InventoryFormModal.jsx:363` | i18n ham `{0}` |
| D1–D16 | DÜŞÜK | yukarıda | catch'siz await sınıfı, ton, ilk çizim, gün sınırı, inline bileşen, boş sayfa, TEAM_ADMIN üyelik (A2), legacy label, tam yükleme, 401 derin bağlantı, latent ofset/dialog, iyimser geri alma, yerelsiz biçim, sabit TR varsayılan, ölü `\|\|` dalı |
