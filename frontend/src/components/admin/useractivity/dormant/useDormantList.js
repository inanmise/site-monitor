import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../../../api/client'

/**
 * Atıl hesaplar TAM listesi (2026-10-09): `GET /api/admin/system/user-activity/dormant` → `{rows (≤ 5000), meta}`.
 * Yoklanan Kullanıcı / Oturum özeti (Sistem Sağlığı'nın her bölümünde 30 sn'de bir) atıl satırları yalnız ilk 500'e kadar
 * taşır; tam liste bu kancayla PENCERE AÇILINCA bir kez ve kullanıcının Yenile / Yeniden dene düğmesiyle istenir.
 *
 * Sonsuz döngü / yığılma kuralı (CLAUDE.md "No unbounded loop"):
 *  • YOKLAMA YOK — zamanlayıcı, aralık ya da yeniden deneme döngüsü yok; her istek bir kullanıcı eyleminin sonucu.
 *  • ÜST ÜSTE BİNMEZ — bir istek yoldayken `reload()` yeni istek AÇMAZ (false döner); düğmeler de o sırada kapalı.
 *  • SIRA KORUMASI — her isteğin sıra numarası var; pencere kapandıktan sonra gelen (bayat) yanıt hiçbir şeyi ezmez.
 *
 * @returns {{ data: {rows: object[], meta: object, generated_at?: string}|null, loading: boolean,
 *             error: string|true|null, reload: () => Promise<boolean> }}
 */
export function useDormantList() {
  const [state, setState] = useState({ data: null, loading: true, error: null })
  const seq = useRef(0)
  const inflight = useRef(false)

  const reload = useCallback(async () => {
    if (inflight.current) return false
    inflight.current = true
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.admin.getDormantAccounts()
      if (my !== seq.current) return false
      if (res?.success && Array.isArray(res.data?.rows)) setState({ data: res.data, loading: false, error: null })
      else setState((s) => ({ ...s, loading: false, error: (typeof res?.error === 'string' && res.error) || true }))
    } catch (e) {
      if (my === seq.current) setState((s) => ({ ...s, loading: false, error: (typeof e?.message === 'string' && e.message) || true }))
    } finally {
      if (my === seq.current) inflight.current = false
    }
    return true
  }, [])

  useEffect(() => {
    reload()
    return () => { seq.current += 1; inflight.current = false }   // kapanış: yoldaki yanıt artık bayat
  }, [reload])

  return { ...state, reload }
}

export default useDormantList
