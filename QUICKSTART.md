# SSL/TLS Sertifika İzleme Sistemi - Hızlı Başlangıç

## Kurulum (2 dakika)

### 1. Bağımlılıkları Yükleyin

**Windows:**
```bash
cd backend
pip install -r requirements.txt
```

**Linux/Mac:**
```bash
cd backend
python3 -m venv venv
source venv/bin/activate  # Linux/Mac
pip install -r requirements.txt
```

### 2. Domain Listesini Ekleyin

`sertifikaListesi.txt` dosyasını açıp kontrol edilecek domain'leri ekleyin:

```
google.com
github.com
api.example.com:8443
web.domain.com
```

### 3. Uygulamayı Başlatın

**Windows:**
```bash
START.bat
```

**Linux/Mac:**
```bash
chmod +x START.sh
./START.sh
```

Veya doğrudan:
```bash
cd backend
python app.py
```

## Tarayıcı ile Erişim

Uygulama başladıktan sonra:

👉 **http://localhost:5000**

## Dashboard Kullanımı

### Sekmeler

1. **Dashboard** - Özet bilgi ve önemli sertifikalar
2. **⚠️ Uyarılar** - Dikkat gerektiren sertifikalar (30 gün kala, hata, vs.)
3. **Tüm Sertifikalar** - Tam tablo görünümü

### İşlevler

- **🔄 Şimdi Kontrol Et** - Tüm domain'leri hemen kontrol et
- **📊 İstatistikler** - Genel özet (toplam, geçerli, uyarılı, hata)
- **Arama** - Domain, veren, konu ile ara
- **Sertifika Tıkla** - Detayları ve geçmişi görüntüle

## API Endpoints

### Temel İstekler

```bash
# Tüm sertifikaları al
curl http://localhost:5000/api/certificates

# Uyarılı sertifikaları al
curl http://localhost:5000/api/warnings

# Belirli domain'in geçmişini al
curl http://localhost:5000/api/history/google.com

# Belirli domain'i hemen kontrol et
curl http://localhost:5000/api/check/google.com

# Zamanlayıcıyı hemen çalıştır
curl -X POST http://localhost:5000/api/scheduler/run

# İstatistikleri al
curl http://localhost:5000/api/stats
```

## Yapılandırma

### Günlük Kontrol Saati

`backend/config.py` içinde:

```python
SCHEDULER_HOUR = 2      # 02:00 (24 saat formatı)
SCHEDULER_MINUTE = 0
```

### Uyarı Süresi

```python
WARNING_DAYS_BEFORE_EXPIRY = 30  # 30 gün kala uyarı
```

### Port Değiştirme

```python
FLASK_PORT = 5000  # İsteğe bağlı port
```

## Domain Listesi Formatı

`sertifikaListesi.txt`:

```
# Yorumlar # ile başlar

# Boş satırlar göz ardı edilir

# Her satırda bir domain
google.com

# Port isteğe bağlı (varsayılan: 443)
github.com:443
api.example.com:8443

# HTTPS dışı portlar da test edilebilir
internal.domain.com:8000
```

## Veritabanı

Sistem otomatik olarak SQLite veritabanı oluşturur (`data/certificates.db`).

**Tablolar:**
- `certificate_checks` - Tüm kontrol geçmişi
- `latest_checks` - En son sonuçlar

Manual olarak sorgu çalıştırmak:

```bash
cd data
sqlite3 certificates.db

# SQL komutları
.tables
SELECT * FROM latest_checks;
SELECT domain, not_after, days_remaining FROM latest_checks ORDER BY days_remaining;
```

## Sorun Giderme

### "Port 5000 already in use"

```bash
# Port değiştir (5001)
# backend/config.py:
FLASK_PORT = 5001
```

### "sertifikaListesi.txt not found"

```bash
# Dosya kök dizinde olmalı
cert-monitor/
├── sertifikaListesi.txt  ← Buraya
```

### Kontrol çalışmıyor

1. Ağ bağlantısını kontrol et
2. Firewall kurallarını kontrol et
3. Domain adı doğru mu? (`nslookup google.com`)

### Dashboard boş görünüyor

1. Console'de hata var mı? (F12 → Console)
2. Backend'in başladığını kontrol et
3. `sertifikaListesi.txt`'te domain var mı?

## Günlükleme

Backend konsolu tüm işlemleri gösterir:

```
2026-05-13 10:30:00 - INFO - Sertifika kontrolü başlandı
2026-05-13 10:30:01 - INFO - Yüklenen domain sayısı: 5
2026-05-13 10:30:05 - INFO - Kontrol tamamlandı - Başarılı: 5, Uyarı: 1, Hata: 0
```

## İş Akışı

```
1. Uygulamayı başlat
2. Domain listesi yüklendi
3. İlk kontrol çalıştırıldı
4. Sonuçlar veritabanına kaydedildi
5. Dashboard'da görüntülendi
6. Zamanlayıcı ertesi gün 02:00'de çalışmaya hazır
```

## Üretim Ortamı (Production)

Production'da kullanmak için:

1. **HTTPS Etkinleştir**
   ```python
   # app.py
   app.run(ssl_context='adhoc')  # veya certfile/keyfile
   ```

2. **Debug Kapat**
   ```python
   FLASK_DEBUG = False
   ```

3. **Güvenli Server Kullan**
   ```bash
   pip install gunicorn
   gunicorn -w 4 -b 0.0.0.0:5000 app:app
   ```

4. **Reverse Proxy Ekle** (Nginx/Apache)

## Performans

- 10 domain: ~3-5 saniye
- 50 domain: ~15-25 saniye
- 100 domain: ~30-60 saniye

## Yardım

Daha fazla bilgi için:
- `README.md` - Tam dokumentasyon
- `backend/` - Python dosyaları
- `frontend/` - Web arayüzü

---

**Başlama Tarihi:** 13 Mayıs 2026
