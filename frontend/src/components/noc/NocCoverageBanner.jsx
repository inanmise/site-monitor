import { useEffect, useState } from 'react'
import { Headset, MoonStar, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { uncoveredActiveCount, unwrap } from './nocModel.js'
import { NOC_COVERAGE_EVENT } from '../../utils/nocCoverageEvent.js'

/** Oturum boyunca gizleme anahtarı (sessionStorage — kişisel kolaylık; okunamazsa şerit yine çalışır). */
export const DISMISS_KEY = 'sm.noc.bannerDismissed'

/**
 * Pano önbelleği: aynı `refreshKey` (Pano'nun son güncelleme damgası) için ikinci istek YOK — sekmeler arası gidip
 * gelmek kapsamı yeniden çekmez; Pano 5 dk'da bir tazelendiğinde (yeni damga) bir kez tazelenir. Kendi yoklaması YOK.
 */
const cache = { key: undefined, data: null, at: 0, pending: null }
/** Testler için: önbelleği sıfırla (uçuştaki istek de bırakılır — kapsam değiştiyse eski yanıt benimsenmez). */
export function resetNocBannerCache() { cache.key = undefined; cache.data = null; cache.at = 0; cache.pending = null }
/** Girişte Pano damgası henüz yokken (null) çekilen veri, damga ilk geldiğinde tekrar çekilmez (çift istek olmasın). */
const ADOPT_MS = 60_000
// Kapsam değişti (aç/kapa, toplu, izleme formu, 7/24 ayarları — utils/nocCoverageEvent): şerit o an Pano'da DEĞİLKEN de
// önbellek düşer; Pano'ya dönüşte bayat sayı (5 dk'ya dek) gösterilmez. Açık şerit ayrıca kendi dinleyicisiyle tazelenir.
try { window.addEventListener(NOC_COVERAGE_EVENT, resetNocBannerCache) } catch { /* window yok */ }

function readDismissed() {
  try { return sessionStorage.getItem(DISMISS_KEY) === '1' } catch { return false }
}

/**
 * Genel Bakış'ta sakin uyarı şeridi (2026-09-27): "N izleme gece kesintisinde 7/24 izleme ekibine bildirilmiyor"
 * + "İncele" → 7/24 Kapsamı sekmesi. Kullanıcının görüş kapsamındaki AKTİF ama kapsanmayan izlemeler sayılır.
 * Hiç aktif grup yoksa: global yöneticiye "grup tanımla" ipucu (Ayarlar → 7/24), diğerlerine HİÇBİR ŞEY (yapabilecekleri
 * bir şey yok — sayfada "yöneticinize sorun" notu var). Oturum boyunca kapatılabilir; kapalıyken istek de atılmaz.
 * Test kancası: `data-slot="noc-banner"` + `data-kind="uncovered|no-groups"`.
 */
export default function NocCoverageBanner({ globalAdmin = false, refreshKey = null }) {
  const t = useT()
  const [dismissed, setDismissed] = useState(readDismissed)
  const [data, setData] = useState(() => (cache.key === refreshKey ? cache.data : null))
  const [changed, setChanged] = useState(0)   // kapsam değişti olayı → yeniden çek (önbellek modül dinleyicisinde düştü)
  useEffect(() => {
    const on = () => setChanged((n) => n + 1)
    window.addEventListener(NOC_COVERAGE_EVENT, on)
    return () => window.removeEventListener(NOC_COVERAGE_EVENT, on)
  }, [])

  useEffect(() => {
    if (dismissed) return undefined
    if (cache.data && cache.key === null && refreshKey != null && Date.now() - cache.at < ADOPT_MS) cache.key = refreshKey
    if (cache.key === refreshKey && cache.data) { setData(cache.data); return undefined }
    let alive = true
    // Uçuştaki istek paylaşılır (2026-10-09): girişte damga yokken (null) başlayan istek, damga gelince İKİNCİ kez
    // atılmaz — aynı yanıt yeni damgayla önbelleğe yazılır. Yalnız özet istenir (satır listesi şeride gerekmez).
    let req = cache.pending
    if (req && (req.key === refreshKey || req.key === null)) req.key = refreshKey
    else {
      req = { key: refreshKey, promise: null }
      const mine = req
      mine.promise = Promise.resolve(api.noc.coverage({ summary: true }))
        .then((res) => {
          const r = unwrap(res)
          if (cache.pending === mine) {
            cache.pending = null
            if (r.ok && r.data) { cache.key = mine.key; cache.data = r.data; cache.at = Date.now() }
          }
          return r
        })
        .catch(() => { if (cache.pending === mine) cache.pending = null; return null })
      cache.pending = mine
    }
    req.promise.then((r) => {
      if (alive && r?.ok && r.data) setData(r.data)
    }).catch(() => { /* şerit en iyi-çaba: hata sessiz, Pano etkilenmez */ })
    return () => { alive = false }
  }, [refreshKey, dismissed, changed])

  if (dismissed || !data) return null

  const dismiss = () => {
    setDismissed(true)
    try { sessionStorage.setItem(DISMISS_KEY, '1') } catch { /* depolama yok: yalnız bu görünümde gizli */ }
  }
  const closeBtn = (
    <Button type="button" variant="ghost" size="icon" className="size-10 text-inherit opacity-80 hover:opacity-100 sm:size-8 sm:pointer-coarse:size-10"
      aria-label={t('noc.bannerDismiss')} title={t('noc.bannerDismiss')} onClick={dismiss}>
      <X aria-hidden="true" />
    </Button>
  )

  const activeGroups = Number(data?.summary?.active_groups ?? 1)
  if (!(activeGroups > 0)) {
    if (!globalAdmin) return null
    return (
      <div data-slot="noc-banner" data-kind="no-groups" className="mb-4">
        <AlertBanner tone="info" icon={Headset} className="mb-0" title={t('noc.bannerNoGroupsTitle')} actions={closeBtn}>
          <span className="block">{t('noc.bannerNoGroupsBody')}</span>
          <Button type="button" variant="outline" size="sm" className="mt-2 h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={() => navigateTo('settings', { sec: 'noc' })}>
            {t('noc.bannerNoGroupsCta')}
          </Button>
        </AlertBanner>
      </div>
    )
  }

  const n = uncoveredActiveCount(data)
  if (n <= 0) return null
  return (
    <div data-slot="noc-banner" data-kind="uncovered" className="mb-4">
      <AlertBanner tone="warning" icon={MoonStar} className="mb-0" title={n === 1 ? t('noc.bannerTitle1') : t('noc.bannerTitle', n)}
        actions={closeBtn}>
        <span className="block">{t('noc.bannerBody')}</span>
        <Button type="button" variant="outline" size="sm" className="mt-2 h-10 sm:h-8 sm:pointer-coarse:h-10" data-action="noc-banner-review" onClick={() => navigateTo('noc')}>
          {t('noc.bannerReview')}
        </Button>
      </AlertBanner>
    </div>
  )
}
