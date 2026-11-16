import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import SmartDateTimeField from '../components/ui/SmartDateTimeField.jsx'
import { toIso, formatLocal, parseIso } from '../utils/smartDateTime.js'

// Yerel saatle kurulan anlar — beklenen ISO aynı Date'ten hesaplanır (CI UTC, yerel İstanbul: ikisinde de doğru).
const L = (y, mo, d, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi, 0, 0)
const NOW = L(2026, 11, 16, 15, 42)

function Harness({ initial = '', onChange, ...props }) {
  const [v, setV] = useState(initial)
  return (
    <>
      <label htmlFor="f">Oluş Zamanı</label>
      <SmartDateTimeField id="f" value={v} onChange={(x) => { setV(x); onChange?.(x) }} {...props} />
      <output data-testid="value">{v}</output>
    </>
  )
}
const input = () => screen.getByRole('textbox', { name: 'Oluş Zamanı' })
const value = () => screen.getByTestId('value').textContent

beforeEach(() => {
  // Yalnız Date sahte (zamanlayıcılar gerçek): hızlı seçimler ve "N önce" satırı sabit "şimdi"ye göre
  vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true })
  vi.setSystemTime(NOW)
})
afterEach(() => { vi.useRealTimers() })

describe('SmartDateTimeField — yazarak giriş', () => {
  it('gün VE saat yazılınca değer anında kaydedilir (yerel → UTC ISO); metin yazıldığı gibi kalır', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '16.11.2026 14:30' } })
    expect(value()).toBe(toIso(L(2026, 11, 16, 14, 30)))
    expect(input()).toHaveValue('16.11.2026 14:30')
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('yalnız saat: odak çıkınca mevcut günde kaydedilir ve biçimlenir', () => {
    render(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '1430' } })
    expect(value()).toBe(toIso(L(2026, 11, 10, 8, 20)))   // henüz kaydedilmedi
    fireEvent.blur(input())
    expect(value()).toBe(toIso(L(2026, 11, 10, 14, 30)))
    expect(input()).toHaveValue('10.11.2026 14:30')
  })

  it('Enter kaydeder (form gönderilmez), "dün 09:15" ve "şimdi" sözcükleri', () => {
    render(<Harness />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: 'dün 09:15' } })
    expect(value()).toBe(toIso(L(2026, 11, 15, 9, 15)))
    fireEvent.change(input(), { target: { value: 'şimdi' } })
    expect(fireEvent.keyDown(input(), { key: 'Enter' })).toBe(false)   // preventDefault: çevreleyen form gönderilmez
    expect(value()).toBe(toIso(NOW))
    expect(input()).toHaveValue('16.11.2026 15:42')
  })

  it('okunamayan metin önceki değeri değiştirmez; ne yazıldığı ve örnekler alanın altında, alan aria-invalid', () => {
    const initial = toIso(L(2026, 11, 10, 8, 20))
    render(<Harness initial={initial} />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '31.02.2026 10:00' } })
    fireEvent.blur(input())
    expect(value()).toBe(initial)
    expect(input()).toHaveValue('10.11.2026 08:20')
    const msg = document.querySelector('[data-slot="smart-datetime-problem"]')
    expect(msg.textContent).toMatch(/31\.02\.2026 10:00/)
    expect(msg.textContent).toMatch(/önceki değer korundu|previous value was kept/)
    expect(input()).toHaveAttribute('aria-invalid', 'true')
    expect(input().getAttribute('aria-describedby')).toContain(msg.id)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '11.11.2026 09:00' } })
    expect(document.querySelector('[data-slot="smart-datetime-problem"]')).toBeNull()
  })

  it('boşaltıp çıkmak değeri temizler; temizleme düğmesi yalnız clearable + değer varken', () => {
    const { rerender } = render(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} clearable />)
    expect(screen.getByRole('button', { name: /Tarihi temizle|Clear the date/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Tarihi temizle|Clear the date/ }))
    expect(value()).toBe('')
    expect(input()).toHaveValue('')
    rerender(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} />)   // zorunlu alan (clearable yok)
    expect(screen.queryByRole('button', { name: /Tarihi temizle|Clear the date/ })).toBeNull()
  })

  it('↑ 5 dk, Shift+↓ 1 saat adımlar', () => {
    render(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} />)
    fireEvent.focus(input())
    fireEvent.keyDown(input(), { key: 'ArrowUp' })
    expect(value()).toBe(toIso(L(2026, 11, 10, 8, 25)))
    fireEvent.keyDown(input(), { key: 'ArrowDown', shiftKey: true })
    expect(value()).toBe(toIso(L(2026, 11, 10, 7, 25)))
    expect(input()).toHaveValue('10.11.2026 07:25')
  })

  it('dışarıdan gelen değer (form sıfırlama) metni günceller', () => {
    function Outer() {
      const [v, setV] = useState(toIso(L(2026, 11, 10, 8, 20)))
      return (
        <>
          <label htmlFor="f">Oluş Zamanı</label>
          <SmartDateTimeField id="f" value={v} onChange={setV} />
          <button type="button" onClick={() => setV(toIso(L(2026, 1, 2, 3, 4)))}>reset</button>
        </>
      )
    }
    render(<Outer />)
    fireEvent.click(screen.getByRole('button', { name: 'reset' }))
    expect(input()).toHaveValue('02.01.2026 03:04')
  })
})

