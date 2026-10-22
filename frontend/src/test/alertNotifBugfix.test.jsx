import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

/**
 * Alarm Geçmişi bildirim yüzeyleri — 2026-10-22 hata düzeltmeleri:
 *  - ALERTS-18 / UIM-7: paylaşımla okuyanda gizli push satırları (`username: null`, satır başına bir kişi, K4) "1 kişi"ye
 *    çöküyordu; sistem karar satırı (`-`) kişi sayılıyordu.
 *  - ALERTS-19: yalnız Teams/Slack kontak webhook'uyla ulaşılan alarm kırmızı "kimseye ulaşmadı" gösteriyordu.
 *  - L-E6: `QUEUED_RETRY: <SMTP metni>` rozete ham basılıyordu. L-H2: MANUAL_RESOLVE tetiği ham kod.
 *  - UIM-33 / UIM-32: DOMAIN_EXPIRY kaynağı; hızlı dönemde seçilen bitiş günü.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ formatDate: (s) => s ?? '', api: withApiFallback({}) }))

import { PushDeliveryGroup, EmailStatusBadge, NotifLogCard, mailTriggerText } from '../components/admin/alerts/AlertNotifications.jsx'
import { WhyOpenChips } from '../components/admin/alerts/AlertBadges.jsx'
import {
  buildAlertTimeline, pushPeopleCount, alertHref, alertSourceTab, presetStartDay,
} from '../components/admin/alerts/alertHistoryModel.js'
import { categoryFromAlertType } from '../components/admin/alerts/alertIncidentModel.js'

const masked = (n, extra = {}) => Array.from({ length: n }, (_, i) => ({ id: 100 + i, username: null, display_name: null,
  status: 'SENT', trigger: 'OPEN', dedupe_key: 'K', message: 'msg', sent_at: '2026-10-22T08:00:00', ...extra }))

describe('ALERTS-18 — gizli push alıcıları satır satır sayılır', () => {
  it('pushPeopleCount: 5 gizli satır = 5; adlar tekilleşir; sistem satırı kişi değil', () => {
    expect(pushPeopleCount(masked(5))).toBe(5)
    expect(pushPeopleCount([{ username: 'a' }, { username: 'a' }, { username: 'b' }])).toBe(2)
    expect(pushPeopleCount([{ username: '-' }, ...masked(2)])).toBe(2)
    expect(pushPeopleCount([{ username: '-' }])).toBe(0)
  })

  it('zaman çizelgesi "Push: 5 alıcı" (eskiden 1)', () => {
    const items = buildAlertTimeline({ alert: { id: 1, created_at: '2026-10-22T07:00:00' }, pushGroups: [masked(5)] })
    expect(items.find((x) => x.kind === 'push').people).toBe(5)
  })

  it('parti kartı "5 kişi" der ve listede TEK "5 gizli alıcı" rozeti; sistem satırı ayrı "katman kararı"', () => {
    const rows = [...masked(5), { id: 9, username: '-', status: 'SENT', trigger: 'OPEN', dedupe_key: 'K', message: 'msg' }]
    const { container } = render(<PushDeliveryGroup rows={rows} />)
    expect(container.textContent).toMatch(/5 (kişi|people|recipients)/i)
    fireEvent.click(container.querySelector('[data-notif-head]'))
    const hidden = container.querySelectorAll('[data-who="hidden"]')
    expect(hidden).toHaveLength(1)
    expect(hidden[0].textContent).toMatch(/5/)
    expect(container.querySelectorAll('[data-who="system"]')).toHaveLength(1)
  })

  it('tek gizli satır "Gizli alıcı" der, "—" değil', () => {
    const { container } = render(<PushDeliveryGroup rows={masked(1)} />)
    expect(container.querySelector('[data-who="hidden"]').textContent).toMatch(/gizli alıcı|hidden recipient/i)
  })
})

describe('ALERTS-19 — webhook ile ulaşılan alarm "kimseye ulaşmadı" değildir', () => {
  const base = { id: 1, alert_type: 'HTTP_DOWN', acknowledged: false, email_sent_count: 0, email_failed_count: 0 }
  const nobody = () => screen.queryByText(/kimseye ulaşmadı|reached nobody|nobody/i)

  it('e-posta 0, push yok, webhook 1 → kırmızı çip YOK, webhook çipi var', () => {
    const { container } = render(<WhyOpenChips alert={{ ...base, webhook_sent_count: 1 }} push={null} />)
    expect(nobody()).toBeNull()
    expect(container.querySelector('[data-why-webhook]')).not.toBeNull()
  })

  it('webhook alanı yoksa (eski sunucu) davranış eskisi gibi: kırmızı çip görünür', () => {
    const { container } = render(<WhyOpenChips alert={base} push={null} />)
    expect(nobody()).not.toBeNull()
    expect(container.querySelector('[data-why-webhook]')).toBeNull()
  })
})

describe('L-E6 / L-H2 — e-posta durum ve tetik etiketleri', () => {
  it('QUEUED_RETRY ham sunucu metni rozete basılmaz', () => {
    const { container } = render(<EmailStatusBadge status="QUEUED_RETRY: 421 4.7.0 Try again later (smtp.example.com)" />)
    expect(container.textContent).not.toMatch(/421|smtp\.example\.com/)
    expect(container.textContent).toMatch(/kuyruk|queued/i)
  })

  it('MANUAL_RESOLVE okunur etiketle gösterilir', () => {
    const t = (k) => k
    expect(mailTriggerText(t, 'MANUAL_RESOLVE')).toBe('alh.trigger.manualResolve')
    const { container } = render(<NotifLogCard log={{ id: 1, trigger: 'MANUAL_RESOLVE', recipient_name: 'Ekip', email_status: 'SENT', sent_at: '2026-10-22T08:00:00' }} />)
    expect(container.textContent).not.toMatch(/MANUAL_RESOLVE/)
  })
})

describe('UIM-33 / UIM-32 — DOMAIN_EXPIRY kaynağı ve hızlı dönem bitişi', () => {
  it('HTTP izlemesinin DOMAIN_EXPIRY alarmı HTTP sekmesine gider; olay kategorisi sertifika değil', () => {
    expect(alertSourceTab('DOMAIN_EXPIRY')).toBe('http')
    expect(alertHref({ alert_type: 'DOMAIN_EXPIRY', domain: 'https://x.example.com' })).toBe('?tab=http&q=' + encodeURIComponent('https://x.example.com'))
    expect(categoryFromAlertType('DOMAIN_EXPIRY')).not.toBe('CERTIFICATE')
    expect(alertSourceTab('EXPIRY')).toBeNull()   // sertifika alarmı değişmez
  })

  it('presetStartDay: dönemin başladığı yerel gün; dönem değilse boş', () => {
    const now = new Date(2026, 9, 22, 10, 0, 0).getTime()
    expect(presetStartDay('7d', now)).toBe('2026-10-15')
    expect(presetStartDay('30d', now)).toBe('2026-09-22')
    expect(presetStartDay('24h', now)).toBe('2026-10-21')
    expect(presetStartDay('2026-10-01', now)).toBe('')
  })
})
