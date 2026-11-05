import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:           vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getKeywordMonitors:   vi.fn(),
      getCheckHistory:      vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createKeywordMonitor: vi.fn(),
      updateKeywordMonitor: vi.fn(),
      triggerKeywordCheck:  vi.fn(),
      testKeyword:          vi.fn(),
      keywordSameUrl:       vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Keyword izleme sayfası — zenginleştirme (kw fe1, 2026-11-05): düzenlemede YALNIZ değişen alanlar (B14) ve değişiklik yoksa
 * istek yok, yeni alanların istemci doğrulaması ve sunucu alan hatası alanın altında (kapalı bölüm açılır), test yükünde kayıtlı
 * sırlar (`useSaved`) ve test sonucunun yeni satırları, aynı URL uyarısı, detay "İstek ayarları" satırları, başlık kaldırma,
 * paylaşımla düzenleyende posta konusu kilidi.
 */
const ROW = {
  id: 1, name: 'Portal', url: 'https://www.example.com/', keyword: 'Giriş', operator: 'GTE', match_count: 1,
  group_name: 'Kurumsal', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, occurrences: 2,
  active: true, checked_at: '2026-11-05T08:00:00', interval_seconds: 300, timeout_ms: 10000, alert_level: 'WARNING',
  notify_email: true, notify_webhook: true, noc_notify: false, noc_group_ids: [], use_proxy: 'AUTO', case_sensitive: false,
  confirm_attempts: 3, confirm_interval_seconds: 30, recovery_checks: 3, recovery_interval_seconds: 30,
  match_scope: 'RAW', normalize_ws: false, method: 'GET', request_body: null, request_content_type: null,
  basic_auth_user: 'izleme', has_basic_auth_pass: true, user_agent_mode: 'DEFAULT', user_agent: null,
  expected_status: null, expected_final_url: null, follow_redirects: true, mail_subject_template: null,
  has_custom_headers: true, custom_header_names: ['X-Api-Key'], decoded_charset: 'windows-1254', can_edit: true, can_check: true,
}

const openEdit = async () => {
  await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
  fireEvent.click(screen.getByRole('button', { name: /— (düzenle|edit)$/i }))
  return screen.getAllByRole('dialog').at(-1)
}
const save = () => fireEvent.click(screen.getByRole('button', { name: /^(save|kaydet)$/i }))
const reqTrigger = () => screen.getByRole('button', { name: /^(Advanced request|Gelişmiş istek)/ })

