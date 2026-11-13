import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import IncidentsPage from '../components/IncidentsPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

let MOBILE = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => MOBILE }))

// Dakikalık sessiz yenileme testte elle tetiklenir: kanca geri çağrıyı süresine göre saklar (LiveDuration'ınki 1000 ms).
const INTERVALS = {}
vi.mock('../hooks/useVisibleInterval.js', () => ({
  useVisibleInterval: (fn, ms) => { if (ms) INTERVALS[ms] = fn },
}))

const navigateTo = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => navigateTo(...a) }))
const downloadBlob = vi.fn(() => true)
vi.mock('../utils/csvExport.js', async (orig) => ({ ...(await orig()), downloadBlob: (...a) => downloadBlob(...a) }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    getActivity: vi.fn(),
    monitoring: {
      incidents: {
        list: vi.fn(), get: vi.fn(), summary: vi.fn(), comments: vi.fn(), addComment: vi.fn(), deleteComment: vi.fn(),
        remove: vi.fn(), exportCsv: vi.fn(), exportJson: vi.fn(),
      },
    },
    admin: {
      acknowledgeAlert: vi.fn(), resolveAlert: vi.fn(), getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(),
      getAlertStormPush: vi.fn(), bulkAlertAction: vi.fn(), previewReNotify: vi.fn(), reNotifyAlert: vi.fn(), getAlert: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

const base = {
  id: 1, status: 'ongoing', monitor: { name: 'https://x.example.com', type: 'http', tab: 'http', monitor_id: 9 },
  root_cause: { code: '500', category: 'server_error' }, comment_count: 0, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
  started_at: '2026-11-12T10:00:00', resolved_at: null, resolved_by: null, acknowledged: false, domain: 'https://x.example.com',
  message: 'HTTP 500', team_id: 5, team_name: 'Takım A', can_manage: true, can_act: true, can_comment: true, can_delete: false,
  can_notify: true, recurrence_7d: 3,
}
const second = { ...base, id: 2, alert_level: 'HIGH', monitor: { ...base.monitor, name: 'api.example.com', monitor_id: 10 }, recurrence_7d: null }
const foreign = { ...base, id: 3, can_manage: false, can_act: false, can_comment: false, can_notify: false,
  monitor: { ...base.monitor, name: 'other.example.com', monitor_id: 12 }, team_id: 7, team_name: 'Takım C' }
const cert = { ...base, id: 4, alert_type: 'EXPIRY', domain: 'cert.example.com', monitor: { name: 'cert.example.com', type: 'cert', tab: 'dashboard', monitor_id: null } }

const SUMMARY = {
  open: 4, acknowledged: 1, critical: 2, teamless: 0, mine: 3, resolved_24h: null,
  mtta_24h_minutes: null, mtta_7d_minutes: 72, mttr_24h_minutes: 30, mttr_7d_minutes: 95, mtta_sample_7d: 4, mttr_sample_7d: 6,
  timing_capped: false, longest_open: { id: 99, name: 'longest.example.com', started_at: '2026-11-10T08:00:00', alert_level: 'HIGH' },
  team_options: [{ id: 5, name: 'Takım A', count: 2 }, { id: 7, name: 'Takım C', count: 1 }],
}

function mock({ rows = [base, second], summary = SUMMARY, total = rows.length, serverFacets = true } = {}) {
  api.monitoring.incidents.list.mockImplementation(() =>
    Promise.resolve({ success: true, data: rows, total, type_counts: { HTTP_DOWN: 5, PING_DOWN: 2 }, server_facets: serverFacets }))
  api.monitoring.incidents.summary.mockResolvedValue(summary ? { success: true, data: summary } : { success: true, data: [] })
}
const lastListParams = () => api.monitoring.incidents.list.mock.calls.at(-1)[0]
const openDetail = async (name) => {
  fireEvent.click(await screen.findByRole('button', { name: new RegExp(`Open incident ${name}|${name} olayını aç`) }))
  return screen.findByRole('dialog', { name: /Incident #|Olay #/ })
}

beforeEach(() => {
  vi.clearAllMocks(); MOBILE = false; localStorage.clear()
  for (const k of Object.keys(INTERVALS)) delete INTERVALS[k]
  window.history.replaceState({}, '', '/?tab=incidents')
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertStormPush.mockResolvedValue({ success: true, data: { items: [], pending: [], storms: [] } })
  api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [] })
  api.getActivity.mockResolvedValue({ success: true, data: [], total: 0 })
})

describe('Olaylar zenginleştirmesi — özet ve süzgeçler', () => {
  it('özet KESİN uçtan: kartlar sunucu sayıları, okunamayan 24 sa "—" (0 değil); müdahale süreleri şeridi', async () => {
    mock()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await waitFor(() => expect(api.monitoring.incidents.summary).toHaveBeenCalled())
    const tile = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"] [data-slot="stat-value"]`)
    await waitFor(() => expect(tile('open')?.textContent).toBe('4'))
    expect(tile('resolved24').textContent).toBe('—')
    expect(tile('critical').textContent).toBe('2')
    // Canlı satır da uydurmaz
    expect(document.querySelector('[data-slot="incidents-live"]').textContent).toMatch(/—/)
    const strip = document.querySelector('[data-slot="incident-metrics"]')
    expect(strip).toBeTruthy()
    const cells = strip.querySelectorAll('[data-slot="incident-metric"]')
    expect(cells[0].querySelector('[data-slot="incident-metric-24h"]').textContent).toBe('—')
    expect(cells[0].querySelector('[data-slot="incident-metric-7d"]').textContent).toMatch(/1 (h|sa) 12/)
    expect(within(strip).getByText('longest.example.com')).toBeInTheDocument()
  })

  it('eski sunucu (özet ucu yok): kartlar açık kümeden türetilir, müdahale şeridi çizilmez', async () => {
    mock({ summary: null })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await waitFor(() => expect(document.querySelector('[data-slot="stat-item"][data-key="open"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="incident-metrics"]')).toBeNull()
  })

  it('en uzun açık olay: sayfada değilse tekil uçtan açılır', async () => {
    mock()
    api.monitoring.incidents.get.mockResolvedValue({ success: true, data: { ...base, id: 99, monitor: { ...base.monitor, name: 'longest.example.com' } } })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    fireEvent.click(await screen.findByRole('button', { name: /longest\.example\.com/ }))
    await waitFor(() => expect(api.monitoring.incidents.get).toHaveBeenCalledWith(99))
    expect(await screen.findByRole('dialog', { name: /Incident #99|Olay #99/ })).toBeInTheDocument()
  })

  it('sunucu süzgeçleri: önem "kritik ve yüksek", takım (ADI ile), tekrarlayanlar → istek parametresi; çipler ham kimlik basmaz', async () => {
    mock()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByRole('button', { name: /Open incident https:\/\/x\.example\.com|olayını aç/ })
    fireEvent.change(screen.getByRole('combobox', { name: /Severity|Önem/ }), { target: { value: 'CRITICAL,HIGH' } })
    await waitFor(() => expect(lastListParams().level).toBe('CRITICAL,HIGH'))
    const team = screen.getByRole('combobox', { name: /^Team$|^Takım$/ })
    expect(within(team).getByRole('option', { name: 'Takım C' })).toBeInTheDocument()
    fireEvent.change(team, { target: { value: '7' } })
    await waitFor(() => expect(lastListParams().teamId).toBe('7'))
    fireEvent.click(document.querySelector('[data-slot="incidents-recurring"]'))
    await waitFor(() => expect(lastListParams().recurring).toBe('true'))
    const chips = document.querySelector('[data-slot="active-filters"]')
    expect(within(chips).getByText(/Critical and high|Kritik ve yüksek/)).toBeInTheDocument()
    expect(within(chips).getByText(/Takım C/)).toBeInTheDocument()
    // Sunucu süzdü: "yüklenen sayfa" notu yok
    expect(document.querySelector('[data-slot="facet-note"]')).toBeNull()
  })

  it('"Tüm nedenler" çipi kök neden seçiliyken de süzgeçsiz toplamı gösterir', async () => {
    mock()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const all = await screen.findByRole('button', { name: /^(All|Tümü|Tüm nedenler)\s*\d/ })
    expect(all).toHaveTextContent('7')
    fireEvent.click(screen.getByRole('button', { name: /PING_DOWN|Ping/ }))
    await waitFor(() => expect(lastListParams().rootCause).toBe('PING_DOWN'))
    expect(screen.getByRole('button', { name: /^(All|Tümü|Tüm nedenler)\s*\d/ })).toHaveTextContent('7')
  })

  it('tekrarlayan imza rozeti "7 günde N kez"; kapsam "Takımımın" boşken "her şey yolunda" demez', async () => {
    mock()
    const { unmount } = render(<IncidentsPage systemRole="USER" teamId={5} />)
    const badge = await waitFor(() => { const b = document.querySelector('[data-slot="incident-recur"]'); expect(b).toBeTruthy(); return b })
    expect(badge).toHaveAttribute('data-count', '3')
    unmount()
    api.monitoring.incidents.list.mockResolvedValue({ success: true, data: [], total: 0, type_counts: {}, server_facets: true, scope: 'mine', scope_counts: { mine: 0, others: 3, all: 3 } })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    expect(await screen.findByText(/No incidents for your teams|Takımlarınızın olayı yok/)).toBeInTheDocument()
    expect(screen.queryByText(/all clear|Her şey yolunda/i)).toBeNull()
  })
})

describe('Olaylar zenginleştirmesi — toplu işlem, yeni olay, klavye, dışa aktarım', () => {
  it('liste görünümü: yalnız işlem yapılabilen açık satırlar seçilir; toplu çöz → gerekçe → satır başına sonuç', async () => {
    mock({ rows: [base, second, foreign] })
    api.admin.bulkAlertAction.mockResolvedValue({ success: true, data: { processed: 1, skipped: 0, failed: 0,
      results: [{ id: 1, outcome: 'processed' }, { id: 2, outcome: 'closed' }] } })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    fireEvent.click(await screen.findByRole('button', { name: /^(List|Liste)$/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="incidents-select-row"]').length).toBe(2))   // yabancı satır seçilemez
    fireEvent.click(document.querySelector('[data-slot="incidents-select-all"]'))
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="incidents-bulk-bar"]'); expect(b).toBeTruthy(); return b })
    fireEvent.click(within(bar).getByRole('button', { name: /Resolve selected|Toplu çöz/ }))
    const dlg = await screen.findByRole('dialog', { name: /Resolve|Çöz/ })
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Reason|Gerekçe)/ }), { target: { value: 'root cause fixed by deploy' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Resolve|Çöz)/ }))
    await waitFor(() => expect(api.admin.bulkAlertAction).toHaveBeenCalledWith('resolve', [1, 2], 'root cause fixed by deploy'))
    const result = await waitFor(() => { const r = document.querySelector('[data-slot="incidents-bulk-result"]'); expect(r).toBeTruthy(); return r })
    expect(result.querySelector('[data-outcome="closed"]')).toHaveTextContent(/closed meanwhile|bu arada kapandı/)
  })

  it('dakikalık SESSİZ yenilemede gelen açık olay vurgulanır ("1 yeni olay"); detay açılınca kalkar', async () => {
    mock({ rows: [second] })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByRole('button', { name: /api\.example\.com/ })
    // Yeni olay = önceki yüklemedeki en büyük kimlikten (2) büyük açık olay
    mock({ rows: [{ ...base, id: 7 }, second] })
    await act(async () => { INTERVALS[60000]() })
    expect(await screen.findByRole('button', { name: /1 new incident|1 yeni olay/ })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="incident-card"][data-incident-id="7"]')).toHaveAttribute('data-new', 'true')
    await openDetail('https://x.example.com')
    await waitFor(() => expect(screen.queryByRole('button', { name: /1 new incident|1 yeni olay/ })).toBeNull())
  })

  it('klavye: j imleci ilk olaya taşır, a gerekçeli onay penceresini açar; yazı alanındayken çalışmaz', async () => {
    mock()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByRole('button', { name: /api\.example\.com/ })
    const search = screen.getByRole('searchbox')
    fireEvent.keyDown(search, { key: 'j' })
    fireEvent.keyDown(search, { key: 'a' })
    expect(screen.queryByRole('dialog', { name: /Acknowledge|Onayla/ })).toBeNull()
    fireEvent.keyDown(document.body, { key: 'j' })
    fireEvent.keyDown(document.body, { key: 'a' })
    expect(await screen.findByRole('dialog', { name: /Acknowledge|onayla/i })).toBeInTheDocument()
  })

  it('dışa aktarım: CSV süzgeç parametreleriyle sunucudan; Excel JSON satırlarından tarayıcıda kurulur', async () => {
    mock()
    api.monitoring.incidents.exportCsv.mockResolvedValue({ success: true, name: 'olaylar.csv', truncated: false })
    api.monitoring.incidents.exportJson.mockResolvedValue({ success: true, data: { rows: [base, second], truncated: true, max_rows: 10000 } })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByRole('button', { name: /api\.example\.com/ })
    const trigger = document.querySelector('[data-slot="incidents-export"]')
    fireEvent.pointerDown(trigger, { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: /CSV/ }))
    await waitFor(() => expect(api.monitoring.incidents.exportCsv).toHaveBeenCalledWith(expect.objectContaining({ status: '' })))
    await waitFor(() => expect(document.querySelector('[data-slot="incidents-export"]')).not.toBeDisabled())
    fireEvent.pointerDown(document.querySelector('[data-slot="incidents-export"]'), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: /Excel/ }))
    await waitFor(() => expect(downloadBlob).toHaveBeenCalled())
    expect(downloadBlob.mock.calls[0][0]).toMatch(/^olaylar-\d{8}-\d{4}\.xlsx$/)
  })

  it('gürültü analizi bağlantısı Alarm Geçmişi panelini seçili takımla açar', async () => {
    window.history.replaceState({}, '', '/?tab=incidents&team=5')
    mock()
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    fireEvent.click(await screen.findByRole('link', { name: /Noise analysis|Gürültü analizi/ }))
    expect(navigateTo).toHaveBeenCalledWith('alerthistory', { nz_open: '1', nz_team: '5' })
  })
})

