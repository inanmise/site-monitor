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

// Testler arası URL izolasyonu: URL-sync'li sayfalar (useUrlQuerySync) paramları adres çubuğuna yazar;
// bir dosyanın bıraktığı ?page=/&stat= sonraki dosyanın mount'unu etkilemesin.
import { afterEach } from 'vitest'
afterEach(() => {
  try { window.history.replaceState({}, '', '/') } catch { /* jsdom */ }
})
