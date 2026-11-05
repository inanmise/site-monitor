import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
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
      keywordChange:        vi.fn(),
      keywordContentSeries: vi.fn(),
      keywordMatches:       vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
// Dönüştürme penceresi kendi testinde (ConvertToSyntheticDialog.test.jsx) — burada yalnız HANGİ izlemeyle açıldığı
vi.mock('../components/keyword/k6/ConvertToSyntheticDialog.jsx', () => ({
  default: ({ monitor, onClose }) => (
    <div role="dialog" aria-label="k6-convert">convert:{monitor?.id}<button type="button" onClick={onClose}>kapat</button></div>
  ),
}))
import { api } from '../api/client'

/**
 * Keyword sayfası — fe2 Aşama 2 bağlantıları (kw paket C / D / E2 / F): kural düzenleyici formda (eski izleme tek kuralla açılır,
 * basit tek kural eski alanlarla gider, özel kural listesi `rules` + `ruleLogic` ile), istemci + sunucu hataları `rules[i].x`
 * alanının altında (eski `keyword` hatası kural #1'e taşınır, değişen kuralın iletisi kalkar), içerik kontrolleri (boyut +
 * değişim), test sonucunda kural listesi, detayda "İçerik" sekmesi, "N kural · VE" özeti ve "Sentetik izlemeye dönüştür".
 */
const ROW = {
  id: 1, name: 'Portal', url: 'https://www.example.com/', keyword: 'Giriş', operator: 'GTE', match_count: 1,
  group_name: 'Kurumsal', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, occurrences: 2,
  active: true, checked_at: '2026-11-05T08:00:00', interval_seconds: 300, timeout_ms: 10000, alert_level: 'WARNING',
  notify_email: true, notify_webhook: true, noc_notify: false, noc_group_ids: [], use_proxy: 'AUTO', case_sensitive: false,
  confirm_attempts: 3, confirm_interval_seconds: 30, recovery_checks: 3, recovery_interval_seconds: 30,
  match_scope: 'RAW', normalize_ws: false, method: 'GET', follow_redirects: true, can_edit: true, can_check: true,
  // be2: etkin kural listesi — eski izlemede kural #1'den türetilmiş tek kural (rules_custom false)
  rules: [{ id: 'r1', kind: 'TEXT', pattern: 'Giriş', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: null }],
  rules_custom: false, rule_logic: 'AND', min_bytes: null, max_bytes: null, change_detect: false, change_mask: [], change_selector: null,
}
const CUSTOM = {
  ...ROW, id: 2, url: 'https://shop.example.com/', name: 'Mağaza',
  rules: [
    { id: 'r1', kind: 'TEXT', pattern: 'Sepete ekle', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: 'Satın alma düğmesi' },
    { id: 'r-ab12', kind: 'REGEX', pattern: 'Hata\\s+\\d{3}', case_sensitive: false, operator: 'LTE', count: 0, critical: null, scope: null, label: null },
  ],
  rules_custom: true, rule_logic: 'AND', keyword: 'Sepete ekle',
  rule_results: [{ id: 'r1', ok: true, count: 2 }, { id: 'r-ab12', ok: true, count: 0 }],
}

const slot = (s, root = document) => root.querySelector(`[data-slot="${s}"]`)
const slots = (s, root = document) => [...root.querySelectorAll(`[data-slot="${s}"]`)]
const field = (k) => document.querySelector(`[data-field="${k}"]`)
const save = () => fireEvent.click(screen.getByRole('button', { name: /^(save|kaydet)$/i }))
async function openEditOf(name) {
  await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
  const card = [...document.querySelectorAll('[data-slot="card"]')].find((c) => c.textContent.includes(name))
  fireEvent.click(within(card).getByRole('button', { name: /— (düzenle|edit)$/i }))
  return screen.getAllByRole('dialog').at(-1)
}

