import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

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

  it('e-posta ve webhook kutuları DEĞİŞTİRİLEBİLİR (ikisi de gerçek kanal)', () => {
    const onChange = vi.fn()
    const { container } = draw({ onChange })
    const boxes = [...container.querySelectorAll('.http-channel input[type="checkbox"]')]

    // 4 döşeme: e-posta, SMS, sesli, webhook
    expect(boxes).toHaveLength(4)
    fireEvent.click(boxes[0])
    expect(onChange).toHaveBeenCalledWith({ notifyEmail: false })
    fireEvent.click(boxes[3])
    expect(onChange).toHaveBeenCalledWith({ notifyWebhook: false })
  })

  it('SMS ve Sesli arama PASİF — ürün kararı, kullanıcı bunlara tıklayıp umutlanmasın', () => {
    const { container } = draw()
    const boxes = [...container.querySelectorAll('.http-channel input[type="checkbox"]')]
    expect(boxes[1].disabled).toBe(true)
    expect(boxes[2].disabled).toBe(true)
    expect(boxes[0].disabled).toBe(false)
    expect(boxes[3].disabled).toBe(false)
  })

  it('HEDEF satırı doğruyu söyler: grup seçili değilse TAKIM adı', () => {
    draw({ groupId: '' })
    expect(screen.getAllByText('Takım A').length).toBeGreaterThan(0)
  })

  it('HEDEF satırı doğruyu söyler: grup seçiliyse GRUP (takım adı hedef olarak gösterilmez)', () => {
    // Yanlış hedef göstermek en kötü hata olurdu: kullanıcı "takıma gidecek" sanıp
    // aslında bir gruba giden alarmı yanlış kişilerde arardı.
    const { container } = draw({ groupId: 3, groupName: 'Nöbetçi Grup' })
    const target = container.querySelector('.http-ch-target')
    expect(target.textContent).toBe('Nöbetçi Grup')
  })

  it('onGroupChange verilmezse grup seçici çizilmez (kullanan formu zorlamaz)', () => {
    const { container } = draw({ onGroupChange: undefined })
    expect(container.querySelectorAll('.http-channel')).toHaveLength(4)
  })

  // ── Alarm seviyesi (2026-09-19): varsayılan Uyarı; Yüksek/Kritik seçilebilir; onAlertLevelChange yoksa çizilmez ──
  it('alarm seviyesi seçici: onAlertLevelChange verilince üç düğme, seçili olan aria-pressed; tıklama seviyeyi geri verir; verilmezse yok', () => {
    const onLevel = vi.fn()
    const { container } = draw({ alertLevel: 'WARNING', onAlertLevelChange: onLevel })
    const btns = [...container.querySelectorAll('.notify-level-btn')]
    expect(btns.map((b) => b.textContent)).toEqual(expect.arrayContaining([expect.stringMatching(/Uyarı|Warning/), expect.stringMatching(/Yüksek|High/), expect.stringMatching(/Kritik|Critical/)]))
    expect(btns[0].getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(btns[2])
    expect(onLevel).toHaveBeenCalledWith('CRITICAL')
    const { container: c2 } = draw({ alertLevel: 'bogus', onAlertLevelChange: onLevel })
    expect(c2.querySelector('.notify-level-btn--warning').getAttribute('aria-pressed')).toBe('true')   // bilinmeyen → Uyarı
    const { container: c3 } = draw({})
    expect(c3.querySelector('.notify-level')).toBeNull()
  })
})
