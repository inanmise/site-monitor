import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { requestParams, isLiveRange, LIVE_INTERVAL_MS } from './responseChartModel.js'

/** Tür → seri ucu (api.monitoring.*). Sertifika domain-anahtarlıdır; diğerleri izleme kimliği. */
const FETCHERS = {
  ping: 'getPingResponseSeries', keyword: 'getKeywordResponseSeries', port: 'getPortResponseSeries',
  dns: 'getDnsResponseSeries', http: 'getHttpResponseSeries', page: 'getPageResponseSeries',
  scripted: 'getScriptedResponseSeries', pagespeed: 'getPageSpeedSeries', ssl: 'getSslResponseSeries',
}

/**
 * Seri yükleyici — yarış korumalı, "eskiyi göster, yeniyi bekle" (stale-while-loading).
 *
 * <p>Sözleşme:
 * - Her istek bir tur numarası alır (`seqRef`); yalnız EN SON turun yanıtı yazılır (24s → 7g → 30g hızla
 *   tıklanınca yavaş dönen eski yanıt yeniyi ezmesin). Yükleme bayrağını da yalnız son tur indirir — ara
 *   turun `finally`'si onu erken söndürmez; son tur her yolda (başarı / hata / istisna) `finally`'ye ulaşır.
 * - Aralık/metrik değişince önceki görünüm (`view`) DURUR, çağıran onu soluk çizer (`stale` — yeni tur
 *   sürerken de, başarısız olduysa da); hedef
 *   (tür + kimlik) değişince eski izlemenin verisi HİÇ gösterilmez (`view` null döner → iskelet).
 * - Aynı parametrelerle tazeleme (elle / canlı) görünümü soldurmaz (`refreshing`).
 * - Hata: `success:false` zarfı da istisna da hata sayılır; önceki görünüm korunur.
 * - Canlı: ≤ 24 sa hazır aralıkta dakikada bir, sekme görünürken (useVisibleInterval).
 *
 * @param opts `{ monitorId, kind, metric, preset, custom, extra }` — `extra` (birim, eşik) başarılı yanıtla
 *   birlikte saklanır; eski görünüm soluk çizilirken KENDİ biriminde/eşiğinde çizilsin (metrik değişiminde
 *   yeni birimle eski sayıları etiketlemek yanlış okuturdu).
 */
export function useResponseSeries({ monitorId, kind, metric, preset, custom, extra }) {
  const targetKey = `${kind}|${monitorId}`
  const paramsKey = `${targetKey}|${metric ?? ''}|${custom ? `${custom.from}~${custom.to}` : preset}`
  const [view, setView] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const seqRef = useRef(0)
  // Yaşam bayrağı KURULUMDA da true'ya çekilir: StrictMode kur→temizle→kur sırasında kalıcı "ölü" kalmasın.
  const aliveRef = useRef(true)
  useEffect(() => {
    aliveRef.current = true
    return () => { aliveRef.current = false }
  }, [])

  const argsRef = useRef(null)
  argsRef.current = { monitorId, kind, metric, preset, custom, extra, targetKey, paramsKey }

  const load = useCallback(async () => {
    const a = argsRef.current
    const seq = ++seqRef.current
    setLoading(true)
    try {
      const fetcher = api.monitoring[FETCHERS[a.kind]] ?? api.monitoring.getKeywordResponseSeries
      const res = await fetcher(a.monitorId, requestParams(a))
      if (seq !== seqRef.current || !aliveRef.current) return
      if (res?.success) {
        setView({ targetKey: a.targetKey, paramsKey: a.paramsKey, rangeKey: a.custom ? 'custom' : a.preset,
          metric: a.metric, extra: a.extra, data: res.data, at: Date.now() })
        setError(null)
      } else {
        setError({ message: res?.error || res?.message || null })
      }
    } catch (e) {
      if (seq === seqRef.current && aliveRef.current) setError({ message: e?.message || null })
    } finally {
      if (seq === seqRef.current && aliveRef.current) setLoading(false)
    }
  }, [])

  // paramsKey değişince (hedef / aralık / metrik) yeni tur; load kimliği sabit.
  useEffect(() => { load() }, [paramsKey, load])

  useVisibleInterval(load, isLiveRange({ preset, custom }) ? LIVE_INTERVAL_MS : 0, false)

  const shown = view && view.targetKey === targetKey ? view : null
  // Görünüm seçili aralığa/metriğe AİT DEĞİL: yeni tur sürüyor ya da başarısız oldu. İkisinde de eski veri
  // soluk kalır — başarısız aralık değişiminden sonra 24 saatlik veriyi "7 gün" seçiliyken tam renkli
  // göstermek yanlış okuturdu.
  const stale = !!shown && shown.paramsKey !== paramsKey
  return {
    view: shown,
    loading,
    error: loading ? null : error,
    stale,
    refreshing: loading && !!shown && !stale,
    reload: load,
  }
}
