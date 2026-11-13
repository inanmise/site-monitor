import { describe, it, expect, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { setDateLocale } from '../i18n/dateLocale.js'
import {
  NO_TEAM, NON_SIZED_KEY_TOKENS, buildTeams, domainTeamMap, gradeOf, inWeek, istanbulDay, keySizeStatus, matchesSec,
  resolveTeam, secFlagsOf, weekWindows, withTeamless, applyFilters, matrixRows,
} from '../components/stats/statsModel.js'
import {
  alarmTrend, caDistribution, freshestCommon, healthTone, securityCounts, slaPct, sortScorecard, statsPct, teamScorecard, tierDistribution,
  weekHistogram,
} from '../components/stats/statsInsights.js'
import { matrixSheetRows, scorecardSheetRows, summaryRows, tableSheetRows } from '../components/stats/statsExportModel.js'

/**
 * İstatistikler zenginleştirmesi (2026-11-13) — saf model: sayfaya özel yüzde (Türkçe ondalık virgül), güvenlik duruşu
 * (anahtar boyu kuralı Java ile aynı), sağlayıcı / katman dağılımı, İstanbul haftaları, takımsız satır, takım çözümü (ham
 * kimlik yok), takım karnesi (bilinmeyen ≠ 0), dışa aktarım satırları.
 */
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const t = (k, ...a) => `${k}${a.length ? `(${a.join('|')})` : ''}`

afterEach(() => setDateLocale('en'))

describe('statsPct — sayfaya özel yüzde', () => {
  it('Türkçe ondalık virgül ve yüzde öne; İngilizce nokta ve yüzde sona; bilinmeyen tire', () => {
    setDateLocale('tr')
    expect(statsPct(97.5)).toBe('%97,5')
    expect(statsPct(100)).toBe('%100')
    expect(statsPct(0)).toBe('%0')
    expect(statsPct(null)).toBe('—')
    expect(statsPct('x')).toBe('—')
    setDateLocale('en')
    expect(statsPct(97.5)).toBe('97.5%')
  })
})

describe('slaPct — SLA hedefi ve hedefle karşılaştırılan değer (inceleme 2026-11-13)', () => {
  it('iki ondalık: hedef 99,95 "%100" değil; ölçülen değer AŞAĞI kesilir — hedefin altındaki asla hedefte / üstünde görünmez', () => {
    setDateLocale('tr')
    expect(slaPct(99.95)).toBe('%99,95')
    expect(slaPct(99.9)).toBe('%99,9')
    expect(statsPct(99.95)).toBe('%100')           // eski tek ondalık hatası (oracle)
    expect(slaPct(99.86, { floor: true })).toBe('%99,86')   // kayan nokta: 99,86 × 100 = 9985,999…
    expect(slaPct(99.949, { floor: true })).toBe('%99,94')  // hedef 99,95'in altında kalır (yuvarlamayla %99,95 olurdu)
    expect(slaPct(99.8999, { floor: true })).toBe('%99,89')
    expect(slaPct(97.25, { floor: true })).toBe('%97,25')
    expect(slaPct(null)).toBe('—')
    setDateLocale('en')
    expect(slaPct(99.95)).toBe('99.95%')
    // Hedefin altındaki her değer, biçimlenmiş hâliyle de hedeften küçük
    for (const target of [99.9, 99.95, 99.99]) {
      for (let k = 1; k <= 50; k++) {
        const v = target - k * 0.0007
        const shown = Number(slaPct(v, { floor: true }).replace('%', ''))
        expect(shown, `${v} < ${target}`).toBeLessThan(target)
      }
    }
  })
})

describe('güvenlik duruşu', () => {
  it('anahtar boyu kuralı sunucunun CertificateHealthRules.keySizeStatus aynası (ölçülmeyen aile listesi Java ile AYNI)', () => {
    const java = fs.readFileSync(path.join(root, 'backend/src/main/java/com/sitemonitor/service/CertificateHealthRules.java'), 'utf8')
    const m = java.match(/NON_SIZED_KEY_TOKENS = List\.of\(([^)]*)\)/s)
    const tokens = [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])
    expect(NON_SIZED_KEY_TOKENS).toEqual(tokens)
    expect(keySizeStatus('RSA', 1024)).toBe('FAIL')
    expect(keySizeStatus('RSA', 2048)).toBe('OK')
    expect(keySizeStatus('EC', 224)).toBe('FAIL')
    expect(keySizeStatus('ECDSA', 256)).toBe('OK')
    expect(keySizeStatus('Ed25519', 256)).toBe('UNKNOWN')
    expect(keySizeStatus('RSA', -1)).toBe('UNKNOWN')
    expect(keySizeStatus('RSA', null)).toBe('UNKNOWN')
  })

  it('bayraklar sunucu alanlarından; notsuz sertifika "none" — iyi sayılmaz; süzgeç not ve bayrak', () => {
    const certs = [
      { domain: 'a', tls_grade: 'A+', chain_status: 'VALID' },
      { domain: 'b', tls_grade: 'F', chain_status: 'BROKEN', trust_status: 'UNTRUSTED', public_key_algorithm: 'RSA', public_key_size: 1024 },
      { domain: 'c', revocation_status: 'REVOKED', deployment_status: 'INCOMPLETE' },
      { domain: 'd', tls_grade: 'Z' },
    ]
    expect(secFlagsOf(certs[1])).toEqual(['chain', 'untrusted', 'weakkey'])
    expect(secFlagsOf(certs[2])).toEqual(['revoked', 'mismatch'])
    expect(gradeOf(certs[3])).toBe('none')
    const sc = securityCounts(certs)
    expect(sc.grades).toMatchObject({ 'A+': 1, F: 1, none: 2 })
    expect(sc.graded).toBe(2)
    expect(sc.ungraded).toBe(2)
    expect(sc.flagged).toBe(2)
    expect(matchesSec(certs[0], 'g:A+')).toBe(true)
    expect(matchesSec(certs[2], 'g:none')).toBe(true)
    expect(applyFilters(certs, { sec: 'weakkey' }).map((c) => c.domain)).toEqual(['b'])
  })
})