describe('Olaylar zenginleştirmesi — detay penceresi', () => {
  it('bağlam: gerekçe notları, sessiz kapanış, hatırlatma; Alarm Geçmişi bağlantısı; kapanınca ?incident adresten silinir', async () => {
    window.history.replaceState({}, '', '/?tab=incidents&incident=1')
    const full = { ...base, acknowledged: true, acknowledged_note: 'deploy rollback in progress', realert_count: 2,
      last_re_alert_at: '2026-11-12T12:00:00', resolved_silently: false, in_maintenance: true }
    mock({ rows: [full] })
    api.monitoring.incidents.get.mockResolvedValue({ success: true, data: full })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const dlg = await screen.findByRole('dialog', { name: /Incident #1|Olay #1/ })
    const ctx = dlg.querySelector('[data-slot="incident-context"]')
    expect(ctx.querySelector('[data-slot="incident-ack-note"]')).toHaveTextContent('deploy rollback in progress')
    expect(ctx.querySelector('[data-slot="incident-realert"]')).toHaveTextContent(/2/)
    expect(ctx.querySelector('[data-slot="incident-maintenance"]')).toBeTruthy()
    expect(dlg.querySelector('[data-slot="incident-open-alert"]')).toHaveAttribute('href', '?tab=alerthistory&alert=1')
    fireEvent.click(within(dlg).getByRole('button', { name: /^Close$|^Kapat$/ }))
    await waitFor(() => expect(window.location.search).not.toMatch(/incident=/))
  })

  it('yorum yazıcı sunucunun can_comment\'iyle; yorum silme yorum başına can_delete; yorum okunamazsa "yeniden dene"', async () => {
    mock({ rows: [{ ...base, can_comment: false }] })
    api.monitoring.incidents.comments.mockResolvedValue({ success: false, error: 'boom' })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    let dlg = await openDetail('https://x.example.com')
    expect(dlg.querySelector('[data-slot="incident-composer-locked"]')).toHaveTextContent(/not allowed to comment|yorum ekleme yetkiniz yok/)
    expect(await within(dlg).findByText(/could not be read|okunamadı/)).toBeInTheDocument()
    api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [
      { id: 11, author_name: 'Ayse', body: 'mine', created_at: '2026-11-12T10:05:00', can_delete: true },
      { id: 12, author_name: 'Veli', body: 'theirs', created_at: '2026-11-12T10:06:00', can_delete: false },
    ] })
    fireEvent.click(within(dlg.querySelector('[data-slot="incident-comments-failed"]')).getByRole('button', { name: /Try again|Tekrar dene|Yeniden dene/ }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="incident-comments-failed"]')).toBeNull())
    const events = [...dlg.querySelectorAll('[data-slot="timeline-event"][data-kind="comment"]')]
    expect(events).toHaveLength(2)
    expect(within(events[0]).queryByRole('button', { name: /Delete|Sil/ })).toBeInTheDocument()
    expect(within(events[1]).queryByRole('button', { name: /Delete|Sil/ })).toBeNull()
  })

  it('bildirim tetiği ve durumu çevrilir (ham kod basılmaz); bildirim geçmişi okunamazsa bant', async () => {
    mock({ rows: [base] })
    api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [
      { id: 8, sent_at: '2026-11-12T10:01:00', recipient_name: 'Takım A', trigger: 'INITIAL', email_status: 'FAILED: 550 mailbox' },
    ] })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const dlg = await openDetail('https://x.example.com')
    const ev = await waitFor(() => { const e = dlg.querySelector('[data-slot="timeline-event"][data-kind="notified"]'); expect(e).toBeTruthy(); return e })
    expect(ev.textContent).not.toMatch(/FAILED: 550/)
    expect(ev.textContent).not.toMatch(/\bINITIAL\b/)
  })

  it('yeniden bildir: can_notify olan olayda önizleme → onay penceresi', async () => {
    mock({ rows: [base] })
    api.admin.previewReNotify.mockResolvedValue({ success: true, data: { recipients: [{ email: 'a@x.com', name: 'A', kind: 'TEAM' }], webhook: null } })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const dlg = await openDetail('https://x.example.com')
    fireEvent.click(dlg.querySelector('[data-slot="incident-renotify"]'))
    await waitFor(() => expect(api.admin.previewReNotify).toHaveBeenCalledWith(1))
  })

  it('kontrol kanıtları: izleme kimliğiyle Aktivite sorgusu; sertifika olayında "bağlanamadı"; okuma hatasında yeniden dene', async () => {
    mock({ rows: [base, cert] })
    api.getActivity.mockResolvedValueOnce({ success: true, total: 1, data: [
      { id: 501, activity_time: '2026-11-12T09:58:00', result_status: 'ERROR', failure_reason: 'HTTP_STATUS', http_status: 503 },
    ] })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    let dlg = await openDetail('https://x.example.com')
    // Uyarı veren kontroller de kanıttır (HTTP hata kodu, içerik koşulu, yavaş yanıt WARNING yazılır)
    await waitFor(() => expect(api.getActivity).toHaveBeenCalledWith(expect.objectContaining({ monitor: 'HTTP:9', status: 'ERROR,TIMEOUT,WARNING' })))
    const row = await waitFor(() => { const r = dlg.querySelector('[data-slot="incident-evidence-row"]'); expect(r).toBeTruthy(); return r })
    expect(row.querySelector('a')).toHaveAttribute('href', '?tab=activity&act=501')
    fireEvent.click(within(dlg).getByRole('button', { name: /^Close$|^Kapat$/ }))
    dlg = await openDetail('cert.example.com')
    expect(dlg.querySelector('[data-slot="incident-evidence-state"]')).toHaveAttribute('data-state', 'cert')
  })
})

