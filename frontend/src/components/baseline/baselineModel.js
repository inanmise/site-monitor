// Dinamik taban çizgisi (yanıt-süresi anomalisi) — saf yardımcılar (2026-11-12). Sunucu BaselineParams / BaselineConfigService
// sınırlarının aynası; sunucu doğrulaması YETKİLİDİR (kaydetmede alan hataları döner), bu yalnız UX ön kontrolüdür.

export const K_MIN = 1, K_MAX = 10, WINDOW_MIN = 10, WINDOW_MAX = 200, MIN_SAMPLES_MIN = 5
export const DIRECTIONS = ['up', 'down', 'both']
export const SEASONS = ['none', 'daily', 'weekly']

export const BASELINE_DEFAULTS = Object.freeze({
  enabled: false, direction: 'up', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'none',
})

const num = (v, d) => (Number.isFinite(Number(v)) ? Number(v) : d)
const int = (v, d) => (Number.isInteger(Number(v)) ? Number(v) : d)

/** Sunucu görünümünü (view) forma çevirir. */
export function formFromConfig(cfg) {
  if (!cfg) return { ...BASELINE_DEFAULTS }
  return {
    enabled: !!cfg.enabled,
    direction: DIRECTIONS.includes(cfg.direction) ? cfg.direction : 'up',
    k_warning: num(cfg.k_warning, 2.5),
    k_critical: num(cfg.k_critical, 3.5),
    window: int(cfg.window, 30),
    min_samples: int(cfg.min_samples, 10),
    season: SEASONS.includes(cfg.season) ? cfg.season : 'none',
  }
}

/** Form → sunucu gövdesi. */
export function toBody(form) {
  return {
    enabled: !!form.enabled,
    direction: form.direction,
    k_warning: Number(form.k_warning),
    k_critical: Number(form.k_critical),
    window: Number(form.window),
    min_samples: Number(form.min_samples),
    season: form.season,
  }
}

/** İstemci ön doğrulaması — alan → kod ({@code range|order|invalid}). Yalnız AÇIKKEN parametreler denetlenir. */
export function validate(form) {
  const e = {}
  if (!form || !form.enabled) return e
  const kw = Number(form.k_warning), kc = Number(form.k_critical)
  const w = Number(form.window), ms = Number(form.min_samples)
  if (!(kw >= K_MIN && kw <= K_MAX)) e.k_warning = 'range'
  if (!(kc >= K_MIN && kc <= K_MAX)) e.k_critical = 'range'
  else if (Number.isFinite(kw) && kc < kw) e.k_critical = 'order'
  if (!(Number.isInteger(w) && w >= WINDOW_MIN && w <= WINDOW_MAX)) e.window = 'range'
  const hi = Number.isInteger(w) && w >= WINDOW_MIN && w <= WINDOW_MAX ? w : WINDOW_MAX
  if (!(Number.isInteger(ms) && ms >= MIN_SAMPLES_MIN && ms <= hi)) e.min_samples = 'range'
  if (!DIRECTIONS.includes(form.direction)) e.direction = 'invalid'
  if (!SEASONS.includes(form.season)) e.season = 'invalid'
  return e
}

/** Alanın izin verilen aralığı (ipucu metni için). */
export function rangeOf(field, form) {
  switch (field) {
    case 'k_warning':
    case 'k_critical': return [K_MIN, K_MAX]
    case 'window': return [WINDOW_MIN, WINDOW_MAX]
    case 'min_samples': return [MIN_SAMPLES_MIN, Number(form?.window) || WINDOW_MAX]
    default: return null
  }
}

/** Hazırlığı etkileyen parametreler (kayıtlı ile formdaki farklıysa sunucunun hesabı bu forma ait değildir). */
const READINESS_PARAMS = ['window', 'min_samples', 'season']

/**
 * Hazırlık durumu (2026-11-13, hata taraması #8). Kaynak SUNUCU: GET yanıtı {@code global_enabled} (ana anahtar) ve kayıtlı
 * yapılandırmanın GERÇEK değerlendirme penceresindeki örnek sayısını ({@code ready_state} / {@code ready_samples} /
 * {@code ready_min}) taşır — toplam örnek sayısı (mevsimselde eşleşmeyen saatler dâhil) "hazır" demeye yetmez. Bilinmeyen
 * asla yeşil değildir: ana anahtar kapalı → nötr; kayıtlı değil / ayar değişti → bilgi; eski sunucu (alan yok) → toplamdan
 * yalnız "kuruluyor" ya da nötr "doğrulanamıyor".
 */
export function readiness(form, cfg) {
  if (!form || !form.enabled) return { tone: 'muted', state: 'off' }
  if (cfg && cfg.global_enabled === false) return { tone: 'muted', state: 'switchOff' }
  if (!cfg || !cfg.enabled) return { tone: 'info', state: 'notSaved' }
  const saved = formFromConfig(cfg)
  if (READINESS_PARAMS.some((k) => String(form[k]) !== String(saved[k]))) return { tone: 'info', state: 'pending' }
  const min = Number(cfg.ready_min) || Number(saved.min_samples) || 10
  if (typeof cfg.ready_state === 'string') {
    const n = Number(cfg.ready_samples) || 0
    if (cfg.ready_state === 'ready') return { tone: 'ok', state: 'ready', n }
    if (cfg.ready_state === 'insufficient') return { tone: 'muted', state: 'insufficient', n, min }
    if (cfg.ready_state === 'building') return { tone: 'info', state: 'building', n, min }
    return { tone: 'muted', state: 'unknown' }
  }
  // Eski sunucu: yalnız toplam örnek sayısı — "hazır" doğrulanamaz (mevsimsel eşleşme / ana anahtar bilinmiyor)
  const n = Number(cfg.sample_count) || 0
  if (n < min) return { tone: 'info', state: 'building', n, min }
  return { tone: 'muted', state: 'unverified', n }
}

/** Form, kaynak yapılandırmasından farklı mı (kaydet düğmesi). */
export function isDirty(form, cfg) {
  const base = formFromConfig(cfg)
  return JSON.stringify(toBody(form)) !== JSON.stringify(toBody(base))
}
