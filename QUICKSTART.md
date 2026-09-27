<img src="branding/assets/logo-ok-64.png" width="64" alt="SiteMonitor logosu">

# SSL/TLS Sertifika İzleme Sistemi — Hızlı Başlangıç

## Gereksinimler

- Java 25
- Maven 3.9+
- Node.js 24 (CI ve Docker build ile aynı major)

---

## 1. Yapılandırma

```bash
cp .env.example .env
```

`.env` dosyasını açın ve en az parolayı değiştirin:

```
SITE_MONITOR_USERNAME=admin
SITE_MONITOR_PASSWORD=guclu-parola-girin
```

---

## 2. Domain Listesi

`sertifikaListesi.txt` dosyasına izlenecek domain'leri ekleyin:

```
google.com
github.com
api.example.com:8443
internal.app.com
```

---

## 3. Uygulamayı Başlatma

### Seçenek A — Doğrudan (Geliştirme)

**Backend** (terminal 1):

```bash
cd backend
mvn spring-boot:run
```

**Frontend** (terminal 2):

```bash
cd frontend
npm install
npm run dev
```

Tarayıcıda açın: **http://localhost:5173**

### Seçenek B — Docker Compose

```bash
docker-compose up -d
```

Tarayıcıda açın: **http://localhost:8080**

### Seçenek C — Başlatma Betikleri

**Windows:**
```
START.bat
```

**Linux/Mac:**
```bash
chmod +x START.sh && ./START.sh
```

---

## 4. Giriş

| Alan | Değer |
|------|-------|
| Kullanıcı adı | `.env` içindeki `SITE_MONITOR_USERNAME` |
| Parola | `.env` içindeki `SITE_MONITOR_PASSWORD` |

Varsayılan (yerel geliştirme): `user` / `changeme-local-dev`

---

## 5. İlk Kontrol

Giriş yaptıktan sonra **"Şimdi Kontrol Et"** düğmesine basın. Sonuçlar birkaç saniye içinde Dashboard'da görünür.

---

## Dashboard Sekmeleri

| Sekme | İçerik |
|-------|--------|
| Dashboard | Özet istatistikler, kritik sertifikalar |
| Uyarılar | WARNING / HIGH / CRITICAL durumdaki sertifikalar |
| Tüm Sertifikalar | Filtreli ve sıralanabilir tam tablo |
| Admin | Envanter, eşikler, eskalasyon kişileri, uyarı geçmişi |

---

## API'ye Doğrudan Erişim

API HTTP Basic (`curl -u`) **desteklemez** — oturum çereziyle çalışır (`-u` ile her istek 401 döner).
Önce giriş yapıp çerezi saklayın, sonraki isteklerde gönderin:

```bash
# Giriş — oturum çerezini cookies.txt'ye yazar
curl -c cookies.txt -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<parola>"}' http://localhost:8080/api/login

# Tüm sertifikaları al
curl -b cookies.txt http://localhost:8080/api/certificates

# Uyarılı sertifikaları al
curl -b cookies.txt http://localhost:8080/api/warnings

# Belirli domain'i hemen kontrol et
curl -b cookies.txt http://localhost:8080/api/check/google.com

# Zamanlayıcıyı hemen çalıştır
curl -b cookies.txt -X POST http://localhost:8080/api/scheduler/run

# İstatistikleri al
curl -b cookies.txt http://localhost:8080/api/stats
```

---

## Kontrol Sıklığı

Sertifika taraması her saat başı tetiklenir (`SCHEDULER_CRON`, varsayılan `0 0 * * * *`); her domain kendi
**kontrol sıklığına** (Envanter → Düzenle: saatlik / 6 sa / 12 sa / günlük / haftalık) göre sıraya girer.
Eski `site.monitor.check.hour` / `check.minute` ayarları artık yoktur.

---

## Sorun Giderme

| Sorun | Çözüm |
|-------|-------|
| Port 8080 meşgul | `server.port=8081` ayarı |
| Dashboard boş | "Şimdi Kontrol Et" düğmesine basın |
| E-posta gitmiyor | `SITE_MONITOR_EMAIL_ENABLED=true`, SMTP ayarlarını kontrol edin |
| Sertifika okunamıyor | Ağ/güvenlik duvarı erişimini kontrol edin |

---

Daha fazla bilgi için **README.md** dosyasına bakın.
