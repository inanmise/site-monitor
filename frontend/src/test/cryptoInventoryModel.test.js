import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  ACTIONS, BANDS, BUCKETS, CATEGORIES, DEFAULT_FILTERS, PQC_STATES, REMNANTS, SIG_HASHES, SORTS, SOURCES, TIERS,
  bucketLabel, daysText, exportColumns, exportFileName, exportRow, filterRows, filterSummary, filtersToUrl, hashLabel, isFiltered,
  keyText, readiness, sanitizeFilters, scopeText, sortRows, summaryRows, teamExportRows, teamOptions,
} from '../components/cryptoinv/cryptoInventoryModel.js'

/**
 * Kripto envanteri modeli (2026-10-10): süzme, sıralama, URL eşlemesi, takım seçenekleri, dışa aktarım tablosu ve
 * DİNAMİK çeviri anahtarlarının iki dilde de var olduğu (i18n-used-keys kapısı `t(\`cinv.cat.${c}\`)` biçimini göremez).
 */
const tt = (key, ...args) => {
  const v = TR[key]
  if (v == null) return key
  return v.replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))
}

const row = (o) => ({
  domain: 'x.example.com', source: 'NETWORK', team_id: 1, team_name: 'Takım A', tier: 1, key_algorithm: 'RSA', key_size: 2048,
  key_bucket: 'RSA_2048', signature_algorithm: 'SHA256withRSA', sig_hash: 'SHA256', remnants: [], pqc: 'VULNERABLE',
  category: 'LEGACY', days_remaining: 100, priority: { score: 60, band: 'P2', exposure: 40, strength: 20, renewal: 0, hndl: 0 },
  rank: 1, ...o,
})

const ROWS = [
  row({ domain: 'b.example.com', rank: 2, days_remaining: 20, priority: { score: 85, band: 'P1' }, tier: 1, owner: 'Ödeme Ekibi' }),
  row({ domain: 'a.example.com', rank: 1, days_remaining: 300, priority: { score: 90, band: 'P1' }, category: 'BROKEN', sig_hash: 'SHA1',
    signature_algorithm: 'SHA1withRSA', remnants: ['SHA1_LEAF'] }),
  row({ domain: 'İzmir.example.com', rank: 3, team_id: 2, team_name: 'Takım B', tier: 4, key_bucket: 'EC_P256', key_algorithm: 'EC',
    key_size: 256, category: 'MODERN', days_remaining: null, priority: { score: 20, band: 'P4' } }),
  row({ domain: 'keystore-app', rank: 4, team_id: null, team_name: null, tier: null, source: 'MANUAL', manual_version: 2,
    key_bucket: 'UNKNOWN', key_algorithm: null, key_size: null, category: 'UNKNOWN', pqc: 'UNKNOWN', days_remaining: 5,
    priority: { score: 15, band: 'P4' } }),
  row({ domain: 'pqc.example.com', rank: 5, key_bucket: 'PQC', key_algorithm: 'ML-DSA-65', key_size: null, category: 'PQC_READY',
    pqc: 'PQC', sig_hash: 'PQC', days_remaining: 50, priority: { score: 0, band: 'DONE' } }),
]

