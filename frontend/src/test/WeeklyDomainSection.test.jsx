import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Madde 4 — "Domain Bazlı Kritik İşlerin Durumu" (2026-09-27 yeniden tasarım): alan tablosu (geniş) / kartlar (dar),
 * satır içi ad, Enter ile yeni satır, ekle / kaldır (onaylı) / taşı / çoğalt, boş durum, doğrulama; okuma görünümü özet +
 * süzgeç. Veri biçimi aynı: channels [{ id, name, notes_md }].
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
vi.mock('@uiw/react-md-editor', () => ({ default: ({ value }) => <textarea readOnly value={value ?? ''} />, commands: { bold: {}, italic: {}, group: () => ({}) } }))
vi.mock('react-markdown', () => ({ default: ({ children }) => <div data-testid="md">{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }), ToastProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ formatDate: (s) => String(s ?? ''), api: withApiFallback({}) }))
import { DomainWorkEditor, DomainWorkView } from '../components/weekly/WeeklyDomainSection.jsx'

const CH = [
  { id: 'a', name: 'Web', notes_md: '- Yeni giriş akışı açıldı' },
  { id: 'b', name: 'Mobil', notes_md: '' },
  { id: 'c', name: 'Çağrı Merkezi', notes_md: '**IVR** sadeleşti', extra: 1 },
]
let last = null
function Harness({ initial = CH, templateNames = [] }) {
  const [v, setV] = useState(initial)
  last = v
  return <LangProvider><DomainWorkEditor channels={v} onChange={(n) => { last = n; setV(n) }} reportId={1} templateNames={templateNames} /></LangProvider>
}
const names = () => last.map((c) => c.name)
const nameBox = (n) => screen.getByRole('textbox', { name: new RegExp(`(Domain adı — ${n}\\. satır|Domain name — row ${n})$`) })

describe('DomainWorkEditor — geniş kap (tablo)', () => {
  beforeEach(() => { confirmMock.mockClear(); confirmMock.mockResolvedValue(true) })

  it('tablo: satır başına ad alanı + güncelleme özeti; özet satırı; ad düzenlenir, ek anahtarlar korunur', () => {
    render(<Harness />)
    const table = document.querySelector('[data-slot="wr-domain-table"]')
    expect(table).not.toBeNull()
    expect(table.querySelectorAll('[data-slot="wr-domain-row"]').length).toBe(3)
    expect(table.querySelectorAll('[data-slot="wr-domain-row"][data-filled]').length).toBe(2)
    expect(document.querySelector('[data-slot="wr-domain-summary"]').textContent).toMatch(/3.*2.*1/)
    fireEvent.change(nameBox(3), { target: { value: 'IVR' } })
    expect(names()).toEqual(['Web', 'Mobil', 'IVR'])
    expect(last[2].extra).toBe(1)
    expect(last[2].notes_md).toBe('**IVR** sadeleşti')
  })

  it('Enter yeni alanı ALTINA ekler ve odağa alır; "Domain Ekle" sona ekler', async () => {
    render(<Harness />)
    fireEvent.keyDown(nameBox(1), { key: 'Enter' })
    expect(last).toHaveLength(4)
    expect(names()[0]).toBe('Web')
    expect(names()[1]).toMatch(/Yeni Domain|New Domain/)
    await waitFor(() => expect(nameBox(2)).toHaveFocus())
    fireEvent.click(screen.getByRole('button', { name: /^(Domain Ekle|Add Domain)$/ }))
    expect(last).toHaveLength(5)
    expect(names()[4]).toMatch(/Yeni Domain|New Domain/)
  })

  it('kaldır ONAY ister (iptal → değişmez); taşı ve çoğalt KebabMenu / düğmelerle', async () => {
    render(<Harness />)
    confirmMock.mockResolvedValueOnce(false)
    pressMenuTrigger(screen.getByRole('button', { name: /Mobil — (İşlemler|Actions)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Domaini Sil|Delete Domain/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(names()).toEqual(['Web', 'Mobil', 'Çağrı Merkezi'])
    pressMenuTrigger(screen.getByRole('button', { name: /Mobil — (İşlemler|Actions)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Domaini Sil|Delete Domain/ }))
    await waitFor(() => expect(names()).toEqual(['Web', 'Çağrı Merkezi']))
    fireEvent.click(screen.getByRole('button', { name: /(Aşağı taşı|Move down) — Web/ }))
    expect(names()).toEqual(['Çağrı Merkezi', 'Web'])
    expect(screen.getByRole('button', { name: /(Yukarı taşı|Move up) — Çağrı Merkezi/ })).toBeDisabled()
    pressMenuTrigger(screen.getByRole('button', { name: /Web — (İşlemler|Actions)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Çoğalt|Duplicate/ }))
    await waitFor(() => expect(names()).toEqual(['Çağrı Merkezi', 'Web', expect.stringMatching(/Web \((kopya|copy)\)/)]))
    expect(last[2].notes_md).toBe('- Yeni giriş akışı açıldı')
    expect(last[2].id).not.toBe(last[1].id)
  })

  it('doğrulama: boş ad ve yinelenen ad işaretlenir (aria-invalid + görünür ileti)', () => {
    render(<Harness initial={[{ id: 'a', name: 'Web', notes_md: '' }, { id: 'b', name: 'web', notes_md: '' }, { id: 'c', name: ' ', notes_md: '' }]} />)
    expect(nameBox(1)).not.toHaveAttribute('aria-invalid')
    expect(nameBox(2)).toHaveAttribute('aria-invalid', 'true')
    expect(nameBox(3)).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/başka bir satırda da var|already used in another row/)).toBeInTheDocument()
    expect(screen.getByText(/Domain adı gerekli|domain name is required/)).toBeInTheDocument()
  })

  it('boş durum: "İlk domaini ekle" + şablondan tamamla', () => {
    render(<Harness initial={[]} templateNames={['Web', 'Mobil']} />)
    expect(screen.getByText(/Henüz domain yok|No domains yet/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /İlk domaini ekle|Add the first domain/ }))
    expect(last).toHaveLength(1)
  })
})

