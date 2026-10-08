import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { SHARED_VIEWS, certInfoRows, daysText, exportFileName, exportTable, groupPeers, noteText } from '../components/sharedcert/sharedCertModel.js'

/**
 * Paylaşılan sertifika penceresi — gruplama ve dışa aktarma modeli (2026-10-08, kullanıcı: "takım bazlı, grup bazlı
 * görebilmeliyim; PDF ve Excel olarak dışa alabilmeliyim"). Pencere, PDF ve Excel aynı modeli okur.
 */
const fmt = (k, ...a) => (EN[k] ?? k).replace(/\{(\d)\}/g, (_, i) => String(a[Number(i)]))
const PEERS = [
  { domain: 'a.example.com', self: true, team_name: 'Takım B', group_name: 'Ödeme', days_remaining: 20, not_after: '2026-10-28T00:00:00', in_inventory: true, tier: 1, port: 443 },
  { domain: 'b.example.com', team_name: 'Takım A', group_name: null, days_remaining: -2, in_inventory: true, platform: 'IIS', platform_detail: 'web01', port: 8443 },
  { domain: 'c.example.com', team_name: null, group_name: 'ödeme', days_remaining: null, in_inventory: false },
  { domain: 'd.example.com', team_name: 'takım a', group_name: 'Çekirdek', days_remaining: 5, in_inventory: true },
]

describe('sharedCertModel', () => {
  it('görünümler sabit; yeni metinlerin TR + EN karşılığı var', () => {
    expect(SHARED_VIEWS).toEqual(['list', 'team', 'group'])
    const keys = ['shc.view', 'shc.viewList', 'shc.viewTeam', 'shc.viewGroup', 'shc.colGroup', 'shc.noTeam', 'shc.noGroup',
      'shc.sectionCount', 'shc.exportPdf', 'shc.exportXlsx', 'shc.exportTitle', 'shc.exportFile', 'shc.exportFailed',
      'shc.xl.sheetDomains', 'shc.xl.sheetCert', 'shc.xl.field', 'shc.xl.value', 'shc.colDays', 'shc.colDaysNum', 'shc.colNote', 'shc.generated']
    expect(keys.filter((k) => !TR[k] || !EN[k])).toEqual([])
  })

  it('liste: tek başlıksız bölüm, sunucu sırası', () => {
    const s = groupPeers(PEERS, 'list')
    expect(s).toHaveLength(1)
    expect(s[0].label).toBeNull()
    expect(s[0].rows.map((p) => p.domain)).toEqual(['a.example.com', 'b.example.com', 'c.example.com', 'd.example.com'])
  })

  it('takıma göre: büyük/küçük harf aynı takım, Türkçe A→Z, Takımsız EN SONDA', () => {
    const s = groupPeers(PEERS, 'team', { noneLabel: 'No team' })
    expect(s.map((x) => [x.label, x.rows.map((p) => p.domain)])).toEqual([
      ['Takım A', ['b.example.com', 'd.example.com']],
      ['Takım B', ['a.example.com']],
      ['No team', ['c.example.com']],
    ])
    expect(s[2].none).toBe(true)
  })

  it('gruba göre: Çekirdek < Ödeme (Türkçe sıralama), Grupsuz sonda', () => {
    const s = groupPeers(PEERS, 'group', { noneLabel: 'No group' })
    expect(s.map((x) => [x.label, x.rows.length])).toEqual([['Çekirdek', 1], ['Ödeme', 2], ['No group', 1]])
  })

  it('satır metinleri: kalan gün / doldu / bilinmiyor; not: bu kart · envanterde değil', () => {
    expect(daysText(PEERS[0], fmt)).toBe(fmt('shc.daysLeft', 20))
    expect(daysText(PEERS[1], fmt)).toBe(fmt('shc.expiredAgo', 2))
    expect(daysText(PEERS[2], fmt)).toBe('—')
    expect(noteText(PEERS[0], fmt)).toBe(fmt('shc.thisDomain'))
    expect(noteText(PEERS[2], fmt)).toBe(fmt('shc.notInInventory'))
  })

  it('dışa aktarma tablosu: 10 sütun, port/platform/tier biçimi, sayı sütunu Excel için sayı', () => {
    const { columns, sections } = exportTable({ peers: PEERS }, 'team', fmt, { fmtDate: (v) => `D(${v})`, fmtDateSec: (v) => `S(${v})` })
    expect(columns).toHaveLength(10)
    const b = sections[0].cells[0]
    expect(b[0]).toBe('b.example.com:8443')
    expect(b[3]).toBe('IIS · web01')
    expect(b[6]).toBe(-2)
    const a = sections[1].cells[0]
    expect(a[4]).toBe('T1')
    expect(a[7]).toBe('D(2026-10-28T00:00:00)')
    expect(sections[2].cells[0][6]).toBeNull()
  })

  it('sertifika bilgisi satırları ve dosya adı', () => {
    const rows = certInfoRows({ domain: 'a.example.com', fingerprint: 'AB', san: ['a.example.com', 'b.example.com'], peers: PEERS, hidden: 2, days_remaining: 20, not_after: 'X' }, fmt)
    expect(rows.find((r) => r[0] === fmt('shc.fingerprint'))[1]).toBe('AB')
    expect(rows.find((r) => r[0] === fmt('shc.san', 2))[1]).toBe('a.example.com, b.example.com')
    expect(rows[rows.length - 1][1]).toBe(fmt('shc.hidden', 2))
    expect(exportFileName('shared-certificate', 'Api.Example.com:443/x', 'xlsx', new Date(2026, 9, 8)))
      .toBe('shared-certificate-api.example.com-443-x-2026-10-08.xlsx')
  })
})
