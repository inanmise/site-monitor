import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import ReNotifyConfirmModal from '../components/admin/alerts/ReNotifyConfirmModal.jsx'

/**
 * Tekrar bildir onayı — izlemenin e-posta kanalı alarm anında kapalıysa (2026-10-22, sunucu `email_block_reason:
 * SKIPPED_MONITOR_OFF`; gönderim e-postayı atlar): e-posta bölümü genel "alıcı yok" yerine NEDENİ söyler, push alıcıları
 * yine seçilebilir. Alan yoksa bugünkü ekran.
 */
const WEBHOOK = { channel_enabled: true, block_reason: null, recipients: [{ username: 'N00001', display_name: 'Örnek Kişi', status: 'PENDING' }] }

describe('ReNotifyConfirmModal — e-posta kanalı izlemede kapalı', () => {
  it('neden yazılır (genel "alıcı yok" değil); push alıcısıyla gönderim açık, e-posta sayılmaz', () => {
    const onSend = vi.fn()
    render(<ReNotifyConfirmModal domain="ornek.example.com" recipients={[]} webhook={WEBHOOK} emailBlockReason="SKIPPED_MONITOR_OFF"
      sending={false} onSend={onSend} onClose={() => {}} />)
    expect(screen.getByText(EN['alh.renotifyModal.emailMonitorOff'])).toBeInTheDocument()
    expect(document.querySelector('[data-slot="renotify-email-monitor-off"]')).not.toBeNull()
    expect(screen.queryByText(EN['alh.renotifyModal.noRecipients'])).toBeNull()
    const send = screen.getByRole('button', { name: EN['alh.renotifyModal.send'] })
    expect(send).not.toBeDisabled()
    fireEvent.click(send)
    expect(onSend).toHaveBeenCalledWith([], [], { pushCount: 1 })
  })

  it('alan yoksa bugünkü genel "alıcı yok" metni', () => {
    render(<ReNotifyConfirmModal domain="ornek.example.com" recipients={[]} webhook={WEBHOOK}
      sending={false} onSend={() => {}} onClose={() => {}} />)
    expect(screen.getByText(EN['alh.renotifyModal.noRecipients'])).toBeInTheDocument()
    expect(document.querySelector('[data-slot="renotify-email-monitor-off"]')).toBeNull()
  })
})
