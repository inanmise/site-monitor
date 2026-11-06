import '@testing-library/jest-dom'

// jsdom does not implement matchMedia — stub it so ThemeProvider can initialise
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})

// jsdom'da pano API'si yok — copyText/CopyableRef/CopyLinkButton'ın ilk kademesi.
// writable+configurable şart: bazı jsdom sürümlerinde navigator.clipboard salt-okunur bir
// getter'dır ve düz atama sessizce düşer (matchMedia'da olduğu gibi defineProperty kullanılıyor).
Object.defineProperty(navigator, 'clipboard', {
  writable: true,
  configurable: true,
  value: { writeText: () => Promise.resolve() },
})

// jsdom execCommand'i implemente etmez ("not implemented" hatası basar); kopyalamanın
// 2. kademesi deterministik olsun diye stub'lanıyor. Kademeyi test eden dosya bunu ezer.
Object.defineProperty(document, 'execCommand', {
  writable: true,
  configurable: true,
  value: () => false,
})

// Odak yönetimi olan bileşenler (ModalShell) tarayıcıda scrollIntoView tetikleyebilir.
if (!HTMLElement.prototype.scrollIntoView) HTMLElement.prototype.scrollIntoView = () => {}

// jsdom ResizeObserver'ı implemente etmez; uzun formlarda "aşağı kaydır" ipucunu süren
// InventoryFormModal gibi bileşenler mount'ta ReferenceError'a düşerdi. jsdom yerleşim
// hesaplamadığı için gözlemcinin gerçekten çalışması beklenmiyor — no-op yeterli.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
}

// Sözlüklerin İKİSİ DE ayrı (lazy) chunk (İngilizce 2026-10-02, öneri 22; Türkçe 2026-11-06, performans). Testler —
// tıpkı sözlükleri bir kez inmiş çalışan uygulama gibi — iki dili de eşzamanlı hazır bulur: aksi halde her bileşen testi
// önce açılış ekranını görürdü. Burada statik import serbest (kurulum üretim paketine girmez). Yüklenmemiş-sözlük yolu
// (açılış ekranı, çalışırken geçiş, indirme hatası, öbür dile düşme, sözlüksüz hata ekranı) i18n-lazy.test.jsx'te ayrıca
// sınanır. Kayıt globalThis'te (i18n/index.jsx DICTS): vi.resetModules() sonrası yeniden değerlendirilen i18n modülü de
// yüklenmiş sözlükleri görür.
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
const i18nDicts = (globalThis.__smI18nDicts ??= {})
i18nDicts.tr = TR
i18nDicts.en = EN

// Testler arası URL izolasyonu: URL-sync'li sayfalar (useUrlQuerySync) paramları adres çubuğuna yazar;
// bir dosyanın bıraktığı ?page=/&stat= sonraki dosyanın mount'unu etkilemesin.
import { afterEach } from 'vitest'
afterEach(() => {
  try { window.history.replaceState({}, '', '/') } catch { /* jsdom */ }
})

// Silme işaretleri (utils/recentlyDeleted, 2026-10-07) modül düzeyinde yaşar: bir testin sildiği izleme/sertifika
// sonraki testte AYNI kimlikle çizilen kartı gizlemesin.
import { __resetDeletedMarks } from '../utils/recentlyDeleted.js'
afterEach(() => { __resetDeletedMarks() })