describe('cryptoInventoryModel — süzme', () => {
  const f = (p) => ({ ...DEFAULT_FILTERS, ...p })
  const names = (rows) => rows.map((r) => r.domain)

  it('süzgeç yokken tüm satırlar', () => {
    expect(filterRows(ROWS, DEFAULT_FILTERS)).toHaveLength(5)
    expect(isFiltered(DEFAULT_FILTERS)).toBe(false)
    expect(isFiltered(f({ sort: 'domain' }))).toBe(false)
  })

  it('takım (id ve takımsız), kategori, PQC, bant, kaynak, katman, kova, özet', () => {
    expect(names(filterRows(ROWS, f({ team: '2' })))).toEqual(['İzmir.example.com'])
    expect(names(filterRows(ROWS, f({ team: 'none' })))).toEqual(['keystore-app'])
    expect(names(filterRows(ROWS, f({ category: 'BROKEN' })))).toEqual(['a.example.com'])
    expect(names(filterRows(ROWS, f({ pqc: 'PQC' })))).toEqual(['pqc.example.com'])
    expect(names(filterRows(ROWS, f({ band: 'P1' })))).toEqual(['b.example.com', 'a.example.com'])
    expect(names(filterRows(ROWS, f({ source: 'MANUAL' })))).toEqual(['keystore-app'])
    expect(names(filterRows(ROWS, f({ tier: '4' })))).toEqual(['İzmir.example.com'])
    expect(names(filterRows(ROWS, f({ tier: 'none' })))).toEqual(['keystore-app'])
    expect(names(filterRows(ROWS, f({ bucket: 'EC_P256' })))).toEqual(['İzmir.example.com'])
    expect(names(filterRows(ROWS, f({ hash: 'SHA1' })))).toEqual(['a.example.com'])
    expect(isFiltered(f({ team: '2' }))).toBe(true)
  })

  it('kalıntı ve yenileme penceresi (yalnız kuantuma açık, ≤ 90 gün)', () => {
    expect(names(filterRows(ROWS, f({ remnant: true })))).toEqual(['a.example.com'])
    expect(names(filterRows(ROWS, f({ due: true })))).toEqual(['b.example.com'])   // keystore 5 gün ama pqc UNKNOWN; pqc 50 gün ama PQC
  })

  it('arama: alan adı, takım, sahip, algoritma — Türkçe büyük/küçük harf duyarsız', () => {
    expect(names(filterRows(ROWS, f({ q: 'izmir' })))).toEqual(['İzmir.example.com'])
    expect(names(filterRows(ROWS, f({ q: 'ödeme' })))).toEqual(['b.example.com'])
    expect(names(filterRows(ROWS, f({ q: 'ml-dsa' })))).toEqual(['pqc.example.com'])
    expect(names(filterRows(ROWS, f({ q: 'takım b' })))).toEqual(['İzmir.example.com'])
  })
})

describe('cryptoInventoryModel — sıralama', () => {
  const names = (rows) => rows.map((r) => r.domain)
  it('varsayılan = sunucunun öncelik sırası (rank)', () => {
    expect(names(sortRows(ROWS, 'priority'))).toEqual(['a.example.com', 'b.example.com', 'İzmir.example.com', 'keystore-app', 'pqc.example.com'])
  })
  it('en yakın bitiş: bilinmeyen sonda', () => {
    expect(names(sortRows(ROWS, 'expiry'))).toEqual(['keystore-app', 'b.example.com', 'pqc.example.com', 'a.example.com', 'İzmir.example.com'])
  })
  it('alan adı Türkçe sıralı; takım takımsız sonda; katman katmansız sonda; en zayıf anahtar önce', () => {
    expect(names(sortRows(ROWS, 'domain'))).toEqual(['a.example.com', 'b.example.com', 'İzmir.example.com', 'keystore-app', 'pqc.example.com'])
    expect(names(sortRows(ROWS, 'team')).at(-1)).toBe('keystore-app')
    expect(names(sortRows(ROWS, 'tier')).at(-1)).toBe('keystore-app')
    expect(names(sortRows(ROWS, 'key'))[0]).toBe('a.example.com')   // RSA_2048 (rank 1) EC_P256'dan önce
    expect(names(sortRows(ROWS, 'key')).at(-1)).toBe('keystore-app')   // UNKNOWN en sonda
  })
  it('girdiyi değiştirmez', () => {
    const copy = [...ROWS]
    sortRows(ROWS, 'domain')
    expect(ROWS).toEqual(copy)
  })
})

