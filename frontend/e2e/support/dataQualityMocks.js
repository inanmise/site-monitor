// Veri Kalitesi (2026-10-10) e2e mock'ları — en geniş hâl: uzun takım adları, beş bant, Sahipsiz kovası, 7/24 notu,
// 30 günlük eğilim, düzeltme listesinde uzun alan adları ve URL'ler (taşma ölçümü dolu verilerle yapılsın).

const DAY = 24 * 3600 * 1000

function trend(base, days = 30) {
  const out = []
  const now = Date.UTC(2026, 9, 10)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now - i * DAY).toISOString().slice(0, 10)
    out.push({ day: d, score: Math.max(0, Math.min(100, base - Math.round(i / 3) + (i % 4))) })
  }
  return out
}

const RULES_ORG = [
  { code: 'INV_NO_TEAM', scope: 'INVENTORY', severity: 'HIGH', weight: 3, eligible: 420, failing: 6, health: 0.986, points: 1.1 },
  { code: 'INV_NO_TIER', scope: 'INVENTORY', severity: 'MEDIUM', weight: 2, eligible: 420, failing: 37, health: 0.912, points: 4.2 },
  { code: 'NOC_CRITICAL_UNCOVERED', scope: 'INVENTORY', severity: 'HIGH', weight: 3, eligible: 160, failing: 22, health: 0.862, points: 7.6 },
  { code: 'TEAM_NO_ESCALATION', scope: 'TEAM', severity: 'HIGH', weight: 3, eligible: 14, failing: 4, health: 0.714, points: 9.9 },
  { code: 'MON_NO_GROUP', scope: 'MONITOR', severity: 'LOW', weight: 1, eligible: 380, failing: 51, health: 0.866, points: 1.6 },
  { code: 'MON_PAUSED_LONG', scope: 'MONITOR', severity: 'LOW', weight: 1, eligible: 800, failing: 12, health: 0.985, points: 0.2 },
]

const TEAMS = [
  ['Ödeme Sistemleri ve Kart Operasyonları Platform Ekibi', 41, 'POOR', -6],
  ['Altyapı', 96, 'EXCELLENT', 2],
  ['Kanal Uygulamaları — Mobil ve İnternet Şubesi', 78, 'GOOD', 4],
  ['Veri Ambarı ve Raporlama', 63, 'NEEDS_ATTENTION', 0],
  ['Güvenlik Operasyon Merkezi', 88, 'GOOD', null],
  ['İnsan Kaynakları', null, 'NO_DATA', null],
].map(([name, score, band, delta], i) => ({
  id: i + 1, name, score, band, delta_7d: delta, findings: score == null ? 0 : Math.round((100 - score) / 3),
  items: 40 + i * 13, trend: score == null ? [] : trend(score),
  top_issues: score == null || score > 95 ? [] : [
    { code: 'TEAM_NO_ESCALATION', severity: 'HIGH', failing: 1, eligible: 1, points: 18.8 },
    { code: 'NOC_CRITICAL_UNCOVERED', severity: 'HIGH', failing: 7, eligible: 19, points: 6.9 },
    { code: 'INV_NO_TIER', severity: 'MEDIUM', failing: 3, eligible: 40, points: 0.9 },
  ],
}))

export function dataQualitySummaryMock({ seesAll = true } = {}) {
  const out = {
    generated_at: '2026-10-10T06:00:00',
    org: { score: 81, band: 'GOOD', findings: 132, items: 1214, delta_7d: 3, trend: trend(81), rules: RULES_ORG },
    teams: TEAMS,
    notes: [{ code: 'NOC_NOT_CONFIGURED', params: { reason: 'NO_ACTIVE_GROUP', critical: 160 } }],
    catalog: [],
    config: { band_excellent: 90, band_good: 75, band_fair: 50, paused_long_days: 30, repeated_errors: 3, error_window_days: 7 },
  }
  if (seesAll) out.unassigned = { findings: 9, items: 7, rules: [] }
  return out
}

const LONG = 'raporlama-ve-analitik-platformu.ic-servisler.ornek-kurum-alan-adi.example.com'

export function dataQualityTeamMock(key) {
  const unassigned = key === 'unassigned'
  const team = unassigned ? { findings: 9, items: 7 } : TEAMS.find((t) => String(t.id) === String(key)) || TEAMS[0]
  const inv = (id, name, facts = {}, can = true) => ({ kind: 'inventory', type: 'SSL', id, name, target: name, team_id: 1, facts, can_edit: can })
  return {
    generated_at: '2026-10-10T06:00:00',
    unassigned,
    team,
    org_score: 81,
    notes: [],
    rules: [
      { code: 'NOC_CRITICAL_UNCOVERED', scope: 'INVENTORY', severity: 'HIGH', weight: 3, eligible: 19, failing: 2, health: 0.89, points: 6.9, truncated: 0,
        items: [inv(11, LONG, { tier: 1 }), inv(12, 'odeme-api.example.com', { tier: 2 })] },
      { code: 'TEAM_NO_ESCALATION', scope: 'TEAM', severity: 'HIGH', weight: 3, eligible: 1, failing: 1, health: 0, points: 18.8, truncated: 0,
        items: [{ kind: 'team', type: 'TEAM', id: 1, name: team.name || 'Takım', target: null, team_id: 1, facts: { missing: ['HIGH', 'CRITICAL'] }, can_edit: false }] },
      { code: 'INV_TIER_SUSPECT', scope: 'INVENTORY', severity: 'LOW', weight: 1, eligible: 38, failing: 1, health: 0.97, points: 0.4, truncated: 0,
        items: [inv(13, 'uat-odeme.example.com', { tier: 1, reason: 'NONPROD_NAME_ON_PROD_TIER', token: 'uat' })] },
      { code: 'INV_CHECK_FAILING', scope: 'INVENTORY', severity: 'MEDIUM', weight: 2, eligible: 40, failing: 1, health: 0.97, points: 0.9, truncated: 0,
        items: [inv(14, 'eski-portal.example.com', { errors_7d: 21, error: 'java.net.SocketTimeoutException: Connect timed out after 10000 ms while connecting to eski-portal.example.com:443 via proxy' })] },
      { code: 'MON_NO_GROUP', scope: 'MONITOR', severity: 'LOW', weight: 1, eligible: 30, failing: 2, health: 0.93, points: 0.6, truncated: 0,
        items: [
          { kind: 'monitor', type: 'HTTP', id: 21, name: 'Ödeme API sağlık ucu (çok uzun izleme adı örneği — taşmamalı)', target: `https://${LONG}/api/v2/health?verbose=true`, team_id: 1, facts: {}, can_edit: true },
          { kind: 'monitor', type: 'PING', id: 22, name: 'gw-01', target: '10.20.30.40', team_id: 1, facts: {}, can_edit: true },
        ] },
      { code: 'MON_PAUSED_LONG', scope: 'MONITOR', severity: 'LOW', weight: 1, eligible: 60, failing: 1, health: 0.98, points: 0.2, truncated: 0,
        items: [{ kind: 'monitor', type: 'DNS', id: 31, name: 'odeme.example.com (A)', target: 'odeme.example.com (A)', team_id: 1, facts: { days: 74, exact: true }, can_edit: true }] },
      { code: 'TEAM_NO_MEMBERS', scope: 'TEAM', severity: 'HIGH', weight: 3, eligible: 1, failing: 0, health: 1, points: 0 },
    ],
  }
}
