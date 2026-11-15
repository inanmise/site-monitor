import { describe, it, expect } from 'vitest'
import { buildDomainIcs, icsEscape, icsFold } from '../utils/exportDomainsIcs.js'
import { TR } from '../i18n/tr.js'

const BS = String.fromCharCode(92)
const enc = new TextEncoder()
const t = (key, ...args) => String(TR[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))

/** Katlanmış satırları RFC 5545'e göre açar (CRLF + boşluk → hiçbir şey). */
const unfold = (ics) => ics.replace(/\r\n /g, '')

describe('C3 — alan adı bitişleri .ics (RFC 5545)', () => {
  it('metin kaçışı: ters bölü, noktalı virgül, virgül ve satır sonu', () => {
    expect(icsEscape(`a${BS}b;c,d\ne\r\nf`)).toBe(`a${BS}${BS}b${BS};c${BS},d${BS}ne${BS}nf`)
  })

  it('katlama 75 OKTETTE (Türkçe harf 2 bayt), çok baytlı karakter bölünmez, devam satırı boşlukla başlar', () => {
    const line = 'SUMMARY:' + 'ığüşöçİĞÜŞÖÇ'.repeat(12) + '🙂'.repeat(5)
    const folded = icsFold(line)
    const parts = folded.split('\r\n')
    expect(parts.length).toBeGreaterThan(2)
    parts.forEach((p, i) => {
      expect(enc.encode(p).length).toBeLessThanOrEqual(75)
      if (i > 0) expect(p.startsWith(' ')).toBe(true)
      expect(p).not.toMatch(/�/)
    })
    expect(unfold(folded)).toBe(line)
  })

  it('takvim: tüm gün VEVENT bitiş gününde, kararlı UID, CRLF, kişi verisi yok; bitişi bilinmeyen atlanır ve sayılır', () => {
    const rows = [
      { id: 7, domain: 'örnek-alan-adı.com.tr', name: 'Kurumsal site, ana', registrar: 'GoDaddy.com; LLC', expiry_date: '2027-03-15', team_name: 'Takım X', renewal_planned_by: 'Ayşe' },
      { id: 8, domain: 'bilinmiyor.com', name: 'x', expiry_date: null },
      { id: 9, domain: 'rdap.com', name: 'rdap.com', registrar: '', expiry_date: '2027-03-15T23:59:59Z' },
    ]
    const now = new Date('2026-11-15T09:30:00Z')
    const { ics, count, skipped } = buildDomainIcs(rows, t, now)
    expect(count).toBe(2)
    expect(skipped).toBe(1)
    expect(ics.endsWith('\r\n')).toBe(true)
    expect(ics.replace(/\r\n/g, '')).not.toMatch(/\n/)   // yalnız CRLF
    const flat = unfold(ics)
    expect(flat).toContain('UID:domain-monitor-7@site-monitor')
    expect(flat).toContain('DTSTART;VALUE=DATE:20270315')
    expect(flat).toContain('DTEND;VALUE=DATE:20270316')
    expect(flat).toContain('DTSTAMP:20261115T093000Z')
    expect(flat).toContain(`GoDaddy.com${BS}; LLC`)
    expect(flat).toContain(`Kurumsal site${BS}, ana`)
    expect(flat).toContain('örnek-alan-adı.com.tr')
    expect(flat).not.toContain('Ayşe')
    expect(flat).not.toContain('Takım X')
    expect(flat).toContain(`Registrar: ${TR['dom.ics.registrarUnknown']}`)   // boş registrar
    // RDAP zaman damgası yerel güne düşer (İstanbul'da 16 Mart)
    expect(flat).toMatch(/UID:domain-monitor-9@site-monitor\r\nDTSTAMP:[^\r]+\r\nDTSTART;VALUE=DATE:2027031[56]/)
    for (const l of ics.split('\r\n')) expect(enc.encode(l).length).toBeLessThanOrEqual(75)
    // aynı girdi → aynı UID'ler (yeniden içe aktarma çoğaltmaz)
    expect(buildDomainIcs(rows, t, now).ics).toBe(ics)
  })
})
