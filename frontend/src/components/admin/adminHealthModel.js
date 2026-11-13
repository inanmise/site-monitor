// Yönetim sağlık panosu (2026-11-12, çapraz zenginleştirme #2) — saf, test edilebilir sinyal modeli. Girdi: overview
// yanıtı (counts + warnings) + eskalasyon kapsam boşluğu (frontend'de contactsCoverageModel.buildCoverage ile hesaplanır,
// bilinmiyorsa null). Çıktı: eyleme dönük sağlık sinyalleri — her biri sekme + SÜZGEÇ (g_* paramları) ile derin bağlantı.
// Kullanıcı-etkinlik uyarıları (kilitli/atıl/hiç) TEK "Dikkat isteyen kullanıcılar" kutucuğunda toplanır (şeridi çipe
// boğmadan; 2026-09-27 kararının ruhu) ve Kullanıcılar sekmesine süzgeçli gider; kırılım orada.

export const HEALTH_SEVERE = new Set(['NO_ADMIN', 'SINGLE_ADMIN', 'ESCALATION_GAP', 'TEAM_NO_EMAIL'])

function sig(code, count, tab, params, severe, extra) {
  return { code, count, tab, params: params || {}, severe: !!severe, category: categoryOf(code), ...(extra || {}) }
}

function categoryOf(code) {
  if (code === 'ESCALATION_GAP' || code === 'CONTACT_INACTIVE') return 'contacts'
  if (code === 'TEAM_NO_EMAIL' || code === 'TEAM_NO_LEADER') return 'teams'
  if (code === 'GROUP_NO_EMAILS') return 'groups'
  return 'users'
}

/**
 * Kullanıcı "dikkat isteyen" kutucuğunun derin-bağlantı süzgeci. Sayı ile tıklama hedefi TUTARLI olmalı (2026-11-13 hata
 * taraması: kutucuk kilitli + hiç giriş + atıl TOPLAMINI gösterip tıklayınca yalnız birini süzüyordu — 51 → 1 kilitli).
 * Yalnız TEK tür varsa o süzgeç; birden fazlaysa süzgeçsiz Kullanıcılar sekmesi (orada üç ayrı KPI kartı kırılımı verir) ve
 * kutucuk kırılımı kendisi yazar (`parts`).
 */
function userAttentionParams(count, overview) {
  const kinds = ['USER_LOCKED', 'USER_DORMANT', 'USER_NEVER_LOGGED_IN'].filter((k) => count(k) > 0)
  if (kinds.length !== 1) return {}
  if (kinds[0] === 'USER_LOCKED') return { g_status: 'locked' }
  if (kinds[0] === 'USER_DORMANT') return { g_dormant: String(overview?.dormant_days || 90) }
  return { g_dormant: 'never' }
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
  // Boşluk sayısı: sayı ya da { count, atLeast } (bazı takımların kişi durumu okunamadı → "en az N")
  const gapCount = typeof escalationGap === 'number' ? escalationGap : escalationGap?.count
  if (typeof gapCount === 'number' && gapCount > 0) {
    out.push(sig('ESCALATION_GAP', gapCount, 'contacts', {}, true, escalationGap?.atLeast ? { atLeast: true } : null))
  }
  if (has('TEAM_NO_EMAIL')) out.push(sig('TEAM_NO_EMAIL', count('TEAM_NO_EMAIL'), 'teams', {}, true))

  // ── Uyarı (warn) ──
  if (has('TEAM_NO_LEADER')) out.push(sig('TEAM_NO_LEADER', count('TEAM_NO_LEADER'), 'teams', {}, false))
  if (has('GROUP_NO_EMAILS')) out.push(sig('GROUP_NO_EMAILS', count('GROUP_NO_EMAILS'), 'notifyGroups', {}, false))
  if (has('CONTACT_INACTIVE')) out.push(sig('CONTACT_INACTIVE', count('CONTACT_INACTIVE'), 'contacts', {}, false))

  const attn = count('USER_LOCKED') + count('USER_NEVER_LOGGED_IN') + count('USER_DORMANT')
  if (attn > 0) {
    const parts = [['locked', 'USER_LOCKED'], ['never', 'USER_NEVER_LOGGED_IN'], ['dormant', 'USER_DORMANT']]
      .filter(([, code]) => count(code) > 0).map(([key, code]) => ({ key, count: count(code) }))
    out.push(sig('USER_ATTENTION', attn, 'users', userAttentionParams(count, overview), false, { parts }))
  }

  // Kararlı sıralama: ağırlar önce, üretim sırası korunur
  return out.map((s, i) => ({ s, i })).sort((a, b) => (Number(b.s.severe) - Number(a.s.severe)) || (a.i - b.i)).map(({ s }) => s)
}

/**
 * Eskalasyon kapsam boşluğu sayısı — contactsCoverageModel çıktısından; veri eksikse null (bilinmiyor → kutucuk yok).
 * Bazı takımların kapsamı bilinmiyorsa (bağlı kullanıcının durumu okunamadı, 2026-11-13 hata taraması): kesin boşluk varsa
 * `{ count, atLeast: true }` ("en az N"), yoksa null — "boşluk yok" diye yeşil denmez.
 */
export function escalationGapFrom(coverage, contactsOk) {
  if (!contactsOk || !coverage || !coverage.summary || !coverage.summary.statsKnown) return null
  const gap = coverage.summary.gap || 0
  if ((coverage.summary.unknown || 0) > 0) return gap > 0 ? { count: gap, atLeast: true } : null
  return gap
}
