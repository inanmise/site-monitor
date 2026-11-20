import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { reportResponse, TEMPLATE } from './helpers/techErrorFixtures.js'
import { closedMonths, monthValue } from '../components/techerror/incidents/incidentImportModel.js'

// Ters giriş (2026-11-20): olay kaydı ayrıntısında "Teknik hata raporuna ekle" (kanal + kapanmış ay → o ayın raporuna konu,
// rapor yoksa oluşturulur, sonra rapora gidilir) ve "Kullanıldığı raporlar" kartı (yalnız ilk açılışta okunur). Okuma hatası
// "kanal yok" / "kullanılmıyor" demez.
const perm = vi.hoisted(() => ({ edit: true, read: true, incidents: true, scope: null }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {}, scope: perm.scope, loaded: true, failed: false, refresh: () => {},
    canView: (r) => (r === 'tech_error_reports.read' ? perm.read : r === 'incidents.view' ? perm.incidents : false),
    canEdit: (r) => (r === 'tech_error_reports.crud' ? perm.edit : false),
    canExecute: () => false,
  }),
  PermissionsProvider: ({ children }) => children,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), createReport: vi.fn(), importIncidents: vi.fn(),
      incidentUsage: vi.fn(),
    },
  }),
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({ default: ({ value }) => <div data-slot="md-view">{value}</div> }))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import AddToReportDialog from '../components/incidenthistory/AddToReportDialog.jsx'
import HistoryDetailSheet from '../components/incidenthistory/HistoryDetailSheet.jsx'

const te = () => api.techErrors
const pad = (n) => String(n).padStart(2, '0')
// Tarihten bağımsız: iki ay önce başlayıp bitmiş olay (kapanmış ay)
const YM = closedMonths(Date.now(), 2)[1]
const MONTH = monthValue(YM)
const incident = (over = {}) => ({
  id: 9, title: 'Ödeme API kesintisi', occurred_at: `${YM.year}-${pad(YM.month)}-12T11:00:00`,
  resolved_at: `${YM.year}-${pad(YM.month)}-12T12:30:00`, severity: 'CRITICAL', status: 'RESOLVED', category: 'NETWORK',
  team_id: 1, team_name: 'Dijital Kanallar', sla_breached: false, ...over,
})
const ch = (id, name, teamId, extra = {}) => ({ id, name, team_id: teamId, team_name: teamId === 1 ? 'Dijital Kanallar' : 'Kurumsal',
  active: true, can_report: true, can_edit: true, ...extra })
const overviewRes = (channels) => ({ success: true, data: { period: YM, teams: [], channels, can_create: true } })
const ISSUES = { id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}', allow_images: true,
  ask_time: true, ask_outage: true, ask_impact: true, no_impact_display: 'hide_count' }
const TPL = { ...TEMPLATE, sections: [ISSUES] }
const detail = (id, extra = {}) => reportResponse({ template: TPL,
  report: { ...reportResponse().data.report, id, year: YM.year, month: YM.month, version: 4 },
  values: { fields: {}, issues: {}, texts: {} }, incident_links: [], ...extra })
const dialog = () => screen.findByRole('dialog', { name: 'Teknik hata raporuna ekle' })
const submit = () => document.querySelector('[data-slot="inc-to-report-submit"]')

let navEvents = []
const onNav = (e) => navEvents.push(e.detail)

beforeEach(() => {
  vi.clearAllMocks()
  perm.edit = true
  perm.read = true
  perm.incidents = true
  perm.scope = null
  navEvents = []
  window.addEventListener('sm:navigate', onNav)
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overviewRes([ch(10, 'Mobil', 1), ch(20, 'POS', 2), ch(30, 'Pasif kanal', 1, { active: false })]))
  te().reports.mockResolvedValue({ success: true, data: { reports: [{ id: 100, year: YM.year, month: YM.month }] } })
  te().report.mockImplementation(async (id) => detail(Number(id)))
  te().template.mockResolvedValue({ success: true, data: { template: TPL, version: 3 } })
  te().importIncidents.mockImplementation(async (id) => ({ ...detail(Number(id)), data: { ...detail(Number(id)).data,
    imported: [{ incident_id: 9, issue_id: 'i-9', notes: [] }] } }))
})
afterEach(() => window.removeEventListener('sm:navigate', onNav))

