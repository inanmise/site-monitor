import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Rapor düzenleyicisi kısayolları (A4, 2026-11-20): Ctrl/⌘+S Kaydet ile AYNI yol ve engeller (değişiklik yok / salt okunur /
// pencere açık / IME bileşimi → hiçbir şey); Alt+↓ / Alt+↑ bölüm başlıkları arasında odak taşır (bölüm açmaz), yazı alanında
// çalışmaz; düzenleyici kuruluyken `?` listesinin bağlamı etkin.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { TE_EDITOR_CONTEXT, activeShortcutContexts } from '../utils/shortcutContexts.js'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const toggles = () => [...document.querySelectorAll('[data-slot="te-section-toggle"]')]
const openStates = () => [...document.querySelectorAll('[data-section][data-open]')].map((c) => c.getAttribute('data-open'))
const saved = (fields) => ({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-02T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values: { fields, issues: {}, texts: {} } } })

async function openEditor(resp = reportResponse()) {
  te().report.mockResolvedValue(resp)
  const utils = render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
  return utils
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().saveReport.mockImplementation(async (_id, values) => saved(values.fields))
})
afterEach(() => { document.querySelectorAll('[data-test-overlay]').forEach((n) => n.remove()) })

describe('Ctrl/⌘+S', () => {
  it('değişiklik varsa kaydeder ve tarayıcının kaydet penceresini engeller; değişiklik yoksa istek yok', async () => {
    await openEditor()
    const input = screen.getByLabelText('Toplam işlem adedi')
    let notPrevented = fireEvent.keyDown(document.body, { key: 's', ctrlKey: true })
    expect(notPrevented).toBe(false)                                    // tarayıcının penceresi açılmaz
    expect(te().saveReport).not.toHaveBeenCalled()                     // kaydedilecek değişiklik yok
    fireEvent.change(input, { target: { value: '7' } })
    notPrevented = fireEvent.keyDown(input, { key: 's', ctrlKey: true }) // yazı alanındayken de çalışır
    expect(notPrevented).toBe(false)
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].fields['total-tx']).toBe(7)
    expect(await screen.findByText('Rapor kaydedildi.')).toBeInTheDocument()
  })

  it('⌘+S (Mac) aynı; Shift / Alt ile, IME bileşiminde ve açık pencere varken çalışmaz', async () => {
    await openEditor()
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '9' } })
    fireEvent.keyDown(document.body, { key: 's', ctrlKey: true, shiftKey: true })
    fireEvent.keyDown(document.body, { key: 's', ctrlKey: true, altKey: true })
    fireEvent.keyDown(document.body, { key: 's', ctrlKey: true, isComposing: true })
    const overlay = document.createElement('div')
    overlay.setAttribute('role', 'dialog')
    overlay.setAttribute('data-test-overlay', '')
    document.body.appendChild(overlay)
    fireEvent.keyDown(document.body, { key: 's', metaKey: true })
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).not.toHaveBeenCalled()
    overlay.remove()
    fireEvent.keyDown(document.body, { key: 'S', metaKey: true })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
  })

  it('yarım yazılmış sayıyla kaydetmez (inceleme 2026-11-20): alan bırakılır, hata görünür, istek yok, odak alana döner', async () => {
    await openEditor()
    fireEvent.change(screen.getByLabelText('Teknik hata oranı'), { target: { value: '5' } })   // kaydedilecek değişiklik var
    const input = screen.getByLabelText('Toplam işlem adedi')
    input.focus()
    fireEvent.change(input, { target: { value: '1.250.0' } })
    const notPrevented = fireEvent.keyDown(input, { key: 's', ctrlKey: true })
    expect(notPrevented).toBe(false)
    await waitFor(() => expect(input).toHaveAttribute('aria-invalid', 'true'))
    expect(await screen.findByText('Bazı alanlarda geçersiz değer var; işaretli alanları düzeltin.')).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 30))
    expect(te().saveReport).not.toHaveBeenCalled()
    expect(input).toHaveFocus()
    // Düzeltilince aynı kısayol kaydeder
    fireEvent.change(input, { target: { value: '1.250' } })
    fireEvent.keyDown(input, { key: 's', ctrlKey: true })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].fields['total-tx']).toBe(1250)
  })

  it('değişiklik yokken yalnız yarım yazım: Ctrl+S kaydetmez ama söyler', async () => {
    await openEditor()
    const input = screen.getByLabelText('Toplam işlem adedi')
    input.focus()
    fireEvent.change(input, { target: { value: '-' } })
    fireEvent.keyDown(input, { key: 's', ctrlKey: true })
    expect(await screen.findByText('Bazı alanlarda geçersiz değer var; işaretli alanları düzeltin.')).toBeInTheDocument()
    expect(te().saveReport).not.toHaveBeenCalled()
  })

  it('Latin olmayan düzende fiziksel S (e.code KeyS) de kaydeder; Latin harf veren düzende başka harf (Dvorak Ctrl+O) kaydetmez', async () => {
    await openEditor()
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '7' } })
    fireEvent.keyDown(document.body, { key: 'o', code: 'KeyS', ctrlKey: true })
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).not.toHaveBeenCalled()
    const notPrevented = fireEvent.keyDown(document.body, { key: 'ы', code: 'KeyS', ctrlKey: true })
    expect(notPrevented).toBe(false)
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
  })

  it('salt okunur raporda hiçbir şey yapmaz (tarayıcı davranışı engellenmez)', async () => {
    await openEditor(reportResponse({ can_edit: false }))
    const notPrevented = fireEvent.keyDown(document.body, { key: 's', ctrlKey: true })
    expect(notPrevented).toBe(true)
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).not.toHaveBeenCalled()
  })
})

