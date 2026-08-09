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

// Testler arası URL izolasyonu: URL-sync'li sayfalar (useUrlQuerySync) paramları adres çubuğuna yazar;
// bir dosyanın bıraktığı ?page=/&stat= sonraki dosyanın mount'unu etkilemesin.
import { afterEach } from 'vitest'
afterEach(() => {
  try { window.history.replaceState({}, '', '/') } catch { /* jsdom */ }
})