describe('sağlayıcı ve katman dağılımı', () => {
  it('en çok N sağlayıcı + diğer; adı okunamayan ayrı; katman sırası T1..T4, sınıflandırılmamış', () => {
    const certs = [
      ...Array.from({ length: 3 }, (_, i) => ({ domain: `r${i}`, issuer_cn: 'R3', tier: 1 })),
      { domain: 'd', issuer_cn: 'DigiCert', tier: 2 }, { domain: 'x', issuer_cn: 'X', tier: null }, { domain: 'e', status: 'error' },
    ]
    const ca = caDistribution(certs, 2)
    expect(ca.rows).toEqual([{ name: 'R3', count: 3 }, { name: 'DigiCert', count: 1 }])
    expect(ca).toMatchObject({ other: 1, otherNames: 1, unknown: 1, total: 6 })
    expect(tierDistribution(certs)).toEqual([{ tier: 1, count: 3 }, { tier: 2, count: 1 }, { tier: 3, count: 0 }, { tier: 4, count: 0 }, { tier: 0, count: 2 }])
  })
})

describe('12 hafta (İstanbul)', () => {
  it('pencere bu haftanın PAZARTESİSİNDEN; bitiş İstanbul günü (UTC 21:30 → ertesi gün); dolmuş / hatalı sayılmaz', () => {
    // 2026-11-13 Cuma 10:00 İstanbul
    const now = Date.parse('2026-11-13T07:00:00Z')
    const w = weekWindows(now)
    expect(w).toHaveLength(12)
    expect(w[0]).toMatchObject({ start: '2026-11-09', end: '2026-11-16' })
    expect(w[1].start).toBe('2026-11-16')
    expect(istanbulDay('2026-11-15T21:30:00')).toBe('2026-11-16')   // UTC pazar akşamı = İstanbul pazartesi
    const c1 = { domain: 'a', days_remaining: 2, not_after: '2026-11-15T20:00:00' }   // İstanbul 15'i 23:00 → 0. hafta
    const c2 = { domain: 'b', days_remaining: 3, not_after: '2026-11-15T21:30:00' }   // İstanbul 16'sı → 1. hafta
    const gone = { domain: 'c', days_remaining: -1, not_after: '2026-11-12T00:00:00' }
    const err = { domain: 'd', status: 'error', days_remaining: 5, not_after: '2026-11-14T00:00:00' }
    expect(inWeek(c1, w[0])).toBe(true)
    expect(inWeek(c2, w[0])).toBe(false)
    expect(inWeek(c2, w[1])).toBe(true)
    expect(inWeek(gone, w[0])).toBe(false)
    expect(inWeek(err, w[0])).toBe(false)
    const h = weekHistogram([c1, c2, gone, err], w)
    expect(h[0].count).toBe(1)
    expect(h[1].count).toBe(1)
    expect(h[0].minDays).toBe(2)
  })
})

