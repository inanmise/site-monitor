import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → Alarm akışı → otomatik IP engeli (2026-10-31): ayar grubu (aç / kapa, eşik, süre, katla), eşik < sınır kuralı
// alanın altında (istek gitmez), "Engellenen IP'ler" kartı (etkin önce + kalan süre, biten / elle kaldırılan geçmiş),
// "Engeli kaldır" onay → istek → liste güncellenir, süren engel yoksa bilgi tostu, liste okunamazsa "okunamadı" (asla
// "engel yok"), eski sunucu (alanlar yok) → grup ve kart çizilmez.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    alarmFeed: {
      get: vi.fn(), save: vi.fn(), preview: vi.fn(), requests: vi.fn(), request: vi.fn(), exportRequests: vi.fn(),
      blocks: vi.fn(), unblock: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import AlarmFeedSettings from '../components/admin/alarmfeed/AlarmFeedSettings.jsx'

const SETTINGS = {
  enabled: true, min_level: 'WARNING', team_ids: [], types: [], resolved_minutes: 15, resolved_mode: 'status', clear_text: 'Clear',
  severity_critical: 'Critical', severity_high: 'Major', severity_warning: 'Warning', rci_mode: 'target_type', source: 'SiteMonitor',
  rcitype: '', mail_separator: 'semicolon', allowed_ips: [], rate_per_minute: 30, max_rows: 2000, store_body: true,
  block_enabled: true, block_threshold: 120, block_minutes: 15, block_escalate: true, block_exempt_ips: [],
}
const isoAt = (ms) => new Date(ms).toISOString().slice(0, 19)
const NOW = Date.now()
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 LoopingPoller/1.0'
const ACTIVE = {
  ip: '10.20.30.40', blocked_at: isoAt(NOW - 2 * 60_000), blocked_until: isoAt(NOW + 13 * 60_000), state: 'active', remaining_seconds: 780,
  minutes: 15, hits: 143, threshold: 120, block_count: 2, last_user_agent: UA, blocked_by_pod: 'pod-a', unblocked_at: null, unblocked_by: null,
  local_only: false,
}
const OLD = {
  ip: '2001:db8:85a3::8a2e:370:7334', blocked_at: isoAt(NOW - 3 * 3600_000), blocked_until: isoAt(NOW - 2 * 3600_000), state: 'expired',
  remaining_seconds: 0, minutes: 15, hits: 400, threshold: 120, block_count: 1, last_user_agent: 'curl/8.4.0', blocked_by_pod: 'pod-b',
  unblocked_at: null, unblocked_by: null, local_only: false,
}
const LIFTED = { ...OLD, ip: '10.9.9.9', state: 'unblocked', unblocked_at: isoAt(NOW - 3600_000), unblocked_by: 'root' }
const data = (extra = {}) => ({
  endpoint: '/api/public/alarm-feed', base_url: 'https://sitemonitor.example.com', settings: SETTINGS,
  limits: { resolved_minutes: [1, 1440], rate_per_minute: [1, 600], max_rows: [100, 10000], text_max: 60, allowed_ips_max: 100,
    block_threshold: [10, 6000], block_minutes: [1, 1440] },
  log_retention_days: 90, teams: [], types: [],
  status: { last_call_at: isoAt(NOW - 30_000), last_call_ip: '10.20.30.40', last_outcome: 'BLOCKED', calls_24h: 900, ok_24h: 600,
    refused_24h: 300, errors_24h: 0, distinct_ips_24h: 3, avg_duration_ms_24h: 4, last_row_count: null, log_write_failures: 0 },
  callers: [], blocks: [OLD, ACTIVE, LIFTED], active_blocks: 1,
  ...extra,
})
const ok = (d) => ({ success: true, data: d })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.alarmFeed.get.mockResolvedValue(ok(data()))
  api.alarmFeed.requests.mockResolvedValue(ok({ items: [], total: 0, page: 0, size: 25 }))
})

async function mount() {
  render(<AlarmFeedSettings />)
  await waitFor(() => expect(slot('af-root')).toHaveAttribute('data-state', 'ok'))
}
const saveBar = () => slot('settings-save-bar', slot('af-settings'))
const saveBtn = () => within(saveBar()).getByRole('button', { name: /^Kaydet$/ })

