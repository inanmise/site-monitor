import { createRoot } from 'react-dom/client'
import SearchableSelect from '../../src/components/ui/SearchableSelect.jsx'
import '../../src/App.css'

/**
 * "Kişi Ekle" modalının YERLEŞİMİ — izole harness (bkz. contact-modal.html).
 *
 * Bilerek gerçek `EscalationContacts` mount edilmiyor: o bileşen API'ye gidiyor ve testin
 * sorusu veri akışı değil, DÜZEN. Burada modalın yapısı birebir kopyalanıyor —
 * `.modal-overlay > .modal-box > .form-grid.form-grid--top > label` — ve alanlar gerçek
 * `SearchableSelect` ile kuruluyor, çünkü taşmanın olası kaynağı tam olarak o bileşenin
 * daralabilirliği.
 *
 * Uzun metin ÜRETİLMİYOR, üretimde görülen şekliyle veriliyor: kullanıcı seçeneği
 * "Ad Soyad (Uzun Bölüm Adı) (uzun.eposta@example.com)" biçiminde ve ekranı taşıran da buydu.
 * Gerçek kişi/kurum adı KULLANILMAZ (proje kuralı) — aynı UZUNLUKTA yer tutucu kullanılıyor.
 *
 *   ?w=<px>   host genişliği (dar ekran senaryosu)
 */
const q = new URLSearchParams(location.search)

/** Üretimdeki en uzun seçenek kadar uzun, ama tamamen yer tutucu. */
const LONG_USER = 'Ayşe Yılmazoğlu (Teknoloji Servis Yönetimi Bölümü) (ayse.yilmazoglu@example.com)'

function App() {
  return (
    <div className="modal-overlay" style={{ position: 'static' }}>
      <div className="modal-box">
        <h3>Kişi Ekle</h3>
        <div className="form-grid form-grid--top">
          <label data-testid="f-user">
            <span>Kullanıcı <span className="req-star">*</span></span>
            <SearchableSelect
              value="1"
              onChange={() => {}}
              searchThreshold={2}
              options={[{ value: '1', label: LONG_USER }]}
            />
            <span className="field-hint">{LONG_USER}</span>
          </label>

          <label data-testid="f-team">Takım
            <SearchableSelect value="1" onChange={() => {}} searchThreshold={2}
              options={[{ value: '1', label: 'CALL OF DUTY' }]} />
          </label>

          <label data-testid="f-role">Rol
            <SearchableSelect value="TECH" onChange={() => {}}
              options={[{ value: 'TECH', label: 'Teknik Ekip' }]} />
          </label>

          <label data-testid="f-level">Asgari Alarm Seviyesi
            <SearchableSelect value="WARNING" onChange={() => {}}
              options={[{ value: 'WARNING', label: 'UYARI (30+ gün)' }]} />
          </label>

          <label data-testid="f-webhook">Webhook URL (isteğe bağlı)
            <input value="" onChange={() => {}} placeholder="https://..." />
          </label>

          <label data-testid="f-webhook-type">Webhook Türü
            <SearchableSelect value="TEAMS" onChange={() => {}}
              options={[{ value: 'TEAMS', label: 'Microsoft Teams' }]} />
          </label>

          <label className="checkbox-label" data-testid="f-active">
            <input type="checkbox" checked readOnly />
            Aktif
          </label>
        </div>
        <div className="modal-actions">
          <button className="btn btn-secondary">İptal</button>
          <button className="btn btn-primary">Kaydet</button>
        </div>
      </div>
    </div>
  )
}

const host = document.getElementById('host')
if (q.has('w')) host.style.width = q.get('w') + 'px'
createRoot(host).render(<App />)
