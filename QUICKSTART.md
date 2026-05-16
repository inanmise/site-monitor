# SSL/TLS Sertifika İzleme Sistemi — Hızlı Başlangıç

## Gereksinimler

- Java 21
- Maven 3.9+
- Node.js 20+

---

## 1. Yapılandırma

```bash
cp .env.example .env
```

`.env` dosyasını açın ve en az parolayı değiştirin:

```
CERT_MONITOR_USERNAME=admin
CERT_MONITOR_PASSWORD=guclu-parola-girin
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
| Kullanıcı adı | `.env` içindeki `CERT_MONITOR_USERNAME` |
| Parola | `.env` içindeki `CERT_MONITOR_PASSWORD` |

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

```bash
# Tüm sertifikaları al
curl -u admin:parola http://localhost:8080/api/certificates

# Uyarılı sertifikaları al
curl -u admin:parola http://localhost:8080/api/warnings

# Belirli domain'i hemen kontrol et
curl -u admin:parola http://localhost:8080/api/check/google.com

# Zamanlayıcıyı hemen çalıştır
curl -u admin:parola -X POST http://localhost:8080/api/scheduler/run

# İstatistikleri al
curl -u admin:parola http://localhost:8080/api/stats
```

---

## Günlük Kontrol Saati

`application.properties` veya ortam değişkeni:

```properties
cert.monitor.check.hour=2    # 02:00'de çalışır
cert.monitor.check.minute=0
```

---

## Sorun Giderme

| Sorun | Çözüm |
|-------|-------|
| Port 8080 meşgul | `server.port=8081` ayarı |
| Dashboard boş | "Şimdi Kontrol Et" düğmesine basın |
| E-posta gitmiyor | `CERT_MONITOR_EMAIL_ENABLED=true`, SMTP ayarlarını kontrol edin |
| Sertifika okunamıyor | Ağ/güvenlik duvarı erişimini kontrol edin |

---

Daha fazla bilgi için **README.md** dosyasına bakın.
