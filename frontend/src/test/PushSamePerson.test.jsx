import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, width.value] }))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : ''),
  api: withApiFallback({ me: { getPushHistory: vi.fn() } }),
}))
import { api } from '../api/client'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { PUSH_STATUS_KEYS, statusLabel } from '../components/admin/alerts/alertHistoryModel.js'
import { pushReasonLabel } from '../utils/pushPrefs.js'
import { buildView } from '../components/admin/whonotified/whoNotifiedModel.js'
import { pushFix } from '../components/admin/whonotified/PushDecisions.jsx'
import { PushStatusBadge } from '../components/admin/push/PushLogRows.jsx'
import { PushDeliveryGroup } from '../components/admin/alerts/AlertNotifications.jsx'
import ReNotifyConfirmModal from '../components/admin/alerts/ReNotifyConfirmModal.jsx'
import PushHistorySection from '../components/myactivity/PushHistorySection.jsx'

/**
 * Aynı kişi kuralı (2026-10-21, prod alarm #628): e-postası aynı aktif hesaplardan yalnız biri push alır; öbürü
 * SKIPPED_SAME_PERSON satırı + push'un gittiği hesap (kullanıcı adı). Etiket TR "Aynı kişiye başka hesapla gönderildi" /
 * EN "Sent to the same person via another account" — günlük, geçmiş, alarm detayı, tekrar bildir onayı ve "Kim alır?".
 */
const enT = (key, ...args) => {
  const v = EN[key]
  if (v == null) return key
  return args.reduce((s, a, i) => s.replace(`{${i}}`, a), v)
}

describe('aynı kişi — durum sözlükleri', () => {
  it('durum anahtarı bilinir; TR / EN etiketler kullanıcının metniyle aynı', () => {
    expect(PUSH_STATUS_KEYS.has('SKIPPED_SAME_PERSON')).toBe(true)
    expect(TR['alh.push.status.SKIPPED_SAME_PERSON']).toBe('Aynı kişiye başka hesapla gönderildi')
    expect(EN['alh.push.status.SKIPPED_SAME_PERSON']).toBe('Sent to the same person via another account')
    expect(TR['push.reason.SKIPPED_SAME_PERSON']).toBe('Aynı kişiye başka hesapla gönderildi')
    expect(statusLabel(enT, 'SKIPPED_SAME_PERSON')).toBe('Sent to the same person via another account')
    expect(pushReasonLabel('SKIPPED_SAME_PERSON', enT)).toBe('Sent to the same person via another account')
    expect(TR['userpush.decision.SKIPPED_SAME_PERSON']).toBeTruthy()
    expect(EN['userpush.decision.SKIPPED_SAME_PERSON']).toBeTruthy()
  })
})

describe('aynı kişi — ekranlar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    width.value = 0
  })

  it('push günlüğü satırı: neden + gönderilen hesap', () => {
    render(<PushStatusBadge t={enT} row={{ id: 1, status: 'SKIPPED_SAME_PERSON', kind: 'SKIPPED', same_person_username: 'N60146' }} />)
    expect(screen.getByText('Sent to the same person via another account')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="pl-same-person"]').textContent).toBe('Sent via account: N60146')
  })

  it('alarm detayı push grubu: atlanan hesabın yanında gönderilen hesap', () => {
    render(<PushDeliveryGroup rows={[{ id: 7, trigger: 'OPEN', status: 'SKIPPED_SAME_PERSON', username: 'MP60146',
      display_name: 'Emre Gökoğlu MP60146', same_person_username: 'N60146', created_at: '2026-10-19T18:18:00', message: 'x' }]} />)
    expect(document.querySelector('[data-push-status]').textContent).toBe('Sent to the same person via another account')
    expect(document.querySelector('[data-who-same]').textContent).toContain('Sent via account: N60146')
  })

  it('tekrar bildir onayı: atlanan hesap etiketle (ham kod değil) ve gönderilecek hesapla', () => {
    render(<ReNotifyConfirmModal domain="ocpaa" recipients={[]} sending={false} onSend={() => {}} onClose={() => {}}
      webhook={{ channel_enabled: true, block_reason: null, recipients: [
        { username: 'N60146', display_name: 'Emre N', status: 'PENDING' },
        { username: 'MP60146', display_name: 'Emre MP', status: 'SKIPPED_SAME_PERSON', same_person_of: 'N60146' },
      ] }} />)
    expect(screen.getByText('Sent to the same person via another account')).toBeInTheDocument()
    expect(screen.queryByText('SKIPPED_SAME_PERSON')).toBeNull()
    expect(document.querySelector('[data-slot="renotify-same-person"]').textContent).toBe('Sent via account: N60146')
  })

  it('Kim bilgilendirilir? push kararı: almaz + hangi hesabın aldığı', () => {
    const v = buildView({
      team_name: 'Takım', email_total: 0, push_access: 'FULL', push_settings: 'FULL',
      push: [
        { username: 'N60146', display_name: 'Emre N', decision: 'RECIPIENT', group: 'uzman', min_level: 'WARNING' },
        { username: 'MP60146', display_name: 'Emre MP', decision: 'SKIPPED_SAME_PERSON', same_person_of: 'N60146' },
      ],
    })
    const row = v.push.rows.find((r) => r.username === 'MP60146')
    expect(row.receives).toBe(false)
    expect(row.samePersonOf).toBe('N60146')
    expect(v.push.recipients.map((r) => r.username)).toEqual(['N60146'])
    expect(pushFix(enT, row, { access: 'FULL', settings: 'FULL', level: 'HIGH' }).text)
      .toBe("This person's account N60146 uses the same e-mail, so the push goes only to that account (one push per person).")
  })

  it('Push bildirimlerim: atlanan hesabın satırı nedeni ve gönderilen hesabı gösterir', async () => {
    const at = new Date(Date.now() - 60_000).toISOString().slice(0, 19)
    api.me.getPushHistory.mockResolvedValue({
      success: true, total: 1, page: 0, size: 25, days: 7, filter: 'all', login_code_note: true,
      data: [{ id: 3, at, trigger: 'OPEN', status: 'SKIPPED_SAME_PERSON', kind: 'not_sent', reason: 'SKIPPED_SAME_PERSON',
        own: true, team_decision: false, visible: true, scope: 'own', alert_level: 'HIGH', monitor_type: 'http', lang: 'tr',
        target: 'ocpaa.example.com', message: 'YÜKSEK: ocpaa', same_person_username: 'N60146' }],
      kpis: { sent: 0, pending: 0, not_sent: 1, not_sent_by_reason: { SKIPPED_SAME_PERSON: 1 }, summarized: 0,
        team_decisions: 0, team_decisions_by_reason: {} },
    })
    render(<PushHistorySection />)
    const table = await screen.findByTestId('ph-table')
    expect(within(table).getAllByText('Sent to the same person via another account').length).toBeGreaterThan(0)
    expect(table.querySelector('[data-slot="ph-same-person"]').textContent).toBe('Sent via account: N60146')
  })
})
