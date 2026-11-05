import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ getActivityRelated: vi.fn() }),
}))
import { api } from '../api/client'
import RelatedActivityPanel from '../components/activity/related/RelatedActivityPanel.jsx'
import {
  factText, offsetText, suspectParams, suspectText, suspectTone, unwrapRelated,
} from '../components/activity/related/relatedModel.js'

/** "Aynı anda ne oldu?" (2026-11-05, F) — model + panel. Sunucu yanıtı sözleşme biçiminde taklit edilir. */
const tr = (key, ...args) => {
  let s = TR[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a)) })
  return s
}

const BODY = {
  anchor: { id: 10, time: '2026-11-05T08:00:00', type: 'HTTP', target: 'https://a.example.com', host: 'a.example.com', team_id: 1,
    failure_reason: 'DNS_NXDOMAIN', error_class: 'DNS' },
  window_s: 120,
  total: 3,
  by_reason: [{ reason: 'DNS_NXDOMAIN', count: 3 }],
  by_host: [{ host: 'a.example.com', count: 2 }, { host: 'b.example.com', count: 1 }],
  by_team: [{ team_id: 1, team_name: 'Ödeme Sistemleri ve Dijital Kanallar Platform Ekibi', count: 3 }],
  items: [
    { id: 11, monitor_type: 'PING', monitor_name: 'Ağ geçidi', target: '10.0.0.1', activity_time: '2026-11-05T08:00:45',
      result_status: 'ERROR', failure_reason: 'DNS_NXDOMAIN', team_id: 1, team_name: 'Ödeme' },
    { id: 12, monitor_type: 'DNS', monitor_name: 'odeme.example.com', target: 'odeme.example.com', activity_time: '2026-11-05T07:58:00',
      result_status: 'TIMEOUT', failure_reason: 'DNS_TIMEOUT', team_id: 1, team_name: 'Ödeme' },
  ],
  suspects: [{ code: 'DNS_WIDE', params: { monitors: 3, total_monitors: 4, share_pct: 75 } }, { code: 'NEW_FUTURE_CODE', params: {} }],
  facts: [{ code: 'FIRST_FAILURE_IN_DAYS', params: { days: 14, at_least: true } }, { code: 'LAST_SUCCESS', params: { at: '2026-11-05T07:55:00' } }],
}

beforeEach(() => { vi.clearAllMocks(); localStorage.setItem('site-monitor-lang', 'en') })

describe('relatedModel', () => {
  it('unwrapRelated: kök ya da `data` altındaki gövde; bilinmeyen kod süzülür; tanınmayan gövde null (okunamadı)', () => {
    expect(unwrapRelated({ success: true, ...BODY }).suspects.map((s) => s.code)).toEqual(['DNS_WIDE'])
    expect(unwrapRelated({ success: true, data: BODY }).items).toHaveLength(2)
    expect(unwrapRelated({ success: true, data: [] })).toBeNull()
    expect(unwrapRelated(null)).toBeNull()
  })

  it('şüphe parametreleri eş adlarla da okunur; ton: *_WIDE uyarı, SINGLE nötr', () => {
    expect(suspectParams({ monitors: 4, total_monitors: 6, share_pct: 66.7 })).toMatchObject({ count: 4, total: 6, pct: 67 })
    expect(suspectParams({ monitors: 4, ratio: 0.5 })).toMatchObject({ count: 4, pct: 50 })
    expect(suspectParams({ n: 2, percent: 66.6, team: 'A' })).toMatchObject({ count: 2, pct: 67, team: 'A' })
    expect(suspectTone('TLS_WIDE')).toBe('warning')
    expect(suspectTone('SAME_HOST')).toBe('info')
    expect(suspectTone('SINGLE')).toBe('neutral')
    expect(suspectText({ code: 'SAME_HOST', params: { host: 'h1', count: 2 } }, tr).hint).toContain('h1')
    // eksik parametre ham "{host}" değil "…"
    expect(suspectText({ code: 'SAME_HOST', params: {} }, tr).hint).not.toMatch(/\{host\}/)
  })

  it('olgu metni + zaman farkı', () => {
    expect(factText({ code: 'RECOVERED_AFTER', params: { seconds: 125 } }, tr)).toBe('2 dk sonra yeniden başarılı oldu.')
    expect(factText({ code: 'FAILURES_24H', params: { n: 4 } }, tr)).toBe('Son 24 saatte 4 hatalı kontrol.')
    expect(factText({ code: 'FIRST_FAILURE_IN_DAYS', params: { days: 30, at_least: true } }, tr)).toBe('Bu izleme en az 30 gündür ilk kez hata veriyor.')
    expect(factText({ code: 'STILL_FAILING', params: { since: 'X' } }, tr, (v) => `[${v}]`)).toMatch(/^\[X\] tarihinden beri/)
    expect(factText({ code: 'STILL_FAILING', params: {} }, tr)).toBe(TR['act.rel.fact.STILL_FAILING'])
    expect(offsetText('2026-11-05T08:00:00', '2026-11-05T08:00:45', tr)).toBe('+45 sn')
    expect(offsetText('2026-11-05T08:00:00', '2026-11-05T07:58:00', tr)).toBe('−2 dk')
    expect(offsetText('2026-11-05T08:00:00', '2026-11-05T08:00:00', tr)).toBe(TR['act.rel.sameTime'])
    expect(offsetText(null, '2026-11-05T08:00:00', tr)).toBe('')
  })
})

