import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, renderHook, act } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Katlanır anomali konuları (2026-10-18, kullanıcı isteği: "Anormal Artış Gösteren Hatalar altında eklenen konu ve başlıklar da
// kendi içinde akordiyon gibi açılıp kapanabilsin"): var olan konular KAPALI başlar, başlık satırı konunun başlığını ve özet
// çiplerini taşır; yeni konu açık gelir ve odak ilk alanına geçer; açık konular bu rapor için hatırlanır (`sm.te.open.ri.<rapor>`,
// bölüm kaydından ayrı); hatası olan konu açılır, kapalıyken rozet; "Tüm konuları aç / kapat" bölümlere dokunmaz; salt okunur da
// açıp kapatır; sayfada bul kapalı konuyu açar; içerik kapalıyken de kurulu kalır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(),
      deleteImage: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { TOPIC_OPEN_OPTS, useSectionOpenState } from '../components/techerror/SectionCollapse.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const KEY = 'sm.te.open.ri.100'
const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu? (zaman, yer, etki)', customers_label: 'Etkilenen müşteri sayısı',
  errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count',
}
const TPL = {
  ...TEMPLATE,
  sections: [
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' },
    ] },
    ISSUES,
  ],
}
const K1 = { id: 'k1', topic: 'Kart Limiti', title: '', title_auto: true, description: '', date: '2026-09-12', start: '14:00',
  end: '15:30', errors: 170, outage: true, outage_id: 'INC-2026-0915', impact: true, customers: 320 }
const K2 = { id: 'k2', topic: 'Fatura Ödeme', title: '', title_auto: true, description: '', errors: 40 }
const respond = (issues, extra = {}) => reportResponse({ template: TPL,
  values: { fields: { 'error-count': 5000 }, issues: { issues }, texts: {} }, ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-18T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })
const cardOf = (iid) => document.querySelector(`[data-slot="te-issue"][data-issue="${iid}"]`)
const toggleOf = (iid) => cardOf(iid).querySelector('[data-slot="te-issue-toggle"]')
const contentOf = (iid) => cardOf(iid).querySelector('[data-slot="te-issue-content"]')
const stored = () => JSON.parse(localStorage.getItem(KEY) || 'null')

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  // Bölümler açık (kullanıcı daha önce açmış), konular KAPALI: akordeonun kendisi sınanıyor
  openAllSections({ topics: false })
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil')))
  te().saveReport.mockImplementation(async (_r, values) => saved(values))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('katlanır anomali konuları', () => {
  it('var olan konular KAPALI başlar: başlıkta başlık + özet çipleri; içerik kurulu ama gizli; düğmenin adı konu başlığı, açıklaması çipler', async () => {
    te().report.mockResolvedValue(respond([K1, K2]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    for (const iid of ['k1', 'k2']) {
      expect(cardOf(iid)).toHaveAttribute('data-open', 'false')
      expect(toggleOf(iid)).toHaveAttribute('aria-expanded', 'false')
      expect(contentOf(iid)).toHaveAttribute('hidden', 'until-found')
    }
    // Kapalı konu hikâyesini söyler: zaman, adet + pay, kesinti + ID, müşteri
    const chips = cardOf('k1').querySelector('[data-slot="te-issue-chips"]')
    expect(within(chips).getByText('12 Eylül 2026 · 14:00–15:30')).toBeInTheDocument()
    expect(within(chips).getByText('170 hata · %3,4')).toBeInTheDocument()
    expect(within(chips).getByText('Kesinti · INC-2026-0915')).toBeInTheDocument()
    expect(within(chips).getByText('320 müşteri')).toBeInTheDocument()
    // WAI akordeon: düğme başlığın (h4) içinde, adı yalnız başlık, çipler açıklamada
    const toggle = screen.getByRole('button', { name: '1. Kart Limiti: Hata Artışı' })
    expect(toggle).toBe(toggleOf('k1'))
    expect(toggle.closest('h4')).not.toBeNull()
    expect(screen.getByRole('heading', { level: 4, name: '1. Kart Limiti: Hata Artışı' })).toBeInTheDocument()
    // Açıklama: düğmenin aria-describedby'ı özet çiplerini (ekran okuyucu için "Konunun özeti:" önekiyle) gösterir
    expect(toggle.getAttribute('aria-describedby')).toBe(chips.id)
    expect(toggle.contains(chips)).toBe(true)
    expect(chips.textContent).toMatch(/^Konunun özeti: 12 Eylül 2026 · 14:00–15:30.*Kesinti · INC-2026-0915/)
    // İçerik DOM'da kurulu (girdiler, görsel yuvaları), erişilebilir değil
    expect(document.getElementById('te-iss-issues-k1-topic')).not.toBeNull()
    expect(screen.queryByRole('textbox', { name: 'Konu' })).toBeNull()
    expect(cardOf('k1').querySelector('[data-slot="te-image-slot"]')).not.toBeNull()
    // Kopyala / taşı / sil düğmenin DIŞINDA, başlığın kardeşi
    expect(toggle.querySelector('[data-slot="te-copy"]')).toBeNull()
    expect(cardOf('k1').querySelector('[data-slot="te-issue-header"] [data-slot="te-copy"]')).not.toBeNull()
    expect(toggle.contains(cardOf('k1').querySelector('[data-slot="te-issue-remove"]'))).toBe(false)
    expect(stored()).toBeNull()
  })

  it('tıklama ve klavye (Enter / Boşluk) açar-kapatır; açık konu bu rapor için hatırlanır (bölüm kaydı değişmez); yeniden açılışta aynı', async () => {
    te().report.mockResolvedValue(respond([K1, K2]))
    const user = userEvent.setup()
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const sectionsBefore = localStorage.getItem('sm.te.open.r.100')
    fireEvent.click(toggleOf('k1'))
    expect(toggleOf('k1')).toHaveAttribute('aria-expanded', 'true')
    expect(contentOf('k1')).not.toHaveAttribute('hidden')
    expect(screen.getByRole('textbox', { name: 'Konu' })).toHaveValue('Kart Limiti')
    expect(stored()).toEqual({ ids: ['issues:k1'] })
    expect(localStorage.getItem('sm.te.open.r.100')).toBe(sectionsBefore)
    // Klavye: odaktaki düğmede Enter açar, Boşluk kapatır
    toggleOf('k2').focus()
    await user.keyboard('{Enter}')
    expect(toggleOf('k2')).toHaveAttribute('aria-expanded', 'true')
    await user.keyboard(' ')
    expect(toggleOf('k2')).toHaveAttribute('aria-expanded', 'false')
    expect(stored()).toEqual({ ids: ['issues:k1'] })

    unmount()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(toggleOf('k1')).toHaveAttribute('aria-expanded', 'true')
    expect(toggleOf('k2')).toHaveAttribute('aria-expanded', 'false')
  })

  it('yeni konu AÇIK gelir ve odak Konu alanına geçer; öteki konular kapalı kalır; silinen konunun açık kaydı düşer', async () => {
    te().report.mockResolvedValue(respond([K1]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
    const cards = document.querySelectorAll('[data-slot="te-issue"]')
    expect(cards).toHaveLength(2)
    const fresh = cards[1].getAttribute('data-issue')
    expect(cardOf(fresh)).toHaveAttribute('data-open', 'true')
    await waitFor(() => expect(document.activeElement).toBe(document.getElementById(`te-iss-issues-${fresh}-topic`)))
    expect(cardOf('k1')).toHaveAttribute('data-open', 'false')
    expect(stored()).toEqual({ ids: [`issues:${fresh}`] })
    fireEvent.click(within(cardOf(fresh)).getByRole('button', { name: '“2” başlığını kaldır' }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-issue"]')).toHaveLength(1))
    expect(stored()).toEqual({ ids: [] })
  })

  it('"Tüm konuları aç / kapat" yalnız bu bölümün konularını değiştirir; bölümlerin "Tümünü aç / kapat"ı konulara dokunmaz', async () => {
    te().report.mockResolvedValue(respond([K1, K2]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const all = document.querySelector('[data-slot="te-issues-all"]')
    expect(all).toHaveTextContent('Tüm konuları aç')
    fireEvent.click(all)
    expect(cardOf('k1')).toHaveAttribute('data-open', 'true')
    expect(cardOf('k2')).toHaveAttribute('data-open', 'true')
    expect(document.querySelector('[data-slot="te-issues-all"]')).toHaveTextContent('Tüm konuları kapat')
    // Bölümler hâlâ açık; bölümlerin "Tümünü kapat"ı konuların kaydını değiştirmez
    const topicsState = localStorage.getItem(KEY)
    fireEvent.click(screen.getByRole('button', { name: 'Tümünü kapat' }))
    expect(document.querySelector('[data-slot="te-issues"]')).toHaveAttribute('data-open', 'false')
    expect(localStorage.getItem(KEY)).toBe(topicsState)
    fireEvent.click(screen.getByRole('button', { name: 'Tümünü aç' }))
    expect(cardOf('k1')).toHaveAttribute('data-open', 'true')
    expect(cardOf('k2')).toHaveAttribute('data-open', 'true')
    // Biri kapalıyken düğme "aç" der; "Tüm konuları kapat" hepsini kapatır
    fireEvent.click(toggleOf('k2'))
    expect(document.querySelector('[data-slot="te-issues-all"]')).toHaveTextContent('Tüm konuları aç')
    fireEvent.click(document.querySelector('[data-slot="te-issues-all"]'))
    fireEvent.click(document.querySelector('[data-slot="te-issues-all"]'))
    expect(cardOf('k1')).toHaveAttribute('data-open', 'false')
    expect(cardOf('k2')).toHaveAttribute('data-open', 'false')
  })

  it('hatası olan konu açılış anında açık; kapatılınca rozet sayar; Kaydet konuyu (ve bölümünü) açar ve kaydetmez', async () => {
    // Kesinti Evet ama ID boş: kaydı durduran zorunlu alan
    te().report.mockResolvedValue(respond([{ ...K1, outage_id: '' }, K2]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(cardOf('k1')).toHaveAttribute('data-open', 'true'))
    expect(cardOf('k2')).toHaveAttribute('data-open', 'false')
    fireEvent.click(toggleOf('k1'))
    expect(cardOf('k1')).toHaveAttribute('data-open', 'false')
    expect(cardOf('k1').querySelector('[data-slot="te-issue-invalid"]')).toHaveTextContent('1 alan düzeltilmeli')
    expect(cardOf('k2').querySelector('[data-slot="te-issue-invalid"]')).toBeNull()
    // Bölüm de kapatılır; Kaydet ikisini de açar
    fireEvent.click(screen.getByRole('button', { name: 'Anormal Artış Gösteren Hatalar' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '125000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(cardOf('k1')).toHaveAttribute('data-open', 'true'))
    expect(document.querySelector('[data-slot="te-issues"]')).toHaveAttribute('data-open', 'true')
    expect(cardOf('k1').querySelector('[data-slot="te-issue-invalid"]')).toBeNull()
    expect(cardOf('k2')).toHaveAttribute('data-open', 'false')
    expect(te().saveReport).not.toHaveBeenCalled()
  })

  it('sunucunun konu alanı hatası kapalı konuyu açar (yalnız o konuyu)', async () => {
    te().report.mockResolvedValue(respond([K1, K2]))
    te().saveReport.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'issues.issues.k2.topic',
      error: '2. başlık: konu en çok 200 karakter olabilir; kısaltın.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '125000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(cardOf('k2')).toHaveAttribute('data-open', 'true'))
    expect(cardOf('k1')).toHaveAttribute('data-open', 'false')
    expect(await screen.findByText('2. başlık: konu en çok 200 karakter olabilir; kısaltın.', { selector: '[role="alert"]' })).toBeVisible()
  })

  it('salt okunur kullanıcı da açıp kapatır: ekle / taşı / sil yok, "Tüm konuları aç" var', async () => {
    te().report.mockResolvedValue(respond([K1, K2], { can_edit: false }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.queryByRole('button', { name: 'Başlık ekle' })).toBeNull()
    expect(cardOf('k1').querySelector('[data-slot="te-issue-remove"]')).toBeNull()
    fireEvent.click(toggleOf('k1'))
    expect(cardOf('k1')).toHaveAttribute('data-open', 'true')
    expect(screen.getByRole('textbox', { name: 'Konu' })).toBeDisabled()
    fireEvent.click(document.querySelector('[data-slot="te-issues-all"]'))
    expect(cardOf('k2')).toHaveAttribute('data-open', 'true')
  })

  it('sayfada bul (beforematch) kapalı konuyu açar; kapatılan konuda yarım yazılmış metin ve değer korunur (içerik kurulu)', async () => {
    te().report.mockResolvedValue(respond([K1, K2]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent(contentOf('k2'), new Event('beforematch'))
    expect(cardOf('k2')).toHaveAttribute('data-open', 'true')
    expect(stored()).toEqual({ ids: ['issues:k2'] })
    // Açık konuda yazılan değer kapatıp açınca yerinde
    fireEvent.change(screen.getByRole('textbox', { name: 'Konu' }), { target: { value: 'Fatura Ödeme Servisi' } })
    fireEvent.click(toggleOf('k2'))
    expect(document.getElementById('te-iss-issues-k2-topic')).toHaveValue('Fatura Ödeme Servisi')
    // Kapalı konunun başlığı canlı: çipler / başlık güncel
    expect(toggleOf('k2')).toHaveAccessibleName('2. Fatura Ödeme Servisi: Hata Artışı')
    fireEvent.click(toggleOf('k2'))
    expect(screen.getByRole('textbox', { name: 'Konu' })).toHaveValue('Fatura Ödeme Servisi')
  })

  it('inceleme 2026-10-19: açık konu kaydı raporda OLMAYAN konulardan arınır — 64 eski kimlikle dolu kayıtta yeni konu açmak en eski AÇIK konuyu sessizce kapatmaz', async () => {
    const stale = Array.from({ length: 63 }, (_, i) => `issues:silinen${i}`)
    localStorage.setItem(KEY, JSON.stringify({ ids: ['issues:k1', ...stale] }))
    te().report.mockResolvedValue(respond([K1, K2]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(toggleOf('k1')).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(toggleOf('k2'))
    expect(toggleOf('k2')).toHaveAttribute('aria-expanded', 'true')
    expect(toggleOf('k1')).toHaveAttribute('aria-expanded', 'true')
    expect(stored()).toEqual({ ids: ['issues:k1', 'issues:k2'] })
  })

  it('inceleme 2026-10-19: tavan bir raporun en çok konusu — 70 gerçek konu (üç bölüm) birlikte açık kalır; aynı çizimde eklenen konu (henüz listede yok) açılır; Vazgeç ile kalkan konu bir sonraki yazımda düşer', () => {
    localStorage.removeItem('sm.te.open.ri.555')
    const ids = Array.from({ length: 70 }, (_, i) => `b${Math.floor(i / 30)}:t${i}`)
    const { result, rerender } = renderHook(({ list }) => useSectionOpenState('sm.te.open.ri.555', list, TOPIC_OPEN_OPTS),
      { initialProps: { list: ids } })
    for (const id of ids) act(() => result.current.setOpen(id, true))
    expect(result.current.state.ids).toEqual(ids)
    // Yeni konu: taslağa aynı olayda eklendi, kimlik listesi henüz eski — arınma onu düşürmez
    act(() => result.current.setOpen('b2:yeni', true))
    expect(result.current.state.ids).toHaveLength(71)
    expect(result.current.state.ids.at(-1)).toBe('b2:yeni')
    // Vazgeç: ilk konu taslaktan kalktı; sonraki yazım (başka bir konuyu kapatmak) onun kimliğini de düşürür
    rerender({ list: [...ids.slice(1), 'b2:yeni'] })
    act(() => result.current.setOpen(ids[5], false))
    expect(result.current.state.ids).toHaveLength(69)
    expect(result.current.state.ids).not.toContain(ids[0])
    expect(result.current.state.ids).not.toContain(ids[5])
    expect(result.current.state.ids).toContain('b2:yeni')
    expect(JSON.parse(localStorage.getItem('sm.te.open.ri.555'))).toEqual({ ids: result.current.state.ids })
  })

  it('inceleme 2026-10-19: arınma taslak ∪ kayıtlı rapor kümesine karşı — keep\'teki (kayıtlı) konunun açık kaydı daha az konulu taslakta yapılan yazımda düşmez; ikisinde de olmayan düşer', () => {
    localStorage.setItem('sm.te.open.ri.556', JSON.stringify({ ids: ['issues:k1', 'issues:k2', 'issues:eski'] }))
    const { result, rerender } = renderHook(({ list, keep }) => useSectionOpenState('sm.te.open.ri.556', list,
      { ...TOPIC_OPEN_OPTS, keep }), { initialProps: { list: ['issues:k1'], keep: ['issues:k1', 'issues:k2'] } })
    act(() => result.current.setOpen('issues:k1', false))
    expect(result.current.state.ids).toEqual(['issues:k2'])
    // keep yoksa (eski davranış) kayıtlı konu da düşerdi
    rerender({ list: ['issues:k1'], keep: null })
    act(() => result.current.setOpen('issues:k1', true))
    expect(result.current.state.ids).toEqual(['issues:k1'])
  })

  it('inceleme 2026-10-19: daha az konulu yedek taslak geri yüklenip bir konu açılıp kapanınca kayıtlı konuların açık kaydı silinmez — "Değişiklikleri geri al"dan sonra açık görünürler', async () => {
    localStorage.setItem(KEY, JSON.stringify({ ids: ['issues:k1', 'issues:k2'] }))
    localStorage.setItem('sm.te.draft.100', JSON.stringify({ version: 4, at: Date.now(),
      values: { fields: { 'error-count': 5000 }, issues: { issues: [{ ...K1, topic: 'Kart Limiti (yedek)' }] }, texts: {} } }))
    te().report.mockResolvedValue(respond([K1, K2]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(toggleOf('k2')).toHaveAttribute('aria-expanded', 'true'))
    fireEvent.click(await screen.findByRole('button', { name: 'Geri yükle' }))
    await waitFor(() => expect(cardOf('k2')).toBeNull())
    // Yedekte olmayan kayıtlı konu (k2) taslakta yok; k1'i kapatmak bir YAZIM — k2'nin açık kaydı kalmalı
    fireEvent.click(toggleOf('k1'))
    await waitFor(() => expect(toggleOf('k1')).toHaveAttribute('aria-expanded', 'false'))
    expect(stored()).toEqual({ ids: ['issues:k2'] })
    // Vazgeç: kayıtlı taslak geri gelir, k2 AÇIK
    fireEvent.pointerDown(screen.getByRole('button', { name: /Diğer işlemler/ }), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Değişiklikleri geri al' }))
    await waitFor(() => expect(cardOf('k2')).not.toBeNull())
    expect(toggleOf('k2')).toHaveAttribute('aria-expanded', 'true')
    expect(toggleOf('k1')).toHaveAttribute('aria-expanded', 'false')
  })
})