describe('cryptoInventoryModel — URL ve seçenekler', () => {
  it('sanitizeFilters bilinmeyen kodu varsayılana düşürür; boolean "1" okur', () => {
    const s = sanitizeFilters({ category: 'BROKEN', pqc: 'NOPE', sort: 'expiry', remnant: '1', due: 'x', team: '7', tier: '9' })
    expect(s).toMatchObject({ category: 'BROKEN', pqc: '', sort: 'expiry', remnant: true, due: false, team: '7', tier: '' })
  })
  it('filtersToUrl varsayılanı yazmaz', () => {
    expect(Object.values(filtersToUrl(DEFAULT_FILTERS)).every((v) => v === null)).toBe(true)
    expect(filtersToUrl({ ...DEFAULT_FILTERS, category: 'LEGACY', remnant: true, sort: 'tier' }))
      .toMatchObject({ ci_cat: 'LEGACY', ci_rem: '1', ci_sort: 'tier', ci_q: null })
  })
  it('teamOptions A→Z, takımsız sonda', () => {
    expect(teamOptions(ROWS)).toEqual([{ value: '1', label: 'Takım A' }, { value: '2', label: 'Takım B' }, { value: 'none', label: null }])
  })
  it('readiness payları', () => {
    expect(readiness({ total: 8, by_pqc: { VULNERABLE: 6, HYBRID: 1, PQC: 0, UNKNOWN: 1 } }))
      .toMatchObject({ total: 8, vulnerable: 6, ready: 1, unknown: 1, vulnerablePct: 75, readyPct: 12.5 })
    expect(readiness({})).toMatchObject({ total: 0, vulnerablePct: 0 })
  })
})

describe('cryptoInventoryModel — metin ve dışa aktarım', () => {
  it('kova/özet etiketleri, anahtar metni, kalan gün', () => {
    expect(bucketLabel('RSA_2048', tt)).toBe('RSA 2048')
    expect(bucketLabel('EC_P384', tt)).toBe('ECDSA P-384')
    expect(bucketLabel('UNKNOWN', tt)).toBe(TR['cinv.bucket.UNKNOWN'])
    expect(hashLabel('SHA1', tt)).toBe('SHA-1')
    expect(hashLabel('OTHER', tt)).toBe(TR['cinv.hash.OTHER'])
    expect(keyText(ROWS[0])).toBe('RSA 2048')
    expect(keyText(ROWS[3])).toBe('—')
    expect(daysText(-3, tt)).toBe(tt('cinv.daysPast', 3))
    expect(daysText(null, tt)).toBe('—')
  })
  it('süzgeç özeti ve kapsam metni', () => {
    expect(filterSummary(DEFAULT_FILTERS, tt)).toBe(TR['cinv.f.none'])
    const s = filterSummary({ ...DEFAULT_FILTERS, team: '2', category: 'BROKEN', remnant: true }, tt, () => 'Takım B')
    expect(s).toContain('Takım B')
    expect(s).toContain(TR['cinv.cat.BROKEN'])
    expect(s).toContain(TR['cinv.f.remnantOn'])
    expect(scopeText({ all: true }, tt)).toBe(TR['cinv.scopeAll'])
    expect(scopeText({ all: false, teams: [{ id: 1, name: 'Takım A' }] }, tt)).toBe(tt('cinv.scopeTeams', 'Takım A'))
  })
  it('dışa aktarım satırı sütun sayısıyla aynı uzunlukta; sayılar sayı kalır; manuel sürüm yazılır', () => {
    const cols = exportColumns(tt)
    for (const r of ROWS) expect(exportRow(r, tt)).toHaveLength(cols.length)
    const cells = exportRow(ROWS[3], tt)
    expect(cells[0]).toBe(4)
    expect(cells[4]).toBe(`${TR['cinv.src.MANUAL']} v2`)
    expect(cells[5]).toBe(TR['cinv.noTeam'])
    expect(cells[20]).toBe(5)
    expect(exportRow(ROWS[1], tt)[14]).toBe(TR['cinv.rem.SHA1_LEAF'])
  })
  it('takım tablosu: başlık + takımlar + takımsız (varsa)', () => {
    const teams = [{ team_id: 1, team_name: 'Takım A', total: 3, by_category: { BROKEN: 1, LEGACY: 2 }, by_band: { P1: 1 }, remnants: 1, top_score: 90, next_migrate_by: '2026-10-10' }]
    const out = teamExportRows(teams, { total: 2, by_category: { UNKNOWN: 2 }, by_band: { P4: 2 } }, tt)
    expect(out).toHaveLength(3)
    expect(out[0]).toHaveLength(out[1].length)
    expect(out[2][0]).toBe(TR['cinv.noTeam'])
    expect(teamExportRows(teams, { total: 0 }, tt)).toHaveLength(2)
  })
  it('özet satırları künyeyi (hazırlanma / veri tarihi / kapsam / süzgeç) ve sınırlamayı taşır', () => {
    const rows = summaryRows({ generated_at: '2026-10-10T08:00:00', data_as_of: '2026-10-10T07:00:00', scope: { all: true },
      summary: { total: 5, by_pqc: { VULNERABLE: 4 }, remnants: { sha1_leaf: 1 } } }, DEFAULT_FILTERS, tt, { fmtDateSec: (v) => `D(${v})`, now: 'NOW' })
    const map = Object.fromEntries(rows)
    expect(map[TR['cinv.xl.preparedAt']]).toBe('D(NOW)')
    expect(map[TR['cinv.xl.dataAsOf']]).toBe('D(2026-10-10T07:00:00)')
    expect(map[TR['cinv.xl.scope']]).toBe(TR['cinv.scopeAll'])
    expect(map[TR['cinv.xl.filters']]).toBe(TR['cinv.f.none'])
    expect(map[TR['cinv.rem.SHA1_LEAF']]).toBe(1)
    expect(map[TR['cinv.xl.kexNote']]).toBe(TR['cinv.kexNote'])
  })
  it('dosya adı tarih damgalı', () => {
    expect(exportFileName('xlsx', new Date(2026, 9, 10, 9, 5))).toBe('sitemonitor-crypto-inventory-20261010-0905.xlsx')
  })
})

