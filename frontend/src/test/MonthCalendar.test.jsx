import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import MonthCalendar from '../components/ui/MonthCalendar.jsx'
import { buildIcs } from '../utils/ics.js'

const BS = String.fromCharCode(92)

/** Aylık takvim (2026-09-12, #8/#19) + ICS üretimi. */
describe('MonthCalendar', () => {
  it('olaylar güne düşer, aynı güne 4 olay → 3 + "+1"; tıklama olayı çağırır; ay gezinme', () => {
    const onClick = vi.fn()
    const events = [
      { date: '2026-09-15', label: 'a.example.com', tone: 'bad', onClick },
      { date: '2026-09-15', label: 'b.example.com', tone: 'warn' },
      { date: '2026-09-15', label: 'c.example.com', tone: 'info' },
      { date: '2026-09-15', label: 'd.example.com', tone: 'ok' },
      { date: '2026-10-02', label: 'next.example.com', tone: 'ok' },
    ]
    render(<MonthCalendar events={events} initialMonth="2026-09-01" />)
    expect(screen.getByText(/^(4 olay|4 events)$/)).toBeInTheDocument()   // eylül: 4 (ekimdeki sayılmaz); gün hedefinin sr adı ayrı
    expect(screen.getByText('a.example.com')).toBeInTheDocument()
    expect(screen.queryByText('d.example.com')).toBeNull()
    expect(screen.getByText('+1')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="month-calendar-day"][data-tone="bad"]')).not.toBeNull()
    expect(document.querySelectorAll('[data-slot="month-calendar-event"]')).toHaveLength(3)   // 4. olay "+1" altında
    fireEvent.click(screen.getByText('a.example.com'))
    expect(onClick).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Sonraki ay|Next month/ }))
    expect(screen.getByText('next.example.com')).toBeInTheDocument()
    expect(screen.queryByText('a.example.com')).toBeNull()
  })

  /** 2026-09-27 (Sertifika Takvimi): ekleyici seçenekler — varsayılanlar eski davranışı korur (üstteki test). */
  it('onDayClick + dense: olaylı günün tarihi düğme olur ve günü olaylarıyla çağırır; olaysız gün düğme değil; hafta sonu işaretli; aya atlama', () => {
    const onDay = vi.fn()
    const events = [
      { date: '2026-09-15', label: 'a.example.com', tone: 'bad' }, { date: '2026-09-15', label: 'b.example.com', tone: 'warn' },
      { date: '2026-09-15', label: 'c.example.com', tone: 'info' }, { date: '2026-09-15', label: 'd.example.com', tone: 'ok' },
      { date: '2026-10-02', label: 'next.example.com', tone: 'ok' },
    ]
    render(<MonthCalendar events={events} initialMonth="2026-09-01" dense onDayClick={onDay} />)
    const open = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-09-15"] [data-slot="month-calendar-day-open"]')
    expect(open).not.toBeNull()
    expect(open.tagName).toBe('BUTTON')
    expect(open.getAttribute('aria-label')).toMatch(/4/)
    expect(open.querySelector('[data-slot="month-calendar-date"]').textContent).toBe('15')
    fireEvent.click(open)
    expect(onDay).toHaveBeenCalledTimes(1)
    expect(onDay.mock.calls[0][0]).toBe('2026-09-15')
    expect(onDay.mock.calls[0][1]).toHaveLength(4)
    expect(document.querySelector('[data-day="2026-09-16"] [data-slot="month-calendar-day-open"]')).toBeNull()
    expect(document.querySelector('[data-day="2026-09-19"]').getAttribute('data-weekend')).toBe('true')   // Cumartesi
    expect(document.querySelector('[data-day="2026-09-16"]').hasAttribute('data-weekend')).toBe(false)
    // Çipler dense'te de DOM'da (geniş takvimde görünür), olay tıklaması korunur
    expect(document.querySelectorAll('[data-slot="month-calendar-event"]')).toHaveLength(3)
    // Aya atlama (NativeSelect): ekim → ekimdeki olay görünür
    fireEvent.change(screen.getByRole('combobox', { name: /Aya git|Go to month/ }), { target: { value: '2026-10' } })
    expect(screen.getByText('next.example.com')).toBeInTheDocument()
    expect(screen.queryByText('a.example.com')).toBeNull()
  })

  /**
   * 2026-11-26 (proje kuralı): dokunmatikte (`pointer-coarse`) her etkileşimli öğe ≥ 40 px; masaüstü küçültmesi yalnız ince
   * işaretçide (`pointer-fine:sm:h-8` — çıplak `sm:h-8` 768 px dokunmatik tablette 32 px kalırdı). Olay etiketi KESİLMEZ,
   * sarar; tam metin `title`'da da. (jsdom yerleşim ölçmez; sınıf sözleşmesi burada, gerçek ölçü e2e'de.)
   */
  it('dokunmatik hedefler ≥ 40 px ve uzun olay etiketi kesilmez, sarar', () => {
    const long = 'Ödeme Sistemleri Altyapı ve Entegrasyon Takımı — çok-uzun-alan-adı.ornek-kurum-hizmetleri.example.com'
    render(<MonthCalendar events={[{ date: '2026-09-15', label: long, tone: 'warn', onClick: vi.fn() }]}
      initialMonth="2026-09-01" dense onDayClick={vi.fn()} />)
    const tokens = (el) => el.className.split(/\s+/)
    const jump = document.querySelector('[data-slot="month-calendar-jump"]')
    const todayBtn = document.querySelector('[data-slot="month-calendar-today"]')
    for (const el of [jump, todayBtn]) {
      expect(tokens(el)).toContain('pointer-coarse:h-10')
      expect(tokens(el)).toContain('pointer-fine:sm:h-8')
      expect(tokens(el)).not.toContain('sm:h-8')
    }
    const open = document.querySelector('[data-slot="month-calendar-day-open"]')
    expect(tokens(open)).toContain('pointer-coarse:min-h-10')
    const chip = document.querySelector('[data-slot="month-calendar-event"]')
    expect(tokens(chip)).toContain('pointer-coarse:min-h-10')
    expect(tokens(chip)).toContain('whitespace-normal')
    const label = chip.querySelector('[data-slot="month-calendar-event-label"]')
    expect(label.textContent).toBe(long)
    expect(tokens(label)).not.toContain('truncate')
    expect(tokens(label)).toContain('[overflow-wrap:anywhere]')
    expect(chip.getAttribute('title')).toBe(long)
    // Kaynakta da kesme yok: bileşen hiçbir yerde `truncate` kullanmaz
    expect(document.querySelector('[data-slot="month-calendar"] .truncate')).toBeNull()
  })

  /**
   * 2026-11-26 (inceleme #13, e2e ölçümünden sonra): DAR takvimde (kart < 42rem — `@container/mcal` + `@2xl`; telefon ve
   * 768 tablet) çip ÇİZİLMEZ — sararak tam yazılan uzun adlar dar sütunda günü yüzlerce piksel uzatıyordu. Olaylı her gün tek
   * bir hedef taşır (`month-calendar-more-touch`: ton noktaları + adet, adı "15 September — 3 events"); balon günün TÜM
   * olaylarını tam adıyla listeler, tıklanabilir olay balonda da düğmedir. jsdom CSS / kap sorgusu uygulamaz: görünürlük sınıf
   * sözleşmesiyle, davranış DOM'la denetlenir (gerçek görünürlük ve yükseklik e2e'de).
   */
  it('dar takvim: çip kabı gizlenir; günün tek hedefi adet + ad taşır; balon TÜM olayları listeler, olay açılır', () => {
    const open = vi.fn()
    const events = [
      { date: '2026-09-15', label: 'a.example.com', tone: 'bad', onClick: open },
      { date: '2026-09-15', label: 'b.example.com', tone: 'warn' },
      { date: '2026-09-15', label: 'cokuzunalanadikirilmanoktasiolmayan.ornekkurumhizmetleri.example.com', tone: 'info' },
      { date: '2026-09-16', label: 'tek.example.com', tone: 'ok' },
    ]
    render(<MonthCalendar events={events} initialMonth="2026-09-01" />)
    const tokens = (el) => el.className.split(/\s+/)
    const day15 = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-09-15"]')
    const chips = [...day15.querySelectorAll('[data-slot="month-calendar-event"]')]
    expect(chips).toHaveLength(3)
    // Kap sorgusu kartta; çiplerin kabı dar takvimde gizli, geniş takvimde görünür; çiplerin kendisi aynı
    expect(tokens(document.querySelector('[data-slot="month-calendar"]'))).toContain('@container/mcal')
    expect(tokens(chips[0].parentElement)).toEqual(expect.arrayContaining(['hidden', '@2xl/mcal:flex']))
    for (const c of chips) expect(tokens(c)).not.toContain('hidden')
    const more = day15.querySelector('[data-slot="month-calendar-more-touch"]')
    expect(more).not.toBeNull()
    // Hedef yalnız dar takvimde görünür (geniş takvimde gizli)
    expect(tokens(more)).toEqual(expect.arrayContaining(['contents', '@2xl/mcal:hidden']))
    const trigger = more.querySelector('[data-slot="hint-trigger"]')
    expect(tokens(trigger)).toContain('pointer-coarse:min-h-10')
    expect(trigger).toHaveAccessibleName('15 September — 3 events')
    // 3 olay ≤ maxPerDay: masaüstü "+N" yok
    expect(day15.textContent).not.toContain('+')
    // Balon günün TÜM olaylarını tam adıyla taşır; tıklanabilir olay düğmedir, dokununca balon kapanır ve olay açılır
    fireEvent.click(trigger)
    const list = document.querySelector('[data-slot="month-calendar-day-events"]')
    expect(list).not.toBeNull()
    const items = [...list.querySelectorAll('[data-slot="month-calendar-day-event"]')]
    expect(items.map((x) => x.textContent)).toEqual(events.slice(0, 3).map((e) => e.label))
    for (const l of list.querySelectorAll('[data-slot="month-calendar-day-event-label"]')) {
      expect(tokens(l)).toContain('[overflow-wrap:anywhere]')
      expect(tokens(l)).not.toContain('truncate')
    }
    expect(items[0].tagName).toBe('BUTTON')
    expect(tokens(items[0])).toContain('min-h-10')
    expect(items[1].tagName).toBe('SPAN')   // onClick yok → düz metin (sahte düğme yok)
    fireEvent.click(items[0])
    expect(open).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[data-slot="month-calendar-day-events"]')).toBeNull()

    // Tek olaylı gün: hedef var, adı tekil ("1 event")
    const day16 = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-09-16"]')
    expect(day16.querySelectorAll('[data-slot="month-calendar-event"]')).toHaveLength(1)
    expect(day16.querySelector('[data-slot="month-calendar-more-touch"] [data-slot="hint-trigger"]'))
      .toHaveAccessibleName('16 September — 1 event')
    // Olaysız gün: hedef yok
    const day17 = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-09-17"]')
    expect(day17.querySelector('[data-slot="month-calendar-more-touch"]')).toBeNull()
  })

  it('maxPerDay aşılınca geniş takvimin "+N"i çip kabında (dar takvimde kapla gizli); dar takvim balonu hepsini listeler', () => {
    const events = ['a', 'b', 'c', 'd', 'e'].map((x) => ({ date: '2026-09-15', label: `${x}.example.com`, tone: 'ok' }))
    render(<MonthCalendar events={events} initialMonth="2026-09-01" />)
    const day = document.querySelector('[data-slot="month-calendar-day"][data-day="2026-09-15"]')
    const touch = day.querySelector('[data-slot="month-calendar-more-touch"]')
    const desktopTrigger = [...day.querySelectorAll('[data-slot="hint-trigger"]')].find((b) => !touch.contains(b))
    expect(desktopTrigger.textContent).toBe('+2')
    expect(desktopTrigger.parentElement.className.split(/\s+/)).toEqual(expect.arrayContaining(['hidden', '@2xl/mcal:flex']))
    fireEvent.click(touch.querySelector('[data-slot="hint-trigger"]'))
    expect(document.querySelectorAll('[data-slot="month-calendar-day-event"]')).toHaveLength(5)
  })

  it('dense + onDayClick: dar takvim hedefi çizilmez (gün düğmesi noktaları taşır); onDayClick yoksa hedef çizilir', () => {
    const ev = [{ date: '2026-09-15', label: 'a.example.com', tone: 'bad' }]
    const { unmount } = render(<MonthCalendar events={ev} initialMonth="2026-09-01" dense onDayClick={vi.fn()} />)
    expect(document.querySelector('[data-slot="month-calendar-more-touch"]')).toBeNull()
    const dots = document.querySelector('[data-slot="month-calendar-day-open"] span[aria-hidden="true"]')
    expect(dots.className.split(/\s+/)).toContain('@2xl/mcal:hidden')
    unmount()
    render(<MonthCalendar events={ev} initialMonth="2026-09-01" dense />)
    expect(document.querySelector('[data-slot="month-calendar-more-touch"]')).not.toBeNull()
  })
})

describe('buildIcs', () => {
  it('tüm-gün VEVENT, kararlı UID, virgül/noktalı virgül kaçışı, DTEND = ertesi gün', () => {
    const ics = buildIcs([{ uid: 'cert-a.example.com-2026-09-15', date: '2026-09-15T00:00:00', summary: 'Sertifika yenileme: a.example.com', description: 'Yenile; sonra, deploy' }], { calName: 'Test' })
    expect(ics).toContain('BEGIN:VCALENDAR')
    expect(ics).toContain('UID:cert-a.example.com-2026-09-15@site-monitor')
    expect(ics).toContain('DTSTART;VALUE=DATE:20260915')
    expect(ics).toContain('DTEND;VALUE=DATE:20260916')
    expect(ics).toContain('SUMMARY:Sertifika yenileme: a.example.com')
    expect(ics).toContain(`DESCRIPTION:Yenile${BS}; sonra${BS}, deploy`)
    expect(ics.endsWith('END:VCALENDAR\r\n')).toBe(true)
    expect(buildIcs([{ date: null, summary: 'x' }])).not.toContain('BEGIN:VEVENT')
  })
})
