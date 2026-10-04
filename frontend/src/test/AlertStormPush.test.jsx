import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

/**
 * Fırtına push'u ↔ alarm bağı (2026-10-04, kullanıcı isteği: "push fırtınaya devredilse bile fırtına ile giden push mesajı
 * ilgili alarmla ilişkilendirilsin — alarmın geçmişinden ne zaman iletildiğini göreyim").
 * Pinlenenler: model (yanıt güvenliği, zaman çizelgesi olayı, başlık/neden), alarm detayının "Fırtına push'u" bloğu
 * (kart başına sonuç + kişi sayısı + tahmin rozeti), zaman çizelgesi olayı ("Fırtına #12 push'u iletildi · 2 kişi")
 * ve alıcılara açılması (durum rozetleriyle), fırtına numarasının fırtına ayrıntısını açması, "henüz gitmedi" ve boş durum.
 */

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null && typeof t[prop] !== 'function' ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {}, monitoring: { storm: {} } }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => false, canEdit: () => false, canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))

import { api } from '../api/client'
import { AlertDetailBody } from '../components/admin/alerts/AlertDetail.jsx'
import { buildAlertTimeline } from '../components/admin/alerts/alertHistoryModel.js'
import {
  normalizeStormPush, stormPushTimelineItems, stormPushHeadline, stormPushReason, stormPushOutcome, showStormPushSection,
} from '../components/admin/alerts/stormPushModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

const fill = (dict) => (key, ...args) => String(dict[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))
const tr = fill(TR)
/** Arayüz varsayılan dili İngilizce (ürün kararı 2026-10-02) — çizilen metinler EN sözlüğünden. */
const en = fill(EN)

const ALERT = {
  id: 414, domain: 'site-a.example.com', alert_type: 'HTTP_DOWN', alert_level: 'WARNING', resolved: false, acknowledged: false,
  created_at: '2026-10-04T08:00:00', team_id: 1, storm_id: 12, message: 'UYARI: site-a.example.com erişilemiyor',
}

const SENT_ITEM = {
  storm_id: 12, team_id: 1, push_key: 'storm:12:INITIAL', trigger: 'INITIAL', day: null, inferred: false,
  covered_at: '2026-10-04T08:00:05', first_created_at: '2026-10-04T08:00:05', first_sent_at: '2026-10-04T08:00:07',
  last_sent_at: '2026-10-04T08:00:09', recipient_total: 3, sent: 2, failed: 1, pending: 0, not_sent: 0,
  counts: { SENT: 2, FAILED: 1 }, decision: null, message: '12 monitör birden erişilemez — Takım A', covered_alarms: 12, outcome: 'sent',
  recipients: [
    { id: 1, username: 'N00001', display_name: 'Kişi Bir', status: 'SENT', sent_at: '2026-10-04T08:00:07', attempts: 1 },
    { id: 2, username: 'N00002', display_name: 'Kişi İki', status: 'SENT', sent_at: '2026-10-04T08:00:09', attempts: 1 },
    { id: 3, username: 'N00003', display_name: 'Kişi Üç', status: 'FAILED', created_at: '2026-10-04T08:00:05', attempts: 3 },
  ],
}
const INFERRED_RESOLVE = {
  storm_id: 12, team_id: 1, push_key: 'storm-resolved:12', trigger: 'RESOLVE', inferred: true, covered_at: '2026-10-04T10:00:00',
  first_created_at: '2026-10-04T10:00:00', first_sent_at: null, last_sent_at: null, recipient_total: 0, sent: 0, failed: 0,
  pending: 0, not_sent: 0, counts: {}, decision: 'SKIPPED_NO_PRIOR', message: null, covered_alarms: null, outcome: 'skipped',
  recipients: [],
}

function mockDetail(data) {
  api.admin.getAlertStormPush.mockResolvedValue({ success: true, data })
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.incidents.byAlert.mockResolvedValue({ success: true, data: [] })
  api.monitoring.storm.detail.mockResolvedValue({ success: true, data: { id: 12, members: [], notifications: {} } })
})

