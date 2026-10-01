import { navigateTo } from '../../../utils/navigate.js'

/**
 * Gürültü analizi — saf model yardımcıları (2026-10-01 yeniden tasarım). Bileşen bunları çizer; test burada kalır.
 *
 * Sunucu ({@code AlertNoiseService}) desen KODU ve öneri KODU üretir; metin ve eylem burada i18n / gezinmeye çevrilir.
 * Öneri kodları ↔ i18n anahtarları sözleşmesi backend'de {@code AlertNoiseSuggestionI18nGateTest} ile pinlidir.
 */

/** Pencere seçenekleri (gün). 7 ve 30 eski; 14 yeni. */
export const NOISE_WINDOWS = [7, 14, 30]

/** Desen → Badge varyantı. Gürültülü desenler amber (warning) / kırmızı (destructive); normal sessiz. */
export const PATTERN_VARIANT = {
  FLAPPING: 'destructive',
  REPEAT_SAME_TARGET: 'destructive',
  SHORT_OUTAGES: 'warning',
  SLOW_THRESHOLD_TIGHT: 'warning',
  NORMAL: 'outline',
}

/** Öneri önem sırası ve rengi (grup başlığı). */
export const SEVERITY_ORDER = ['HIGH', 'MEDIUM', 'INFO']
export const SEVERITY_VARIANT = { HIGH: 'destructive', MEDIUM: 'warning', INFO: 'secondary' }

/** 0–100 skoru banda çevirir: ≥ 60 yüksek, ≥ 30 orta, altı düşük. */
export function scoreBand(score) {
  const s = Number(score) || 0
  if (s >= 60) return 'high'
  if (s >= 30) return 'mid'
  return 'low'
}

/** Skor bandı → MonitorStatsBar tonu. */
export const SCORE_TONE = { high: 'critical', mid: 'warning', low: 'valid' }

/**
 * Takım seçici seçenekleri: "Tüm takımlar", sonra üye olunan takımlar ("Takımlarım" grubu), sonra diğer görülebilir
 * takımlar. Sunucu {@code team_options} (görülebilir aktif takımlar) + {@code my_team_ids} verir. Grup başlıkları
 * yalnız iki grup da doluysa çizilir (tek grup için başlık gereksiz tık/göz yükü).
 */
export function buildTeamOptions(data, t) {
  const opts = [{ value: 'all', label: t('noise.teamAll') }]
  const all = Array.isArray(data?.team_options) ? data.team_options : []
  const mine = new Set((data?.my_team_ids || []).map((id) => String(id)))
  const own = all.filter((o) => mine.has(String(o.id)))
  const others = all.filter((o) => !mine.has(String(o.id)))
  const grouped = own.length > 0 && others.length > 0
  for (const o of own) opts.push({ value: String(o.id), label: o.name, group: grouped ? t('noise.teamMine') : undefined, groupOpen: true })
  for (const o of others) opts.push({ value: String(o.id), label: o.name, group: grouped ? t('noise.teamOthers') : undefined })
  return opts
}

/** Önerileri önem grubuna böler (HIGH → MEDIUM → INFO); boş gruplar atlanır. */
export function groupSuggestions(suggestions) {
  const list = Array.isArray(suggestions) ? suggestions : []
  return SEVERITY_ORDER
    .map((sev) => ({ severity: sev, items: list.filter((s) => s?.severity === sev) }))
    .filter((g) => g.items.length > 0)
}

/**
 * Öneri eylemini uygular. Sunucunun {@code action: {kind, tab, params}} ipucu:
 *  - open_monitor    → izleme türü sekmesi (`tab` = http/ping/…; bilinmiyorsa alerthistory) + `q` araması
 *  - open_alerts     → Alarm Geçmişi süzgeci: aynı sayfadaysak `onPickDomain` (URL/liste güncellenir), yoksa sekme geçişi
 *  - open_settings   → Ayarlar bölümü (`sec: storm | userpush`)
 *  - open_maintenance→ Bakım Pencereleri (+ `q`)
 * Bilinmeyen tür → Alarm Geçmişi. Dönüş: uygulanan {tab, params} (test kolaylığı).
 */
export function runSuggestionAction(suggestion, { onPickDomain } = {}) {
  const a = suggestion?.action || {}
  const params = { ...(a.params || {}) }
  const target = suggestion?.target
  switch (a.kind) {
    case 'open_alerts': {
      if (onPickDomain && target) { onPickDomain(target); return { tab: 'alerthistory', params: { q: target }, local: true } }
      const p = target ? { q: target, ...params } : params
      navigateTo('alerthistory', p)
      return { tab: 'alerthistory', params: p }
    }
    case 'open_settings': {
      navigateTo('settings', params)
      return { tab: 'settings', params }
    }
    case 'open_maintenance': {
      const p = target ? { q: target, ...params } : params
      navigateTo('maintenance', p)
      return { tab: 'maintenance', params: p }
    }
    case 'open_monitor': {
      const tab = a.tab && a.tab !== 'alerthistory' ? a.tab : 'alerthistory'
      const p = target ? { q: target, ...params } : params
      if (tab === 'alerthistory' && onPickDomain && target) { onPickDomain(target); return { tab, params: p, local: true } }
      navigateTo(tab, p)
      return { tab, params: p }
    }
    default: {
      const p = target ? { q: target, ...params } : params
      navigateTo('alerthistory', p)
      return { tab: 'alerthistory', params: p }
    }
  }
}

/** Öneri gövdesinin i18n parametreleri — sunucu dizisi; eksikse boş (metin {0} göstermesin diye '—'). */
export function suggestionParams(s) {
  const p = Array.isArray(s?.params) ? s.params : []
  return p.map((v) => (v == null ? '—' : v))
}

/** Gün × saat ısı haritası ve saat grafiği için gece (22–06) mi? */
export function isNightHour(h) { return h >= 22 || h < 6 }