describe('Olaylar zenginleştirmesi — bağımsız inceleme düzeltmeleri (2026-11-13)', () => {
  it('Enter yalnız odak gövdedeyken imleçteki olayı açar; sayfalama düğmesindeki Enter olay açmaz', async () => {
    mock({ total: 120 })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByRole('button', { name: /api\.example\.com/ })
    fireEvent.keyDown(document.body, { key: 'j' })
    const next = screen.getAllByRole('button', { name: /^(Next|Sonraki)$/ })[0]
    next.focus()
    fireEvent.keyDown(next, { key: 'Enter' })
    expect(screen.queryByRole('dialog', { name: /Incident #|Olay #/ })).toBeNull()
    next.blur()
    fireEvent.keyDown(document.body, { key: 'Enter' })
    expect(await screen.findByRole('dialog', { name: /Incident #|Olay #/ })).toBeInTheDocument()
  })

  it('toplu onay onaylı olayı göndermez (ilk sahiplenme korunur); sonuçta "zaten onaylı — atlandı"', async () => {
    mock({ rows: [base, { ...second, acknowledged: true }] })
    api.admin.bulkAlertAction.mockResolvedValue({ success: true, data: { processed: 1, skipped: 0, failed: 0, results: [{ id: 1, outcome: 'processed' }] } })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    fireEvent.click(await screen.findByRole('button', { name: /^(List|Liste)$/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="incidents-select-row"]').length).toBe(2))
    fireEvent.click(document.querySelector('[data-slot="incidents-select-all"]'))
    expect(await waitFor(() => document.querySelector('[data-slot="incidents-bulk-ack-skip"]'))).toHaveTextContent(/1/)
    fireEvent.click(screen.getByRole('button', { name: /Acknowledge selected|Toplu onayla/ }))
    const dlg = await screen.findByRole('dialog', { name: /Acknowledge|onayla/i })
    fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Reason|Gerekçe)/ }), { target: { value: 'ağ ekibi inceliyor, takipteyiz' } })
    fireEvent.click(dlg.querySelector('[data-action-submit]'))
    await waitFor(() => expect(api.admin.bulkAlertAction).toHaveBeenCalledWith('acknowledge', [1], 'ağ ekibi inceliyor, takipteyiz'))
    const result = await waitFor(() => { const r = document.querySelector('[data-slot="incidents-bulk-result"]'); expect(r).toBeTruthy(); return r })
    expect(result.querySelector('[data-outcome="already_acknowledged"]')).toHaveTextContent(/already acknowledged|zaten onaylı/)
    // Süzgeç değişimi sonuç bandını temizler
    fireEvent.change(screen.getByRole('combobox', { name: /Severity|Önem/ }), { target: { value: 'HIGH' } })
    await waitFor(() => expect(document.querySelector('[data-slot="incidents-bulk-result"]')).toBeNull())
  })

  it('"yeni olay": sayfa sonuna kayan eski satır yeni SAYILMAZ; önceki en büyük kimlikten büyük açık olay sayılır', async () => {
    const a = { ...base, id: 5 }
    const b = { ...second, id: 6 }
    const old = { ...base, id: 3, monitor: { ...base.monitor, name: 'old.example.com', monitor_id: 30 } }
    const fresh = { ...base, id: 9, monitor: { ...base.monitor, name: 'fresh.example.com', monitor_id: 90 } }
    mock({ rows: [a, b] })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByRole('button', { name: /api\.example\.com/ })
    mock({ rows: [a, b, old] })   // daha küçük kimlik (sayfaya kayan eski satır)
    await act(async () => { INTERVALS[60000]() })
    await screen.findByRole('button', { name: /old\.example\.com/ })
    expect(screen.queryByRole('button', { name: /new incident|yeni olay/ })).toBeNull()
    mock({ rows: [fresh, a, b] })
    await act(async () => { INTERVALS[60000]() })
    expect(await screen.findByRole('button', { name: /1 new incident|1 yeni olay/ })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="incident-card"][data-incident-id="9"]')).toHaveAttribute('data-new', 'true')
  })

  it('yedek özet: sunucu çözülme süzgecini uyguladığını söylemezse "Çözüldü (24 sa)" bilinmiyor ("—")', async () => {
    api.monitoring.incidents.summary.mockResolvedValue({ success: true, data: [] })
    api.monitoring.incidents.list.mockImplementation((p = {}) => Promise.resolve(
      p.status === 'resolved' && Number(p.size) === 1
        ? { success: true, data: [], total: 999 }
        : { success: true, data: [base], total: 1, type_counts: {} }))
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const tile = () => document.querySelector('[data-slot="stat-item"][data-key="resolved24"] [data-slot="stat-value"]')
    await waitFor(() => expect(tile()?.textContent).toBe('—'))
    const call = api.monitoring.incidents.list.mock.calls.map((c) => c[0]).find((p) => p.status === 'resolved' && Number(p.size) === 1)
    expect(call.resolvedSince).toBeTruthy()
    expect(call.since).toBeUndefined()
  })

  it('dışa aktarım eski podda (400 INVALID_PARAMETER) "bu sürümde yok" der', async () => {
    mock()
    api.monitoring.incidents.exportCsv.mockResolvedValue({ success: false, status: 400, code: 'INVALID_PARAMETER', error: 'Geçersiz parametre' })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    await screen.findByRole('button', { name: /api\.example\.com/ })
    fireEvent.pointerDown(document.querySelector('[data-slot="incidents-export"]'), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: /CSV/ }))
    expect(await screen.findByText(/no export|Dışa aktarım sunucunun bu sürümünde yok/)).toBeInTheDocument()
  })

  it('başka ekibin satırı: kanıt "başka ekibin izlemesi", Alarm Geçmişi bağlantısı yok (7/24 konsolu değil)', async () => {
    const other = { ...foreign, monitor: { ...foreign.monitor, monitor_id: null } }
    mock({ rows: [other] })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const dlg = await openDetail('other.example.com')
    expect(dlg.querySelector('[data-slot="incident-evidence-state"]')).toHaveAttribute('data-state', 'foreign')
    expect(dlg.querySelector('[data-slot="incident-open-alert"]')).toBeNull()
  })

  it('kanıt satırında durum rozeti: uyarı (WARNING) satırı kırmızı değil', async () => {
    mock({ rows: [base] })
    api.getActivity.mockResolvedValueOnce({ success: true, total: 1, data: [
      { id: 601, activity_time: '2026-11-12T09:59:00', result_status: 'WARNING', failure_reason: 'HTTP_STATUS', http_status: 500 },
    ] })
    render(<IncidentsPage systemRole="USER" teamId={5} />)
    const dlg = await openDetail('https://x.example.com')
    const badge = await waitFor(() => { const b = dlg.querySelector('[data-slot="incident-evidence-status"]'); expect(b).toBeTruthy(); return b })
    expect(badge.className).toMatch(/amber/)
    expect(badge.className).not.toMatch(/(^|\s)(text|bg)-destructive(\/|\s|$)/)
  })
})
