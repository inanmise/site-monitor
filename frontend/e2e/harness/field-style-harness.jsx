import { createRoot } from 'react-dom/client'
import '../../src/styles/globals.css'   // uygulamayla aynı kaskat: shadcn jetonları + Tailwind
import '../../src/App.css'
// 2026-09-27: `.threshold-field` alan kuralları silindi (tüm formlar shadcn) → kapı artık uygulamanın GERÇEKTE
// kullandığı shadcn alanlarını ölçer. Amaç aynı: hiçbir alan tarayıcı varsayılanıyla çizilmez.
import { Input } from '../../src/components/shadcn/input.jsx'
import { Textarea } from '../../src/components/shadcn/textarea.jsx'
import { NativeSelect, NativeSelectOption } from '../../src/components/shadcn/native-select.jsx'

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
      <div className="flex flex-col gap-1.5" data-testid="tf">
        <label htmlFor="tf-input">Çözüm Notu</label>
        <Input id="tf-input" data-testid="tf-input" defaultValue="metin" />
        <NativeSelect data-testid="tf-select" defaultValue="a" aria-label="Seçim">
          <NativeSelectOption value="a">A</NativeSelectOption>
        </NativeSelect>
        <Textarea data-testid="tf-textarea" rows={3} defaultValue="not" aria-label="Not" />
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
