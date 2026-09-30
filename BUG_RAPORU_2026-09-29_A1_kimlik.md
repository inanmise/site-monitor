# BUG RAPORU 2026-09-29 — Eksen A1: Kullanıcı kimliği · Müdür · Takım üyeliği · LDAP alanlarının korunması

**Kapsam:** `D:\site-monitor` main @ 20.91.0 (+ commit edilmemiş "ortam adı ayarı" diff'i — bu eksene dokunmuyor).
Salt okunur kod denetimi; test/derleme/sunucu koşturulmadı. Kullanıcı kuralı: *"kullanıcıların manager'ı hatalı
olmamalı; kullanıcının takım bilgisi hatalı olmamalı; kullanıcılar LDAP üzerinden gelen bilgilerini değiştiremesin."*

**Yöntem:** Her aday bulgu kaynakta açılıp okunarak doğrulandı (grep tek başına bulgu sayılmadı); kardeş-yüzey
karşılaştırması ana kanıt olarak kullanıldı. Yüklü Hibernate sürümünün (7.4.1) `StandardSetSemantics` bytecode'u
`javap` ile incelendi (Y2'nin dayanağı). Gerçek sicil / kurum / kişi adı yazılmadı; örneklerde "Takım A/B",
"Kullanıcı X/Y", "Müdür M" kullanıldı.

**Genel değerlendirme:** LDAP → uygulama eşlemesinin çekirdeği (`LdapProvisioningService`, `ManagerLookup`,
`TeamMembershipSourceService`, `LdapMembershipService`) 2026-09-26 sonrası hâliyle sağlam: müdür sicili sırası,
`manager_id ↔ manager_sicil` tutarlılığı, çoklu sicilde bağ kurmama, bayat müdür tazeleme, özyinelemeli kaydın takım
miras almaması, yalnız LDAP kaynaklı üyeliğin budanması ve tanı uçlarının global-yönetici kapısı doğru. **Sistemik
açık yok; kusurlar YÖNETİCİ yazma yollarında** (`AdminController` → `UserService.updateUser`) ve müdür/üyelik
TÜRETMESİNİN kardeş yüzeylerde ayrışmasında toplanıyor. Üç YÜKSEK bulgu doğrudan "takım bilgisi / müdür bilgisi
hatalı olmasın" kuralını ihlal ediyor ve hiçbiri UI'da fark edilmiyor (kayıt "başarılı" görünüyor).

| Önem | Adet | Kısa |
|---|---|---|
| KRİTİK | 0 | — |
| YÜKSEK | 3 | Y1 org rolü sessiz silme, Y2 birincil takım kayması (HashSet), Y3 LDAP kullanıcısına parola sıfırlama kilidi |
| ORTA | 7 | O1 LDAP alanları yöneticiye yazılabilir, O2 AD e-posta → kontak bayat, O3 NOC↔UI müdür ayrışması, O4 elle müdür yüzeyler arası kayıp, O5 üye ekle/çıkar hedef kapısı yok, O6 kapsamlı müdür çok-takımlı astı düzenleyemez/başka takımdan düşürür, O7 Kullanıcılar ekranı yalnız birincil takım |
| DÜŞÜK | 9 | D1–D9 |

---

## Baseline RE-CHECK

| Kaynak | Madde | Durum | Kanıt |
|---|---|---|---|
| BUG_RAPORU_2026-09-23 **O4** | `manager_sicil` profil alanı kapısız `managerId` yazıyor (kapsamlı müdür kendini ast yapıp kapsam genişletiyor) | **ÇÖZÜLMÜŞ ✓** | `UserService.java:852-861` — yalnız `SessionScope.isGlobalAdminInRequest()` iken yazılır, aksi hâlde alan sessizce yok sayılır; bağ `ManagerLookup` ile (çoklu sicilde bağ yok). |
| BUG_RAPORU_7 **#5** | Takım rehberi beyaz-liste projeksiyonu (telefon/sicil yok) | **ÇÖZÜLMÜŞ ✓ (güncel karar dâhil)** | `TeamDirectoryController.project:112-130` — telefon/sicil/foto base64 yok; `system_role` + `has_photo` 2026-09-28 kullanıcı kararıyla eklenmiş. |
| PROD_DIAGNOSIS **Sebep 1** | Üye kartları müdür zincirini üye diye çiziyordu | **ÇÖZÜLMÜŞ ✓** | `TeamMemberCards.jsx:165-173` yalnız `members`; `TeamManager.jsx:586-594` `usersById` bilerek verilmiyor; `TeamMembersModal.jsx:193-198` müdür yalnız başlık çipi, sayaç `members.length`. |
| PROD_DIAGNOSIS **§5** | `manager_id` ↔ `manager_sicil` ayrışması (eski müdürde asılı kalma) | **ÇÖZÜLMÜŞ ✓** | `LdapProvisioningService.resolveManagerLink:428-445` — sicil çözülemezse `managerId=null`, AD'de müdür yoksa ikisi de temizlenir; test `LdapTeamMembershipProvisioningTest:262,277`. |
| PROD_DIAGNOSIS **§5** | Aynı sicilli iki kullanıcıda istisna (girişte "hatalı parola") / elle sicil 500 | **ÇÖZÜLMÜŞ ✓** | `AppUserRepository.findAllByEmployeeIdNormalized:36-37` liste döner; `ManagerLookup.pick:39-57` belirsizlikte bağ kurmaz + uyarı. |
| PROD_DIAGNOSIS **§4/§8** | LDAP üyelikleri hiç silinmiyor / kaynak izi yok / LDAP değişikliği denetimsiz | **ÇÖZÜLMÜŞ ✓** | `applyTeams:272-322` (prune yalnız `LDAP_SOURCES`), `UserTeamSource`, `auditLdapChange:482-507` (`USER_LDAP_SYNC`, `via=MANAGER_OF:<ast>`). |
| Bellek: scoped-admin gate trap | AD'ye giden tanı uçları müdüre kapalı; rol atama kısıtı `!isAdmin` | **ÇÖZÜLMÜŞ ✓ / korunuyor** | `LdapMembershipController.requireGlobalAdmin:147-151`; `AdminController.createUser:2977-2996`, `applyUserUpdate:3104-3121` (`requireAssignableRole`, `requireCanAdministerTarget`). |
| Bellek: ürün kararı | Takım Müdürü TEK kişi, üye listesine karışmaz; elle atama kazanır "(elle)" | **UI'da doğru; yüzeyler arası tutarsız → O3, O4 (YENİ)** | aşağıda |

---

## AÇIK BULGULAR

### YÜKSEK

**Y1 · `AdminController.java:2854` / `:2875` / `:3129-3136` → `UserService.java:777-781` — üye ekle/çıkar ve toplu işlemler kullanıcının ORG ROLÜNÜ siler ve kilitler**
`UserService.updateUser` için `orgRole == null` "dokunma" değil **"temizle"** demek (`:777-781`; `UserServiceTest:788`
"null orgRole → clears field" ile PİNLİ). Ama üç çağıran null geçiyor:
- `addTeamMember:2854` ve `removeTeamMember:2875`: `updateUser(userId, null, null, null, null, ids, null, null)`.
- `bulkUsers` → `applyUserUpdate` (`:3136` `(String) body.get("org_role")`): `activate` / `deactivate` / `assign_team`
  patch'lerinde `org_role` anahtarı yok → null.
`AdminControllerTest:1624,1631` çağrıyı `isNull()` ile doğruladığı için (servis mock) kusur testlerde görünmez.
| Neden bug: Takımlar → "Üyeleri yönet" → Kullanıcı X'i (AD'den `org_role=TECH`) Takım A'ya ekle → X'in `org_role`
null olur ve `org_role_locked=true` konur; LDAP bir sonraki girişte düzeltemez (`applyOrgRole:208` kilitliyken
dokunmaz). Sonuçlar: push alıcı çözümü "NO_ORG_ROLE" (`UserPushRecipientResolver.explain:148`) → X artık push almaz;
`teamManager.js:101` `NON_MANAGER_ROLES` süzgeci rolü null olan bir PO'yu artık elemez → PO Takım Müdürü adayı olur
(ürün kararı ihlali); toplu "pasifleştir/aktifleştir" 200 kullanıcının org rolünü tek tıkla siler. Yönetici hiçbir uyarı
görmez (kayıt "başarılı").
| Çözüm: (a) `updateUser` sözleşmesini ayır: `orgRole == null` → dokunma, boş dize → temizle (`applyUserUpdate`
zaten `""` üretebiliyor: `:3042` `set_org_role`) ve `UserServiceTest:788`'i buna göre değiştir; ya da (b) `addTeamMember`
/ `removeTeamMember` / bulk yollarında `u.getOrgRole()`'ü aynen geçir. Kapı: `AdminControllerTest.teamMembers` gerçek
`UserService` ile (mock değil) `org_role` ve `org_role_locked` değişmedi iddiası; bulk `activate` için aynı.

**Y2 · `UserService.java:714-717` (`applyTeams`) + `:765` + `AppUser.java:118-124` + `UserEditModal.jsx:73-74` / `UserManager.jsx:315-316` / `AdminController.java:2851-2854, 2868-2875` — birincil takım (`team_id`) sırasız kümenin "ilk" elemanından türetiliyor; her yönetici kaydı birincil takımı kaydırabilir**
`updateUser` `teamIds != null` olduğunda değişiklik olmasa bile `applyTeams(user, next)` çağırır ve `teamId =
next.iterator().next()` yazar (`:714-717`). `next` istemcinin gönderdiği `team_ids` sırasıdır; istemci bu diziyi
sunucunun entity JSON'undan (`user.team_ids`, `UserEditModal.toForm:241`) alır ve `team_id: team_ids[0]` gönderir
(`:74`; `teamIdsFromBody:3845-3849` `team_id`'yi zaten yok sayar). `AppUser.teamIds` `@ElementCollection Set<Long>`;
alan `LinkedHashSet` ile başlasa da Hibernate yüklerken **`PersistentSet` + `HashSet`** kullanır — doğrulandı:
`hibernate-core-7.4.1.Final` `StandardSetSemantics.instantiateRaw` → `new HashSet` / `CollectionHelper.setOfSize`
(`javap`). Yani JSON'daki `team_ids` sırası hash sırasıdır (küçük id'lerde artan id), birincil-önce DEĞİL.
`addTeamMember:2851-2854` / `removeTeamMember:2868-2875` de `new LinkedHashSet<>(u.getTeamIds())` ile aynı hash
sırasını alıp `updateUser`'a veriyor.
| Neden bug: Kullanıcı X üyelikleri {5, 9}, birincil 9 (AD'de ilk grup 9). Yönetici X'in yalnız görünen adını
düzeltir → form `team_ids=[5,9]` gönderir → `changed=false` (kilit yok, iz yok) ama `team_id=5` olur. Ya da yönetici
X'i Takım 12'ye ekler → `ids=[5,9,12]` → `team_id=5`. Etkileri: Nav/`/me` birincil takımı değişir (`refreshTeamScope`
DB'den okur), `requireTeamScopedAdmin(target.getTeamId())` artık başka takımın yöneticisini geçirir (`:3077,3151,
3181…`), `searchUsers` (O7), haftalık rapor müdür alıcıları (D2), `ensureManagerEscalationContact(u.getTeamId())`,
denetim `actorTeamId`. LDAP kullanıcısında bir sonraki girişte AD-ilk-grup geri yazar (`applyTeams:305-306`) — kilitli
ya da yerel kullanıcıda kayma KALICI. `UserServiceTest:730-742` birincili iddia etmiyor; `:715-727` `LinkedHashSet`
fixture'ıyla şans eseri geçiyor (üretimde koleksiyon `HashSet`).
| Çözüm: `updateUser`'da `applyTeams` yerine "birincil hâlâ kümedeyse KORU, değilse ilk eleman" (`if (!next.contains(
user.getTeamId())) user.setTeamId(first)`); `addTeamMember`/`removeTeamMember` `ids`'i `u.getTeamId()` önde kuracak
şekilde oluştur; istemci `team_id`'yi açıkça göndersin ve sunucu `team_ids` ile birlikte gelen `team_id`'yi kümedeyse
birincil olarak uygulasın (`teamIdsFromBody`'ye ek parametre). Kapı: `UserServiceTest` "aynı küme farklı sırada →
birincil değişmez" + `AppUser.teamIds` için `@OrderColumn`/`@OrderBy` KULLANMA kararını yorumla pinle (sıra semantik
taşımasın).

**Y3 · `AdminController.java:3145-3174` + `UserService.java:951-991` + `AuthController.java:164-177` + `UserManager.jsx:486` / `AdminAutoResetModal.jsx` — LDAP kullanıcısına "parola sıfırla" uygulanabiliyor; 24 saat sonra AD girişi reddediliyor**
`autoResetPassword` ve `adminAutoResetPassword` `authSource` kontrol etmiyor; LDAP satırına da `passwordHash=temp`,
`mustChangePassword=true`, `tempPasswordExpiresAt=+24h` yazıyor. `login:164` `isTempPasswordExpired(user)` AD yolundan
gelen kullanıcıya da uygulanıyor. Menü (`UserManager.jsx:486`) ve modal LDAP rozeti olan satırda da eylemi sunuyor
(bileşende `auth_source` kapısı yok — grep boş).
| Neden bug: Yönetici LDAP kullanıcısı X için "parola sıfırla"ya basar (X kilitlendi sanıp). X ertesi gün AD
parolasıyla girer → bind başarılı → `TEMP_PASSWORD_EXPIRED` 401: "Temporary password expired. Ask your admin to reset
again" — yeniden sıfırlama 24 saat daha ertelemekten başka şey yapmaz; kalıcı çözüm X'in geçici parolayı "mevcut
parola" diye girip yerel parola koyması (`changeOwnPassword:547` → hash eşleşir). Böylece LDAP kullanıcısında yerel
hash oluşur; LDAP kapatıldığında `login:155` bu hash'le girişe izin verir (AD dışı kimlik yolu). Ayrıca 24 saat
boyunca `mustChangePassword` tüm `/api/**`'ı 403'e düşürür (`AuthInterceptor:43-47`).
| Çözüm: `adminAutoResetPassword` (ve `changePassword(target…)`) `"LDAP".equalsIgnoreCase(authSource)` ise 400 (`Msg.t`
"AD hesabının parolası uygulamada yönetilmez"); `login`'de `isTempPasswordExpired` yalnız yerel yolda; menü/modal LDAP
satırında eylemi gizlesin (`u.auth_source === 'LDAP'`); açılış yaması: LDAP satırlarında `must_change_password`,
`temp_password_expires_at`, `password_hash` temizle (idempotent). Kapı: `AdminControllerTest` LDAP hedefe 400 +
`UserServiceTest`.

### ORTA

**O1 · `UserService.java:750-752` + `:834-842` (`applyProfileFields`) + `AdminController.java:3129-3139` + `UserEditModal.jsx:139-200` / `UserManager.jsx:812-820` — LDAP kullanıcısında AD-kaynaklı alanlar sunucuda yazılabilir, UI uyarısız, bir sonraki girişte sessizce geri döner**
Kullanıcının KENDİ ucu yok (`/api/me` yalnız push-opt-out / tour / parola — temiz). Ama yönetici formu LDAP satırında
`display_name, email, employee_id, first/last_name, title, phone, department, company_level, mudurluk_name,
manager_sicil` alanlarını düz düzenlenebilir sunuyor (yalnız kod yorumu "sonraki login'de tazelenir" diyor); sunucu
kabul ediyor; `LdapProvisioningService.upsert:136-148` girişte hepsini AD'den yeniden yazıyor.
| Neden bug: Yönetici LDAP kullanıcısı X'in e-postasını düzeltir → `updateUser:786-790` X'e bağlı eskalasyon
kişilerinin e-postasını da yeni değere çeker → X girer → `u.setEmail(mail)` eski AD değerine döner, KONTAK ise yönetici
değerinde kalır (bkz. O2) → kullanıcı ve kontak farklı adres. `employee_id` düzeltmesi girişe kadar `ManagerLookup`'ı
(sicil→kişi) etkiler. Beklenen kural: LDAP kaynaklı alanlar LDAP kullanıcısı için sunucuda salt okunur; yalnız
belgelenmiş ezmeler (`teams.manager_id`, MANUAL üyelik, rol/org rol kilitleri).
| Çözüm: `applyUserUpdate`'te `"LDAP".equalsIgnoreCase(target.getAuthSource())` ise AD alanlarını yok say (customHeaders
deseni) ya da 400; `UserEditModal`/`UserManager` LDAP satırında bu alanları `disabled` + "AD'den gelir" ipucu; alanların
listesi tek kaynakta (`LdapProvisioningService.AD_FIELDS`) ve kapı `AdminControllerTest` "LDAP hedefte email/employee_id
değişmez".

**O2 · `LdapProvisioningService.java:136-145` vs `UserService.updateUser:786-790` + `AdminController.applyContactFields:1877-1885` — AD'den e-posta/ad değişince `user_id` bağlı eskalasyon kişisi tazelenmiyor**
`EscalationContact.userId` (`:24-26` "name/email are derived from the user on save"); yönetici düzenlemesi kontakları
senkronlar, LDAP girişi senkronlamaz.
| Neden bug: Kullanıcı X'in AD e-postası değişir (soyadı değişimi). Girişte `app_users.email` güncellenir; X'i kişi
olarak taşıyan takımların `escalation_contacts.email` eski adreste kalır → alarm/eskalasyon/haftalık rapor onay
maili ölü adrese gider; UI'da kişi "X" adıyla göründüğü için fark edilmez.
| Çözüm: `upsert` sonunda e-posta/ad değiştiyse `contactRepo.findByUserId(u.getId())` ile aynı senkron (updateUser'daki
blok ortak yardımcıya taşınsın); `LdapProvisioningServiceTest` "mail değişti → kontak e-postası güncellendi".

**O3 · `NocCallListService.java:210-239` (`adChainManager`) vs `utils/teamManager.js:97-112` — 7/24 (NOC) postasındaki Takım Müdürü ile Takım Yönetimi'ndeki farklı kişi olabiliyor**
Javadoc "utils/teamManager.js kuralının sade hâli" diyor; dört fark var: (a) NOC üye olan adayı düşürür
(`memberIds.contains(mid)`), UI düşürmez; (b) UI PO/TECH rollü adayı eler (`NON_MANAGER_ROLES`), NOC elemez; (c) UI
yalnız PO/lider kalınca bir kademe yukarı çıkar, NOC çıkmaz; (d) UI `manager_sicil` tekil eşleşmesini de sayar, NOC
yalnız `manager_id`.
| Neden bug: `teamManager.test.js:31` senaryosu ("müdür takım üyesi ve bölüm başkanına bağlı → müdür"): Takım A'nın
üyeleri Müdür M'ye bağlı, M de AD grubuyla A'nın üyesi. UI: "Takım Müdürü: M". NOC postası: M üye diye elenir, M'nin
`manager_id`'si (bölüm başkanı B) tek aday → "Takım Müdürü: B" (+ telefonu) → gece nöbetçi yanlış kişiyi arar.
| Çözüm: Müdür türetmesini TEK backend kaynağına al (`TeamManagerResolver`: elle `manager_id` → aynı kural), UI
`resolveTeamManagerEntry` bunu tüketsin ya da en azından `adChainManager` dört farkı kapatsın; kapı: `teamManager.test.js`
senaryolarının Java ikizi (`NocCallListServiceTest`).

**O4 · `TeamDirectoryController.java:47-57, 96-108` + `teamMembersModel.js:142-160` (`resolveModalManager`) + `TeamManager.jsx:149-152, 182-187` — elle atanmış müdür yüzeyler arasında kayboluyor / sicil dizesine düşüyor**
Rehber ve üye ucu `manager_id` döner ama müdürün ADINI döndürmez. `resolveModalManager` müdür üye değilse ve hiçbir
üyenin `manager_id`'si ona eşit değilse sessizce ÜYELERDEN TÜRETMEYE düşer (`:148-157`) — "(elle)" işareti ve kişi
değişir. Takım Yönetimi'nde `users` = `/admin/users` (KAPSAM süzgeçli, `:151` yalnız aktif); `teamManagerEntry:184`
`userMap[manual]` yoksa türetir; `managerLabelFor:173` çözülemeyen `manager_id`'de `manager_sicil` metnini basar.
| Neden bug: Takım A'ya yönetici, matris müdürü M'yi elle atar (M üye değil, kimse M'ye bağlı değil). Yönetim
Paneli: "M (elle)". Panodaki TeamBadge → üye penceresi: türetilmiş başka kişi ya da "müdür yok". Kapsamlı yönetici
(AD ADMIN) Takım Yönetimi'ni açar: M kapsamı dışında → `users`'ta yok → türetilmiş kişi; üyelerin müdürü kapsam dışı
olduğunda sütunda çıplak sicil numarası görünür (kişisel veri sızıntısı değil ama "müdür hatalı" algısı).
| Çözüm: `/api/teams/directory` ve `/{id}/members` takım satırına `manager_display_name` ekle; `resolveModalManager`
`managerId` doluysa ad çözülemese bile türetmeye DÜŞMESİN (`{label: manager_display_name ?? '#id', manual:true}`);
`TeamManager` müdür çözümü için `/users/directory` (kurum-geneli ad haritası) kullansın.

**O5 · `AdminController.java:2842-2858` (`addTeamMember`) / `:2861-2879` (`removeTeamMember`) — hedef kullanıcı için görünürlük ve `requireCanAdministerTarget` kapısı yok**
Kapı yalnız TAKIM üzerinde (`requireTeamManageScope`). Kardeşler: `applyUserUpdate:3077-3079`, `autoResetPassword:
3151-3153`, `deleteUser:3249-3251` hedefi kontrol eder; `LdapMembershipController.requireCanView:154-161` ve
`isPhotoViewable:2765-2771` görünürlük ister. `users.crud/edit` TEAM_ADMIN'e varsayılan verili (`PermissionCatalog:218`).
| Neden bug: TEAM_ADMIN (Takım A lideri) `POST /admin/teams/A/members {user_id: <global ADMIN id>}` → global
yöneticinin üyeliği değişir, `team_locked=true` olur (LDAP kullanıcıysa AD artık üyeliğini yönetmez), Y1 org rolünü
siler, Y2 birincil takımını kaydırabilir; ardından hedef `listUsers.inScope` ile A liderinin görüş kapsamına girer
(tam entity: telefon, sicil, müdür sicili). UI aday listesi kapsamla sınırlı ama API değil.
| Çözüm: iki uca `requireCanAdministerTarget(session, u)` + hedefin çağıranın görüş kapsamında olması
(`requireCanView` deseni; dışı 404); `AdminControllerTest` kapsamlı oturumla ADMIN hedefe 403.

**O6 · `AdminController.java:3117-3120` + `UserEditModal.jsx:73` (her kayıtta `team_ids`) — kapsamlı müdür çok-takımlı astını düzenleyemiyor; düzenleyebilmek için diğer takımın üyeliğini düşürmek zorunda**
Müdür dalında `requestedTeams != null` ise HEPSİ yönetim kapsamında olmalı; form üyeliği değiştirmese de tam kümeyi
gönderir.
| Neden bug: Müdür M Takım A'yı yönetir; Kullanıcı X = {A (birincil), B}. M yalnız X'in ünvanını düzeltmek ister →
403 "Cannot assign a team outside your management scope: B". M formdan B'yi çıkarırsa kayıt geçer → X'in B üyeliği
(B yöneticisinin haberi olmadan) silinir, `team_locked=true`. Aynı işlem `DELETE /teams/B/members/X` ile yapılsa
`requireTeamManageScope` 403 verirdi — iki uç ayrışık.
| Çözüm: müdür dalında yalnız DEĞİŞEN üyelikleri (eklenen ∪ çıkarılan) kapsamda ara; değişmeyen kapsam dışı üyelik
korunur. Kapı: `AdminControllerTest.scopedAdminSession` ile "aynı küme → 200, B eklenmesi/çıkarılması → 403".

**O7 · `AdminController.java:2945` (`searchUsers`) vs `:2922-2925` (`listUsers`) — Kullanıcılar ekranı kapsamlı roller için yalnız BİRİNCİL takımın kullanıcılarını listeliyor**
`effTeamId = teamId(session)` (oturumun birincil takımı); `listUsers`/`listTeamUsers`/`TeamManager` ise tam görüş
kapsamını (`viewTeamIds`) kullanır. Yorum tek-takım dönemine ait ("TEAM_ADMIN yalnız kendi takımını görür").
| Neden bug: TEAM_ADMIN iki takımın lideri (`ledPlusOwnTeamIds`) ya da kapsamlı müdür {A, B}: Kullanıcılar sekmesi
yalnız birincil takımın kullanıcılarını gösterir, B'nin kullanıcısı bulunamaz; oysa `PUT /users/{id}` B'deki
kullanıcıyı düzenlemesine izin verir ve Takım Yönetimi onları listeler. Y2 birincil kaydırırsa liste bir gecede
"değişir".
| Çözüm: `searchUsers` kapsamlı rolde `viewTeamIds` kümesini süzsün (`findFiltered`'a `teamIds IN` varyantı;
istemci `teamId` süzgeci kümeyle kesiştirilsin).

### DÜŞÜK

- **D1 · `LdapMembershipService.java:150-152`** — `on_login` tahmini `PRUNE_KEY` ayarını okumuyor: ayar kapalıyken
  LDAP kaynaklı üyelik için "REMOVE" der, gerçek giriş (`applyTeams:290-298`) KEEP yapar. Tanı ekranı yanlış
  öngörü. Çözüm: `appSettings.getBoolean(PRUNE_KEY, true)` ile aynı dal.
- **D2 · `WeeklyReportService.java:821` (`resolveTeamManagerUsers`), `:838`, `:1359`, `WeeklyAvailabilityReportService.java:998`, `AdminController.java:2896`** — üyelik yüklemi `findByTeamIdOrderByUsernameAsc` / `findByTeamIdAndOrgRoleAndActiveTrue` = YALNIZ birincil takım; projedeki diğer 8 yüzey `team_id ∪ team_ids` (`findMembersOfTeams`). Ek üyelikle bağlı üyelerin müdürü/PO'su sayılmaz (MANAGER kontağı yoksa `MANAGER_CONTACT_MISSING` 409), takım silme denetimi `member_count`'u eksik yazar. "Tüm müdürler" kuralı bilinçli (bellek), yüklem farkı değil. Çözüm: `findMembersOfTeams(List.of(teamId))` + aktif süzgeci.
- **D3 · `UserService.java:993-1005` (`deleteUser`)** — `teams.manager_id` ve astların `manager_id`'si temizlenmiyor. Elle müdür silinince Takım Yönetimi sessizce türetilene düşer ("(elle)" kaybolur, kolon dolu kalır); astlarda `managerUserOf` çözülemeyip sicil dizesi görünür (LDAP astında girişte kendini onarır, yerel astta kalıcı). Çözüm: `deleteUser`'da `teamRepo.findByManagerId` → null, `userRepo.findByManagerId` → null (+ denetim detayına yaz).
- **D4 · `AuthController.java:383-436` (`/me` `auth_source` döndürmüyor) + `NavUser.jsx:205/260`** — LDAP kullanıcısına "Parolayı değiştir" menüsü sunuluyor; `changeOwnPassword:547` → `changePassword(…, currentPwd)` → `matches(currentPwd, null)` her zaman false → "Invalid admin password". Kullanıcı AD parolasını "yanlış" sanır. Çözüm: `/me`'ye `auth_source`, menüde gizle, sunucuda LDAP için 400 (Y3 ile aynı kapı).
- **D5 · `AdminController.java:3035-3039`** (`assign_team`) — "Takıma ata" (`usr.bulkAssignTeam`) tek takımlı küme yazar: çok-takımlı kullanıcının diğer üyelikleri sessizce düşer (+ Y1 org rolü). Tasarım kararı olabilir → **doğrulanmalı**; en azından etiket "Yalnız bu takıma taşı" ya da "Takıma ekle" seçeneği.
- **D6 · `UserService.java:762-766`** — `changed` yalnız `teamIds`'e bakar, `before` birincili içerir: `legacy_primary_only` satırda (üyelik satırı olmayan birincil; `LdapMembershipService:70-71` bunu ayrıca gösteriyor) aynı takımı içeren form kaydı `changed=true` → gereksiz `team_locked=true`, iz yazılmaz. Açılış yaması (`SchedulerService:1187-1190`) çoğu satırı doldurduğu için nadir.
- **D7 · `LdapProvisioningService.upsert` (giriş, `@Transactional`) ↔ `AdminController.applyUserUpdate`** — aynı kullanıcı için eşzamanlı giriş senkronu + yönetici kaydı: iki yol da entity'yi yükleyip `save` eder, `app_user_teams` ElementCollection'ı sil+yaz; son yazan kazanır (kilit/iz kaybı) ya da PK çakışması. `changed` guard'ı pencereyi daraltıyor. **Doğrulanmalı** (yük testi); çözüm `@Version` optimistic lock.
- **D8 · `TeamAdminService.stats:90-95` (aktif+pasif) vs `TeamDirectoryController.members:78` / `NocCallListService.activeMembers` (yalnız aktif)** — Takım satırındaki "N üye" sayacı pasif hesapları sayar, tıklanınca açılan pencere saymaz → sayaç ≠ liste. Çözüm: sayaçta `active` süzgeci ya da "N (M pasif)".
- **D9 · `AuthInterceptor.java:120-123` (remember-me yeniden kimlikleme)** — `populateSession` DB'den; AD tazelemesi yalnız parola girişinde. Beni-hatırla ile 7 gün dönen kullanıcının AD'deki müdür/takım değişikliği uygulamaya yansımaz (bilgi; kabul edilebilir, belgelensin).

---

## Denetim maddeleri 1–5

**1. Müdür doğruluğu — backend zinciri TEMİZ, türetme kardeşleri BULGU.**
`managerSicilOf` sırası ayardan + geçersiz nitelik süzgeci (`:384-416`) ✓; `manager_id` her zaman sicille tutarlı ya da null (`:428-445`) ✓; çoklu sicilde bağ yok (`ManagerLookup`) ✓; bayat müdür tazeleme yalnız aynı hesapsa (`refreshManager:457-470`) ✓; özyinelemeli kayıt astın takımını almaz (`:166-168`, test H2) ✓; kendi kendine müdür (`filter(id -> !id.equals(u.getId()))`) ✓. UI `resolveTeamManagerEntry`: adaylar yalnız üyeler (`isTeamMember`), lider/PO/TECH elenir, zincir eleme, öncelik, sicil tekil eşleşme ✓; elle `teams.manager_id` UI'da kazanır ✓. **Bulgu:** O3 (NOC farklı kişi), O4 (elle müdür yüzeyler arası), D3 (silinen müdür), Y1 (org rolü silinince PO/TECH süzgeci bozulur). Müdür LDAP'ta değişince: sicil çözülemese bile eski müdür düşer ✓; eski müdürün TEAM_ADMIN rolü kendi bir sonraki girişinde `existsByManagerId` false ise USER'a iner ✓ (oturumu o zamana kadar eski kapsamla sürer — kabul). Pasif müdür: backend `resolveManager`/`adChainManager` pasifi eler, UI `TeamManager` `users` aktif süzgeçli ✓ (tutarlı).

**2. Takım üyeliği — LDAP senkron/budama TEMİZ, yönetici yazma yolları BULGU.**
`team_id ↔ team_ids ↔ app_user_team_sources`: LDAP yolu üçünü birlikte yazar (`applyTeams:304-321`) ✓; `createUser`/`updateUser` MANUAL iz + kilit ✓; `moveAll` TEAM_MOVE iz + kilit + hedefte mevcut izi ezmez ✓; `deleteUser` izleri siler ✓; `deleteTeam` `existsByTeamId || existsByMembershipTeamId` + `impact.empty` ✓. **Ayrışma yolları:** Y2 (birincil sırasız küme), Y1 (org rolü yan etki), O5/O6 (kapsam), D5/D6. AD grup → takım: `OU=ScrumGroups` RDN tam eşleşme, `Onaycı` süzgeci, birebir ad (harf katlama yok — bilinçli), `company` yedeği ✓; budama yalnız `LDAP_*` (`PRUNE_KEY`), MANUAL/LEGACY korunur ✓; birincil = AD'deki ilk grup ✓. Kullanıcı adı normalizasyonu (`normalizeUsername` BÜYÜK, `Locale.ROOT`) ve DB tarafı `UPPER` ✓. `viewTeamIds`/`manageTeamIds`/`memberTeamIds` `ownTeamIds` (team_id ∪ team_ids) ve astların takımları ✓; `/me` `refreshTeamScope` rol aynıysa tazeler ✓. TeamBadge/dizin/üye penceresi/üye yönetimi/AD denetimi/NOC aynı yüklemi (`team_id ∪ team_ids`) kullanır ✓ — istisnalar D2 (haftalık rapor), D8 (sayaç aktif/pasif), push (`findByMembershipTeamId` yalnız üyelik tablosu, `MISSING_MEMBERSHIP` tanısıyla bilinçli).

**3. LDAP alanlarının korunması — kullanıcı ucu TEMİZ, yönetici ucu BULGU.**
AD'den gelen alanlar (`upsert:136-148`): mail, cn→employee_id, givenName, sn, displayName, title, mobile, department, description→company_level, thumbnailPhoto, extensionAttribute5→müdürlük, org_role (kilitsizse), system_role (kilitsizse; ADMIN/AUDIT düşürülmez), takımlar (kilitsizse), manager_sicil/manager_id. Kullanıcının kendi profil ucu YOK — `/api/me/*` yalnız `push-opt-out`, `tour`, `change-password`, cihazlar ✓ (IDOR yüzeyi yok). `auth_source` hiçbir uçtan değiştirilemez ✓ (yalnız `upsert` yeni kayıtta). Kullanıcı CSV/toplu içe aktarma yok ✓. **Sapmalar:** O1 (yönetici LDAP alanlarını ezebilir, girişte sessiz geri dönüş), O2 (kontak asimetrisi), Y3/D4 (LDAP'ta parola), Y1/Y2 (dolaylı). Kapsamlı müdür IDOR: `PUT /users/{id}` `requireTeamScopedAdmin` + `requireCanAdministerTarget` + `requireTeamsInManageScope` ✓; `addTeamMember` O5.

**4. Gösterim tutarlılığı — aynı kullanıcı, farklı yüzey, farklı müdür/takım MÜMKÜN (O3, O4, O7, D2, D8).**

| Yüzey | Üyelik veri kaynağı | Müdür veri kaynağı |
|---|---|---|
| Kullanıcılar listesi (`/admin/users/search`) | AppUser entity; kapsamlı rolde YALNIZ birincil takım (O7) | `manager_sicil` metni |
| Kullanıcı detayı → Takımlar | `/admin/users/{id}/team-membership` (`ownTeams` + iz) | `manager_sicil` (ad çözümü yok) |
| Kullanıcı detayı → Dizin (AD) | canlı AD (`check`) | ea4/manager adayları + DB (`managerCheck`) |
| Kullanıcı Dizini (`/users/directory`) | — | — |
| TeamBadge → üye penceresi (kurum-geneli) | `/api/teams/{id}/members` (aktif, team_id ∪ team_ids) | `teams.manager_id` (üye ya da astı varsa) → üyelerden türetme (O4) |
| Takım Yönetimi sütunu | `/admin/users` (kapsam + aktif) `isTeamMember` | `teams.manager_id` (kapsamda ise) → `resolveTeamManagerEntry` (kapsam+aktif kullanıcılar) |
| Takım → Üyeleri yönet | `/admin/teams/{id}/users` (aktif+pasif) | — |
| Takım → AD ile üyelik denetimi | `findMembersOfTeams` (aktif+pasif) | Takım Yönetimi'nin `managerInfoFor`'u |
| Takım satırı sayacı | `stats` (aktif+pasif) (D8) | — |
| 7/24 arama listesi / NOC postası | `findMembersOfTeams` + aktif | `manager_id` (aktif) → MANAGER kontak e-postası → `adChainManager` (O3) |
| Haftalık rapor onay/push müdürü | `findByTeamIdOrderByUsernameAsc` (yalnız birincil, aktif) (D2) | `Team.managerId` (aktif) → MANAGER kontak → üyelerin TÜM `manager_id`'leri (bilinçli) |
| Push alıcıları | `findByMembershipTeamId` (yalnız `app_user_teams`) + `MISSING_MEMBERSHIP` tanısı | — |

**5. Tanı araçları — TEMİZ (D1 hariç).**
`GET users/{id}/team-membership` kapsam görünürlüğü (`requireCanView`) ✓; `ldap-check` / `ldap-resync` /
`teams/{id}/ldap-check` / `teams/{id}/ldap-resync` yalnız global admin (`isGlobalAdmin`, kapsamlı müdür 403) + matris
izni ✓ (`LdapMembershipControllerTest:92-165`). `check()` hiçbir şey yazmaz ✓; `on_resync` gerçek `ADMIN_RESYNC`
dalıyla birebir ✓; `on_login` D1. `resync` yerel hesabı reddeder, AD'de tek kayıt ister ✓; takım eşitlemesi kilitli/yerel
üyeleri atlar, biri düşerse sürer ✓; denetim `USER_LDAP_RESYNC` + `TEAM_LDAP_RESYNC` ✓. `TeamLdapAuditModal` müdürü
ayrı satırda, "takım üyesi değil" ibaresiyle ✓.

---

## Doğru bulunan (tarandı, temiz — tekrar sorulmasın)

- `LdapProvisioningService`: rol kuralı (LDAP kimseye ADMIN vermez; elle ADMIN/AUDIT düşürülmez; `role_locked` /
  `org_role_locked` / `team_locked` sözleşmeleri) ✓; `applyTeams` küme değişmediyse koleksiyonu yeniden yazmaz ve izi
  tekrarlamaz ✓; `findOrCreateTeam` grup mail'i ✓; PO lidersiz takımın lideri ✓; `ensureManagerEscalationContact`
  varsayılan kapalı, idempotent ✓; `auditLdapChange` değişiklik yoksa yazmaz ✓; `isTeamGroupDn` kaçışlı virgül ✓;
  `indexOfIgnoreCase` "İ" kayması ✓; `sicilFromAttr` DN/düz sicil ✓.
- `ManagerLookup`: kendisi aday değil, aktif > pasif, LDAP > yerel, belirsizlikte boş + uyarı ✓; baştaki sıfırlar
  normalize edilmez (bilinçli) ✓.
- `UserService`: `computeViewTeamIds`/`computeManageTeamIds` (AUDIT null, yerel ADMIN null, AD ADMIN kendi+astlar,
  TEAM_ADMIN led+own, USER own) ✓; `computeMemberTeamIds` asla null ✓; `createUser` kilitler + MANUAL iz ✓;
  `updateTeam` ad benzersizliği harf duyarsız ✓; `updateTeamManager` var-olmayan kullanıcı 400 ✓; `deleteTeam` üç
  engel ✓; `syncPoLeadership` gerçek lideri ezmez, dangling lider lidersiz sayılır ✓; `applyProfileFields`
  `manager_sicil` global-admin kapısı (baseline O4) ✓.
- `TeamAdminService.moveAll`: SY + UG + dokuz izleme + üyelik (birincil korunur, TEAM_MOVE iz, hedefte mevcut iz ezilmez)
  + kişiler + gruplar + AÇIK alarm damgası ✓; `impact` UG'yi sayar ✓.
- `SessionScope`: `isGlobalAdmin` = ADMIN ∧ `viewTeamIds == null`; `canOperateTeam`/`canWriteInventory` üyelik
  kapsamı; rolling-deploy geri düşüşü ✓.
- `AdminController`: `createUser`/`applyUserUpdate` global-olmayan yazar için `requireAssignableRole` +
  `requireCanAdministerTarget` + kendi rolünü/aktifliğini değiştirememe + son aktif ADMIN koruması ✓;
  `userPhoto` kapsam (404) ✓; `listTeamUsers` görüş kapsamı ✓; `updateTeamWeeklyNotifications` gerçek üyelikten
  doğrular ✓; takım `manager_id` null = temizle bilinçli, `leader_id` null = dokunma ✓; `TEAM_AUDIT_FIELDS`
  `managerId` içerir ✓; `_uf` `teamId/teamIds/managerId` içerir ✓.
- `AuthController`: giriş yönlendirmesi `auth_source`'a göre (LDAP kullanıcısı yerel parolayla giremez, LDAP açıkken)
  ✓; kanonik kullanıcı adı (aktif oturum, remember-me iptali, denetim) ✓; `/me` `refreshTeamScope` rol değişmişse
  dokunmaz ✓; `/me/*` self-scope, parametre yok ✓; `populateSession` `memberTeamIds` boş liste (asla null) ✓.
- `TeamDirectoryController` / `UserDirectoryController`: beyaz-liste, yalnız aktif üyeler, bilinmeyen takım 404 ✓.
- `LdapAdminController`: bootstrap admin + `requireNotScopedAdmin` + matris ✓.
- Frontend: `TeamMemberCards` zincir yok, müdür yalnız alan ✓; `TeamMembersModal` sayaç = `members.length` ✓;
  `teamMembersModel.resolveModalManager` `teamManager` verildiğinde sütunla aynı kayıt ✓; `UserDetailPanel` AD sekmesi
  yalnız LDAP + global admin ✓; `TeamsTab` "Yönetim bağı" ayrı kart ✓; `MembershipSource` LEGACY etiketi ✓;
  `MultiTeamSelect` seçim sırası ekleme sırası (sıra semantiği taşımıyor — Y2 çözümünde sunucu sorumluluğu).
- Bilinçli kararlar (bulgu sayılmadı): haftalık raporda "üyelerin tüm müdürleri"; müdürlerde `company` geri düşüşü;
  takım adı eşleşmesinde harf katlama yok; `moveAll`/üyelik değişiminde `team_locked=true`; AD ADMIN'e astların
  takımları (2026-09-09 tasarımı; LDAP TEAM_ADMIN'e verilmedi).

---

## Okundu ✓ (tam)

Backend: `model/AppUser.java`, `model/Team.java`, `model/UserTeamSource.java`, `controller/SessionScope.java`,
`controller/TeamActorScope.java`, `service/ManagerLookup.java`, `service/TeamMembershipSourceService.java`,
`service/LdapProvisioningService.java`, `service/UserService.java`, `service/TeamAdminService.java`,
`service/LdapMembershipService.java`, `controller/AuthController.java`, `controller/AdminController.java`
(:1366-1375, :2575-3260, :3510-3862), `controller/TeamDirectoryController.java`, `controller/UserDirectoryController.java`,
`controller/LdapMembershipController.java`, `controller/LdapAdminController.java`, `controller/NocAdminController.java`,
`repository/AppUserRepository.java`, `service/noc/NocCallListService.java`, `service/WeeklyReportService.java`
(:655-730, :805-945), `service/UserPushRecipientResolver.java` (:90-185), `service/SchedulerService.java` (:1182-1210
yamalar), `config/AuthInterceptor.java` (grep), `service/PermissionCatalog.java` (grep), `model/EscalationContact.java`
(grep), `AdminController.applyContactFields` (:1868-1895).
Frontend: `utils/teamManager.js`, `components/admin/UserEditModal.jsx`, `UserManager.jsx` (:150-260, :283-365,
:455-505, :826-851), `UserDetailPanel.jsx`, `userdetail/OverviewTab.jsx`, `TeamsTab.jsx`, `UserDetailHeader.jsx`,
`userDetailModel.js`, `UserLdapCompare.jsx`, `TeamManager.jsx` (:120-235 + grep), `TeamMembersManager.jsx`,
`TeamLdapAuditModal.jsx`, `MembershipSource.jsx`, `ui/TeamBadge.jsx`, `ui/TeamMembersModal.jsx` (:140-200 + grep),
`ui/TeamMemberCards.jsx` (grep), `ui/teamMembersModel.js`, `ui/MultiTeamSelect.jsx`, `nav/NavUser.jsx` (:190-215),
`api/client.js` (grep), `i18n/index.jsx` (grep).
Testler (başlık/iddia düzeyinde): `UserServiceTest` (:712-800), `AdminControllerTest` (:1612-1642, :3486-3516, grep),
`LdapProvisioningServiceTest`, `LdapTeamMembershipProvisioningTest`, `LdapMembershipControllerTest`,
`UserServiceTeamSourceTest`, `teamManager.test.js`, `teamManagerDerivation.test.js`.
Baseline: `BUG_RAPORU_2026-09-23.md`, `BUG_RAPORU_7.md`, `.migration/team-manager-bug/PROD_DIAGNOSIS.md`,
`.claude/commands/bug-denetle.md`, `CLAUDE.md` (Auth/Permissions/Single session/Frontend), 8 bellek notu.

---

## Önerilen düzeltme sırası

**Tur 1 — sessiz veri bozulması (yayın öncesi).** Y1 (null org rolü: tek satırlık sözleşme değişikliği + test),
Y2 (birincil koruma: `updateUser` + iki üye ucu + istemci `team_id`), Y3 (LDAP hedefte parola sıfırlama 400 + menü
gizle + yama). Üçü de dar; Y1 ve Y2 aynı commit'te (`updateUser`).

**Tur 2 — LDAP sahipliği.** O1 (LDAP kullanıcısında AD alanları sunucuda salt okunur + UI disabled), O2 (LDAP
girişinde kontak senkronu), D4 (`/me auth_source` + menü).

**Tur 3 — kapsam kapıları.** O5 (`addTeamMember`/`removeTeamMember` hedef kapısı), O6 (müdür dalında yalnız
değişen üyelik), O7 (`searchUsers` görüş kapsamı).

**Tur 4 — tek müdür kaynağı.** O3 + O4 birlikte: backend `TeamManagerResolver` (elle → kural), rehber uçlarına
`manager_display_name`, UI tüketimi; D3 `deleteUser` temizliği aynı turda.

**Tur 5 — küçükler.** D1, D2, D5 (karar), D6, D8; D7 için `@Version` değerlendirmesi; D9 belge.

| # | Önem | Dosya:satır | Tek satır |
|---|---|---|---|
| Y1 | YÜKSEK | `AdminController:2854,2875,3136` → `UserService:777-781` | Üye ekle/çıkar + toplu işlem org rolünü siler ve kilitler |
| Y2 | YÜKSEK | `UserService:714-717,765` + `AppUser:118-124` + `UserEditModal:73-74` | Birincil takım sırasız `HashSet`'in ilk elemanı → yönetici kaydı birincili kaydırır |
| Y3 | YÜKSEK | `AdminController:3145-3174`, `UserService:951-991`, `AuthController:164-177` | LDAP kullanıcısına parola sıfırlama → 24 saat sonra AD girişi reddi |
| O1 | ORTA | `UserService:750-752,834-842`, `UserEditModal:139-200` | Yönetici LDAP alanlarını ezebilir; girişte sessiz geri dönüş |
| O2 | ORTA | `LdapProvisioningService:136-145` vs `UserService:786-790` | AD e-posta değişince `user_id` bağlı kontak bayat |
| O3 | ORTA | `NocCallListService:210-239` vs `teamManager.js:97-112` | NOC postası ile Takım Yönetimi farklı Takım Müdürü |
| O4 | ORTA | `TeamDirectoryController:47-57`, `teamMembersModel:142-160`, `TeamManager:149-187` | Elle müdür yüzeyler arasında kayboluyor / sicil dizesi |
| O5 | ORTA | `AdminController:2842-2879` | Üye ekle/çıkar hedef kullanıcı kapısı yok |
| O6 | ORTA | `AdminController:3117-3120` | Kapsamlı müdür çok-takımlı astı düzenleyemez / başka takımdan düşürür |
| O7 | ORTA | `AdminController:2945` | Kullanıcılar ekranı kapsamlı rolde yalnız birincil takım |
| D1–D9 | DÜŞÜK | yukarıda | tanı öngörüsü, birincil-yalnız yüklem, dangling müdür, LDAP parola menüsü, toplu ata, legacy kilit, yarış, sayaç, remember-me |
