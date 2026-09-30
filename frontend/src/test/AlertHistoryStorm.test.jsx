import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { buildAlertTimeline, stormIdFromStatus, statusLabel, PUSH_STATUS_KEYS } from '../components/admin/alerts/alertHistoryModel.js'
import { StormBadge } from '../components/admin/alerts/AlertBadges.jsx'
import { EmailStatusBadge, mailTriggerText } from '../components/admin/alerts/AlertNotifications.jsx'

/**
 * Prod olayı 2026-09-30 (SY-Kurumsal Mimari, SCRIPTED_FAIL #412/#413/#414): alarm fırtınaya SESSİZCE bağlandığında ne
 * e-posta ne push gidiyor, ekranda da "neden" yoktu — "0 bildirim", push "önce bildirim gitmemişti". Artık:
 *  - alarm satırı/penceresi "Fırtına #N" rozeti taşır (storm_id),
 *  - bildirim günlüğündeki STORM tetikli satır zaman çizelgesinde "Bildirim fırtınaya devredildi" olayı olur,
 *  - "SKIPPED: <neden>" e-posta durumu nedeniyle "Atlandı — …" rozetiyle çizilir,
 *  - SKIPPED_STORM / SKIPPED_NO_TEAM push kararları okunur etiket alır.
 */
describe('Alarm Geçmişi — fırtına devri izi', () => {
  const ALERT = { id: 414, domain: 'OCPA - Response Time Anomalisi', alert_type: 'SCRIPTED_FAIL', alert_level: 'WARNING',
    created_at: '2026-09-30T13:45:29', resolved: true, resolved_at: '2026-09-30T13:54:05', resolved_by: 'system', storm_id: 7 }

  it('stormIdFromStatus: "SKIPPED: fırtına #17 — …" → 17; başka durum → null', () => {
    expect(stormIdFromStatus('SKIPPED: fırtına #17 — bireysel bildirim yerine toplu fırtına bildirimi')).toBe(17)
    expect(stormIdFromStatus('SKIPPED: alıcı yok')).toBeNull()
    expect(stormIdFromStatus(null)).toBeNull()
  })

  it('buildAlertTimeline: STORM tetikli günlük satırı "storm" olayı olur (fırtına no + geriye dönük işareti), e-posta olayı değil', () => {
    const notifications = [
      { id: 1, trigger: 'STORM', sent_at: '2026-09-30T13:45:29', recipient_name: 'SY-Kurumsal Mimari',
        email_status: 'SKIPPED: fırtına #7 — bireysel bildirim yerine toplu fırtına bildirimi (geriye dönük kayıt)' },
      { id: 2, trigger: 'RESOLUTION', sent_at: '2026-09-30T13:54:06', recipient_name: 'SY-Kurumsal Mimari', email_status: 'SENT' },
    ]
    const tl = buildAlertTimeline({ alert: ALERT, notifications, pushGroups: [] })
    const storm = tl.find((e) => e.kind === 'storm')
    expect(storm).toBeTruthy()
    expect(storm.stormId).toBe(7)
    expect(storm.backfilled).toBe(true)
    expect(tl.filter((e) => e.kind === 'mail')).toHaveLength(1)
    // Sıra: açılış → fırtına devri → çözüm → çözüm e-postası
    expect(tl.map((e) => e.kind)).toEqual(['opened', 'storm', 'resolved', 'mail'])
  })

  it('buildAlertTimeline: fırtına no durumdan okunamazsa alarmın storm_id\'si kullanılır', () => {
    const tl = buildAlertTimeline({ alert: ALERT, notifications: [{ id: 3, trigger: 'STORM', sent_at: ALERT.created_at, email_status: 'SKIPPED' }] })
    expect(tl.find((e) => e.kind === 'storm').stormId).toBe(7)
  })

  it('StormBadge: storm_id dolu → "Fırtına #7" rozeti (data-storm-id); boş → yok', () => {
    const { container, unmount } = render(<StormBadge stormId={7} />)
    const badge = container.querySelector('[data-slot="alert-storm"]')
    expect(badge).not.toBeNull()
    expect(badge).toHaveAttribute('data-storm-id', '7')
    expect(badge.textContent).toMatch(/7/)
    unmount()
    const { container: c2 } = render(<StormBadge stormId={null} />)
    expect(c2.querySelector('[data-slot="alert-storm"]')).toBeNull()
  })

  it('EmailStatusBadge: "SKIPPED: alıcı yok" → uyarı tonlu "Atlandı — alıcı yok"; SENT → başarı', () => {
    const { container } = render(<div><EmailStatusBadge status="SKIPPED: alıcı yok" /><EmailStatusBadge status="SENT" /></div>)
    const tones = [...container.querySelectorAll('[data-slot="badge"]')].map((b) => b.getAttribute('data-tone'))
    expect(tones).toEqual(['warning', 'success'])
    expect(screen.getByText(/alıcı yok/)).toBeInTheDocument()
  })

  it('push kararı etiketleri ve fırtına tetik adları çeviriden okunur (ham anahtar basılmaz)', () => {
    expect(PUSH_STATUS_KEYS.has('SKIPPED_STORM')).toBe(true)
    expect(PUSH_STATUS_KEYS.has('SKIPPED_NO_TEAM')).toBe(true)
    const t = (k) => ({ 'alh.push.status.SKIPPED_STORM': 'Atlandı — fırtına', 'alh.trigger.storm': 'Fırtına Devri',
      'alh.trigger.stormRealert': 'Fırtına Günlük Hatırlatma' })[k] ?? k
    expect(statusLabel(t, 'SKIPPED_STORM')).toBe('Atlandı — fırtına')
    expect(mailTriggerText(t, 'STORM')).toBe('Fırtına Devri')
    expect(mailTriggerText(t, 'STORM_REALERT')).toBe('Fırtına Günlük Hatırlatma')
  })
})