describe('DomainWorkEditor / View — dar kap (kartlar, 390 px)', () => {
  let w
  beforeEach(() => { w = window.innerWidth; window.innerWidth = 390; confirmMock.mockResolvedValue(true) })
  afterEach(() => { window.innerWidth = w })

  it('düzenleyici kartlarda: tablo yok, her kartta ad + KebabMenu; kart menüsünden kaldır; alta "Domain Ekle"', async () => {
    render(<Harness />)
    expect(document.querySelector('[data-slot="wr-domain-table"]')).toBeNull()
    const cards = document.querySelectorAll('[data-slot="wr-domain-card"]')
    expect(cards.length).toBe(3)
    expect(within(cards[1]).getByRole('button', { name: /Mobil — (İşlemler|Actions)$/ })).toBeInTheDocument()
    pressMenuTrigger(within(cards[1]).getByRole('button', { name: /Mobil — (İşlemler|Actions)$/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Domaini Sil|Delete Domain/ }))
    await waitFor(() => expect(names()).toEqual(['Web', 'Çağrı Merkezi']))
    expect(screen.getAllByRole('button', { name: /^(Domain Ekle|Add Domain)$/ }).length).toBeGreaterThan(0)
  })

  it('okuma görünümü kartlarda: ad, güncellendi rozeti, markdown gövdesi; giriş alanı yok', () => {
    render(<LangProvider><DomainWorkView channels={CH} /></LangProvider>)
    const cards = document.querySelectorAll('[data-slot="wr-domain-card"]')
    expect(cards.length).toBe(3)
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(within(cards[1]).getByText(/Bu hafta güncelleme yok|No update this week/)).toBeInTheDocument()
  })
})

describe('DomainWorkView — okuma / onay görünümü (geniş)', () => {
  const MANY = Array.from({ length: 6 }, (_, i) => ({ id: `k${i}`, name: `Alan ${i + 1}`, notes_md: i % 3 === 0 ? '' : `not ${i}` }))

  it('özet satırı; >4 alanda süzgeç çipleri: "Güncellemesiz" yalnız notsuz alanları bırakır', () => {
    render(<LangProvider><DomainWorkView channels={MANY} /></LangProvider>)
    expect(document.querySelector('[data-slot="wr-domain-summary"]').textContent).toMatch(/6.*4.*2/)
    expect(document.querySelectorAll('[data-slot="wr-domain-row"]').length).toBe(6)
    const filter = document.querySelector('[data-slot="wr-domain-filter"]')
    fireEvent.click(within(filter).getByText(/Güncellemesiz \(2\)|No update \(2\)/))
    expect(document.querySelectorAll('[data-slot="wr-domain-row"]').length).toBe(2)
    expect([...document.querySelectorAll('[data-slot="wr-domain-row"]')].every((r) => !r.hasAttribute('data-filled'))).toBe(true)
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('az alanda süzgeç yok; alan yoksa açıklayıcı metin', () => {
    const { unmount } = render(<LangProvider><DomainWorkView channels={CH} /></LangProvider>)
    expect(document.querySelector('[data-slot="wr-domain-filter"]')).toBeNull()
    unmount()
    render(<LangProvider><DomainWorkView channels={[]} /></LangProvider>)
    expect(screen.getByText(/Bu raporda domain yok|This report has no domains/)).toBeInTheDocument()
  })
})
