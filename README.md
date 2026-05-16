# SSL/TLS Sertifika İzleme Sistemi

Kurumunuzdaki SSL/TLS sertifikalarını merkezi olarak kontrol eden, sonuçları raporlayan ve 30 gün içinde bitecek sertifikaları uyaran tam otomatize sistem.

## Özellikler

✅ **Otomatik Günlük Kontrol** - Belirtilen saatte (varsayılan: 02:00) otomatik kontrol  
✅ **Web Dashboard** - Tüm sertifikaları görüntülemek ve analiz etmek için modern arayüz  
✅ **30 Gün Uyarısı** - Sertifikalar 30 gün kala otomatik uyarı  
✅ **Detaylı Raporlama** - Her sertifika için kapsamlı bilgi ve geçmiş  
✅ **Veritabanı Depolama** - SQLite ile tüm kontrol geçmişi kaydı  
✅ **Hızlı Kontrol** - İsteğe bağlı olarak belirli domain'leri hemen kontrol et  
✅ **REST API** - Harici sistemlerle entegrasyon için tam API desteği

## Proje Yapısı

```
cert-monitor/
├── backend/
│   ├── app.py                 # Flask web sunucusu
│   ├── certificate_checker.py # SSL/TLS kontrol motoru
│   ├── database.py            # Veritabanı yönetimi
│   ├── scheduler.py           # Günlük zamanlayıcı
│   └── requirements.txt        # Python bağımlılıkları
├── frontend/
│   ├── index.html             # Web arayüzü
│   ├── style.css              # Tasarım
│   └── script.js              # İstemci tarafı mantığı
├── data/                      # Veritabanı ve veriler
├── sertifikaListesi.txt       # Kontrol edilecek domain'ler
└── README.md                  # Bu dosya
```

## Kurulum

### Gereksinimler

- Python 3.8+
- pip (Python paket yöneticisi)
- Modern web tarayıcısı

### Adım 1: Bağımlılıkları Yükleyin

```bash
cd backend
pip install -r requirements.txt
```

### Adım 2: Domain Listesini Düzenleyin

`sertifikaListesi.txt` dosyasını açarak kontrol edilecek domain'leri ekleyin:

```
# Örnek format
google.com
github.com:443
api.example.com
web.domain.com:8443
```

**Format:**
- Her satırda bir domain
- Port isteğe bağlı (varsayılan: 443)
- `#` ile başlayan satırlar yorum olarak değerlendirilir

### Adım 3: Uygulamayı Başlatın

```bash
cd backend
python app.py
```

Uygulama başarıyla başladıktan sonra:
- **Web Dashboard:** http://localhost:5000
- **API Base:** http://localhost:5000/api

## Kullanım

### Web Dashboard

Ana sayfada aşağıdaki sekmeleri göreceksiniz:

1. **Dashboard** - En önemli sertifikalar ve özet bilgi
2. **⚠️ Uyarılar** - Dikkat gerektiren sertifikalar
3. **Tüm Sertifikalar** - Tüm kontrollü domain'ler tablosu

### İşlevler

- **🔄 Şimdi Kontrol Et** - Tüm domain'leri hemen kontrol et
- **📊 İstatistikler** - Genel istatistikler ve özet bilgi
- **Arama Kutusu** - Domain, veren, konu ile arama yap
- **Kart Tıklama** - Sertifika detayları ve geçmişini görüntüle

### Bilgiler

Her sertifika için aşağıdaki bilgiler gösterilir:

- **Domain** - Kontrol edilen domain adı
- **Veren (Issuer)** - Sertifikaları veren kurum
- **Konu (Subject)** - Sertifikanın sahibi
- **Başlama Tarihi** - Sertifika başlama tarihi
- **Bitiş Tarihi** - Sertifika bitiş tarihi
- **Kalan Gün** - Sertifikanın kalan geçerlilik süresi
- **Alternative Names (SAN)** - Alternatif domain adları
- **Son Kontrol** - En son kontrol zamanı
- **Durum** - Geçerli/Uyarı/Hata

## API Endpoints

### GET /api/certificates
Tüm sertifikaların son kontrol sonuçlarını al.

**Yanıt:**
```json
{
  "success": true,
  "data": [
    {
      "domain": "google.com",
      "subject": "google.com",
      "issuer_cn": "Google Internet Authority G3",
      "not_before": "2024-01-01T00:00:00",
      "not_after": "2025-01-01T00:00:00",
      "days_remaining": 245,
      "warning": false,
      "status": "valid",
      "checked_at": "2026-05-13T10:30:00"
    }
  ],
  "timestamp": "2026-05-13T10:35:00"
}
```

