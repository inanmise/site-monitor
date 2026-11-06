import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import { EN } from '../i18n/en.js'

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
 * Keyword formu — hata avı düzeltmeleri (2026-11-06, H5#1 / H5#4):
 * - gizli alan: GET'e dönünce gövde / içerik türü null gider; değişim kapalıyken seçici doğrulanmaz ve gönderilmez — eskiden
 *   gizli alandaki hata kaydı SESSİZCE durduruyordu;
 * - güvenlik ağı: sunucunun alan hatası DOM'da olmayan bir alana düşerse ileti tostla söylenir (kayıt sessizce durmaz);
 * - "Test et": kullanıcı adı kayıtlıdan farklıysa kayıtlı parola istenmez ve sonuçta "parolayı yazın" notu çıkar.
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
  has_custom_headers: false, custom_header_names: [], change_detect: false, change_mask: null, change_selector: null,
  can_edit: true, can_check: true,
}

const openEdit = async () => {
  await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
  fireEvent.click(screen.getByRole('button', { name: /— (düzenle|edit)$/i }))
  return screen.getAllByRole('dialog').at(-1)
}
const save = () => fireEvent.click(screen.getByRole('button', { name: /^(save|kaydet)$/i }))
const reqTrigger = () => screen.getByRole('button', { name: /^(Advanced request|Gelişmiş istek)/ })
const renderPage = () => render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)

describe('KeywordMonitorPage — gizli alan doğrulaması (H5#1)', () => {
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

  it('POST → GET: yöntem kümesi gider, gövde ve içerik türü NULL (gizli kutunun eski değeri gönderilmez)', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [{ ...ROW, method: 'POST', request_body: 'q=1',
      request_content_type: 'text/plain; charset=utf-8' }] })
    renderPage()
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.click(within(document.querySelector('[data-slot="kw-req-method"]')).getByRole('button', { name: /^GET$/ }))
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateKeywordMonitor.mock.calls[0][1]).toMatchObject({ method: 'GET', requestBody: null, requestContentType: null })
  })

  it('değişim aç → hatalı seçici yaz → değişimi kapat: kayıt DURMAZ, seçici gönderilmez', async () => {
    renderPage()
    await openEdit()
    fireEvent.click(document.querySelector('[data-slot="kw-content-change-toggle"]'))
    fireEvent.change(document.querySelector('[data-slot="kw-content-selector"]'), { target: { value: '[class~=x]' } })
    fireEvent.click(document.querySelector('[data-slot="kw-content-change-toggle"]'))
    expect(document.querySelector('[data-slot="kw-content-selector"]')).toBeNull()
    fireEvent.change(screen.getByPlaceholderText('https://www.example.com/'), { target: { value: 'Portal (yeni)' } })
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateKeywordMonitor.mock.calls[0][1]).toEqual({ name: 'Portal (yeni)' })
  })

  it('güvenlik ağı: sunucu alan hatası DOM\'da olmayan alana düşerse ileti tostla görünür (sessiz durma yok)', async () => {
    api.monitoring.updateKeywordMonitor.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'changeSelector',
      error: 'Değişim bölgesi seçicisi geçersiz: sunucunun kendi iletisi.' })
    renderPage()
    await openEdit()
    fireEvent.change(screen.getByPlaceholderText('https://www.example.com/'), { target: { value: 'Portal (yeni)' } })
    save()
    expect(await screen.findByText(/sunucunun kendi iletisi/)).toBeInTheDocument()
    expect(document.querySelector('[data-field="changeSelector"]')).toBeNull()
    expect(screen.getByText(new RegExp(EN['keyword.form.unplaced'].replace('{0}', '').trim().slice(0, 30)))).toBeInTheDocument()
  })
})

describe('KeywordMonitorPage — "Test et": kullanıcı adı değişince kayıtlı parola (H5#4)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [ROW] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.keywordSameUrl.mockResolvedValue({ success: true, data: { url: ROW.url, monitors: [], hidden_count: 0 } })
    api.monitoring.testKeyword.mockResolvedValue({ success: true, data: { occurrences: 1, condition_met: true, ok: true,
      phrase: 'en az 1 kez', http_status: 200, used_saved: [] } })
  })

  it('kullanıcı adı kayıtlıdan farklı + parola boş → useSaved İSTENMEZ, sonuçta "parolayı yazın" notu', async () => {
    renderPage()
    await openEdit()
    fireEvent.click(reqTrigger())
    fireEvent.change(document.querySelector('[data-field="basicAuthUser"] input'), { target: { value: 'yeni-kullanici' } })
    fireEvent.click(screen.getByRole('button', { name: /^(test|test et)$/i }))
    await waitFor(() => expect(api.monitoring.testKeyword).toHaveBeenCalled())
    const body = api.monitoring.testKeyword.mock.calls[0][0]
    expect(body.basicAuthUser).toBe('yeni-kullanici')
    expect(body).not.toHaveProperty('useSaved')
    const extra = await waitFor(() => { const el = document.querySelector('[data-slot="kw-test-extra"]'); expect(el).not.toBeNull(); return el })
    expect(extra.querySelector('[data-line="passUser"]')).toHaveTextContent(EN['keyword.adv.savedPassUserChanged'])
    // İstenmediği için "kayıtlı değer kullanılmadı (eski sürüm)" uyarısı da YOK
    expect(extra.querySelector('[data-line="notUsed"]')).toBeNull()
  })

  it('kullanıcı adı aynı → kayıtlı parola useSaved ile istenir, not yok', async () => {
    api.monitoring.testKeyword.mockResolvedValue({ success: true, data: { occurrences: 1, condition_met: true, ok: true,
      phrase: 'en az 1 kez', http_status: 200, used_saved: ['basicAuthPass'] } })
    renderPage()
    await openEdit()
    fireEvent.click(screen.getByRole('button', { name: /^(test|test et)$/i }))
    await waitFor(() => expect(api.monitoring.testKeyword).toHaveBeenCalled())
    expect(api.monitoring.testKeyword.mock.calls[0][0]).toMatchObject({ useSaved: ['basicAuthPass'] })
    const extra = await waitFor(() => { const el = document.querySelector('[data-slot="kw-test-extra"]'); expect(el).not.toBeNull(); return el })
    expect(extra.querySelector('[data-line="passUser"]')).toBeNull()
  })
})
