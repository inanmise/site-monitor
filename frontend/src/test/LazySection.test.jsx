import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { lazy, useState } from 'react'
import { render, screen, fireEvent, act } from './test-utils.jsx'
import LazySection from '../components/ui/LazySection.jsx'

/**
 * Tembel bölüm kabı (2026-11-06, performans PF4 O3): Ayarlar bölümleri, Yönetim sekmeleri ve Sistem Sağlığı'nın ağır alt
 * parçaları artık ilk açılışta indiriliyor. Parça gelene kadar iskelet; bir bölüm çökerse yalnız o bölüm hata ekranı
 * gösterir ve başka bölüme geçiş hata sınırını sıfırlar (gezinti ve diğer bölümler çalışır).
 */
let resolveA
const SectionA = lazy(() => new Promise((r) => { resolveA = r }))
function Boom() { throw new Error('bölüm patladı') }
function Fine() { return <p data-testid="sec-fine">tamam</p> }

function Shell() {
  const [sec, setSec] = useState('a')
  return (
    <div>
      <button type="button" onClick={() => setSec('boom')}>boom</button>
      <button type="button" onClick={() => setSec('fine')}>fine</button>
      <LazySection sectionKey={sec}>
        {sec === 'a' && <SectionA />}
        {sec === 'boom' && <Boom />}
        {sec === 'fine' && <Fine />}
      </LazySection>
    </div>
  )
}

describe('LazySection', () => {
  beforeEach(() => { vi.spyOn(console, 'error').mockImplementation(() => {}) })
  afterEach(() => { vi.restoreAllMocks() })

  it('parça gelene kadar iskelet (role=status, aria-busy); gelince içerik', async () => {
    render(<Shell />)
    const sk = document.querySelector('[data-slot="lazy-section-loading"]')
    expect(sk).not.toBeNull()
    expect(sk).toHaveAttribute('role', 'status')
    expect(sk).toHaveAttribute('aria-busy', 'true')
    await act(async () => { resolveA({ default: () => <p data-testid="sec-a">A bölümü</p> }) })
    expect(await screen.findByTestId('sec-a')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="lazy-section-loading"]')).toBeNull()
  })

  it('bölüm çökerse yalnız o bölüm hata ekranı; başka bölüme geçiş sınırı sıfırlar', async () => {
    // Çökme bildirimi (fetch) bu testte gitmesin
    globalThis.fetch = vi.fn(() => Promise.resolve({ ok: false, json: () => Promise.resolve(null) }))
    render(<Shell />)
    await act(async () => { resolveA({ default: () => <p data-testid="sec-a">A bölümü</p> }) })
    fireEvent.click(screen.getByRole('button', { name: 'boom' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    // Gezinti düğmeleri hâlâ çalışır (hata sınırı yalnız bölümü sarar)
    fireEvent.click(screen.getByRole('button', { name: 'fine' }))
    expect(await screen.findByTestId('sec-fine')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
