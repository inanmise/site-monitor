#!/bin/bash
# SSL/TLS Sertifika İzleme Sistemi - Linux/Mac Başlatıcı

echo ""
echo "========================================"
echo "SSL/TLS Sertifika İzleme Sistemi"
echo "========================================"
echo ""

# Python kontrolü
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python 3 yüklü değil!"
    echo "Lütfen Python 3.8+ yükleyin"
    exit 1
fi

echo "[✓] Python bulundu: $(python3 --version)"

# Backend dizinine git
cd "$(dirname "$0")/backend"

# Sanal ortam kontrolü
if [ ! -d "venv" ]; then
    echo ""
    echo "Sanal ortam oluşturuluyor..."
    python3 -m venv venv
fi

# Sanal ortamı aktif et
source venv/bin/activate

# Bağımlılıkları kontrol et
echo ""
echo "Bağımlılıklar kontrol ediliyor..."
pip list | grep Flask > /dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "Bağımlılıklar yükleniyor..."
    pip install -r requirements.txt
    if [ $? -ne 0 ]; then
        echo "ERROR: Bağımlılıklar yüklenemedi!"
        exit 1
    fi
    echo "[✓] Bağımlılıklar yüklendi"
else
    echo "[✓] Bağımlılıklar zaten yüklü"
fi

# Uygulamayı başlat
echo ""
echo "========================================"
echo "Uygulama başlatılıyor..."
echo "========================================"
echo ""
echo "Web Dashboard: http://localhost:5000"
echo "API Base: http://localhost:5000/api"
echo ""
echo "Durmak için CTRL+C tuşlarını kullanın"
echo ""

python app.py
