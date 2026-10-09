import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import ChangeHistoryTab from '../components/history/ChangeHistoryTab.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors:   vi.fn(),
      getCheckHistory:   vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      updateHttpMonitor: vi.fn(),
      triggerHttpCheck:  vi.fn(),
      getChanges:        vi.fn(),
      getChangeDetail:   vi.fn(),
      restoreChange:     vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * AÇIK DETAYDAN İKİNCİ DÜZENLEME İLKİNİ GERİ ALMAZ (2026-10-09, doğrulanmış hata). Başarılı güncellemeden sonra yalnız
 * liste yeniden yükleniyordu; açık detay penceresinin kopyası (`selected`) eski kalıyor, aynı pencereden ikinci "Düzenle"
 * formu bu bayat kopyadan kuruyordu ve kaydetme TAM yük gönderdiği için ilk düzenleme sessizce geri alınıyordu.
 * Aynı bayatlık 60 sn'lik liste yenilemesinde (başka kullanıcının değişikliği) ve değişiklik geçmişinden geri almada da
 * vardı. HTTP temsilci: dokuz sayfa aynı yardımcıları (utils/monitorDetailSync) kullanır.
 *
 * Bilerek KONTROLÜ ETKİLEMEYEN bir alan (SSL hata kontrolü) değiştirilir: kontrolü etkileyen değişiklikte kayıt sonrası
 * "Şimdi kontrol et" yanıtı kopyayı zaten değiştiriyordu ve hata yalnız meta/bildirim ayarlarında görünüyordu.
 */
const A = {
  id: 1, name: 'Example', url: 'https://www.example.com/', method: 'GET', expected_status: '201-204',
  group_name: 'X Sistemleri', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, response_ms: 12,
  interval_seconds: 600, timeout_ms: 7000, active: true, checked_at: '2026-06-24T00:00:00', check_ssl_errors: false,
}
const SSL_ROW = 'SSL certificate and Domain checks'
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }

const openDetail = async () => {
  const title = await waitFor(() => {
    const el = document.querySelector(`[data-monitor-open][aria-label^="${A.url} — "]`)
    if (!el) throw new Error('kart yok')
    return el
  })
  fireEvent.click(title)
  return screen.findByRole('dialog', { name: new RegExp(A.url.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')) })
}
/** Detayın "SSL sertifikası ve alan adı kontrolleri" satırının değeri (DetailInfoCard: etiket + değer kardeş). */
const sslRowValue = (dlg) => within(dlg).getByText(SSL_ROW).nextElementSibling.textContent
const editFrom = async (dlg) => {
  fireEvent.click(within(dlg).getByRole('button', { name: 'Edit' }))
  return screen.findByRole('dialog', { name: /Edit HTTP Monitor/ })
}
const saveForm = (form) => fireEvent.click(within(form).getByRole('button', { name: /^save$/i }))

describe('HttpMonitorPage — açık detaydan ikinci düzenleme ilkini geri almaz', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    api.monitoring.getHttpMonitors.mockReset()
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [A] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.triggerHttpCheck.mockResolvedValue({ success: true, data: { ...A } })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('kayıt sonrası açık detay sunucu satırını gösterir; ikinci Düzenle güncel değerle açılır ve ilk düzenlemeyi geri YAZMAZ', async () => {
    const A1 = { ...A, check_ssl_errors: true }
    const A2 = { ...A1, name: 'Renamed' }
    api.monitoring.updateHttpMonitor
      .mockResolvedValueOnce({ success: true, data: A1 })
      .mockResolvedValueOnce({ success: true, data: A2 })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const dlg = await openDetail()
    expect(sslRowValue(dlg)).toBe('None')

    // 1. düzenleme: SSL hata kontrolünü aç
    let form = await editFrom(dlg)
    fireEvent.click(within(form).getByRole('checkbox', { name: 'Check SSL errors' }))
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [A1] })
    saveForm(form)
    await waitFor(() => expect(api.monitoring.updateHttpMonitor).toHaveBeenCalledTimes(1))
    expect(api.monitoring.updateHttpMonitor.mock.calls[0][1].checkSslErrors).toBe(true)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Edit HTTP Monitor/ })).toBeNull())

    // Açık detayın kopyası kaydetme yanıtıyla tazelendi (eskiden "None" kalıyordu)
    const detail = screen.getByRole('dialog')
    expect(sslRowValue(detail)).toBe('Check SSL errors')
    // Kontrolü etkilemeyen değişiklik kayıt sonrası kontrol BAŞLATMAZ (kopyayı başka yol tazelemedi)
    expect(api.monitoring.triggerHttpCheck).not.toHaveBeenCalled()

    // 2. düzenleme AYNI pencereden: form güncel değerle açılır
    form = await editFrom(detail)
    expect(within(form).getByRole('checkbox', { name: 'Check SSL errors' })).toBeChecked()
    fireEvent.change(within(form).getByPlaceholderText(A.url), { target: { value: 'Renamed' } })
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [A2] })
    saveForm(form)
    await waitFor(() => expect(api.monitoring.updateHttpMonitor).toHaveBeenCalledTimes(2))
    const second = api.monitoring.updateHttpMonitor.mock.calls[1][1]
    expect(second.name).toBe('Renamed')
    expect(second.checkSslErrors).toBe(true)   // ilk düzenleme geri YAZILMADI
  })

  it('liste yenilemesi (başka kullanıcının değişikliği) sonrası detaydan Düzenle GÜNCEL satırla açılır', async () => {
    const hidden = Object.getOwnPropertyDescriptor(document, 'hidden')
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      const dlg = await openDetail()
      const loads = api.monitoring.getHttpMonitors.mock.calls.length
      api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{ ...A, check_ssl_errors: true }] })
      // Görünür sekmeye dönüş = liste tazelemesi (useVisibleInterval) — 60 sn'lik yoklamanın aynı yolu
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
      await waitFor(() => expect(api.monitoring.getHttpMonitors.mock.calls.length).toBeGreaterThan(loads))
      await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

      const form = await editFrom(dlg)
      expect(within(form).getByRole('checkbox', { name: 'Check SSL errors' })).toBeChecked()
    } finally {
      if (hidden) Object.defineProperty(document, 'hidden', hidden)
      else delete document.hidden
    }
  })

  it('detaydan "Sürdür" sonrası liste henüz tazelenmeden Düzenle: form ETKİN açılır (duraklatma geri yazılmaz)', async () => {
    const paused = { ...A, active: false }
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [paused] })
    api.monitoring.updateHttpMonitor.mockResolvedValue({ success: true, data: { ...paused, active: true } })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const dlg = await openDetail()
    const pending = deferred()
    api.monitoring.getHttpMonitors.mockImplementation(() => pending.p)   // tazeleme yolda kalır
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Resume|Sürdür)$/ }))
    await waitFor(() => expect(api.monitoring.updateHttpMonitor).toHaveBeenCalledWith(1, { active: true }))
    await waitFor(() => expect(within(screen.getByRole('dialog')).queryByRole('button', { name: /^(Resume|Sürdür)$/ })).toBeNull())

    const form = await editFrom(screen.getByRole('dialog'))
    expect(within(form).getByRole('checkbox', { name: 'Active' })).toBeChecked()
    await act(async () => { pending.resolve({ success: true, data: [{ ...paused, active: true }] }) })
  })

  it('değişiklik geçmişinden geri alma: liste yeniden yüklenir, açık detay ve sonraki Düzenle geri alınan değeri gösterir', async () => {
    const created = {
      seq: 0, kind: 'HTTP', resource_id: 1, resource_name: 'Example', event_type: 'CREATE', team_id: 5, team_name: 'SY-A',
      actor: 'N1', actor_name: 'Ada', ip_address: '10.0.0.1', user_agent: 'Mozilla/5.0', changes: null, note: null,
      at: '2026-10-01T09:00:00',
    }
    api.monitoring.getChanges.mockResolvedValue({ success: true, data: { changes: [created], total: 1, page: 0, size: 25 } })
    api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...created, snapshot: JSON.stringify({ checkSslErrors: true }) } })
    api.monitoring.restoreChange.mockResolvedValue({ success: true, data: { restored: true, fields: ['checkSslErrors'], skipped_masked: [] } })
    window.history.replaceState({}, '', '/?tab=http&monitor=1&mtab=changes')
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const detail = await screen.findByRole('dialog', { name: /www\.example\.com/ })
    expect(sslRowValue(detail)).toBe('None')

    const toggle = await waitFor(() => { const b = detail.querySelector('[data-open-detail]'); if (!b) throw new Error('satır yok'); return b })
    fireEvent.click(toggle)
    fireEvent.click(await within(detail).findByRole('button', { name: /Roll back/ }))
    const loads = api.monitoring.getHttpMonitors.mock.calls.length
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{ ...A, check_ssl_errors: true }] })
    fireEvent.change(await screen.findByRole('textbox'), { target: { value: 'yanlış ayar geri alındı' } })
    fireEvent.click(screen.getAllByRole('button', { name: /Roll back/ }).at(-1))

    await waitFor(() => expect(api.monitoring.restoreChange).toHaveBeenCalledWith('http', 1, 0, 'yanlış ayar geri alındı'))
    await waitFor(() => expect(api.monitoring.getHttpMonitors.mock.calls.length).toBeGreaterThan(loads))
    await waitFor(() => expect(sslRowValue(screen.getByRole('dialog'))).toBe('Check SSL errors'))

    const form = await editFrom(screen.getByRole('dialog'))
    expect(within(form).getByRole('checkbox', { name: 'Check SSL errors' })).toBeChecked()
  })
})