describe('stormPushModel', () => {
  it('normalizeStormPush: hata / dizi dönen taklit / eski sunucu → boş bölüm; alanlar güvenle okunur', () => {
    expect(normalizeStormPush(null)).toMatchObject({ items: [], pending: [], handedOver: false })
    expect(normalizeStormPush({ success: true, data: [] }).items).toEqual([])
    expect(normalizeStormPush({ success: false, data: { items: [SENT_ITEM] } }).items).toEqual([])
    const n = normalizeStormPush({ success: true, data: { items: [SENT_ITEM], pending: [{ storm_id: 12 }], handed_over: true, push_individual: false } })
    expect(n.items).toHaveLength(1)
    expect(n.pending).toHaveLength(1)
    expect(n.handedOver).toBe(true)
  })

  it('zaman çizelgesi: bildirim başına bir stormPush olayı, ilk iletim anında; açılıştan sonra sıralanır', () => {
    const sp = normalizeStormPush({ success: true, data: { items: [INFERRED_RESOLVE, SENT_ITEM] } })
    expect(stormPushTimelineItems(sp).map((e) => e.id)).toEqual(['sp-storm-resolved:12|1', 'sp-storm:12:INITIAL|1'])
    const tl = buildAlertTimeline({ alert: ALERT, notifications: [], pushGroups: [], stormPush: sp })
    expect(tl.map((e) => e.kind)).toEqual(['opened', 'stormPush', 'stormPush'])
    expect(tl[1].item.push_key).toBe('storm:12:INITIAL')
    expect(tl[1].when).toBe('2026-10-04T08:00:07')
    // Parametresiz (eski çağıran) davranış değişmez
    expect(buildAlertTimeline({ alert: ALERT }).map((e) => e.kind)).toEqual(['opened'])
  })

  it('başlık + neden: iletildi / düzelme gönderilmedi (kanal kararı) / başarısız / kayıt yok', () => {
    expect(stormPushHeadline(tr, SENT_ITEM)).toBe("Fırtına #12 push'u iletildi · 2 kişi")
    expect(stormPushReason(tr, SENT_ITEM)).toBeNull()
    expect(stormPushHeadline(tr, INFERRED_RESOLVE)).toBe("Fırtına #12 düzelme push'u gönderilmedi")
    expect(stormPushReason(tr, INFERRED_RESOLVE)).toBe(TR['push.reason.SKIPPED_NO_PRIOR'] ?? 'SKIPPED_NO_PRIOR')
    // Teslimat satırı hiç yok (saklama süresi): "gönderilmedi" denmez
    expect(stormPushHeadline(tr, { storm_id: 5, outcome: 'none' })).toBe("Fırtına #5 push'u — teslimat kaydı yok")
    expect(stormPushReason(tr, { storm_id: 5, outcome: 'none' })).toBe(TR['alh.sp.reason.none'])
    expect(stormPushOutcome({ sent: 0, failed: 2 })).toBe('failed')
    expect(stormPushReason(tr, { sent: 0, failed: 2 })).toBe(TR['alh.sp.reason.failed'])
    expect(stormPushReason(tr, { sent: 0, counts: { RATE_LIMITED: 2 }, outcome: 'skipped' })).toBe(TR['push.reason.RATE_LIMITED'] ?? 'RATE_LIMITED')
  })

  it('bölüm görünürlüğü: fırtına bağı ya da bağ/devir izi varsa', () => {
    const empty = normalizeStormPush(null)
    expect(showStormPushSection({ storm_id: null }, empty)).toBe(false)
    expect(showStormPushSection({ storm_id: 3 }, empty)).toBe(true)
    expect(showStormPushSection({ storm_id: null }, { ...empty, handedOver: true })).toBe(true)
    expect(showStormPushSection({ storm_id: null }, null)).toBe(false)
  })
})

