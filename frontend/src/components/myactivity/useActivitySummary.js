import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { EV } from './activityModel.js'

/**
 * Seçili aralığın GÜVENLİK ÖZETİ — sayım kartları, uyarı şeridi, cihaz kartı ve ısı haritası buradan beslenir.
 *
 * <p>Neden ayrı bir çekim: liste sunucu sayfalıdır ve `/api/me/audit`'in özet ucu YOKTUR. Sayfadaki 50 satırdan
 * sayım üretmek yanlış olurdu (ikinci sayfadaki başarısız giriş sayılmazdı). Bunun yerine SINIRLI bir ek çekim:
 *
 *  1. Aralığın en yeni {@link SUMMARY_SAMPLE} olayı tek istekte (uç tavanı 200). Toplam ≤ örneklem ise HER ŞEY
 *     bu satırlardan KESİN hesaplanır — tipik kullanıcı (30 günde birkaç yüz olaydan az) tek istekle kapanır.
 *  2. Toplam örneklemi aşıyorsa güvenlik sayıları (giriş, başarısız giriş, engellenen, parola değişikliği)
 *     tür/sonuç süzgeçli ayrı isteklerin `total` alanından KESİN alınır; satırları uyarı ve cihaz dökümünü besler.
 *     Tür dağılımı (ör. izleme/envanter işlemleri) yalnız örneklemden gelir ve ekranda "son N / M olaya göre"
 *     notuyla gösterilir — sahte kesinlik yok.
 */
export const SUMMARY_SAMPLE = 200
const DETAIL_SAMPLE = 50

async function fetchOwn(params) {
  const r = await api.me.getMyAudit(params)
  if (!r?.success) throw new Error(r?.error || 'my-audit')
  const rows = Array.isArray(r.data) ? r.data : []
  const n = Number(r.total)
  return { rows, total: Number.isFinite(n) ? n : rows.length }
}

/** Örneklemden süzülmüş alt küme — örneklem tamamsa kesin. */
function subset(rows, fn) {
  const out = rows.filter(fn)
  return { rows: out, total: out.length }
}

export async function loadActivitySummary({ since, until }) {
  const base = { since, until }
  const all = await fetchOwn({ ...base, page: 0, size: SUMMARY_SAMPLE })
  const complete = all.rows.length >= all.total
  if (complete) {
    return {
      all, complete,
      signIns: subset(all.rows, (r) => r.event_type === EV.SIGN_IN),
      failed: subset(all.rows, (r) => r.event_type === EV.SIGN_IN_FAILED),
      blocked: subset(all.rows, (r) => r.outcome === 'BLOCKED'),
      password: subset(all.rows, (r) => r.event_type === EV.PASSWORD),
    }
  }
  const [signIns, failed, blocked, password] = await Promise.all([
    fetchOwn({ ...base, eventType: EV.SIGN_IN, page: 0, size: SUMMARY_SAMPLE }),
    fetchOwn({ ...base, eventType: EV.SIGN_IN_FAILED, page: 0, size: DETAIL_SAMPLE }),
    fetchOwn({ ...base, outcome: 'BLOCKED', page: 0, size: DETAIL_SAMPLE }),
    fetchOwn({ ...base, eventType: EV.PASSWORD, page: 0, size: 1 }),
  ])
  return { all, complete, signIns, failed, blocked, password }
}

/**
 * @param range  { since, until } — UTC ISO
 * @param reloadKey  değişince yeniden çeker (Yenile düğmesi)
 * @returns { data, loading, error }
 */
export function useActivitySummary(range, reloadKey = 0) {
  const [state, setState] = useState({ data: null, loading: true, error: false })
  const { since, until } = range
  useEffect(() => {
    let alive = true
    setState((s) => ({ ...s, loading: true, error: false }))
    loadActivitySummary({ since, until })
      .then((data) => { if (alive) setState({ data, loading: false, error: false }) })
      .catch(() => { if (alive) setState((s) => ({ ...s, loading: false, error: true })) })
    return () => { alive = false }
  }, [since, until, reloadKey])
  return state
}
