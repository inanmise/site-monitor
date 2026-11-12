// Yönetim sağlık panosu (2026-11-12, çapraz zenginleştirme #2) — saf, test edilebilir sinyal modeli. Girdi: overview
// yanıtı (counts + warnings) + eskalasyon kapsam boşluğu (frontend'de contactsCoverageModel.buildCoverage ile hesaplanır,
// bilinmiyorsa null). Çıktı: eyleme dönük sağlık sinyalleri — her biri sekme + SÜZGEÇ (g_* paramları) ile derin bağlantı.
// Kullanıcı-etkinlik uyarıları (kilitli/atıl/hiç) TEK "Dikkat isteyen kullanıcılar" kutucuğunda toplanır (şeridi çipe
// boğmadan; 2026-09-27 kararının ruhu) ve Kullanıcılar sekmesine süzgeçli gider; kırılım orada.

export const HEALTH_SEVERE = new Set(['NO_ADMIN', 'SINGLE_ADMIN', 'ESCALATION_GAP', 'TEAM_NO_EMAIL'])

function sig(code, count, tab, params, severe) {
  return { code, count, tab, params: params || {}, severe: !!severe, category: categoryOf(code) }
}

function categoryOf(code) {
  if (code === 'ESCALATION_GAP' || code === 'CONTACT_INACTIVE') return 'contacts'
  if (code === 'TEAM_NO_EMAIL' || code === 'TEAM_NO_LEADER') return 'teams'
  if (code === 'GROUP_NO_EMAILS') return 'groups'
  return 'users'
}

/** Kullanıcı "dikkat isteyen" kutucuğunun derin-bağlantı süzgeci: kilitli varsa duruma, yoksa atıl/hiç giriş filtresine. */
function userAttentionParams(count, overview) {
  if (count('USER_LOCKED') > 0) return { g_status: 'locked' }
  if (count('USER_DORMANT') > 0) return { g_dormant: String(overview?.dormant_days || 90) }
  if (count('USER_NEVER_LOGGED_IN') > 0) return { g_dormant: 'never' }
  return {}
}

/**
 * overview + eskalasyon boşluğu → sıralı sağlık sinyalleri (ağır olanlar önce). Ağır: yöneticisiz/tek-yönetici,
 * kritik eskalasyon boşluğu, e-postasız takım (alarm yönlenemeyebilir). Her sinyal count>0 iken üretilir (NO_ADMIN
 * istisna: 0 yönetici = varlığıyla gösterilir). escalationGap null ise (bilinmiyor/okunamadı) o kutucuk hiç çizilmez.
 */
export function buildHealthSignals(overview, escalationGap) {
  const warnings = overview?.warnings || []
  const has = (code) => warnings.some((w) => w.code === code)
  const count = (code) => warnings.find((w) => w.code === code)?.count ?? 0
  const out = []

  // ── Ağır (severe) ──
  if (has('NO_ADMIN')) out.push(sig('NO_ADMIN', 0, 'users', { g_role: 'ADMIN' }, true))
  else if (has('SINGLE_ADMIN')) out.push(sig('SINGLE_ADMIN', 1, 'users', { g_role: 'ADMIN' }, true))
  if (typeof escalationGap === 'number' && escalationGap > 0) out.push(sig('ESCALATION_GAP', escalationGap, 'contacts', {}, true))
  if (has('TEAM_NO_EMAIL')) out.push(sig('TEAM_NO_EMAIL', count('TEAM_NO_EMAIL'), 'teams', {}, true))

  // ── Uyarı (warn) ──
  if (has('TEAM_NO_LEADER')) out.push(sig('TEAM_NO_LEADER', count('TEAM_NO_LEADER'), 'teams', {}, false))
  if (has('GROUP_NO_EMAILS')) out.push(sig('GROUP_NO_EMAILS', count('GROUP_NO_EMAILS'), 'notifyGroups', {}, false))
  if (has('CONTACT_INACTIVE')) out.push(sig('CONTACT_INACTIVE', count('CONTACT_INACTIVE'), 'contacts', {}, false))

  const attn = count('USER_LOCKED') + count('USER_NEVER_LOGGED_IN') + count('USER_DORMANT')
  if (attn > 0) out.push(sig('USER_ATTENTION', attn, 'users', userAttentionParams(count, overview), false))

  // Kararlı sıralama: ağırlar önce, üretim sırası korunur
  return out.map((s, i) => ({ s, i })).sort((a, b) => (Number(b.s.severe) - Number(a.s.severe)) || (a.i - b.i)).map(({ s }) => s)
}

/** Eskalasyon kapsam boşluğu sayısı — contactsCoverageModel çıktısından; veri eksikse null (bilinmiyor → kutucuk yok). */
export function escalationGapFrom(coverage, contactsOk) {
  if (!contactsOk || !coverage || !coverage.summary || !coverage.summary.statsKnown) return null
  return coverage.summary.gap || 0
}
