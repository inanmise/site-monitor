import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ThemeProvider, useTheme } from '../i18n/theme.jsx'
import { LangProvider, useT } from '../i18n/index.jsx'

/**
 * BEYAZ EKRAN BEKÇİSİ.
 *
 * ThemeProvider ve LangProvider, main.jsx'te ErrorBoundary'nin ÜSTÜNDE duruyor: burada fırlayan
 * bir hata sınır tarafından yakalanamaz ve uygulama hata mesajı bile veremeden tamamen beyaz kalır.
 * Depolama kurumsal tarayıcı politikası, gizli mod veya dolu kota nedeniyle erişilemez olabilir —
 * o durumda uygulamanın yine de açılması gerekir (tercihler yalnız o oturumda geçerli olur).
 */
function throwingStorage() {
  return {
    getItem() { throw new DOMException('The operation is insecure.', 'SecurityError') },
    setItem() { throw new DOMException('The operation is insecure.', 'SecurityError') },
    removeItem() { throw new DOMException('The operation is insecure.', 'SecurityError') },
    clear() {},
    key() { return null },
    length: 0,
  }
}

function Probe() {
  const { theme } = useTheme()
  const t = useT()
  return <div data-testid="probe" data-theme={theme}>{t('app.checkNow')}</div>
}

describe('localStorage erişilemezken uygulama yine açılır', () => {
  let original

  beforeEach(() => {
    original = Object.getOwnPropertyDescriptor(window, 'localStorage')
    Object.defineProperty(window, 'localStorage', {
      configurable: true, writable: true, value: throwingStorage(),
    })
  })

  afterEach(() => {
    if (original) Object.defineProperty(window, 'localStorage', original)
  })

  it('ThemeProvider fırlatmaz, varsayılan temayla render eder', () => {
    render(<ThemeProvider><LangProvider><Probe /></LangProvider></ThemeProvider>)
    const probe = screen.getByTestId('probe')
    expect(probe).toBeInTheDocument()
    expect(['light', 'dark']).toContain(probe.dataset.theme)
  })

  it('LangProvider fırlatmaz, çeviri fonksiyonu çalışır', () => {
    render(<ThemeProvider><LangProvider><Probe /></LangProvider></ThemeProvider>)
    // Anahtar değil, gerçek metin basılmalı (sözlük yüklenmiş demektir).
    expect(screen.getByTestId('probe').textContent).not.toBe('app.checkNow')
    expect(screen.getByTestId('probe').textContent.length).toBeGreaterThan(0)
  })

  it('tema değiştirme depolama fırlatsa da state\'i günceller', () => {
    function Toggler() {
      const { theme, toggle } = useTheme()
      return <button onClick={toggle} data-testid="tgl">{theme}</button>
    }
    render(<ThemeProvider><Toggler /></ThemeProvider>)
    const btn = screen.getByTestId('tgl')
    const before = btn.textContent
    fireEvent.click(btn)   // fireEvent act() ile sarar; düz .click() state'i flush etmez
    expect(btn.textContent).not.toBe(before)   // setItem fırladı ama tema değişti
  })
})
