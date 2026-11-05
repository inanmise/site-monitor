import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import AlertNotificationsPanel, { NotifLogCard } from '../components/admin/alerts/AlertNotifications.jsx'

/**
 * Bildirim geçmişi — ALARM İLETİMİ satırları (2026-11-05, `recipient_role = FORWARD`): sahip takımın alarmı başka takıma
 * AYRI posta olarak iletildi. Satır "İletim" rozetiyle ayrılır (ham "FORWARD" rolü basılmaz); kart tonu tetikten gelir;
 * iletilen posta teslim edildiyse bölüm başlığında "İletime de gitti" (7/24 deseni).
 */
const LONG_TEAM = 'Dijital Kanallar Operasyon ve Uygulama Destek Ekibi (Ankara / Bölge Müdürlüğü)'
const own = { id: 1, trigger: 'INITIAL', recipient_name: 'Takım A', recipient_email: 'takim-a@example.com', recipient_role: 'TEAM',
  email_status: 'SENT', sent_at: '2026-11-05T09:00:00', subject: 'Alarm', message: 'gövde' }
const fwd = (over = {}) => ({ id: 2, trigger: 'INITIAL', recipient_name: LONG_TEAM, recipient_email: 'ops@example.com',
  recipient_role: 'FORWARD', email_status: 'SENT', sent_at: '2026-11-05T09:00:01', subject: 'Alarm', message: 'gövde', ...over })

describe('NotifLogCard — iletim satırı', () => {
  it('"İletim" rozeti (ham FORWARD yok); kart tonu tetikten (açılış)', () => {
    render(<NotifLogCard log={fwd()} alertLevel="HIGH" />)
    const badge = document.querySelector('[data-slot="notif-forward"]')
    expect(badge).toHaveTextContent(EN['fwdx.role'])
    expect(document.body).not.toHaveTextContent('FORWARD')
    expect(document.querySelector('[data-notif-card]')).toHaveAttribute('data-notif-card', 'initial')
    expect(screen.getByText(LONG_TEAM)).toBeInTheDocument()
  })

  it('sahip satırı bugünkü rol rozetini taşır (iletim rozeti yok)', () => {
    render(<NotifLogCard log={own} alertLevel="HIGH" />)
    expect(document.querySelector('[data-slot="notif-forward"]')).toBeNull()
    expect(document.querySelector('[data-role="TEAM"]')).toBeTruthy()
  })

  it('çözüm postası da iletim satırı olarak ayrılır (çözüm tonu)', () => {
    render(<NotifLogCard log={fwd({ trigger: 'RESOLUTION' })} alertLevel="HIGH" />)
    expect(document.querySelector('[data-notif-card]')).toHaveAttribute('data-notif-card', 'resolution')
    expect(document.querySelector('[data-slot="notif-forward"]')).toBeTruthy()
  })
})

describe('AlertNotificationsPanel — başlık özeti', () => {
  const draw = (history) => render(<AlertNotificationsPanel history={history} loadingHistory={false} loadFailed={false} pushGroups={[]} alertLevel="HIGH" />)

  it('teslim edilen iletim postası → "İletime de gitti"', () => {
    draw([own, fwd()])
    expect(document.querySelector('[data-slot="notif-forward-summary"]')).toHaveTextContent(EN['fwdx.inHistory'])
  })

  it('kuyrukta yeniden denenecek (QUEUED_RETRY) da gitti sayılır', () => {
    draw([fwd({ email_status: 'QUEUED_RETRY: 421' })])
    expect(document.querySelector('[data-slot="notif-forward-summary"]')).toBeTruthy()
  })

  it('yalnız atlanan / başarısız iletim → özet yok; iletim yoksa da yok', () => {
    const { unmount } = draw([fwd({ email_status: 'SKIPPED: alıcı yok' }), fwd({ id: 3, email_status: 'FAILED: x' })])
    expect(document.querySelector('[data-slot="notif-forward-summary"]')).toBeNull()
    unmount()
    draw([own])
    expect(document.querySelector('[data-slot="notif-forward-summary"]')).toBeNull()
  })

  it('bölüm açılınca iletim kartı listede', () => {
    draw([own, fwd()])
    fireEvent.click(screen.getByRole('button', { name: new RegExp(EN['alh.notifModal.allHistory']) }))
    expect(document.querySelectorAll('[data-notif-card]')).toHaveLength(2)
    expect(document.querySelector('[data-slot="notif-forward"]')).toBeTruthy()
  })
})
