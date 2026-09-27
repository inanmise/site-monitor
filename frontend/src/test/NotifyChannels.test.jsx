import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

vi.mock('../api/client', () => ({
  api: { notificationGroups: { list: vi.fn().mockResolvedValue({ success: true, data: { groups: [] } }) } },
}))

import NotifyChannels from '../components/ui/NotifyChannels.jsx'

/**
 * Ortak bildirim bloğunun SÖZLEŞMESİ.
 *
 * Aynı iş dokuz formda üç farklı biçimde yazılmıştı ve DNS/Ping/Domain formlarında "E-posta"
 * kutusu hiç yoktu. Blok tek yere alındı; buradaki kurallar dokuz sayfa için birden geçerli.
 */
describe('NotifyChannels', () => {
  const draw = (props = {}) => render(
    <NotifyChannels
      notifyEmail notifyWebhook
      onChange={() => {}}
      teamLabel="Takım A" teamId={5} groupId="" onGroupChange={() => {}}
      {...props}
    />
  )

  // Kutular shadcn Checkbox (role="checkbox"): 4 döşeme sırasıyla e-posta, SMS, sesli, webhook.
  const channelBoxes = () => screen.getAllByRole('checkbox')

  it('e-posta ve webhook kutuları DEĞİŞTİRİLEBİLİR (ikisi de gerçek kanal)', () => {
    const onChange = vi.fn()
    draw({ onChange })
    const boxes = channelBoxes()

    // 4 döşeme: e-posta, SMS, sesli, webhook
    expect(boxes).toHaveLength(4)
    fireEvent.click(boxes[0])
    expect(onChange).toHaveBeenCalledWith({ notifyEmail: false })
    fireEvent.click(boxes[3])
    expect(onChange).toHaveBeenCalledWith({ notifyWebhook: false })
  })

  it('döşemenin ETİKETİNE tıklamak da kutuyu değiştirir (seçim kartı)', () => {
    const onChange = vi.fn()
    draw({ onChange, notifyEmail: false })
    fireEvent.click(screen.getByText(/^(E-posta|E-mail)$/))
    expect(onChange).toHaveBeenCalledWith({ notifyEmail: true })
  })

  it('SMS ve Sesli arama PASİF — ürün kararı, kullanıcı bunlara tıklayıp umutlanmasın', () => {
    draw()
    const boxes = channelBoxes()
    expect(boxes[1]).toBeDisabled()
    expect(boxes[2]).toBeDisabled()
    expect(boxes[0]).not.toBeDisabled()
    expect(boxes[3]).not.toBeDisabled()
  })

  it('HEDEF satırı doğruyu söyler: grup seçili değilse TAKIM adı', () => {
    draw({ groupId: '' })
    expect(screen.getAllByText('Takım A').length).toBeGreaterThan(0)
  })

  it('HEDEF satırı doğruyu söyler: grup seçiliyse GRUP (takım adı hedef olarak gösterilmez)', () => {
    // Yanlış hedef göstermek en kötü hata olurdu: kullanıcı "takıma gidecek" sanıp
    // aslında bir gruba giden alarmı yanlış kişilerde arardı.
    const { container } = draw({ groupId: 3, groupName: 'Nöbetçi Grup' })
    const target = container.querySelector('[data-slot="notify-target"]')
    expect(target.textContent).toBe('Nöbetçi Grup')
  })

  it('onGroupChange verilmezse grup seçici çizilmez (kullanan formu zorlamaz)', () => {
    const { container } = draw({ onGroupChange: undefined })
    expect(container.querySelectorAll('[data-slot="notify-channel"]')).toHaveLength(4)
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  // ── Alarm seviyesi (2026-09-19): varsayılan Uyarı; Yüksek/Kritik seçilebilir; onAlertLevelChange yoksa çizilmez ──
  it('alarm seviyesi seçici: onAlertLevelChange verilince üç düğme, seçili olan aria-pressed; tıklama seviyeyi geri verir; verilmezse yok', () => {
    const onLevel = vi.fn()
    const { unmount } = draw({ alertLevel: 'WARNING', onAlertLevelChange: onLevel })
    const group = screen.getByRole('group', { name: /alarm seviyesi|alert level/i })
    const btns = within(group).getAllByRole('button')
    expect(btns.map((b) => b.textContent)).toEqual(expect.arrayContaining([expect.stringMatching(/Uyarı|Warning/), expect.stringMatching(/Yüksek|High/), expect.stringMatching(/Kritik|Critical/)]))
    expect(btns[0].getAttribute('aria-pressed')).toBe('true')
    pressMenuTrigger(btns[2])
    expect(onLevel).toHaveBeenCalledWith('CRITICAL')
    unmount()
    const { container: c2, unmount: u2 } = draw({ alertLevel: 'bogus', onAlertLevelChange: onLevel })
    expect(c2.querySelector('[data-level="WARNING"]').getAttribute('aria-pressed')).toBe('true')   // bilinmeyen → Uyarı
    u2()
    const { container: c3 } = draw({})
    expect(c3.querySelector('[data-slot="notify-level"]')).toBeNull()
  })
})
