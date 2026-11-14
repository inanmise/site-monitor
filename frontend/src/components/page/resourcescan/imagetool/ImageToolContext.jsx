import { createContext, lazy, Suspense, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import { api } from '../../../../api/client'
import { createImageToolStore } from './imageToolStore.js'

// Önce / sonra karşılaştırması ve duyarlı set pencereleri yalnız açılınca iner
const ImageCompareDialog = lazy(() => import('./ImageCompareDialog.jsx'))
const ResponsiveSetDialog = lazy(() => import('./ResponsiveSetDialog.jsx'))

/**
 * Görsel aracı bağlamı (2026-11-14): "Kaynak analizi" sekmesinin altındaki her parça (nesne satırı, nesne ayrıntısı,
 * pano kartları, araç görünümü) aynı DEPOYU ve aynı analiz künyesini buradan okur. Bağlam değeri KARARLIDIR (ilerleme
 * depo konularından okunur) — bir görselin ilerlemesi sekmenin tamamını yeniden çizmez. Bağlam yoksa (parça tek başına
 * çizildiğinde — eski testler) görsel eylemleri hiç çizilmez.
 */
const ImageToolContext = createContext(null)

export const useImageTool = () => useContext(ImageToolContext)

/** Depo konusuna abonelik: `read` DEĞİŞMEZ bir anlık görüntü döner (değişince depo yenisini koyar). */
export function useTopic(store, topic, read) {
  const subscribe = useCallback((cb) => store.on(topic, cb), [store, topic])
  return useSyncExternalStore(subscribe, read, read)
}

/**
 * Sekme başına depo. Sekme sökülünce depo BIRAKILIR (`release`): satır işleri ve araç kuyruğu iptal edilir, bellekteki
 * görseller bırakılır; süren toplu indirme / ZIP ise SÜRER ve ZIP iner (uygulama içi gezinme — sekme değiştirme, Geri,
 * pencereyi kapatma — onu sessizce iptal etmez). Çıkışta (oturum sıfırlama) depo hemen kapanır. (StrictMode'da etki iki
 * kez koşar: ilk temizlik boştaki depoyu kapatır → ikinci koşuda yenisi kurulur.)
 */
export function useImageToolStore(monitorId) {
  const make = useCallback(() => createImageToolStore({
    fetchImage: (scanId, url, signal) => api.monitoring.resourceScanImage(monitorId, scanId, url, { signal }),
  }), [monitorId])
  const [store, setStore] = useState(make)
  useEffect(() => {
    let s = store
    if (s.isDisposed() || s.isReleased()) { s = make(); setStore(s) }
    return () => s.release()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [make])
  return store
}

/**
 * Sağlayıcı: depo + seçili analizin künyesi (`scanId`, `canRun` = "Şimdi kontrol et" kapısı, `itemsKept` = nesne ayrıntısı
 * saklanıyor mu) + araç görünümüne geçiş. Karşılaştırma ve duyarlı set pencereleri burada TEK örnek olarak yaşar.
 * Toplu indirme / ZIP sürerken tarayıcı sekmesi kapatılırsa tarayıcı sorar (`beforeunload` — imageToolBusy kaydı).
 */
export function ImageToolProvider({ store, monitorId, scanId, canRun, itemsKept, monitorName, onOpenTool, children }) {
  const [compare, setCompare] = useState(null)       // { title, original: Blob, result: Blob, summary }
  const [responsive, setResponsive] = useState(null) // { name, base, blob, info, format }
  const openCompare = useCallback((payload) => setCompare(payload || null), [])
  const openResponsive = useCallback((payload) => setResponsive(payload || null), [])
  const value = useMemo(() => ({ store, monitorId, scanId, canRun: canRun === true, itemsKept: itemsKept !== false, monitorName: monitorName || '',
    openTool: onOpenTool, openCompare, openResponsive }),
  [store, monitorId, scanId, canRun, itemsKept, monitorName, onOpenTool, openCompare, openResponsive])


  return (
    <ImageToolContext.Provider value={value}>
      {children}
      {compare && (
        <Suspense fallback={null}>
          <ImageCompareDialog {...compare} onClose={() => setCompare(null)} />
        </Suspense>
      )}
      {responsive && (
        <Suspense fallback={null}>
          <ResponsiveSetDialog {...responsive} store={store} onClose={() => setResponsive(null)} />
        </Suspense>
      )}
    </ImageToolContext.Provider>
  )
}