describe('olay kaydından rapora — pencere', () => {
  it('yalnız olayın takımının kanalı; tek kanal kendiliğinden seçili, ay olayın ayı; var olan rapora eklenir ve rapora gidilir', async () => {
    const onClose = vi.fn()
    render(<AddToReportDialog incident={incident()} onClose={onClose} />)
    const dlg = await dialog()
    const select = await within(dlg).findByLabelText('Kanal')
    const options = [...select.querySelectorAll('option')].map((o) => o.textContent)
    expect(options).toEqual(['Kanal seçin', 'Mobil · Dijital Kanallar'])
    await waitFor(() => expect(select.value).toBe('10'))
    expect(within(dlg).getByLabelText('Rapor ayı').value).toBe(MONTH)
    await waitFor(() => expect(dlg.querySelector('[data-slot="inc-to-report-target"]')?.getAttribute('data-exists')).toBe('true'))
    expect(te().reports).toHaveBeenCalledWith('10')
    expect(dlg.textContent).toContain('Bu ayın raporu var')
    expect(dlg.textContent).toContain('ilk iki uygun görseli')
    expect(submit()).not.toBeDisabled()
    fireEvent.click(submit())
    await waitFor(() => expect(te().importIncidents).toHaveBeenCalledTimes(1))
    expect(te().createReport).not.toHaveBeenCalled()
    expect(te().importIncidents).toHaveBeenCalledWith(100, { version: 4, section_id: 'issues', incident_ids: [9],
      parts: ['rca', 'impact', 'resolution'], images: true })
    await waitFor(() => expect(navEvents).toEqual([{ tab: 'channelreports', params: { te_view: 'report', te_rep: '100', te_ch: '10' } }]))
    expect(onClose).toHaveBeenCalled()
    expect(await screen.findByText('Olay kaydı rapora eklendi.')).toBeInTheDocument()
  })

  it('o ayın raporu yoksa oluşturulur, sonra güncel sürümle içe alınır', async () => {
    te().reports.mockResolvedValue({ success: true, data: { reports: [] } })
    te().createReport.mockResolvedValue({ success: true, data: { id: 101 } })
    render(<AddToReportDialog incident={incident()} onClose={() => {}} />)
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="inc-to-report-target"]')?.getAttribute('data-exists')).toBe('false'))
    expect(te().template).toHaveBeenCalledWith('10')
    expect(dlg.textContent).toContain('Bu ay için henüz rapor yok')
    fireEvent.click(submit())
    await waitFor(() => expect(te().importIncidents).toHaveBeenCalledTimes(1))
    expect(te().createReport).toHaveBeenCalledWith(10, YM.year, YM.month)
    expect(te().report).toHaveBeenCalledWith(101)
    expect(te().importIncidents.mock.calls[0][0]).toBe(101)
    await waitFor(() => expect(navEvents[0]?.params?.te_rep).toBe('101'))
  })

  it('olay bu rapora zaten alınmışsa bilgi + "Raporu aç"; ekle kapalı', async () => {
    te().report.mockImplementation(async (id) => detail(Number(id), { incident_links: [{ section_id: 'issues', issue_id: 'i-9',
      incident_id: 9, state: 'ok', title: 'Ödeme', pinned: [], notes: [] }] }))
    render(<AddToReportDialog incident={incident()} onClose={() => {}} />)
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="inc-to-report-open-existing"]')).not.toBeNull())
    expect(dlg.textContent).toContain('zaten konu olarak alınmış')
    expect(submit()).toBeDisabled()
    fireEvent.click(dlg.querySelector('[data-slot="inc-to-report-open-existing"]'))
    expect(navEvents).toEqual([{ tab: 'channelreports', params: { te_view: 'report', te_rep: '100', te_ch: '10' } }])
  })

  it('sürmekte olan olayda ay seçili gelmez; kullanıcı seçer', async () => {
    render(<AddToReportDialog incident={incident({ resolved_at: null })} onClose={() => {}} />)
    const dlg = await dialog()
    const month = await within(dlg).findByLabelText('Rapor ayı')
    expect(month.value).toBe('')
    expect(dlg.textContent).toContain('Yalnız kapanmış aylar seçilebilir')
    expect(submit()).toBeDisabled()
    fireEvent.change(month, { target: { value: MONTH } })
    await waitFor(() => expect(submit()).not.toBeDisabled())
  })

  it('kanal listesi okunamazsa "kanal yok" denmez; yeniden dene; 403 erişim yok', async () => {
    te().overview.mockResolvedValueOnce({ success: false, status: 500, error: 'iç hata' })
    const { unmount } = render(<AddToReportDialog incident={incident()} onClose={() => {}} />)
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="inc-to-report-failed"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="inc-to-report-nochannel"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="inc-to-report-target"]')).not.toBeNull())
    unmount()

    te().overview.mockResolvedValueOnce({ success: false, status: 403, error: 'yok' })
    render(<AddToReportDialog incident={incident()} onClose={() => {}} />)
    const dlg2 = await dialog()
    await waitFor(() => expect(dlg2.querySelector('[data-slot="inc-to-report-failed"]')?.getAttribute('data-kind')).toBe('forbidden'))
    expect(within(dlg2).queryByRole('button', { name: 'Tekrar dene' })).toBeNull()
  })

  it('sunucu reddi kendi cümlesiyle (bölüm dolu / başka takımın olayı / olay bulunamadı) — ham kod yazılmaz', async () => {
    const cases = [
      [{ success: false, status: 400, code: 'SECTION_FULL', field: 'incident_ids', error: 'x' }, 'Bölüm dolu'],
      [{ success: false, status: 400, code: 'INCIDENT_OTHER_TEAM', field: 'incident_ids', error: 'x' }, 'bu kanalın takımına ait değil'],
      [{ success: false, status: 400, code: 'INCIDENT_NOT_FOUND', field: 'incident_ids', error: 'x' }, 'bulunamadı ya da artık görme yetkiniz yok'],
      [{ success: false, status: 409, code: 'INCIDENT_ALREADY_IMPORTED', incident_id: 9, error: 'x' }, 'zaten konu olarak alınmış'],
      [{ success: false, status: 429, code: 'IMPORT_BUSY', retry_after_seconds: 5, error: 'x' }, 'görselli olay içe almaları dolu'],
    ]
    for (const [res, text] of cases) {
      te().importIncidents.mockResolvedValueOnce(res)
      const { unmount } = render(<AddToReportDialog incident={incident()} onClose={() => {}} />)
      const dlg = await dialog()
      await waitFor(() => expect(submit()).not.toBeDisabled())
      fireEvent.click(submit())
      const err = await waitFor(() => {
        const el = dlg.querySelector('[data-slot="inc-to-report-error"]')
        expect(el).not.toBeNull()
        return el
      })
      expect(err.textContent, res.code).toContain(text)
      expect(err.textContent).not.toMatch(/[A-Z]{3,}_[A-Z_]+/)
      expect(dlg.querySelector('[data-slot="inc-to-report-open"]')).not.toBeNull()
      // Meşgul hata değil: bilgi tonu ("Biraz bekleyin"); diğerleri tehlike
      expect(err.closest('[data-slot="alert"]').getAttribute('data-tone'), res.code).toBe(res.code === 'IMPORT_BUSY' ? 'info' : 'danger')
      unmount()
    }
    expect(navEvents).toEqual([])
  })

  it('takımı olmayan olay: kanal okunmaz, hiçbir kanal listelenmez, kendi cümlesi (sunucu her zaman reddederdi)', async () => {
    render(<AddToReportDialog incident={incident({ team_id: null, team_name: null })} onClose={() => {}} />)
    const dlg = await dialog()
    const note = dlg.querySelector('[data-slot="inc-to-report-nochannel"]')
    expect(note).not.toBeNull()
    expect(note.getAttribute('data-reason')).toBe('no_team')
    expect(note.textContent).toBe('Bu olay kaydının takımı yok; rapora yalnız kanalın takımına ait olay kayıtları eklenebilir. '
      + 'Önce olay kaydını düzenleyip takımını seçin.')
    // Hiçbir istek gitmez: kanal listesi okunmaz, rapor OLUŞTURULMAZ, içe alma denenmez
    await new Promise((r) => setTimeout(r, 30))
    expect(te().overview).not.toHaveBeenCalled()
    expect(te().createReport).not.toHaveBeenCalled()
    expect(te().importIncidents).not.toHaveBeenCalled()
    expect(dlg.querySelector('#inc-to-report-channel')).toBeNull()
    expect(submit()).toBeDisabled()
  })

  it('takımın kanalı yoksa açıkça söylenir', async () => {
    te().overview.mockResolvedValue(overviewRes([ch(20, 'POS', 2)]))
    render(<AddToReportDialog incident={incident()} onClose={() => {}} />)
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="inc-to-report-nochannel"]')).not.toBeNull())
    expect(dlg.textContent).toContain('Bu olayın takımının, rapor yazabileceğiniz etkin bir kanalı yok.')
    expect(dlg.querySelector('[data-slot="inc-to-report-nochannel"]').getAttribute('data-reason')).toBe('no_channel')
  })

  it('sonucu bilinmeyen içe alma körlemesine yinelenmez: pencerede ileti + "Raporu aç"', async () => {
    te().importIncidents.mockResolvedValue({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'zaman aşımı' })
    render(<AddToReportDialog incident={incident()} onClose={() => {}} />)
    const dlg = await dialog()
    await waitFor(() => expect(submit()).not.toBeDisabled())
    fireEvent.click(submit())
    await waitFor(() => expect(dlg.querySelector('[data-slot="inc-to-report-error"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="inc-to-report-error"]').textContent).toContain('Eklemenin sonucu bilinmiyor')
    expect(dlg.querySelector('[data-slot="inc-to-report-open"]')).not.toBeNull()
    expect(te().importIncidents).toHaveBeenCalledTimes(1)
    expect(navEvents).toEqual([])
  })
})

