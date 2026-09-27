import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Bölüm yükleyicisi — kullanıcı ayrıntısının her ayağı (üyelik, eskalasyon, yetki, push, geçmiş) KENDİ durumunu taşır.
 *
 * <p>Neden: eski pencere tüm istekleri `.catch(() => {})` ile yutuyordu; bir uç düşünce bölüm "—" ya da "Çözümlenemedi"
 * gösteriyor, kullanıcı "veri yok" ile "yüklenemedi"yi ayıramıyordu. Burada hata bölümün içinde, yeniden dene
 * düğmesiyle görünür; diğer bölümler etkilenmez.
 *
 * - `key` değişince (kullanıcı / sayfa) ya da `reload()` çağrılınca yeniden yükler; `enabled=false` → istek YOK.
 * - Yeniden yüklemede önceki veri KORUNUR (liste zıplamaz; sayfa değişirken soluk gösterilir).
 * - Yükleniyor durumu istek anahtarından TÜRETİLİR (efektte eşzamanlı setState yok): yanıtın anahtarı güncel
 *   anahtara eşit değilse bölüm yükleniyordur.
 * - `load` her çizimde yeni olabilir: son hâli ref'ten okunur (efekt yalnız anahtara bağlı).
 * - Yanıt `success` taşımıyorsa `load` Error fırlatır (bkz. `unwrap`); mesaj kullanıcıya gösterilir.
 */
export function useSection(key, load, enabled = true) {
  const loadRef = useRef(load)
  useEffect(() => { loadRef.current = load })
  const [nonce, setNonce] = useState(0)
  const reqKey = `${key}#${nonce}`
  const [res, setRes] = useState({ reqKey: null, status: 'idle', data: null, error: null })

  useEffect(() => {
    if (!enabled) return undefined
    let alive = true
    Promise.resolve()
      .then(() => loadRef.current())
      .then((data) => { if (alive) setRes({ reqKey, status: 'ok', data, error: null }) })
      .catch((e) => { if (alive) setRes((s) => ({ reqKey, status: 'error', data: s.data, error: e?.message || String(e) })) })
    return () => { alive = false }
  }, [reqKey, enabled])

  const reload = useCallback(() => setNonce((n) => n + 1), [])
  const settled = res.reqKey === reqKey
  const status = !enabled ? 'idle' : (settled ? res.status : 'loading')
  return { status, data: enabled ? res.data : null, error: settled ? res.error : null, loading: status === 'loading', reload }
}

/** Sunucu zarfını doğrular: `success` yoksa sunucunun (dil başlığına göre çevrilmiş) hata metniyle fırlatır. */
export function unwrap(res, fallback) {
  if (!res || res.success !== true) throw new Error(res?.error || res?.message || fallback)
  return res
}