describe('SmartDateTimeField — hızlı seçim ve bağlam satırı', () => {
  it('çapa yokken: Şimdi / 15 dk önce …; tıklanınca değer ve "N önce · gün" satırı', () => {
    render(<Harness />)
    const group = screen.getByRole('group', { name: /Hızlı seçim|Quick pick/ })
    const chips = within(group).getAllByRole('button')
    expect(chips.map((c) => c.getAttribute('data-preset'))).toEqual(['now', 'ago-15', 'ago-30', 'ago-60', 'ago-120', 'yesterday'])
    fireEvent.click(within(group).getByRole('button', { name: /15 dk önce|15 min ago/ }))
    expect(value()).toBe(toIso(L(2026, 11, 16, 15, 27)))
    expect(input()).toHaveValue('16.11.2026 15:27')
    const ctx = document.querySelector('[data-slot="smart-datetime-context"]')
    expect(ctx.textContent).toMatch(/15 dk önce|15 min ago/)
    expect(ctx.textContent).toMatch(/pazartesi|Monday/i)
    expect(within(group).getByRole('button', { name: /15 dk önce|15 min ago/ })).toHaveAttribute('aria-pressed', 'true')
  })

  it('çapa (oluş zamanı) varken: +5 dk … +2 sa; gelecekteki an pasif; satır "oluştan N sonra"', () => {
    render(<Harness anchor={toIso(L(2026, 11, 16, 15, 0))} anchorLabel="Oluş Zamanı" />)
    const group = screen.getByRole('group', { name: /Hızlı seçim|Quick pick/ })
    const plus30 = within(group).getByRole('button', { name: /30 dk sonra \(esas: Oluş Zamanı\)|30 min after Oluş Zamanı/ })
    expect(within(group).getByRole('button', { name: /60 dk sonra|1 sa sonra|1 h after/ })).toBeDisabled()
    fireEvent.click(plus30)
    expect(value()).toBe(toIso(L(2026, 11, 16, 15, 30)))
    expect(document.querySelector('[data-slot="smart-datetime-context"]').textContent)
      .toMatch(/30 dk sonra \(esas: Oluş Zamanı\)|30 min after Oluş Zamanı/)
    // Soluk (gelecekteki) çiplerin nedeni görünür ve erişilebilir adda
    expect(document.querySelector('[data-slot="smart-datetime-presets-note"]').textContent).toMatch(/henüz gelmedi|have not come yet/)
    expect(within(group).getByRole('button', { name: /2 sa sonra|2 h after/ }).getAttribute('aria-label')).toMatch(/henüz gelmedi|has not come yet/)
  })

  it('çapadan önceki an uyarı tonunda; gelecekteki an "doğru mu?" uyarısı', () => {
    const { unmount } = render(<Harness initial={toIso(L(2026, 11, 16, 14, 0))} anchor={toIso(L(2026, 11, 16, 15, 0))} anchorLabel="Oluş" />)
    let ctx = document.querySelector('[data-slot="smart-datetime-context"]')
    expect(ctx).toHaveAttribute('data-warn', 'true')
    expect(ctx.textContent).toMatch(/1 sa önce|1 h before/)
    unmount()
    render(<Harness initial={toIso(L(2026, 11, 17, 10, 0))} />)
    ctx = document.querySelector('[data-slot="smart-datetime-context"]')
    expect(ctx).toHaveAttribute('data-warn', 'true')
    expect(ctx.textContent).toMatch(/Gelecekte|in the future/i)
  })

  it('disabled: yazılamaz, çip ve takvim düğmesi yok / pasif', () => {
    render(<Harness disabled initial={toIso(L(2026, 11, 10, 8, 20))} />)
    expect(input()).toBeDisabled()
    expect(screen.queryByRole('group', { name: /Hızlı seçim|Quick pick/ })).toBeNull()
    expect(screen.getByRole('button', { name: /Takvimden|Pick from the calendar/ })).toBeDisabled()
  })
})

