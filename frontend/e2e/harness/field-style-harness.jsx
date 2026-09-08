import { createRoot } from 'react-dom/client'
import '../../src/App.css'

/**
 * Paylaşılan form-alanı kutularının GÖRÜNÜMÜ — izole harness (bkz. field-style.html).
 *
 * Proje kuralı: hiçbir alan tarayıcı varsayılanıyla çizilmez. Kural İHLALİ görünüşte kalmıyor;
 * kullanıcı "bu ekran bozuk / yarım kalmış" diye okuyor ve ürüne güveni düşüyor.
 *
 * Burada gerçek uygulama mount edilmiyor: sorulan şey veri değil KASKAD. Sayfa, uygulamada
 * kullanılan alan kapsayıcılarını gerçek sınıf adlarıyla kurar; test her alanın tarayıcı
 * varsayılanından farklı olduğunu ÖLÇER.
 */
function App() {
  return (
    <div style={{ maxWidth: 640 }}>
      {/* Sorun Bildirimleri'ndeki "Çözüm Notu" ile AYNI yapı. */}
      <div className="threshold-field" data-testid="tf">
        <label>Çözüm Notu</label>
        <input data-testid="tf-input" defaultValue="metin" />
        <select data-testid="tf-select" defaultValue="a">
          <option value="a">A</option>
        </select>
        <textarea data-testid="tf-textarea" rows={3} defaultValue="not" />
      </div>

      {/* Karşılaştırma tabanı: HİÇBİR sınıfı olmayan alanlar = tarayıcı varsayılanı. */}
      <div data-testid="bare">
        <input data-testid="bare-input" defaultValue="metin" />
        <textarea data-testid="bare-textarea" rows={3} defaultValue="not" />
      </div>
    </div>
  )
}

createRoot(document.getElementById('host')).render(<App />)