describe('alarm detayı — Fırtına push\'u', () => {
  it('blok: kapsayan iki bildirim kartı (sonuç, kişi sayısı, kapsanan alarm), tahmini rozet; zaman çizelgesinde iki olay', async () => {
    mockDetail({ alert_id: 414, handed_over: true, push_individual: false, items: [SENT_ITEM, INFERRED_RESOLVE], pending: [], storms: [] })
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-04T12:00:00Z')} />)

    await waitFor(() => expect(document.querySelectorAll('[data-slot="sp-card"]')).toHaveLength(2))
    expect(api.admin.getAlertStormPush).toHaveBeenCalledWith(414)
    const section = document.querySelector('[data-slot="alert-storm-push"]')
    expect(within(section).getByText(EN['alh.sp.title'])).toBeTruthy()
    const [sent, resolve] = section.querySelectorAll('[data-slot="sp-card"]')
    expect(sent.getAttribute('data-outcome')).toBe('sent')
    expect(sent.getAttribute('data-inferred')).toBe('false')
    expect(sent.textContent).toContain(en('alh.sp.sent', 12, 2))
    expect(sent.querySelector('[data-slot="sp-covered"]').textContent).toBe('12')
    expect(sent.textContent).toContain('12 monitör birden erişilemez')
    expect(resolve.getAttribute('data-inferred')).toBe('true')
    expect(resolve.querySelector('[data-slot="sp-inferred"]').textContent).toContain(EN['alh.sp.inferred'])
    expect(resolve.textContent).toContain(en('alh.sp.reason', EN['push.reason.SKIPPED_NO_PRIOR'] ?? 'SKIPPED_NO_PRIOR'))
    // Sol renk şeridi yok (kart kuralı)
    expect(sent.className).not.toMatch(/border-l-|before:/)

    const events = document.querySelectorAll('[data-slot="timeline-event"][data-kind="stormPush"]')
    expect(events).toHaveLength(2)
    expect(events[0].textContent).toContain('Storm #12 push delivered · 2 people')
  })

  it('zaman çizelgesi olayı alıcılara açılır: kişi satırları durum rozetleriyle (tablo)', async () => {
    mockDetail({ alert_id: 414, handed_over: true, items: [SENT_ITEM], pending: [], storms: [] })
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-04T12:00:00Z')} />)
    const ev = await waitFor(() => {
      const el = document.querySelector('[data-slot="timeline-event"][data-kind="stormPush"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(ev.querySelector('[data-slot="sp-recipient"]')).toBeNull()
    fireEvent.click(within(ev).getByRole('button', { name: en('alh.sp.showRecipients', 3) }))
    await waitFor(() => expect(ev.querySelectorAll('[data-slot="sp-recipient"]')).toHaveLength(3))
    expect(ev.querySelector('[data-slot="sp-recipients-table"]')).toBeTruthy()
    const rows = [...ev.querySelectorAll('[data-slot="sp-recipient"]')]
    expect(rows.map((r) => r.getAttribute('data-status'))).toEqual(['SENT', 'SENT', 'FAILED'])
    expect(rows[0].textContent).toContain('N00001')
    expect(rows[2].textContent).toContain(EN['alh.push.status.FAILED'])
    fireEvent.click(within(ev).getByRole('button', { name: EN['alh.sp.hideRecipients'] }))
    await waitFor(() => expect(ev.querySelectorAll('[data-slot="sp-recipient"]')).toHaveLength(0))
  })

  it('fırtına numarası mevcut fırtına ayrıntısını açar', async () => {
    mockDetail({ alert_id: 414, handed_over: true, items: [SENT_ITEM], pending: [], storms: [] })
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-04T12:00:00Z')} />)
    const link = await waitFor(() => {
      const el = document.querySelector('[data-slot="sp-card"] [data-slot="sp-storm-link"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(link.getAttribute('aria-label')).toBe(en('alh.sp.openStorm', 12))
    fireEvent.click(link)
    await waitFor(() => expect(api.monitoring.storm.detail).toHaveBeenCalledWith(12))
  })

  it('"henüz fırtına push\'u gitmedi": açık fırtınaya açılış push\'undan sonra katılan alarm — sıradaki tekrar zamanıyla', async () => {
    mockDetail({ alert_id: 414, handed_over: true, items: [], storms: [],
      pending: [{ storm_id: 12, team_id: 1, joined_at: '2026-10-04T08:30:00', next_realert_at: '2026-10-05T08:00:00' }] })
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-04T12:00:00Z')} />)
    const p = await waitFor(() => {
      const el = document.querySelector('[data-slot="sp-pending"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(p.textContent).toContain(en('alh.sp.pending', 12))
    expect(p.querySelector('[data-slot="sp-storm-link"]')).toBeTruthy()
    expect(document.querySelector('[data-slot="sp-empty"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="timeline-event"][data-kind="stormPush"]')).toHaveLength(0)
  })

  it('boş durum: fırtınaya bağlı ama kapsayan push yok; bireysel kipte devredilmemişse "tek tek gönderildi"', async () => {
    mockDetail({ alert_id: 414, handed_over: true, push_individual: false, items: [], pending: [], storms: [] })
    const { unmount } = render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-04T12:00:00Z')} />)
    await waitFor(() => expect(document.querySelector('[data-slot="sp-empty"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="sp-empty"]').textContent).toContain(EN['alh.sp.none'])
    unmount()

    mockDetail({ alert_id: 414, handed_over: false, push_individual: true, items: [], pending: [], storms: [] })
    render(<AlertDetailBody alert={ALERT} nowMs={Date.parse('2026-10-04T12:00:00Z')} />)
    await waitFor(() => expect(document.querySelector('[data-slot="sp-empty"]')?.textContent).toContain(EN['alh.sp.individual']))
  })

  it('fırtınayla ilgisi olmayan alarmda blok YOK; uç patlarsa detay yine açılır', async () => {
    api.admin.getAlertStormPush.mockRejectedValue(new Error('boom'))
    render(<AlertDetailBody alert={{ ...ALERT, storm_id: null }} nowMs={Date.parse('2026-10-04T12:00:00Z')} />)
    await waitFor(() => expect(api.admin.getAlertStormPush).toHaveBeenCalled())
    await waitFor(() => expect(document.querySelector('[data-slot="alert-timeline"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="alert-storm-push"]')).toBeNull()
    expect(screen.getAllByText(EN['alh.ev.opened']).length).toBeGreaterThan(0)
  })
})