describe('takımlar', () => {
  const certs = [
    { domain: 'a', team_id: 1, team_name: 'A', owner_team_ids: [1, 2] },
    { domain: 'b', team_id: 2, team_name: 'B', owner_team_ids: [2] },
    { domain: 'orphan', team_id: null, owner_team_ids: [] },
    { domain: 'passive', team_id: 9, team_name: 'Pasif', owner_team_ids: [9] },
  ]
  const lean = { mode: 'domains', teams: [
    { team_id: 1, team_name: 'A', sy_domains: ['a'], ug_domains: [] },
    { team_id: 2, team_name: 'B', sy_domains: ['b'], ug_domains: ['a'] },
  ] }

  it('yalın biçim (sy_domains / ug_domains) eski biçimle aynı takım kümesini kurar; UG kimliği eşlemede', () => {
    const teams = buildTeams(lean)
    expect(teams.map((x) => [x.id, [...x.domains].sort()])).toEqual([[1, ['a']], [2, ['a', 'b']]])
    const dtm = domainTeamMap(teams)
    expect(dtm.get('a')).toMatchObject({ sy: ['A'], ug: ['B'], ids: { A: 1, B: 2 } })
  })

  it('takımsız / pasif takımlı sertifikalar ayrı satır (none); takım listesi yokken eklenmez', () => {
    const teams = withTeamless(buildTeams(lean), certs, 'Takımsız')
    const tl = teams.find((x) => x.id === NO_TEAM)
    expect([...tl.domains].sort()).toEqual(['orphan', 'passive'])
    expect(withTeamless([], certs, 'x')).toEqual([])
    expect(matrixRows(certs, teams).find((r) => r.id === NO_TEAM).total).toBe(2)
    expect(resolveTeam(NO_TEAM, teams, certs).name).toBe('Takımsız')
  })

  it('listede olmayan takım: sahip takımlar (SY ∪ UG) üzerinden; ad bilinmiyorsa null (çip ham kimlik yazmaz)', () => {
    const r = resolveTeam('2', [], certs)
    expect([...r.domains].sort()).toEqual(['a', 'b'])
    expect(r.name).toBe('B')
    const unknown = resolveTeam('77', [], certs)
    expect(unknown.name).toBeNull()
    expect(unknown.domains.size).toBe(0)
  })
})

