/**
 * Takım veri kalitesi kural kodları — backend `service/quality/DataQualityRule` ile SIRA DAHİL birebir aynı
 * (kapı: backend `DataQualityRuleI18nGateTest.frontendCodeListMatches`). Arayüz metinleri `dq.rule.<KOD>.title|why|fix`
 * anahtarlarıyla dinamik çevrilir; yeni kod eklenince TR + EN üç anahtar birlikte eklenir.
 */
export const DATA_QUALITY_RULES = [
  'INV_NO_TEAM',
  'MON_NO_TEAM',
  'INV_NO_TIER',
  'INV_TIER_SUSPECT',
  'INV_NO_CONTACTS',
  'INV_NEVER_CHECKED',
  'INV_STALE_CHECK',
  'INV_CHECK_FAILING',
  'NOC_CRITICAL_UNCOVERED',
  'MON_PAUSED_LONG',
  'MON_NO_GROUP',
  'MON_DUPLICATE',
  'TEAM_NO_MEMBERS',
  'TEAM_NO_MANAGER',
  'TEAM_NO_NOTIFY_ADDRESS',
  'TEAM_NO_ESCALATION',
]

/** Bantlar (backend `DataQualityScore.Band`) — iyiden kötüye; `NO_DATA` puanı olmayan takım. */
export const DATA_QUALITY_BANDS = ['EXCELLENT', 'GOOD', 'NEEDS_ATTENTION', 'POOR', 'NO_DATA']

/** Önemler (backend `DataQualityRule.Severity`) — ağırlık 3 / 2 / 1. */
export const DATA_QUALITY_SEVERITIES = ['HIGH', 'MEDIUM', 'LOW']
