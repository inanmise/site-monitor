import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Olay kaydından anomali konusu (2026-11-20): içe alma penceresi (aday listesi, seçim sınırları, önizleme) ve düzenleyiciyle
// bağlantı (düğmenin görünürlüğü, önce kaydet, yanıtın yeniden yükleme gibi uygulanması, yeni konuya odak, çakışmada yeniden
// okuma). Sunucu yanıtlarının şekli `olay-ice-alma-api.md` sözleşmesidir.
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
      deleteImage: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
      incidentCandidates: vi.fn(), incidentImportPreview: vi.fn(), importIncidents: vi.fn(), syncIncidentLinks: vi.fn(),
      refreshIncidentLink: vi.fn(), unlinkIncident: vi.fn(), incidentUsage: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import IncidentImportDialog from '../components/techerror/incidents/IncidentImportDialog.jsx'

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
const SECTIONS = [{ id: 'issues', title: 'Anormal Artış Gösteren Hatalar',
  opts: { askTime: true, askOutage: true, askImpact: true, allowImages: true, legacy: false } }]

const cand = (id, title, extra = {}) => ({
  id, title, occurred_at: '2026-09-12T11:00:00', resolved_at: '2026-09-12T12:30:00', status: 'RESOLVED', severity: 'HIGH',
  channel: 'Mobil', tech_error_count: 1250, outage_related: true, affected_customers: 320, imported: false, ...extra,
})
const candidates = (items, total = items.length) => ({ success: true, data: { items, total, page: 0, size: 10, period: { year: 2026, month: 9 } } })
const proposal = (id, title, extra = {}) => ({
  incident_id: id, title,
  fields: { topic: title, date: '2026-09-12', start: '14:00', end: '15:30', end_next_day: false, errors: 1250, outage: true,
    outage_id: 'INC-2026-0915', impact: true, customers: 320 },
  description_preview: '**Kök neden**\nVeritabanı bağlantı havuzu doldu.', notes: [], hidden: false,
  images: { copy: 1, skipped: [{ reason: 'format' }] }, ...extra,
})
const topicFrom = (id, name) => ({ id, topic: name, title: '', title_auto: true, description: '**Kök neden**\nDB', date: '2026-09-12',
  start: '14:00', end: '15:30', errors: 1250, outage: true, outage_id: 'INC-1', impact: true, customers: 320 })
const link = (iid, incidentId, extra = {}) => ({
  section_id: 'issues', issue_id: iid, incident_id: incidentId, state: 'ok', title: `Olay ${incidentId}`, status: 'RESOLVED',
  occurred_at: '2026-09-12T11:00:00', pinned: [], notes: [], synced_at: '2026-10-01T08:00:00', incident_updated_at: '2026-09-30T08:00:00',
  ...extra,
})
const baseReport = reportResponse().data.report
const respond = ({ issues = null, extra = {}, report = {} } = {}) => reportResponse({ template: TPL, report: { ...baseReport, ...report },
  values: { fields: { 'error-count': 5000 }, issues: issues ? { issues } : {}, texts: {} }, incident_links: [], ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-18T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })
const dialog = () => screen.findByRole('dialog', { name: 'Olay kaydından konu al' })
const rowOf = (id) => document.querySelector(`[data-slot="te-inc-row"][data-incident="${id}"]`)
const check = (id) => document.querySelector(`[data-inc-check="${id}"]`)
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
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
  te().incidentCandidates.mockResolvedValue(candidates([cand(9, 'Ödeme API kesintisi'), cand(11, 'Kart doğrulama gecikmesi'),
    cand(12, 'Zaten alınmış olay', { imported: true })]))
  te().incidentImportPreview.mockResolvedValue({ success: true, data: { proposals: [proposal(9, 'Ödeme API kesintisi')] } })
})

function renderDialog(props = {}) {
  const onImport = props.onImport || vi.fn(async () => ({ ok: true }))
  const onClose = props.onClose || vi.fn()
  render(<IncidentImportDialog open reportId="100" sections={SECTIONS} initialSectionId="issues" counts={{ issues: 0 }}
    importedIds={new Set()} period={{ year: 2026, month: 9 }} onClose={onClose} onImport={onImport} {...props} />)
  return { onImport, onClose }
}

