@echo off
REM SSL/TLS Sertifika İzleme Sistemi - Windows Başlatıcı

echo.
echo ========================================
echo SSL/TLS Sertifika İzleme Sistemi
echo ========================================
echo.

REM Python kontrolü
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python yüklü değil!
    echo Lütfen Python 3.8+ yükleyin
    pause
    exit /b 1
)

echo [✓] Python bulundu

REM Backend dizinine git
cd /d "%~dp0backend"

REM Bağımlılıkları kontrol et
echo.
echo Bağımlılıklar kontrol ediliyor...
pip list | findstr Flask >nul 2>&1
if errorlevel 1 (
    echo Bağımlılıklar yükleniyor...
    pip install -r requirements.txt
    if errorlevel 1 (
        echo ERROR: Bağımlılıklar yüklenemedi!
        pause
        exit /b 1
    )
    echo [✓] Bağımlılıklar yüklendi
) else (
    echo [✓] Bağımlılıklar zaten yüklü
)

REM Uygulamayı başlat
echo.
echo ========================================
echo Uygulama başlatılıyor...
echo ========================================
echo.
echo Web Dashboard: http://localhost:5000
echo API Base: http://localhost:5000/api
echo.
echo Durmak için CTRL+C tuşlarını kullanın
echo.

python app.py

pause
