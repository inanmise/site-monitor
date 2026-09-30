/**
 * Form doğrulama hataları — ALANIN YANINDA (2026-09-30, kullanıcı: "sağ üstte uyarı çıkıyor ama hangi alan
 * olduğunu göstermiyor; kullanıcı hatayı aramamalı").
 *
 * <p>Kural: her zorunlu alan `data-field="<anahtar>"` taşır (ui/Field `name`, MonitorForm.FormSection `name`).
 * Doğrulama başarısızsa hata metni o alanın altında (FieldError, aria-describedby ile bağlı) görünür, sayfa ilk
 * hatalı alana KAYDIRILIR ve odak oraya taşınır. Tost yok — hata gidince (alan düzenlenince) metin kalkar.
 */

/** Hatalı alan anahtarları (nesne sırasıyla; falsy değerler atılır). */
export function errorKeys(errors) {
  return Object.entries(errors || {}).filter(([, v]) => !!v).map(([k]) => k)
}

/** `{ anahtar: mesaj | false | null }` → yalnız dolu mesajlar; hiç yoksa null. */
export function compactErrors(map) {
  const out = {}
  for (const [k, v] of Object.entries(map || {})) if (v) out[k] = v
  return Object.keys(out).length ? out : null
}

/** Odaklanabilir ilk kontrol — Radix seçicilerde düğme, metin alanlarında input/textarea. */
function firstFocusable(el) {
  if (!el) return null
  return el.querySelector('input:not([type="hidden"]):not([disabled]), textarea:not([disabled]), select:not([disabled]), [role="combobox"]:not([disabled]), button:not([disabled]), [tabindex]:not([tabindex="-1"])')
}

/**
 * İlk hatalı alana kaydır + odakla. `root` verilmezse belge. Kaydırma hareket-azalt tercihine uyar.
 * @returns {string|null} odaklanan alan anahtarı
 */
export function focusFormError(errors, root) {
  const keys = errorKeys(errors)
  if (keys.length === 0) return null
  const scope = root || (typeof document !== 'undefined' ? document : null)
  if (!scope) return null
  for (const key of keys) {
    const el = scope.querySelector(`[data-field="${key}"]`)
    if (!el) continue
    try {
      const reduce = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
      el.scrollIntoView?.({ block: 'center', behavior: reduce ? 'auto' : 'smooth' })
    } catch { /* jsdom */ }
    const target = firstFocusable(el) || el
    try { target.focus?.({ preventScroll: true }) } catch { /* odaklanamaz */ }
    return key
  }
  return keys[0]
}
