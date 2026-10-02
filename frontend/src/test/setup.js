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

// İngilizce sözlük 2026-10-02'den beri ayrı (lazy) bir chunk (performans önerisi 22). Testler — tıpkı
// sözlüğü bir kez inmiş çalışan uygulama gibi — iki dili de eşzamanlı hazır bulur: varsayılan dil 'en'
// olduğundan aksi halde her bileşen testi önce açılış ekranını görürdü. Yüklenmemiş-sözlük yolu (açılış
// ekranı, çalışırken geçiş, indirme hatası) i18n-lazy.test.jsx'te ayrıca sınanır. Kayıt globalThis'te:
// vi.resetModules() sonrası yeniden değerlendirilen i18n modülü de yüklenmiş sözlüğü görür.
import { loadLanguage } from '../i18n/index.jsx'
await loadLanguage('en')

// Testler arası URL izolasyonu: URL-sync'li sayfalar (useUrlQuerySync) paramları adres çubuğuna yazar;
// bir dosyanın bıraktığı ?page=/&stat= sonraki dosyanın mount'unu etkilemesin.
import { afterEach } from 'vitest'
afterEach(() => {
  try { window.history.replaceState({}, '', '/') } catch { /* jsdom */ }
})
