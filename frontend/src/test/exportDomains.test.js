import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../api/client', () => ({ formatDate: (s) => 'D(' + String(s).slice(0, 10) + ')' }))
vi.mock('../utils/pdfBrand.js', () => ({ drawBrandHeader: async () => 60 }))
const captured = { blob: null, name: null }
vi.mock('../utils/exportInventory.js', () => ({
  triggerDownload: (blob, name) => { captured.blob = blob; captured.name = name },
  dateStamp: () => '2026-09-22',
  registerRobotoFont: async () => {},
}))

import { exportDomainsCsv } from '../utils/exportDomains.js'

const t = (k, ...a) => k + (a.length ? '(' + a.join(',') + ')' : '')

/** Alan adı listesi CSV dışa aktarımı (2026-09-22, D): görünen liste, tüm alanlar, EPP tek biçim, BOM + kaçış. */
describe('exportDomainsCsv', () => {
  beforeEach(() => { captured.blob = null; captured.name = null })

  it('başlık + satır; EPP kodları tek biçim; CSV enjeksiyonu kaçışlı; BOM ile başlar', async () => {
    const n = exportDomainsCsv([{
      domain: 'a.example.com', name: '=cmd()', team_name: 'Takım A', group_name: 'G', status: 'WARNING', days_remaining: 12,
      expiry_date: '2026-10-04T05:00:00Z', registration_date: '2010-03-28', last_changed: '2024-12-08T08:34:00Z',
      registrar: 'Registrar, Inc.', registrar_iana_id: '1091', source: 'RDAP', transfer_lock: 'CLIENT', dnssec: 'unsigned',
      blacklist_status: 'SKIPPED', status_codes: ['clientTransferProhibited', 'client update prohibited'],
      nameservers: 'ns1.example.com,ns2.example.com', ns_resolves: true, active_alarm: true, alarm_level: 'HIGH', checked_at: '2026-09-22T03:00:00',
    }], t)
    expect(n).toBe(1)
    expect(captured.name).toBe('site-monitor-domains-2026-09-22.csv')
    // Blob.text() BOM'u soyar (TextDecoder varsayılanı) → ham baytlara bak
    const bytes = new Uint8Array(await captured.blob.arrayBuffer())
    expect([bytes[0], bytes[1], bytes[2]]).toEqual([0xef, 0xbb, 0xbf])
    const text = new TextDecoder('utf-8').decode(bytes)
    const [head, row] = text.split('\r\n')
    expect(head.split(',')[0]).toBe('dom.domain')
    expect(row).toContain('a.example.com')
    expect(row).toContain('"Registrar, Inc."')                 // virgül → tırnak
    expect(row).not.toMatch(/(^|,)=cmd\(\)/)                   // formül enjeksiyonu kaçışlı
    expect(row).toContain('client transfer prohibited | client update prohibited')
    expect(row).toContain('ns1.example.com | ns2.example.com')
    expect(row).toContain('dom.lockClient')
    expect(row).toContain('HIGH')
    expect(row).toContain('D(2026-09-22)')
  })

  it('7/24 sütunları (2026-09-27): açık/kapalı + grup ADLARI; varsayılan gruplar boş; başlık ve satır aynı genişlikte', async () => {
    exportDomainsCsv([
      { domain: 'a.example.com', noc_notify: true, noc_group_ids: [2] },
      { domain: 'b.example.com', noc_notify: false, noc_group_ids: [] },
    ], t, { 2: 'Hafta Sonu Vardiyası' })
    const text = new TextDecoder('utf-8').decode(new Uint8Array(await captured.blob.arrayBuffer()))
    const [head, a, b] = text.split('\r\n').map((l) => l.split(','))
    expect(head.slice(-2)).toEqual(['nocf.csvNotify', 'nocf.csvGroups'])
    expect(a).toHaveLength(head.length)
    expect(a.slice(-2)).toEqual(['dom.on', 'Hafta Sonu Vardiyası'])
    expect(b.slice(-2)).toEqual(['dom.off', ''])
  })

  it('boş liste: 0 döner ve dosya üretmez', () => {
    expect(exportDomainsCsv([], t)).toBe(0)
    expect(captured.blob).not.toBeNull()   // başlık satırı yine yazılır (Excel şablonu olarak işe yarar)
  })
})