describe('SmartDateTimeField — takvim ve saat paneli', () => {
  it('panel: ay / yıl açılır listeleri, saat ve dakika listeleri; gün, saat, dakika seçimi değeri günceller', async () => {
    render(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} />)
    fireEvent.click(screen.getByRole('button', { name: /Takvimden|Pick from the calendar/ }))
    const panel = await waitFor(() => {
      const p = document.querySelector('[data-slot="smart-datetime-panel"]')
      expect(p).toBeTruthy()
      return p
    })
    expect(panel.querySelectorAll('select').length).toBeGreaterThanOrEqual(2)   // captionLayout="dropdown": ay + yıl
    fireEvent.click(within(panel.querySelector('[role="grid"]')).getByText('12', { selector: 'button' }))
    await waitFor(() => expect(parseIso(value()).getDate()).toBe(12))
    expect(parseIso(value()).getHours()).toBe(8)                       // gün değişince saat korunur
    const hours = panel.querySelector('[data-slot="smart-datetime-hours"]')
    fireEvent.click(within(hours).getByRole('radio', { name: /^(Saat|Hour) 14$/ }))
    expect(parseIso(value()).getHours()).toBe(14)
    const minutes = panel.querySelector('[data-slot="smart-datetime-minutes"]')
    expect(within(minutes).getByRole('radio', { name: /^(Dakika|Minute) 20$/ })).toHaveAttribute('data-state', 'on')
    fireEvent.click(within(minutes).getByRole('radio', { name: /^(Dakika|Minute) 45$/ }))
    expect(formatLocal(parseIso(value()))).toBe('12.11.2026 14:45')
    expect(panel.querySelector('[data-slot="smart-datetime-panel-value"]').textContent).toBe('12.11.2026 14:45')
    fireEvent.click(within(panel).getByRole('button', { name: /^(Şimdi|Now)$/ }))
    expect(value()).toBe(toIso(NOW))
  })

  it('adımda olmayan dakika (42) listede seçili görünür', async () => {
    render(<Harness initial={toIso(L(2026, 11, 10, 8, 42))} />)
    fireEvent.click(screen.getByRole('button', { name: /Takvimden|Pick from the calendar/ }))
    const minutes = await waitFor(() => {
      const m = document.querySelector('[data-slot="smart-datetime-minutes"]')
      expect(m).toBeTruthy()
      return m
    })
    expect(within(minutes).getByRole('radio', { name: /^(Dakika|Minute) 42$/ })).toHaveAttribute('data-state', 'on')
  })
})

