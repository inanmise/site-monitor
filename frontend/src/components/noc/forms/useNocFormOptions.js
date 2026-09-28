import { useEffect, useState } from 'react'
import { api } from '../../../api/client'
import { unwrap } from '../nocModel.js'
import { NOC_COVERAGE_EVENT } from '../../../utils/nocCoverageEvent.js'

/**
 * İzleme formunun 7/24 alanı için seçenekler (2026-09-27) — TEK istek:
 * `GET /api/noc/groups/options` → `{ groups: [{ id, name, is_default, active }], disabled_types: ['PING', …] }`
 * (e-posta YOK; `monitoring.read` olan herkes okur — sözleşme "Backend sapmaları"). Pasif gruplar da gelir (kayıtlı
 * seçimi göstermek için); seçici yalnız aktifleri önerir.
 *
 * Önbellek (modül düzeyi, 60 sn): form sık açılıp kapanır ve dokuz sayfa + envanter + dışa aktarımlar aynı seçenekleri
 * ister — dakikada en fazla bir istek. BAŞARISIZ yanıt önbelleğe girmez (sonraki açılış yeniden dener) ve alan
 * "bilinmiyor" (null) kalır: "aktif grup yok" / "tür kapalı" gibi bir iddia yalnız GERÇEK yanıttan çıkar.
 */
const TTL_MS = 60_000
let cache = null   // { at, promise }

/** Testler için: önbelleği sıfırla. */
export function resetNocFormOptionsCache() { cache = null }
// 7/24 yazması (Ayarlar'da grup ekle/düzenle/sil, yapılandırma — utils/nocCoverageEvent) → sonraki form açılışı 60 sn
// beklemeden taze grupları / kapalı türleri görür.
try { window.addEventListener(NOC_COVERAGE_EVENT, resetNocFormOptionsCache) } catch { /* window yok */ }

async function fetchOptions() {
  const r = unwrap(await api.noc.groupOptions())
  if (!r.ok || r.data == null) return null
  const d = r.data
  // Güvenlik ağı: zarfın içi düz dizi gelirse (sözleşmenin ilk taslağı) yalnız gruplar bilinir.
  if (Array.isArray(d)) return { groups: d, disabledTypes: null, hasActiveGroup: null, minLevel: null }
  return {
    groups: Array.isArray(d.groups) ? d.groups : null,
    disabledTypes: Array.isArray(d.disabled_types) ? d.disabled_types : null,
    // Kart göstergesi (2026-09-28, noc/useNocState): sunucunun kapsam yüklemiyle AYNI "kullanılabilir grup var mı"
    // hükmü ve en düşük seviye. Eski sunucu göndermezse null — iddia üretilmez.
    hasActiveGroup: typeof d.has_active_group === 'boolean' ? d.has_active_group : null,
    minLevel: typeof d.min_level === 'string' && d.min_level ? d.min_level : null,
  }
}

/**
 * Önbellekli seçenekler — `{ groups, disabledTypes }` ya da (hata) `null`. React dışı çağıranlar (CSV dışa aktarımı:
 * grup kimliği → ad) da kullanır.
 */
export function loadNocFormOptions() {
  if (cache && Date.now() - cache.at < TTL_MS) return cache.promise
  const entry = { at: Date.now(), promise: null }
  entry.promise = Promise.resolve()
    .then(fetchOptions)
    .catch(() => null)
    .then((value) => {
      if (value == null && cache === entry) cache = null
      return value
    })
  cache = entry
  return entry.promise
}

/** Kimlik → ad eşlemi (dışa aktarım için). Seçenekler yüklenemezse boş eşlem. */
export async function loadNocGroupNames() {
  const opts = await loadNocFormOptions()
  return Object.fromEntries((opts?.groups || []).map((g) => [Number(g.id), g.name]))
}

/**
 * @param {boolean} enabled false → istek atılmaz (ör. form kapalı)
 * @returns {{ loading: boolean, groups: Array|null, disabledTypes: string[]|null }}
 */
export function useNocFormOptions(enabled = true) {
  const [state, setState] = useState({ loading: true, groups: null, disabledTypes: null })
  useEffect(() => {
    if (!enabled) return undefined
    let alive = true
    loadNocFormOptions().then((opts) => {
      if (alive) setState({ loading: false, groups: opts?.groups ?? null, disabledTypes: opts?.disabledTypes ?? null })
    })
    return () => { alive = false }
  }, [enabled])
  return state
}
