# Site Monitör — Kurumsal SSL/TLS Sertifika İzleme Platformu

> Not: Ürün adı CertMonitor → **Site Monitör** olarak değişti; indirilebilir PDF bir sonraki
> whitepaper güncellemesinde yeni adla yenilenecek.
## White Paper | Versiyon 19.11.x | Haziran 2026

---

## İçindekiler

1. [Yönetici Özeti](#1-yönetici-özeti)
2. [Problem Tanımı](#2-problem-tanımı)
3. [Ürün Nedir — Ne Yapar](#3-ürün-nedir--ne-yapar)
4. [Mimari ve Teknoloji Yığını](#4-mimari-ve-teknoloji-yığını)
5. [Sistem Topolojisi](#5-sistem-topolojisi)
6. [Veritabanı Şeması](#6-veritabanı-şeması)
7. [Sertifika Kontrol Akışı](#7-sertifika-kontrol-akışı)
8. [Alarm ve Eskalasyon Mekanizması](#8-alarm-ve-eskalasyon-mekanizması)
9. [Bildirim Sistemi](#9-bildirim-sistemi)
10. [Güvenlik Modeli](#10-güvenlik-modeli)
11. [Kullanıcı Rolleri ve Yetki Matrisi](#11-kullanıcı-rolleri-ve-yetki-matrisi)
12. [Kullanıcı Ekranları ve Aksiyonlar](#12-kullanıcı-ekranları-ve-aksiyonlar)
13. [Operasyonel Prosedürler](#13-operasyonel-prosedürler)
14. [Dağıtım ve DevOps](#14-dağıtım-ve-devops)
15. [Yüksek Erişilebilirlik](#15-yüksek-erişilebilirlik)
16. [Konfigürasyon Referansı](#16-konfigürasyon-referansı)
17. [Sürüm ve Yayın Bilgisi](#17-sürüm-ve-yayın-bilgisi)
18. [Production Dağıtım & Güvenlik Checklist'i](#18-production-dağıtım--güvenlik-checklisti)

---

## 1. Yönetici Özeti

**Site Monitör**, kurumsal ortamlarda çalışan SSL/TLS sertifikalarını otomatik olarak izleyen, süresi dolmak üzere olan veya hatalı sertifikalar için sorumlu ekipleri proaktif olarak bilgilendiren, takım tabanlı sorumluluk yönetimi ve tam denetim izine sahip bir **kurumsal sertifika izleme platformudur.**

### Temel Sorun

SSL/TLS sertifikalarının süresi beklenmedik anlarda dolduğunda:
- Müşteri hizmetleri kesintiye uğrar (HTTPS bağlantısı kesilir)
- Tarayıcı güvenlik uyarıları son kullanıcıları etkiler
- Finansal kurumlarda düzenleyici uyumsuzluk riski doğar
- Siber saldırganlar geçersiz sertifika fırsatından yararlanabilir

### Çözüm

Site Monitör bu sorunu çözmek için:
- Tüm sertifikaları saatlik periyotlarla otomatik kontrol eder
- Sertifika zinciri, iptal durumu ve dağıtım uyumluluğunu doğrular
- Süre dolmadan **30 / 15 / 7 gün önce** ilgili ekiplere e-posta ve webhook bildirimi gönderir
- Sorumlulukları takım bazında yönetir (her sertifika bir takıma atanır; takımın lideri/müdürü/üyeleri ile)
- Tüm işlemleri coğrafi konum ve tarayıcı bilgisiyle denetim kaydına yazar

### Hedef Kitle

| Kullanıcı Profili | Kullanım Amacı |
|---|---|
| Operasyon / Altyapı Ekipleri | Sertifikaları izleme, yenileme koordinasyonu |
| Uygulama Geliştirme Ekipleri | Uygulama SSL sertifikalarını izleme, haftalık rapor girişi |
| BT Güvenlik Ekibi | Denetim, zayıf algoritma tespiti, uyumluluk |
| Yöneticiler (PO, Müdür, C-Level) | Genel durum izleme, eskalasyon bildirimleri alma, rapor onayı |
| Sistem Yöneticileri | Platform kurulumu, yapılandırma, kullanıcı/yetki yönetimi |

---

## 2. Problem Tanımı

### 2.1 Sertifika Yönetiminin Karmaşıklığı

Modern kuruluşlar onlarca, hatta yüzlerce aktif SSL/TLS sertifikasını yönetmek durumundadır. Bu sertifikalar:

- Farklı sertifika otoritelerinden (CA) temin edilebilir
- Farklı sunucular, yük dengeleyiciler veya CDN sistemlerine dağıtılmış olabilir
- Farklı geçerlilik sürelerine sahip olabilir (90 gün ila 2 yıl)
- Farklı takımların sorumluluğunda bulunabilir

### 2.2 Manuel İzlemenin Yetersizliği

Sertifika sürelerini Excel tablosu veya takvim hatırlatıcısıyla takip etmek:
- İnsan hatasına açıktır
- Organizasyon değişikliklerinde (personel rotasyonu) bilgi kaybına yol açar
- Sertifika zinciri ve iptal durumunu kontrol etmez
- Gerçek zamanlı görünürlük sağlamaz

### 2.3 Site Monitör'ün Sağladığı Değer

```
Reaktif Yaklaşım              →  Proaktif Yaklaşım
─────────────────────────────────────────────────────
Sertifika dolunca haberdar ol    30 gün öncesinden haberdar ol
Manuel kontrol                   Otomatik saatlik tarama
Kimin sorumlu olduğu belirsiz    Takım bazlı sorumluluk
E-posta zinciri kaos             Yapılandırılmış eskalasyon
Denetim izi yok                  Tam audit log
```

---

## 3. Ürün Nedir — Ne Yapar

### 3.1 Temel Yetenekler

**Otomatik Sertifika Taraması**
- Envanterdeki tüm aktif domainleri saatlik olarak tarar
- Her domain için TCP/SSL el sıkışması yaparak gerçek sertifika bilgisini alır
- 20 paralel worker ile büyük envanterleri hızla işler

**Kapsamlı Sertifika Analizi**
Her taramada şu bilgiler elde edilip kaydedilir:

| Alan | Açıklama |
|---|---|
| Subject / Issuer | Sertifika sahibi ve veren kurum |
| Geçerlilik tarihleri | Not Before / Not After |
| Kalan gün sayısı | Anlık hesaplama |
| SAN (Subject Alternative Names) | Tüm geçerli alan adları |
| Parmak izi (SHA-256) | Sertifika kimliği |
| Anahtar algoritması | RSA, EC, DSA |
| Anahtar boyutu | 2048, 4096 bit vb. |
| İmza algoritması | SHA-256, SHA-1 vb. |
| Key Usage / EKU | Sertifika kullanım amaçları |
| CA sertifikası mı | Ara/kök CA tespiti |
| Sertifika zinciri durumu | VALID / BROKEN |
| İptal durumu (OCSP/CRL) | VALID / REVOKED / UNDETERMINED |
| Dağıtım durumu | OK / INCOMPLETE |
| OCSP URL | Canlı iptal kontrolü için |
| CRL URL | İptal listesi için |

**Zincir Doğrulama**
- Yaprak sertifikadan kök CA'ya tüm zinciri analiz eder
- Ara CA sertifikalarının kalan sürelerini hesaplar
- Zincir kırıksa `CHAIN_BROKEN` alarmı üretir

**İptal Kontrolü**
- Önce OCSP (Online Certificate Status Protocol) ile kontrol
- OCSP başarısız olursa CRL (Certificate Revocation List) ile kontrol
- CRL dosyaları 1 saat önbelleklenir (200 giriş kapasitesi)

**Dağıtım Uyumluluk Kontrolü**
- Envantere kaydedilen beklenen parmak izi ile sunucudaki parmak izini karşılaştırır
- Yeni sertifika temin edilmiş ama sunucuya dağıtılmamışsa `MISMATCH` alarmı üretir

**Domain Bazlı Proxy Yönlendirmesi (`use_proxy`)**
- Çoğu kontrol DİREKT outbound bağlantı ile yapılır
- Envanterde "Proxy Üzerinden Kontrol Et" işareti olan domain'ler için kontrol kurumsal HTTP CONNECT proxy üzerinden gönderilir
- WAF/firewall'un pod IP'sini reddettiği özel domain'ler için tasarlanmıştır
- OCSP / CRL fetch'leri de aynı proxy ayarını kullanır (`ChainValidationService`)

**Kritiklik Katmanı (Tier 1–4)**
- Her domain bir tier'a atanır: 1=Müşteri-Yüzlü Prod, 2=Dahili Prod, 3=UAT/Pre-Prod, 4=Dev/Sandbox
- Sıralama, kriticilik rozetleri ve eskalasyon kişi seçiminde rol oynar
- Tier=1 alarmları daha yüksek öncelikle takım yöneticilerine eskalasyon yapılır

**Sertifika Dışı İzleme**
- DNS kaydı izleme (A/AAAA/CNAME/MX/TXT) — değişim algılama (`CHANGED` / `ROTATED` event)
- Port izleme (TCP/UDP açık-kapalı + bağlantı süresi)
- Uptime HTTP izleme (statü kodu + yanıt süresi)
- Sertifika sweep'inden bağımsız scheduler entry'leri

**Domain Yeniden Adlandırma**
- Admin envanterde domain değiştirdiğinde geçmiş tüm kontrol verisi, alarm geçmişi ve notlar yeni domain'e atomik olarak taşınır (`@Transactional`)
- Aynı isimde kayıt varsa 409 ile uyarı verilir (bilgi sızıntısı yok)

**LDAP / Active Directory Entegrasyonu**
- Yerel hesapların yanında AD/LDAP ile kimlik doğrulama (login anında bind)
- İlk girişte kullanıcı otomatik provizyon edilir: ünvan/`company` → orgRole (PO/MANAGER/TECH), `manager` → müdür ilişkisi, takım ataması
- Müdür–takım ilişkisi kurulunca takıma otomatik MANAGER eskalasyon kontağı eklenir
- Bind parolası AES-GCM ile şifrelenir; bağlantı Ayarlar ekranından test edilebilir (öznitelik görüntüleyici dahil)

**Haftalık Raporlar**
- Takımlar haftalık operasyon raporu girer (acil/yüksek olay sayıları, açık problemler, planlı işler)
- PO **e-posta bağlantısı üzerinden onay** verir (tek tıkla onay/iade)
- Cuma hatırlatma ve özet e-postaları; rapor başka takıma transfer edilebilir

**Yetki Matrisi (Permissions)**
- Rol bazında (TEAM_ADMIN/USER/AUDIT) view/edit/execute izinleri ekrandan açılıp kapanır; ADMIN tam yetkili ve kilitlidir
- Hassas işlemler için onay; "varsayılanlara dön" desteği

**SQL Playground (yalnız global admin)**
- Salt-okuma (SELECT) sorgu konsolu; sorgu geçmişi tutulur ve gece temizlenir

### 3.2 Alarm Yönetimi

Sistem üç seviyede alarm üretir:

```
UYARI (WARNING)  ←── ≤ 30 gün kaldıysa
YÜKSEK (HIGH)    ←── ≤ 15 gün kaldıysa
KRİTİK (CRITICAL) ←── ≤ 7 gün kaldıysa (veya iptal/zincir hatası)
```

Ayrıca alarm tipleri:

| Tip | Tetikleyici | Seviye |
|---|---|---|
| EXPIRY | Sertifika süresi dolmak üzere | WARNING / HIGH / CRITICAL |
| REVOKED | Sertifika iptal edilmiş | CRITICAL |
| CHAIN_BROKEN | Zincirde süresi dolmuş ara CA | CRITICAL |
| MISMATCH | Dağıtım eksik | CRITICAL |

### 3.3 Bildirim Sistemi

- Alarm oluşunca ilgili eskalasyon kişilerine **HTML e-posta** gönderilir
- Her gün onaylanmamış alarmlar için **günlük tekrar bildirimi** gönderilir
- Alarm çözülünce **çözüm bildirimi** gönderilir
- Opsiyonel **Slack / Microsoft Teams webhook** desteği

### 3.4 Takım Tabanlı Sorumluluk

Her sertifika **bir takıma** atanır (eski SY/UG ikili takım modeli kaldırılmıştır). Takım, kendi içinde:
- **Takım lideri** (genelde PO): takımın yönetiminden sorumlu
- **Müdür** (manager): AD `manager` ilişkisinden türetilir; takıma otomatik MANAGER eskalasyon kontağı olarak eklenir
- **Üyeler**: takımın sertifikalarını görüntüler ve haftalık rapor girer

Alarmlar ve bildirimler sertifikanın atandığı takıma ve onun eskalasyon kişilerine yönlendirilir.

---

## 4. Mimari ve Teknoloji Yığını

### 4.1 Backend

| Bileşen | Teknoloji | Versiyon |
|---|---|---|
| Uygulama çerçevesi | Spring Boot | 4.1.0 |
| Dil | Java | 25 (LTS) |
| ORM | Hibernate / JPA | Spring Data JPA (`ddl-auto=update`) |
| Veritabanı sürücüsü | PostgreSQL JDBC | 16 |
| Şifreleme / ASN.1 | BouncyCastle | 1.78.1 |
| Validation | Hibernate Validator | jakarta-validation 3.x |
| E-posta | JavaMail (Spring Mail) | SMTP/STARTTLS |
| JSON | Jackson (SNAKE_CASE) | 2.x |
| HTTP güvenliği | Spring Session JDBC | Distributed sessions |
| Metrikler | Micrometer + Prometheus | `/actuator/prometheus` |
| Test | JUnit 5 + Mockito | 835 test |
| Build | Maven | 3.9.9 |

**Temel Servisler:**

```
SchedulerService          — Zamanlayıcı, HA dağıtık kilit, startup catch-up,
                            nightly cleanup (audit/notif/sql_query_history)
CertificateCheckerService — SSL/TLS soket bağlantısı, sertifika çekme, proxy tunnel
ChainValidationService    — Zincir analizi, OCSP/CRL iptal kontrolü, proxy-aware
CertificateService        — Sonuç kaydetme, raporlama, filtreleme, cache batch evict
EscalationService         — Alarm işleme, eskalasyon, bildirim yönlendirme,
                            sweep'te batch open-alert + inventory ön yükleme
EmailNotificationService  — HTML e-posta oluşturma, async 421 retry executor
WebhookService            — Slack/Teams webhook entegrasyonu
UserService               — Kimlik doğrulama, takım/kullanıcı CRUD, kilitleme
AuditService              — Denetim kaydı yazma + GeoIP zenginleştirme
GeoIpService              — IP → ülke/şehir (ip-api.com, 1h cache)
HttpMetricsService        — Per-endpoint p50/p95/p99 latency
ExtendedHealthService     — System Health tab arkası: heartbeat, SMTP, DB stats
ShutdownLogger            — JVM kapanış nedenini log'a yazar
PortCheckerService        — TCP/UDP port izleme
DnsCheckerService         — DNS record izleme, CHANGED/ROTATED algılama
UptimeHttpCheckerService  — HTTP uptime izleme
SqlPlaygroundService      — Admin-only read-mostly SQL runner (audited)
```

**Cross-Cutting Bileşenler:**

```
GlobalExceptionHandler    — 9 exception handler + catch-all (stack trace UI'ye sızmaz)
RequestLoggingFilter      — TRACE seviyede full request/response (60+ alan masked)
AuthInterceptor           — Custom session+token tabanlı auth (Spring Security YOK)
PermissionCatalog         — Tüm kaynak-aksiyon matrisi tek kaynağında
```

### 4.2 Frontend

| Bileşen | Teknoloji |
|---|---|
| Framework | React 18.3 |
| Build aracı | Vite 5.4 |
| Routing | Tab state (React Router YOK) |
| Dil desteği | Türkçe / İngilizce (1500+ çeviri, parity test'i ile zorunlu) |
| Tema | Açık / Koyu mod (localStorage persist) |
| İkonlar | `lucide-react` (emoji KULLANILMAZ) |
| Test | Vitest (121 test, 18 dosya) |
| Hata sınırı | ErrorBoundary root + tab seviyesinde |
| Responsive | Tam mobil uyumlu |

**Ana Bileşenler:**

```
App.jsx            — Root, 17 tab, ErrorBoundary ile sarılı, loadData allSettled
Dashboard          — Ana izleme ekranı, sertifika kartları
CertificateModal   — 5 sekme: Detay, Alarmlar, Bildirimler, Güvenlik, Notlar
AdminPanel         — Yönetim merkezi (Inventory/Users/Teams/Permissions...)
InventoryManager   — Domain envanteri, 50+ alan + domain rename onayı
AlertHistory       — Alarm geçmişi, onay/çözüm aksiyonları, batch notify
SystemHealth       — JVM, DB havuzu, scheduler metrikleri (allSettled load)
AuditLogViewer     — Güvenlik denetim kayıtları + GeoIP zenginleştirme
WeakAlgorithmReport— Zayıf algoritma tespiti
UptimePage / PortMonitorPage / DnsMonitorPage — Sertifika dışı izleme
ErrorBoundary      — Render hatalarında "Yenile" fallback UI
```

### 4.3 Eşzamanlılık ve Performans

```
certCheckExecutor     : core 20, max 50, queue 1000 (EXECUTOR_* env ile tunable)
Kontrol timeout       : 10 saniye (cert.monitor.check-timeout-seconds)
CRL önbellek TTL      : 1 saat
CRL önbellek boyutu   : 200 giriş (Caffeine maximumSize+expireAfterWrite)
OCSP/CRL HTTP timeout : 5s OCSP, 10s CRL (connect+read explicit)
DB bağlantı havuzu    : 2-10 bağlantı (HikariCP), leak threshold 60s
JVM heap              : RAM'in %75'i (K8s: 6 GB / 8 GB limitte)
SMTP 421 retry        : Async (ScheduledExecutorService daemon, caller bloke etmez)
Cache eviction        : Sweep BAŞINA tek seferlik batch (saveResult her satırda DEĞİL)
N+1 yöntemi           : Sweep başında inventoryRepo.findByDomainIn +
                        alertEventRepo.findOpenByDomainIn — loop'ta in-memory map
```

**Performans Hedefleri (k6 smoke):**
- p95 < 500 ms
- Hata oranı < %1
- Checks pass oranı > %99
- 50 VU × 30 saniye yükte SLA korunur

---

## 5. Sistem Topolojisi

### 5.1 Üretim Ortamı (Kubernetes)

```
┌────────────────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster (Production)                  │
│                    Namespace: cert-monitor                          │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Ingress Controller (nginx)                                  │  │
│  │  cert-monitor.example.com  ─── TLS termination              │  │
│  │  Proxy timeout: 60s  │  Max body: 1MB                       │  │
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
│  │  StatefulSet: PostgreSQL 16-alpine                           │  │
│  │  PersistentVolumeClaim: 10 Gi (ReadWriteOnce)               │  │
│  │  Kullanıcı/DB: certmonitor/certmonitor                       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ConfigMap: cert-monitor-config  │  Secret: cert-monitor-secret     │
│  HPA: 3–10 pod  │  PDB: minAvailable=2  │  PodDisruptionBudget      │
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

### 5.2 Yerel Geliştirme (Docker Compose)

```
┌────────────────────────────────────────────┐
│  Docker Compose                            │
│                                            │
│  ┌────────────────────────────────────┐   │
│  │  cert-monitor:latest               │   │
│  │  Port: 8080                        │   │
│  │  Volume: ./data:/app/data          │   │
│  │  ReadOnly FS + /tmp tmpfs          │   │
│  │  Health: wget /health (30s)        │   │
│  └────────────────────────────────────┘   │
│                                            │
│  Ortam: .env dosyası (DB, SMTP, Admin)    │
└────────────────────────────────────────────┘
```

### 5.3 Dağıtık Zamanlayıcı Kilidi

Çok pod çalıştırıldığında yalnızca bir pod aynı anda tarama yapmalıdır. Site Monitör DB tabanlı dağıtık kilit kullanır:

```
scheduler_lock tablosu:
  name        → "cert-check"
  locked_by   → "hostname-uuid8"
  locked_until→ "2026-05-22T09:10:00" (TTL: 10 dk)

Akış:
  Pod başlar → eski kilitleri temizle (aynı host)
  Tarama zamanı → INSERT INTO scheduler_lock (benzersiz kısıt)
    ├─ Başarılı → bu pod tarar
    └─ Hata (duplicate key) → başka pod tarıyor, atla
  Tarama biter → DELETE FROM scheduler_lock
```

---

## 6. Veritabanı Şeması

Toplam **30'dan fazla tablo** bulunmaktadır (`ddl-auto=update` ile yönetilir):

### 6.1 Temel Tablolar

| Tablo | Amaç | Kritik Alanlar |
|---|---|---|
| `app_users` | Kullanıcı hesapları | username, password_hash, system_role, org_role, team_id, manager_id, manager_sicil, auth_source (LOCAL/LDAP), lockout_until |
| `teams` | Takım tanımları | name, leader_id, email, active |
| `certificate_inventory` | Domain envanteri | domain, port, team_id (ug_team_id: legacy), tier, deleted_at |
| `latest_checks` | Her domain için son kontrol (tek kayıt) | domain (PK), not_after, days_remaining, status, fingerprint |
| `certificate_checks` | Tüm kontrol geçmişi | domain, run_id, checked_at, tüm sertifika alanları |

### 6.2 Alarm ve Bildirim Tabloları

| Tablo | Amaç | Kritik Alanlar |
|---|---|---|
| `alert_events` | Açık/kapalı alarm olayları | domain, alert_level, alert_type, acknowledged, resolved, last_re_alert_at |
| `alert_thresholds` | Gün eşikleri konfigürasyonu | warning_days, high_days, critical_days, re_alert_interval_hours |
| `escalation_contacts` | Bildirim alıcıları | team_id, email, role, min_alert_level, webhook_url |
| `notification_logs` | Gönderilen tüm bildirimler | alert_event_id, recipient_email, sent_at, email_status, trigger |

### 6.3 Destek Tabloları

| Tablo | Amaç |
|---|---|
| `audit_log` | Tüm admin aksiyonları kayıtları |
| `certificate_notes` | Domain bazlı notlar |
| `alert_events` | Alarm olayları |
| `scheduler_lock` | Dağıtık tarama kilidi |
| `system_heartbeat` | Sistem sağlık sinyali |
| `spring_session` | Kullanıcı oturumları (opsiyonel JDBC store) |
| `spring_session_attributes` | Oturum özellikleri |
| `remember_me_tokens` | "Beni Hatırla" tokenleri |
| `permission_grants` | Rol bazlı yetki matrisi (role/resource/action/allowed) |
| `ldap_settings` / `smtp_settings` | LDAP/AD ve SMTP yapılandırması (bind parolası AES-GCM) |
| `weekly_report` / `weekly_report_mail` / `weekly_report_image` | Haftalık rapor, onay/iade postaları, görseller |
| `uptime_check` / `port_monitor` / `port_check` / `dns_monitor` / `dns_record` | İzleme hedefleri ve ölçümleri |
| `network_outage_event` | İzleme kesinti teyit olayları |
| `sql_query_history` | SQL Playground sorgu geçmişi |
| `password_history` | Parola tekrar kullanım kontrolü |
| `guide_link` | Yardım/rehber bağlantıları |

### 6.4 Soft Delete

`certificate_inventory` tablosundaki kayıtlar fiziksel olarak silinmez:
```sql
deleted_at VARCHAR(255)  -- NULL = aktif, dolu = silinmiş
active     BOOLEAN       -- silinince FALSE yapılır
```
Bu sayede tüm geçmiş veriler korunur ve silinen sertifikalar geri yüklenebilir.

---

## 7. Sertifika Kontrol Akışı

### 7.1 Tam Akış Şeması

```
Tetikleyiciler:
  ┌─ Saatlik (cron: her saatin başı)
  ├─ Startup (backend açılışında)
  ├─ Stale sweep (5 dk'dan fazla kontrol edilmemiş domainler)
  └─ Manuel (admin "Şimdi Kontrol Et" butonuyla)
         │
         ▼
  SchedulerService.runCheck()
  │  ├─ DB dağıtık kilidi al (tek pod çalışır)
  │  ├─ Envanterdeki aktif domainleri yükle (+ her domain'in use_proxy bayrağı)
  │  └─ Her domain için checkAsync(domain, port, forceProxy) başlat (paralel)
         │
         ▼
  CertificateCheckerService.check(domain, port, forceProxy)
  │  ├─ Yönlendirme kararı:
  │  │     forceProxy AND proxy yapılandırıldı AND domain noProxy listesinde DEĞİL
  │  │     → HTTP CONNECT tunnel kur (Authorization header opsiyonel)
  │  │     aksi halde → direkt outbound TCP
  │  ├─ TCP soket bağlantısı (timeout: 10s)
  │  ├─ TLS_MODE=browser: TLS 1.2 + ALPN [h2, http/1.1] zorla (WAF/Akamai uyumu)
  │  ├─ SSL handshake → sertifika zincirini al
  │  ├─ Yaprak sertifikayı ayrıştır (subject, issuer, tarihler, SAN, vb.)
  │  ├─ HSTS check (HTTP HEAD, try-finally ile leak-proof)
  │  ├─ Kalan gün hesapla
  │  └─ ChainValidationService.analyze() çağır
         │
         ▼
  ChainValidationService (proxy-aware: OCSP/CRL fetch'leri proxy üzerinden)
  │  ├─ Zincirdeki tüm sertifikaları incele
  │  ├─ Ara CA sürelerini kontrol et → chain_status: VALID / BROKEN
  │  ├─ SHA-256 parmak izi hesapla
  │  └─ Revocation: OCSP (5s timeout) → CRL (10s timeout, Caffeine cached)
  │       └─ revocation_status: VALID / REVOKED / UNDETERMINED
         │
         ▼
  CertificateService.saveResult()
  │  ├─ certificate_checks tablosuna geçmiş kaydı ekle
  │  ├─ latest_checks tablosunda mevcut durumu güncelle (UPSERT)
  │  ├─ Dağıtım kontrolü:
  │  │   └─ Envanterdeki expected_fingerprint ≠ mevcut fingerprint → INCOMPLETE
  │  └─ Uyarı durumunu belirle (days_remaining ≤ threshold)
         │
         ▼
  EscalationService.processResults()
  │  ├─ Başta TEK SEFER batch ön yükleme (N+1 önleme):
  │  │   ├─ inventoryRepo.findByDomainIn(allDomains)  → tek query
  │  │   └─ alertEventRepo.findOpenByDomainIn(allDomains)  → tek query
  │  ├─ Her domain için alert tipi belirle (loop'ta DB değil, map lookup):
  │  │   ├─ revocation_status = REVOKED → REVOKED
  │  │   ├─ deployment_status = INCOMPLETE → MISMATCH
  │  │   ├─ chain_status = BROKEN → CHAIN_BROKEN
  │  │   └─ warning = true / status = error → EXPIRY
  │  ├─ Alert seviyesi belirle (WARNING / HIGH / CRITICAL)
  │  ├─ Mevcut açık alarm var mı?
  │  │   ├─ Hayır → yeni AlertEvent oluştur → INITIAL bildirim gönder
  │  │   ├─ Evet + seviye yükseldi → ESCALATION bildirimi gönder, acknowledged sıfırla
  │  │   └─ Evet + onaylanmamış + bugün gönderilmemiş → DAILY_REALERT gönder
  │  └─ Sorun çözüldüyse → resolved = true → RESOLUTION bildirimi gönder
         │
         ▼
  EmailNotificationService + WebhookService
  │  ├─ HTML e-posta oluştur (domain, seviye, kalan gün, sertifika bilgileri)
  │  ├─ SMTP ile gönder → email_status:
  │  │       SENT / FAILED / SKIPPED_DISABLED / QUEUED_RETRY (421 rate-limit)
  │  ├─ 421 ise async retry executor'da 90s sonra tekrar dene (caller bloke olmaz)
  │  └─ Webhook varsa Slack/Teams'e gönder
         │
         ▼
  notification_logs tablosuna kayıt
         │
         ▼
  CertificateService.evictAllCaches()  — Sweep sonu TEK sefer
  (Her saveResult'ta DEĞİL; 1000 domain'lik sweep'te 4000 evict → 4 evict)
```

### 7.2 Startup Catch-Up Mekanizması

Backend açıldığında sertifika taraması başlamadan önce kaçırılan bildirimleri gönderir:

```
runOnStartup()
  ├─ Schema güncelleme (ALTER TABLE patch'leri)
  ├─ Bootstrap (admin kullanıcı, default takım)
  ├─ Default alarm eşiği oluştur
  ├─ Eski dağıtık kilitleri temizle
  │
  ├─ catchUpMissedDailyAlerts()  ← SYNC, ağ çağrısı yok
  │   ├─ Tüm açık + onaylanmamış alertleri sorgula
  │   ├─ Her alert için: lastReAlertAt bugün mü?
  │   │   ├─ Evet → atla ("already notified today")
  │   │   └─ Hayır → DAILY_REALERT gönder, lastReAlertAt = şimdi
  │   └─ Log: "X missed notification(s) sent"
  │
  └─ Thread(runCheck).start()   ← ASYNC, ağ taraması
```

Bu sayede backend herhangi bir nedenle kapandıysa, açılışta o günün bildirimleri anında gönderilir.

---

## 8. Alarm ve Eskalasyon Mekanizması

### 8.1 Alarm Yaşam Döngüsü

```
Sertifika sorunlu tespit edildi
         │
         ▼
 AlertEvent oluşturuldu (resolved=false, acknowledged=false)
         │
         ▼
 INITIAL bildirim → eskalasyon kişilerine e-posta
         │
         ▼
 Her gün (UTC bazlı) DAILY_REALERT → tekrar bildirim
         │                    └─ (acknowledged=true ise durur)
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
           Sertifika yenilendi / sorun giderildi
                      │
                      ▼
           resolved=true, resolvedAt, resolvedBy
           RESOLUTION bildirimi gönderilir
```

### 8.2 Eskalasyon Matrisi

| Alarm Seviyesi | Kalan Gün | Bildirim Alan Roller | Açıklama |
|---|---|---|---|
| WARNING | ≤ 30 gün | PO + TECH | İlk uyarı, planlama başlamalı |
| HIGH | ≤ 15 gün | PO + TECH + MANAGER | Acil yenileme gerekiyor |
| CRITICAL | ≤ 7 gün | Tüm kişiler (+ C-LEVEL) | Kritik, derhal müdahale |

Eskalasyon kişileri takım bazında tanımlanır. Eğer bir takımın kişisi yoksa global kişilere düşer.

### 8.3 Bildirim Tetikleyici Tipleri

| Tetikleyici | Açıklama |
|---|---|
| INITIAL | İlk alarm oluştuğunda |
| ESCALATION | Seviye yükseldiğinde (ör. WARNING → HIGH) |
| DAILY_REALERT | Her gün, onaylanmamış alarmlar için |
| MANUAL | Admin "Yeniden Bildir" düğmesine bastığında |
| RESOLUTION | Sorun giderildiğinde |
| MANUAL_RESOLVE | Admin manuel olarak çözdü işaretlediğinde |

### 8.4 Onaylama (Acknowledge) Davranışı

Bir alarm onaylandığında:
- Günlük tekrar bildirimleri **durur**
- Alert **açık** kalmaya devam eder (sorun çözülmedi, sadece görüldü)
- Seviye yükselirse acknowledged otomatik **sıfırlanır** ve yeni bildirim gider

---

## 9. Bildirim Sistemi

### 9.1 E-posta Formatı

Her alarm e-postası zengin HTML içerikle gelir:

```
┌─────────────────────────────────────────────────────┐
│  [Renk çubuk: UYARI=turuncu / KRİTİK=kırmızı]      │
│  Site Monitör — Sertifika İzleme                     │
│  🌐 kurumsalinternetsubesi.akbank.com               │
│  UYARI  ·  Son Kullanma Tarihi                      │
├─────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────┐   │
│  │    18          │  Son Kullanma Tarihi         │   │
│  │  GÜN KALDI     │  08.06.2026 23:59 UTC        │   │
│  │  Yenileme      │  📅 18 gün sonra sona eriyor │   │
│  │  planlanmalı   │                              │   │
│  └──────────────────────────────────────────────┘   │
│                                                      │
│  SERTİFİKA BİLGİLERİ   │  DURUM ÖZETİ              │
│  ───────────────────────│───────────────────────    │
│  🌐 Alan Adı: ...       │  Sertifika: ⚠ Uyarı       │
│  📋 Sahibi: ...         │  İptal: ✓ İptal edilmedi  │
│  🏢 Veren Kurum: ...    │  Zincir: ✓ Sağlıklı       │
│  📅 Geçerlilik Başl.: ..│  Dağıtım: ✓ Tamamlandı    │
│  🔑 Parmak İzi: ...     │                           │
│                                                      │
│  ALARM DETAYI                                        │
│  UYARI: domain adresindeki sertifikanın süresi       │
│  18 gün içinde doluyor.                              │
├─────────────────────────────────────────────────────┤
│  Site Monitör  ·  Son kontrol: ... · Bildirim: ...   │
└─────────────────────────────────────────────────────┘
```

### 9.2 Çözüm E-postası

Sorun giderildiğinde yeşil renkte çözüm e-postası gönderilir:
- Çözen kişi, çözülme tarihi, alarm oluşturma tarihi
- Alarm tipi ve önceki seviye
- Sertifika bilgileri

### 9.3 Webhook Desteği

| Platform | Format |
|---|---|
| Slack | Slack Block Kit JSON formatı |
| Microsoft Teams | Adaptive Card formatı |

Her eskalasyon kişisine ayrı webhook tanımlanabilir.

### 9.4 E-posta Konfigürasyonu

```properties
cert.monitor.email.enabled=true       # E-postayı etkinleştir
SPRING_MAIL_HOST=smtp.gmail.com       # SMTP sunucu
SPRING_MAIL_PORT=587                  # STARTTLS portu
SPRING_MAIL_USERNAME=hesap@gmail.com  # Gönderen hesap
SPRING_MAIL_PASSWORD=uygulama-sifresi # App Password
cert.monitor.email.from=gönderen@adres
```

---

## 10. Güvenlik Modeli

### 10.1 Kimlik Doğrulama

**Oturum Tabanlı Kimlik Doğrulama:**
- Spring Session ile yönetilen HTTP oturumları
- Opsiyonel JDBC Session Store (HA modunda tüm pod'larda oturum paylaşımı)
- BCrypt ile şifrelenmiş yerel parolalar

**LDAP / Active Directory ile Kimlik Doğrulama:**
- Ayarlardan etkinleştirilir; login anında AD'ye bind edilerek doğrulama yapılır
- İlk başarılı girişte kullanıcı otomatik provizyon edilir (orgRole, müdür ilişkisi, takım) — bkz. §3.1
- Provizyon sonucu sistem rolü atanır: müdür → kapsamlı ADMIN (salt-okuma), PO → TEAM_ADMIN, diğerleri → USER (bkz. §11)
- Yerel `admin` hesabı her zaman geçerlidir (acil erişim / bootstrap); LDAP/SMTP ayarları yalnız bu hesaba açıktır
- Bind parolası AES-GCM ile şifreli saklanır (`CERT_MONITOR_SECRET_KEY`)

**"Beni Hatırla" Özelliği:**
- 7 günlük kalıcı oturum
- HMAC imzalı token (DB'de saklanır)
- Oturum açıldığında token yenilenir

### 10.2 Aşamalı Hesap Kilitleme

Brute-force saldırılarına karşı aşamalı kilitleme mekanizması:

| Başarısız Giriş | Bekleme Süresi |
|---|---|
| 5 hatalı giriş | 30 saniye |
| 3 ek hatalı giriş | 2 dakika |
| 2 ek hatalı giriş | 10 dakika |
| 1 ek hatalı giriş | 30 dakika |
| Eşik aşılırsa | **Kalıcı kilit** (admin açar) |

### 10.3 Hareketsizlik Timeout

- 5 dakika hareketsizlik → 60 saniyelik uyarı sayacı başlar
- Uyarı süresi dolunca → otomatik çıkış

### 10.4 Konteyner Güvenliği

```yaml
securityContext:
  runAsNonRoot: true          # Root olarak çalışmaz
  runAsUser: 1000             # UID 1000
  readOnlyRootFilesystem: true # Kök dosya sistemi salt okunur
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]               # Tüm Linux yetenekleri kaldırılır

# Yalnızca /tmp yazılabilir (emptyDir)
```

### 10.5 Denetim Kaydı (Audit Log)

Her admin aksiyonu şu bilgilerle kaydedilir:

| Alan | İçerik |
|---|---|
| Zaman | UTC timestamp |
| Kullanıcı | Kullanıcı adı |
| IP Adresi | İstemci IP'si |
| Coğrafi Konum | Ülke / Şehir |
| Tarayıcı | User-Agent |
| Olay Tipi | DOMAIN_ADD, USER_UPDATE, TEAM_DELETE, vb. |
| Kaynak | Etkilenen nesne (domain, kullanıcı adı, vb.) |
| Sonuç | SUCCESS / FAILURE |
| Değişiklikler | Eski değer → Yeni değer |

Audit log **değiştirilemez** — yalnızca ekleme yapılır. Gece 03:30 cron'u 180 günden eski kayıtları temizler.

### 10.6 Hata Yönetimi ve Bilgi Sızıntısı Önleme

**GlobalExceptionHandler** (Spring `@RestControllerAdvice`) tüm uncaught exception'ları yakalar ve TR mesajla kullanıcı-dostu yanıt döner:

| Exception | HTTP Status | UI Mesaj |
|---|---|---|
| `NoSuchElementException` | 404 | exception mesajı |
| `IllegalStateException` | 409 | exception mesajı |
| `IllegalArgumentException` | 400 | exception mesajı |
| `SecurityException` | 403 | exception mesajı |
| `DataIntegrityViolationException` | 409 | "Bu domain envanterde zaten var" vb. |
| `MethodArgumentNotValidException` | 400 | "Geçersiz alan(lar): X, Y" + alan listesi |
| `HttpMessageNotReadableException` | 400 | "Geçersiz istek formatı" |
| `MissingServletRequestParameterException` | 400 | "Eksik parametre: X" |
| `ResponseStatusException` | passthrough | exception mesajı |
| **Diğer her şey (catch-all)** | 500 | "Sunucu hatası" — **stack trace UI'ye SIZMAZ** |

Stack trace ve iç hata mesajı sadece log dosyasına yazılır.

### 10.7 Hassas Alan Maskeleme (Request Logging)

**RequestLoggingFilter** TRACE seviyede HTTP request/response gövdesi log'lar — default `com.certmonitor=DEBUG` seviyede SESSİZ kalır. Açmak için:

```properties
logging.level.com.certmonitor.config.RequestLoggingFilter=TRACE
```

Açıldığında JSON body / form body / URL query üzerinden ~60 hassas alan otomatik `*******` ile maskelenir:

```
password / passwd / pwd / pass / parola / sifre /
old_password / new_password / smtp_pass / db_password /
token / access_token / refresh_token / bearer / csrf /
secret / api_key / client_secret / private_key /
session_id / jsessionid / sid /
pin / otp / mfa_code / verification_code  (EN + TR varyantları)
```

Sensitive HTTP headers (`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token` vb.) da maskelenir. Skip path'ler: `/health`, `/favicon.ico`, `/assets/*`, `/static/*`, static asset uzantıları.

### 10.8 Frontend Hata Sınırı (ErrorBoundary)

Tüm uygulama `ErrorBoundary` ile sarılıdır:
- **Root seviye** (main.jsx) — komple white-screen önler
- **Tab seviyesi** (App.jsx, `key={tab}` ile) — bir tab'ın render hatası diğerlerini öldürmez

Hata durumunda kullanıcıya "Bir şey ters gitti / Something went wrong" + "Yenile / Reload" butonu gösterilir, console'a tam hata düşer.

### 10.9 Input Validation

Modelde `jakarta-validation` annotation'ları + controller'da `@Valid`:

```java
@NotBlank @Pattern(...) String domain  // RFC 1123 + wildcard izinli
@Min(1) @Max(65535)     Integer port
@Min(1) @Max(4)         Integer tier   // 1=Müşteri-Yüzlü Prod ... 4=Dev
```

Geçersiz body → 400 + alan listesi (stack trace yok).

---

## 11. Kullanıcı Rolleri ve Yetki Matrisi

### 11.1 Sistem Rolleri

| Rol | Açıklama | Erişim Kapsamı |
|---|---|---|
| **ADMIN (global)** | Yerel/bootstrap admin (`admin`) | Tüm takımlar + global işlemler (yetki matrisi, SQL Playground, sistem denetimi, LDAP/SMTP ayarları) |
| **ADMIN (müdür, kapsamlı)** | AD'den gelen, astı olan kullanıcı | Sistem rolü ADMIN'dir ama **yalnız astlarının takımlarını GÖRÜNTÜLER (salt-okunur)**; global işlemlere giremez |
| **TEAM_ADMIN** | Genelde PO (orgRole=PO) | Liderlik ettiği takım(lar)da **görüntüleme + yönetim** (çok-takım) |
| **USER** | Normal kullanıcı | Yalnız kendi takımı: okuma + kendi takım alarm aksiyonları + haftalık rapor girişi |
| **AUDIT** | Denetçi | Sistem geneli **salt-okuma** + Denetim Günlüğü |

> **Kapsam (Faz 3b):** Oturuma `viewTeamIds` (okuma) ve `manageTeamIds` (yönetim) kapsamları yazılır. Global admin'de bu kapsamlar sınırsızdır (null). Müdürün kapsamı **astlarının** takımları, PO'nun kapsamı **liderlik ettiği** takımlardır — bu ilişkiler AD'den (yönetici/lider) türetilir. Bir rolün sistemRole'ü ADMIN olsa bile kapsamı doluysa **global değildir** (yetki sızıntısını önler).

### 11.2 Organizasyonel Roller (orgRole)

AD'deki `company`/ünvan bilgisinden türetilir; hem eskalasyon bildirim seviyesini hem de sistem rolünü etkiler:

| Org Rol | Eşlenen Sistem Rolü | Hangi Alarmda Bildirim Alır |
|---|---|---|
| **PO** (Product Owner) | TEAM_ADMIN + takım lideri | WARNING + HIGH + CRITICAL |
| **MANAGER** (Müdür) | ADMIN (kapsamlı/salt-okuma) | HIGH + CRITICAL |
| **TECH** | USER | WARNING + HIGH + CRITICAL |
| **C-LEVEL** | — | CRITICAL |

### 11.3 Takım Kapsamı ve AD İlişkileri

- Her sertifika **tek bir takıma** atanır (eski SY/UG ikili takım modeli kaldırılmıştır).
- Takım–müdür ilişkisi AD provizyonunda kurulur: üyenin `manager` alanı → müdür; müdür bulununca o takıma **otomatik olarak MANAGER eskalasyon kontağı** (min. seviye HIGH) eklenir.
- PO bir takıma atandığında, takımın lideri yoksa **otomatik takım lideri** olur (mevcut lider ezilmez).

### 11.4 Yetki Matrisi (Detaylı)

Aşağıdaki varsayılanlar **Yetki (Permissions) ekranından** rol bazında düzenlenebilir (ADMIN her zaman tam yetkilidir ve kilitlidir). Müdür/PO kapsamı (hangi takımlar) AD ilişkilerinden gelir.

| İşlev | ADMIN | TEAM_ADMIN (PO) | USER | AUDIT |
|---|---|---|---|---|
| Dashboard / sertifika görüntüleme | ✓ | ✓ (kapsam) | ✓ (kendi takımı) | ✓ (tümü) |
| "Şimdi Kontrol Et" / canlı tarama | ✓ | ✗ | ✗ | ✗ |
| Domain Envanteri (CRUD) | ✓ | ✓ (kapsam) | ✗ (okuma) | ✗ (okuma) |
| Alarm onay / yeniden bildir / çözüldü | ✓ | ✓ (kapsam) | ✓ (kendi takımı) | ✗ |
| Eskalasyon kişileri (CRUD) | ✓ | ✓ (kapsam) | ✗ (okuma) | ✗ (okuma) |
| Alarm eşikleri | ✓ | ✗ | ✗ (okuma) | ✗ (okuma) |
| Takım / kullanıcı yönetimi | ✓ | ✓ (kapsam) | ✗ (okuma) | ✗ (okuma) |
| Haftalık raporlar (yaz/düzenle) | ✓ | ✓ | ✓ (kendi takımı) | ✗ (okuma) |
| Haftalık rapor **onay** | ✓ | ✓ (PO) | ✗ | ✗ |
| İzleme (uptime/port/dns) yapılandırma | ✓ | ✗ (okuma) | ✗ (okuma) | ✗ (okuma) |
| Denetim Günlüğü | ✓ | ✓ (kapsam) | ✗ | ✓ |
| Yetki Matrisi / SQL Playground / Sistem Denetimi | ✓ (yalnız global) | ✗ | ✗ | ✗ |
| LDAP / SMTP Ayarları | ✓ (yalnız `admin`) | ✗ | ✗ | ✗ |

---

## 12. Kullanıcı Ekranları ve Aksiyonlar

### 12.1 Giriş Ekranı

**Ekran:** `http://cert-monitor.example.com`

**Kullanıcı Aksiyonları:**

| Aksiyon | Açıklama |
|---|---|
| Kullanıcı adı + şifre gir → Giriş yap | Normal oturum açma |
| "Beni Hatırla" işaretle | 7 günlük kalıcı oturum |
| Hatalı giriş yap (tekrar) | Sayaç artar → kilitleme başlar |
| Kilitlenme süresi dolunca | Yeniden deneyebilir |
| Kalıcı kilitliyse | Mesaj gösterilir, admin müdahalesi gerekir |

---

### 12.2 Dashboard (Ana Ekran)

**Ekran:** Giriş sonrası otomatik açılır

**Görüntülenen Bilgiler:**
- **İstatistik Paneli** (genişletilebilir/daraltılabilir): Toplam | Geçerli | Uyarı | Hata | 30 günde doluyor | Süresi dolmuş
- **Sertifika Kartları**: Her sertifika için domain, kalan gün, durum rozeti, issuer, son kontrol

**Kullanıcı Aksiyonları:**

| Aksiyon | Açıklama |
|---|---|
| İstatistik kartına tıkla | İlgili kategoriye göre filtrele |
| Durum filtresi (Tümü/Geçerli/Uyarı/Hata) | Sertifikaları duruma göre filtrele |
| Süre filtresi (7/30/90 gün/Süresi dolmuş) | Yaklaşan sertifikaları göster |
| Arama kutusuna domain/issuer yaz | Anlık filtreleme |
| Sıralama değiştir (Öncelik/Artan/Azalan) | Kalan güne göre sırala |
| Sayfa başına seç (10/25/50/Tümü) | Kaç sertifika gösterilsin |
| Sertifika kartına tıkla | Detay modalını aç |
| "Şimdi Kontrol Et" butonu (Admin) | Anlık arka plan taraması başlat |
| Dil değiştir (TR/EN) | Arayüz dilini değiştir |
| Tema değiştir (Açık/Koyu) | Görsel temayı değiştir |
| 5 dakika hareketsiz kal | 60s uyarısı → otomatik çıkış |

---

### 12.3 Sertifika Detay Modalı

**Erişim:** Herhangi bir sertifika kartına tıkla

**5 Sekme:**

**Sekme 1 — Detaylar**

| Görüntülenen Bilgi |
|---|
| Domain adı |
| Durum (Geçerli/Uyarı/Hata/Kritik) |
| Kalan gün sayısı |
| Subject (sertifika sahibi) |
| Issuer (veren kurum) |
| Geçerlilik başlangıcı |
| Bitiş tarihi |
| Son kontrol zamanı |
| SAN (Subject Alternative Names) listesi |
| Hata mesajı (varsa) |

**Sekme 2 — Alarmlar**

| Görüntülenen Bilgi |
|---|
| Bu sertifikaya ait tüm alarm olayları |
| Her alarm için: tarih, seviye, tip, durum |
| Onaylanma bilgisi (kim, ne zaman) |
| Çözülme bilgisi (kim, ne zaman) |

**Sekme 3 — Bildirimler**

| Görüntülenen Bilgi |
|---|
| Bu domain için gönderilmiş tüm bildirimler |
| Alıcı adı ve e-posta |
| Gönderim zamanı |
| Konu |
| E-posta durumu (SENT/FAILED/SKIPPED) |
| Webhook durumu |
| Tetikleyici tipi |

**Sekme 4 — Güvenlik Bilgisi**

| Alan | İçerik |
|---|---|
| Subject DN | Tam subject distinguished name |
| Issuer DN | Tam issuer distinguished name |
| Seri Numarası | Hex format |
| Anahtar Algoritması | RSA / EC / DSA |
| Anahtar Boyutu | 2048, 4096 bit vb. |
| İmza Algoritması | SHA-256WithRSA vb. |
| CA Sertifikası mı | Evet/Hayır |
| Key Usage | TLS Web Server Auth vb. |
| Extended Key Usage | Detaylı kullanım amaçları |
| Zincir Durumu | VALID / BROKEN |
| İptal Durumu | VALID / REVOKED / UNDETERMINED |
| Dağıtım Durumu | OK / INCOMPLETE |
| OCSP URL | Canlı iptal kontrolü |
| CRL URL | İptal listesi |
| SHA-256 Parmak İzi | Benzersiz kimlik |

**Sekme 5 — Notlar**

| Aksiyon | Açıklama |
|---|---|
| Not listesini görüntüle | Bu sertifikaya özel tüm notlar |
| Yeni not ekle | Serbest metin not girişi |
| Notu düzenle | Mevcut notu güncelle |
| Notu sil | Onay sonrası sil |

---

### 12.4 İstatistikler Ekranı

**Erişim:** Üst menü → İstatistikler sekmesi

| Görüntülenen Bilgi |
|---|
| Detaylı sertifika sayıları (toplam/geçerli/uyarı/hata/süresi dolmuş) |
| **Tier Dağılım Grafiği**: Tier 1-4 ve sınıflandırılmamış pasta grafiği |
| **Takım Bazlı Dağılım**: Her takım için geçerli/uyarı/hata sayıları |
| Takım kırılımı (kapsamlı rollerde yalnız erişilen takımlar) |

**Aksiyonlar:**

| Aksiyon | Açıklama |
|---|---|
| Tier grafiği dilimine tıkla | Dashboard'u o Tier'a filtrele |
| Takım satırına tıkla | O takımın sertifikalarını filtrele |

---

### 12.5 Uyarılar Ekranı

**Erişim:** Üst menü → Uyarılar sekmesi

- Sadece `warning` veya `error` durumundaki sertifikalar gösterilir
- Aynı filtreleme ve sıralama özellikleri geçerlidir

---

### 12.6 Tüm Sertifikalar (Tablo Görünümü)

**Erişim:** Üst menü → Tüm Sertifikalar sekmesi

**Kolon Başlıkları ve Sıralama:**

| Kolon | Sıralanabilir |
|---|---|
| Domain | ✓ (A-Z / Z-A) |
| Issuer | ✓ (A-Z / Z-A) |
| Subject | ✗ |
| Bitiş Tarihi | ✗ |
| Kalan Gün | ✓ |
| Durum | ✗ |
| Son Kontrol | ✓ |

---

### 12.7 Yenileme Tavsiyesi Ekranı

**Erişim:** Üst menü → Yenileme Tavsiyesi sekmesi

Her sertifika için öncelik sıralamalı Türkçe aksiyon önerileri:
- **Kritik**: Derhal müdahale gerekiyor
- **Uyarı**: Yenileme planlanmalı
- **Bilgi**: Takip edilmesi gereken durumlar

---

### 12.8 Domain Inventory Ekranı (Admin)

**Erişim:** Admin Panel → Domain Inventory sekmesi

**Tablo Görünümü:**

| Kolon |
|---|
| Domain + Port |
| Tier (rozet: Tier 1-4 / Sınıflandırılmamış) |
| Takım |
| Sahip |
| Açıklama |
| Aktif Durumu |
| Aksiyonlar (Düzenle / Devret / Sil / Geri Yükle) |

> **Kapsam:** Global admin tüm envanteri görür ve yönetir. PO/TEAM_ADMIN yalnız liderlik ettiği takımların domain'lerini görür+yönetir; müdür (kapsamlı ADMIN) astlarının takımlarını **salt-okuma** görür; USER/AUDIT salt-okuma.

**Aksiyon: Yeni Domain Ekle**

Modal 5 bölümden oluşur:

**Bölüm 1 — Temel Bilgiler**

| Alan | Zorunlu | Açıklama |
|---|---|---|
| Domain | ✓ | FQDN (ör. api.example.com) |
| Port | ✓ | Varsayılan: 443 |
| Takım | ✓ | Sorumlu takım (alarm/bildirim yönlendirmesi) |
| Tier | ✗ | 1: Müşteri hizmetleri prod / 2: İç prod / 3: UAT / 4: Dev |
| Sahip | ✗ | Sorumlu kişi/birim |
| Aktif | ✓ | Sertifika izlensin mi |

**Bölüm 2 — Operasyonel Bayraklar**

| Bayrak | Açıklama |
|---|---|
| Dış Tedarikçi | Sertifika harici tedarikçiden mi alınıyor |
| Aksiyon Gerekli | Bekleyen bir müdahale var mı |
| OpenShift | OpenShift platformunda mı çalışıyor |
| SSL Pinning | Uygulamada SSL pinning uygulanmış mı |
| Dahili Sertifika | Kurumsal CA'dan mı verilmiş |
| JKS Keystore | Java KeyStore kullanılıyor mu |
| Sunucu Güncellemesi | Sunucu güncelleme bekliyor mu |
| Netscaler | Netscaler/F5 arkasında mı |
| WAF Aktif | Web Application Firewall aktif mi |
| Kullanımda | Bu sertifika aktif olarak kullanılıyor mu |
| EV Sertifikası | Extended Validation sertifikası mı |

**Bölüm 3 — Süreç Bilgisi**

| Alan | Açıklama |
|---|---|
| Satın Alan | Sertifikayı kim/hangi birim satın aldı |

**Bölüm 4 — Açıklamalar**

| Alan | Açıklama |
|---|---|
| Açıklama | Genel serbest metin açıklaması |
| Değişiklik Açıklaması | Süreç notu (devir/yenileme süreci vb.) |

**Bölüm 5 — Gelişmiş**

| Alan | Açıklama |
|---|---|
| Beklenen Parmak İzi | SHA-256 hex — dağıtım kontrolü için |
| Beklenen Subject | CN karşılaştırması için |

**Aksiyon: Sil (Soft Delete)**
- Sertifika fiziksel olarak silinmez
- `deleted_at` timestamp set edilir, `active = false` yapılır
- Tabloda varsayılan olarak gizlenir
- Onay diyaloğu gösterilir

**Aksiyon: Geri Yükle**
- "Silinenleri Göster" toggle açılır
- Silinen satırda "Geri Yükle" butonuna tıklanır
- `deleted_at = null`, `active = true` yapılır

**Aksiyon: Devret (Takım)**
- Sertifikayı başka bir takıma aktar
- Dropdown'da tüm takımlar listelenir; mevcut takım seçili gelir
- Yalnız global admin için açık global işlemdir

**Toggle: Silinenleri Göster/Gizle**
- Silinmiş sertifikaları tabloda göster veya gizle

---

### 12.9 Aktivite Günlüğü Ekranı

**Erişim:** Üst menü → Aktivite Günlüğü sekmesi

Her tarama çalışmasının özeti gösterilir:

| Görüntülenen Bilgi |
|---|
| Çalışma zamanı |
| Tetikleyici tipi (Manuel / Zamanlı / Güncel olmayan) |
| Kontrol edilen domain sayısı |
| Uyarı sayısı |
| Hata sayısı |
| Çalışma kimliği (Run ID) |

**Filtre:** Son N saat göster (ör. son 24 saat, son 7 gün)

---

### 12.10 Alarm Geçmişi Ekranı (Admin)

**Erişim:** Admin Panel → Alarm Geçmişi sekmesi veya Üst menü

**Filtreler:**

| Filtre |
|---|
| Sadece açık alarmları göster toggle |
| Domain arama |
| Alarm seviyesi filtresi |
| Alarm tipi filtresi |

**Tablo Kolonları:**

| Kolon | Açıklama |
|---|---|
| Domain | Sertifikaya ait domain |
| Seviye | WARNING / HIGH / CRITICAL (renkli rozet) |
| Tip | EXPIRY / REVOKED / CHAIN_BROKEN / MISMATCH |
| Kalan Gün | Alarm anındaki kalan gün |
| Oluşturma Tarihi | Alarm ne zaman açıldı |
| Son Bildirim | Son DAILY_REALERT zamanı |
| Durum | Açık / Onaylandı / Çözüldü |
| Aksiyonlar | Onayla / Yeniden Bildir / Çözüldü İşaretle |

**Aksiyon: Onayla**
- Alarmı "gördüm, biliyorum" olarak işaretle
- Günlük tekrar bildirimleri durur
- Alarm açık kalmaya devam eder

**Aksiyon: Yeniden Bildir**
- Tüm eskalasyon kişilerine MANUAL tetikleyicili bildirim gönder
- Kullanım: acil durum, sorumlu değişti, vb.
- Kota veya aralık kısıtı yoktur

**Aksiyon: Çözüldü İşaretle**
- Sertifika yenilendi veya sorun giderildi
- `resolved = true`, çözen kişi kaydedilir
- RESOLUTION bildirimi tüm eskalasyon kişilerine gönderilir

**Alarm Detayı (genişletilince):**
- Bildirim geçmişi: Alıcı, konu, e-posta durumu, webhook durumu, tetikleyici tipi
- Onay bilgisi: Kim, ne zaman onayladı
- Çözüm bilgisi: Kim, ne zaman çözdü

---

### 12.11 Eskalasyon Kişileri Ekranı (Admin)

**Erişim:** Admin Panel → Eskalasyon Kişileri sekmesi

**Aksiyon: Yeni Kişi Ekle**

| Alan | Zorunlu | Açıklama |
|---|---|---|
| Kullanıcı | ✓ | Sistemdeki aktif kullanıcılardan seç |
| Takım | ✗ | Hangi takımın alarmlarını alacak |
| Organizasyonel Rol | ✗ | PO / TECH / MANAGER / C-LEVEL |
| Minimum Alarm Seviyesi | ✓ | WARNING / HIGH / CRITICAL |
| Webhook URL | ✗ | Slack/Teams bildirim URL'si |
| Webhook Tipi | ✗ | SLACK / TEAMS |
| Aktif | ✓ | Bu kişi bildirim alsın mı |

**Eskalasyon Matrisi Açıklaması (ekranda gösterilir):**

```
WARNING → PO + Technical Team
HIGH    → PO + Technical Team + Manager
CRITICAL→ PO + Technical Team + Manager + C-Level
```

**Aksiyon: Düzenle** — Tüm alanları güncelle  
**Aksiyon: Sil** — Onay sonrası sil (ilişkili bildirim logları korunur)

---

### 12.12 Alarm Eşikleri Ekranı (Admin)

**Erişim:** Admin Panel → Alarm Eşikleri sekmesi

**Konfigürasyon Alanları:**

| Alan | Varsayılan | Açıklama |
|---|---|---|
| Uyarı Günü (Warning Days) | 30 | Bu gün veya altında WARNING alarmı |
| Yüksek Gün (High Days) | 15 | Bu gün veya altında HIGH alarmı |
| Kritik Gün (Critical Days) | 7 | Bu gün veya altında CRITICAL alarmı |
| Tekrar Bildirim Aralığı (saat) | 24 | DAILY_REALERT ne sıklıkla gider |

**Inline Düzenleme:** Karta tıkla → değeri değiştir → kaydet

---

### 12.13 Takım Yönetimi Ekranı (Admin)

**Erişim:** Admin Panel → Takım Yönetimi sekmesi

**Tablo Kolonları:**

Takımlar **kart görünümünde** listelenir; her kartta takım adı, e-posta, lider (PO), müdür ve üye sayısı/org rolleri gösterilir (eski SY/UG takım türü kaldırılmıştır).

| Kart Bilgisi |
|---|
| Takım Adı |
| E-posta |
| Lider (PO) |
| Müdür (AD `manager` ilişkisinden) |
| Üyeler + org rolleri (renkli rozet) |
| Aktif Durumu |
| Aksiyonlar |

**Aksiyon: Yeni Takım Ekle**

| Alan | Zorunlu | Açıklama |
|---|---|---|
| Takım Adı | ✓ | Benzersiz olmalı |
| E-posta | ✓ | Takım iletişim e-postası |
| Lider | ✗ | Sistemdeki aktif kullanıcılardan seç (PO atanınca otomatik dolabilir) |
| Açıklama | ✗ | Takım hakkında not |
| Aktif | ✓ | Takım aktif mi |

**Aksiyon: Düzenle** — Tüm alanları güncelle (başarılı kayıt sonrası modal 1.8s içinde kapanır)  
**Aksiyon: Sil** — Sertifika ataması yoksa silinebilir, onay diyaloğu gösterilir  
**Aksiyon: Üyeleri Görüntüle** — Kart üzerinden üyeler listelenir, org rolleri renkli görünür

> **Otomatik lider:** Bir kullanıcı PO olarak takıma atandığında, takımın lideri yoksa otomatik lider olur; mevcut lider **ezilmez**. Müdür–takım ilişkisi kurulunca takıma otomatik MANAGER eskalasyon kontağı eklenir.

---

### 12.14 Kullanıcı Yönetimi Ekranı (Admin)

**Erişim:** Admin Panel → Kullanıcı Yönetimi sekmesi

**Tablo Kolonları:**

| Kolon |
|---|
| Kullanıcı Adı |
| Sicil No |
| Ad Soyad |
| E-posta |
| Kaynak (Yerel / LDAP) |
| Sistem Rolü (rozet) |
| Org Rolü (renkli rozet) |
| Takım |
| Müdür |
| Aktif Durumu |
| Kilit Durumu (🔒 kalıcı kilitliyse) |
| Aksiyonlar |

**Aksiyon: Yeni Kullanıcı Ekle**

| Alan | Zorunlu | Açıklama |
|---|---|---|
| Kullanıcı Adı | ✓ | Benzersiz, sonradan değiştirilemez |
| Şifre | ✓ | Min. 4 karakter (yerel hesaplar için) |
| Ad Soyad | ✓ | Görünen ad |
| E-posta | ✓ | İletişim e-postası |
| Sicil No | ✗ | Kurum kimlik numarası |
| Sistem Rolü | ✓ | USER / AUDIT / ADMIN |
| Org Rolü | ✗ | TECH / PO / MANAGER / C-LEVEL |
| Takım | ✓ | Hangi takıma atanacak |
| Müdür | ✗ | Üst yönetici (kapsam/eskalasyon için) |
| Aktif | ✓ | Hesap aktif mi |

**Aksiyon: Düzenle** — Kullanıcı adı dışında tüm alanları güncelle (AD'den gelen kullanıcılarda da org rol/takım/müdür düzenlenip kaydedilebilir; bir sonraki AD girişinde provizyon yeniden uygulanabilir)  
**Aksiyon: Şifre Değiştir** — Ayrı modal, yeni şifre girişi (yerel hesaplar)  
**Aksiyon: Kilidi Aç** — Kalıcı kilitli hesabı serbest bırak  
**Aksiyon: Sil** — Onay sonrası kullanıcıyı sil

> **Not:** LDAP/AD kaynaklı kullanıcılar ilk girişte otomatik oluşur; org rol, müdür ve takım ilişkisi AD özniteliklerinden türetilir (bkz. §3.1, §10.1). Sistem rolü ADMIN/AUDIT için manuel atama korunur (provizyon bunu düşürmez).

---

### 12.15 Denetim Günlüğü Ekranı (Admin / Audit)

**Erişim:** Üst menü → Denetim Günlüğü sekmesi

**Özet Panel:**

| Metrik |
|---|
| Son 24 saatteki toplam olay sayısı |
| Son 7 gündeki toplam olay sayısı |
| Son 24 saatteki başarısız giriş sayısı |
| Son 7 gündeki başarısız giriş sayısı |
| Anomali sayısı |

**Filtreler:**

| Filtre |
|---|
| Kullanıcı adı |
| Olay tipi (DOMAIN_ADD, USER_UPDATE, LOGIN_FAIL, vb.) |
| Sonuç (SUCCESS / FAILURE) |
| Sadece anomalileri göster |

**Tablo Kolonları:**

| Kolon |
|---|
| Tarih/Saat (UTC) |
| Olay Tipi |
| Kullanıcı |
| IP Adresi |
| Coğrafi Konum |
| Kaynak Nesne |
| Sonuç |
| Tarayıcı |

**Satır Genişletme:** Alan değişiklikleri (eski değer → yeni değer) gösterilir

---

### 12.16 Sistem Sağlığı Ekranı (Admin)

**Erişim:** Üst menü → Sistem Sağlığı sekmesi

**Otomatik yenileme:** 30 saniyede bir

**Bölümler:**

**Zamanlayıcı Durumu:**

| Metrik |
|---|
| Durum (Çalışıyor / Bekliyor) |
| Çalışan Run ID |
| Son çalışma zamanı |
| Sonraki çalışma tahmini |
| Aktif domain sayısı |
| Instance ID |

**Dağıtık Kilit Durumu:**

| Metrik |
|---|
| Kilit tutulmuş mu |
| Kimin tuttuğu |
| Kilit bitiş zamanı |
| Bu pod'un kilidi mi |

**Veritabanı Bağlantı Havuzu (HikariCP):**

| Metrik |
|---|
| Aktif bağlantılar |
| Boşta bağlantılar |
| Toplam bağlantılar |
| Bekleyen iş parçacıkları |
| Maksimum havuz boyutu |

**JVM Bellek:**

| Metrik |
|---|
| Kullanılan bellek (MB) |
| Boşta bellek (MB) |
| Toplam bellek (MB) |
| Maksimum bellek (MB) |
| Kullanım yüzdesi |

**Tarama İstatistikleri:**

| Metrik |
|---|
| Son tarama süresi (ms) |
| Son taramadaki toplam domain sayısı |
| Son taramadaki uyarı sayısı |
| Son taramadaki hata sayısı |
| Tarama alarmı (2 saatte bir tarama yoksa) |

**SMTP Durumu:**

| Metrik |
|---|
| Son 30 gündeki başarılı e-posta sayısı |
| Son 30 gündeki başarısız e-posta sayısı |
| Başarı oranı (%) |
| E-posta teslimat logları (tetikleyici tipine göre filtreli) |

**Admin Aksiyonları (Sistem Sağlığı ekranından):**

| Aksiyon | Açıklama |
|---|---|
| Kilidi Zorla Serbest Bırak | Sıkışmış scheduler kilidini temizle |
| Manuel tarama başlat | Anlık tüm domain taraması |

---

### 12.17 Zayıf Algoritma Raporu (Admin)

**Erişim:** Üst menü → Zayıf Algoritma Raporu sekmesi

SHA-1 imzalı, RSA-1024, RC4 gibi güvensiz algoritma kullanan sertifikaları listeler.

| Görüntülenen Bilgi |
|---|
| Domain |
| İmza Algoritması |
| Anahtar Algoritması |
| Anahtar Boyutu |
| Zayıflık Tipi |
| Kritiklik Seviyesi (Critical / High) |
| Sahip |
| Takım |
| Bitiş Tarihi |
| Durum |

**Sıralama:** Domain / Sahip / Takım / Algoritma / Zayıflık / Bitiş / Durum

---

### 12.18 Haftalık Raporlar Ekranı

**Erişim:** Üst menü → Haftalık Raporlar

Takımların haftalık operasyon raporlarını yazdığı, izlediği ve PO'nun onayladığı ekran.

| Bölüm |
|---|
| Hafta seçimi (geçerli/geçmiş haftalar) |
| Bölüm 1 — Olay özeti (acil / yüksek olay sayıları) |
| Bölüm 2 — Açık olay & problemler, planlı işler (☑/☐ liste) |
| Durum (Taslak / Gönderildi / Onaylandı / İade) |

**Aksiyonlar:**
- **Gönder** — raporu PO onayına gönderir; PO'ya HTML e-posta ile bildirim/onay bağlantısı gider
- **PO Onayı** — PO doğrudan ekrandan veya **e-postadaki bağlantıdan tek tıkla** onaylar/iade eder
- **Transfer** — rapor başka bir takıma devredilebilir
- **Cuma hatırlatması** — bekleyen raporlar için otomatik hatırlatma e-postası

> Kapsam: USER kendi takımının raporunu yazar; PO/TEAM_ADMIN liderlik ettiği takımların raporlarını görür ve onaylar; global admin tümünü görür.

---

### 12.19 İzleme Ekranı (Monitoring)

**Erişim:** Üst menü → İzleme

Sertifika dışı erişilebilirlik izlemesi (sertifika sweep'inden bağımsız).

| İzleme Tipi | Ölçülen |
|---|---|
| Uptime (HTTP) | Statü kodu + yanıt süresi |
| Port (TCP/UDP) | Açık/kapalı + bağlantı süresi |
| DNS | A/AAAA/CNAME/MX/TXT kayıt değişimi (`CHANGED` / `ROTATED`) |

**Alarm tipleri:** `ACCESSIBILITY` (erişilemiyor), `PORT_DOWN`, `DNS_FAILURE`, `DNS_CHANGED`. Kesinti teyidi `MonitoringOutageService` ile birkaç ardışık başarısız kontrol sonrası verilir (tek seferlik dalgalanma alarm üretmez).

> Yapılandırma (hedef ekleme/düzenleme) yalnız global admin'e açıktır; diğer roller salt-okuma görür.

---

### 12.20 Ayarlar Ekranı (yalnız `admin`)

**Erişim:** Üst menü → Ayarlar (yalnız yerel `admin` hesabı)

**LDAP / Active Directory:**
- Sunucu URL, base DN, bind DN/parolası (AES-GCM şifreli), kullanıcı/öznitelik eşlemeleri
- **Bağlantı testi** ve **öznitelik görüntüleyici** (bir kullanıcının AD özniteliklerini canlı görüntüleme)

**SMTP / E-posta:**
- Sunucu/port/STARTTLS, kullanıcı adı/parola, gönderen adresi
- Test e-postası gönderimi

---

### 12.21 SQL Playground Ekranı (yalnız global admin)

**Erişim:** Üst menü → SQL Playground

- Salt-okuma (SELECT) sorgu konsolu — yazma sorguları reddedilir
- Sonuç tablosu + sorgu geçmişi (gece otomatik temizlenir)
- Yalnız global admin erişebilir (denetim altında)

---

### 12.22 Yetki (Permissions) Ekranı (yalnız global admin)

**Erişim:** Admin Panel → Yetki sekmesi

- Rol bazında (TEAM_ADMIN / USER / AUDIT) **view / edit / execute** izinlerini açıp kapatan matris
- ADMIN sütunu tam yetkili ve **kilitlidir**
- Kaynaklar gruplara ayrılır: sertifikalar, iletişim, yönetim, alarmlar, izleme, günlükler, raporlar, araçlar
- Hassas işlemler için onay diyaloğu; **"Varsayılanlara Dön"** ile rol varsayılanlarına sıfırlama
- Ekranın üstündeki bilgi paneli güncel rol modelini (ADMIN / Müdür / TEAM_ADMIN-PO / USER / AUDIT ve kapsam mantığı) özetler

---

## 13. Operasyonel Prosedürler

### 13.1 Yeni Domain Nasıl Eklenir

1. **Admin Panel → Domain Inventory** sekmesine git
2. **"Alan Ekle"** butonuna tıkla
3. **Temel Bilgiler** bölümünde:
   - Domain adını gir (ör. `api.example.com`)
   - Port'u gir (çoğunlukla `443`)
   - Sorumlu Takımı seç (alarm/bildirim bu takıma yönlendirilir)
   - Tier seç (Tier 1 = müşteriye dönük production)
4. **Operasyonel Bayraklar** bölümünü doldu
5. **Kaydet** butonuna bas
6. Sistem bir sonraki saatlik taramada bu domaini kontrol edecek
7. İlk kontrol sonuçlarını Dashboard'da görebilirsin

> **İpucu:** "Şimdi Kontrol Et" butonuna basarak taramayı hemen tetikleyebilirsin.

---

### 13.2 Alarm Aldığımda Ne Yapmalıyım

**E-posta ile alarm aldıysanız:**

1. E-postadaki domain bilgisine bakın
2. Kalan gün sayısını kontrol edin
3. **Eğer haberdar olduysanız:** Site Monitör'de ilgili alarmı **"Onayla"** → günlük tekrar bildirimler durur
4. **Sertifikayı yenileme sürecini başlatın** (CA, ekip, platform)
5. **Yenileme tamamlandığında:** Sistemi bir sonraki taramada otomatik olarak kontrol edecektir
6. **Sorun giderilinse:** Alarm Geçmişi'nde **"Çözüldü İşaretle"** → çözüm bildirimi gönderilir

**Acil durumda (7 gün kaldı / CRITICAL):**

1. Alarmı hemen **Onayla**
2. Sertifika yenilemeyi **derhal başlat**
3. Gerekirse **"Yeniden Bildir"** ile üst yönetimi anında bilgilendir
4. Yenileme ve dağıtım tamamlanınca **"Çözüldü İşaretle"**

---

### 13.3 Sertifika Yenilendikten Sonra Ne Yapmalıyım

1. Yeni sertifikayı sunucuya/platforma dağıtın
2. Site Monitör bir sonraki saatlik taramada yeni sertifikayı tespit edecektir
3. **Dağıtım doğrulama:** Envanterde `expected_fingerprint` güncellenmişse sistem uyumluluğu kontrol edecektir
4. Alarm Geçmişi'nde ilgili alarmı **"Çözüldü İşaretle"** ile kapatın
5. Sistem otomatik olarak `RESOLUTION` bildirimi gönderir

---

### 13.4 Takım Kurulumu Nasıl Yapılır

**Adım 1 — Kullanıcıları Oluştur:**
- Admin Panel → Kullanıcı Yönetimi → Yeni Kullanıcı Ekle
- Her kullanıcı için uygun Sistem Rolü ve Org Rolü ata

**Adım 2 — Takımı Oluştur:**
- Admin Panel → Takım Yönetimi → Yeni Takım Ekle
- Takım liderini (PO) ata
- Takım e-postasını gir

**Adım 3 — Kullanıcıları Takıma Ata:**
- Kullanıcı Düzenle → Takım alanını güncelle

**Adım 4 — Eskalasyon Kişilerini Tanımla:**
- Admin Panel → Eskalasyon Kişileri → Yeni Kişi Ekle
- Her kişi için minimum alarm seviyesi seç
- İsteğe bağlı Slack/Teams webhook ekle

**Adım 5 — Sertifikaları Takıma Ata:**
- Domain Inventory → Düzenle → Sorumlu Takımı seç

---

### 13.5 Eskalasyon Kişisi Nasıl Tanımlanır

```
Senaryo: Bir takım için eskalasyon ayarla

PO (Ürün Sahibi):
  Min Seviye: WARNING → Her alarm türünde haberdar olur

Teknik Sorumlu (TECH):
  Min Seviye: WARNING → Her alarm türünde haberdar olur

Yönetici (MANAGER):
  Min Seviye: HIGH → Yüksek ve kritik alarmları alır

Direktör (C-LEVEL):
  Min Seviye: CRITICAL → Sadece kritik alarmları alır
```

---

### 13.6 Backend Düştüğünde Ne Olur

Site Monitör, backend kapalıyken aşağıdaki davranışları gösterir:

1. **Tarama durur** — Aktif sertifika kontrolü yapılamaz
2. **Bildirimler gönderilmez** — Scheduler çalışmadığı için DAILY_REALERT gönderilemez

**Backend Yeniden Açıldığında:**

1. **Startup Catch-Up** mekanizması devreye girer (ağ çağrısı olmadan, hızlı)
2. O güne ait gönderilmemiş bildirimler anında gönderilir
3. Ardından tam sertifika taraması başlar
4. Güncel sertifika verileri `latest_checks` tablosuna yazılır

> Sonuç: Backend kapalı kalsa bile **o günün bildirimleri kesinlikle gönderilir** — geç bile olsa açılışta telafi edilir.

---

### 13.7 Eski Log Kayıtlarının Otomatik Temizliği

Her gece **03:30** (`cert.monitor.scheduler.cleanup-cron` ile özelleştirilebilir) `SchedulerService.cleanupOldLogs()` çalışır ve tabloları sınırlı tutar:

| Tablo | Saklama Süresi | Açıklama |
|---|---|---|
| `audit_log` | 180 gün | Admin/güvenlik aksiyonları |
| `notification_logs` | 90 gün | E-posta + webhook gönderim kayıtları |
| `sql_query_history` | 30 gün | SQL Playground geçmişi |

- Native bulk `DELETE` ile kısa transaction süresi.
- Cleanup başarısız olursa job DEVAM ETMEZ ama uygulama çökmez — bir sonraki gece tekrar denenir.
- Manuel tetikleme yok; pod restart'ta tek seferlik çalıştırma istenirse cron'u geçici olarak değiştir.

---

### 13.8 Production'da Proxy Üzerinden Kontrol

Kurumsal ağ politikaları nedeniyle bazı domain'lerin firewall/WAF'i pod IP'sini reddeder. Bu domain'ler için:

1. OpenShift Deployment YAML'ına `HTTP_PROXY_HOST=dmzproxy.aknet.akb` ve `HTTP_PROXY_PORT=8080` env vars'larını ekle.
2. Pod yeniden başlat (`oc rollout restart deployment/cert-monitor`).
3. Admin → Envanter → ilgili domain'i düzenle → **"Proxy Üzerinden Kontrol Et"** toggle'ını aç.
4. Bir sonraki sweep'te o domain'in kontrolü proxy üzerinden gider; geri kalanlar direkt outbound olarak çalışır.
5. Loglarda `[cert-proxy]` etiketiyle tunnel kurma adımları takip edilebilir.

> Tüm pod outbound'unu proxy'ye yönlendirmek diğer 11/13 domain'i kırar (v18.45.1 deneyimi). Sadece sorun yaşayan domain'leri işaretle.

---

## 14. Dağıtım ve DevOps

### 14.1 CI/CD Pipeline

```
Git Push (master / develop / release-*)
         │
         ▼
GitHub Actions: .github/workflows/release.yml
  ├─ Backend: mvn test (241 test)
  ├─ Frontend: npx vitest run (72 test)
  ├─ mvn package → JAR
  ├─ npm run build → dist/
  ├─ Docker build → ghcr.io/inanmise/certmonitor:<tag>
  ├─ Docker push → GitHub Container Registry
  ├─ Helm chart OCI push → ghcr.io/inanmise/certmonitor-chart
  ├─ VERSION dosyası güncelle
  └─ GitHub Release oluştur
```

### 14.2 Ortam Stratejisi

| Dal | Ortam | Replica | Cron |
|---|---|---|---|
| `develop` | Geliştirme | 1 | Her 2 saatte bir |
| `release-*` | Staging | 2 (maks. 5) | Gece 03:00 UTC |
| `master` | Production | 3 (maks. 10) | Gece 02:00 UTC |

### 14.3 Helm Dağıtımı

```bash
# Production dağıtımı
helm upgrade --install cert-monitor ./helm/cert-monitor \
  -f helm/cert-monitor/values.yaml \
  -f helm/cert-monitor/environments/master.yaml \
  --set image.tag=10.8.0 \
  --set secret.adminPassword=$ADMIN_PASSWORD \
  --set secret.dbPassword=$DB_PASSWORD \
  -n cert-monitor --create-namespace
```

### 14.4 Docker Image

```
Kayıt Defteri: ghcr.io/inanmise/certmonitor
Etiket formatı:
  - latest              (master son)
  - 10.8.0             (üretim versiyonu)
  - develop-a1b2c3d     (geliştirme commit)
  - 10.8.0-rc           (staging release candidate)
```

---

## 15. Yüksek Erişilebilirlik

### 15.1 Pod Dağıtımı

```yaml
replicas: 3           # Minimum 3 pod
maxSurge: 1          # Rolling update: önce +1 pod ekle
maxUnavailable: 0    # Sıfır kesinti güncelleme

topologySpreadConstraints:
  maxSkew: 1         # Node'lar arası maksimum 1 pod farkı
```

### 15.2 Pod Disruption Budget

```yaml
minAvailable: 2      # Production: En az 2 pod çalışmalı
                     # Staging: En az 1 pod çalışmalı
```

### 15.3 Sağlık Kontrolleri

| Tip | Endpoint | Başlama | Periyot | Başarısızlık |
|---|---|---|---|---|
| Startup | GET /health | - | 10s | 12 hata → restart |
| Readiness | GET /health | 30s | 10s | 3 hata → trafik kesilir |
| Liveness | GET /health | 60s | 30s | 3 hata → restart |

### 15.4 Dağıtık Oturum (Opsiyonel)

```properties
SPRING_SESSION_STORE_TYPE=jdbc
```

JDBC Session aktifleştirilince tüm pod'lar kullanıcı oturumlarını paylaşır — bir pod yeniden başlasa bile kullanıcı oturumu kaybolmaz.

### 15.5 Dağıtık Zamanlayıcı Kilidi

Birden fazla pod aynı anda tarama yapmaz. DB tabanlı kilit (TTL: 10 dk) yalnızca bir pod'un tarama yürütmesini sağlar.

---

## 16. Konfigürasyon Referansı

### 16.1 Temel Konfigürasyon

| Parametre | Varsayılan | Açıklama |
|---|---|---|
| `cert.monitor.warning-days` | 30 | Uyarı başlangıç günü |
| `cert.monitor.parallel-workers` | 20 | Eş zamanlı kontrol sayısı |
| `cert.monitor.check-timeout-seconds` | 10 | SSL soket timeout |
| `cert.monitor.scheduler.cron` | `0 0 * * * *` | Saatlik tarama cron |
| `cert.monitor.scheduler.stale-minutes` | 65 | Bayat domain eşiği (dk) |
| `cert.monitor.scheduler.lock-ttl-minutes` | 10 | Dağıtık kilit TTL |
| `cert.monitor.cache.crl-ttl-hours` | 1 | CRL önbellek süresi |
| `cert.monitor.cache.crl-max-size` | 200 | CRL önbellek kapasitesi |
| `cert.monitor.password.min-length` | 4 | Minimum şifre uzunluğu |
| `cert.monitor.alert.default-warning-days` | 30 | Uyarı gün eşiği |
| `cert.monitor.alert.default-high-days` | 15 | Yüksek gün eşiği |
| `cert.monitor.alert.default-critical-days` | 7 | Kritik gün eşiği |
| `cert.monitor.alert.default-realert-hours` | 24 | Tekrar bildirim aralığı |
| `cert.monitor.scheduler.cleanup-cron` | `0 30 3 * * *` | Gece log temizleme cron'u |
| `EXECUTOR_CORE_SIZE` | 20 | certCheckExecutor core thread |
| `EXECUTOR_MAX_SIZE` | 50 | certCheckExecutor max thread |
| `EXECUTOR_QUEUE_CAPACITY` | 1000 | certCheckExecutor queue boyutu |
| `mail.send.retry-delay-ms` | 90000 | SMTP 421 retry gecikmesi (async) |

**Proxy / Outbound:**

| Parametre / Env | Açıklama |
|---|---|
| `HTTP_PROXY_HOST` / `HTTP_PROXY_PORT` | Kurumsal HTTP CONNECT proxy (OCSP/CRL/use_proxy domain'ler için) |
| `HTTP_PROXY` (URL formatı) | Alternatif: `http://user:pass@host:port` — `ChainValidationService` URL parse fallback'i |
| `NO_PROXY` | Proxy bypass suffix listesi (virgülle ayrılmış) |
| `TLS_MODE` | `browser` (default, ALPN+TLS1.2 zorla) veya `default` (debug) |

**Log Seviyeleri (Opsiyonel):**

| Parametre | Açıklama |
|---|---|
| `logging.level.com.certmonitor` | Default `DEBUG` |
| `logging.level.com.certmonitor.config.RequestLoggingFilter` | `TRACE` ile full HTTP req/resp logging açılır (masked) |
| `logging.level.root` | Default `INFO` |
| `LOG_TIMEZONE` | `Europe/Istanbul` (default) |

### 16.2 Kilitleme Konfigürasyonu

| Parametre | Varsayılan | Açıklama |
|---|---|---|
| `cert.monitor.lockout.failures-needed` | `5,3,2,1` | Her seviyede gerekli hata |
| `cert.monitor.lockout.durations-seconds` | `30,120,600,1800` | Her seviyede bekleme |
| `cert.monitor.lockout.permanent-failures` | 5 | Kalıcı kilitleme için toplam |
| `cert.monitor.remember-me.validity-seconds` | 604800 | "Beni Hatırla" süresi (7 gün) |
| `cert.monitor.inactivity-timeout-minutes` | 5 | Hareketsizlik timeout |

### 16.3 E-posta Konfigürasyonu

| Parametre | Açıklama |
|---|---|
| `CERT_MONITOR_EMAIL_ENABLED` | E-postayı etkinleştir (true/false) |
| `SPRING_MAIL_HOST` | SMTP sunucu adresi |
| `SPRING_MAIL_PORT` | SMTP portu (587 = STARTTLS) |
| `SPRING_MAIL_USERNAME` | Gönderen hesap |
| `SPRING_MAIL_PASSWORD` | SMTP şifresi / App Password |
| `CERT_MONITOR_EMAIL_FROM` | Gönderen e-posta adresi |

---

## 17. Sürüm ve Yayın Bilgisi

| Bilgi | Değer |
|---|---|
| Güncel Versiyon | 19.11.x |
| Java Versiyonu | 25 (LTS) |
| Spring Boot | 4.1.0 (Spring Framework 7, Jakarta EE 11) |
| BouncyCastle | 1.78.1 |
| React / Vite | 18.3 / 5.4 |
| PostgreSQL | 16-alpine |
| Docker Image | `ghcr.io/inanmise/certmonitor` |
| Helm Chart | `ghcr.io/inanmise/certmonitor-chart` |
| Backend Test Sayısı | 1024 |
| Frontend Test Sayısı | 168 |
| Desteklenen Diller | Türkçe / İngilizce |
| Lisans | Kurumsal kullanım |
| Geliştirici | inanmise (erdi.inanmis@gmail.com) |

### 19.x Sürüm Vurguları (bu sürüm — Haziran 2026)

- **İzleme detay grafikleri:** Keyword/Ping izleme kartına tıklayınca açılan modal **üç sekme** (Kontrol
  Geçmişi / Alarm Geçmişi / **Süre Grafiği**). Süre grafiği keyword için HTTP yanıt süresi, ping için RTT'yi
  **30/90 güne** kadar gösterir: **ortalama + min/max bandı + p95**, kesinti kovaları kırmızı, ping'de **paket
  kaybı (%) ikinci eksende**. Hazır aralık (24s/7g/30g/90g) + **özel aralık** (x gün önce şu saatler arası).
  recharts tembel-yüklenir.
- **Per-monitor doğrulama:** Keyword/Ping alarmı girilirken **doğrulama denemesi sayısı + aralık** kullanıcıdan
  alınır (varsayılan 30sn × 3); yanlış pozitifleri azaltır.
- **SystemHealth — Kullanıcı & Oturum:**
  - **Login aktivite zaman-serisi grafiği** (ResponseTimeChart deseni): X=zaman, Y=login adedi; toplam + başarılı/
    başarısız çizgileri → gün-içi/günler-arası artış-azalış. 1g/7g/30g + **istenen güne git** (saatlik) + **özel
    aralık** ("x gün x saat").
  - **Aktivite heatmap** (hafta-günü × saat): **ISO hafta (Pzt–Paz)**, **bugün satırı vurgulu**, **gün/saat/hafta
    toplamları**; tek hafta tam-genişlik **büyük** gösterim + **Önceki/Sonraki Hafta** ile **4 hafta** geriye gezme.
  - **Reverse-DNS:** login anında PTR çözümü kaydedilir; "en çok login kaynakları"nda IP yanında DNS adı.
  - **En çok login kullanıcılar** + rol/takım drill-down: **ad-soyad + AD fotoğrafı** (case-insensitive eşleştirme).
- **Proje-geneli kullanıcı kimliği:** username görünen her yer ortak **`UserBadge`** ile **ad-soyad + avatar**
  gösterir (denetim kaydı actor, haftalık rapor oluşturan/onaylayan, sertifika not yazarı, alarm ack/çözen +
  bildirilen alıcılar, eskalasyon kontakları). Hafif **`/api/users/directory`** (username/e-posta → ad/foto).
- **Olay (incident) modülü:** **Problem Tipi** (çoklu seçim), **Etkilenen Uygulama/Sistemler/Müşteri-Adedi/
  İşlem-Adedi** alanları; combobox seçenekleri (Kanal/Servis/Hata-kodu/Fonksiyon-kodu) **takıma özel** (bir
  takımın eklediği değer diğerine sızmaz); tarih alanları **tam-dakika** + "Tamam" butonu; combobox placeholder'ları;
  günlük trend grafiği okunaklı (olaysız günler + tarih/adet etiketleri).
- **OpenShift sağlamlaştırma:** Actuator **probe grupları** (`/health/readiness`, `/health/liveness`) +
  **readiness-gating** (açılış bootstrap'ı boyunca `REFUSING_TRAFFIC`) → pod yalnız gerçekten hazır olunca trafiğe
  girer ("Ready ama ilk istekler askıda" sorunu çözüldü); liveness DB'den bağımsız (gereksiz restart yok); warmup CPU tavanı.
- **Güvenlik (prod-öncesi):** Settings (SMTP/LDAP/Secret/DB) erişimi **konfigüre bootstrap-admin** bayrağıyla
  (literal "admin" değil → kilitlenme-güvenli); yıkıcı işlemler için **dedike+sensitive yetkiler** (`inventory.purge`,
  `system_health.terminate`, `system_health.scheduler_lock`); `incidents.*` kendi izin grubunda; **parola politikası**
  min 12 / max 64 / history 5.

### Platform Yükseltmesi — JDK 25 + Spring Boot 4.1 (Haziran 2026)

- **Çalışma zamanı:** Java 21 → **25 (LTS)**; Spring Boot 3.3.6 → **4.1.0** (Spring Framework 7, Jakarta EE 11, Hibernate 7, Tomcat 11). JDK 25 desteği için Spring Boot major yükseltmesi zorunluydu.
- **Jackson 2 → Jackson 3** (`tools.jackson`): SNAKE_CASE API sözleşmesi korundu (controller JSON testleriyle doğrulandı); tarih özellikleri `spring.jackson.datatype.datetime.*` altına taşındı.
- **Test çerçevesi (SB4):** `@MockBean` → `@MockitoBean`, modüler test starter'ları (`webmvc-test`, `data-jpa-test`); `@WebMvcTest` cache döngüsü için `@EnableCaching` ayrı config'e; EMF dairesel bağımlılığı `@Lazy` ile kırıldı.
- **Araç zinciri:** JaCoCo 0.8.14 (JDK 25 bytecode), Lombok 1.18.42 + `annotationProcessorPaths` (JDK 23+ artık classpath'ten processor keşfetmiyor); Docker/CI Eclipse Temurin 25.
- **Doğrulama:** 835 backend + 145 frontend test JDK 25'te yeşil; performans denetiminde upgrade kaynaklı leak/bug bulunmadı (yalnız bir mapper yeniden-kullanım iyileştirmesi).
- **Operasyonel not:** Spring Session 4 oturum şeması değişmiş olabilir → ilk deploy'da kullanıcılar bir kez yeniden login olabilir; Hibernate 7 `ddl-auto=update` ilk açılışta izlenmeli.

### 18.83.x Sürüm Vurguları (Haziran 2026)

- **Kimlik & Provizyon:** LDAP/Active Directory ile giriş; ilk girişte otomatik provizyon (orgRole, müdür ilişkisi, takım). Bind parolası AES-GCM şifreli; Ayarlardan bağlantı testi + öznitelik görüntüleyici.
- **Rol modeli (Faz 3b):** Çok-takım yetki kapsamı — müdür = kapsamlı **salt-okuma** ADMIN (astlarının takımları), PO = TEAM_ADMIN (liderlik ettiği takımlar, görüntüleme+yönetim). Oturuma `viewTeamIds`/`manageTeamIds` kapsamları yazılır; global-only işlemler yalnız global admin'e açık.
- **Tek takım modeli:** Eski SY/UG ikili takım yapısı kaldırıldı; her sertifika tek takıma atanır. Takım yönetimi kart görünümüne geçti (lider/müdür/üyeler).
- **Haftalık Raporlar:** Takım operasyon raporları + PO **e-posta bağlantısıyla onay**, cuma hatırlatması, takımlar arası transfer.
- **İzleme:** Uptime/port/DNS izleme + `ACCESSIBILITY`/`PORT_DOWN`/`DNS_FAILURE`/`DNS_CHANGED` alarmları; `MonitoringOutageService` ardışık-başarısızlık teyidi.
- **Yetki Matrisi:** Rol bazlı view/edit/execute izin ekranı (ADMIN kilitli) + güncel rol modeli bilgi paneli; SQL Playground salt-okuma konsolu (yalnız global admin).
- **Sağlamlaştırma:** Uçtan uca performans/kilit/sızıntı denetimi; backend test sayısı **539 → 829**'a çıkarıldı (e-posta/SMTP builder, LDAP, SQL, eskalasyon N+1, zayıf-algoritma kapsamı).

### 18.50.x Sürüm Vurguları (Haziran 2026)

- **Dayanıklılık:** `CertificateCheckerService` HSTS check'inde `HttpURLConnection` leak'i try-finally ile kapatıldı; `ChainValidationService` OCSP/CRL bağlantıları her durumda `disconnect()` çağırıyor.
- **Hata yönetimi:** `GlobalExceptionHandler`'a 5 yeni handler + catch-all eklendi; stack trace artık UI'ye sızmıyor (500 → "Sunucu hatası").
- **Frontend:** `ErrorBoundary` root + tab seviyesinde; `SystemHealth` + `InventoryManager` `Promise.allSettled` ile bir endpoint çökse de diğerleri yüklenir; "Yüklenemedi" banner'ı.
- **Performans:** `EscalationService.processResults` N+1 sorunu çözüldü (sweep başında batch `findByDomainIn` + `findOpenByDomainIn`); `saveResult` cache evict'leri sweep sonuna toplandı (4000 evict → 4 evict).
- **SMTP:** 421 rate-limit retry'ı asenkron oldu — caller thread artık 90s bloke etmiyor (`ScheduledExecutorService` daemon).
- **Validation:** `CertificateInventory` model'inde `@NotBlank/@Pattern/@Min/@Max`; controller'da `@Valid`; geçersiz body → 400 + alan listesi.
- **Operasyonel:** Gece 03:30 cron'u `audit_log` (180g), `notification_logs` (90g), `sql_query_history` (30g) temizler.
- **Observability:** `RequestLoggingFilter` TRACE seviyede full HTTP log + 60+ alan masking (`*******`); opt-in env var ile aktif.
- **Envanter:** Domain bazlı `use_proxy` bayrağı (problemli WAF/firewall domain'leri için proxy yönlendirmesi); Domain rename (`latest_checks`, `certificate_checks`, `alert_events`, `notes` atomik taşıma).
- **Test:** Backend 530 → 539 (+9 handler test), Frontend 114 → 121 (+4 ErrorBoundary, +3 SystemHealth load-error).

### Sürüm Numaralandırma

| Prefix | Bump Tipi | Örnek |
|---|---|---|
| `feat:` | Minor | 10.7.0 → 10.8.0 |
| `fix:` / `chore:` / `refactor:` | Patch | 10.8.0 → 10.8.1 |
| `BREAKING CHANGE` | Major | 10.8.0 → 11.0.0 |

---

## 18. Production Dağıtım & Güvenlik Checklist'i

Site Monitör'ün **varsayılan konfigürasyonu yerel geliştirme** içindir; kod default'ları kasıtlı olarak
güvensizdir ve prod profilinde / ortam değişkenleriyle override edilir. **Prod'a çıkmadan önce** aşağıdakiler
ayarlanmalıdır.

### 18.1 Zorunlu (güvenlik-kritik)

| Değişken | Neden | Güvensiz Default |
|---|---|---|
| `CERT_MONITOR_SECRET_KEY` | Saklanan LDAP/SMTP parolaları AES-GCM ile bu anahtarla şifrelenir. Boşsa dahili **dev anahtarına** düşer → sırlar kaynak koduna erişen herkesçe çözülebilir. **≥32 rastgele karakter, kalıcı.** | boş → dev anahtarı |
| `CERT_MONITOR_USERNAME` / `CERT_MONITOR_PASSWORD` | Bootstrap admin kimliği (zayıf default). | `user` / `password` |
| `CORS_ALLOWED_ORIGINS` | Yalnız prod host(lar)ına izin ver. | `localhost:5173/3000` |
| `COOKIE_SECURE=true` | Oturum çerezi yalnız HTTPS üzerinden (oturum çalınmasını önler). | `false` (prod profilinde `true`) |
| `DB_PASSWORD` | PostgreSQL parolası (prod profilinde zorunlu, default yok). | — |

### 18.2 Önerilen

| Değişken | Açıklama |
|---|---|
| `APP_BASE_URL=https://<host>` | E-posta linkleri (haftalık rapor, şifre sıfırlama, olay) bu adresi kullanır; yoksa localhost. |
| `SYSTEM_ADMIN_EMAIL` | Ağ-kesinti/sistem bildirimleri buraya gider — kişisel default'u override edin. |
| `CERT_MONITOR_EMAIL_ENABLED=true` + SMTP | E-posta kapalıyken alarm/eskalasyon/olay bildirimleri **sessizce gönderilmez**. |
| `SPRING_SESSION_STORE_TYPE=jdbc` | Pod restart'larında oturumlar korunur (HA). |
| `PASSWORD_MIN_LENGTH` / `MAX_LENGTH` / `HISTORY_COUNT` | Varsayılanlar prod-uygun (12 / 64 / 5); kurumsal politikaya göre artırılabilir. |

### 18.3 Erişim & Yetki

- **Bootstrap-admin geçidi:** SMTP/LDAP/Secret/DB/General ayarlarına `cert.monitor.username` ile tanımlı bootstrap
  admin HER ZAMAN erişir (literal "admin" değil) → admin `CERT_MONITOR_USERNAME` ile yeniden adlandırılsa da
  kilitlenmez; "admin" adlı başka kullanıcı bypass alamaz.
- **Permission Matrix:** ADMIN tüm yetkilere sahiptir (UI'da kilitli, revoke edilemez). **Yıkıcı işlemler**
  (`inventory.purge`, `system_health.terminate`, `system_health.scheduler_lock`) **sensitive** — matriste grant
  verirken onay ister; sistem-geneli iki işlem ayrıca admin-gated kalır.
- Default grant'ler ilk açılışta seed'lenir; yeni sürümlerin eklediği yetkiler `seedMissingDefaults` ile mevcut
  DB'ye backfill edilir (admin özelleştirmeleri korunur).

### 18.4 OpenShift / Kubernetes

- Sağlık probe'ları Actuator gruplarını kullanır: `livenessProbe → /health/liveness` (DB'den bağımsız → DB blip'i
  pod'u restart ettirmez), `readinessProbe` + `startupProbe → /health/readiness`. Uygulama açılış bootstrap'ı
  boyunca readiness'i `REFUSING_TRAFFIC` tutar → **pod yalnız gerçekten hazır olunca** Service endpoint'lerine
  girer (soğuk-başlangıçta "Ready ama ilk istekler askıda" sorunu yaşanmaz).
- Dağıtım: `helm upgrade` ile chart (`certmonitor-chart`) + imaj güncellenir; `oc rollout status` ile doğrulanır.
  Soğuk-başlangıç CPU tavanı (resources.limits.cpu) warmup'ı hızlandırır.

---

*Bu belge Site Monitör v19.11.x için Haziran 2026 itibarıyla hazırlanmıştır.*  
*Güncellemeler için: "raporu güncelle" komutu ile belge yenilenebilir.*