describe('AlarmFeedSettings — otomatik engel ayarları', () => {
  it('grup çizilir: anahtar, eşik, süre, katla + özet; yalnız değişen alan gönderilir', async () => {
    await mount()
    expect(slot('af-grp-block')).toBeInTheDocument()
    expect(slot('af-f-block_enabled')).toHaveAttribute('data-on', 'true')
    expect(slot('af-f-block_threshold')).toHaveValue('120')
    expect(slot('af-f-block_minutes')).toHaveValue('15')
    expect(slot('af-block-summary')).toHaveTextContent('Dakikada 30 çağrıyı aşan IP 429 alır; 120 çağrıyı aşan IP 15 dk engellenir.')
    api.alarmFeed.save.mockResolvedValue(ok(data({ settings: { ...SETTINGS, block_minutes: 30 } })))
    fireEvent.change(slot('af-f-block_minutes'), { target: { value: '30' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.alarmFeed.save).toHaveBeenCalledWith({ block_minutes: 30 }))
  })

  it('eşik dakikalık sınırdan küçükse hata EŞİK alanının altında, istek gitmez; sınır büyütülünce hata SINIR alanında', async () => {
    await mount()
    fireEvent.change(slot('af-f-block_threshold'), { target: { value: '20' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="block_threshold"]'))
      .toHaveTextContent('Engel eşiği (20 / dk) dakikalık çağrı sınırından (30 / dk) küçük olamaz'))
    expect(api.alarmFeed.save).not.toHaveBeenCalled()
    fireEvent.change(slot('af-f-block_threshold'), { target: { value: '120' } })
    fireEvent.change(slot('af-f-rate_per_minute'), { target: { value: '200' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="rate_per_minute"]'))
      .toHaveTextContent('dakikalık çağrı sınırından (200 / dk) küçük olamaz'))
    expect(api.alarmFeed.save).not.toHaveBeenCalled()
  })

  it('kapatılınca özet "kapalı" der', async () => {
    await mount()
    fireEvent.click(within(slot('af-f-block_enabled')).getByRole('switch'))
    expect(slot('af-block-summary')).toHaveAttribute('data-on', 'false')
    expect(slot('af-block-summary')).toHaveTextContent('Otomatik engelleme kapalı')
  })

  it('eski sunucu: engel alanları ve liste yok → grup ve kart çizilmez, Kaydet kapalı kalır', async () => {
    const legacy = { ...SETTINGS }
    for (const k of ['block_enabled', 'block_threshold', 'block_minutes', 'block_escalate']) delete legacy[k]
    const d = data({ settings: legacy })
    delete d.blocks
    delete d.active_blocks
    api.alarmFeed.get.mockResolvedValue(ok(d))
    await mount()
    expect(slot('af-grp-block')).toBeNull()
    expect(slot('af-blocks')).toBeNull()
    expect(saveBtn()).toBeDisabled()
  })
})

describe('AlarmFeedSettings — engellenen IP listesi', () => {
  it('etkin engel önce (kalan süre, tetikleyen, engel sayısı, uzun UA tam); biten ve elle kaldırılan geçmiş; sayı rozeti', async () => {
    await mount()
    const rows = slots('af-block')
    expect(rows.map((r) => r.getAttribute('data-ip'))).toEqual(['10.20.30.40', OLD.ip, '10.9.9.9'])
    expect(rows[0]).toHaveAttribute('data-state', 'active')
    expect(within(rows[0]).getByText(/Engelli · 13 dk kaldı/)).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('Dakikada 143 çağrı (eşik 120)')
    expect(rows[0]).toHaveTextContent(UA)
    expect(within(rows[0]).getByRole('button', { name: 'Engeli kaldır: 10.20.30.40' })).toBeInTheDocument()
    expect(rows[1]).toHaveTextContent('Süresi doldu')
    expect(within(rows[1]).queryByRole('button', { name: /Engeli kaldır/ })).toBeNull()
    expect(rows[2]).toHaveTextContent('Elle kaldırıldı')
    expect(rows[2]).toHaveTextContent('root kaldırdı')
    expect(slot('af-block-active')).toHaveTextContent('1 IP şu an engelli')
    expect(slot('af-block-mode')).toHaveAttribute('data-state', 'on')
    // Çağrı sonucu BLOCKED okunur etiketle
    expect(within(slot('af-last-call')).getByText('Engellendi')).toBeInTheDocument()
  })

  it('Engeli kaldır: onay → istek → liste sunucunun yanıtıyla güncellenir + başarı tostu', async () => {
    await mount()
    api.alarmFeed.unblock.mockResolvedValue({ success: true, message: 'Engel kaldırıldı: 10.20.30.40',
      data: { blocks: [{ ...ACTIVE, state: 'unblocked', unblocked_by: 'root', unblocked_at: isoAt(NOW) }, OLD], active_blocks: 0 } })
    fireEvent.click(within(slots('af-block')[0]).getByRole('button', { name: 'Engeli kaldır: 10.20.30.40' }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent('10.20.30.40 IP\'sinin engeli hemen kalkar')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Engeli kaldır' }))
    await waitFor(() => expect(api.alarmFeed.unblock).toHaveBeenCalledWith('10.20.30.40'))
    await waitFor(() => expect(slots('af-block')[0]).toHaveAttribute('data-state', 'unblocked'))
    expect(slot('af-block-active')).toHaveTextContent('0 IP şu an engelli')
    expect(await screen.findByText('Engel kaldırıldı: 10.20.30.40')).toBeInTheDocument()
  })

  it('onayda Vazgeç → istek yok; süren engel yoksa (404) bilgi tostu ve liste yenilenir', async () => {
    await mount()
    fireEvent.click(within(slots('af-block')[0]).getByRole('button', { name: 'Engeli kaldır: 10.20.30.40' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.alarmFeed.unblock).not.toHaveBeenCalled()
    api.alarmFeed.unblock.mockResolvedValue({ success: false, status: 404, code: 'ALARM_FEED_BLOCK_NOT_FOUND',
      error: 'Bu IP için süren bir engel yok — süresi dolmuş ya da başka bir yönetici kaldırmış olabilir.',
      data: { blocks: [OLD], active_blocks: 0 } })
    fireEvent.click(within(slots('af-block')[0]).getByRole('button', { name: 'Engeli kaldır: 10.20.30.40' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Engeli kaldır' }))
    expect(await screen.findByText(/Bu IP için süren bir engel yok/)).toBeInTheDocument()
    await waitFor(() => expect(slots('af-block')).toHaveLength(1))
  })

  it('liste okunamazsa "okunamadı" + Tekrar dene (asla "engel yok"); tekrar deneme listeyi getirir', async () => {
    api.alarmFeed.get.mockResolvedValue(ok(data({ blocks: null, active_blocks: null, blocks_error: 'DataAccessResourceFailureException' })))
    await mount()
    expect(slot('af-blocks-failed')).toHaveTextContent('Engel listesi okunamadı')
    expect(slot('af-blocks-empty')).toBeNull()
    expect(slot('af-block-active')).toBeNull()
    api.alarmFeed.blocks.mockResolvedValue(ok({ blocks: [], active_blocks: 0 }))
    fireEvent.click(slot('af-blocks-retry'))
    await waitFor(() => expect(slot('af-blocks-empty')).toHaveTextContent('Engellenen IP yok'))
  })

  it('izin listesindeki IP engellenmişse rozet + uyarı; etkin liste kesildiyse "en az N" ve uyarı', async () => {
    api.alarmFeed.get.mockResolvedValue(ok(data({ blocks: [{ ...ACTIVE, allow_listed: true }, OLD], active_blocks: 1000,
      active_truncated: true })))
    await mount()
    expect(within(slots('af-block')[0]).getByText('İzin listesinde')).toBeInTheDocument()
    expect(slot('af-block-allowlisted-note')).toHaveTextContent('10.20.30.40 IP izin listesinde')
    expect(slot('af-block-truncated')).toBeInTheDocument()
    expect(slot('af-block-active')).toHaveTextContent('En az 1 IP şu an engelli')
  })

  it('muaf IP alanı: geçersiz giriş alanın altında; geçerli CIDR eklenip yalnız o alan kaydedilir', async () => {
    await mount()
    const box = slot('af-f-block_exempt_ips')
    const input = within(box).getByRole('textbox')
    fireEvent.change(input, { target: { value: 'bozuk' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(document.querySelector('[data-field="block_exempt_ips"]')).toHaveTextContent('bozuk'))
    fireEvent.change(input, { target: { value: '10.20.30.0/24' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    api.alarmFeed.save.mockResolvedValue(ok(data({ settings: { ...SETTINGS, block_exempt_ips: ['10.20.30.0/24'] } })))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.alarmFeed.save).toHaveBeenCalledWith({ block_exempt_ips: ['10.20.30.0/24'] }))
  })

  it('otomatik engel kapalıyken kart "kapalı" notu gösterir', async () => {
    api.alarmFeed.get.mockResolvedValue(ok(data({ settings: { ...SETTINGS, block_enabled: false } })))
    await mount()
    expect(slot('af-block-mode')).toHaveAttribute('data-state', 'off')
    expect(slot('af-block-off-note')).toHaveTextContent('hiçbir IP engellenmez')
  })
})
