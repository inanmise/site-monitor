import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Olay kaydına BAĞLI konu (2026-11-20, CANLI bağ): kaynak çubuğu ve durumları, açılıştaki tek eşitleme (yalnız taslak temizken
// uygulanır), "Güncelle", dondurulmuş raporda onay, elle değiştirilen alanın işareti + "Olaydaki değere dön", bağı koparma,
// olay kaydını açma, kayıttan sonra bağların yeniden okunması ve değişiklik geçmişindeki yeni olay türleri.
const perm = vi.hoisted(() => ({ incidents: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {}, scope: null, loaded: true, failed: false, refresh: () => {},
    canView: (r) => (r === 'incidents.view' ? perm.incidents : false),
    canEdit: () => false,
    canExecute: () => false,
  }),
  PermissionsProvider: ({ children }) => children,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(),
      deleteImage: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`, reportHistory: vi.fn(),
      incidentCandidates: vi.fn(), incidentImportPreview: vi.fn(), importIncidents: vi.fn(), syncIncidentLinks: vi.fn(),
      refreshIncidentLink: vi.fn(), unlinkIncident: vi.fn(), incidentUsage: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu?', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count',
}
const TPL = { ...TEMPLATE, sections: [
  { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [{ id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' }] },
  ISSUES,
] }
const topic = (id, name, extra = {}) => ({ id, topic: name, title: '', title_auto: true, description: 'DB havuzu doldu.',
  date: '2026-09-12', start: '14:00', end: '15:30', errors: 1250, outage: true, outage_id: 'INC-1', impact: true, customers: 320, ...extra })
const link = (iid, incidentId, extra = {}) => ({
  section_id: 'issues', issue_id: iid, incident_id: incidentId, state: 'ok', title: 'Ödeme API kesintisi', status: 'RESOLVED',
  occurred_at: '2026-09-12T11:00:00', pinned: [], notes: [], synced_at: '2026-10-01T08:00:00', incident_updated_at: '2026-09-30T08:00:00',
  ...extra,
})
const baseReport = reportResponse().data.report
const respond = ({ issues = [topic('i-9', 'Ödeme API kesintisi')], links = [link('i-9', 9)], report = {}, extra = {} } = {}) =>
  reportResponse({ template: TPL, report: { ...baseReport, ...report },
    values: { fields: { 'error-count': 5000 }, issues: { issues }, texts: {} }, incident_links: links, ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-18T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })
const bar = (id = 9) => document.querySelector(`[data-slot="te-issue-incident"][data-incident="${id}"]`)
const stateOf = (id = 9) => bar(id)?.getAttribute('data-state')
const topicInput = (iid = 'i-9') => document.getElementById(`te-iss-issues-${iid}-topic`)
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
async function openMenu(name) {
  pressMenuTrigger(screen.getByRole('button', { name }))
  return screen.findByRole('menu')
}

beforeEach(() => {
  vi.clearAllMocks()
  perm.incidents = true
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil')))
  te().saveReport.mockImplementation(async (_id, values) => saved(values))
  te().syncIncidentLinks.mockResolvedValue({ success: false, status: 500, error: 'eşitleme kapalı' })
})

describe('bağlı konu — kaynak çubuğu', () => {
  it('eşit bağ: kaynak + "Olayı izliyor" + başlık çipi; "Güncelle" yok; eşitleme isteği yok', async () => {
    te().report.mockResolvedValue(respond())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    expect(stateOf()).toBe('ok')
    expect(bar().querySelector('[data-slot="te-issue-incident-source"]').textContent).toBe('Olay kaydı #9 · Ödeme API kesintisi')
    expect(bar().querySelector('[data-slot="te-issue-incident-state"]').textContent).toContain('Olayı izliyor')
    expect(bar().querySelector('[data-slot="te-issue-incident-update"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-issue-chips"] [data-chip="incident"]').textContent).toContain('Olay #9')
    expect(te().syncIncidentLinks).not.toHaveBeenCalled()
  })

  it('silinmiş ve erişilemeyen olay: açıklama; erişilemeyende başlık gösterilmez; açma / güncelleme yok', async () => {
    te().report.mockResolvedValue(respond({
      issues: [topic('i-9', 'Ödeme'), topic('i-11', 'Kart')],
      links: [link('i-9', 9, { state: 'deleted' }), link('i-11', 11, { state: 'hidden', title: 'Gizli başlık' })],
    }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar(11)).not.toBeNull())
    expect(stateOf(9)).toBe('deleted')
    expect(bar(9).textContent).toContain('Olay kaydı silindi')
    expect(bar(9).querySelector('[data-slot="te-issue-incident-hint"]').textContent).toContain('Bağı koparıp sıradan konu')
    // hidden = okuma izni yok YA DA olay başka bir takıma taşındı (arka uç): ifade yalnız "erişim yok" demez
    expect(bar(11).querySelector('[data-slot="te-issue-incident-source"]').textContent)
      .toBe('Olay kaydı görüntülenemiyor (erişiminiz yok ya da başka bir takıma taşındı)')
    expect(bar(11).querySelector('[data-slot="te-issue-incident-state"]').textContent).toContain('Görüntülenemiyor')
    expect(bar(11).querySelector('[data-slot="te-issue-incident-hint"]').textContent).toContain('başka bir takıma taşındı')
    expect(bar(11).textContent).not.toContain('Gizli başlık')
    // Olaydan değer alan eylem yok: güncelleme düğmesi ve menüde "Tüm alanları olaydan yeniden al" çizilmez
    expect(bar(11).querySelector('[data-slot="te-issue-incident-update"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-issue-incident-update"]')).toBeNull()
    const menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    expect(within(menu).queryByRole('menuitem', { name: /Olay kaydını aç/ })).toBeNull()
    expect(within(menu).getByRole('menuitem', { name: /Bağı kopar/ })).toBeInTheDocument()
  })

  it('"Olay kaydını aç" olay geçmişine gider; olay okuma izni yoksa menüde yok', async () => {
    te().report.mockResolvedValue(respond())
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    try {
      const { unmount } = render(<TechErrorReportsPage />)
      await screen.findByRole('heading', { name: TITLE })
      await waitFor(() => expect(bar()).not.toBeNull())
      const menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
      fireEvent.click(within(menu).getByRole('menuitem', { name: /Olay kaydını aç/ }))
      await waitFor(() => expect(seen).toEqual([{ tab: 'incident-history', params: { incident: '9' } }]))
      unmount()

      perm.incidents = false
      render(<TechErrorReportsPage />)
      await screen.findByRole('heading', { name: TITLE })
      await waitFor(() => expect(bar()).not.toBeNull())
      const menu2 = await openMenu('Olay #9 — Olay kaydı işlemleri')
      expect(within(menu2).queryByRole('menuitem', { name: /Olay kaydını aç/ })).toBeNull()
    } finally {
      window.removeEventListener('sm:navigate', onNav)
    }
  })

  it('salt okunur: kaynak görünür; eşitleme, "Güncelle", "Olaydaki değere dön" ve bağı koparma yok', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale', pinned: ['topic'] })], extra: { can_edit: false } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    expect(stateOf()).toBe('stale')
    expect(bar().querySelector('[data-slot="te-issue-incident-update"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-issue-pinned"][data-group="topic"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-issue-unpin"]')).toBeNull()
    const menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    expect(within(menu).queryByRole('menuitem', { name: /Bağı kopar/ })).toBeNull()
    expect(within(menu).getByRole('menuitem', { name: /Olay kaydını aç/ })).toBeInTheDocument()
    expect(te().syncIncidentLinks).not.toHaveBeenCalled()
  })
})

describe('bağlı konu — eşitleme ve güncelleme', () => {
  it('açılışta bayat bağ: TEK eşitleme; taslak temizken yanıt uygulanır (konu güncellenir, bağ eşit)', async () => {
    const d = deferred()
    te().syncIncidentLinks.mockReturnValue(d.promise)
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale' })] }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(te().syncIncidentLinks).toHaveBeenCalledTimes(1))
    expect(te().syncIncidentLinks).toHaveBeenCalledWith('100')
    // Sürerken: "güncelleniyor", "Güncelle" düğmesi yok
    await waitFor(() => expect(bar().querySelector('[data-slot="te-issue-incident-state"]').textContent).toContain('güncelleniyor'))
    expect(bar().querySelector('[data-slot="te-issue-incident-update"]')).toBeNull()
    d.resolve(respond({ issues: [topic('i-9', 'Ödeme API kesintisi (güncel)')], report: { version: 5 } }))
    await waitFor(() => expect(topicInput().value).toBe('Ödeme API kesintisi (güncel)'))
    expect(stateOf()).toBe('ok')
    // Döngü yok: yanıtın uygulanması (yeni veri, yeni bağ dizini) ikinci bir eşitleme göndermez
    await new Promise((r) => setTimeout(r, 60))
    expect(te().syncIncidentLinks).toHaveBeenCalledTimes(1)
  })

  it('eşitleme sürerken kullanıcı yazarsa yanıt taslağın yerine geçmez ama çakışma HEMEN söylenir (sunucu zaten yazdı)', async () => {
    const d = deferred()
    te().syncIncidentLinks.mockReturnValue(d.promise)
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale' })] }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(te().syncIncidentLinks).toHaveBeenCalledTimes(1))
    fireEvent.change(topicInput(), { target: { value: 'Benim başlığım' } })
    d.resolve(respond({ issues: [topic('i-9', 'Sunucunun başlığı')], report: { version: 5 } }))
    // Eskiden yanıt sessizce atılıyor, sonraki otomatik kayıt yanıltıcı bir 409 alıyordu
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    expect(topicInput().value).toBe('Benim başlığım')
    expect(te().saveReport).not.toHaveBeenCalled()
  })

  it('eşitleme sürerken araya kayıt girerse (yükleme sırası ilerledi) yanıt atılır: çakışma yok, yazılan kayıtlı', async () => {
    const d = deferred()
    te().syncIncidentLinks.mockReturnValue(d.promise)
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale' })] }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(te().syncIncidentLinks).toHaveBeenCalledTimes(1))
    fireEvent.change(topicInput(), { target: { value: 'Benim başlığım' } })
    fireEvent.click(document.querySelector('[data-slot="te-save"]'))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    d.resolve(respond({ issues: [topic('i-9', 'Sunucunun başlığı')], report: { version: 6 } }))
    await new Promise((r) => setTimeout(r, 30))
    expect(screen.queryByText('Rapor siz düzenlerken değişti')).toBeNull()
    expect(topicInput().value).toBe('Benim başlığım')
  })

  it('"Güncelle": sürümle istek, yanıt uygulanır, ileti', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale' })] }))
    te().refreshIncidentLink.mockResolvedValue(respond({ issues: [topic('i-9', 'Yeni başlık')], report: { version: 5 } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    // Açılış eşitlemesi başarısız (beforeEach) → bağ bayat kalır, düğme görünür
    await waitFor(() => expect(bar()?.querySelector('[data-slot="te-issue-incident-update"]')).not.toBeNull())
    fireEvent.click(bar().querySelector('[data-slot="te-issue-incident-update"]'))
    await waitFor(() => expect(te().refreshIncidentLink).toHaveBeenCalledWith('100', 'issues', 'i-9', { version: 4, unpin: [] }))
    await waitFor(() => expect(topicInput().value).toBe('Yeni başlık'))
    expect(stateOf()).toBe('ok')
    expect(await screen.findByText('Konu olay kaydından güncellendi.')).toBeInTheDocument()
  })

  it('dondurulmuş raporda "Güncelle" önce sorar; vazgeçince istek yok', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'frozen_stale' })],
      report: { status: 'sent', last_sent_at: '2026-10-05T09:00:00Z' } }))
    te().refreshIncidentLink.mockResolvedValue(respond({ report: { version: 5, status: 'changed' } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()?.querySelector('[data-slot="te-issue-incident-update"]')).not.toBeNull())
    expect(te().syncIncidentLinks).not.toHaveBeenCalled()
    expect(bar().querySelector('[data-slot="te-issue-incident-hint"]').textContent).toContain('onay eskir')
    fireEvent.click(bar().querySelector('[data-slot="te-issue-incident-update"]'))
    let dlg = await screen.findByRole('dialog', { name: 'Gönderilmiş rapor güncellensin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Gönderilmiş rapor güncellensin mi?' })).toBeNull())
    expect(te().refreshIncidentLink).not.toHaveBeenCalled()
    fireEvent.click(bar().querySelector('[data-slot="te-issue-incident-update"]'))
    dlg = await screen.findByRole('dialog', { name: 'Gönderilmiş rapor güncellensin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yine de güncelle' }))
    await waitFor(() => expect(te().refreshIncidentLink).toHaveBeenCalledTimes(1))
  })

  it('çakışma: ileti ve rapor yeniden okunur; bilinmeyen sonuç da yeniden okur', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale' })] }))
    te().refreshIncidentLink.mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()?.querySelector('[data-slot="te-issue-incident-update"]')).not.toBeNull())
    const before = te().report.mock.calls.length
    fireEvent.click(bar().querySelector('[data-slot="te-issue-incident-update"]'))
    expect(await screen.findByText('Rapor bu arada başka bir yerde değişti; en son hâli yüklendi. İşlemi raporun güncel hâli üzerinde yeniden yapın.')).toBeInTheDocument()
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(before))
  })

  it('404 INCIDENT_LINK_NOT_FOUND / INCIDENT_NOT_FOUND: kendi cümlesi, bağlar yeniden okunur ("bu sürümde yok" denmez)', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale' })] }))
    te().refreshIncidentLink.mockResolvedValueOnce({ success: false, status: 404, code: 'INCIDENT_LINK_NOT_FOUND', error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()?.querySelector('[data-slot="te-issue-incident-update"]')).not.toBeNull())
    let before = te().report.mock.calls.length
    fireEvent.click(bar().querySelector('[data-slot="te-issue-incident-update"]'))
    expect(await screen.findByText(/Bu konunun olay kaydı bağı bulunamadı/)).toBeInTheDocument()
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(before))

    te().refreshIncidentLink.mockResolvedValueOnce({ success: false, status: 404, code: 'INCIDENT_NOT_FOUND', error: 'x' })
    await waitFor(() => expect(bar()?.querySelector('[data-slot="te-issue-incident-update"]')).not.toBeNull())
    before = te().report.mock.calls.length
    fireEvent.click(bar().querySelector('[data-slot="te-issue-incident-update"]'))
    expect(await screen.findByText(/konu olaydan güncellenemez/)).toBeInTheDocument()
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(before))
    expect(document.body.textContent).not.toContain('INCIDENT_')
  })

  it('olay okuma izni yoksa olaydan değer alan eylemler yok (sunucu yenilemede olay kaydını okur); bağı koparma kalır', async () => {
    perm.incidents = false
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'stale', pinned: ['topic'] })] }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    expect(bar().querySelector('[data-slot="te-issue-incident-update"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-issue-pinned"][data-group="topic"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-issue-unpin"]')).toBeNull()
    const menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    expect(within(menu).queryByRole('menuitem', { name: /Tüm alanları olaydan yeniden al/ })).toBeNull()
    expect(within(menu).getByRole('menuitem', { name: /Bağı kopar/ })).toBeInTheDocument()
  })

  it('silinmiş olayın elle değiştirilen alanında "Olaydaki değere dön" yok (sunucu 404 verirdi)', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { state: 'deleted', pinned: ['topic'] })] }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    expect(document.querySelector('[data-slot="te-issue-pinned"][data-group="topic"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-issue-unpin"]')).toBeNull()
  })
})

describe('bağlı konu — elle değiştirilen alanlar', () => {
  it('işaretler ilgili grupta; "Olaydaki değere dön" yalnız o grubu, menüdeki tümünü yeniden alır', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { pinned: ['impact', 'topic'] })] }))
    te().refreshIncidentLink.mockResolvedValue(respond({ report: { version: 5 } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    expect(bar().querySelector('[data-slot="te-issue-incident-pinned"]').textContent)
      .toBe('2 alan elle değiştirildi ve olayı izlemiyor: Konu, Müşteri etkisi')
    const topicGroup = document.querySelector('[data-slot="te-issue-topic"]')
    const impactGroup = document.querySelector('[data-slot="te-issue-impact"]')
    expect(topicGroup.querySelector('[data-slot="te-issue-pinned"][data-group="topic"]').textContent).toContain('Konu elle değiştirildi')
    expect(impactGroup.querySelector('[data-slot="te-issue-pinned"][data-group="impact"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-issue-when"] [data-slot="te-issue-pinned"]')).toBeNull()
    fireEvent.click(within(topicGroup).getByRole('button', { name: 'Olaydaki değere dön: Konu' }))
    await waitFor(() => expect(te().refreshIncidentLink).toHaveBeenCalledWith('100', 'issues', 'i-9', { version: 4, unpin: ['topic'] }))
    expect(await screen.findByText('Alan olaydaki değere döndü ve yeniden olayı izliyor.')).toBeInTheDocument()
  })

  it('menüden "Tüm alanları olaydan yeniden al" → unpin "all"', async () => {
    te().report.mockResolvedValue(respond({ links: [link('i-9', 9, { pinned: ['topic'] })] }))
    te().refreshIncidentLink.mockResolvedValue(respond({ report: { version: 5 } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    const menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Tüm alanları olaydan yeniden al/ }))
    await waitFor(() => expect(te().refreshIncidentLink).toHaveBeenCalledWith('100', 'issues', 'i-9', { version: 4, unpin: 'all' }))
  })

  it('bağlı konu kaydedilince bağlar yeniden okunur (iğneyi sunucu belirler)', async () => {
    te().report
      .mockResolvedValueOnce(respond())
      .mockResolvedValue(respond({ links: [link('i-9', 9, { pinned: ['topic'] })], report: { version: 5 } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    expect(document.querySelector('[data-slot="te-issue-pinned"]')).toBeNull()
    fireEvent.change(topicInput(), { target: { value: 'Elle yazılan başlık' } })
    fireEvent.click(document.querySelector('[data-slot="te-save"]'))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.querySelector('[data-slot="te-issue-topic"] [data-slot="te-issue-pinned"]')).not.toBeNull())
    // Yalnız bağlar alındı: kullanıcının yazdığı başlık yerinde
    expect(topicInput().value).toBe('Elle yazılan başlık')
  })
})

describe('bağlı konu — bağı koparma', () => {
  it('onaydan sonra DELETE; konu kalır, kaynak çubuğu gider; vazgeçince istek yok', async () => {
    te().report.mockResolvedValue(respond())
    te().unlinkIncident.mockResolvedValue({ success: true, data: { incident_links: [] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    let menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Bağı kopar/ }))
    let dlg = await screen.findByRole('dialog', { name: 'Olay kaydıyla bağ koparılsın mı?' })
    expect(dlg.textContent).toContain('Ödeme API kesintisi')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Olay kaydıyla bağ koparılsın mı?' })).toBeNull())
    expect(te().unlinkIncident).not.toHaveBeenCalled()

    menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Bağı kopar/ }))
    dlg = await screen.findByRole('dialog', { name: 'Olay kaydıyla bağ koparılsın mı?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Bağı kopar' }))
    await waitFor(() => expect(te().unlinkIncident).toHaveBeenCalledWith('100', 'issues', 'i-9'))
    await waitFor(() => expect(bar()).toBeNull())
    expect(topicInput().value).toBe('Ödeme API kesintisi')
    expect(await screen.findByText('Bağ koparıldı; konu sıradan konu olarak kaldı.')).toBeInTheDocument()
    expect(te().saveReport).not.toHaveBeenCalled()
  })

  it('bağ zaten yoksa (404 INCIDENT_LINK_NOT_FOUND) bilgi verilir ve bağlar yeniden okunur', async () => {
    te().report.mockResolvedValueOnce(respond()).mockResolvedValue(respond({ links: [] }))
    te().unlinkIncident.mockResolvedValue({ success: false, status: 404, code: 'INCIDENT_LINK_NOT_FOUND', error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(bar()).not.toBeNull())
    const menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Bağı kopar/ }))
    const dlg = await screen.findByRole('dialog', { name: 'Olay kaydıyla bağ koparılsın mı?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Bağı kopar' }))
    expect(await screen.findByText(/Bu konunun olay kaydı bağı bulunamadı/)).toBeInTheDocument()
    await waitFor(() => expect(bar()).toBeNull())
  })
})

describe('bağlı konu — işlem sürerken eylemler kapalı (inceleme 2026-11-20)', () => {
  const importButton = () => document.querySelector('[data-slot="te-inc-import"]')
  const updateOf = (id) => bar(id)?.querySelector('[data-slot="te-issue-incident-update"]')

  it('güncelleme sürerken içe alma, öbür konunun "Güncelle"si, "Olaydaki değere dön" ve menü öğeleri kapalı; işlem gören "Güncelleniyor…"', async () => {
    const d = deferred()
    te().report.mockResolvedValue(respond({ issues: [topic('i-9', 'Ödeme'), topic('i-11', 'Kart')],
      links: [link('i-9', 9, { state: 'stale' }), link('i-11', 11, { state: 'stale', pinned: ['topic'] })] }))
    te().refreshIncidentLink.mockReturnValue(d.promise)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(updateOf(11)).not.toBeNull())
    expect(importButton()).not.toBeDisabled()
    fireEvent.click(updateOf(9))
    await waitFor(() => expect(te().refreshIncidentLink).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(updateOf(9).textContent).toContain('Güncelleniyor…'))
    expect(updateOf(9)).toBeDisabled()
    expect(updateOf(9).getAttribute('aria-busy')).toBe('true')
    // Öbür konu ve araç çubuğu: kapalı (eskiden tıklama sessizce yutuluyordu)
    expect(updateOf(11)).toBeDisabled()
    expect(updateOf(11).textContent).toContain('Güncelle')
    expect(updateOf(11).textContent).not.toContain('Güncelleniyor')
    expect(importButton()).toBeDisabled()
    expect(importButton().getAttribute('data-locked')).toBe('true')
    expect(document.querySelector('[data-slot="te-issue-unpin"]')).toBeDisabled()
    const menu = await openMenu('Olay #11 — Olay kaydı işlemleri')
    expect(within(menu).getByRole('menuitem', { name: /Tüm alanları olaydan yeniden al/ })).toHaveAttribute('data-disabled')
    expect(within(menu).getByRole('menuitem', { name: /Bağı kopar/ })).toHaveAttribute('data-disabled')
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(te().refreshIncidentLink).toHaveBeenCalledTimes(1)
    d.resolve(respond({ issues: [topic('i-9', 'Ödeme'), topic('i-11', 'Kart')], report: { version: 5 },
      links: [link('i-9', 9), link('i-11', 11, { state: 'stale', pinned: ['topic'] })] }))
    await waitFor(() => expect(importButton()).not.toBeDisabled())
    expect(updateOf(11)).not.toBeDisabled()
    expect(document.querySelector('[data-slot="te-issue-unpin"]')).not.toBeDisabled()
  })

  it('bağı koparma da düzenleyicinin kilidini alır: sürerken "Bağ koparılıyor…", içe alma ve öbür eylemler kapalı; bitince açılır', async () => {
    const d = deferred()
    te().report.mockResolvedValue(respond({ issues: [topic('i-9', 'Ödeme'), topic('i-11', 'Kart')],
      links: [link('i-9', 9), link('i-11', 11, { state: 'stale' })] }))
    te().unlinkIncident.mockReturnValue(d.promise)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(updateOf(11)).not.toBeNull())
    const menu = await openMenu('Olay #9 — Olay kaydı işlemleri')
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Bağı kopar/ }))
    const dlg = await screen.findByRole('dialog', { name: 'Olay kaydıyla bağ koparılsın mı?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Bağı kopar' }))
    await waitFor(() => expect(te().unlinkIncident).toHaveBeenCalledTimes(1))
    const busyLine = await waitFor(() => {
      const el = bar(9)?.querySelector('[data-slot="te-issue-incident-busy"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(busyLine.textContent).toBe('Bağ koparılıyor…')
    expect(importButton()).toBeDisabled()
    expect(updateOf(11)).toBeDisabled()
    // Kapalı düğmeye basmak istek göndermez (paralel güncelleme yok)
    fireEvent.click(updateOf(11))
    expect(te().refreshIncidentLink).not.toHaveBeenCalled()
    d.resolve({ success: true, data: { incident_links: [link('i-11', 11, { state: 'stale' })] } })
    await waitFor(() => expect(bar(9)).toBeNull())
    expect(importButton()).not.toBeDisabled()
    expect(updateOf(11)).not.toBeDisabled()
  })
})

describe('değişiklik geçmişi — olay türleri', () => {
  it('içe alma, eşitleme ve bağı koparma okunur başlıklarla; olay kimlikleri yazılır', async () => {
    te().report.mockResolvedValue(respond())
    te().reportHistory.mockResolvedValue({ success: true, data: { has_more: false, events: [
      // Sunucunun gerçek ayrıntıları (TechErrorIncidentImportService / SyncService)
      { id: 4, kind: 'INCIDENT_UNLINK', at: '2026-10-13T11:00:00Z', actor_name: 'Ayşe Yılmaz', system: false, version: null,
        detail: { incident_id: 9, section_id: 'issues', issue_id: 'i-9', topic: 'Ödeme API kesintisi' } },
      { id: 3, kind: 'INCIDENT_SYNC', at: '2026-10-13T10:00:00Z', actor_name: 'Ayşe Yılmaz', system: false, version: 7,
        detail: { incident_ids: [9], incident_id: 9, refresh: true, unpinned: ['topic', 'impact', 'bogus'], changes: [] } },
      { id: 2, kind: 'INCIDENT_SYNC', at: '2026-10-13T09:00:00Z', actor_name: 'SiteMonitor', system: true, version: 6,
        detail: { incident_ids: [9], incident_id: 9, changes: [{ section: 'Anormal Artış Gösteren Hatalar', label: 'Konu', type: 'text', op: 'change',
          before: 'Ödeme', after: 'Ödeme API kesintisi' }] } },
      { id: 1, kind: 'INCIDENT_IMPORT', at: '2026-10-13T08:00:00Z', actor_name: 'Ayşe Yılmaz', system: false, version: 5,
        detail: { incident_ids: [9, 11], section_id: 'issues', issues_added: 2, images_added: 1,
          images: [{ slot: 'iss:issues:i-9:1', incident_id: 9, size_bytes: 1200 }], changes: [] } },
    ] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Değişiklik geçmişi' }))
    const sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    await within(sheet).findByText('Olay kaydından konu alındı')
    expect(within(sheet).getAllByText('Olay kaydından güncellendi')).toHaveLength(2)
    expect(within(sheet).getByText('Olay kaydıyla bağ koparıldı')).toBeInTheDocument()
    expect(sheet.querySelector('[data-kind="INCIDENT_IMPORT"] [data-slot="te-history-incident-ids"]').textContent).toBe('Olay kayıtları: #9, #11')
    const syncs = [...sheet.querySelectorAll('[data-kind="INCIDENT_SYNC"]')]
    expect(syncs).toHaveLength(2)
    for (const el of syncs) expect(el.querySelector('[data-slot="te-history-incident-ids"]').textContent).toBe('Olay kaydı: #9')
    // Otomatik eşitleme ile elle güncelleme ayrılır; geri döndürülen alanlar okunur adlarla (bilinmeyen grup yazılmaz)
    expect(syncs[0].querySelector('[data-slot="te-history-incident-mode"]').textContent).toBe('Olay kaydından elle güncellendi')
    expect(syncs[0].querySelector('[data-slot="te-history-incident-unpinned"]').textContent)
      .toBe('Olaydaki değere döndürülen alanlar: Konu, Müşteri etkisi')
    expect(syncs[1].querySelector('[data-slot="te-history-incident-mode"]').textContent).toBe('Olay kaydı değiştiği için kendiliğinden eşitlendi')
    expect(syncs[1].textContent).toContain('Ödeme API kesintisi')
    const imp = sheet.querySelector('[data-kind="INCIDENT_IMPORT"]')
    expect(imp.querySelector('[data-slot="te-history-incident-issues"]').textContent).toBe('2 konu eklendi')
    expect(imp.querySelector('[data-slot="te-history-incident-images"]').textContent).toBe('1 görsel kopyalandı')
    // Özet satırı olan olayda 'alan ayrıntısı yok' yazılmaz
    expect(imp.textContent).not.toContain('Alan ayrıntısı yok.')
    expect(sheet.textContent).not.toContain('bogus')
    expect(sheet.querySelector('[data-kind="INCIDENT_UNLINK"]').textContent).toContain('Konu: Ödeme API kesintisi')
    expect(sheet.textContent).not.toContain('INCIDENT_')
  })
})