describe('içe alma penceresi — aday listesi', () => {
  it('kanalın ayındaki olayları listeler; bu raporda olan rozetli ve seçilemez; sayılar düz cümle', async () => {
    renderDialog({ importedIds: new Set([11]) })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    expect(te().incidentCandidates).toHaveBeenCalledWith('100', { q: '', page: 0, size: 10, overlap: false }, expect.anything())
    expect(dlg.textContent).toContain('Eylül 2026')
    expect(rowOf(9).textContent).toContain('1.250 teknik hata')
    expect(rowOf(9).textContent).toContain('Kesinti kaynaklı')
    expect(rowOf(9).textContent).toContain('320 müşteri etkilendi')
    // Sunucunun `imported` bayrağı ve düzenleyicinin bildiği bağlar: ikisi de "bu raporda"
    expect(rowOf(12).getAttribute('data-imported')).toBe('true')
    expect(rowOf(11).getAttribute('data-imported')).toBe('true')
    expect(check(12)).toBeDisabled()
    expect(check(11)).toBeDisabled()
    expect(within(rowOf(12)).getByText('Bu raporda')).toBeInTheDocument()
    expect(check(9)).not.toBeDisabled()
    // Canlı bağ notu ve varsayılan açıklama parçaları (kök neden, iş etkisi, çözüm)
    expect(dlg.querySelector('[data-slot="te-inc-live-note"]')).not.toBeNull()
    expect(within(dlg).getByRole('checkbox', { name: 'Kök neden' })).toBeChecked()
    expect(within(dlg).getByRole('checkbox', { name: 'Teknik açıklama' })).not.toBeChecked()
    expect(within(dlg).getByRole('checkbox', { name: 'Görselleri de al' })).toBeChecked()
  })

  it('arama gecikmeli: hızlı yazmak tek istek gönderir; önceki aydan sürenler anahtarı isteğe girer', async () => {
    renderDialog()
    await dialog()
    await waitFor(() => expect(te().incidentCandidates).toHaveBeenCalledTimes(1))
    const box = screen.getByLabelText('Olay kaydı ara')
    for (const v of ['ö', 'öd', 'ödeme']) fireEvent.change(box, { target: { value: v } })
    await waitFor(() => expect(te().incidentCandidates).toHaveBeenCalledTimes(2))
    expect(te().incidentCandidates.mock.calls[1][1]).toMatchObject({ q: 'ödeme', page: 0 })
    fireEvent.click(screen.getByRole('switch', { name: 'Önceki aydan süren olayları da göster' }))
    await waitFor(() => expect(te().incidentCandidates).toHaveBeenCalledTimes(3))
    expect(te().incidentCandidates.mock.calls[2][1]).toMatchObject({ q: 'ödeme', overlap: true })
  })

  it('boş liste ile okunamayan liste ayrıdır: okuma hatası "olay yok" demez, yeniden dener', async () => {
    te().incidentCandidates.mockResolvedValueOnce({ success: false, status: 0, code: 'NETWORK_ERROR', error: 'ağ' })
    renderDialog()
    const dlg = await dialog()
    const failed = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="te-inc-list-failed"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(failed.getAttribute('data-kind')).toBe('network')
    expect(dlg.querySelector('[data-slot="te-inc-empty"]')).toBeNull()
    expect(failed.textContent).toContain('Olay kayıtları okunamadı')
    te().incidentCandidates.mockResolvedValueOnce(candidates([]))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-retry"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-empty"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="te-inc-empty"]').textContent).toContain('Bu ayda başlayan')
  })

  it('bozuk yanıt da okunamadı sayılır; 405 = bu sürümde yok (yeniden dene yok)', async () => {
    te().incidentCandidates.mockResolvedValueOnce({ success: true, data: {} })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-list-failed"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="te-inc-list-failed"]').getAttribute('data-kind')).toBe('error')
    expect(dlg.querySelector('[data-slot="te-inc-retry"]')).not.toBeNull()

    te().incidentCandidates.mockResolvedValueOnce({ success: false, status: 405, error: 'x' })
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-retry"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-list-failed"]')?.getAttribute('data-kind')).toBe('unsupported'))
    expect(dlg.querySelector('[data-slot="te-inc-retry"]')).toBeNull()
    expect(dlg.textContent).toContain('Sunucunun bu sürümü olay kaydından konu almayı bilmiyor')
  })

  it('kodsuz 404 eski sürümdür: "bu sürümde yok", yeniden dene yok', async () => {
    te().incidentCandidates.mockResolvedValueOnce({ success: false, status: 404 })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-list-failed"]')?.getAttribute('data-kind')).toBe('unsupported'))
    expect(dlg.querySelector('[data-slot="te-inc-retry"]')).toBeNull()
  })

  it('genel NOT_FOUND: rapor yok ya da sürüm bilmiyor — ikisi birlikte söylenir; 400 q kendi cümlesiyle', async () => {
    te().incidentCandidates.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'Kayıt yok' })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-list-failed"]')?.getAttribute('data-kind')).toBe('missing'))
    expect(dlg.textContent).toContain('Şu an kullanılamıyor')
    expect(dlg.textContent).toContain('rapor silinmiş olabilir ya da sunucu bu özelliği henüz tanımıyor')
    expect(dlg.textContent).not.toContain('NOT_FOUND')
  })

  it('400 q: aramanın sınırı kendi cümlesiyle (sunucunun alan kodu yazılmaz)', async () => {
    te().incidentCandidates.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'q', error: 'x' })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-list-failed"]')?.getAttribute('data-kind')).toBe('query'))
    expect(dlg.textContent).toContain('Arama en çok 200 karakter olabilir')
    expect(dlg.textContent).not.toContain('VALIDATION_FAILED')
  })

  it('sunucu ayın yalnız ilk 2000 olayında aradıysa liste altında not; aramasız / kesilmemişte yok', async () => {
    te().incidentCandidates.mockResolvedValueOnce({ success: true, data: { items: [cand(9, 'Ödeme API kesintisi')], total: 1, page: 0,
      size: 10, period: { year: 2026, month: 9 }, truncated: true } })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    const note = dlg.querySelector('[data-slot="te-inc-truncated"]')
    expect(note).not.toBeNull()
    expect(note.textContent).toContain('yalnız ilk 2.000 olay kaydında arandı')
  })

  it('kesilmemiş listede not çizilmez', async () => {
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    expect(dlg.querySelector('[data-slot="te-inc-truncated"]')).toBeNull()
  })

  it('bölümde kalan yer kadar seçilir; üçüncü olay nedeniyle devre dışı; bölüm doluysa önizleme kapalı', async () => {
    te().incidentCandidates.mockResolvedValue(candidates([cand(9, 'A'), cand(11, 'B'), cand(13, 'C')]))
    renderDialog({ counts: { issues: 28 } })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(13)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(check(11))
    expect(dlg.querySelector('[data-slot="te-inc-selected"]').textContent).toBe('2 olay seçildi')
    expect(check(13)).toBeDisabled()
    // Neden görünür metinde (title değil): sınır notuna bağlı
    expect(check(13).getAttribute('aria-describedby')).toBe('te-inc-limit-note')
    expect(document.getElementById('te-inc-limit-note').textContent).toContain('Bölümde kalan 2 konu yerinin hepsi seçildi')
    // Seçili olan bırakılabilir
    fireEvent.click(check(9))
    expect(check(13)).not.toBeDisabled()
  })

  it('bölüm doluysa uyarı ve önizleme düğmesi kapalı', async () => {
    renderDialog({ counts: { issues: 30 } })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    expect(dlg.textContent).toContain('Bu bölümde yer kalmadı')
    expect(check(9)).toBeDisabled()
    expect(dlg.querySelector('[data-slot="te-inc-next"]')).toBeDisabled()
  })
})