describe('olay ayrıntısı — giriş noktaları', () => {
  const rec = (over = {}) => ({ id: 9, title: 'Ödeme API kesintisi', occurred_at: '2026-09-12T11:00:00', severity: 'CRITICAL',
    status: 'RESOLVED', category: 'NETWORK', team_id: 1, team_name: 'Dijital Kanallar', sla_breached: false, ...over })

  it('"Teknik hata raporuna ekle" rapor yazma izniyle; modül kapalıysa ya da izin yoksa yok', async () => {
    const { unmount } = render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    const btn = document.querySelector('[data-slot="ih-add-to-report"]')
    expect(btn).not.toBeNull()
    fireEvent.click(btn)
    expect(await dialog()).toBeInTheDocument()
    unmount()

    perm.edit = false
    const r2 = render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    expect(document.querySelector('[data-slot="ih-add-to-report"]')).toBeNull()
    r2.unmount()

    perm.edit = true
    perm.scope = { channel_reports: false }
    const r3 = render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    expect(document.querySelector('[data-slot="ih-add-to-report"]')).toBeNull()
    expect(document.querySelector('[data-slot="ih-usage"]')).toBeNull()
    r3.unmount()

    // Sunucunun içe alma ve kullanım uçları olay kayıtlarını görme iznini de ister
    perm.scope = null
    perm.incidents = false
    render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    expect(document.querySelector('[data-slot="ih-add-to-report"]')).toBeNull()
    expect(document.querySelector('[data-slot="ih-usage"]')).toBeNull()
  })

  it('"Kullanıldığı raporlar" kapalı başlar, ilk açılışta BİR kez okunur; satır raporu açar', async () => {
    te().incidentUsage.mockResolvedValue({ success: true, data: { items: [
      { report_id: 100, channel_id: 10, channel_name: 'Mobil', team_name: 'Dijital Kanallar', year: 2026, month: 9, status: 'sent',
        topic: 'Ödeme API kesintisi', state: 'stale' },
      // hidden (bu kartta): olay başka bir takıma taşındı — o rapor artık izlemiyor (kendi cümlesi; "görüntülenemiyor" değil)
      { report_id: 101, channel_id: 11, channel_name: 'POS', team_name: 'Dijital Kanallar', year: 2026, month: 8, status: 'draft',
        topic: 'POS kesintisi', state: 'hidden' },
    ] } })
    render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    expect(document.querySelector('[data-slot="ih-usage"]')).not.toBeNull()
    expect(te().incidentUsage).not.toHaveBeenCalled()
    const toggle = document.querySelector('[data-slot="ih-usage-toggle"]')
    fireEvent.click(toggle)
    await waitFor(() => expect(document.querySelector('[data-slot="ih-usage-row"]')).not.toBeNull())
    expect(te().incidentUsage).toHaveBeenCalledWith(9)
    const [row, hiddenRow] = document.querySelectorAll('[data-slot="ih-usage-row"]')
    expect(row.textContent).toContain('Mobil · Eylül 2026')
    expect(row.textContent).toContain('Konu: Ödeme API kesintisi')
    expect(row.querySelector('[data-slot="ih-usage-state"]').textContent).toBe('Olay değişti')
    expect(hiddenRow.querySelector('[data-slot="ih-usage-state"]').getAttribute('data-state')).toBe('hidden')
    expect(hiddenRow.querySelector('[data-slot="ih-usage-state"]').textContent)
      .toBe('Olay başka bir takıma taşındı; bu rapor artık onu izlemiyor')
    expect(hiddenRow.textContent).toContain('POS · Ağustos 2026')
    fireEvent.click(toggle)
    fireEvent.click(toggle)
    expect(te().incidentUsage).toHaveBeenCalledTimes(1)
    // Kapatıp açınca içerik yeniden kurulur: satır yeniden bulunur (eski düğüm belgeden kopmuştur)
    const again = await waitFor(() => {
      const el = document.querySelector('[data-slot="ih-usage-row"] [data-slot="ih-usage-open"]')
      expect(el).not.toBeNull()
      return el
    })
    fireEvent.click(again)
    expect(navEvents).toEqual([{ tab: 'channelreports', params: { te_view: 'report', te_rep: '100', te_ch: '10' } }])
  })

  it('kullanım listesi okunamazsa "kullanılmıyor" denmez (yeniden dene); eski sürüm nötr; boş liste açıkça', async () => {
    te().incidentUsage.mockResolvedValueOnce({ success: false, status: 0, code: 'NETWORK_ERROR' })
    const { unmount } = render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    fireEvent.click(document.querySelector('[data-slot="ih-usage-toggle"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-usage-failed"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="ih-usage-empty"]')).toBeNull()
    te().incidentUsage.mockResolvedValueOnce({ success: true, data: { items: [] } })
    fireEvent.click(document.querySelector('[data-slot="ih-usage-retry"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-usage-empty"]')).not.toBeNull())
    unmount()

    te().incidentUsage.mockResolvedValueOnce({ success: false, status: 405 })
    const r2 = render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    fireEvent.click(document.querySelector('[data-slot="ih-usage-toggle"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-usage-failed"]')?.getAttribute('data-kind')).toBe('unsupported'))
    expect(document.querySelector('[data-slot="ih-usage-retry"]')).toBeNull()
    r2.unmount()

    // Kodlu 404 eski sürüm DEĞİL: olay bulunamadı / görülemiyor (nötr, yeniden dene yok)
    te().incidentUsage.mockResolvedValueOnce({ success: false, status: 404, code: 'INCIDENT_NOT_FOUND', error: 'x' })
    render(<HistoryDetailSheet record={rec()} onClose={() => {}} />)
    fireEvent.click(document.querySelector('[data-slot="ih-usage-toggle"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-usage-failed"]')?.getAttribute('data-kind')).toBe('incident_gone'))
    expect(document.querySelector('[data-slot="ih-usage-failed"]').textContent).toContain('Olay kaydı bulunamadı')
    expect(document.querySelector('[data-slot="ih-usage-failed"]').textContent).not.toContain('bu sürüm')
    expect(document.querySelector('[data-slot="ih-usage-retry"]')).toBeNull()
  })
})
