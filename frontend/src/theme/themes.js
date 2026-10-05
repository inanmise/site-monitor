/**
 * Arayüz temaları (2026-10-05, kullanıcı isteği) — ön yüzün TEK kaynağı.
 *
 * Sunucu karşılığı `backend/…/service/ThemeCatalog.java` (aynı kimlik + şema + SIRA; `ThemeCatalogSyncTest` ve
 * `src/test/theme-catalog-sync.test.js` ikisini karşılaştırır). Renkler burada DEĞİL, CSS'te:
 *   • temel şemalar `styles/globals.css` + `App.css` (`:root, [data-scheme="light"]` / `[data-scheme="dark"]`),
 *   • ek temalar `styles/themes.css` (`[data-theme="<id>"]`, şema bloklarından SONRA — yalnız jetonları ezer).
 * `<html data-theme="<id>" data-scheme="light|dark">`: koyu şemalı her tema mevcut koyu stillerin TAMAMINI miras alır
 * (`dark:` yardımcıları ve App.css koyu kuralları `data-scheme`'e bağlıdır), üstüne yalnız kendi jetonlarını koyar.
 *
 * Etiketler i18n'den: `theme.name.<id>`, `theme.desc.<id>` (TR + EN).
 * Not: girdiler düz kalmalı (iç dizi YOK) — sunucu kapısı diziyi ilk `]`'a kadar okur.
 */
export const THEMES = Object.freeze([
  { id: 'light', scheme: 'light', base: true },
  { id: 'dark', scheme: 'dark', base: true },
  { id: 'blueprint', scheme: 'dark', base: false },
  { id: 'parchment', scheme: 'light', base: false },
  { id: 'alloy', scheme: 'light', base: false },
  { id: 'obsidian', scheme: 'dark', base: false },
  { id: 'slag', scheme: 'dark', base: false },
  { id: 'crucible', scheme: 'dark', base: false },
])

export const THEME_IDS = Object.freeze(THEMES.map((t) => t.id))

/** Varsayılan değer: işletim sisteminin açık/koyu tercihine göre `light` / `dark`. */
export const SYSTEM = 'system'

/** Yönetici politikası gelmeden (ilk açılış, ağ hatası) geçerli olan: sekizi açık, varsayılan sistem. */
export const DEFAULT_POLICY = Object.freeze({ enabled: THEME_IDS, default: SYSTEM })

export function isKnownTheme(id) {
  return typeof id === 'string' && THEME_IDS.includes(id)
}

/** Temanın renk şeması (`light` | `dark`); bilinmeyen kimlik → `light` (güvenli, okunur varsayılan). */
export function schemeOf(id) {
  return THEMES.find((t) => t.id === id)?.scheme ?? 'light'
}

export function themeMeta(id) {
  return THEMES.find((t) => t.id === id) ?? null
}

/** Bilinen kimlikleri katalog sırasına dizer (tekrarsız). */
export function canonicalIds(ids) {
  const set = new Set(Array.isArray(ids) ? ids : [])
  return THEME_IDS.filter((id) => set.has(id))
}

/**
 * Sunucudan (ya da önbellekten) gelen politikayı HOŞGÖRÜLÜ biçimde düzeltir — sunucu `ThemeCatalog.effective` ile aynı
 * kural: bilinmeyen kimlik atılır, liste boşsa sekizi de açık; varsayılan geçersizse `system` (Açık + Koyu açıksa) ya
 * da listedeki ilk tema.
 */
export function sanitizePolicy(policy) {
  let enabled = canonicalIds(policy?.enabled)
  if (enabled.length === 0) enabled = [...THEME_IDS]
  const systemOk = enabled.includes('light') && enabled.includes('dark')
  const d = typeof policy?.default === 'string' ? policy.default.trim() : SYSTEM
  let def
  if ((d === SYSTEM || d === '') && systemOk) def = SYSTEM
  else if (isKnownTheme(d) && enabled.includes(d)) def = d
  else def = systemOk ? SYSTEM : enabled[0]
  return { enabled, default: def }
}

/** Politikanın varsayılan teması (somut kimlik): `system` → işletim sistemi tercihine göre Açık/Koyu. */
export function defaultThemeOf(policy, prefersDark) {
  const p = sanitizePolicy(policy)
  if (p.default === SYSTEM) return prefersDark ? 'dark' : 'light'
  return p.default
}

/**
 * Etkin tema: kullanıcının seçimi listede AÇIKSA o; değilse (yönetici kaldırdı, bilinmeyen değer, seçim yok) yöneticinin
 * varsayılanı. Kullanıcının saklı seçimi SİLİNMEZ — tema yeniden açılırsa kendiliğinden geri gelir.
 */
export function resolveTheme(choice, policy, prefersDark) {
  const p = sanitizePolicy(policy)
  if (isKnownTheme(choice) && p.enabled.includes(choice)) return choice
  return defaultThemeOf(p, prefersDark)
}

/**
 * Hızlı açık↔koyu geçişi (Komut Paleti "Koyu Mod / Açık Mod", eski `toggle`): etkin temanın şema karşılığı — önce temel
 * tema (Açık / Koyu), kapalıysa karşı şemadaki ilk açık tema; hiçbiri yoksa `null` (geçiş yapılmaz).
 */
export function counterpartTheme(current, enabled) {
  const target = schemeOf(current) === 'dark' ? 'light' : 'dark'
  const list = canonicalIds(enabled)
  if (list.includes(target)) return target
  return list.find((id) => schemeOf(id) === target) ?? null
}

/** İki politika aynı mı (gereksiz yeniden çizim / yazma olmasın). */
export function samePolicy(a, b) {
  if (!a || !b) return false
  return a.default === b.default && a.enabled.length === b.enabled.length && a.enabled.every((id, i) => id === b.enabled[i])
}