describe('içe alma penceresi — önizleme ve içe alma', () => {
  it('önizleme dolacak / boş kalacak alanları, notları ve görselleri gösterir; gövde sözleşmedeki gibi', async () => {
    te().incidentImportPreview.mockResolvedValue({ success: true, data: { proposals: [
      proposal(9, 'Ödeme API kesintisi', { notes: ['topic_clipped', 'brand_new_code'] }),
      proposal(11, 'Kart doğrulama gecikmesi', {
        fields: { topic: 'Kart doğrulama gecikmesi', date: null, start: null, end: null, end_next_day: false, errors: null,
          outage: null, outage_id: null, impact: false, customers: null },
        description_preview: '', notes: ['started_before_month', 'will_be_hidden'], hidden: true, images: { copy: 0, skipped: [] },
      }),
    ] } })
    const { onImport } = renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(11)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(check(11))
    fireEvent.click(within(dlg).getByRole('checkbox', { name: 'Teknik açıklama' }))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-inc-proposal"]')).toHaveLength(2))
    expect(te().incidentImportPreview).toHaveBeenCalledWith('100', { section_id: 'issues', incident_ids: [9, 11],
      parts: ['rca', 'impact', 'resolution', 'technical'], images: true })
    const first = dlg.querySelector('[data-slot="te-inc-proposal"][data-incident="9"]')
    const filled = [...first.querySelectorAll('[data-slot="te-inc-pv-row"]')].map((r) => [r.getAttribute('data-group'), r.getAttribute('data-filled')])
    expect(filled).toEqual([['topic', 'true'], ['date', 'true'], ['time', 'true'], ['errors', 'true'], ['outage', 'true'], ['impact', 'true']])
    expect(first.textContent).toContain('12 Eylül 2026')
    expect(first.textContent).toContain('14:00–15:30')
    expect(first.textContent).toContain('Kesinti kaynaklı (Kesinti ID: INC-2026-0915)')
    expect(first.textContent).toContain('Var — 320 müşteri')
    expect(first.textContent).toContain('1 görsel kopyalanacak')
    expect(first.textContent).toContain('Biçimi desteklenmiyor (yalnız PNG ve JPEG).')
    // Bilinmeyen not kodu ham yazılmaz
    expect(first.textContent).toContain('Başlık 120 karakteri aştığı için kısaltıldı.')
    expect(first.textContent).toContain('Sunucu bu konu için bir not bıraktı.')
    expect(first.textContent).not.toContain('brand_new_code')
    const second = dlg.querySelector('[data-slot="te-inc-proposal"][data-incident="11"]')
    expect(second.getAttribute('data-hidden')).toBe('true')
    expect(second.querySelector('[data-slot="te-inc-pv-hidden"]')).not.toBeNull()
    const empty = [...second.querySelectorAll('[data-slot="te-inc-pv-row"][data-filled="false"]')].map((r) => r.getAttribute('data-group'))
    expect(empty).toEqual(['date', 'time', 'errors', 'outage'])
    expect(second.textContent).toContain('Açıklama boş kalır')
    expect(second.textContent).toContain('Olay raporun ayından önce başladı')

    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(onImport).toHaveBeenCalledTimes(1))
    expect(onImport).toHaveBeenCalledWith({ section_id: 'issues', incident_ids: [9, 11], parts: ['rca', 'impact', 'resolution', 'technical'], images: true })
  })

  it('önizleme okunamazsa "yeniden dene"; geri dönünce seçim korunur', async () => {
    te().incidentImportPreview.mockResolvedValueOnce({ success: false, status: 500, code: 'INTERNAL', error: 'Önizleme sunucuda hazırlanamadı (iç hata).' })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-preview-failed"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="te-inc-import-confirm"]')).toBeDisabled()
    expect(dlg.textContent).toContain('Önizleme sunucuda hazırlanamadı (iç hata).')
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-preview-retry"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-back"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('pick'))
    expect(check(9)).toBeChecked()
  })

  it('olay arada bu rapora alınmışsa satır "bu raporda" olur, seçimden düşer, ileti pencerede', async () => {
    const onImport = vi.fn(async () => ({ ok: false, kind: 'already', incidentId: 9, message: 'Bu olay kaydı bu rapora zaten alınmış; seçimden çıkarıldı.' }))
    renderDialog({ onImport })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('pick'))
    expect(dlg.querySelector('[data-slot="te-inc-import-error"]').textContent).toContain('zaten alınmış')
    expect(rowOf(9).getAttribute('data-imported')).toBe('true')
    expect(check(9)).toBeDisabled()
    expect(dlg.querySelector('[data-slot="te-inc-selected"]').textContent).toBe('Henüz olay seçilmedi')
  })

  it('önizleme 409 INCIDENT_ALREADY_IMPORTED: seçime döner, satır "bu raporda", ileti olay numarasıyla', async () => {
    te().incidentImportPreview.mockResolvedValueOnce({ success: false, status: 409, code: 'INCIDENT_ALREADY_IMPORTED', incident_id: 9,
      error: 'Olay kaydı #9 bu rapora zaten alınmış.' })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('pick'))
    expect(dlg.querySelector('[data-slot="te-inc-import-error"]').textContent).toBe(
      'Olay kaydı #9 bu rapora zaten alınmış; seçimden çıkarıldı. Bir olay kaydı bir rapora yalnız bir kez alınır.')
    expect(rowOf(9).getAttribute('data-imported')).toBe('true')
    expect(check(9)).toBeDisabled()
  })

  it('önizleme 400 SECTION_FULL / INCIDENT_OTHER_TEAM: kendi cümlesi, yeniden dene yok, "geri dönün" ipucu', async () => {
    te().incidentImportPreview.mockResolvedValueOnce({ success: false, status: 400, code: 'SECTION_FULL', field: 'incident_ids', error: 'x' })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    const failed = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="te-inc-preview-failed"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(failed.getAttribute('data-kind')).toBe('full')
    expect(failed.textContent).toContain('Bölüm dolu: bir anomali bölümünde en çok 30 konu olabilir.')
    expect(failed.textContent).toContain('Geri dönüp seçimi değiştirin.')
    expect(dlg.querySelector('[data-slot="te-inc-preview-retry"]')).toBeNull()
    expect(failed.textContent).not.toContain('SECTION_FULL')

    te().incidentImportPreview.mockResolvedValueOnce({ success: false, status: 400, code: 'INCIDENT_OTHER_TEAM', field: 'incident_ids', error: 'x' })
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-back"]'))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-preview-failed"]')?.getAttribute('data-kind')).toBe('other_team'))
    expect(dlg.textContent).toContain('bu kanalın takımına ait değil')
  })

  it('önizleme görselleri: tahmin notu, numaralı atlama nedeni, kalan görsel; bölüm izin vermiyorsa söyler', async () => {
    te().incidentImportPreview.mockResolvedValueOnce({ success: true, data: { proposals: [
      proposal(9, 'Ödeme API kesintisi', { images: { copy: 2, skipped: [{ reason: 'too_large', image_id: 41 }], remaining: 3 } }),
      proposal(11, 'Kart doğrulama gecikmesi', { images: { copy: 0, skipped: [], allowed: false } }),
    ] } })
    renderDialog()
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(11)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(check(11))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-inc-proposal"]')).toHaveLength(2))
    const first = dlg.querySelector('[data-slot="te-inc-proposal"][data-incident="9"] [data-slot="te-inc-pv-images"]')
    expect(first.getAttribute('data-allowed')).toBe('true')
    expect(first.textContent).toContain('2 görsel kopyalanacak')
    expect(first.textContent).toContain('Sayı tahminidir')
    expect(first.querySelector('[data-slot="te-inc-pv-img-skip"]').textContent).toBe('Görsel #41: Görsel çok büyük (en çok 5 MB, 6000 piksel).')
    expect(first.querySelector('[data-slot="te-inc-pv-img-remaining"]').textContent).toBe('3 görsel daha alınmayacak (bir konuya en çok 2 görsel).')
    const second = dlg.querySelector('[data-slot="te-inc-proposal"][data-incident="11"] [data-slot="te-inc-pv-images"]')
    expect(second.getAttribute('data-allowed')).toBe('false')
    expect(second.textContent).toContain('Bu bölüm konu görseline izin vermiyor')
    expect(second.textContent).not.toContain('kopyalanacak')
  })

  it('görsel izni olmayan bölümde "Görselleri de al" çizilmez ve gövdede images=false', async () => {
    const sections = [{ id: 'issues', title: 'Eski düzen', opts: { askTime: false, askOutage: false, askImpact: false, allowImages: false, legacy: true } }]
    renderDialog({ sections })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    expect(dlg.querySelector('[data-slot="te-inc-images"]')).toBeNull()
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(te().incidentImportPreview).toHaveBeenCalled())
    expect(te().incidentImportPreview.mock.calls[0][1].images).toBe(false)
    // Eski düzende tarih / saat / kesinti satırları gösterilmez; müşteri sayısı etkisiz satır
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    const groups = [...dlg.querySelectorAll('[data-slot="te-inc-pv-row"]')].map((r) => r.getAttribute('data-group'))
    expect(groups).toEqual(['topic', 'errors', 'impact'])
    expect(dlg.textContent).toContain('Etkilenen müşteri')
  })
})