describe('Alt+↓ / Alt+↑ bölüm gezintisi', () => {
  it('başlık düğmeleri arasında odak taşır, hiçbir bölümü açıp kapamaz; uçta yerinde kalır', async () => {
    await openEditor()
    const list = toggles()
    expect(list.length).toBeGreaterThan(2)
    const before = openStates()
    list[0].focus()
    expect(fireEvent.keyDown(list[0], { key: 'ArrowDown', altKey: true })).toBe(false)
    expect(document.activeElement).toBe(list[1])
    fireEvent.keyDown(list[1], { key: 'ArrowDown', altKey: true })
    expect(document.activeElement).toBe(list[2])
    fireEvent.keyDown(list[2], { key: 'ArrowUp', altKey: true })
    expect(document.activeElement).toBe(list[1])
    expect(openStates()).toEqual(before)
    list[0].focus()
    expect(fireEvent.keyDown(list[0], { key: 'ArrowUp', altKey: true })).toBe(true)   // önceki yok: engellenmez
    expect(document.activeElement).toBe(list[0])
  })

  it('yazı alanında çalışmaz (imleç hareketi kullanıcınındır)', async () => {
    await openEditor()
    const input = screen.getByLabelText('Toplam işlem adedi')
    input.focus()
    expect(fireEvent.keyDown(input, { key: 'ArrowDown', altKey: true })).toBe(true)
    expect(document.activeElement).toBe(input)
  })

  it('düz ok tuşu ve Ctrl+Alt+ok kısayol değildir', async () => {
    await openEditor()
    const list = toggles()
    list[0].focus()
    fireEvent.keyDown(list[0], { key: 'ArrowDown' })
    fireEvent.keyDown(list[0], { key: 'ArrowDown', altKey: true, ctrlKey: true })
    expect(document.activeElement).toBe(list[0])
  })
})

describe('kısayol bağlamı', () => {
  it('düzenleyici kuruluyken etkin, sökülünce kalkar', async () => {
    const { unmount } = await openEditor()
    expect(activeShortcutContexts().has(TE_EDITOR_CONTEXT)).toBe(true)
    unmount()
    expect(activeShortcutContexts().has(TE_EDITOR_CONTEXT)).toBe(false)
  })
})
