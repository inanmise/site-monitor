import { describe, it, expect } from 'vitest'
import {
  bandBadgeVariant, bandColor, bandCounts, bandOf, bandTone, csvRowsOf, deltaTone, factText, failingRules, filterRules,
  filterTeams, formatDelta, healthPercent, linkFor, normalizeSummary, sortTeams, trendRows, NOC_SETTINGS_LINK,
} from '../components/dataquality/dataQualityModel.js'
import { DATA_QUALITY_RULES } from '../components/dataquality/dataQualityCodes.js'

const TEAMS = [
  { id: 1, name: 'Ödeme', score: 41, band: 'POOR', findings: 20 },
  { id: 2, name: 'altyapı', score: 96, band: 'EXCELLENT', findings: 1 },
  { id: 3, name: 'İnsan Kaynakları', score: null, band: 'NO_DATA', findings: 0 },
  { id: 4, name: 'Kanal', score: 78, band: 'GOOD', findings: 7 },
  { id: 5, name: 'Bant', score: 78, band: 'GOOD', findings: 9 },
]

describe('veri kalitesi modeli — bantlar ve fark', () => {
  it('bilinmeyen bant NO_DATA; ton + renk + rozet tek tabloda', () => {
    expect(bandOf('XYZ')).toBe('NO_DATA')
    expect(bandTone('EXCELLENT')).toBe('success')
    expect(bandTone('POOR')).toBe('danger')
    expect(bandColor('POOR')).toBe('var(--destructive)')
    expect(bandColor('EXCELLENT')).toBe('var(--success)')
    expect(bandBadgeVariant('NEEDS_ATTENTION')).toBe('warning')
    expect(bandBadgeVariant(undefined)).toBe('outline')
  })

  it('7 günlük fark: tipografik eksi, sıfır, yok', () => {
    expect(formatDelta(4)).toBe('+4')
    expect(formatDelta(-3)).toBe('−3')
    expect(formatDelta(0)).toBe('0')
    expect(formatDelta(null)).toBeNull()
    expect(deltaTone(2)).toBe('up')
    expect(deltaTone(-1)).toBe('down')
    expect(deltaTone(0)).toBe('neutral')
  })

  it('bant sayaçları', () => {
    expect(bandCounts(TEAMS)).toEqual({ EXCELLENT: 1, GOOD: 2, NEEDS_ATTENTION: 0, POOR: 1, NO_DATA: 1 })
  })
})