describe('takım karnesi', () => {
  const certs = [
    { domain: 'a', days_remaining: 100 }, { domain: 'b', days_remaining: 10 }, { domain: 'c', days_remaining: -1 },
    { domain: 'd', status: 'error' }, { domain: 'e', days_remaining: null },
  ]
  const teams = [{ id: 1, name: 'A', domains: new Set(['a', 'b', 'c', 'd', 'e']) }, { id: 2, name: 'B', domains: new Set(['zzz']) }]

  it('sertifika sağlığı tablonun kuralıyla; okunmayan kaynak null (bilinmiyor), OKUNAN kaynakta görünmeyen takım gerçek 0', () => {
    const none = teamScorecard({ teams, certs })
    expect(none).toHaveLength(1)
    expect(none[0].certs).toMatchObject({ total: 5, ok: 1, under30: 1, expired: 1, error: 1, unchecked: 1, evaluated: 4, health: 25 })
    expect(none[0].alerts).toBeNull()
    expect(none[0].sla).toBeNull()
    const full = teamScorecard({
      teams, certs,
      alerts: { teams: [{ team_id: 3, team_name: 'C', open: 2, last30: 5 }] },
      sla: { by_team: [{ team_id: 1, monitors: 4, breaches: 1 }] },
      dq: { teams: [{ id: 3, name: 'C', score: 80 }] },
    })
    const a = full.find((r) => r.id === 1), c = full.find((r) => r.id === 3)
    expect(a.alerts).toEqual({ open: 0, last30: 0 })
    expect(a.sla).toEqual({ breaches: 1, monitors: 4 })
    expect(a.dq).toBeNull()
    expect(c).toMatchObject({ name: 'C', certs: null, alerts: { open: 2, last30: 5 }, dq: { score: 80 } })
  })

  it('eski sunucu sla.by_team göndermez → takım SLA bilgisi BİLİNMİYOR (0 / 0 değil); sertifikasız takım adı yalın listeden', () => {
    const rows = teamScorecard({
      teams, certs,
      alerts: { teams: [{ team_id: 2, team_name: null, open: 1, last30: 2 }] },
      sla: { target_pct: 99.9, monitors: 3, breaches: 1 },   // by_team yok
    })
    for (const r of rows) expect(r.sla).toBeNull()
    // Takım B'nin sertifikası yok (zzz listede değil) ama alarmı var: adı yalın listeden, "Bilinmeyen takım" değil
    expect(rows.find((r) => r.id === 2)).toMatchObject({ name: 'B', certs: null, alerts: { open: 1, last30: 2 } })
    // SLA kırılımı ad taşımaz: yalnız SLA'sı olan sertifikasız takım da adını listeden alır
    const slaOnly = teamScorecard({ teams, certs, sla: { by_team: [{ team_id: 2, monitors: 5, breaches: 0 }] } })
    expect(slaOnly.find((r) => r.id === 2)).toMatchObject({ name: 'B', sla: { breaches: 0, monitors: 5 } })
  })

  it('sıralama: bilinmeyen her yönde sona; sağlık tonu eşikleri; bilinmiyor nötr', () => {
    const rows = [{ name: 'x', certs: null }, { name: 'y', certs: { health: 50 } }, { name: 'z', certs: { health: 99 } }]
    expect(sortScorecard(rows, 'health', 'asc').map((r) => r.name)).toEqual(['y', 'z', 'x'])
    expect(sortScorecard(rows, 'health', 'desc').map((r) => r.name)).toEqual(['z', 'y', 'x'])
    expect([healthTone(null), healthTone(96), healthTone(92), healthTone(10)]).toEqual(['neutral', 'ok', 'warn', 'bad'])
  })
})

describe('alarm eğilimi, tazelik, dışa aktarım satırları', () => {
  it('alarmTrend seri + MTTR; yanıt yoksa null; tazelik parçaların EN ESKİSİ', () => {
    expect(alarmTrend(null)).toBeNull()
    const tr = alarmTrend({ series: [{ date: '2026-11-01', count: 2 }, { date: '2026-11-02', count: 5 }], total: 7, mttr_minutes: 30 })
    expect(tr).toMatchObject({ total: 7, peak: 5, mttr: 30 })
    // Hiç alarm yokken "çözülen %0" yok → bilinmiyor (inceleme 2026-11-13)
    expect(alarmTrend({ series: [{ date: '2026-11-01', count: 0 }], total: 0, resolved_pct: 0, mttr_minutes: null }).resolvedPct).toBeNull()
    expect(alarmTrend({ series: [], total: 4, resolved_pct: 50 }).resolvedPct).toBe(50)
    expect(freshestCommon('2026-11-13T10:00:00', Date.parse('2026-11-13T09:00:00Z'))).toBe(Date.parse('2026-11-13T09:00:00Z'))
    expect(freshestCommon(null, undefined)).toBeNull()
  })

  it('özet satırları: okunamayan blok boş hücre (asla 0); alarm izni yoksa alarm satırı yok', () => {
    const rows = summaryRows({ t, kpi: { total: 3 }, exec: { certs: { error_msg: 'X' }, alerts: { visible: false }, sla: { breaches: 2, monitors: 9 } } })
    const map = Object.fromEntries(rows.slice(1))
    expect(map['exs.health']).toBe('')
    expect(map['exs.alerts']).toBeUndefined()
    expect(map['stx.xl.slaBreaches']).toBe(2)
    const sc = scorecardSheetRows([{ name: 'A', certs: { health: 50, evaluated: 2, under30: 1, expired: 0, error: 1 }, alerts: { open: 3, last30: 4 } }], t, (r) => r.name, { alerts: false })
    expect(sc[1]).toEqual(['A', 50, 2, 1, 1, '', '', '', '', ''])
    expect(matrixSheetRows([{ name: 'A', counts: { valid: 1 }, total: 1 }], t, (r) => r.name)[1][0]).toBe('A')
    expect(tableSheetRows([{ domain: 'a', days_remaining: 5, tls_grade: 'B' }], t, new Map())[1][8]).toBe('B')
  })
})