describe('içe alma penceresi — inceleme düzeltmeleri (2026-11-20)', () => {
  const limitNote = (dlg) => dlg.querySelector('[data-slot="te-inc-limit"]')

  it('devre dışı kutunun nedeni GÖRÜNÜR metin ve aria-describedby ile bağlı (title değil); bu raporda olan rozete bağlanır', async () => {
    te().incidentCandidates.mockResolvedValue(candidates([cand(9, 'A'), cand(11, 'B'), cand(13, 'C'), cand(12, 'D', { imported: true })]))
    renderDialog({ counts: { issues: 28 } })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(13)).not.toBeNull())
    // Sınıra ulaşılmadan not yok
    expect(limitNote(dlg)).toBeNull()
    // Bu raporda olan satır: kutu rozetine bağlı, rozet görünür
    expect(check(12)).toBeDisabled()
    expect(check(12).getAttribute('title')).toBeNull()
    const impId = check(12).getAttribute('aria-describedby')
    expect(impId).toBe('te-inc-imp-12')
    expect(document.getElementById(impId).textContent).toBe('Bu raporda')
    fireEvent.click(check(9))
    fireEvent.click(check(11))
    // Bölümde kalan 2 yerin hepsi seçildi: bilgi satırı ("seçimi azaltın" değil), kutular ona bağlı
    const note = limitNote(dlg)
    expect(note).not.toBeNull()
    expect(note.getAttribute('data-kind')).toBe('capacity')
    expect(note.getAttribute('role')).toBe('status')
    expect(note.textContent).toBe('Bölümde kalan 2 konu yerinin hepsi seçildi. Başka bir olay seçmek için önce seçili birini bırakın.')
    expect(note.textContent).not.toContain('azaltın')
    expect(check(13)).toBeDisabled()
    expect(check(13).getAttribute('title')).toBeNull()
    expect(check(13).getAttribute('aria-describedby')).toBe('te-inc-limit-note')
    expect(document.getElementById('te-inc-limit-note').textContent).toBe(note.textContent)
    // Seçili kutu bırakılabilir, açıklaması yok
    expect(check(9).getAttribute('aria-describedby')).toBeNull()
    fireEvent.click(check(9))
    expect(limitNote(dlg)).toBeNull()
    expect(check(13).getAttribute('aria-describedby')).toBeNull()
  })

  it('bir seferde en çok 10: onuncu seçimde bilgi satırı, on birinci kutu ona bağlı', async () => {
    const many = Array.from({ length: 11 }, (_, i) => cand(100 + i, `Olay ${i + 1}`))
    te().incidentCandidates.mockResolvedValue({ success: true, data: { items: many, total: 11, page: 0, size: 20, period: { year: 2026, month: 9 } } })
    renderDialog({ counts: { issues: 0 } })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(110)).not.toBeNull())
    for (let i = 0; i < 10; i += 1) fireEvent.click(check(100 + i))
    const note = limitNote(dlg)
    expect(note.getAttribute('data-kind')).toBe('max')
    expect(note.textContent).toBe('10 olay seçtiniz: bir seferde alınabilecek en çok sayı bu. Başka bir olay seçmek için önce seçili birini bırakın.')
    expect(check(110)).toBeDisabled()
    expect(check(110).getAttribute('aria-describedby')).toBe('te-inc-limit-note')
    // Sınır uyarı değil: önizleme açık
    expect(dlg.querySelector('[data-slot="te-inc-next"]')).not.toBeDisabled()
  })

  it('tek yer kaldıysa tekil cümle; bölüm doluysa kutular "bölüm dolu" bandına bağlı', async () => {
    renderDialog({ counts: { issues: 29 } })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(11)).not.toBeNull())
    fireEvent.click(check(9))
    expect(limitNote(dlg).textContent).toBe('Bölümde kalan 1 konu yeri seçildi. Başka bir olay seçmek için önce seçili olayı bırakın.')
    expect(check(11).getAttribute('aria-describedby')).toBe('te-inc-limit-note')
  })

  it('bölüm doluysa kutular "bölüm dolu" bandına bağlı', async () => {
    renderDialog({ counts: { issues: 30 } })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    expect(limitNote(dlg).getAttribute('data-kind')).toBe('full')
    expect(check(9).getAttribute('aria-describedby')).toBe('te-inc-limit-note')
    expect(document.getElementById('te-inc-limit-note').textContent).toContain('Bu bölümde yer kalmadı')
  })

  it('önizleme 409 INCIDENT_ALREADY_IMPORTED (numarasız yarış): seçime döner, liste ve rapor yeniden okunur, alınan seçimden düşer', async () => {
    te().incidentImportPreview.mockResolvedValueOnce({ success: false, status: 409, code: 'INCIDENT_ALREADY_IMPORTED', error: 'x' })
    const onReportStale = vi.fn()
    renderDialog({ onReportStale })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    const listCalls = te().incidentCandidates.mock.calls.length
    // Yeniden okunan listede 9 artık bu raporda
    te().incidentCandidates.mockResolvedValue(candidates([cand(9, 'Ödeme API kesintisi', { imported: true }), cand(11, 'Kart doğrulama gecikmesi')]))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('pick'))
    expect(dlg.querySelector('[data-slot="te-inc-import-error"]').textContent).toBe(
      'Seçilen olay kayıtlarından biri siz alırken başka biri tarafından bu rapora alındı. Raporu yenileyip konuları denetleyin.')
    expect(onReportStale).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(te().incidentCandidates.mock.calls.length).toBeGreaterThan(listCalls))
    await waitFor(() => expect(rowOf(9)?.getAttribute('data-imported')).toBe('true'))
    expect(check(9)).toBeDisabled()
    expect(check(9)).not.toBeChecked()
    expect(dlg.querySelector('[data-slot="te-inc-selected"]').textContent).toBe('Henüz olay seçilmedi')
    expect(dlg.querySelector('[data-slot="te-inc-preview-failed"]')).toBeNull()
  })

  it('içe alma 409 numarasız: aynı seçimle önizlemede KALINMAZ — seçime döner, liste yeniden okunur, rapor yenilenir', async () => {
    const onImport = vi.fn(async () => ({ ok: false, kind: 'already', incidentId: null,
      message: 'Seçilen olay kayıtlarından biri siz alırken başka biri tarafından bu rapora alındı. Raporu yenileyip konuları denetleyin.' }))
    const onReportStale = vi.fn()
    renderDialog({ onImport, onReportStale })
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(11)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(check(11))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    const listCalls = te().incidentCandidates.mock.calls.length
    te().incidentCandidates.mockResolvedValue(candidates([cand(9, 'Ödeme API kesintisi'), cand(11, 'Kart doğrulama gecikmesi', { imported: true })]))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('pick'))
    expect(onReportStale).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(te().incidentCandidates.mock.calls.length).toBeGreaterThan(listCalls))
    await waitFor(() => expect(rowOf(11)?.getAttribute('data-imported')).toBe('true'))
    // Alınan olay seçimden düştü; öbürü seçili kaldı
    expect(check(9)).toBeChecked()
    expect(check(11)).not.toBeChecked()
    expect(dlg.querySelector('[data-slot="te-inc-selected"]').textContent).toBe('1 olay seçildi')
    expect(dlg.querySelector('[data-slot="te-inc-import-error"]').textContent).toContain('başka biri tarafından bu rapora alındı')
  })
})