describe('veri kalitesi modeli — süzme ve sıralama', () => {
  it('arama Türkçe büyük/küçük harf duyarsız; bant süzgeci', () => {
    expect(filterTeams(TEAMS, { q: 'ÖDEME' }).map((t) => t.id)).toEqual([1])
    expect(filterTeams(TEAMS, { q: 'insan' }).map((t) => t.id)).toEqual([3])
    expect(filterTeams(TEAMS, { band: 'GOOD' }).map((t) => t.id)).toEqual([4, 5])
    expect(filterTeams(TEAMS, { q: 'ka', band: 'GOOD' }).map((t) => t.id)).toEqual([4])
  })

  it('varsayılan: en düşük puan üstte; puansız her sıralamada SONDA (ad sıralaması hariç); eşitlikte ad', () => {
    expect(sortTeams(TEAMS).map((t) => t.id)).toEqual([1, 5, 4, 2, 3])
    expect(sortTeams(TEAMS, 'score_desc').map((t) => t.id)).toEqual([2, 5, 4, 1, 3])
    expect(sortTeams(TEAMS, 'findings').map((t) => t.id)).toEqual([1, 5, 4, 2, 3])
    expect(sortTeams(TEAMS, 'name').map((t) => t.name)).toEqual(['altyapı', 'Bant', 'İnsan Kaynakları', 'Kanal', 'Ödeme'])
    expect(TEAMS.map((t) => t.id)).toEqual([1, 2, 3, 4, 5])   // girdi değişmez
  })

  it('kural listesi: yalnız kusurlular, katalog sırasıyla; önem + metin süzgeci', () => {
    const rules = [
      { code: 'TEAM_NO_ESCALATION', severity: 'HIGH', failing: 1, eligible: 1, items: [{ name: 'Ödeme', target: null }] },
      { code: 'INV_NO_TIER', severity: 'MEDIUM', failing: 2, eligible: 10, items: [{ name: 'a.example.com', target: 'a.example.com' }, { name: 'b.example.com', target: 'b.example.com:8443' }] },
      { code: 'MON_NO_GROUP', severity: 'LOW', failing: 0, eligible: 5, items: [] },
    ]
    expect(failingRules(rules).map((r) => r.code)).toEqual(['INV_NO_TIER', 'TEAM_NO_ESCALATION'])
    expect(filterRules(rules, { severity: 'HIGH' }).map((r) => r.code)).toEqual(['TEAM_NO_ESCALATION'])
    const q = filterRules(rules, { q: '8443' })
    expect(q.map((r) => r.code)).toEqual(['INV_NO_TIER'])
    expect(q[0].items.map((i) => i.name)).toEqual(['b.example.com'])
  })

  it('düzeltme listesi ETKİYE göre: en çok puan kaybettiren üstte, eşitlikte katalog sırası', () => {
    const rules = [
      { code: 'INV_NO_TIER', severity: 'MEDIUM', failing: 1, eligible: 9, points: 2, items: [] },
      { code: 'TEAM_NO_ESCALATION', severity: 'HIGH', failing: 1, eligible: 1, points: 30, items: [] },
      { code: 'MON_NO_GROUP', severity: 'LOW', failing: 1, eligible: 9, points: 2, items: [] },
      { code: 'INV_NO_TEAM', severity: 'HIGH', failing: 1, eligible: 9, items: [] },
    ]
    expect(filterRules(rules).map((r) => r.code)).toEqual(['TEAM_NO_ESCALATION', 'INV_NO_TIER', 'MON_NO_GROUP', 'INV_NO_TEAM'])
  })

  it('sağlık yüzdesi', () => {
    expect(healthPercent({ eligible: 40, failing: 2 })).toBe(95)
    expect(healthPercent({ eligible: 0, failing: 0 })).toBe(100)
    expect(healthPercent({ eligible: 1, failing: 1 })).toBe(0)
  })

  it('özet normalizasyonu: varsayılan dizi gövdesi ya da bozuk yanıt null', () => {
    expect(normalizeSummary([])).toBeNull()
    expect(normalizeSummary(null)).toBeNull()
    const n = normalizeSummary({ teams: [{ id: 1 }, null, { name: 'kimliksiz' }], notes: 'x' })
    expect(n.teams).toEqual([{ id: 1 }])
    expect(n.notes).toEqual([])
    expect(n.unassigned).toBeNull()
  })

  it('eğilim: geçersiz noktalar düşer, etiket gg.aa', () => {
    expect(trendRows([{ day: '2026-10-09', score: 80 }, { day: 5, score: 3 }, { day: '2026-10-10', score: null }, null]))
      .toEqual([{ day: '2026-10-09', label: '09.10', score: 80 }])
    expect(trendRows('x')).toEqual([])
  })

  it('kod listesi backend kataloğuyla aynı uzunlukta ve tekrarsız', () => {
    expect(new Set(DATA_QUALITY_RULES).size).toBe(DATA_QUALITY_RULES.length)
    expect(DATA_QUALITY_RULES).toHaveLength(16)
  })
})