### GET /api/warnings
Uyarı gerektiren sertifikaları al.

### GET /api/history/<domain>
Belirtilen domain'in kontrol geçmişini al (son 30 kontrol).

### GET /api/check/<domain>
Belirtilen domain'i hemen kontrol et.

### POST /api/scheduler/run
Zamanlayıcıyı hemen çalıştır.

### GET /api/stats
İstatistikleri al.

### GET /api/scheduler/status
Zamanlayıcı durumunu al.

## Yapılandırma

### Günlük Kontrol Saati Değiştirme

`backend/app.py` dosyasında:

```python
# Günlük kontrol saati (varsayılan: 02:00)
scheduler.start(hour=2, minute=0)
```

Örneğin, her gün saat 03:30'da kontrol etmek için:
```python
scheduler.start(hour=3, minute=30)
```

### Timeout Ayarı

`backend/certificate_checker.py` dosyasında:

```python
# Varsayılan: 10 saniye
checker = CertificateChecker(timeout=10)
```

## Uyarı Sistemi

### 30 Gün Uyarısı

Sertifika bitiş tarihine 30 gün kaldığında otomatik olarak uyarı durumu aktif olur. Dashboard'da:

- Uyarılı sertifikalar sarı renkte gösterilir
- **⚠️ Uyarılar** sekmesinde listelenir
- Kalan gün sayısı gösterilir

### Süresi Geçmiş Sertifikalar

Sertifika tarihinden sonra:

- Kırmızı renkte gösterilir
- Kritik olarak işaretlenir
- Derhal güncellenmesi gerekir

### Hata Durumu

Sertifika kontrol edilemezse:

- Hata mesajı gösterilir
- İndis verilir
- Bağlantı sorunları kontrol edilmeli

## Veritabanı

Sistem SQLite veritabanı kullanır (`data/certificates.db`).

**Tablolar:**

1. **certificate_checks** - Tüm kontrol geçmişi
2. **latest_checks** - En son kontrol sonuçları (hızlı erişim)

Veritabanı otomatik olarak oluşturulur. Elle müdahale gerekmez.

## Günlükleme

Tüm işlemler günlüğe kaydedilir:

```
2026-05-13 10:30:00 - INFO - Sertifika kontrolü başlandı
2026-05-13 10:30:01 - INFO - Yüklenen domain sayısı: 5
2026-05-13 10:30:05 - INFO - Kontrol tamamlandı - Başarılı: 5, Uyarı: 1, Hata: 0
```

## Sorun Giderme

### "Dosya bulunamadı: sertifikaListesi.txt"

`backend/app.py` başlatırken:
1. `sertifikaListesi.txt` dosyasının proje kök dizininde olduğundan emin olun
2. Dosya boş ise bile oluşturulması gerekir

### "Port 5000 zaten kullanımda"

Başka bir uygulamanın port 5000'i kullanıyor. Çözümler:
1. Diğer uygulamayı kapatın
2. Veya `app.py`'de port numarasını değiştirin:
```python
app.run(debug=True, host='0.0.0.0', port=5001)
```

### "SSL: CERTIFICATE_VERIFY_FAILED"

Bazı ağlarda sertifika doğrulaması sorun yaratabilir. `certificate_checker.py` dosyasında:

```python
context = ssl.create_default_context()
# Doğrulamayı devre dışı bırakmak için (güvenli değildir):
context.check_hostname = False
context.verify_mode = ssl.CERT_NONE
```

### Dashboard Boş Görünüyor

1. Tarayıcı konsolunda hata olup olmadığını kontrol edin (F12)
2. `sertifikaListesi.txt`'de domain'ler olup olmadığını kontrol edin
3. Backend sunucusunun çalıştığını kontrol edin

## Güvenlik Notları

- Sistem local network'te çalıştırılabilir
- Production ortamında HTTPS kullanın
- API endpoint'leri authentication ile korunmalı
- Veritabanı dosyasını yedekleyin

## Performans

- Yüzlerce domain aynı anda kontrol edilebilir
- Kontrol süresi domain sayısına ve ağ hızına bağlıdır
- Tipik olarak: 100 domain ≈ 30-60 saniye

## Geliştirilecek Özellikler

- [ ] Email/SMS uyarıları
- [ ] Active Directory entegrasyonu
- [ ] Sertifika yenileme önerileri
- [ ] Multithreaded kontrol (paralel)
- [ ] Docker desteği
- [ ] Prometheus metrikleri

## Destek

Sorunlar veya öneriler için proje sahibine başvurun.

## Lisans

Bu proje dahili kullanım için tasarlanmıştır.

---

**Son Güncelleme:** 13 Mayıs 2026