describe('ChangeHistoryTab — onRestored', () => {
  const row = {
    seq: 0, kind: 'PORT', resource_id: 4, resource_name: 'p', event_type: 'CREATE', team_id: 5, team_name: 'K',
    actor: 'N1', actor_name: 'Ada', ip_address: '10.0.0.1', user_agent: 'Mozilla/5.0', changes: null, note: null,
    at: '2026-10-01T09:00:00',
  }
  const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)
  const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 0)) })
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getChanges.mockResolvedValue({ success: true, data: { changes: [row], total: 1, page: 0, size: 25 } })
    api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...row, snapshot: JSON.stringify({ port: 443 }) } })
  })
  const openRow = async () =>
    fireEvent.click(await waitFor(() => { const b = document.querySelector('[data-open-detail]'); if (!b) throw new Error('yok'); return b }))
  const confirmRestore = async () => {
    fireEvent.click(await screen.findByRole('button', { name: /chg.restoreAction/ }))
    fireEvent.change(await screen.findByRole('textbox'), { target: { value: 'eski porta geri dönüldü' } })
    // act içinde: onayın çözdüğü geri alma akışı (restoring / liste) bu fonksiyonun dönüşünden önce otursun
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: /chg.restoreAction/ }).at(-1)) })
  }

  it('başarılı geri almadan sonra yanıtla BİR KEZ çağrılır', async () => {
    const data = { restored: true, fields: ['port'], skipped_masked: [] }
    api.monitoring.restoreChange.mockResolvedValue({ success: true, data })
    const onRestored = vi.fn()
    render(<ChangeHistoryTab t={t} kind="port" monitorId={4} canManage onRestored={onRestored} />)
    await openRow()
    await confirmRestore()
    await waitFor(() => expect(onRestored).toHaveBeenCalledTimes(1))
    expect(onRestored).toHaveBeenCalledWith(data)
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenCalledTimes(2))   // kendi listesi de tazelendi
    await settle()
  })

  it('başarısız geri almada çağrılmaz; sayfanın reddedilen tazelemesi geri almayı bozmaz', async () => {
    api.monitoring.restoreChange.mockResolvedValueOnce({ success: false, error: 'yetkiniz yok' })
    const onRestored = vi.fn(() => Promise.reject(new Error('liste düştü')))
    render(<ChangeHistoryTab t={t} kind="port" monitorId={4} canManage onRestored={onRestored} />)
    await openRow()
    await confirmRestore()
    await waitFor(() => expect(api.monitoring.restoreChange).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())   // not penceresi kapandı
    await settle()
    expect(onRestored).not.toHaveBeenCalled()

    api.monitoring.restoreChange.mockResolvedValueOnce({ success: true, data: { restored: true, fields: [], skipped_masked: [] } })
    await confirmRestore()   // satır açık kaldı (başarısız geri alma listeyi tazelemez)
    await waitFor(() => expect(onRestored).toHaveBeenCalledTimes(1))
    // geri alma kendi listesini yine tazeledi (reddedilen sayfa tazelemesi yakalandı, akış sürdü)
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenCalledTimes(2))
    await settle()
  })
})
