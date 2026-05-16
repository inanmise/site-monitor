# SSL/TLS Sertifika İzleme Sistemi Projesi

## Proje Açıklaması

Bu proje, kurumunuzun SSL/TLS sertifikalarını otomatik olarak izleyen, günlük kontrol yapan ve 30 gün kala uyarı veren kapsamlı bir sistemdir.

**Ana Bileşenler:**
- Python backend: SSL/TLS sertifika kontrol motoru
- Flask web sunucusu: REST API ve web arayüzü
- SQLite veritabanı: Tüm kontrol geçmişi
- APScheduler: Günlük otomatik kontrol
- Modern Web Dashboard: Grafiksel arayüz

## Proje Yapısı

```
cert-monitor/
├── backend/              # Python backend
│   ├── app.py           # Ana Flask uygulaması
│   ├── certificate_checker.py
│   ├── database.py
│   ├── scheduler.py
│   └── requirements.txt
├── frontend/            # Web dashboard
│   ├── index.html
│   ├── style.css
│   └── script.js
├── data/                # Veritabanı dizini
└── sertifikaListesi.txt # Kontrol edilecek domain'ler
```

## Teknoloji Stack

- **Backend:** Python 3.8+, Flask
- **Veritabanı:** SQLite
- **Zamanlama:** APScheduler
- **Frontend:** HTML5, CSS3, Vanilla JavaScript
- **API:** RESTful

## Özellikler

1. **Otomatik Günlük Kontrol** - Belirtilen saatte (varsayılan 02:00)
2. **30 Gün Uyarısı** - Sertifikalar bitecekken otomatik uyarı
3. **Web Dashboard** - Modern ve responsive arayüz
4. **REST API** - Harici sistemlerle entegrasyon
5. **Veritabanı** - Tüm kontrol geçmişi kaydı
6. **İstatistikler** - Özet bilgi ve analytics

## Kurulum Adımları

1. Backend bağımlılıklarını yükle: `pip install -r backend/requirements.txt`
2. `sertifikaListesi.txt`'e domain'leri ekle
3. `backend/app.py` başlat: `python app.py`
4. Web tarayıcıda http://localhost:5000 aç

## Ana API Endpoints

- GET `/api/certificates` - Tüm sertifikalar
- GET `/api/warnings` - Uyarılı sertifikalar
- GET `/api/history/<domain>` - Domain geçmişi
- GET `/api/check/<domain>` - Belirli domain'i kontrol et
- POST `/api/scheduler/run` - Hemen kontrol et
- GET `/api/stats` - İstatistikler

## Yapılandırma

- Kontrol saati: `backend/app.py` 'de `scheduler.start(hour=2, minute=0)`
- Timeout: `backend/certificate_checker.py` 'de `CertificateChecker(timeout=10)`
- Domain listesi: `sertifikaListesi.txt`

## Kullanım

1. Dashboard'da tüm sertifikaları görüntüle
2. Uyarılar sekmesinde kritik sertifikaları kontrol et
3. İsteğe bağlı olarak "Şimdi Kontrol Et" ile hemen çalıştır
4. Sertifika detaylarını tıklayarak görüntüle

## Veritabanı

SQLite veritabanı (`data/certificates.db`) şu tabloları içerir:
- `certificate_checks` - Tüm kontrol geçmişi
- `latest_checks` - En son sonuçlar

## Günlükleme

Tüm işlemler backend'de kaydedilir (stdout ve log dosyaları).

## Sorun Giderme

- Port 5000 kullanımda: `app.py`'de port değiştir
- Domain liste boş: `sertifikaListesi.txt`'e domain ekle
- Kontrol çalışmıyor: Ağ bağlantısını ve firewall kurallarını kontrol et

## Güvenlik

- Local network'te kullanılmalı
- Production'da HTTPS gerekli
- API endpoint'leri authentication ile korunmalı

## Performans

- 100 domain kontrol: ~30-60 saniye
- Veritabanı: Yüzlerce domain'in geçmişi saklar
- Sunucu: Çok sayıda eşzamanlı bağlantı destekler

## Geliştirme

- Email uyarıları eklenebilir
- Prometheus metrikleri entegre edilebilir
- Docker containerization yapılabilir
- Multithreading ile hız artırılabilir

## Bakım

- Veritabanı düzenli olarak yedeklenmelidir
- Eski veritabanı kayıtları periyodik olarak temizlenebilir
- Log dosyaları kontrol edilmelidir

## Lisans

Dahili kullanım için tasarlanmıştır.

---

**Başlama Tarihi:** 13 Mayıs 2026