describe('SmartDateTimeField — inceleme düzeltmeleri (2026-11-17)', () => {
  it('odak halkası: giriş InputGroup denetim kancasını (data-slot="input-group-control") korur', () => {
    render(<Harness />)
    expect(input()).toHaveAttribute('data-slot', 'input-group-control')
    expect(input()).toHaveAttribute('data-sdt', 'input')
  })

  it('düzenlemeden odak çıkışı onChange çağırmaz — saniyeli değer (uyarıdan) korunur, form kirlenmez', () => {
    const onChange = vi.fn()
    const withSeconds = toIso(L(2026, 11, 10, 8, 20)).replace(/:00$/, ':45')
    render(<Harness initial={withSeconds} onChange={onChange} />)
    fireEvent.focus(input())
    fireEvent.blur(input())
    fireEvent.focus(input())
    fireEvent.keyDown(input(), { key: 'Enter' })
    // Aynı dakikayı yeniden yazmak da değeri değiştirmez
    fireEvent.change(input(), { target: { value: '10.11.2026 08:20' } })
    fireEvent.blur(input())
    expect(onChange).not.toHaveBeenCalled()
    expect(value()).toBe(withSeconds)
  })

  it('yarım yazılan saat / yıl anında kaydedilmez; okunamayan metin odaklanmadan ÖNCEKİ değeri geri getirir', () => {
    const before = toIso(L(2026, 11, 16, 14, 30))
    const onChange = vi.fn()
    render(<Harness initial={before} onChange={onChange} />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '16.11.2026 9' } })     // saat yarım
    fireEvent.change(input(), { target: { value: '16.11.2026 143' } })   // "1:43" olarak okunabilirdi
    fireEvent.change(input(), { target: { value: '16.11.20 14:30' } })   // yıl yeniden yazılıyor (2020 değil)
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.change(input(), { target: { value: '16.11.2026 09:05' } })  // tam yazım → anında
    expect(value()).toBe(toIso(L(2026, 11, 16, 9, 5)))
    fireEvent.change(input(), { target: { value: '16.11.2026 09:05x' } })
    fireEvent.blur(input())
    expect(value()).toBe(before)                                         // ara değer kalmadı
    expect(input()).toHaveValue('16.11.2026 14:30')
    expect(document.querySelector('[data-slot="smart-datetime-problem"]').textContent).toContain('09:05x')
  })

  it('saati silip çıkmak: saat, yazarken kaydedilen ara değerden değil odaklanmadan önceki değerden alınır', () => {
    render(<Harness initial={toIso(L(2026, 11, 16, 14, 30))} />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '16.11.2026 09:05' } })
    fireEvent.change(input(), { target: { value: '15.11.2026' } })
    fireEvent.blur(input())
    expect(value()).toBe(toIso(L(2026, 11, 15, 14, 30)))
  })

  it('Ctrl/⌘+Enter yazılanı çözer ve olayı forma bırakır (preventDefault yok)', () => {
    render(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '14:30' } })            // yalnız saat: anında kaydedilmez
    expect(fireEvent.keyDown(input(), { key: 'Enter', ctrlKey: true })).toBe(true)
    expect(value()).toBe(toIso(L(2026, 11, 10, 14, 30)))
  })

  it('okunamayan metin onProblem(true) bildirir; düzelince false', () => {
    const onProblem = vi.fn()
    render(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} onProblem={onProblem} />)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: 'abc' } })
    fireEvent.blur(input())
    expect(onProblem).toHaveBeenLastCalledWith(true)
    fireEvent.focus(input())
    fireEvent.change(input(), { target: { value: '10.11.2026 09:00' } })
    expect(onProblem).toHaveBeenLastCalledWith(false)
  })

  it('değer yokken takvimden gün: saat çapadan (oluş) gelir, en erken andan önceye düşmez', async () => {
    render(<Harness anchor={toIso(L(2026, 11, 12, 10, 40))} anchorLabel="Oluş Zamanı" min={toIso(L(2026, 11, 12, 10, 40))} />)
    fireEvent.click(screen.getByRole('button', { name: /Takvimden|Pick from the calendar/ }))
    const panel = await waitFor(() => {
      const el = document.querySelector('[data-slot="smart-datetime-panel"]')
      expect(el).toBeTruthy()
      return el
    })
    fireEvent.click(within(panel.querySelector('[role="grid"]')).getByText('12', { selector: 'button' }))
    await waitFor(() => expect(value()).toBe(toIso(L(2026, 11, 12, 10, 40))))
  })

  it('en erken andan (tespit) önce ama çapadan (oluş) sonra: uyarı tonunda, en erken anın adıyla', () => {
    render(<Harness initial={toIso(L(2026, 11, 16, 10, 0))} anchor={toIso(L(2026, 11, 16, 9, 0))} anchorLabel="Oluş Zamanı"
      min={toIso(L(2026, 11, 16, 11, 0))} minLabel="Tespit Zamanı" />)
    const ctx = document.querySelector('[data-slot="smart-datetime-context"]')
    expect(ctx).toHaveAttribute('data-warn', 'true')
    expect(ctx.textContent).toMatch(/1 sa önce \(esas: Tespit Zamanı\)|1 h before Tespit Zamanı/)
  })

  it('label verilince düğme ve çip grubu adları alana özgü', () => {
    render(<Harness label="Çözülme Zamanı" clearable initial={toIso(L(2026, 11, 10, 8, 20))} />)
    expect(screen.getByRole('button', { name: /Takvimden ve saat listesinden seç: Çözülme Zamanı|Pick from the calendar and time lists: Çözülme Zamanı/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Tarihi temizle: Çözülme Zamanı|Clear the date: Çözülme Zamanı/ })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: /Hızlı seçim: Çözülme Zamanı|Quick pick: Çözülme Zamanı/ })).toBeInTheDocument()
  })

  it('takvimin yıl listesi yazımın aralığıyla uyumlu: 2000 seçilebilir', async () => {
    render(<Harness initial={toIso(L(2026, 11, 10, 8, 20))} />)
    fireEvent.click(screen.getByRole('button', { name: /Takvimden|Pick from the calendar/ }))
    const panel = await waitFor(() => {
      const el = document.querySelector('[data-slot="smart-datetime-panel"]')
      expect(el).toBeTruthy()
      return el
    })
    const years = [...panel.querySelectorAll('select')].map((sel) => [...sel.options].map((o) => o.textContent))
    expect(years.some((list) => list.includes('2000') && list.includes('2027'))).toBe(true)
  })
})
