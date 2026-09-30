import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { useVisibleInterval } from './useVisibleInterval.js'

/** Yoklama aralığı — sekme gizliyken durur (useVisibleInterval); alarm eylemleri `sm:alerts-changed` ile anında tazeler. */
export const OPEN_ALERTS_POLL_MS = 60_000
/** Alarm sahiplen/çöz/toplu işlem sonrası menü rozetleri hemen tazelensin diye yayılan olay. */
export const ALERTS_CHANGED_EVENT = 'sm:alerts-changed'

const EMPTY = Object.freeze({ tabs: {}, total: 0, sampled: false, visible: false })

/**
 * İzleme menüsü rozetleri (2026-09-30): görüş kapsamındaki AÇIK alarmların izleme türü başına özeti
 * (`GET /api/me/open-alerts`). Dakikada bir yoklar; ağ hatasında son bilinen veri korunur (rozet titremez).
 *
 * @returns {{ byTab: object, total: number, sampled: boolean, visible: boolean, loaded: boolean, refresh: Function }}
 */
export function useOpenAlerts({ enabled = true, pollMs = OPEN_ALERTS_POLL_MS } = {}) {
  const [data, setData] = useState(EMPTY)
  const [loaded, setLoaded] = useState(false)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const refresh = useCallback(async () => {
    if (!enabled) return
    try {
      const res = await api.me.openAlerts()
      if (!alive.current) return
      if (res?.success && res.data) setData({ ...EMPTY, ...res.data, tabs: res.data.tabs || {} })
    } catch { /* son bilinen veri kalır */ } finally {
      if (alive.current) setLoaded(true)
    }
  }, [enabled])

  useVisibleInterval(refresh, enabled ? pollMs : 0)
  useEffect(() => {
    if (!enabled) return undefined
    const onChanged = () => { refresh() }
    window.addEventListener(ALERTS_CHANGED_EVENT, onChanged)
    return () => window.removeEventListener(ALERTS_CHANGED_EVENT, onChanged)
  }, [enabled, refresh])

  return { byTab: data.tabs || {}, total: Number(data.total || 0), sampled: !!data.sampled, visible: !!data.visible, loaded, refresh }
}

/** Alarm durumu değişti — menü rozetlerini tazele (sahiplen / çöz / toplu işlem çağırır). */
export function announceAlertsChanged() {
  try { window.dispatchEvent(new CustomEvent(ALERTS_CHANGED_EVENT)) } catch { /* jsdom eksikliği — sessiz */ }
}
