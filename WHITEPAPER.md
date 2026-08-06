<img src="branding/assets/logo-ok-512.png" width="200" alt="Site Monitor logosu">

<!-- ÜRETİLEN KOPYA — elle düzenlemeyin. Kaynak: frontend/src/assets/whitepaper.md ; senkron: node scripts/sync-whitepaper.mjs -->

# Site Monitor — Kurumsal İzleme Platformu

Sürüm `20.0.3` · Ağustos 2026 · Türkçe / English

---

## İçindekiler

1. [Yönetici Özeti](#1-yonetici-ozeti)
2. [Problem Tanımı](#2-problem-tanimi)
3. [Ürün Yetenekleri](#3-urun-yetenekleri)
4. [Mimari ve Teknoloji Yığını](#4-mimari-ve-teknoloji-yigini)
5. [Sistem Topolojisi](#5-sistem-topolojisi)
6. [Veritabanı Şeması](#6-veritabani-semasi)
7. [Sertifika Kontrol Akışı](#7-sertifika-kontrol-akisi)
8. [Alarm ve Eskalasyon Mekanizması](#8-alarm-ve-eskalasyon-mekanizmasi)
9. [Bildirim Sistemi](#9-bildirim-sistemi)
10. [İzleme Türleri](#10-izleme-turleri)
11. [Bakım Pencereleri](#11-bakim-pencereleri)
12. [Güvenlik Modeli](#12-guvenlik-modeli)
13. [Kullanıcı Rolleri ve Yetki Modeli](#13-kullanici-rolleri-ve-yetki-modeli)
14. [Kullanıcı Ekranları ve Aksiyonlar](#14-kullanici-ekranlari-ve-aksiyonlar)
15. [Operasyonel Prosedürler](#15-operasyonel-prosedurler)
16. [Dağıtım ve DevOps](#16-dagitim-ve-devops)
17. [Yüksek Erişilebilirlik](#17-yuksek-erisilebilirlik)
18. [Konfigürasyon Referansı](#18-konfigurasyon-referansi)
19. [Sürüm ve Yayın Bilgisi](#19-surum-ve-yayin-bilgisi)
20. [Production Dağıtım ve Güvenlik Kontrol Listesi](#20-production-dagitim-ve-guvenlik-kontrol-listesi)
21. [Terimler Sözlüğü](#21-terimler-sozlugu)

---

## 1. Yönetici Özeti

**Site Monitor**, kurumunuzun dışa ve içe dönük servislerini tek panelden izleyen bir kurumsal izleme platformudur. Çekirdeğinde SSL/TLS sertifika yaşam döngüsü yönetimi vardır; bunun çevresine HTTP/port/DNS/ping erişilebilirlik izlemesi, sayfa bütünlüğü denetimi, sentetik (k6) akış testleri ve alan adı tescil takibi eklenmiştir. Sorunları siz fark etmeden önce tespit eder, sorumlu takıma alarm üretir ve tüm süreci denetim iziyle kayıt altına alır.

Sertifikanızın süresi beklenmedik bir anda dolduğunda müşteri trafiği kesilir, tarayıcılar güvenlik uyarısı gösterir ve düzenleyici uyum riski doğar. Site Monitor bu senaryoyu üç adımda ortadan kaldırır: envanterinizdeki her domaini saatlik olarak tarar, süre dolmadan 30 / 15 / 7 gün önce sorumlu takıma kademeli alarm üretir ve yenileme tamamlanana kadar günlük hatırlatma gönderir.

Platformu farklı profiller farklı amaçlarla kullanır:

| Kullanıcı Profili | Kullanım Amacı |
|---|---|
| Operasyon / Altyapı ekipleri | Sertifika ve erişilebilirlik izleme, yenileme koordinasyonu |
| Uygulama geliştirme ekipleri | Uygulama servislerini izleme, haftalık rapor girişi |
| BT Güvenlik ekibi | Denetim, zayıf algoritma tespiti, uyumluluk |
| Yöneticiler (PO, Müdür, C-Level) | Genel durum izleme, eskalasyon alarmları, rapor onayı |
| Sistem yöneticileri | Kurulum, yapılandırma, kullanıcı ve yetki yönetimi |

---

## 2. Problem Tanımı

Bu bölüm, Site Monitor'ün neden var olduğunu anlatır: manuel sertifika takibinin nerede kırıldığını ve otomatik izlemenin hangi boşluğu doldurduğunu. Platformu değerlendiren yöneticiler ve süreci devralan yeni ekip üyeleri için bağlam sağlar.

### 2.1 Sertifika Yönetiminin Karmaşıklığı

Modern bir kuruluş onlarca, çoğu zaman yüzlerce aktif SSL/TLS sertifikası yönetir. Bu sertifikalar farklı sertifika otoritelerinden (CA) temin edilir, farklı sunuculara, yük dengeleyicilere ve CDN katmanlarına dağıtılır, 90 gün ile 2 yıl arasında değişen geçerlilik sürelerine sahiptir ve farklı takımların sorumluluğundadır. Tek bir gözden kaçan bitiş tarihi, müşteriye dönük bir servisin kesintisi demektir.

### 2.2 Manuel İzlemenin Yetersizliği

Sertifika sürelerini Excel tablosu veya takvim hatırlatıcısıyla takip etmeyi denediyseniz sonucu bilirsiniz: insan hatasına açıktır, personel rotasyonunda bilgi kaybolur, sertifika zinciri ve iptal durumu hiç kontrol edilmez ve anlık görünürlük yoktur. Sorun ancak kullanıcılar şikayet ettiğinde fark edilir.

### 2.3 Site Monitor'ün Sağladığı Değer

```
Reaktif Yaklaşım              →  Proaktif Yaklaşım
─────────────────────────────────────────────────────
Sertifika dolunca haberdar ol    30 gün öncesinden haberdar ol
Manuel kontrol                   Otomatik saatlik tarama
Kimin sorumlu olduğu belirsiz    Takım bazlı sorumluluk
E-posta zinciri kaosu            Yapılandırılmış eskalasyon
Denetim izi yok                  Tam denetim kaydı
```

---

## 3. Ürün Yetenekleri

Bu bölümde platformun ne yaptığını uçtan uca görürsünüz. Sertifika tarafı çekirdektir; izleme türlerinin her birinin ayrıntısını [10. İzleme Türleri](#10-izleme-turleri) bölümünde bulursunuz.

### 3.1 Otomatik Sertifika Taraması

Site Monitor, envanterdeki tüm aktif domainleri saatlik periyotta tarar. Her domain için gerçek bir TCP/SSL el sıkışması yapar — yani sunucunuzun o an gerçekten sunduğu sertifikayı görürsünüz, bir kayıt defterindeki teorik değeri değil. Paralel çalışan iş parçacığı havuzu (`certCheckExecutor`) büyük envanterleri dakikalar içinde işler.

Her taramada şu bilgiler elde edilip kaydedilir:

| Alan | Açıklama |
|---|---|
| Subject / Issuer | Sertifika sahibi ve veren kurum |
| Geçerlilik tarihleri | Not Before / Not After |
| Kalan gün sayısı | Anlık hesaplama |
| SAN (Subject Alternative Names) | Tüm geçerli alan adları |
| Parmak izi (SHA-256) | Sertifika kimliği |
| Anahtar algoritması ve boyutu | RSA, EC, DSA — 2048, 4096 bit vb. |
| İmza algoritması | SHA-256, SHA-1 vb. |
| Key Usage / EKU | Sertifika kullanım amaçları |
| CA sertifikası mı | Ara/kök CA tespiti |
| Sertifika zinciri durumu | `VALID` / `BROKEN` |
| İptal durumu (OCSP/CRL) | `VALID` / `REVOKED` / `UNDETERMINED` |
| Dağıtım durumu | `OK` / `INCOMPLETE` |
| OCSP / CRL URL | Canlı iptal kontrolü ve iptal listesi adresleri |

### 3.2 Zincir Doğrulama ve İptal Kontrolü

Yaprak sertifikadan kök CA'ya kadar tüm zincir analiz edilir; ara CA sertifikalarının kalan süreleri hesaplanır ve zincir kırıksa `CHAIN_BROKEN` alarmı üretilir. İptal kontrolünde önce OCSP (Online Certificate Status Protocol) sorgulanır; başarısız olursa CRL'e (Certificate Revocation List) düşülür. CRL yanıtları performans için 1 saat önbelleklenir (200 giriş kapasiteli Caffeine cache).

### 3.3 Dağıtım Uyumluluk Kontrolü

Yeni sertifikayı temin ettiniz ama sunucuya dağıtmayı unuttunuz — klasik bir operasyon hatası. Envantere beklenen parmak izini girerseniz, Site Monitor her taramada sunucudaki gerçek parmak iziyle karşılaştırır ve uyuşmazlıkta `MISMATCH` alarmı üretir.

### 3.4 Domain Bazlı Proxy Yönlendirmesi

Kontroller varsayılan olarak doğrudan (direkt outbound) bağlantıyla yapılır. Bazı WAF/firewall yapılandırmaları izleme pod'unun IP'sini reddeder; bu durumda envanterde ilgili domainin "Proxy Üzerinden Kontrol Et" (`use_proxy`) işaretini açarsınız ve yalnız o domainin kontrolü kurumsal HTTP CONNECT proxy üzerinden gider. OCSP/CRL sorguları da aynı proxy ayarını kullanır.

### 3.5 Kritiklik Katmanı

Her domain bir tier'a atanır: 1 = Müşteriye Dönük Prod, 2 = Dahili Prod, 3 = UAT/Pre-Prod, 4 = Dev/Sandbox. Tier; sıralamayı, kritiklik rozetlerini ve eskalasyon kişi seçimini etkiler — Tier 1 alarmları daha yüksek öncelikle takım yöneticilerine taşınır.

### 3.6 Sertifika Dışı İzleme Ailesi

Sertifika sweep'inden bağımsız zamanlayıcılarla çalışan dokuz izleme türü vardır: HTTP/Website, Port (TCP/UDP/TLS/BANNER), DNS, Anahtar Kelime, Ping, Sayfa Bütünlüğü, Sentetik (k6), Alan Adı tescili ve envanter domainleri için Uptime genel görünümü. Her biri kendi eşiklerine, doğrulama/kurtarma denemelerine ve alarm tiplerine sahiptir — ayrıntılar [10. İzleme Türleri](#10-izleme-turleri) bölümündedir.

### 3.7 Alarm Yönetimi Özeti

Sertifika alarmları kalan güne göre üç seviyede üretilir:

```
WARNING  (UYARI)  ←── ≤ 30 gün kaldıysa
HIGH     (YÜKSEK) ←── ≤ 15 gün kaldıysa
CRITICAL (KRİTİK) ←── ≤ 7 gün kaldıysa (veya iptal / zincir hatası)
```

Sertifika alarm tipleri:

| Tip | Tetikleyici | Seviye |
|---|---|---|
| `EXPIRY` | Sertifika süresi dolmak üzere | WARNING / HIGH / CRITICAL |
| `REVOKED` | Sertifika iptal edilmiş | CRITICAL |
| `CHAIN_BROKEN` | Zincirde süresi dolmuş ara CA | CRITICAL |
| `MISMATCH` | Dağıtım eksik | CRITICAL |

İzleme alarmları (`ACCESSIBILITY`, `PORT_DOWN`, `DNS_FAILURE`, `DNS_CHANGED`, `DOMAINMON_*` vb.) ve alarm yaşam döngüsünün tamamı [8. Alarm ve Eskalasyon Mekanizması](#8-alarm-ve-eskalasyon-mekanizmasi) bölümünde anlatılır.

### 3.8 Takım Tabanlı Sorumluluk

Her sertifika tek bir takıma atanır. Takımın lideri (genelde PO) yönetimden sorumludur; müdür ilişkisi Active Directory'deki `manager` alanından türetilir ve müdür, takıma otomatik olarak MANAGER eskalasyon kontağı olarak eklenir. Üyeler takımın sertifikalarını görüntüler ve haftalık rapor girer. Alarmlar ve bildirimler her zaman sertifikanın atandığı takıma ve o takımın eskalasyon kişilerine yönlenir.

### 3.9 Domain Yeniden Adlandırma

Envanterde bir domainin adını değiştirdiğinizde geçmiş tüm kontrol verisi, alarm geçmişi ve notlar yeni ada atomik olarak (`@Transactional`) taşınır. Aynı isimde kayıt varsa işlem `409` ile reddedilir; hangi kayıtla çakıştığı sızdırılmaz.

### 3.10 LDAP ve Active Directory Entegrasyonu

Yerel hesapların yanında AD/LDAP ile kimlik doğrulama yapabilirsiniz: giriş anında AD'ye bind edilir, ilk başarılı girişte kullanıcı otomatik provizyon edilir (ünvan → orgRole, `manager` → müdür ilişkisi, takım ataması). Müdür–takım ilişkisi kurulduğunda takıma otomatik MANAGER eskalasyon kontağı eklenir. Bind parolası AES-GCM ile şifrelenir; bağlantıyı **Ayarlar** ekranından test edebilir, bir kullanıcının AD özniteliklerini canlı görüntüleyebilirsiniz.

### 3.11 Haftalık Raporlar ve Onay Akışı

Takımlar her ISO haftası için operasyon raporu girer (acil/yüksek olay sayıları, açık problemler, planlı işler). Rapor taslaktan onaya gönderilir; PO doğrudan ekrandan ya da e-postadaki bağlantıdan tek tıkla onaylar veya iade eder. Cuma sabahı bekleyen raporlar için otomatik hatırlatma gider; rapor gerektiğinde başka takıma devredilebilir.

### 3.12 Yetki Matrisi ve SQL Playground

Rol bazında (TEAM_ADMIN / USER / AUDIT) `view` / `edit` / `execute` izinlerini **Yetkiler** ekranından açıp kapatırsınız; ADMIN tam yetkili ve kilitlidir. Hassas işlemler onay ister, "Varsayılanlara Dön" desteklenir. Global admin ayrıca salt-okuma SQL konsolu olan **SQL Playground** sekmesini kullanır; sorgu geçmişi tutulur ve gece temizlenir.

---

## 4. Mimari ve Teknoloji Yığını

Site Monitor tek deploy edilebilir bir uygulamadır: React arayüzü, derlenmiş `dist/` çıktısı olarak Spring Boot jar'ının içinden servis edilir. Bu bölüm, bileşenlerin hangi teknolojilerle yazıldığını ve birbirine nasıl bağlandığını gösterir.

### 4.1 Backend

| Bileşen | Teknoloji | Versiyon |
|---|---|---|
| Uygulama çerçevesi | Spring Boot | 4.1.0 |
| Dil | Java | 25 (LTS) |
| ORM | Hibernate / JPA | Spring Data JPA (`ddl-auto=update`) |
| Veritabanı | PostgreSQL | JDBC sürücüsü |
| Şifreleme / ASN.1 | BouncyCastle | 1.78.x |
| Validation | Hibernate Validator | jakarta-validation 3.x |
| E-posta | JavaMail (Spring Mail) | SMTP/STARTTLS |
| JSON | Jackson (SNAKE_CASE) | 3.x (`tools.jackson`) |
| Oturum | Spring Session JDBC | Dağıtık oturum (prod) |
| Metrikler | Micrometer + Prometheus | `/actuator/prometheus` |
| Test | JUnit 5 + Mockito | Surefire + Jacoco |
| Build | Maven | 3.9.x |

Temel servisler:

```
SchedulerService          — Zamanlayıcı, HA dağıtık kilit, startup catch-up,
                            gece temizliği, şema yamaları
CertificateCheckerService — SSL/TLS soket bağlantısı, sertifika çekme, proxy tunnel
ChainValidationService    — Zincir analizi, OCSP/CRL iptal kontrolü, proxy-aware
CertificateService        — Sonuç kaydetme, raporlama, filtreleme, toplu cache evict
EscalationService         — Alarm işleme, eskalasyon, bildirim yönlendirme
EmailNotificationService  — HTML e-posta oluşturma, async 421 retry
WebhookService            — Slack/Teams webhook entegrasyonu
UserService               — Kimlik doğrulama, takım/kullanıcı CRUD, kilitleme
AuditService              — Denetim kaydı + GeoIP zenginleştirme (async)
GeoIpService              — IP → ülke/şehir (ip-api.com, 1 saat cache)
PortCheckerService        — TCP/UDP/TLS/BANNER port izleme
DnsCheckerService         — DNS kayıt izleme, CHANGED/ROTATED algılama
UptimeHttpCheckerService  — Envanter domainleri için HTTP uptime izleme
HttpCheckerService        — Bağımsız HTTP/Website monitörleri
KeywordCheckerService     — Sayfa gövdesinde metin arama
PingCheckerService        — ICMP echo izleme
PageCheckerService        — Sayfa bütünlüğü (kaynak) denetimi
ScriptedCheckerService    — Sentetik k6 koşuları
DomainCheckerService      — Alan adı tescil (RDAP/WHOIS) izleme
MaintenanceService        — Bakım penceresi motoru (O(1) aktif-hedef cache)
StormService              — Alarm fırtınası gruplaması
MonitoringOutageService   — Kesinti teyit durum makinesi
IncidentService           — Olay defteri (SRE incident ledger)
WeeklyReportService       — Haftalık rapor durum makinesi
SqlPlaygroundService      — Salt-okuma SQL konsolu (denetimli)
ExtendedHealthService     — Sistem Sağlığı sekmesinin veri kaynağı
DbAnalyticsService        — pg_stat_statements tabanlı sorgu analitiği
UserActivityService       — Login serileri, ısı haritası, aktif oturumlar
ShutdownLogger            — JVM kapanış nedenini loglar
```

Kesişen (cross-cutting) bileşenler:

```
GlobalExceptionHandler    — Exception → kullanıcı-dostu yanıt; stack trace sızmaz
RequestLoggingFilter      — TRACE seviyede tam istek/yanıt logu (hassas alan maskeli)
AuthInterceptor           — Oturum + token tabanlı özel auth (Spring Security filter zinciri YOK)
PermissionCatalog         — Tüm kaynak-aksiyon matrisinin tek kaynağı
```

### 4.2 Frontend

| Bileşen | Teknoloji |
|---|---|
| Framework | React 18.3 |
| Build aracı | Vite 5.4 |
| Routing | Sekme durumu, `?tab=` senkronu (React Router YOK) |
| Dil desteği | Türkçe / İngilizce (parity testiyle zorunlu eşlik) |
| Tema | Açık / Koyu mod (localStorage kalıcı) |
| İkonlar | `lucide-react` (emoji kullanılmaz) |
| Grafikler | recharts (tembel yüklenir) |
| Test | Vitest + Testing Library |
| Hata sınırı | ErrorBoundary — root + sekme seviyesi |
| Responsive | Tam mobil uyumlu |

Uygulama tek sayfalıdır: `App.jsx` içindeki sekme durumu, doğrulanmış bir anahtar listesine (`VALID_TABS`) karşı kontrol edilen `?tab=` parametresiyle senkron tutulur. Otuzun üzerinde sekme vardır; görünürlük rol bazlıdır (ör. **Yetkiler** ve **SQL Playground** yalnız global admin, **Ayarlar** yalnız bootstrap admin, **Denetim Logu** global admin veya AUDIT).

### 4.3 Eşzamanlılık ve Performans

```
certCheckExecutor     : core 20, max 50, queue 1000 (EXECUTOR_* env ile ayarlanır)
Kontrol timeout       : 10 saniye (site.monitor.check-timeout-seconds)
CRL önbellek          : 1 saat TTL, 200 giriş (Caffeine)
OCSP/CRL HTTP timeout : 5 sn OCSP, 10 sn CRL (connect+read)
DB bağlantı havuzu    : 2-10 bağlantı (HikariCP), leak eşiği 60 sn
JVM heap              : Konteyner RAM'inin ~%75'i
SMTP 421 retry        : Asenkron (çağıran thread bloke olmaz)
Cache eviction        : Sweep başına tek toplu işlem
N+1 önleme            : Sweep başında batch ön yükleme, döngüde bellek-içi map
```

Performans hedefleri (k6 smoke): p95 < 500 ms, hata oranı < %1, kontrol geçiş oranı > %99 — 50 sanal kullanıcı × 30 saniye yük altında.

---

## 5. Sistem Topolojisi

Bu bölüm, platformun üretimde ve yerel geliştirmede nasıl konumlandığını gösterir. Üretimde aynı imaj birden çok replika olarak çalışır; durum taşıyan her şey (oturum, zamanlayıcı kilidi) pod ölümüne dayanacak şekilde veritabanında tutulur.

### 5.1 Üretim Ortamı Kubernetes

```
┌────────────────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster (Production)                  │
│                    Namespace: site-monitor                          │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Ingress Controller (nginx)                                  │  │
│  │  site-monitor.example.com  ─── TLS termination               │  │
│  │  Proxy timeout: 60s  │  Max body: 1MB                        │  │
│  └──────────────────────┬───────────────────────────────────────┘  │
│                         ↓                                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Service (ClusterIP)                                         │  │
│  │  Port 80 → Pod:8080                                          │  │
│  └──────────────────────┬───────────────────────────────────────┘  │
│                         ↓                                           │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐        │
│  │   Pod 1        │  │   Pod 2        │  │   Pod 3        │        │
│  │  Spring Boot   │  │  Spring Boot   │  │  Spring Boot   │        │
│  │  Java 25       │  │  Java 25       │  │  Java 25       │        │
│  │  512Mi–8Gi     │  │  512Mi–8Gi     │  │  512Mi–8Gi     │        │
│  │  500m–5000m    │  │  500m–5000m    │  │  500m–5000m    │        │
│  └───────┬────────┘  └───────┬────────┘  └───────┬────────┘        │
│          └──────────────────┬┘───────────────────┘                  │
│                             ↓                                        │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  StatefulSet: PostgreSQL                                     │  │
│  │  PersistentVolumeClaim: 10 Gi (ReadWriteOnce)                │  │
│  │  Kullanıcı/DB: <db-kullanıcısı>/<db-adı> (.env'den gelir)    │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ConfigMap: site-monitor-config  │  Secret: site-monitor-secret     │
│  HPA: 3–10 pod  │  PDB: minAvailable=2  │  Topology spread          │
└────────────────────────────────────────────────────────────────────┘
           │                                          │
           ↓                                          ↓
   ┌───────────────┐                       ┌──────────────────┐
   │  SMTP Sunucu  │                       │  Hedef Sunucular │
   │  Gmail /      │                       │  (443/TCP SSL)   │
   │  Kurumsal     │                       │  Taranacak       │
   │  SMTP         │                       │  Domainler       │
   └───────────────┘                       └──────────────────┘
```

Veritabanı kullanıcı adı ve veritabanı adı ortama özeldir; `<db-kullanıcısı>/<db-adı>` değerleri `.env` dosyasından (Kubernetes'te Secret'tan) gelir ve hiçbir zaman koda gömülmez.

### 5.2 Yerel Geliştirme Docker Compose

```
┌────────────────────────────────────────────┐
│  Docker Compose                            │
│                                            │
│  ┌────────────────────────────────────┐    │
│  │  site-monitor:latest               │    │
│  │  Port: 8080                        │    │
│  │  Volume: ./data:/app/data          │    │
│  │  ReadOnly FS + /tmp tmpfs          │    │
│  │  Health: wget /health (30s)        │    │
│  └────────────────────────────────────┘    │
│                                            │
│  Ortam: .env dosyası (DB, SMTP, Admin)     │
└────────────────────────────────────────────┘
```

### 5.3 Dağıtık Zamanlayıcı Kilidi

Birden çok pod çalışırken taramayı aynı anda yalnız bir pod yapmalıdır. Site Monitor bunun için veritabanı tabanlı bir dağıtık kilit kullanır:

```
scheduler_lock tablosu:
  name        → "cert-check"
  locked_by   → "hostname-uuid8"
  locked_until→ "2026-08-06T09:10:00" (TTL: 10 dk)

Akış:
  Pod başlar → aynı host'un eski kilitleri temizlenir
  Tarama zamanı → INSERT INTO scheduler_lock (benzersiz kısıt)
    ├─ Başarılı → bu pod tarar
    └─ Hata (duplicate key) → başka pod tarıyor, atla
  Tarama biter → DELETE FROM scheduler_lock
```

Sıkışan bir kilidi **Sistem Sağlığı** ekranından zorla serbest bırakabilirsiniz.

---
## 6. Veritabanı Şeması

PostgreSQL sistemin tek kayıt kaynağıdır. Şema, klasik migration aracı yerine iki mekanizmayla evrilir: Hibernate `ddl-auto=update` yeni entity ve kolonları oluşturur; `SchedulerService.applySchemaPatches()` açılışta idempotent `ALTER TABLE` yamaları uygular. Otuzdan fazla tablo vardır; en önemlileri aşağıdadır.

### 6.1 Temel Tablolar

| Tablo | Amaç | Kritik Alanlar |
|---|---|---|
| `app_users` | Kullanıcı hesapları | username, password_hash, system_role, org_role, team_id, manager_id, auth_source (LOCAL/LDAP), lockout_until |
| `teams` | Takım tanımları | name, leader_id, email, active |
| `certificate_inventory` | Domain envanteri | domain, port, team_id, tier, use_proxy, deleted_at |
| `latest_checks` | Her domain için son kontrol (tek kayıt) | domain (PK), not_after, days_remaining, status, fingerprint |
| `certificate_checks` | Tüm kontrol geçmişi | domain, run_id, checked_at, tüm sertifika alanları |

### 6.2 Alarm ve Bildirim Tabloları

| Tablo | Amaç | Kritik Alanlar |
|---|---|---|
| `alert_events` | Açık/kapalı alarm olayları | domain, alert_level, alert_type, team_id, acknowledged, resolved, last_re_alert_at, storm_id |
| `alert_thresholds` | Gün eşikleri konfigürasyonu | warning_days, high_days, critical_days, re_alert_interval_hours |
| `escalation_contacts` | Bildirim alıcıları | team_id, email, role, min_alert_level, webhook_url |
| `notification_logs` | Gönderilen tüm bildirimler | alert_event_id, recipient_email, sent_at, email_status, trigger |
| `alert_storms` | Alarm fırtınası kayıtları | scope, aktif/kapalı durum |

### 6.3 İzleme ve Destek Tabloları

| Tablo | Amaç |
|---|---|
| `audit_log` | Tüm yönetim ve güvenlik aksiyonları |
| `certificate_notes` | Domain bazlı notlar |
| `scheduler_lock` | Dağıtık tarama kilidi |
| `system_heartbeat` | Sistem sağlık sinyali |
| `spring_session` / `spring_session_attributes` | JDBC oturum deposu (prod) |
| `remember_me_tokens` | "Beni Hatırla" tokenleri |
| `permission_grants` | Rol bazlı yetki matrisi |
| `ldap_settings` / `smtp_settings` | LDAP/AD ve SMTP yapılandırması (parolalar AES-GCM) |
| `weekly_report` ve ilişkili tablolar | Haftalık rapor, onay/iade postaları, görseller |
| `uptime_check` / `port_monitor` / `port_check` / `dns_monitor` / `dns_record` | Erişilebilirlik izleme hedefleri ve ölçümleri |
| `domain_checks` | Alan adı tescil kontrolleri (append-only) |
| `network_outage_event` | Toplu ağ kesintisi olayları |
| `maintenance_window` kayıtları | Bakım pencereleri |
| `diagnostic_runs` | Tanı aracı koşu geçmişi |
| `sql_query_history` | SQL Playground sorgu geçmişi |
| `password_history` | Parola tekrar kullanım kontrolü |
| `guide_link` | Değişim Rehberi bağlantıları |
| `weekly_availability_log` | Haftalık erişilebilirlik maili idempotency kaydı |

### 6.4 Soft Delete

Envanter kayıtları fiziksel olarak silinmez:

```sql
deleted_at VARCHAR(255)  -- NULL = aktif, dolu = silinmiş
active     BOOLEAN       -- silinince FALSE yapılır
```

Böylece tüm geçmiş veri korunur ve silinen sertifikaları "Silinenleri Göster" görünümünden geri yükleyebilirsiniz.

---

## 7. Sertifika Kontrol Akışı

Bu bölüm, bir sertifikanın saatlik sweep'te başından geçenleri adım adım anlatır. Akışı bilmek, bir alarmın neden ve ne zaman üretildiğini anlamanın en kısa yoludur.

### 7.1 Tam Akış Şeması

```
Tetikleyiciler:
  ┌─ Saatlik (cron: her saatin başı)
  ├─ Startup (backend açılışında)
  ├─ Stale sweep (uzun süre kontrol edilmemiş domainler)
  └─ Manuel (admin "Şimdi Kontrol Et" butonuyla)
         │
         ▼
  SchedulerService.runCheck()
  │  ├─ DB dağıtık kilidi al (tek pod çalışır)
  │  ├─ Envanterdeki aktif domainleri yükle (+ use_proxy bayrağı)
  │  └─ Her domain için checkAsync(domain, port, forceProxy) başlat (paralel)
         │
         ▼
  CertificateCheckerService.check(domain, port, forceProxy)
  │  ├─ Yönlendirme kararı:
  │  │     forceProxy AND proxy yapılandırıldı AND domain NO_PROXY'de DEĞİL
  │  │     → HTTP CONNECT tunnel; aksi halde direkt outbound TCP
  │  ├─ TCP soket bağlantısı (timeout: 10 sn)
  │  ├─ TLS_MODE=browser: TLS 1.2 + ALPN [h2, http/1.1] (WAF/Akamai uyumu)
  │  ├─ SSL el sıkışması → sertifika zinciri alınır
  │  ├─ Yaprak sertifika ayrıştırılır (subject, issuer, tarihler, SAN...)
  │  ├─ HSTS kontrolü (HTTP HEAD, try-finally ile bağlantı sızıntısız)
  │  └─ ChainValidationService.analyze()
         │
         ▼
  ChainValidationService (OCSP/CRL sorguları proxy-aware)
  │  ├─ Zincirdeki tüm sertifikalar incelenir
  │  ├─ Ara CA süreleri → chain_status: VALID / BROKEN
  │  ├─ SHA-256 parmak izi hesaplanır
  │  └─ İptal: OCSP (5 sn) → CRL (10 sn, Caffeine cache)
  │       └─ revocation_status: VALID / REVOKED / UNDETERMINED
         │
         ▼
  CertificateService.saveResult()
  │  ├─ certificate_checks tablosuna geçmiş kaydı
  │  ├─ latest_checks tablosunda UPSERT
  │  ├─ Dağıtım kontrolü: expected_fingerprint ≠ gerçek → INCOMPLETE
  │  ├─ Bakım penceresi işareti (uptime yüzdesinden hariç tutulur)
  │  └─ Uyarı durumu belirlenir (days_remaining ≤ eşik)
         │
         ▼
  EscalationService.processResults()
  │  ├─ Sweep başında TEK SEFER batch ön yükleme (N+1 önleme)
  │  ├─ Her domain için alarm tipi: REVOKED → MISMATCH → CHAIN_BROKEN → EXPIRY
  │  ├─ Alarm seviyesi: WARNING / HIGH / CRITICAL
  │  ├─ Açık alarm var mı?
  │  │   ├─ Hayır → yeni AlertEvent → INITIAL bildirim
  │  │   ├─ Evet + seviye yükseldi → ESCALATION bildirimi, acknowledged sıfırlanır
  │  │   └─ Evet + onaysız + bugün gönderilmemiş → DAILY_REALERT
  │  └─ Sorun çözüldüyse → resolved=true → RESOLUTION bildirimi
         │
         ▼
  EmailNotificationService + WebhookService
  │  ├─ HTML e-posta oluşturulur (marka logolu kart tasarımı — bkz. §9)
  │  ├─ SMTP durumu: SENT / FAILED / SKIPPED_DISABLED / QUEUED_RETRY (421)
  │  ├─ 421'de asenkron retry (90 sn sonra, çağıran bloke olmaz)
  │  └─ Webhook varsa Slack/Teams'e gönderilir
         │
         ▼
  notification_logs kaydı → sweep sonunda TEK toplu cache eviction
```

### 7.2 Toplu Ağ Kesintisi Algılama

Bir sweep'te başarısızlıkların en az `NETWORK_ERROR_THRESHOLD` oranı (varsayılan %50) ağ kaynaklıysa ve en az `NETWORK_MIN_ERRORS` (3) kontrol başarısız olduysa, Site Monitor bunun tekil sertifika sorunları değil bir altyapı kesintisi olduğuna karar verir: o koşunun bireysel sertifika alarmları bastırılır, tek bir `NetworkOutageEvent` açılır ve sistem yöneticisine tek e-posta gider. Bu bilinçli bir tasarımdır — kesinti anında yüzlerce yanlış alarm yerine tek doğru sinyal alırsınız.

### 7.3 Yeniden Deneme Politikası

Yalnızca ağ (NETWORK) sınıfı hatalar bir kez yeniden denenir (`CHECK_RETRY=true`). SSL, DNS ve sertifika hataları asla yeniden denenmez — bunlar geçici aksaklık değil, gerçek bulgulardır.

### 7.4 Startup Catch-Up Mekanizması

Backend herhangi bir nedenle kapalı kaldıysa, açılışta o günün kaçırılmış bildirimleri telafi edilir:

```
runOnStartup()
  ├─ Şema yamaları (idempotent ALTER TABLE)
  ├─ Bootstrap (admin kullanıcı, varsayılan takım, alarm eşiği)
  ├─ Eski dağıtık kilitlerin temizliği
  │
  ├─ catchUpMissedDailyAlerts()  ← SENKRON, ağ çağrısı yok
  │   ├─ Açık + onaysız alarmlar sorgulanır
  │   ├─ lastReAlertAt bugün değilse → DAILY_REALERT gönderilir
  │   └─ Log: "X missed notification(s) sent"
  │
  └─ Thread(runCheck).start()   ← ASENKRON tam tarama
```

Sonuç: pod ne kadar geç açılırsa açılsın, o günün bildirimleri kesinlikle gönderilir.

---

## 8. Alarm ve Eskalasyon Mekanizması

Alarm, Site Monitor'ün kalbidir: bir sorunun tespitinden çözümüne kadar geçen sürecin kaydıdır. Bu bölümde alarm yaşam döngüsünü, kimin hangi seviyede haberdar edildiğini ve tekrar bildirimlerin nasıl bastırıldığını öğrenirsiniz.

### 8.1 Alarm Yaşam Döngüsü

```
Sorun tespit edildi
         │
         ▼
 AlertEvent oluşturuldu (resolved=false, acknowledged=false)
         │
         ▼
 INITIAL bildirim → eskalasyon kişilerine e-posta / webhook
         │
         ▼
 Her gün DAILY_REALERT → tekrar bildirim
         │            └─ (acknowledged=true ise durur)
         │
    ┌────┴────────────────────────────────────┐
    │                                         │
    ▼                                         ▼
Kullanıcı "Onayla" tıklar           Seviye yükseldi (ör. WARNING→HIGH)
acknowledged=true                    acknowledged=false yapılır
Günlük bildirim durur                ESCALATION bildirimi gönderilir
    │                                         │
    └─────────────────┬───────────────────────┘
                      │
                      ▼
           Sorun giderildi / sertifika yenilendi
                      │
                      ▼
           resolved=true, resolvedAt, resolvedBy
           RESOLUTION bildirimi gönderilir
```

### 8.2 Alarm Tipleri

| Kaynak | Tipler |
|---|---|
| Sertifika sweep'i | `EXPIRY`, `REVOKED`, `CHAIN_BROKEN`, `MISMATCH` |
| Uptime / Port / DNS izleme | `ACCESSIBILITY`, `PORT_DOWN`, `DNS_FAILURE`, `DNS_CHANGED` |
| Keyword / Ping / HTTP / Sayfa Bütünlüğü / Sentetik | Monitör türüne özel kesinti ve bütünlük alarmları |
| Alan adı izleme | `DOMAINMON_EXPIRY`, `DOMAINMON_UNKNOWN`, `DOMAINMON_STATUS`, `DOMAINMON_CHANGED` |

İzleme alarmları `MonitoringOutageService` üzerinden gelir: alarm açılmadan önce ardışık N başarısız kontrol (doğrulama denemesi) beklenir, düzelme için de ardışık başarılı kontrol aranır. Tek seferlik dalgalanma alarm üretmez; sinyal tipleri birbirini maskelemez.

### 8.3 Eskalasyon Matrisi

| Alarm Seviyesi | Kalan Gün | Bildirim Alan Roller | Açıklama |
|---|---|---|---|
| WARNING | ≤ 30 gün | PO + TECH | İlk alarm, planlama başlamalı |
| HIGH | ≤ 15 gün | PO + TECH + MANAGER | Acil yenileme gerekiyor |
| CRITICAL | ≤ 7 gün | Tüm kişiler (+ C-LEVEL) | Derhal müdahale |

Eskalasyon kişileri takım bazında tanımlanır; takımın kişisi yoksa global kişilere düşülür.

### 8.4 Alıcı Yönlendirme Kuralları

Bildirimin kime gideceği alarmın kaynağına göre belirlenir ve üç yol (ilk alarm, çözülme, manuel yeniden bildirim) tutarlı çalışır:

- Bağımsız izleme alarmları (keyword, ping, HTTP, alan adı, `DOMAINMON_*`) takımı `AlertEvent.teamId` alanından çözer — alarm oluşurken damgalanır. Bu monitörler sertifika envanterinde olmadığı için envanterden takım aramak sessizce global kişilere düşerdi; üç yol da bu yüzden aynı alandan okur.
- Müdür/eskalasyon kontakları yalnız KRİTİK alan adı vade alarmına eklenir; WARNING seviyesindeki alan adı alarmları ve tüm keyword/ping/HTTP alarmları takım kişileriyle sınırlı kalır. Bu bilinçli bir ürün politikasıdır: müdür yalnız bir domain kritik biçimde vadeye yaklaştığında çağrılır.
- Sertifika alarmları envanterdeki takımı kullanır ve seviyeye göre eskalasyon kişilerini ekler.

### 8.5 Bildirim Tetikleyici Tipleri

| Tetikleyici | Açıklama |
|---|---|
| `INITIAL` | İlk alarm oluştuğunda |
| `ESCALATION` | Seviye yükseldiğinde (ör. WARNING → HIGH) |
| `DAILY_REALERT` | Her gün, onaylanmamış alarmlar için |
| `MANUAL` | "Yeniden Bildir" düğmesiyle |
| `RESOLUTION` | Sorun kendiliğinden giderildiğinde |
| `MANUAL_RESOLVE` | Alarm elle "Çözüldü" işaretlendiğinde |

Tekrar bildirim günlük bazda tekilleştirilir: aynı alarm için aynı gün ikinci bir DAILY_REALERT gitmez.

### 8.6 Onaylama Davranışı

Bir alarmı onayladığınızda günlük tekrar bildirimleri durur; ancak alarm açık kalmaya devam eder — sorun çözülmedi, yalnızca görüldü. Seviye yükselirse onay otomatik sıfırlanır ve yeni bildirim gider.

### 8.7 Alarm Fırtınası Gruplaması

Kısa bir pencerede çok sayıda monitör birden düştüğünde (ör. ortak bir ağ segmenti koptu) her monitör için ayrı e-posta almak istemezsiniz. `StormService`, doğrulanmış kesintileri izler: kapsam için aktif bir fırtına varsa yeni alarm ona eklenir, eşik aşıldığında bireysel bildirimler tek toplu bildirime terfi eder. Olay kayıtları (AlertEvent) monitör başına yazılmaya devam eder — geçmiş ve uptime yüzdesi etkilenmez; yalnız bildirim gruplanır. Fırtına dağıldığında tek toplu düzelme bildirimi gider. Özellik varsayılan açıktır (`site.monitor.storm.enabled`) ve **Ayarlar → Fırtına** bölümünden yönetilir.

### 8.8 Alan Adı Vade Alarmlarında Çifte Kontrol

Alan adı vade alarmının seviyesi kart durumu katmanlarını aynen izler: kalan gün kritik eşiğin altındaysa CRITICAL, uyarı eşiği ile kritik eşik arasındaysa WARNING. Ayrıca her gün saat 16:00'da (Europe/Istanbul, `DOMAIN_CRITICAL_CHECK_CRON`) ikinci bir sweep yalnız kritik eşiğe girmiş domainleri yeniden kontrol eder: aynı gün yenilenen bir domainin açık `DOMAINMON_EXPIRY` alarmı ertesi sabahı beklemeden otomatik kapanır; hâlâ kritik olan domain için günlük tekilleştirme sayesinde ikinci bir bildirim gitmez.

---

## 9. Bildirim Sistemi

Alarm üretmek işin yarısıdır; doğru kişiye, okunur bir biçimde ulaştırmak diğer yarısı. Site Monitor bildirimleri markalı HTML e-posta ve Slack/Teams webhook'ları ile dağıtır; her gönderim denemesi `notification_logs` tablosuna işlenir.

### 9.1 E-posta Tasarımı

Tüm sistem e-postaları ortak, Outlook-güvenli bir kart şablonu kullanır. Kartın başlık çubuğunda 32 piksellik durum-duyarlı turp logosu ile "Site Monitor" yazı kilidi (lockup) yer alır; logo CID inline eki olarak gömülür (`cid:brand-logo`), yani harici kaynak yüklemeyen katı e-posta istemcilerinde de görünür. Genişlik/yükseklik HTML öznitelik olarak yazılır — Outlook'un Word tabanlı motoru CSS genişliğini yok saydığı için bu zorunludur. Mail başına tek logo kuralı geçerlidir: logo yalnız başlık çubuğundadır.

Logo varyantı e-postanın anlamını taşır:

| E-posta | Logo / Renk |
|---|---|
| CRITICAL alarm | Kırmızı yapraklı turp + kırmızı vurgu |
| WARNING / HIGH alarm | Amber (kehribar) yapraklı turp + amber vurgu |
| Çözülme, rapor, hatırlatma, test | Yeşil yapraklı turp + yeşil vurgu |

Alarm e-postasının gövdesi büyük "kalan gün" sayacı, sertifika bilgileri (alan adı, sahibi, veren kurum, geçerlilik, parmak izi) ve durum özeti (sertifika / iptal / zincir / dağıtım) bölümlerinden oluşur. Çözülme e-postası çözen kişiyi, çözülme tarihini, alarm oluşturma tarihini ve önceki seviyeyi içerir.

Alan adı izleme (`DOMAINMON_*`) e-postalarının detay tablosu her gönderimde en güncel kontrol kaydından yeniden kurulur — yeniden bildirim ve çözülme mailleri de her zaman güncel veriyi taşır.

### 9.2 Webhook Desteği

| Platform | Format |
|---|---|
| Slack | Slack Block Kit JSON |
| Microsoft Teams | Adaptive Card |

Her eskalasyon kişisine ayrı webhook tanımlayabilirsiniz; gönderim sonuçları e-postayla aynı şekilde `notification_logs` tablosuna yazılır (10 sn timeout).

### 9.3 Haftalık Erişilebilirlik Maili

Sertifika sahibi her aktif takıma, sahip olduğu domainlerin geçen tam haftaya (Pazartesi 00:00 – Pazar 23:59, Europe/Istanbul) ait erişilebilirlik özeti yönetici özeti formatında gönderilir. Kesinti olsun olmasın gider; kesinti yaşayan domainler ayrıca vurgulanır. Gönderim `weekly_availability_log` ile idempotenttir (aynı hafta için ikinci mail gitmez) ve **Ayarlar → Haftalık Erişilebilirlik** bölümünden yapılandırılır.

### 9.4 E-posta Konfigürasyonu

```properties
site.monitor.email.enabled=true       # E-postayı etkinleştir
SPRING_MAIL_HOST=smtp.ornek.com       # SMTP sunucu
SPRING_MAIL_PORT=587                  # STARTTLS portu
SPRING_MAIL_USERNAME=hesap@ornek.com  # Gönderen hesap
SPRING_MAIL_PASSWORD=uygulama-sifresi # Uygulama parolası
site.monitor.email.from=gonderen@adres
```

SMTP ayarlarını çalışma anında **Ayarlar → SMTP** ekranından da yönetebilir ve test e-postası gönderebilirsiniz; kayıtlı parola AES-GCM ile şifrelenir.

---
## 10. İzleme Türleri

Sertifika izlemesi "kilit sağlam mı" sorusuna cevap verir; bu bölümdeki türler "kapı açık mı, içerisi doğru mu" sorularına bakar. Her tür üst menüdeki **İzleme** grubunda kendi sekmesine sahiptir, kendi zamanlayıcısıyla çalışır ve her monitör bir takıma atanır — alarm ve raporlar o takıma gider. Yeni monitörü ilgili sekmedeki + Yeni Monitör butonuyla eklersiniz; her formun yanında o türe özel yerleşik bir nasıl-yapılır rehberi bulunur.

Ortak davranışlar: kontrol aralığı 30 saniye ile 24 saat arasında seçilir; alarm öncesi ardışık başarısızlık (doğrulama denemesi, varsayılan 3) ve düzelme ilanı için ardışık başarı (kurtarma, varsayılan 3) aranır; monitörü silmeden "Aktif" anahtarıyla duraklatabilirsiniz; monitörler grup etiketiyle kümelenir (**Ayarlar → Monitör Grupları**).

### 10.1 Durum İzleme Genel Bakışı

**Durum İzleme** sekmesi, sertifika envanterinizdeki domainlerin HTTP erişilebilirlik fotoğrafını tek ekranda verir: durum, yanıt süresi, uptime yüzdesi ve son kontrol zamanı. Domain başına tanı penceresi açarak ağ/OpenSSL/HSTS tanı araçlarını çalıştırabilirsiniz. Bakım penceresindeki domainler uptime yüzdesi hesabından hariç tutulur, böylece planlı kesinti istatistiği bozmaz.

### 10.2 HTTP Website İzleme

Bir URL'nin ayakta olup olmadığını ve beklediğiniz durum kodunu dönüp dönmediğini izler. Metod (GET/HEAD/POST), beklenen durum kodu (`200`, `2xx`, `200-399` gibi kalıplar; boşsa 200–399), yönlendirme takibi ve istek zaman aşımı (varsayılan `10000` ms) ayarlanır. TLS doğrulaması varsayılan kapalıdır — yalnız erişilebilirlik ölçülür, iç-CA/self-signed sunucular sorun çıkarmaz; açarsanız geçersiz sertifika alarma dönüşür. İsteğe bağlı SSL/domain bitiş hatırlatmaları da (ör. `30,14,7` gün) tanımlanabilir.

Ne zaman kullanmalı? Servisin dışarıdan ulaşılabilirliğini ve doğru cevap verdiğini garanti etmek istediğinizde. Örneğin ödeme API'nizin sağlık ucunu 1 dakikada bir `GET /health` ile izler, `200` dışına düşen üç ardışık kontrolde takımınıza alarm gönderirsiniz — müşteri fark etmeden önce siz fark edersiniz.

### 10.3 Port İzleme

URL'si olmayan servisleri — SMTP, veritabanı, özel TCP servisleri — host ve port üzerinden izler. Beş kontrol tipi vardır: TCP (port açık mı), TLS (el sıkışması + sertifika sunumu), HTTP(S) (durum kodu), BANNER (yanıtta alt-dizge arama, ör. `220`, `SSH-2.0`) ve UDP. Yavaş yanıt alarmı için eşik (varsayılan `3000` ms) tanımlayabilir, IP sürümünü (Otomatik/IPv4/IPv6) seçebilirsiniz.

Ne zaman kullanmalı? Web dışı bir bağımlılığın çalıştığını doğrulamak istediğinizde. Örneğin kurumsal SMTP sunucunuzun 25 portunu BANNER modunda `220` bekleyerek izlersiniz: port açık ama SMTP servisi asılıysa bunu TCP kontrolü göremez, banner kontrolü görür.

### 10.4 DNS İzleme

Bir kaydın (A, AAAA, CNAME, MX, TXT, NS) çözülmeye devam ettiğini ve değerinin değişmediğini izler. Sorgular izleme sunucusundan dnsjava ile önbelleksiz atılır; varsayılan hedef işletim sisteminin DNS zinciridir ve sorgu zaman aşımı ayarlanabilir (`site.monitor.dns.query-timeout-ms`, varsayılan 2000 ms). Üç güçlü özellik öne çıkar:

- Beklenen değer kilidi: satır başına bir değer girersiniz; canlı yanıtta beklenen kümede olmayan her değer DNS ele geçirme şüphesi olarak işaretlenir. "Şu anki değeri sabitle" mevcut yanıtı tek tıkla doldurur.
- DNS değişikliği alarmı: kayıt değeri değişince `DNS_CHANGED` alarmı üretilir (otomatik kapanmaz, günlük hatırlatılır). Yeni değerlerin tamamı beklenen listedeyse — örneğin bilinen iç ↔ dış IP geçişi — alarm üretilmez.
- Propagation kontrolü: kayıt birden çok public çözümleyicide (varsayılan `8.8.8.8, 1.1.1.1, 9.9.9.9` — `site.monitor.dns.resolvers`) karşılaştırılır; en az ikisi farklıysa tutarsız işaretlenir.

Ne zaman kullanmalı? Bir alan adının hedefinin sessizce değişmesinin felaket olacağı yerlerde. Örneğin internet bankacılığı domaininizin A kaydını beklenen IP kilidiyle izlersiniz; bir gece kayıt bilinmeyen bir IP'ye dönerse sabah trafiği başlamadan alarm elinizdedir.

### 10.5 Anahtar Kelime İzleme

Bir sayfanın gövdesinde belirli bir metnin bulunup bulunmadığını — ve kaç kez geçtiğini — kontrol eder. Adet koşulu esnektir: En az / En fazla / Tam olarak / Şundan fazla / Şundan az. Büyük-küçük harf duyarlılığı seçilebilir; önbellek kırma için özel HTTP başlıkları ve URL'de `{timestamp}` yer tutucusu desteklenir. Varsayılan kontrol aralığı 1 dakikadır.

Ne zaman kullanmalı? HTTP 200 dönen ama içeriği bozulmuş sayfaları yakalamak istediğinizde. Örneğin ana sayfanızda "Bakımdayız" metni için Tam olarak 0 koşulu tanımlarsınız: bakım sayfası yanlışlıkla üretimde kalırsa, durum kodu 200 olsa bile alarm alırsınız.

### 10.6 Ping İzleme

Bir host'a ICMP echo paketleri gönderir ve RTT ile paket kaybını ölçer. Paket sayısı (1–10, varsayılan 4), IP sürümü ve zaman aşımı (varsayılan 5000 ms) ayarlanır. Bazı ağlar ICMP'yi engeller — böyle hedeflerde Port (TCP) izlemesi daha güvenilirdir; kilitli ortamda ICMP yetkisi yoksa sonuç hata değil N/A olur.

Ne zaman kullanmalı? Uygulama katmanından bağımsız, saf ağ erişilebilirliğini izlemek istediğinizde. Örneğin şube yönlendiricinizi ping ile izler, RTT grafiğindeki tırmanışı hat doygunluğunun erken işareti olarak okursunuz.

### 10.7 Sayfa Bütünlüğü İzleme

Sayfanın kendisi değil, içindeki parçalar için: HTML'den çıkarılan her kaynak (görsel, CSS, JS, iframe, font, link) tek tek HTTP ile doğrulanır. İki mod vardır: Tek Sayfa ve Site Tarama (derinlik 0–5, en çok 500 sayfa, hariç tutma desenleri). Kırık tanımı bilinçli olarak dardır: 404/410, 503 dışı 5xx ve hiç kurulamayan bağlantı kırıktır; 401/403/429/503 gibi belirsiz kodlar "Belirsiz" sayılır ve alarm üretmez (WAF/bot engeli tarayıcıda sorunsuz çalışabilir). Başarısız HEAD, GET ile teyit edilir ve bir kez yeniden denenir. Tek kırık kaynak bile sayfayı DEGRADED yapar ve YÜKSEK seviyeli sayfa bütünlüğü alarmı açılır; kaynak düzelince alarm otomatik kapanır. HTTPS sayfadaki `http://` kaynaklar için mixed-content alarmı varsayılan açıktır; üçüncü-taraf kaynak alarmı dış CDN gürültüsünü azaltmak için varsayılan kapalıdır.

Ne zaman kullanmalı? Kampanya ve vitrin sayfalarında "sayfa açılıyor ama görseller kırık" utancını önlemek için. Örneğin yeni kampanya sayfanızı Tek Sayfa modunda izlersiniz; bir deploy sonrası ana bannerin 404 verdiğini müşteri hizmetleri değil, Site Monitor söyler.

### 10.8 Sentetik İzleme k6

Tek istek yetmediğinde — çok adımlı akışları (OIDC/Keycloak login, API zincirleri, form login) gerçek bir k6 script'iyle uçtan uca koşturur. Hazır şablonlardan başlarsınız; script `__ENV` üzerinden ortam değişkenleri okur. Sırları script gövdesine yazmazsınız: "Gizli" işaretli değişkenler şifreli saklanır, çıktı ve loglarda maskelenir, geri okunamaz. Koşu sonucu net sınıflanır: başarısız k6 check'i / exit 99 → FAIL, süre aşımı → TIMEOUT, diğer hatalar → ERROR, aksi halde PASS. Kaydetmeden önce "Test Çalıştır" ile script'i anında deneyebilirsiniz. İzleme için gerçek kullanıcı yerine ayrılmış bir servis hesabı kullanın.

Ne zaman kullanmalı? "Login çalışıyor mu" sorusunun cevabı tek bir HTTP isteğine sığmadığında. Örneğin internet şubesi giriş akışınızı (form → OIDC yönlendirme → token → portfolio çağrısı) 5 dakikada bir sentetik koşturursunuz; zincirdeki herhangi bir halka koptuğunda hangi check'in kırıldığını k6 çıktısında görürsünüz.

### 10.9 Alan Adı Tescil İzleme

SSL sertifikanız kusursuz olabilir; ama alan adının tescili dolarsa domain elinizden çıkar. **Alan Adı İzleme** sekmesi bir domainin tescil bitişini (RDAP, `.tr` gibi TLD'lerde HTTPS web-whois) günde bir kontrol eder — vade seyrek değiştiği için ayrı sıklık alanı yoktur. Alt alan girseniz bile kayıtlı domaine (eTLD+1) indirgenir. Uyarı eşiği (varsayılan 30 gün), kritik eşiği (7 gün) ve hatırlatma günleri (varsayılan `60,30,14,7,3,1`) monitör başına ayarlanır.

Detay penceresindeki Domain Kaydı sekmesi tescilin kimlik kartıdır: kayıt operatörü (registrar) ve IANA kimliği, önemli tarihler, ad sunucuları, çözümlenen A/AAAA IP'leri ve reverse-DNS adları, EPP durum kodları (açıklamalı) ve DNSSEC durumu. Vade sorgusu bir tanı aracıyla adım adım izlenebilir; kurumsal SSL-inspection proxy'si RDAP'ı bozarsa proxy CA zinciri yakalanıp güven deposuna eklenebilir (bkz. §14.23).

Ne zaman kullanmalı? Kurumun sahip olduğu her üretim domaininde — istisnasız. Örneğin `sirketiniz.com` için varsayılan eşikleri korur, marka domainleriniz için hatırlatma listesine `90` gününü de eklersiniz; tescil yenileme bütçe onayı gerektiriyorsa 90 günlük öncü sinyal tam da bunun içindir.

---

## 11. Bakım Pencereleri

Planlı bir kesinti sırasında alarm yağmuru hem gürültüdür hem de gerçek alarmlara karşı duyarsızlaştırır. **Bakım** sekmesi, belirli monitörleri (veya tümünü) belirli zaman aralıklarında sessize alan pencereler tanımlamanızı sağlar; operasyon ve takım yöneticileri kullanır.

Bir pencere tanımlarken ad ve açıklama girer, hedefleri seçersiniz: tek tek monitörler (HTTP, port, keyword, ping, sayfa, DNS, alan adı ve envanter sertifika domainleri karışık seçilebilir) ya da "tüm monitörler". Zamanlama saat diliminde tanımlanır (varsayılan `Europe/Istanbul`): başlangıç zamanı + süre (dakika) ve tekrarlama — tek seferlik, günlük, haftalık (haftanın günleri seçilir) veya aylık (ayın günü). Tekrarlama hesabı yaz saati geçişlerine dayanıklıdır.

Pencere aktifken etkisi kesindir: hedef monitör için alarm açılmaz ve hiçbir kanaldan bildirim gitmez — ilk alarm, günlük hatırlatma ve DNS değişikliği dahil. Pencere içinde düzelen mevcut alarmlar sessizce kapanır (çözülme maili de gitmez — tam sessizlik). Bakımdaki kontroller uptime yüzdesi hesabından hariç tutulur, yani planlı kesinti erişilebilirlik istatistiğinizi cezalandırmaz. Aktif hedef kümesi bellek içinde tutulur ve yaklaşık 30 saniyede bir ile her kayıt değişikliğinde tazelenir; sweep yolunda pencere sorgusu O(1)'dir.

Ne zaman kullanmalı? Örneğin her Pazar 02:00–04:00 arası veritabanı bakımınız varsa haftalık bir pencere tanımlarsınız: ilgili monitörler o aralıkta susar, 04:01'de sorun devam ediyorsa normal alarm düzeni kaldığı yerden çalışır.

---

## 12. Güvenlik Modeli

Site Monitor bir izleme aracı olduğu kadar bir kurumsal kayıt sistemidir; bu yüzden kimlik doğrulama, oturum ve denetim katmanları ilk günden tasarımın parçasıdır. Bu bölüm güvenlik denetimlerinin tamamını tek yerde toplar.

### 12.1 Kimlik Doğrulama

Oturum tabanlı kimlik doğrulama kullanılır: HTTP oturumları Spring Session ile yönetilir, prod profilinde JDBC deposuna yazılır (tüm pod'lar oturumu paylaşır). Yerel parolalar BCrypt ile saklanır. `/api/**` istekleri Spring Security filter zinciri yerine özel bir `AuthInterceptor` tarafından korunur; classpath'te yalnız BCrypt için `spring-security-crypto` vardır.

LDAP/Active Directory doğrulaması açıldığında giriş anında AD'ye bind edilir; ilk başarılı girişte kullanıcı otomatik provizyon edilir (orgRole, müdür ilişkisi, takım). Yerel bootstrap admin hesabı her zaman geçerlidir (acil erişim); LDAP/SMTP ayarları yalnız bu hesaba açıktır. Bind parolası `SITE_MONITOR_SECRET_KEY` ile AES-GCM şifreli saklanır.

"Beni Hatırla" özelliği 7 günlük kalıcı oturum sağlar: HMAC imzalı token veritabanında tutulur ve her oturum açılışında yenilenir.

### 12.2 Tek Aktif Oturum

Bir kullanıcı aynı anda en fazla bir canlı oturum tutar; en yeni giriş kazanır. Arayüz `/api/session/ping` ile (~15 sn) oturumu taze tutar. Başka bir cihazdan giriş yapıldığında eski oturum ilk isteğinde 401 alır ve `/?session=expired` sayfasına yönlenir. Zaten canlı bir oturum varken giriş denemesi 409 döner; onay penceresini kabul ederseniz `forceLogin=true` ile eski oturum düşürülür. Yönetici, **Sistem Sağlığı** üzerinden herhangi bir kullanıcının oturumunu zorla sonlandırabilir.

### 12.3 Aşamalı Hesap Kilitleme

Kaba kuvvet saldırılarına karşı kilitleme kademeli sertleşir:

| Başarısız Giriş | Bekleme Süresi |
|---|---|
| 5 hatalı giriş | 30 saniye |
| 3 ek hatalı giriş | 2 dakika |
| 2 ek hatalı giriş | 10 dakika |
| 1 ek hatalı giriş | 30 dakika |
| Eşik aşılırsa | Kalıcı kilit (yalnız admin açar) |

### 12.4 Hareketsizlik Zaman Aşımı

5 dakika hareketsizlikten sonra 60 saniyelik uyarı sayacı başlar; süre dolunca oturum otomatik kapatılır.

### 12.5 Konteyner Güvenliği

```yaml
securityContext:
  runAsNonRoot: true            # Root olarak çalışmaz
  runAsUser: 1000               # UID 1000
  readOnlyRootFilesystem: true  # Kök dosya sistemi salt okunur
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]                 # Tüm Linux yetenekleri kaldırılır

# Yalnızca /tmp yazılabilir (emptyDir)
```

### 12.6 Denetim Kaydı

Her giriş, çıkış ve yönetim aksiyonu şu bilgilerle kaydedilir: UTC zaman damgası, kullanıcı, istemci IP'si, coğrafi konum (ülke/şehir), tarayıcı, olay tipi (DOMAIN_ADD, USER_UPDATE, LOGIN_FAILED...), etkilenen kaynak, sonuç (SUCCESS/FAILURE) ve alan bazında eski → yeni değer farkları. Kayıtlar yalnız eklenir, değiştirilemez; gece temizliği 180 günden eskileri siler.

Denetim kayıtları anomali bayraklarıyla zenginleştirilir: mesai dışı erişim (OFF_HOURS), alışılmadık IP (UNUSUAL_IP), coğrafi hız ihlali (GEO_VELOCITY), kaba kuvvet (BRUTE_FORCE) ve hız sınırı (RATE_LIMITED). Algılama pencereleri **Ayarlar → Login Anomali** bölümünden yönetilir; anormal başarısız giriş yoğunluğu otomatik olay kaydı da açabilir.

### 12.7 Hata Yönetimi ve Bilgi Sızıntısı Önleme

`GlobalExceptionHandler` tüm yakalanmamış istisnaları karşılar ve kullanıcı-dostu Türkçe mesajla yanıt döner:

| Exception | HTTP | Arayüz Mesajı |
|---|---|---|
| `NoSuchElementException` | 404 | istisna mesajı |
| `IllegalStateException` | 409 | istisna mesajı |
| `IllegalArgumentException` | 400 | istisna mesajı |
| `SecurityException` | 403 | istisna mesajı |
| `DataIntegrityViolationException` | 409 | "Bu domain envanterde zaten var" vb. |
| `MethodArgumentNotValidException` | 400 | "Geçersiz alan(lar): X, Y" + alan listesi |
| `HttpMessageNotReadableException` | 400 | "Geçersiz istek formatı" |
| `MissingServletRequestParameterException` | 400 | "Eksik parametre: X" |
| `ResponseStatusException` | geçirilir | istisna mesajı |
| Diğer her şey (catch-all) | 500 | "Sunucu hatası" — stack trace arayüze SIZMAZ |

Stack trace ve iç hata detayı yalnız log dosyasına yazılır.

### 12.8 Hassas Alan Maskeleme

`RequestLoggingFilter`, TRACE seviyesinde tüm HTTP istek/yanıt gövdesini loglayabilir; varsayılan `com.sitemonitor=DEBUG` seviyesinde sessizdir. Açmak için:

```properties
logging.level.com.sitemonitor.config.RequestLoggingFilter=TRACE
```

Açıldığında JSON gövde, form gövde ve URL sorgusu üzerinden ~60 hassas alan otomatik `*******` ile maskelenir:

```
password / passwd / pwd / pass / parola / sifre /
old_password / new_password / smtp_pass / db_password /
token / access_token / refresh_token / bearer / csrf /
secret / api_key / client_secret / private_key /
session_id / jsessionid / sid /
pin / otp / mfa_code / verification_code  (EN + TR varyantları)
```

Hassas HTTP başlıkları (`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token` vb.) de maskelenir. `/health`, `/favicon.ico`, `/assets/*`, `/static/*` ve statik uzantılar atlanır; gövde logu 2000 karakterde kesilir.

### 12.9 Frontend Hata Sınırı

Uygulama iki katmanlı `ErrorBoundary` ile sarılıdır: kök seviye beyaz ekranı önler, sekme seviyesi bir sekmenin render hatasının diğerlerini düşürmesini engeller. Hata durumunda "Bir şey ters gitti" mesajı ve "Yenile" butonu gösterilir; tam hata konsola düşer.

### 12.10 Girdi Doğrulama

Modellerde `jakarta-validation` anotasyonları, controller'larda `@Valid` kullanılır:

```java
@NotBlank @Pattern(...) String domain  // RFC 1123 + wildcard izinli
@Min(1) @Max(65535)     Integer port
@Min(1) @Max(4)         Integer tier
```

Geçersiz gövde 400 + alan listesiyle reddedilir; stack trace dönmez.

---

## 13. Kullanıcı Rolleri ve Yetki Modeli

Yetki modeli iki eksenlidir: sistem rolü ne yapabileceğinizi, takım kapsamı nerede yapabileceğinizi belirler. Kapsamlar AD ilişkilerinden türetilir; ekrandan düzenlenebilen izin matrisi ise rol varsayılanlarının üzerine ince ayar sağlar.

### 13.1 Sistem Rolleri

| Rol | Açıklama | Erişim Kapsamı |
|---|---|---|
| ADMIN (global) | Yerel/bootstrap admin | Tüm takımlar + global işlemler (yetki matrisi, SQL Playground, sistem denetimi, LDAP/SMTP ayarları) |
| ADMIN (müdür, kapsamlı) | AD'den gelen, astı olan kullanıcı | Sistem rolü ADMIN'dir ama yalnız astlarının takımlarını salt-okuma görüntüler; global işlemlere giremez |
| TEAM_ADMIN | Genelde PO (orgRole=PO) | Liderlik ettiği takımlarda görüntüleme + yönetim (çok-takım) |
| USER | Normal kullanıcı | Yalnız kendi takımı: okuma + takım alarm aksiyonları + haftalık rapor girişi |
| AUDIT | Denetçi | Sistem geneli salt-okuma + Denetim Logu |

Oturuma `viewTeamIds` (okuma) ve `manageTeamIds` (yönetim) kapsamları yazılır. Global admin'de kapsamlar sınırsızdır; müdürün kapsamı astlarının takımları, PO'nun kapsamı liderlik ettiği takımlardır. Bir kullanıcının sistem rolü ADMIN olsa bile kapsamı doluysa global değildir — yetki sızıntısı bu kuralla önlenir.

### 13.2 Organizasyonel Roller

AD'deki ünvan/`company` bilgisinden türetilir; hem eskalasyon bildirim seviyesini hem sistem rolünü etkiler:

| Org Rol | Eşlenen Sistem Rolü | Hangi Alarmda Bildirim Alır |
|---|---|---|
| PO (Product Owner) | TEAM_ADMIN + takım lideri | WARNING + HIGH + CRITICAL |
| MANAGER (Müdür) | ADMIN (kapsamlı, salt-okuma) | HIGH + CRITICAL |
| TECH | USER | WARNING + HIGH + CRITICAL |
| C-LEVEL | — | CRITICAL |

### 13.3 Takım Kapsamı ve AD İlişkileri

Her sertifika tek bir takıma atanır. Takım–müdür ilişkisi AD provizyonunda kurulur: üyenin `manager` alanı müdürü belirler; müdür bulununca o takıma otomatik MANAGER eskalasyon kontağı (minimum seviye HIGH) eklenir. PO bir takıma atandığında takımın lideri yoksa otomatik lider olur — mevcut lider ezilmez.

### 13.4 Yetki Matrisi Varsayılanları

Aşağıdaki varsayılanlar **Yetkiler** ekranından rol bazında düzenlenebilir (ADMIN her zaman tam yetkili ve kilitlidir):

| İşlev | ADMIN | TEAM_ADMIN (PO) | USER | AUDIT |
|---|---|---|---|---|
| Dashboard / sertifika görüntüleme | ✓ | ✓ (kapsam) | ✓ (kendi takımı) | ✓ (tümü) |
| "Şimdi Kontrol Et" / canlı tarama | ✓ | ✗ | ✗ | ✗ |
| Sertifika Envanteri (CRUD) | ✓ | ✓ (kapsam) | ✗ (okuma) | ✗ (okuma) |
| Alarm onay / yeniden bildir / çözüldü | ✓ | ✓ (kapsam) | ✓ (kendi takımı) | ✗ |
| Eskalasyon kişileri (CRUD) | ✓ | ✓ (kapsam) | ✗ (okuma) | ✗ (okuma) |
| Alarm eşikleri | ✓ | ✗ | ✗ (okuma) | ✗ (okuma) |
| Takım / kullanıcı yönetimi | ✓ | ✓ (kapsam) | ✗ (okuma) | ✗ (okuma) |
| Haftalık raporlar (yaz/düzenle) | ✓ | ✓ | ✓ (kendi takımı) | ✗ (okuma) |
| Haftalık rapor onayı | ✓ | ✓ (PO) | ✗ | ✗ |
| İzleme monitörleri yapılandırma | ✓ | ✓ (kapsam) | ✗ (okuma) | ✗ (okuma) |
| Bakım penceresi yönetimi | ✓ | ✓ | ✗ | ✗ |
| Denetim Logu | ✓ | ✓ (kapsam) | ✗ | ✓ |
| Yetki Matrisi / SQL Playground / Sistem Denetimi | ✓ (yalnız global) | ✗ | ✗ | ✗ |
| LDAP / SMTP / Ayarlar | ✓ (yalnız bootstrap admin) | ✗ | ✗ | ✗ |

Yıkıcı işlemler (`inventory.purge`, `system_health.terminate`, `system_health.scheduler_lock`) hassas işaretlidir: matriste bu izinleri verirken ayrıca onay istenir. Kodda yeni bir kaynak anahtarı eklendiğinde tek doğruluk kaynağı `PermissionCatalog`'dur; varsayılan izinler ilk açılışta tohumlanır, yeni sürümlerin eklediği izinler mevcut veritabanına geri doldurulur (yönetici özelleştirmeleri korunur).

---
## 14. Kullanıcı Ekranları ve Aksiyonlar

Bu bölüm arayüzün sekme sekme gezisidir. Sekmeler üst menüde gruplanır: Sertifikalar, İzleme, Raporlar, Kayıtlar ve Yönetim. İzleme sekmelerinin kavramsal ayrıntısı [10. İzleme Türleri](#10-izleme-turleri) bölümündedir; burada ekran davranışlarına odaklanılır.

### 14.1 Giriş Ekranı

**Ekran:** `https://site-monitor.example.com`

| Aksiyon | Açıklama |
|---|---|
| Kullanıcı adı + parola → Giriş | Normal oturum açma (yerel veya LDAP) |
| "Beni Hatırla" işareti | 7 günlük kalıcı oturum |
| Hatalı giriş tekrarı | Sayaç artar → kademeli kilitleme başlar |
| Kilit süresi dolunca | Yeniden deneyebilirsiniz |
| Kalıcı kilit | Mesaj gösterilir, admin müdahalesi gerekir |
| Sorun Bildir | Giriş yapamayan kullanıcı ekran görüntülü sorun kaydı bırakır (bkz. §14.20) |
| Başka cihazda oturum varken giriş | 409 + onay penceresi → onaylarsanız eski oturum düşürülür |

### 14.2 Genel Bakış

Giriş sonrası açılan ana ekrandır. Genişletilebilir istatistik paneli (Toplam / Geçerli / Uyarı / Hata / 30 günde doluyor / Süresi dolmuş) ve her sertifika için domain, kalan gün, durum rozeti, veren kurum ve son kontrol bilgisini taşıyan kartlardan oluşur.

| Aksiyon | Açıklama |
|---|---|
| İstatistik kartına tıklama | İlgili kategoriye göre filtreler |
| Durum / süre filtresi | Duruma veya kalan güne göre daraltır |
| Arama kutusu | Domain/issuer üzerinde anlık filtre |
| Sıralama ve sayfa boyutu | Önceliğe veya kalan güne göre sıralar |
| Karta tıklama | Sertifika detay penceresini açar |
| "Şimdi Kontrol Et" (yetkili) | Anlık arka plan taraması başlatır |
| Dil / tema değiştirme | TR/EN ve Açık/Koyu arasında geçiş |

### 14.3 Sertifika Detay Penceresi

Herhangi bir sertifika kartına tıkladığınızda beş sekmeli pencere açılır:

- Detaylar: domain, durum, kalan gün, subject/issuer, geçerlilik aralığı, son kontrol, SAN listesi, varsa hata mesajı.
- Alarmlar: bu sertifikanın tüm alarm olayları — tarih, seviye, tip, onay ve çözüm bilgileri.
- Bildirimler: gönderilmiş tüm bildirimler — alıcı, konu, e-posta/webhook durumu, tetikleyici tipi.
- Güvenlik: tam DN'ler, seri numarası, anahtar/imza algoritmaları, Key Usage / EKU, zincir-iptal-dağıtım durumları, OCSP/CRL URL'leri, SHA-256 parmak izi.
- Notlar: sertifikaya özel serbest metin notları ekler, düzenler, silersiniz.

### 14.4 İstatistikler

Detaylı sertifika sayıları, Tier 1–4 dağılım pastası ve takım bazlı geçerli/uyarı/hata kırılımı. Tier dilimine veya takım satırına tıklayarak **Genel Bakış** ekranını o kesite filtreleyebilirsiniz. Kapsamlı rollerde yalnız erişebildiğiniz takımlar görünür.

### 14.5 Uyarılar

Yalnız `warning` veya `error` durumundaki sertifikaları gösterir; filtreleme ve sıralama **Genel Bakış** ile aynıdır. Sertifikası sorunlu olanların hızlı çalışma listesidir.

### 14.6 Tüm Sertifikalar

Kart yerine tablo görünümü: Domain, Issuer, Subject, Bitiş Tarihi, Kalan Gün, Durum ve Son Kontrol kolonları. Domain/Issuer/Kalan Gün/Son Kontrol kolonları sıralanabilir.

### 14.7 Yenileme Önerileri

Her sertifika için öncelik sıralı Türkçe aksiyon önerileri üretir: Kritik (derhal müdahale), Uyarı (yenileme planlanmalı), Bilgi (takip edilmesi gerekenler). Yenileme koordinasyonunu yürüten operasyon ekiplerinin haftalık gündem listesidir.

### 14.8 Değişim Rehberi

Sertifika yenileme süreçlerine dair kurumsal dokümanların ve dış bağlantıların kategorize koleksiyonudur (`guide_link`). Yenileme sırasında "hangi CA portalı, hangi iç prosedür" sorularının cevabını tek yerde tutar; bağlantıları yönetici ekler, düzenler ve sıralar.

### 14.9 Vade Takvimi

Sertifika vadelerinin analitik panelidir: KPI kartları (Kritik ≤7 gün / Yüksek 8–14 / Uyarı 15–30 / Toplam Aktif), günlük vade dağılım grafiği, takvim ısı haritası, takım ve durum bazlı yük dağılım pastaları ve yaklaşan süre sonları listesi. Takvimde bir güne tıklayıp o gün dolan sertifikaları görebilirsiniz. Kapasite planlaması yapan yöneticinin ekranıdır: "Kasım ayında kaç yenileme birikiyor?" sorusuna bir bakışta cevap verir.

### 14.10 Sertifika Envanteri

İzlenecek domainlerin kayıt defteridir. Tabloda domain+port, tier rozeti, takım, sahip, açıklama, aktiflik ve aksiyonlar (Düzenle / Devret / Sil / Geri Yükle) görünür. Global admin tüm envanteri yönetir; PO liderlik ettiği takımların kayıtlarını yönetir; müdür ve USER/AUDIT salt-okuma görür.

Yeni domain eklerken beş bölümlü form doldurursunuz:

- Temel Bilgiler: domain (FQDN), port (varsayılan `443`), sorumlu takım, tier, sahip, aktiflik.
- Operasyonel Bayraklar: Dış Tedarikçi, Aksiyon Gerekli, OpenShift, SSL Pinning, Dahili Sertifika, JKS Keystore, Sunucu Güncellemesi, Netscaler, WAF Aktif, Kullanımda, EV Sertifikası, Proxy Üzerinden Kontrol Et. Bu bayraklar kontrol hattını değiştirmez (proxy hariç); envanter raporlarında ve filtrelerde görünen operasyon meta verisidir.
- Süreç Bilgisi: satın alan kişi/birim.
- Açıklamalar: genel açıklama + süreç notu.
- Gelişmiş: beklenen parmak izi (SHA-256, dağıtım kontrolü için) ve beklenen subject.

Silme yumuşaktır (soft delete): kayıt gizlenir ama geçmişi korunur; "Silinenleri Göster" ile geri yüklersiniz. Devret aksiyonu sertifikayı başka takıma taşır. Domain adını değiştirirseniz tüm geçmiş atomik taşınır (bkz. §3.9).

### 14.11 Zayıf Algoritma Raporu

SHA-1 imza, RSA-1024 gibi güvensiz algoritma kullanan sertifikaları listeler: domain, imza/anahtar algoritması, anahtar boyutu, zayıflık tipi, kritiklik, sahip, takım, bitiş tarihi. Güvenlik ekibinin uyumluluk taramalarında ilk baktığı ekrandır.

### 14.12 İzleme Sekmeleri

**Durum İzleme**, **HTTP / Website**, **Port İzleme**, **DNS İzleme**, **Keyword İzleme**, **Ping İzleme**, **Sayfa Bütünlüğü**, **Sentetik İzleme** ve **Alan Adı İzleme** sekmelerinin her biri aynı düzeni izler: durum/grup/takım filtreli monitör listesi, monitör kartına tıklayınca kontrol geçmişi + alarm geçmişi + süre grafiği sekmeli detay penceresi, sağ üstte + Yeni Monitör. Süre grafikleri 24 saat / 7 gün / 30 gün / 90 gün hazır aralıkları ve özel aralık seçimiyle ortalama, min/maks bandı ve p95 gösterir; kesinti aralıkları kırmızı işaretlenir, ping grafiğinde paket kaybı ikinci eksende çizilir. Monitör davranışlarının ayrıntısı için bkz. [10. İzleme Türleri](#10-izleme-turleri).

### 14.13 Olaylar

İzleme alarmlarından türeyen kesinti olaylarının çalışma listesi. Her satırda başlangıç zamanı, durum, önem ve kök neden sınıfı görünür; filtreleyebilir, yorum ekleyebilir, çözülen olayları kapatabilirsiniz. Bir kesintinin "kaç dakika sürdü, ne zaman kapandı" cevabı buradadır.

### 14.14 Alarm Geçmişi

Tüm alarm olaylarının arşivi ve aksiyon merkezi. Filtreler: yalnız açık alarmlar, domain araması, seviye, tip. Tablo kolonları: domain, seviye (renkli rozet), tip, kalan gün, oluşturma tarihi, son bildirim, durum, aksiyonlar.

| Aksiyon | Etkisi |
|---|---|
| Onayla | "Gördüm" işareti; günlük tekrar bildirimi durur, alarm açık kalır |
| Yeniden Bildir | Tüm eskalasyon kişilerine `MANUAL` tetikleyicili bildirim — kota yok |
| Çözüldü İşaretle | `resolved=true`, çözen kişi kaydedilir, `RESOLUTION` bildirimi gider |

Satırı genişlettiğinizde bildirim geçmişi (alıcı, konu, e-posta/webhook durumu, tetikleyici) ile onay ve çözüm bilgileri açılır.

### 14.15 Haftalık Raporlar

Takımların haftalık operasyon raporunu yazdığı ve PO'nun onayladığı ekran. Hafta seçimi, olay özeti bölümü (acil/yüksek olay sayıları), açık problemler ve planlı işler listesi, durum rozeti (Taslak / Gönderildi / Onaylandı / İade).

Akış: raporu Gönder ile PO onayına iletirsiniz; PO'ya onay bağlantılı HTML e-posta gider ve PO ekrandan ya da e-postadaki bağlantıdan tek tıkla onaylar veya iade eder — e-posta token bağlantısı bilinçli olarak oturum açmadan çalışır. Cuma sabahı bekleyen raporlara otomatik hatırlatma gider; rapor başka takıma devredilebilir. USER kendi takımının raporunu yazar; PO liderlik ettiği takımları görür ve onaylar; global admin tümünü görür.

### 14.16 Olay ve Hata Geçmişi

Elle tutulan SRE olay defteridir — izlemeden bağımsız, kurumsal olay yönetimi kaydı. Her olayda önem (CRITICAL / HIGH / MEDIUM / LOW), durum (OPEN → INVESTIGATING → MITIGATED → RESOLVED), kategori (DATABASE / NETWORK / CERTIFICATE / APPLICATION / INFRASTRUCTURE / OTHER), problem tipi, etkilenen uygulama/sistemler, müşteri ve işlem adedi, markdown açıklama ve görsel ekler bulunur. Kanal/servis/hata kodu gibi açılır liste seçenekleri takıma özeldir — bir takımın eklediği değer diğerine sızmaz. Günlük trend grafiği olay yoğunluğunu gösterir; bildirim e-postaları gönderilmeden önizlenebilir.

### 14.17 Aktivite Logu

Her tarama koşusunun özetini listeler: çalışma zamanı, tetikleyici tipi (Manuel / Zamanlı / Bayat), kontrol edilen domain sayısı, uyarı ve hata sayıları, koşu kimliği (Run ID). Son N saat filtresiyle daraltılır. "Tarama gerçekten çalıştı mı?" sorusunun kanıt ekranıdır.

### 14.18 Etkinliklerim

Kendi hesabınızın denetim izi: giriş/çıkışlarınız, başarısız girişler, parola değişiklikleriniz ve yaptığınız envanter işlemleri; sonuç (SUCCESS / FAILURE / BLOCKED), tarih aralığı ve olay tipi filtreleriyle. Hesabınızda şüpheli hareket olup olmadığını yöneticiye sormadan kendiniz görürsünüz.

### 14.19 Denetim Logu

Global admin ve AUDIT rolüne açık, sistem genelindeki denetim kayıtları ekranı. Özet panel (24 saat / 7 gün olay ve başarısız giriş sayıları, anomali sayısı), kullanıcı / olay tipi / sonuç / yalnız-anomaliler filtreleri ve tarih, olay tipi, kullanıcı, IP, coğrafi konum, kaynak, sonuç, tarayıcı kolonlu tablo. Satırı genişlettiğinizde alan bazında eski → yeni değer farkları görünür.

### 14.20 Sorun Bildirimleri

Giriş ekranındaki "Sorun Bildir" akışıyla gelen kayıtların yönetim ekranı. Giriş yapamayan kullanıcı ekran görüntüsü ekleyerek kayıt bırakır; siz kayıtları OPEN → IN_PROGRESS → RESOLVED akışında yönetir, yanlış kapatılan kaydı yeniden açarsınız. Parola/kilit kaynaklı çağrı trafiğini yapılandırılmış kayda dönüştürür.

### 14.21 Yönetim Paneli

Yönetim işlevlerinin merkezi: envanter, kullanıcılar, takımlar, eskalasyon kişileri ve alarm eşikleri alt sekmeleri.

Eskalasyon Kişileri: kullanıcı, takım, org rolü, minimum alarm seviyesi (WARNING / HIGH / CRITICAL), isteğe bağlı Slack/Teams webhook URL'si ve aktiflik ile kişi tanımlarsınız. Ekranda eskalasyon matrisi özeti gösterilir: WARNING → PO + TECH; HIGH → + MANAGER; CRITICAL → + C-LEVEL. Kişi silindiğinde geçmiş bildirim logları korunur.

Alarm Eşikleri: Uyarı Günü (varsayılan 30), Yüksek Gün (15), Kritik Gün (7) ve Tekrar Bildirim Aralığı (24 saat) kartlarını satır içi düzenlersiniz.

Takım Yönetimi: takımlar kart görünümünde listelenir — ad, e-posta, lider (PO), müdür ve org rol rozetli üyeler. Yeni takım eklerken ad, e-posta, isteğe bağlı lider ve açıklama girersiniz. PO atanınca lidersiz takımda otomatik lider olur (mevcut lider ezilmez); müdür ilişkisi kurulunca takıma otomatik MANAGER eskalasyon kontağı eklenir. Sertifika ataması olan takım silinemez.

Kullanıcı Yönetimi: kullanıcı adı, sicil, ad soyad, e-posta, kaynak (Yerel/LDAP), sistem ve org rolü, takım, müdür, aktiflik ve kilit durumu kolonlu tablo. Aksiyonlar: yeni kullanıcı ekleme, düzenleme (kullanıcı adı hariç; AD kullanıcılarının org rol/takım/müdür alanları da düzenlenebilir), parola değiştirme (yerel hesaplar), kalıcı kilidi açma, silme. LDAP kullanıcıları ilk girişte otomatik oluşur; elle atanmış ADMIN/AUDIT sistem rolünü provizyon düşürmez.

### 14.22 Yetkiler

Rol bazında (TEAM_ADMIN / USER / AUDIT) `view` / `edit` / `execute` izinlerini açıp kapattığınız matris; ADMIN sütunu tam yetkili ve kilitlidir. Kaynaklar gruplara ayrılır (sertifikalar, iletişim, yönetim, alarmlar, izleme, kayıtlar, raporlar, araçlar, olaylar). Hassas işlemler için onay penceresi çıkar; "Varsayılanlara Dön" rol varsayılanlarına sıfırlar. Üstteki bilgi paneli güncel rol modelini özetler. Yalnız global admin erişir.

### 14.23 Ayarlar

Yalnız bootstrap admin hesabına açık, sol menülü yapılandırma merkezi. Değişiklikler canlı yansır — yeniden başlatma gerekmez:

| Bölüm | İçerik |
|---|---|
| Genel | Küratörlü çalışma zamanı ayarları (`AppSettingsCatalog`), CORS canlı yenileme |
| Marka | Beyaz etiket: kurum logosu, uygulama kimliği, duyuru şeridi |
| Monitör Grupları | İzleme gruplarının yönetimi |
| SMTP | Sunucu/port/STARTTLS, kimlik bilgileri, test e-postası |
| Haftalık Erişilebilirlik | Haftalık erişilebilirlik maili yapılandırması |
| Fırtına | Alarm fırtınası gruplama eşikleri ve aç/kapat |
| Login Anomali | Anomali algılama pencereleri |
| LDAP | Sunucu URL, base DN, bind kimliği (AES-GCM), öznitelik eşlemeleri, bağlantı testi + öznitelik görüntüleyici |
| Domain Tanılama | Alan adı vade sorgusu adım izi, proxy CA zinciri yakalama (PEM) |
| Veritabanı | Bağlantı ve şema bilgileri |
| Gizli Anahtar Araçları | Saklanan SMTP/LDAP parolalarının AES-GCM araçları (`SITE_MONITOR_SECRET_KEY` ile korunur) |

### 14.24 Sistem Sağlığı

Platformun kendi sağlık panosudur; 30 saniyede bir otomatik yenilenir. Bölümler:

- Zamanlayıcı: durum, çalışan Run ID, son/sonraki çalışma, aktif domain sayısı, instance kimliği.
- Dağıtık kilit: kilit tutulmuş mu, kim tutuyor, bitiş zamanı; sıkışan kilidi zorla serbest bırakma.
- Veritabanı havuzu (HikariCP): aktif/boşta/toplam bağlantı, bekleyen thread, maksimum havuz.
- JVM bellek: kullanılan/boş/toplam/maksimum ve yüzde.
- Tarama istatistikleri: son tarama süresi, domain/uyarı/hata sayıları, tarama bayatlık alarmı.
- SMTP: son 30 günün başarılı/başarısız e-posta sayıları, başarı oranı, tetikleyici filtreli teslimat logları.
- Veritabanı analitiği: `pg_stat_statements` ile DB genelinde en sık/en yavaş sorgular (eklenti yoksa playground geçmişine düşer), tablo boyutları, bağlantı durumu, saatlik/günlük zaman serileri.
- Kullanıcı/Oturum: login zaman serisi (1g/7g/30g + özel aralık), ISO haftalık 7×24 aktivite ısı haritası, en çok giriş yapan kullanıcılar ve kaynak IP'ler (reverse-DNS adlarıyla), aktif oturum listesi ve oturum sonlandırma.
- Tanı araçları: OpenSSL, ağ, HSTS ve bağlantı tanıları; koşu geçmişi `diagnostic_runs` tablosunda saklanır.

### 14.25 SQL Playground

Global admin'e özel salt-okuma SQL konsolu: yalnız `SELECT` çalışır, yazma sorguları reddedilir. Sonuç tablosu, sorgu geçmişi (gece temizlenir) ve satır detay penceresi vardır; tüm kullanım denetim kaydına işlenir. Panolarda olmayan anlık bir soruyu — "hangi takımın kaç açık alarmı var?" — rapor beklemeden cevaplarsınız.

### 14.26 Yardım

Bu kılavuzun kendisi: sol tarafta başlıklardan otomatik üretilen içindekiler, sağda içerik. Kılavuz uygulamayla birlikte sürümlenir ve PDF olarak indirilebilir.

---

## 15. Operasyonel Prosedürler

Bu bölüm günlük operasyonun tarif defteridir: sık yapılan işlerin adım adım, ekran adlarıyla anlatımı.

### 15.1 Yeni Domain Ekleme

1. **Sertifika Envanteri** sekmesine gidin ve "Alan Ekle" butonuna tıklayın.
2. Temel Bilgiler bölümünde domaini (ör. `api.example.com`), portu (çoğunlukla `443`), sorumlu takımı ve tier'ı girin.
3. Operasyonel bayrakları işaretleyin ve kaydedin.
4. Sistem domaini bir sonraki saatlik taramada kontrol eder; ilk sonuçları **Genel Bakış** ekranında görürsünüz.

İpucu: "Şimdi Kontrol Et" ile taramayı beklemeden tetikleyebilirsiniz.

### 15.2 Alarm Aldığınızda

1. E-postadaki domain ve kalan gün bilgisine bakın.
2. Konudan haberdarsanız **Alarm Geçmişi** ekranında alarmı Onaylayın — günlük tekrar bildirimleri durur.
3. Sertifika yenileme sürecini başlatın (CA, ekip, platform).
4. Yenileme tamamlandığında sistem bir sonraki taramada yeni sertifikayı otomatik görür.
5. Sorun giderildiyse "Çözüldü İşaretle" ile kapatın — çözülme bildirimi gönderilir.

Acil durumda (CRITICAL): alarmı hemen onaylayın, yenilemeyi derhal başlatın, gerekirse "Yeniden Bildir" ile üst yönetimi anında bilgilendirin, iş bitince "Çözüldü İşaretle" deyin.

### 15.3 Sertifika Yenilendikten Sonra

1. Yeni sertifikayı sunucuya/platforma dağıtın.
2. Site Monitor bir sonraki saatlik taramada yeni sertifikayı tespit eder.
3. Envanterde beklenen parmak izini güncellediyseniz dağıtım uyumluluğu da doğrulanır.
4. **Alarm Geçmişi** ekranında ilgili alarmı "Çözüldü İşaretle" ile kapatın; `RESOLUTION` bildirimi otomatik gider.

### 15.4 Takım Kurulumu

1. Kullanıcıları oluşturun: **Yönetim Paneli** → Kullanıcı Yönetimi → uygun sistem ve org rolleriyle.
2. Takımı oluşturun: Takım Yönetimi → lider (PO) ve takım e-postası.
3. Kullanıcıları takıma atayın: kullanıcı düzenleme penceresinden.
4. Eskalasyon kişilerini tanımlayın: her kişi için minimum alarm seviyesi, isterseniz Slack/Teams webhook.
5. Sertifikaları takıma atayın: **Sertifika Envanteri** → Düzenle → sorumlu takım.

LDAP kullanıyorsanız 1–3 adımları büyük ölçüde kendiliğinden gerçekleşir: kullanıcı ilk girişte provizyon edilir, müdür ilişkisi ve MANAGER eskalasyon kontağı otomatik kurulur.

### 15.5 Eskalasyon Kişisi Şablonu

```
Senaryo: Bir takım için eskalasyon ayarı

PO (Ürün Sahibi):        Min Seviye WARNING → her alarmda haberdar
Teknik Sorumlu (TECH):   Min Seviye WARNING → her alarmda haberdar
Yönetici (MANAGER):      Min Seviye HIGH    → yüksek ve kritik alarmlar
Direktör (C-LEVEL):      Min Seviye CRITICAL → yalnız kritik alarmlar
```

### 15.6 Bakım Penceresi Planlama

1. **Bakım** sekmesinde yeni pencere oluşturun; ad ve açıklama girin.
2. Hedefleri seçin: etkilenecek monitörler ya da "tüm monitörler".
3. Saat dilimini, başlangıç zamanını, süreyi ve tekrarlamayı (tek seferlik / günlük / haftalık / aylık) ayarlayın.
4. Pencere aktifken hedefler için alarm açılmaz, bildirim gitmez ve uptime yüzdesi etkilenmez; süre bitiminde izleme kaldığı yerden devam eder.

### 15.7 Backend Kapalı Kaldığında

Backend kapalıyken tarama durur ve bildirim gönderilmez. Yeniden açıldığında startup catch-up devreye girer: o güne ait gönderilmemiş günlük bildirimler ağ çağrısı olmadan anında gönderilir, ardından tam tarama başlar. Sonuç: backend ne kadar kapalı kalırsa kalsın o günün bildirimleri — geç de olsa — kesinlikle gönderilir.

### 15.8 Eski Kayıtların Otomatik Temizliği

Her gece 03:30'da (`site.monitor.scheduler.cleanup-cron`) temizlik işi çalışır:

| Tablo | Saklama Süresi |
|---|---|
| `audit_log` | 180 gün |
| `notification_logs` | 90 gün |
| `sql_query_history` | 30 gün |

Silme native toplu `DELETE` ile kısa transaction'da yapılır; iş başarısız olursa uygulama etkilenmez, ertesi gece yeniden denenir.

### 15.9 Production Ortamında Proxy Kullanımı

Bazı domainlerin WAF/firewall'u izleme pod'unun IP'sini reddedebilir. Böyle bir domain için:

1. Deployment YAML'ına `HTTP_PROXY_HOST` ve `HTTP_PROXY_PORT` ortam değişkenlerini ekleyin ve pod'u yeniden başlatın.
2. **Sertifika Envanteri** ekranında ilgili domaini düzenleyip "Proxy Üzerinden Kontrol Et" anahtarını açın.
3. Sonraki sweep'te yalnız o domainin kontrolü proxy üzerinden gider; loglarda `[cert-proxy]` etiketiyle tunnel adımlarını izleyebilirsiniz.

Uyarı: tüm pod trafiğini proxy'ye yönlendirmek diğer domainlerin kontrollerini kırar — yalnız sorun yaşayan domainleri işaretleyin.

---

## 16. Dağıtım ve DevOps

Site Monitor tek imaj olarak paketlenir ve üç GitHub Actions iş akışıyla yaşar: doğrulama, imaj üretimi ve sürümleme birbirinden ayrıdır.

### 16.1 CI/CD Hattı

```
ci.yml (her push/PR)
  ├─ Backend: Java 25, mvn -B clean verify (Surefire + Jacoco artefaktları)
  ├─ Frontend: Node 24, npm ci → lint → test → build → npm audit (bilgilendirici)
  └─ Helm lint: üç ortam values dosyasına karşı

docker-build.yml
  ├─ Çok mimarili imaj derlemesi
  └─ Trivy güvenlik taraması (HIGH/CRITICAL, rapor modunda)

release.yml (main dalı)
  ├─ Conventional commit önekinden bump türü: feat → minor, fix → patch,
  │  BREAKING CHANGE → major
  ├─ VERSION dosyasını yazar, Helm chart'ı yayınlar
  └─ Testleri tekrar KOŞMAZ — ci.yml sonucuna güvenir
```

`VERSION` dosyasını ve `Chart.yaml` sürümünü elle düzenlemeyin: her sürüm `release.yml` tarafından otomatik yazılır; elle bump sonraki senkronizasyonda çakışma üretir.

### 16.2 Ortam Stratejisi

| Values Dosyası | Ortam | Replica | Not |
|---|---|---|---|
| `develop.yaml` | Geliştirme | 1 | E-posta kapalı, 2 saatlik tarama, HPA kapalı |
| `release.yaml` | Staging | 2 | Ara doğrulama ortamı |
| `master.yaml` | Production | 3 (maks. 10) | HPA + PDB aktif |

### 16.3 Helm Dağıtımı

```bash
helm upgrade --install site-monitor ./helm/site-monitor \
  -f helm/site-monitor/values.yaml \
  -f helm/site-monitor/environments/master.yaml \
  --set image.tag=20.0.3 \
  --set secret.adminPassword=$ADMIN_PASSWORD \
  --set secret.dbPassword=$DB_PASSWORD \
  -n site-monitor --create-namespace
```

Helm chart'ı Bitnami PostgreSQL'i bağımlılık olarak içerir ve `shared_preload_libraries = 'pg_stat_statements'` ön yüklemesini yapar — Sistem Sağlığı ekranındaki veritabanı analitiği bu eklentiyle tam kapasite çalışır; eklenti yoksa uygulama zarifçe geri düşer.

### 16.4 Docker İmajı

```
Kayıt defteri : ghcr.io/inanmise/site-monitor
Etiketler:
  - latest              (üretim son)
  - 20.0.3              (üretim sürümü)
  - develop-a1b2c3d     (geliştirme commit'i)
  - 20.0.3-rc           (staging release candidate)
```

İmaj üç aşamalıdır: `node:20-alpine` (frontend build) → `maven:3.9` (backend build) → `eclipse-temurin:25-jre-alpine` (çalışma zamanı). Konteyner-farkındalıklı JVM (heap ≈ RAM'in %75'i), pod içi tanı araçları (`curl`, `bash`, `dig`) ve `wget /health` sağlık kontrolü içerir; imaj `appuser:1000` olarak çalışır.

---

## 17. Yüksek Erişilebilirlik

İzleme platformunun kendisi kesintiye düşerse alarm da düşer; bu yüzden Site Monitor üretimde en az üç replika ile, sıfır kesintili güncelleme ve otomatik ölçekleme varsayımıyla çalışır.

### 17.1 Pod Dağıtımı

```yaml
replicas: 3           # Minimum 3 pod
maxSurge: 1           # Rolling update: önce +1 pod
maxUnavailable: 0     # Sıfır kesinti güncelleme

topologySpreadConstraints:
  maxSkew: 1          # Node'lar arası en fazla 1 pod farkı
```

### 17.2 Pod Disruption Budget

```yaml
minAvailable: 2      # Production: en az 2 pod ayakta
                     # Staging: en az 1 pod
```

### 17.3 Sağlık Kontrolleri

| Tip | Endpoint | Başlama | Periyot | Başarısızlık |
|---|---|---|---|---|
| Startup | `/health/readiness` | — | 10 sn | 12 hata → restart |
| Readiness | `/health/readiness` | 30 sn | 10 sn | 3 hata → trafik kesilir |
| Liveness | `/health/liveness` | 60 sn | 30 sn | 3 hata → restart |

Liveness veritabanından bağımsızdır — kısa bir DB kesintisi pod'u yeniden başlatmaz. Açılış bootstrap'ı boyunca readiness `REFUSING_TRAFFIC` döner: pod ancak gerçekten hazır olduğunda Service arkasına girer, soğuk başlangıçta "hazır görünüp ilk istekleri asma" sorunu yaşanmaz.

### 17.4 Dağıtık Oturum

```properties
SPRING_SESSION_STORE_TYPE=jdbc
```

JDBC oturum deposu açıkken tüm pod'lar oturumları paylaşır; bir pod yeniden başlasa bile kullanıcı oturumu kaybolmaz.

### 17.5 Tek Tarayıcı Garantisi

Dağıtık zamanlayıcı kilidi (TTL 10 dk) sayesinde kaç replika çalışırsa çalışsın taramayı aynı anda yalnız bir pod yürütür (bkz. §5.3).

---

## 18. Konfigürasyon Referansı

Tüm konfigürasyon `application.properties` anahtarlarıdır ve ortam değişkenleriyle geçersiz kılınır. Gerçek değerler hiçbir zaman commit edilmez; `.env.example` ve `k8s/secret.example.yaml` şablonlarını kullanırsınız.

### 18.1 Temel Konfigürasyon

| Parametre | Varsayılan | Açıklama |
|---|---|---|
| `site.monitor.warning-days` | 30 | Uyarı başlangıç günü |
| `site.monitor.parallel-workers` | 20 | Eş zamanlı kontrol sayısı |
| `site.monitor.check-timeout-seconds` | 10 | SSL soket zaman aşımı |
| `site.monitor.scheduler.cron` | `0 0 * * * *` | Saatlik tarama cron'u |
| `site.monitor.scheduler.stale-minutes` | 65 | Bayat domain eşiği (dk) |
| `site.monitor.scheduler.lock-ttl-minutes` | 10 | Dağıtık kilit TTL |
| `site.monitor.cache.crl-ttl-hours` | 1 | CRL önbellek süresi |
| `site.monitor.cache.crl-max-size` | 200 | CRL önbellek kapasitesi |
| `site.monitor.alert.default-warning-days` | 30 | Uyarı gün eşiği |
| `site.monitor.alert.default-high-days` | 15 | Yüksek gün eşiği |
| `site.monitor.alert.default-critical-days` | 7 | Kritik gün eşiği |
| `site.monitor.alert.default-realert-hours` | 24 | Tekrar bildirim aralığı |
| `site.monitor.scheduler.cleanup-cron` | `0 30 3 * * *` | Gece temizlik cron'u |
| `EXECUTOR_CORE_SIZE` | 20 | certCheckExecutor çekirdek thread |
| `EXECUTOR_MAX_SIZE` | 50 | certCheckExecutor maksimum thread |
| `EXECUTOR_QUEUE_CAPACITY` | 1000 | certCheckExecutor kuyruk boyutu |
| `mail.send.retry-delay-ms` | 90000 | SMTP 421 asenkron retry gecikmesi |
| `NETWORK_ERROR_THRESHOLD` | 0.50 | Toplu ağ kesintisi oranı eşiği |
| `NETWORK_MIN_ERRORS` | 3 | Toplu kesinti için asgari hata sayısı |
| `DOMAIN_CRITICAL_CHECK_CRON` | `0 0 16 * * *` | Kritik alan adı ikinci kontrolü |
| `site.monitor.storm.enabled` | true | Alarm fırtınası gruplaması |
| `site.monitor.dns.query-timeout-ms` | 2000 | DNS sorgu zaman aşımı |
| `site.monitor.dns.resolvers` | `8.8.8.8, 1.1.1.1, 9.9.9.9` | Propagation çözümleyicileri |

Proxy / dış bağlantı:

| Parametre | Açıklama |
|---|---|
| `HTTP_PROXY_HOST` / `HTTP_PROXY_PORT` | Kurumsal HTTP CONNECT proxy (OCSP/CRL ve `use_proxy` domainleri) |
| `HTTP_PROXY` | Alternatif URL formatı: `http://user:pass@host:port` |
| `NO_PROXY` | Proxy bypass sonek listesi (virgülle ayrılmış) |
| `TLS_MODE` | `browser` (varsayılan; TLS 1.2 + ALPN) veya `default` (hata ayıklama) |

Log seviyeleri:

| Parametre | Açıklama |
|---|---|
| `logging.level.com.sitemonitor` | Varsayılan `DEBUG` |
| `logging.level.com.sitemonitor.config.RequestLoggingFilter` | `TRACE` ile tam HTTP logu (maskeli) |
| `logging.level.root` | Varsayılan `INFO` |
| `LOG_TIMEZONE` | Varsayılan `Europe/Istanbul` |

### 18.2 Kimlik ve Kilitleme Konfigürasyonu

| Parametre | Varsayılan | Açıklama |
|---|---|---|
| `site.monitor.lockout.failures-needed` | `5,3,2,1` | Her kademede gerekli hata sayısı |
| `site.monitor.lockout.durations-seconds` | `30,120,600,1800` | Her kademede bekleme |
| `site.monitor.lockout.permanent-failures` | 5 | Kalıcı kilit eşiği |
| `site.monitor.remember-me.validity-seconds` | 604800 | "Beni Hatırla" süresi (7 gün) |
| `site.monitor.inactivity-timeout-minutes` | 5 | Hareketsizlik zaman aşımı |
| `PASSWORD_MIN_LENGTH` / `MAX_LENGTH` / `HISTORY_COUNT` | 12 / 64 / 5 | Parola politikası |

### 18.3 E-posta Konfigürasyonu

| Parametre | Açıklama |
|---|---|
| `SITE_MONITOR_EMAIL_ENABLED` | E-postayı etkinleştir (true/false) |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` | SMTP sunucu ve port (587 = STARTTLS) |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | Gönderen hesap kimliği |
| `SITE_MONITOR_EMAIL_FROM` | Gönderen adres |
| `APP_BASE_URL` | E-posta bağlantılarının taban adresi |

---

## 19. Sürüm ve Yayın Bilgisi

| Bilgi | Değer |
|---|---|
| Güncel Versiyon | 20.0.3 |
| Belge Tarihi | Ağustos 2026 |
| Java | 25 (LTS) |
| Spring Boot | 4.1.0 (Spring Framework 7, Jakarta EE 11) |
| BouncyCastle | 1.78.x |
| React / Vite | 18.3 / 5.4 |
| PostgreSQL | K8s manifesti 16-alpine; Helm chart Bitnami PostgreSQL 18.x |
| Docker İmajı | `ghcr.io/inanmise/site-monitor` |
| Helm Chart | `ghcr.io/inanmise/sitemonitor-chart` |
| Desteklenen Diller | Türkçe / İngilizce |
| Lisans | Kurumsal kullanım |
| Geliştirici | inanmise (erdi.inanmis@gmail.com) |

Sürüm numarası tek `VERSION` dosyasından gelir ve conventional commit önekleriyle otomatik artar:

| Önek | Bump | Örnek |
|---|---|---|
| `feat:` | Minor | 20.0.x → 20.1.0 |
| `fix:` / `chore:` / `refactor:` | Patch | 20.0.3 → 20.0.4 |
| `BREAKING CHANGE` | Major | 20.0.x → 21.0.0 |

### 19.1 Yakın Dönem Sürüm Vurguları

20.x kuşağı, ürünün Site Monitor adıyla ve genişletilmiş izleme ailesiyle olgunlaştığı kuşaktır. Öne çıkanlar:

- Marka ve e-posta yenilemesi: durum-duyarlı turp logosu tüm arayüzde, dinamik favicon'da ve e-postalarda; e-posta şablonları Outlook-güvenli kart tasarımına geçti (bkz. §9.1).
- İzleme ailesi tamamlandı: HTTP/Website, Keyword, Ping, Sayfa Bütünlüğü, Sentetik (k6) ve Alan Adı tescil izlemesi; monitör detaylarında 30/90 güne kadar süre grafikleri (ortalama + min/maks bandı + p95, ping'de paket kaybı ikinci eksende), per-monitör doğrulama/kurtarma denemeleri.
- Bakım pencereleri: DST-güvenli tekrarlama, tam sessizlik garantisi, uptime yüzdesi muafiyeti.
- Alarm fırtınası gruplaması ve toplu ağ kesintisi bastırması ile bildirim gürültüsü kontrolü.
- Sistem Sağlığı genişlemesi: `pg_stat_statements` tabanlı veritabanı analitiği, login zaman serileri, ISO haftalık aktivite ısı haritası, reverse-DNS'li kaynak listesi, kullanıcı rozeti (ad-soyad + avatar) tüm ekranlarda.
- Olay modülü: problem tipi, etkilenen uygulama/sistem/müşteri-işlem adetleri, takıma özel açılır liste seçenekleri, günlük trend grafiği.
- OpenShift sağlamlaştırması: Actuator probe grupları, açılışta readiness-gating, DB'den bağımsız liveness, warmup CPU tavanı.
- Güvenlik: bootstrap-admin geçidi konfigüre kullanıcı adıyla (literal "admin" değil), yıkıcı işlemler için ayrık hassas yetkiler, parola politikası 12/64/5.

### 19.2 Platform Yükseltmesi JDK 25 ve Spring Boot 4.1

- Çalışma zamanı: Java 21 → 25 (LTS); Spring Boot 3.3 → 4.1 (Spring Framework 7, Jakarta EE 11, Hibernate 7, Tomcat 11).
- Jackson 2 → Jackson 3 (`tools.jackson`); SNAKE_CASE API sözleşmesi controller JSON testleriyle korunarak taşındı.
- Test çerçevesi: `@MockBean` → `@MockitoBean`, modüler test starter'ları; JaCoCo ve Lombok JDK 25 uyumlu sürümlere yükseltildi.
- Operasyonel not: Spring Session 4 oturum şeması değişikliği nedeniyle ilk dağıtımda kullanıcılar bir kez yeniden giriş yapabilir.

### 19.3 Önceki Kuşaklardan Kalıcı Kazanımlar

- Kimlik ve provizyon: LDAP/AD girişi, ilk girişte otomatik provizyon, çok-takım yetki kapsamı (müdür = kapsamlı salt-okuma ADMIN, PO = TEAM_ADMIN).
- Tek takım modeli: SY/UG ikili takım yapısı kaldırıldı; kart görünümlü takım yönetimi.
- Haftalık raporlar, e-posta bağlantılı PO onayı, cuma hatırlatması.
- Dayanıklılık: HTTP bağlantı sızıntıları kapatıldı, `GlobalExceptionHandler` catch-all, ErrorBoundary katmanları, `Promise.allSettled` yüklemeleri.
- Performans: eskalasyon N+1 çözümü (sweep başında batch ön yükleme), sweep sonu tek cache eviction, asenkron SMTP 421 retry.

---

## 20. Production Dağıtım ve Güvenlik Kontrol Listesi

Varsayılan konfigürasyon yerel geliştirme içindir ve kasıtlı olarak güvensizdir; prod profili ve ortam değişkenleri bu varsayılanları ezmek zorundadır. Prod'a çıkmadan önce bu listeyi tamamlayın.

### 20.1 Zorunlu Güvenlik Değişkenleri

| Değişken | Neden | Güvensiz Varsayılan |
|---|---|---|
| `SITE_MONITOR_SECRET_KEY` | Saklanan LDAP/SMTP parolaları bu anahtarla AES-GCM şifrelenir. Boşsa dahili dev anahtarına düşer. En az 32 rastgele karakter, kalıcı olmalı. | boş → dev anahtarı |
| `SITE_MONITOR_USERNAME` / `SITE_MONITOR_PASSWORD` | Bootstrap admin kimliği | `user` / `password` |
| `CORS_ALLOWED_ORIGINS` | Yalnız prod host'larına izin verin | `localhost:5173/3000` |
| `COOKIE_SECURE=true` | Oturum çerezi yalnız HTTPS üzerinden | `false` (prod profilinde `true`) |
| `DB_PASSWORD` | PostgreSQL parolası (prod profilinde zorunlu) | — |

### 20.2 Önerilen Ayarlar

| Değişken | Açıklama |
|---|---|
| `APP_BASE_URL=https://<host>` | E-posta bağlantıları bu adresi kullanır; yoksa localhost üretilir |
| `SYSTEM_ADMIN_EMAIL` | Ağ kesintisi ve sistem bildirimlerinin alıcısı |
| `SITE_MONITOR_EMAIL_ENABLED=true` + SMTP | E-posta kapalıyken alarm bildirimleri sessizce gönderilmez |
| `SPRING_SESSION_STORE_TYPE=jdbc` | Pod yeniden başlatmalarında oturumlar korunur |
| `PASSWORD_MIN_LENGTH` vb. | Varsayılanlar prod uyumlu (12/64/5); kurumsal politikaya göre artırın |

### 20.3 Erişim ve Yetki Kontrolleri

- Bootstrap-admin geçidi: SMTP/LDAP/Secret/DB/Genel ayarlarına `site.monitor.username` ile tanımlı bootstrap admin her zaman erişir — hesap yeniden adlandırılsa da kilitlenme olmaz, "admin" adlı başka bir kullanıcı bypass alamaz.
- Yetki matrisi: ADMIN kilitlidir ve geri alınamaz. Yıkıcı işlemler hassas işaretlidir; grant verirken onay istenir, sistem-geneli iki işlem ayrıca admin korumalıdır.
- Varsayılan izinler ilk açılışta tohumlanır; yeni sürümlerin izinleri mevcut veritabanına geri doldurulur, yönetici özelleştirmeleri korunur.

### 20.4 OpenShift ve Kubernetes Notları

- Sağlık probe'ları Actuator gruplarını kullanır: liveness DB'den bağımsızdır (DB dalgalanması pod'u yeniden başlatmaz); readiness ve startup açılış bootstrap'ı bitene kadar trafiği reddeder.
- Dağıtım `helm upgrade` ile yapılır; `oc rollout status` ile doğrulanır. Soğuk başlangıç için CPU limiti warmup'ı hızlandırır.
- Kurumsal SSL-inspection proxy'si RDAP'ı bozuyorsa proxy CA zincirini **Ayarlar → Domain Tanılama** ile yakalayıp `site.monitor.trust.ca-bundle-pem` güven paketine ekleyin; `.tr` alan adları RDAP sunmadığından port-43 WHOIS erişimi ayrıca firewall izni gerektirir.

---

## 21. Terimler Sözlüğü

Kılavuz boyunca terimler tek biçimde kullanılır; İngilizce arayüz metinlerindeki karşılıklar aşağıdadır.

| Terim | Tanım |
|---|---|
| Alarm | Tespit edilen bir sorunun kayıt altına alınmış olayı (`AlertEvent`). İngilizce arayüzde "alert". Bu kılavuzda "uyarı" kelimesi yalnız WARNING seviyesinin Türkçe adı olarak geçer. |
| Alarm seviyesi | WARNING (Uyarı) / HIGH (Yüksek) / CRITICAL (Kritik) — sorunun aciliyet derecesi. |
| Alarm tipi | Sorunun sınıfı: `EXPIRY`, `REVOKED`, `CHAIN_BROKEN`, `MISMATCH`, `ACCESSIBILITY`, `PORT_DOWN`, `DNS_FAILURE`, `DNS_CHANGED`, `DOMAINMON_*` vb. |
| Onaylama (acknowledge) | Alarmın görüldüğünü işaretleme; günlük tekrar bildirimini durdurur, alarmı kapatmaz. |
| Çözülme (resolution) | Sorunun giderilmesi; alarm kapanır ve yeşil çözülme bildirimi gönderilir. |
| Tekrar bildirim (re-alert) | Onaylanmamış açık alarm için günde bir gönderilen hatırlatma (`DAILY_REALERT`). |
| Eskalasyon | Alarm seviyesine göre bildirim alıcılarının genişletilmesi (PO → TECH → MANAGER → C-LEVEL). |
| Eskalasyon kontağı | Bir takımın alarmlarını alan kişi kaydı; minimum seviye ve isteğe bağlı webhook taşır. |
| Doğrulama denemesi | Alarm açılmadan önce aranan ardışık başarısız kontrol sayısı. |
| Kurtarma | Monitörün "düzeldi" sayılması için aranan ardışık başarılı kontrol sayısı. |
| Alarm fırtınası (storm) | Kısa pencerede çok sayıda kesintinin tek toplu bildirime indirgenmesi. |
| Bakım penceresi | Planlı kesinti aralığı; hedef monitörlerde alarm ve bildirim tamamen susturulur. |
| Sweep | Zamanlayıcının tüm hedefleri dolaştığı tek tarama koşusu. |
| Envanter | İzlenen sertifika domainlerinin kayıt defteri (`certificate_inventory`). |
| Tier | Domain kritiklik katmanı: 1 Müşteriye Dönük Prod … 4 Dev/Sandbox. |
| Monitör | Sertifika sweep'inden bağımsız tekil izleme hedefi (HTTP, port, DNS, keyword, ping, sayfa, sentetik, alan adı). |
| Uptime yüzdesi | Bir hedefin başarılı kontrol oranı; bakım penceresindeki kontroller hariç tutulur. |
| RDAP / WHOIS | Alan adı tescil bilgisinin sorgulandığı protokoller; RDAP birincil, WHOIS yedektir. |
| OCSP / CRL | Sertifika iptal kontrol yöntemleri; OCSP birincil, CRL yedektir. |
| Dağıtık kilit | Çok replikalı ortamda taramanın tek pod'da çalışmasını sağlayan veritabanı kilidi. |
| Bootstrap admin | `SITE_MONITOR_USERNAME` ile tanımlı, ayarlar ekranına her zaman erişebilen yerel yönetici hesabı. |
| Soft delete | Kaydı fiziksel silmek yerine pasifleyip gizleme; geri yüklenebilir. |
| Provizyon | LDAP/AD kullanıcısının ilk girişte otomatik oluşturulması ve rol/takım atanması. |
| Webhook | Slack/Teams kanallarına yapılan HTTP bildirimi. |
| CID inline | E-posta içine gömülü görsel eki (`cid:` referansı); harici kaynak engelli istemcilerde de görünür. |

---

*Bu belge Site Monitor v20.0.3 için Ağustos 2026 itibarıyla hazırlanmıştır.*
