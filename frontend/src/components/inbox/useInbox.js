import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { STORE, STORE_DISMISSED, readSet, writeSet } from './inboxModel.js'

/**
 * Bildirim kutusu durumu (v3, 2026-09-26): güncel liste (60 sn'de bir, sekme görünürken), okundu / temizlendi
 * kümeleri (localStorage, kullanıcıya göre), geçmiş sekmesi (sunucu sayfalı, standart kanca + compact çubuk).
 *
 * - `items === null` → ilk yükleme sürüyor (iskelet); `error` yalnız hiç veri yokken yüzeye çıkar (zil süs,
 *   60 sn'lik yoklama geçici hatayı kendiliğinden kapatır).
 * - `tick`: panel açıkken dakikada bir yeniden çizim (canlı "3 sa 12 dk açık" / göreli zaman).
 */
export function useInbox(username, { open, view }) {
  const [items, setItems] = useState(null)
  const [error, setError] = useState(false)
  const [seen, setSeen] = useState(() => readSet(STORE(username)))
  const [dismissed, setDismissed] = useState(() => readSet(STORE_DISMISSED(username)))
  const [showDismissed, setShowDismissed] = useState(false)
  const [hist, setHist] = useState(null)               // { data, total, page, total_pages }
  const [histLoading, setHistLoading] = useState(false)
  const [histError, setHistError] = useState(false)
  const [histNonce, setHistNonce] = useState(0)        // "Yeniden dene" tetiği
  const [tick, setTick] = useState(0)
  const histPager = useServerPagination({ listKey: 'inbox-history', preset: 'modal', apiBase: 0 })
  const { apiPage: histApiPage, pageSize: histSize } = histPager

  const load = useCallback(async () => {
    try {
      const r = await api.me.inbox()
      if (r?.success && Array.isArray(r.data)) { setItems(r.data); setError(false) }
      else setError(true)
    } catch { setError(true) }
  }, [])
  useVisibleInterval(load, 60_000, true)
  useVisibleInterval(() => setTick((x) => x + 1), open ? 60_000 : 0)

  // Geçmiş: açılınca / sayfa değişince / yeniden dene.
  useEffect(() => {
    if (!open || view !== 'history') return undefined
    let alive = true
    setHistLoading(true)
    setHistError(false)
    Promise.resolve(api.me.inboxHistory?.(histApiPage, histSize))
      .then((r) => {
        if (!alive) return
        if (r?.success) { setHist(r); histPager.bind(r) } else setHistError(true)
      })
      .catch(() => { if (alive) setHistError(true) })
      .finally(() => { if (alive) setHistLoading(false) })
    return () => { alive = false }
  }, [open, view, histApiPage, histSize, histNonce])   // eslint-disable-line react-hooks/exhaustive-deps

  const current = useMemo(() => items || [], [items])
  const active = useMemo(() => current.filter((i) => !dismissed.has(i.key)), [current, dismissed])
  const unreadItems = useMemo(() => active.filter((i) => !seen.has(i.key)), [active, seen])
  const allItems = showDismissed ? current : active
  const dismissedCount = current.length - active.length

  const persistSeen = (next) => { setSeen(next); writeSet(STORE(username), next) }
  const persistDismissed = (next) => { setDismissed(next); writeSet(STORE_DISMISSED(username), next) }

  const markRead = (keys) => { const next = new Set(seen); keys.forEach((k) => next.add(k)); persistSeen(next) }
  const markAll = () => markRead(current.map((i) => i.key))
  const dismiss = (keys) => {
    const next = new Set(dismissed); keys.forEach((k) => next.add(k)); persistDismissed(next)
    markRead(keys)
  }
  const clearAll = () => dismiss(active.map((i) => i.key))

  return {
    items, loading: items === null && !error, error, retry: load,
    seen, dismissed, showDismissed, setShowDismissed,
    active, unreadItems, allItems, unreadCount: unreadItems.length, dismissedCount,
    markRead, markAll, dismiss, clearAll,
    hist, histItems: hist?.data || [], histLoading, histError, histPager, retryHistory: () => setHistNonce((n) => n + 1),
    tick,
  }
}

export default useInbox
