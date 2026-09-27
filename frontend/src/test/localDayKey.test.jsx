import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { localDayKey, formatDateOnly } from '../api/client'
import MonthCalendar, { firstEventMonth } from '../components/ui/MonthCalendar.jsx'
import { buildIcs } from '../utils/ics.js'
import { formatPercent, setDateLocale } from '../i18n/dateLocale.js'

/**
 * QA 2026-09-12 ISSUE-004: sunucu damgası UTC'dir ("2026-10-23T23:59:59" = 24/10 02:59 İstanbul).
 * Takvim ve ICS `.slice(0, 10)` ile UTC gününü alıyor, liste ise yerel günü gösteriyordu → aynı sertifika
 * listede 24/10, takvimde 23/10. Beklenti saat dilimine bağlı olmadan türetilir (CI UTC, yerel İstanbul):
 * takvim günü == formatDateOnly günü olmalı.
 */
const ISO = '2026-10-23T23:59:59'
function localYmd(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const expectedDay = localYmd(new Date(ISO + 'Z'))

describe('localDayKey (ISSUE-004)', () => {
  it('UTC damgasını YEREL güne çevirir; formatDateOnly ile aynı günü verir; yalnız-tarih olduğu gibi kalır', () => {
    expect(localDayKey(ISO)).toBe(expectedDay)
    const [dd, mm, yyyy] = formatDateOnly(ISO).split('/')
    expect(localDayKey(ISO)).toBe(`${yyyy}-${mm}-${dd}`)
    expect(localDayKey('2026-12-31')).toBe('2026-12-31')
    expect(localDayKey(null)).toBeNull()
    expect(localDayKey('garbage')).toBe('garbage'.slice(0, 10))
  })

  it('MonthCalendar olayı yerel güne yerleştirir (damga slice edilmez)', () => {
    const [y, m, d] = expectedDay.split('-').map(Number)
    render(<MonthCalendar events={[{ date: ISO, label: 'a.example.com', tone: 'info' }]} initialMonth={`${y}-${String(m).padStart(2, '0')}-01`} />)
    const cell = screen.getByText('a.example.com').closest('[data-slot="month-calendar-day"]')
    expect(cell.querySelector('[data-slot="month-calendar-date"]').textContent).toBe(String(d))
  })

  it('ICS DTSTART yerel gündür', () => {
    const ics = buildIcs([{ uid: 'x', date: ISO, summary: 's' }])
    expect(ics).toContain(`DTSTART;VALUE=DATE:${expectedDay.replace(/-/g, '')}`)
  })
})

/** ISSUE-003: bu ayda olay yoksa takvim olayı olan ilk aya açılır — boş ızgara "hiçbir şey yok" okunmasın. */
describe('firstEventMonth (ISSUE-003)', () => {
  it('bu ayda olay varsa null; yoksa bugünden sonraki ilk olayın ayı; hepsi geçmişse en erkeni', () => {
    const now = new Date()
    const thisMonth = new Date(now.getFullYear(), now.getMonth(), 15)
    expect(firstEventMonth([{ date: localYmd(thisMonth) }])).toBeNull()
    const inTwo = new Date(now.getFullYear(), now.getMonth() + 2, 10)
    const inFive = new Date(now.getFullYear(), now.getMonth() + 5, 10)
    const r = firstEventMonth([{ date: localYmd(inFive) }, { date: localYmd(inTwo) }])
    expect(r.getFullYear()).toBe(inTwo.getFullYear()); expect(r.getMonth()).toBe(inTwo.getMonth())
    const past = new Date(now.getFullYear() - 1, 2, 1)
    const p = firstEventMonth([{ date: localYmd(past) }])
    expect(p.getMonth()).toBe(2)
    expect(firstEventMonth([])).toBeNull()
  })
})

/** ISSUE-001/007/010: yüzde biçimi dile göre — TR "%100", EN "100%". */
describe('formatPercent', () => {
  it('yerel Türkçe ise % önde, İngilizce ise sonda; boş değer tire', () => {
    setDateLocale('en')
    expect(formatPercent(33.3)).toBe('33.3%')
    setDateLocale('tr')
    expect(formatPercent(100)).toBe('%100')
    expect(formatPercent(null)).toBe('—')
    setDateLocale('en')
  })
})

// 2026-09-27: Genel Bakış kartı ve Uyarılar sayfası yenileme planı penceresine bitiş gününü `not_after.slice(0, 10)`
// ile veriyordu (UTC günü) → gece biten sertifika pencerede takvimden bir gün ERKEN görünüyordu. Sertifika damgaları
// (not_after / not_before) yalnız localDayKey/expiryKey ile güne çevrilir. Alan adı `expiry_date` bilinçli olarak
// kapsam DIŞI: kayıt kuruluşunun kendi takvim günüdür (WHOIS yalnız tarih, RDAP UTC damga) ve tüm alan adı yüzeyleri
// onu aynı biçimde gösterir.
describe('sertifika damgası dilimlenmez (UTC günü = bir gün erken)', () => {
  it('kaynakta not_after / not_before .slice(0, 10) / .substring(0, 10) yok', async () => {
    const fs = await import('node:fs')
    const path = await import('node:path')
    const root = path.resolve(__dirname, '..')
    const offenders = []
    const walk = (dir) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, e.name)
        if (e.isDirectory()) { if (e.name !== 'test') walk(full) }
        else if (/[.]jsx?$/.test(e.name)) {
          fs.readFileSync(full, 'utf8').split('\n').forEach((line, i) => {
            if (/^\s*(\/\/|\*)/.test(line)) return
            if (/not_(after|before)\)?\)?\.(slice|substring)\(0,\s*10\)/.test(line)) offenders.push(`${path.relative(root, full)}:${i + 1}`)
          })
        }
      }
    }
    walk(root)
    expect(offenders, 'localDayKey / expiryKey kullanın').toEqual([])
  })
})