describe('KeywordMonitorPage — kural / içerik bağlantıları (kw fe2 Aşama 2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [ROW, CUSTOM] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.keywordSameUrl.mockResolvedValue({ success: true, data: { url: ROW.url, monitors: [], hidden_count: 0 } })
    api.monitoring.updateKeywordMonitor.mockImplementation(async (id, body) => ({ success: true, data: { ...ROW, ...body, id } }))
    api.monitoring.createKeywordMonitor.mockImplementation(async (body) => ({ success: true, data: { ...ROW, ...body, id: 99 } }))
    api.monitoring.triggerKeywordCheck.mockResolvedValue({ success: true, data: ROW })
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: false } })
    api.monitoring.keywordContentSeries.mockResolvedValue({ success: true, data: { points: [], capped: false } })
  })

  it('eski izleme tek kuralla açılır; yalnız desen değişince yalnız `keyword` gider (rules gönderilmez)', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEditOf(ROW.name)
    expect(slots('kw-rule-card')).toHaveLength(1)
    expect(slot('kw-rule-logic')).toBeNull()
    fireEvent.change(slot('kw-rule-pattern'), { target: { value: 'Giriş yap' } })
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    const [, body] = api.monitoring.updateKeywordMonitor.mock.calls[0]
    expect(body.keyword).toBe('Giriş yap')
    expect('rules' in body).toBe(false)
  })

  it('kural ekle + VEYA: `rules` (temiz: sayı sayı, etiket kırpılmış) + `ruleLogic` gider; eski koşul alanları gitmez', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEditOf(ROW.name)
    fireEvent.click(slot('kw-rule-add'))
    const second = slots('kw-rule-card')[1]
    fireEvent.change(slot('kw-rule-pattern', second), { target: { value: 'Kampanya' } })
    fireEvent.click(screen.getByRole('button', { name: 'OR — any' }))
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    const [, body] = api.monitoring.updateKeywordMonitor.mock.calls[0]
    expect(body.ruleLogic).toBe('OR')
    expect(body.rules).toHaveLength(2)
    expect(body.rules[0]).toMatchObject({ id: 'r1', pattern: 'Giriş', count: 1, operator: 'GTE' })
    expect(body.rules[1]).toMatchObject({ pattern: 'Kampanya', count: 1, operator: 'GTE', kind: 'TEXT' })
    expect(typeof body.rules[1].count).toBe('number')
    for (const k of ['keyword', 'operator', 'matchCount', 'caseSensitive']) expect(k in body).toBe(false)
  })

  it('özel kural listesi tek basit kurala inerse `rules: null` + eski koşul alanları gider (sözleşme §6.6)', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    const dlg = await openEditOf(CUSTOM.name)
    expect(slots('kw-rule-card', dlg)).toHaveLength(2)
    fireEvent.click(slots('kw-rule-delete', dlg)[1])
    fireEvent.click(await screen.findByRole('button', { name: 'Delete rule' }))
    await waitFor(() => expect(slots('kw-rule-card', dlg)).toHaveLength(1))
    fireEvent.change(slot('kw-rule-label', dlg), { target: { value: '' } })
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    const [, body] = api.monitoring.updateKeywordMonitor.mock.calls[0]
    expect(body.rules).toBeNull()
    expect(body).toMatchObject({ keyword: 'Sepete ekle', operator: 'GTE', matchCount: 1, caseSensitive: false })
  })

  it('istemci hatası (boş desen, geçersiz düzenli ifade) alanın altında; kayıt isteği gitmez', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEditOf(ROW.name)
    fireEvent.change(slot('kw-rule-pattern'), { target: { value: '' } })
    save()
    await waitFor(() => expect(field('rules[0].pattern')).toHaveTextContent('Enter the text or pattern to search for.'))
    fireEvent.click(screen.getByRole('button', { name: 'Regular expression' }))
    fireEvent.change(slot('kw-rule-pattern'), { target: { value: '(ab' } })
    expect(field('rules[0].pattern').querySelector('[data-slot="field-error"]')).not.toBeNull()
    save()
    expect(api.monitoring.updateKeywordMonitor).not.toHaveBeenCalled()
  })

  it('sunucu hatası: `rules[1].pattern` alanın altında, kural değişince kalkar; eski `keyword` hatası kural #1\'e taşınır', async () => {
    api.monitoring.updateKeywordMonitor.mockResolvedValueOnce({ success: false, code: 'VALIDATION_FAILED', field: 'rules[1].pattern',
      error: 'Desen çok karmaşık (RE2 program sınırı aşıldı); sadeleştirin.' })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    const dlg = await openEditOf(CUSTOM.name)
    fireEvent.change(slots('kw-rule-pattern', dlg)[1], { target: { value: '(a+)+b' } })
    save()
    await waitFor(() => expect(field('rules[1].pattern')).toHaveTextContent('Desen çok karmaşık'))
    // Kural #1'i değiştirmek #2'nin iletisini SİLMEZ; #2'yi değiştirmek siler
    fireEvent.change(slots('kw-rule-pattern', dlg)[0], { target: { value: 'Sepete ekle!' } })
    expect(field('rules[1].pattern')).toHaveTextContent('Desen çok karmaşık')
    fireEvent.change(slots('kw-rule-pattern', dlg)[1], { target: { value: 'a+b' } })
    expect(field('rules[1].pattern')).not.toHaveTextContent('Desen çok karmaşık')

    api.monitoring.updateKeywordMonitor.mockResolvedValueOnce({ success: false, code: 'VALIDATION_FAILED', field: 'keyword',
      error: 'Bu takımda aynı URL ve kelimeyle izleme zaten var.' })
    save()
    await waitFor(() => expect(field('rules[0].pattern')).toHaveTextContent('aynı URL ve kelimeyle'))
  })

  it('içerik kontrolleri: en az > en fazla → alanın altında, istek yok; geçerli boyut + değişim gövdede', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await openEditOf(ROW.name)
    fireEvent.change(slot('kw-content-minBytes'), { target: { value: '9' } })
    fireEvent.change(slot('kw-content-maxBytes'), { target: { value: '2' } })
    save()
    await waitFor(() => expect(field('maxBytes')).toHaveTextContent('The minimum cannot be larger than the maximum.'))
    expect(api.monitoring.updateKeywordMonitor).not.toHaveBeenCalled()
    fireEvent.change(slot('kw-content-maxBytes'), { target: { value: '20' } })
    fireEvent.click(slot('kw-content-change-toggle'))
    save()
    await waitFor(() => expect(api.monitoring.updateKeywordMonitor).toHaveBeenCalled())
    const [, body] = api.monitoring.updateKeywordMonitor.mock.calls[0]
    expect(body).toMatchObject({ minBytes: 9000, maxBytes: 20000, changeDetect: true })
  })

  it('test: çok kural → kural listesi (seviye + boyut), tek kelime cümlesi yok; testte kurallar temiz gider', async () => {
    api.monitoring.testKeyword.mockResolvedValue({ success: true, data: {
      ok: false, occurrences: 2, http_status: 200, response_ms: 40, level: 'CRITICAL', size_ok: true, failure_reason: 'RULES_NOT_MET',
      rule_results: [{ id: 'r1', kind: 'TEXT', pattern_preview: 'Sepete ekle', count: 2, ok: true, level: 'NONE' },
        { id: 'r-ab12', kind: 'REGEX', pattern_preview: 'Hata\\s+\\d{3}', count: 7, ok: false, level: 'CRITICAL', note: 'SCAN_TRUNCATED' }],
    } })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    const dlg = await openEditOf(CUSTOM.name)
    fireEvent.click(within(dlg).getByRole('button', { name: /^test$/i }))
    await waitFor(() => expect(slot('kw-test-rules')).not.toBeNull())
    const payload = api.monitoring.testKeyword.mock.calls[0][0]
    expect(payload.rules).toHaveLength(2)
    expect(payload.ruleLogic).toBe('AND')
    const rows = slots('kw-rule-result', slot('kw-test-rules'))
    expect(rows.map((r) => r.getAttribute('data-ok'))).toEqual(['true', 'false'])
    expect(rows[0]).toHaveTextContent('Satın alma düğmesi')
    expect(slots('kw-rule-result-count')[1].getAttribute('data-at-least')).toBe('true')
    expect(slot('kw-rule-size')).not.toBeNull()
    // Tek kelimelik "« kelime » N bulundu" cümlesi yok (kural listesi var)
    expect(within(dlg).queryByText(/Sepete ekle.*2.*(found|bulundu)/)).toBeNull()
  })

  it('detay: "N kural · VE" özeti, İçerik sekmesi, "Sentetik izlemeye dönüştür" penceresi bu izlemeyle açılır', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
    const card = [...document.querySelectorAll('[data-slot="card"]')].find((c) => c.textContent.includes(CUSTOM.name))
    expect(within(card).getByText('2 rules · AND')).toBeInTheDocument()
    fireEvent.click(card.querySelector('[data-monitor-open]'))
    const detail = await screen.findByRole('dialog')
    expect(within(detail).getAllByText('2 rules · AND').length).toBeGreaterThan(0)
    fireEvent.mouseDown(within(detail).getByRole('tab', { name: /Content/ }))
    await waitFor(() => expect(slot('kw-content-tab')).not.toBeNull())
    expect(slots('kw-rule-result', slot('kw-content-tab'))).toHaveLength(2)
    fireEvent.click(slot('kw-k6-convert-open'))
    expect(await screen.findByText('convert:2')).toBeInTheDocument()
  })

  it('düzenleme yetkisi yoksa dönüştür düğmesi yok', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [{ ...CUSTOM, can_edit: false }] })
    render(<KeywordMonitorPage systemRole="USER" teamId={7} teamName="SY-B" myTeams={[{ id: 7, name: 'SY-B' }]} />)
    await waitFor(() => expect(document.querySelector('[data-monitor-open]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-monitor-open]'))
    await screen.findByRole('dialog')
    expect(slot('kw-k6-convert-open')).toBeNull()
  })
})