describe('cryptoInventoryModel — dinamik çeviri anahtarları iki dilde de var', () => {
  const dynamic = [
    ...CATEGORIES.flatMap((c) => [`cinv.cat.${c}`, `cinv.catShort.${c}`, `cinv.catDesc.${c}`, `cinv.target.${c}`, `cinv.targetShort.${c}`]),
    ...PQC_STATES.map((p) => `cinv.pqc.${p}`),
    ...BANDS.map((b) => `cinv.band.${b}`),
    ...SOURCES.map((s) => `cinv.src.${s}`),
    ...SORTS.map((s) => `cinv.sort.${s}`),
    ...REMNANTS.map((r) => `cinv.rem.${r}`),
    ...ACTIONS.map((a) => `cinv.actionBy.${a}`),
    ...TIERS.filter((x) => x !== 'none').map((x) => `cinv.tier.${x}`),
    ...['exposure', 'strength', 'renewal', 'hndl'].map((k) => `cinv.rule.${k}`),
    // sabit adı olmayan kovalar / özetler çeviriden gelir
    ...BUCKETS.filter((b) => bucketLabel(b, (k) => `@${k}`).startsWith('@')).map((b) => `cinv.bucket.${b}`),
    ...SIG_HASHES.filter((h) => hashLabel(h, (k) => `@${k}`).startsWith('@')).map((h) => `cinv.hash.${h}`),
  ]
  it.each(dynamic)('%s', (key) => {
    expect(TR[key], `TR ${key}`).toBeTruthy()
    expect(EN[key], `EN ${key}`).toBeTruthy()
  })
})