describe('veri kalitesi modeli — derin bağlantılar (düzeltme yeri)', () => {
  const inv = (over = {}) => ({ kind: 'inventory', type: 'SSL', id: 7, name: 'pay.example.com', target: 'pay.example.com', can_edit: true, ...over })

  it('envanter kuralları → Envanter çekmecesi; başkasının kaydında tüm takımlar kapsamı', () => {
    expect(linkFor('INV_NO_TIER', inv())).toEqual({ tab: 'domains', params: { domain: 'pay.example.com' } })
    expect(linkFor('INV_NO_CONTACTS', inv({ can_edit: false })))
      .toEqual({ tab: 'domains', params: { domain: 'pay.example.com', i_scope: 'all' } })
    expect(linkFor('INV_NO_TEAM', inv()).tab).toBe('domains')
    expect(linkFor('MON_PAUSED_LONG', inv()).tab).toBe('domains')
    expect(linkFor('INV_NEVER_CHECKED', inv()).tab).toBe('domains')
  })

  it('7/24 kritik kayıt → Pano sertifika penceresinin 7/24 alanı; tekrarlayan hata → sertifika penceresi', () => {
    expect(linkFor('NOC_CRITICAL_UNCOVERED', inv())).toEqual({ tab: 'dashboard', params: { domain: 'pay.example.com', open: 'noc' } })
    expect(linkFor('INV_CHECK_FAILING', inv())).toEqual({ tab: 'dashboard', params: { domain: 'pay.example.com', open: 'cert' } })
  })

  it('izleme → türün sayfasında detay penceresi (dokuz tür)', () => {
    const types = { HTTP: 'http', PING: 'ping', PORT: 'port', DNS: 'dns', DOMAIN: 'domain', KEYWORD: 'keyword', PAGE: 'page', PAGESPEED: 'pagespeed', SCRIPTED: 'scripted' }
    for (const [type, tab] of Object.entries(types)) {
      expect(linkFor('MON_NO_GROUP', { kind: 'monitor', type, id: 12, name: 'x' })).toEqual({ tab, params: { monitor: 12 } })
    }
    expect(linkFor('MON_NO_GROUP', { kind: 'monitor', type: 'NOPE', id: 1 })).toBeNull()
  })

  it('takım → Yönetim: eskalasyon kişileri (takım süzgeçli) ya da Takımlar (ad süzgeçli)', () => {
    const team = { kind: 'team', type: 'TEAM', id: 4, name: 'Ödeme' }
    expect(linkFor('TEAM_NO_ESCALATION', team)).toEqual({ tab: 'admin', params: { g_tab: 'contacts', g_team: '4' } })
    expect(linkFor('TEAM_NO_MEMBERS', team)).toEqual({ tab: 'admin', params: { g_tab: 'teams', g_q: 'Ödeme' } })
    expect(linkFor('TEAM_NO_NOTIFY_ADDRESS', team)).toEqual({ tab: 'admin', params: { g_tab: 'teams', g_q: 'Ödeme' } })
    expect(linkFor('TEAM_NO_MANAGER', { ...team, id: null })).toBeNull()
    expect(NOC_SETTINGS_LINK).toEqual({ tab: 'settings', params: { sec: 'noc' } })
  })

  it('kalem yoksa ya da adsız envanter → bağlantı yok', () => {
    expect(linkFor('INV_NO_TIER', null)).toBeNull()
    expect(linkFor('INV_NO_TIER', inv({ name: '' }))).toBeNull()
  })
})

describe('veri kalitesi modeli — kısa neden metni ve CSV', () => {
  it('kurala özgü gerçekler arayüz anahtarına çevrilir', () => {
    expect(factText('INV_NO_TEAM', { reason: 'TEAM_INACTIVE' })).toEqual({ key: 'dq.owner.TEAM_INACTIVE', args: [] })
    expect(factText('INV_TIER_SUSPECT', { reason: 'NONPROD_NAME_ON_PROD_TIER', tier: 1, token: 'uat' }))
      .toEqual({ key: 'dq.tier.NONPROD_NAME_ON_PROD_TIER', args: [1, 'uat'] })
    expect(factText('INV_CHECK_FAILING', { errors_7d: 5 })).toEqual({ key: 'dq.fact.errors7d', args: [5] })
    expect(factText('MON_PAUSED_LONG', { days: 40, exact: true }).key).toBe('dq.fact.pausedExact')
    expect(factText('MON_PAUSED_LONG', { days: 40, exact: false }).key).toBe('dq.fact.pausedApprox')
    expect(factText('MON_DUPLICATE', { duplicate_of_name: 'Asıl' })).toEqual({ key: 'dq.fact.duplicateOf', args: ['Asıl'] })
    expect(factText('TEAM_NO_MANAGER', { leader: 'NONE', manager: 'INACTIVE' }).key).toBe('dq.fact.lead.INACTIVE')
    expect(factText('TEAM_NO_MANAGER', { leader: 'NONE', manager: 'NONE' }).key).toBe('dq.fact.lead.NONE')
    expect(factText('TEAM_NO_ESCALATION', { missing: ['HIGH', 'CRITICAL'] }).key).toBe('dq.fact.escalationBoth')
    expect(factText('TEAM_NO_ESCALATION', { missing: ['HIGH'] }).key).toBe('dq.fact.escalationHigh')
    expect(factText('INV_NO_TIER', {})).toBeNull()
    expect(factText('INV_NO_TEAM', null)).toBeNull()
  })

  it('CSV: başlık + kusurlu kuralların kalemleri (kural adı çevrilmiş)', () => {
    const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)
    const rows = csvRowsOf([
      { code: 'INV_NO_TIER', severity: 'MEDIUM', failing: 1, eligible: 2, items: [{ type: 'SSL', name: 'a.example.com', target: 'a.example.com', facts: {} }] },
      { code: 'MON_NO_GROUP', severity: 'LOW', failing: 0, eligible: 2, items: [] },
    ], t)
    expect(rows).toHaveLength(2)
    expect(rows[1]).toEqual(['dq.rule.INV_NO_TIER.title', 'dq.severity.MEDIUM', 'dq.type.SSL', 'a.example.com', 'a.example.com', ''])
  })
})