describe('düzenleyiciyle bağlantı', () => {
  const importButton = () => document.querySelector('[data-slot="te-inc-import"]')

  it('düğme: olay okuma izni + bağ listesi + düzenleme izni; eski podda, izinsiz ve salt okunurda yok', async () => {
    te().report.mockResolvedValue(respond())
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    expect(importButton().textContent).toContain('Olay kaydından al')
    unmount()

    // Eski pod: `incident_links` hiç yok
    const old = respond()
    delete old.data.incident_links
    te().report.mockResolvedValue(old)
    const r2 = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-add-issue"]')).not.toBeNull()
    expect(importButton()).toBeNull()
    r2.unmount()

    perm.incidents = false
    te().report.mockResolvedValue(respond())
    const r3 = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-add-issue"]')).not.toBeNull()
    expect(importButton()).toBeNull()
    r3.unmount()

    perm.incidents = true
    te().report.mockResolvedValue(respond({ extra: { can_edit: false } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(importButton()).toBeNull()
  })

  it('içe alma: yeni konular açılır, bağlı çizilir, ilk konunun "Konu" alanına odak; sürüm sunucunun', async () => {
    te().report.mockResolvedValue(respond())
    const after = respond({
      issues: [topicFrom('i-9', 'Ödeme API kesintisi'), topicFrom('i-11', 'Kart doğrulama gecikmesi')],
      report: { version: 5 },
      extra: { incident_links: [link('i-9', 9, { notes: ['time_unknown'] }), link('i-11', 11)],
        imported: [{ incident_id: 9, issue_id: 'i-9', notes: ['time_unknown'] }, { incident_id: 11, issue_id: 'i-11', notes: [] }] },
    })
    te().importIncidents.mockResolvedValue(after)
    te().incidentImportPreview.mockResolvedValue({ success: true, data: { proposals: [proposal(9, 'Ödeme API kesintisi'),
      proposal(11, 'Kart doğrulama gecikmesi')] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    fireEvent.click(importButton())
    const dlg = await dialog()
    // Taslak temizdi: kaydetmeden açılır
    expect(te().saveReport).not.toHaveBeenCalled()
    await waitFor(() => expect(rowOf(11)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(check(11))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-inc-proposal"]')).toHaveLength(2))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(te().importIncidents).toHaveBeenCalledTimes(1))
    expect(te().importIncidents).toHaveBeenCalledWith('100', { version: 4, section_id: 'issues', incident_ids: [9, 11],
      parts: ['rca', 'impact', 'resolution'], images: true })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Olay kaydından konu al' })).toBeNull())
    expect(await screen.findByText(/2 olay kaydı konu olarak eklendi\. Bazı alanlar doldurulamadı/)).toBeInTheDocument()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-issue"]')).toHaveLength(2))
    const bar = document.querySelector('[data-slot="te-issue-incident"][data-incident="9"]')
    expect(bar).not.toBeNull()
    expect(bar.getAttribute('data-state')).toBe('ok')
    expect(bar.textContent).toContain('Olay kaydı #9 · Olay 9')
    expect(bar.querySelector('[data-note="time_unknown"]')).not.toBeNull()
    await waitFor(() => expect(document.activeElement?.id).toBe('te-iss-issues-i-9-topic'))
    // Yanıt yeniden yükleme gibi uygulandı: sonraki kayıt sunucunun sürümüyle (5) gider
    fireEvent.change(document.getElementById('te-iss-issues-i-9-topic'), { target: { value: 'Ödeme API kesintisi (Eylül)' } })
    fireEvent.click(document.querySelector('[data-slot="te-save"]'))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    expect(te().saveReport.mock.calls[0][2]).toBe(5)
  })

  it('kaydedilmemiş taslak önce kaydedilir, sonra pencere açılır; içe alma kaydın sürümüyle', async () => {
    te().report.mockResolvedValue(respond({ issues: [{ id: 'k1', topic: 'Kart', title: '', title_auto: true, description: '' }] }))
    te().importIncidents.mockResolvedValue({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION',
      error: 'Rapor başka yerde değişti.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    fireEvent.change(document.getElementById('te-iss-issues-k1-topic'), { target: { value: 'Kart Limiti' } })
    fireEvent.click(importButton())
    const dlg = await dialog()
    expect(te().saveReport).toHaveBeenCalledTimes(1)
    expect(te().saveReport.mock.calls[0][1].issues.issues[0].topic).toBe('Kart Limiti')
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    const reportCalls = te().report.mock.calls.length
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(te().importIncidents).toHaveBeenCalledTimes(1))
    expect(te().importIncidents.mock.calls[0][1].version).toBe(5)
    // Çakışma: pencere kapanır, uyarı, rapor yeniden okunur (körlemesine yeniden denenmez)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Olay kaydından konu al' })).toBeNull())
    expect(await screen.findByText('Rapor bu arada başka bir yerde değişti; en son hâli yüklendi. İşlemi raporun güncel hâli üzerinde yeniden yapın.')).toBeInTheDocument()
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(reportCalls))
    expect(te().importIncidents).toHaveBeenCalledTimes(1)
  })

  it('içe alma 400 SECTION_FULL / REPORT_TOO_LARGE: pencere açık, seçime döner, sunucu kodu yerine cümle', async () => {
    te().report.mockResolvedValue(respond())
    te().importIncidents.mockResolvedValueOnce({ success: false, status: 400, code: 'REPORT_TOO_LARGE', field: 'incident_ids',
      error: 'With the chosen incidents…' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    fireEvent.click(importButton())
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('pick'))
    expect(dlg.querySelector('[data-slot="te-inc-import-error"]').textContent).toBe(
      'Seçilen olaylarla rapor içeriği sınırı (256 KB) aşıyor. Daha az olay ya da daha az açıklama bölümü seçin.')
    // Seçim korunur (kullanıcı azaltır); rapor yeniden yüklenmez
    expect(check(9)).toBeChecked()
    expect(screen.getByRole('dialog', { name: 'Olay kaydından konu al' })).toBeInTheDocument()
  })

  it('görselli içe alma dolu (429 IMPORT_BUSY): nötr bilgi bandı, seçim + önizleme kalır, düğme süre dolunca açılır', async () => {
    te().report.mockResolvedValue(respond())
    te().importIncidents.mockResolvedValueOnce({ success: false, status: 429, code: 'IMPORT_BUSY', retry_after_seconds: 1, error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    fireEvent.click(importButton())
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-busy"]')).not.toBeNull())
    const note = dlg.querySelector('[data-slot="te-inc-busy"]')
    expect(note.textContent).toContain('Yaklaşık 1 saniye sonra yeniden deneyin')
    // Hata değil: tehlike bandı yok, bilgi tonu; başlık "Biraz bekleyin"
    expect(dlg.querySelector('[data-slot="te-inc-import-error"]')).toBeNull()
    expect(note.closest('[data-slot="alert"]').getAttribute('data-tone')).toBe('info')
    expect(note.closest('[data-slot="alert"]').textContent).toContain('Biraz bekleyin')
    // Seçim ve önizleme kalır; düğme süre boyunca kapalı, açıklaması bant
    expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('preview')
    expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull()
    const confirm = dlg.querySelector('[data-slot="te-inc-import-confirm"]')
    expect(confirm).toBeDisabled()
    expect(confirm.getAttribute('aria-describedby')).toBe('te-inc-busy-note')
    // Süre dolunca (otomatik yeniden deneme YOK) düğme açılır ve bant "artık deneyebilirsiniz" der
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-import-confirm"]')).not.toBeDisabled(), { timeout: 4000 })
    expect(te().importIncidents).toHaveBeenCalledTimes(1)
    expect(dlg.querySelector('[data-slot="te-inc-busy"]').getAttribute('data-cooling')).toBe('false')
    expect(dlg.querySelector('[data-slot="te-inc-busy"]').textContent).toContain('Artık yeniden deneyebilirsiniz.')
    // Yeniden basınca AYNI seçimle gider
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(te().importIncidents).toHaveBeenCalledTimes(2))
    expect(te().importIncidents.mock.calls[1][1]).toEqual(te().importIncidents.mock.calls[0][1])
  })

  it('bağ listesi okunamadıysa bağlı konular "bilinmiyor" notu; kart bağ iddia etmez', async () => {
    te().report.mockResolvedValue(respond({ issues: [topicFrom('i-9', 'Ödeme')],
      extra: { incident_links: null, incident_links_error: true } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(document.querySelector('[data-slot="te-inc-links-unknown"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="te-issue-incident"]')).toBeNull()
    expect(te().syncIncidentLinks).not.toHaveBeenCalled()
  })
})

describe('düzenleyiciyle bağlantı — inceleme düzeltmeleri (2026-11-20)', () => {
  const importButton = () => document.querySelector('[data-slot="te-inc-import"]')
  const conflictBanner = () => screen.queryByText('Rapor siz düzenlerken değişti')
  const issueTopic = (iid) => document.getElementById(`te-iss-issues-${iid}-topic`)
  function hideTab() {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
  }
  // Sunucu kayıtta sondaki boşluğu kırpar: yanıtın değerleri gönderilenden FARKLI
  const trimmingSave = async (_id, values) => saved({ ...values,
    issues: { issues: (values.issues?.issues || []).map((i) => ({ ...i, topic: String(i.topic || '').trimEnd() })) } })
  const k1 = (topic) => ({ id: 'k1', topic, title: '', title_auto: true, description: '' })
  async function importNine(dlg) {
    await waitFor(() => expect(rowOf(9)).not.toBeNull())
    fireEvent.click(check(9))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
  }
  afterEach(() => Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true }))

  it('otomatik kayıt sondaki boşluğu kırptıktan sonra içe alma UYGULANIR: konular görünür, çakışma yok', async () => {
    te().report.mockResolvedValue(respond({ issues: [k1('Kart')] }))
    te().saveReport.mockImplementation(trimmingSave)
    te().importIncidents.mockResolvedValue(respond({
      issues: [k1('Kart Limiti'), topicFrom('i-9', 'Ödeme API kesintisi')], report: { version: 6 },
      extra: { incident_links: [link('i-9', 9)], imported: [{ incident_id: 9, issue_id: 'i-9', notes: [] }] },
    }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    fireEvent.change(issueTopic('k1'), { target: { value: 'Kart Limiti ' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][3]).toMatchObject({ auto: true })
    // Otomatik kayıt bitti (taslak yalnız "kabul edildi" işaretiyle temiz): ikinci kayıt gitmeden pencere açılır
    await waitFor(() => expect(document.querySelector('[data-slot="te-save-status"]')?.textContent || '').not.toMatch(/Kaydediliyor/))
    fireEvent.click(importButton())
    const dlg = await dialog()
    expect(te().saveReport).toHaveBeenCalledTimes(1)
    await importNine(dlg)
    await waitFor(() => expect(te().importIncidents).toHaveBeenCalledTimes(1))
    expect(te().importIncidents.mock.calls[0][1].version).toBe(5)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-issue"]')).toHaveLength(2))
    expect(document.querySelector('[data-slot="te-issue-incident"][data-incident="9"]')).not.toBeNull()
    expect(issueTopic('k1').value).toBe('Kart Limiti')
    expect(conflictBanner()).toBeNull()
  })

  it('kaydedilmemiş taslağı sunucu düzelterek kaydederse "Rapor kaydedilemedi" denmez; pencere açılır, içe alma uygulanır', async () => {
    te().report.mockResolvedValue(respond({ issues: [k1('Kart')] }))
    te().saveReport.mockImplementation(trimmingSave)
    te().importIncidents.mockResolvedValue(respond({
      issues: [k1('Kart Limiti'), topicFrom('i-9', 'Ödeme API kesintisi')], report: { version: 6 },
      extra: { incident_links: [link('i-9', 9)], imported: [{ incident_id: 9, issue_id: 'i-9', notes: [] }] },
    }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    fireEvent.change(issueTopic('k1'), { target: { value: 'Kart Limiti ' } })
    fireEvent.click(importButton())
    const dlg = await dialog()
    expect(te().saveReport).toHaveBeenCalledTimes(1)
    expect(te().saveReport.mock.calls[0][1].issues.issues[0].topic).toBe('Kart Limiti ')
    expect(screen.queryByText('Rapor kaydedilemedi; olay kaydından almadan önce sorunu giderip yeniden deneyin.')).toBeNull()
    await importNine(dlg)
    await waitFor(() => expect(te().importIncidents).toHaveBeenCalledTimes(1))
    expect(te().importIncidents.mock.calls[0][1].version).toBe(5)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-issue"]')).toHaveLength(2))
    expect(conflictBanner()).toBeNull()
  })

  it('geç gelen yalnız-bağ okuması içe almanın yeni bağlarını EZMEZ', async () => {
    const d = deferred()
    te().report
      .mockResolvedValueOnce(respond({ issues: [topicFrom('i-9', 'Ödeme')], extra: { incident_links: [link('i-9', 9)] } }))
      .mockReturnValueOnce(d.promise)
      .mockResolvedValue(respond({ issues: [topicFrom('i-9', 'Ödeme')], extra: { incident_links: [link('i-9', 9)] } }))
    // Bağı koparmanın sonucu bilinmiyor → yalnız bağlar yeniden okunur (yanıt bekletiliyor)
    te().unlinkIncident.mockResolvedValue({ success: false, status: 0, code: 'NETWORK_ERROR', error: 'ağ' })
    te().importIncidents.mockResolvedValue(respond({
      issues: [topicFrom('i-9', 'Ödeme'), topicFrom('i-11', 'Kart doğrulama gecikmesi')], report: { version: 5 },
      extra: { incident_links: [link('i-9', 9), link('i-11', 11)], imported: [{ incident_id: 11, issue_id: 'i-11', notes: [] }] },
    }))
    te().incidentCandidates.mockResolvedValue(candidates([cand(9, 'Ödeme', { imported: true }), cand(11, 'Kart doğrulama gecikmesi')]))
    te().incidentImportPreview.mockResolvedValue({ success: true, data: { proposals: [proposal(11, 'Kart doğrulama gecikmesi')] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(document.querySelector('[data-slot="te-issue-incident"][data-incident="9"]')).not.toBeNull())
    pressMenuTrigger(screen.getByRole('button', { name: 'Olay #9 — Olay kaydı işlemleri' }))
    const menu = await screen.findByRole('menu')
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Bağı kopar/ }))
    const confirm = await screen.findByRole('dialog', { name: 'Olay kaydıyla bağ koparılsın mı?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Bağı kopar' }))
    await waitFor(() => expect(te().report).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(importButton()).not.toBeDisabled())
    fireEvent.click(importButton())
    const dlg = await dialog()
    await waitFor(() => expect(rowOf(11)).not.toBeNull())
    fireEvent.click(check(11))
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-next"]'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-proposal"]')).not.toBeNull())
    fireEvent.click(dlg.querySelector('[data-slot="te-inc-import-confirm"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="te-issue-incident"][data-incident="11"]')).not.toBeNull())
    // Eski okuma şimdi gelir (yalnız #9'un bağı): uygulanmaz
    d.resolve(respond({ issues: [topicFrom('i-9', 'Ödeme')], extra: { incident_links: [link('i-9', 9, { state: 'stale' })] } }))
    await new Promise((r) => setTimeout(r, 30))
    expect(document.querySelector('[data-slot="te-issue-incident"][data-incident="11"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-issue-incident"][data-incident="9"]').getAttribute('data-state')).toBe('ok')
  })

  it('içe alma 409 numarasız yarış: pencere açık kalır, seçime döner, düzenleyici raporu yeniden okur', async () => {
    te().report.mockResolvedValue(respond())
    te().importIncidents.mockResolvedValueOnce({ success: false, status: 409, code: 'INCIDENT_ALREADY_IMPORTED', error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(importButton()).not.toBeNull())
    fireEvent.click(importButton())
    const dlg = await dialog()
    const reportCalls = te().report.mock.calls.length
    await importNine(dlg)
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-inc-dialog"]').getAttribute('data-step')).toBe('pick'))
    expect(dlg.querySelector('[data-slot="te-inc-import-error"]').textContent).toContain('başka biri tarafından bu rapora alındı')
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(reportCalls))
    expect(screen.getByRole('dialog', { name: 'Olay kaydından konu al' })).toBeInTheDocument()
  })
})