describe('RelatedActivityPanel', () => {
  it('şüpheler, olgular, dağılımlar ve satırlar; satıra dokunmak o aktiviteyi açar', async () => {
    api.getActivityRelated.mockResolvedValue({ success: true, data: BODY, timestamp: '2026-11-05T08:01:00' })   // be2 kabı
    const open = vi.fn()
    const { container } = render(<RelatedActivityPanel activityId={10} onOpenActivity={open} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-related"][data-state="ok"]')).not.toBeNull())
    expect(api.getActivityRelated).toHaveBeenCalledWith(10, 120)
    const suspects = container.querySelectorAll('[data-slot="act-rel-suspect"]')
    expect(suspects).toHaveLength(1)   // bilinmeyen gelecek kod ekrana ham düşmez
    expect(suspects[0]).toHaveAttribute('data-tone', 'warning')
    expect(suspects[0]).toHaveTextContent('Monitors failing DNS resolution: 3 of 4 (75%)')
    expect([...container.querySelectorAll('[data-slot="act-rel-fact"]')].map((f) => f.getAttribute('data-code')))
      .toEqual(['FIRST_FAILURE_IN_DAYS', 'LAST_SUCCESS'])
    expect(await screen.findByText('First failure of this monitor in at least 14 days.')).toBeInTheDocument()
    expect(container.querySelectorAll('[data-slot="act-rel-dist"]')).toHaveLength(3)
    // Uzun takım adı kesilmez
    const team = container.querySelector('[data-slot="act-rel-dist"][data-kind="team"]')
    expect(within(team).getByText(/Ödeme Sistemleri ve Dijital Kanallar Platform Ekibi/)).toBeInTheDocument()
    expect(team.querySelector('.truncate')).toBeNull()
    const items = container.querySelectorAll('[data-slot="act-rel-item"]')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('+45 sec')
    fireEvent.click(within(items[0]).getByRole('button'))
    expect(open).toHaveBeenCalledWith(11)
  })

  it('pencere değişince yeniden okur; eski pencerenin GEÇ gelen yanıtı yenisini ezmez', async () => {
    let resolveOld
    api.getActivityRelated
      .mockImplementationOnce(() => new Promise((r) => { resolveOld = r }))
      .mockResolvedValue({ success: true, ...BODY, total: 0, items: [], suspects: [{ code: 'SINGLE', params: {} }], by_reason: [], by_host: [], by_team: [] })
    const { container } = render(<RelatedActivityPanel activityId={10} onOpenActivity={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /±5 dk|±5 min/ }))
    await waitFor(() => expect(api.getActivityRelated).toHaveBeenLastCalledWith(10, 300))
    await waitFor(() => expect(container.querySelector('[data-slot="act-rel-suspect"][data-code="SINGLE"]')).not.toBeNull())
    expect(screen.getByText(EN['act.rel.none'])).toBeInTheDocument()
    await act(async () => { resolveOld({ success: true, ...BODY }) })
    expect(container.querySelector('[data-slot="act-rel-suspect"][data-code="DNS_WIDE"]')).toBeNull()
  })

  it('okunamazsa "okunamadı" + yeniden dene (asla "ilişkili hata yok"); 404 = kayıt yok / erişim yok (nötr), eski pod = bu sürümde yok', async () => {
    api.getActivityRelated.mockResolvedValueOnce({ success: false, status: 500, error: 'boom' })
    const { container, unmount } = render(<RelatedActivityPanel activityId={10} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-related"][data-state="error"]')).not.toBeNull())
    expect(screen.queryByText(EN['act.rel.none'])).toBeNull()
    api.getActivityRelated.mockResolvedValueOnce({ success: true, ...BODY })
    fireEvent.click(screen.getByRole('button', { name: /^(Retry|Yeniden dene)$/ }))
    await waitFor(() => expect(container.querySelector('[data-slot="act-related"][data-state="ok"]')).not.toBeNull())
    unmount()
    api.getActivityRelated.mockResolvedValueOnce({ success: false, status: 404, error: 'yok' })
    const second = render(<RelatedActivityPanel activityId={10} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(second.container.querySelector('[data-slot="act-related"][data-state="notFound"]')).not.toBeNull())
    expect(screen.getByText(EN['act.rel.notFound'])).toBeInTheDocument()
    second.unmount()
    // Eski pod: `/related` eşlemesi yok → SPA 404 tavsiyesi `code: NOT_FOUND` → "bu sürümde yok" (kayıt yok DEĞİL)
    api.getActivityRelated.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'Not found' })
    const third = render(<RelatedActivityPanel activityId={10} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(third.container.querySelector('[data-slot="act-related"][data-state="unsupported"]')).not.toBeNull())
    expect(screen.queryByText(EN['act.rel.notFound'])).toBeNull()
  })
})