describe('KeywordMonitorPage — zenginleştirme (kw fe1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [ROW] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.keywordSameUrl.mockResolvedValue({ success: true, data: { url: ROW.url, monitors: [], hidden_count: 0 } })
    api.monitoring.updateKeywordMonitor.mockImplementation(async (id, body) => ({ success: true, data: { ...ROW, ...body, id } }))
    api.monitoring.triggerKeywordCheck.mockResolvedValue({ success: true, data: ROW })
  })

  it('düzenleme: değişiklik yoksa istek GİTMEZ, "değişiklik yok" bildirilir ve form kapanır', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    save()
    expect(await screen.findByText(/No changes|Değişiklik yok/)).toBeInTheDocument()
    expect(api.monitoring.updateKeywordMonitor).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByRole('button', { name: /^(save|kaydet)$/i })).toBeNull())
  })

  it('düzenleme: YALNIZ değişen alan gider (ad) — active / takım / koşul geri yazılmaz; meta değişiklik kontrol başlatmaz', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    fireEvent.change(screen.getByPlaceholderText('https://www.example.com/'), { target: { value: 'Portal (yeni)' } })
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateKeywordMonitor.mock.calls[0]).toEqual([1, { name: 'Portal (yeni)' }])
    await act(async () => {})
    expect(api.monitoring.triggerKeywordCheck).not.toHaveBeenCalled()
  })

  it('düzenleme: durum kodu ön ayarı → yalnız expectedStatus gider ve taze kontrol koşulur', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.click(within(document.querySelector('[data-slot="kw-status-presets"]')).getByRole('radio', { name: /^2xx–3xx/ }))
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateKeywordMonitor.mock.calls[0][1]).toEqual({ expectedStatus: '2xx,3xx' })
    await waitFor(() => expect(api.monitoring.triggerKeywordCheck).toHaveBeenCalledWith(1))
  })

  it('istemci doğrulaması: özel UA boş → kapalı bölüm açılır, hata alanın altında, istek yok', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.click(within(document.querySelector('[data-slot="kw-ua-mode"]')).getByRole('button', { name: /^(Custom|Özel)$/ }))
    fireEvent.click(reqTrigger())   // bölümü kapat — hata bölümü yeniden açmalı
    expect(document.querySelector('[data-slot="kw-ua-mode"]')).toBeNull()
    save()
    await waitFor(() => expect(document.querySelector('[data-field="userAgent"]')).not.toBeNull())
    expect(document.querySelector('[data-field="userAgent"]')).toHaveTextContent(/custom user agent|Özel User-Agent/)
    expect(api.monitoring.updateKeywordMonitor).not.toHaveBeenCalled()
  })

  it('sunucu alan hatası (400 + field) bölümü açar ve alanın altına düşer', async () => {
    api.monitoring.updateKeywordMonitor.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'expectedFinalUrl',
      error: 'Son adres geçersiz: sunucunun kendi iletisi.' })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.change(document.querySelector('[data-field="expectedFinalUrl"] input'), { target: { value: '/hesabim' } })
    fireEvent.click(reqTrigger())
    save()
    await waitFor(() => expect(document.querySelector('[data-field="expectedFinalUrl"]')).toHaveTextContent('sunucunun kendi iletisi'))
    expect(api.monitoring.updateKeywordMonitor.mock.calls[0][1]).toEqual({ expectedFinalUrl: '/hesabim' })
    expect(screen.getByRole('button', { name: /^(save|kaydet)$/i })).toBeInTheDocument()   // form açık kalır
  })

  it('başlık kaldırma (global admin): customHeaders:null gider', async () => {
    render(<KeywordMonitorPage systemRole="ADMIN" globalAdmin teamId={5} teamName="SY-A" />)
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.click(screen.getByRole('checkbox', { name: /remove stored headers|kayıtlı başlıkları kaldır/i }))
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateKeywordMonitor.mock.calls[0][1]).toEqual({ customHeaders: null })
  })

  it('test: kayıtlı izlemede yazılmamış parola useSaved ile istenir; sonuçta durum kuralı / karakter kümesi / kayıtlı parola satırları', async () => {
    api.monitoring.testKeyword.mockResolvedValue({ success: true, data: {
      occurrences: 2, condition_met: true, ok: false, phrase: 'en az 1 kez', http_status: 503, response_ms: 40,
      status_accepted: false, final_url_ok: true, decoded_charset: 'windows-1254', match_scope: 'VISIBLE', used_saved: ['basicAuthPass'],
      failure_reason: 'HTTP_STATUS', failure_detail: 'Kabul edilen: 2xx; gelen: 503' } })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.click(within(document.querySelector('[data-slot="kw-status-presets"]')).getByRole('radio', { name: /^2xx–3xx/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(test|test et)$/i }))
    await waitFor(() => expect(api.monitoring.testKeyword).toHaveBeenCalled())
    expect(api.monitoring.testKeyword.mock.calls[0][0]).toMatchObject({ monitorId: 1, useSaved: ['basicAuthPass'], basicAuthUser: 'izleme',
      expectedStatus: '2xx,3xx', customHeaders: null })
    const extra = await waitFor(() => { const el = document.querySelector('[data-slot="kw-test-extra"]'); expect(el).not.toBeNull(); return el })
    expect(extra.querySelector('[data-line="status"]')).toHaveTextContent(/503/)
    expect(extra.querySelector('[data-line="charset"]')).toHaveTextContent('windows-1254')
    expect(extra.querySelector('[data-line="scope"]')).not.toBeNull()
    expect(extra.querySelector('[data-line="pass"]')).not.toBeNull()
    // condition_met true ama ok false (durum kuralı) → uyarı tonu "koşul sağlanmıyor"
    expect(screen.getByText(/^(Condition not met|Koşul sağlanmıyor)$/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="kw-test-diagnosis"]')).not.toBeNull()
  })

  it('test: eski sunucu used_saved döndürmezse "kayıtlı değer kullanılmadı" uyarısı', async () => {
    api.monitoring.testKeyword.mockResolvedValue({ success: true, data: { occurrences: 1, condition_met: true, phrase: 'en az 1 kez', http_status: 200 } })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    fireEvent.click(screen.getByRole('button', { name: /^(test|test et)$/i }))
    const extra = await waitFor(() => { const el = document.querySelector('[data-slot="kw-test-extra"]'); expect(el).not.toBeNull(); return el })
    expect(extra.querySelector('[data-line="notUsed"]')).toHaveTextContent(/older version|eski sürüm/)
  })

  it('test: hatalı durum kodu sunucuya gitmeden alanın altında', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.click(within(document.querySelector('[data-slot="kw-status-presets"]')).getByRole('radio', { name: /^(Custom|Özel)/ }))
    fireEvent.change(screen.getByRole('textbox', { name: /^(Status codes|Durum kodları)$/ }), { target: { value: '7xx' } })
    fireEvent.click(screen.getByRole('button', { name: /^(test|test et)$/i }))
    await waitFor(() => expect(document.querySelector('[data-field="expectedStatus"]')).toHaveTextContent('7xx'))
    expect(api.monitoring.testKeyword).not.toHaveBeenCalled()
  })

  it('aynı URL uyarısı formda (düzenlemede kaydın kendisi hariç)', async () => {
    api.monitoring.keywordSameUrl.mockResolvedValue({ success: true, data: { url: ROW.url,
      monitors: [{ id: 22, name: 'Portal kampanya', team_name: 'SY-B', keyword: 'Kampanya', active: true }], hidden_count: 2 } })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    const dlg = await openEdit()
    await waitFor(() => expect(dlg.querySelector('[data-slot="kw-same-url"]')).not.toBeNull(), { timeout: 2000 })
    expect(api.monitoring.keywordSameUrl).toHaveBeenCalledWith('https://www.example.com/', 1)
    expect(dlg.querySelector('[data-slot="kw-same-url"]')).toHaveTextContent('Portal kampanya')
  })

  it('Kopyala: kullanıcı adı ve istek ayarları taşınır, parola / başlık taşınmaz', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [{ ...ROW, method: 'POST', request_body: 'q=1',
      user_agent_mode: 'BROWSER', expected_status: '200', follow_redirects: false, match_scope: 'VISIBLE' }] })
    api.monitoring.createKeywordMonitor.mockResolvedValue({ success: true, data: {} })
    render(<KeywordMonitorPage systemRole="ADMIN" globalAdmin teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /— (kopyala|duplicate)$/i }))
    save()
    await waitFor(() => expect(api.monitoring.createKeywordMonitor).toHaveBeenCalled())
    const body = api.monitoring.createKeywordMonitor.mock.calls[0][0]
    expect(body).toMatchObject({ basicAuthUser: 'izleme', method: 'POST', requestBody: 'q=1', userAgentMode: 'BROWSER',
      expectedStatus: '200', followRedirects: false, matchScope: 'VISIBLE' })
    expect(body).not.toHaveProperty('basicAuthPass')
    expect(body).not.toHaveProperty('customHeaders')
  })

  it('detay "İstek ayarları": yöntem, eşleşme, Basic auth, UA, durum kuralı, yönlendirme, karakter kümesi; eski sunucuda satır yok', async () => {
    const { unmount } = render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-monitor-open]'))
    const card = await waitFor(() => { const el = document.querySelector('[data-slot="detail-info"]'); expect(el).not.toBeNull(); return el })
    expect(card).toHaveTextContent(/Method|Yöntem/)
    expect(card).toHaveTextContent(/izleme · (password stored|parola kayıtlı)/)
    expect(card).toHaveTextContent(/Off \(not part of the verdict\)|Kapalı \(karara girmez\)/)
    expect(card).toHaveTextContent('windows-1254')
    unmount()
    const old = { id: 1, name: 'Eski', url: 'https://old.example.com/', keyword: 'x', status: 'up', active: true, team_id: 5 }
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [old] })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-monitor-open]'))
    const card2 = await waitFor(() => { const el = document.querySelector('[data-slot="detail-info"]'); expect(el).not.toBeNull(); return el })
    expect(card2).not.toHaveTextContent(/Method|Yöntem/)
  })

  it('paylaşımla düzenlenen satırda posta konusu kilitli', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [{ ...ROW, routing_locked: true, shared_access: true }] })
    render(<KeywordMonitorPage systemRole="USER" teamId={9} teamName="SY-Z" myTeams={[{ id: 9, name: 'SY-Z' }]} />)
    const dlg = await openEdit()
    expect(within(dlg).getByRole('textbox', { name: /^(Custom subject \(optional\)|Özel konu \(isteğe bağlı\))$/ })).toBeDisabled()
    expect(dlg.querySelector('[data-slot="kw-subject-locked"]')).not.toBeNull()
  })
})
