import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import ReNotifyConfirmModal from '../components/admin/alerts/ReNotifyConfirmModal.jsx'

/**
 * Tekrar bildir onayı — ALARM İLETİMİ satırları (2026-11-05). Sunucu önizlemesi (önizleme = gönderim) iletim alıcılarını
 * `kind: "FORWARD"` ile döndürür: e-postada adres başına, push'ta kişi başına. Satır "İletim · <alıcı takım>" rozetiyle
 * çizilir (sunucunun Türkçe rol metni basılmaz), tek tek çıkarılabilir ve seçim sayısına girer. Alan yoksa bugünkü ekran.
 */
const LONG_TEAM = 'Dijital Kanallar Operasyon ve Uygulama Destek Ekibi (Ankara / Bölge Müdürlüğü)'
const EMAILS = [
  { email: 'takim-a@example.com', name: 'Takım A', role: 'Takım maili', kind: 'TEAM' },
  { email: 'ops@example.com', name: LONG_TEAM, role: `İletim: ${LONG_TEAM}`, kind: 'FORWARD', team_name: LONG_TEAM },
  { email: 'ayse.yilmaz@example.com', name: 'Ağ Ekibi', role: 'İletim: Ağ Ekibi', kind: 'FORWARD' },
]
const WEBHOOK = {
  channel_enabled: true, block_reason: null,
  recipients: [
    { username: 'N00001', display_name: 'Örnek Kişi', status: 'PENDING' },
    { username: 'N00002', display_name: 'Ayşe Yılmaz (Ağ / Uzman)', status: 'PENDING', kind: 'FORWARD', team_name: 'Ağ Ekibi' },
    { username: 'N00003', display_name: 'Push Kapalı', status: 'SKIPPED_USER_OPT_OUT', kind: 'FORWARD', team_name: 'Ağ Ekibi' },
  ],
}
const draw = (props = {}) => render(<ReNotifyConfirmModal domain="ornek.example.com" recipients={EMAILS} webhook={WEBHOOK}
  sending={false} onSend={() => {}} onClose={() => {}} {...props} />)
const fwdBadges = () => [...document.querySelectorAll('[data-renotify="forward"]')]
const badgeText = (team) => EN['fwdx.badgeTeam'].replace('{0}', team)

describe('ReNotifyConfirmModal — iletim alıcıları', () => {
  it('e-posta iletim satırı: adres + "İletim · <takım>" (team_name, yoksa name); ham rol metni yok', () => {
    draw()
    const badges = fwdBadges()
    expect(badges.map((b) => b.textContent)).toEqual([badgeText(LONG_TEAM), badgeText('Ağ Ekibi'), badgeText('Ağ Ekibi'), badgeText('Ağ Ekibi')])
    expect(document.body).not.toHaveTextContent(`İletim: ${LONG_TEAM}`)
    expect(screen.getByText('ops@example.com')).toBeInTheDocument()
    // Uzun takım adı KESİLMEZ (rozet sarar)
    expect(badges[0].className).toMatch(/whitespace-normal/)
    expect(badges[0].className).not.toMatch(/truncate/)
    // Sahip satırı bugünkü gibi
    expect(screen.getByText('Takım maili')).toBeInTheDocument()
  })

  it('iletim alıcıları seçim sayısına girer ve tek tek çıkarılabilir (e-posta + push)', () => {
    const onSend = vi.fn()
    draw({ onSend })
    // 3 e-posta + 2 gönderilebilir push = 5
    expect(screen.getByText(EN['alh.renotifyModal.selectedTotal'].replace('{0}', '5').replace('{1}', '3').replace('{2}', '2'))).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /ops@example\.com/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /Ayşe Yılmaz \(Ağ \/ Uzman\)/ }))
    fireEvent.click(screen.getByRole('button', { name: EN['alh.renotifyModal.send'] }))
    expect(onSend).toHaveBeenCalledWith(['ops@example.com'], ['N00002'], { pushCount: 1 })
  })

  it('gönderilemeyecek iletim push satırı pasif kalır, rozetini taşır', () => {
    draw()
    const row = screen.getByRole('checkbox', { name: /Push Kapalı/ })
    expect(row).toBeDisabled()
    const label = row.closest('div')
    expect(within(label).getByText(badgeText('Ağ Ekibi'))).toBeInTheDocument()
  })

  it('iletim alanı olmayan (eski sunucu) önizleme bugünkü gibi', () => {
    draw({ recipients: [EMAILS[0]], webhook: { ...WEBHOOK, recipients: [WEBHOOK.recipients[0]] } })
    expect(fwdBadges()).toEqual([])
  })
})
