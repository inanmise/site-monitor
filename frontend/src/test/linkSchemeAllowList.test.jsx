import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import { safeHref } from '../utils/safeHref.js'
import { guideHref } from '../components/renewal/guideSteps.js'
import { GuideLinkCard } from '../components/renewal/GuideParts.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ getBranding: vi.fn(async () => ({ success: true, data: {} })) }),
}))
import { api } from '../api/client'
import { BrandingProvider } from '../contexts/BrandingProvider.jsx'
import AnnouncementBanner from '../components/AnnouncementBanner.jsx'

/**
 * 2026-09-27 regresyon taraması, FRONTEND B/7 (BD1 kardeşleri) — yönetici girdisi adresler href'e ŞEMA KONTROLSÜZ
 * yazılıyordu: yenileme kılavuzu bağlantı kartı (`normalizeUrl` şemalı her değeri aynen döndürür) ve duyuru şeridi
 * bağlantısı. `javascript:` / `data:` / `vbscript:` artık tıklanabilir DEĞİL, düz metin.
 */
// Test girdileri parçalardan kurulur: kaynakta çıplak tehlikeli şema dizesi durmasın.
const JS = ['java', 'script:alert(1)'].join('')
const DATA = ['da', 'ta:text/html,<b>x</b>'].join('')
const VB = ['vb', 'script:msgbox(1)'].join('')
const SNEAKY = ['java', String.fromCharCode(10), 'script:alert(1)'].join('')   // tarayıcı href'te satır sonunu siler

describe('safeHref — şema beyaz listesi', () => {
  it('http / https / mailto ve göreli adres geçer (kırpılmış ham değer döner)', () => {
    expect(safeHref(' https://wiki.example.com/a ')).toBe('https://wiki.example.com/a')
    expect(safeHref('http://wiki.local')).toBe('http://wiki.local')
    expect(safeHref('mailto:ops@example.com')).toBe('mailto:ops@example.com')
    expect(safeHref('/?tab=weeklyreports')).toBe('/?tab=weeklyreports')
  })

  it('javascript / data / vbscript (büyük-küçük harf ve gizli satır sonu dâhil) → null', () => {
    for (const bad of [JS, JS.toUpperCase(), DATA, VB, SNEAKY, '  ' + JS]) expect(safeHref(bad)).toBeNull()
    expect(safeHref('')).toBeNull()
    expect(safeHref(null)).toBeNull()
  })
})

describe('Yenileme kılavuzu bağlantı kartı', () => {
  const t = (k) => k
  const card = (url) => render(<GuideLinkCard t={t} link={{ id: 1, title: 'Kaynak', url }} isAdmin={false} />)

  it('guideHref: web, e-posta ve UNC (file:) bağlantı olur; tehlikeli şema null', () => {
    expect(guideHref('wiki.example.com/x')).toBe('https://wiki.example.com/x')
    expect(guideHref('mailto:pki@example.com')).toBe('mailto:pki@example.com')
    expect(guideHref(String.fromCharCode(92, 92) + 'dosya.example.com' + String.fromCharCode(92) + 'x')).toBe('file://dosya.example.com/x')
    for (const bad of [JS, DATA, VB, SNEAKY]) expect(guideHref(bad)).toBeNull()
  })

  it('javascript: / data: adresli kartta başlık BAĞLANTI DEĞİL, düz metin; adres yine görünür', () => {
    for (const bad of [JS, DATA]) {
      const { unmount } = card(bad)
      expect(screen.queryByRole('link')).toBeNull()
      expect(document.querySelector('[data-slot="guide-link-inert"]')).toHaveTextContent('Kaynak')
      expect(document.querySelector('a[href]')).toBeNull()
      unmount()
    }
  })

  it('https kartı bağlantı olarak kalır (davranış değişmedi)', () => {
    card('https://wiki.example.com/pki')
    expect(screen.getByRole('link', { name: /Kaynak/ })).toHaveAttribute('href', 'https://wiki.example.com/pki')
  })
})

describe('Duyuru şeridi bağlantısı', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })
  const wrap = () => render(<BrandingProvider><AnnouncementBanner /></BrandingProvider>)

  it('javascript: bağlantı href OLMAZ — etiket düz metin', async () => {
    api.getBranding.mockResolvedValueOnce({ success: true, data: {
      banner_enabled: true, banner_text: 'Planlı bakım', banner_link: JS, banner_link_label: 'Ayrıntı', banner_version: 7 } })
    wrap()
    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(screen.queryByRole('link')).toBeNull()
    expect(document.querySelector('[data-slot="announcement-link-inert"]')).toHaveTextContent('Ayrıntı')
  })

  it('https bağlantı çalışmaya devam eder', async () => {
    api.getBranding.mockResolvedValueOnce({ success: true, data: {
      banner_enabled: true, banner_text: 'Planlı bakım', banner_link: 'https://wiki.example.com', banner_link_label: 'Wiki', banner_version: 8 } })
    wrap()
    await waitFor(() => expect(screen.getByRole('link', { name: 'Wiki' })).toHaveAttribute('href', 'https://wiki.example.com'))
  })
})
