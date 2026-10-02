import { useEffect, useState } from 'react'
import { api } from '../api/client.js'

/**
 * Çevrimiçi kullanıcı özeti — TEK paylaşılan yoklayıcı (2026-10-02, kullanıcı isteği: "30 sn'de bir tazelensin").
 *
 * <p>Gösterge iki yerde aynı anda yüklü olabilir (masaüstü kenar çubuğu başlığı + CSS ile gizlenen telefon üst
 * çubuğu; telefonda açılan kenar çubuğu çekmecesi). Her biri kendi zamanlayıcısını kursaydı sunucuya 30 sn'de iki-üç
 * istek giderdi. Modül düzeyinde abone sayımı: ilk abone yoklamayı başlatır, son abone ayrılınca durur; tüm
 * aboneler aynı durumu görür. Sekme gizliyken yoklama durur, geri gelince bir kez tazeler (useVisibleInterval ile
 * aynı davranış). Sunucu da sonucu kuruluş geneli 10 sn önbellekte tutar.
 */
export const PRESENCE_REFRESH_MS = 30_000
/** İlk abone geldiğinde veri bundan tazeyse yeniden çekme (sekme/bileşen yeniden bağlanması). */
const FRESH_ENOUGH_MS = 5_000

const subscribers = new Set()
let state = { data: null, error: false, at: 0 }
let timer = null
let inflight = null

function emit() {
  subscribers.forEach((fn) => fn(state))
}

/** Hemen bir kez çeker (aynı anda gelen çağrılar tek isteği paylaşır). */
export function refreshPresence() {
  if (inflight) return inflight
  inflight = (async () => {
    try {
      const res = await api.presence.online()
      state = res?.success && res.data
        ? { data: res.data, error: false, at: Date.now() }
        : { ...state, error: true, at: Date.now() }
    } catch {
      state = { ...state, error: true, at: Date.now() }
    } finally {
      inflight = null
    }
    emit()
  })()
  return inflight
}

function startTimer() {
  if (timer != null || document.hidden) return
  timer = setInterval(refreshPresence, PRESENCE_REFRESH_MS)
}

function stopTimer() {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
}

function onVisibility() {
  if (document.hidden) {
    stopTimer()
  } else {
    refreshPresence()
    startTimer()
  }
}

/** Abone olur; `{ data, error, at }` döner. data: `{ total, teams[{team_id,team_name,count}], no_team, window_seconds, generated_at }`. */
export function usePresence() {
  const [snapshot, setSnapshot] = useState(state)
  useEffect(() => {
    subscribers.add(setSnapshot)
    if (subscribers.size === 1) {
      document.addEventListener('visibilitychange', onVisibility)
      if (Date.now() - state.at > FRESH_ENOUGH_MS) refreshPresence()
      startTimer()
    } else {
      setSnapshot(state)
    }
    return () => {
      subscribers.delete(setSnapshot)
      if (subscribers.size === 0) {
        stopTimer()
        document.removeEventListener('visibilitychange', onVisibility)
      }
    }
  }, [])
  return snapshot
}

/** Yalnız testler: modül durumunu sıfırla. */
export function resetPresenceForTests() {
  stopTimer()
  subscribers.clear()
  document.removeEventListener('visibilitychange', onVisibility)
  state = { data: null, error: false, at: 0 }
  inflight = null
}
