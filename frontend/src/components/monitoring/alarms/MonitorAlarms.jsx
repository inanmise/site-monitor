import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { LayoutGrid, Siren } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { readUrlParam, useUrlQuerySync } from '../../../hooks/useUrlQuerySync.js'
import { usePermissions } from '../../../contexts/PermissionsProvider.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { ALARM_VIEW_KEY, alarmTabFromUrl, inPageTarget } from './monitorAlarmsModel.js'

/** Ağır görünüm (Alarm Geçmişi listesi) yalnız Alarmlar görünümü açılınca indirilir. */
const MonitorAlarmsView = lazy(() => import('./MonitorAlarmsView.jsx'))

/**
 * Alarmlar görünümü sunulur mu? `alerts.read` izni YOKSA hayır (liste 403, özet okunamaz). Yalnız izin anlık görüntüsü
 * OKUNMUŞ ve kaynağı TAŞIYORSA karar verilir — sağlayıcı yok / eski sunucu / henüz okunmadı → sunucu karar verir (sunulur).
 */
export function alarmsAllowed(perms, loaded) {
  const r = perms?.['alerts.read']
  return !(loaded && r && !r.view)
}

/**
 * İzin sağlayıcısının ALTINDA çizilip kararı yukarı bildiren sonda (2026-11-19, yayın sonrası düzeltme): Sertifika Panosu'nun
 * görünüm durumu App gövdesinde yaşar ve App sağlayıcıyı kendisi çizer — App gövdesindeki `usePermissions()` sağlayıcısız
 * varsayılanı (her şey serbest) okur, izin kapısı panoda hiç işlemiyordu.
 */
export function AlarmsPermissionProbe({ onChange }) {
  const { perms, loaded } = usePermissions()
  const ok = alarmsAllowed(perms, loaded)
  useEffect(() => { onChange?.(ok) }, [ok, onChange])
  return null
}

/** App ile aynı olay (aynı sekmede yeni parametre — `useMonitorDeepLink` dinler); App'i içe aktarmamak için ad burada. */
const TAB_PARAMS_EVENT = 'sm:tab-params'

/**
 * İzleme sayfasının "İzlemeler | Alarmlar" görünüm durumu (2026-11-19, kullanıcı isteği). URL anahtarı `mal`
 * (`open | closed | all` = Alarmlar görünümü o sekmeyle; yok = İzlemeler). Sayfa hook'u bir kez çağırır, döneni başlıktaki
 * anahtara ({@link MonitorViewSwitch}) ve gövde sarmalayıcısına ({@link MonitorPageBody}) verir.
 *
 * @param {string} src   sayfa kategorisi (`http`, `uptime`, `cert` …) — alarm tipleri ve Alarm Geçmişi bağlantısı buradan
 * @param {{ onSearch?: (q: string) => void, enabled?: boolean, allowed?: boolean }} [opts]  kimliksiz satırda sayfa içi arama
 *        (Erişilebilirlik); `enabled: false` → URL'e yazmaz (App'te yaşayan Sertifika Panosu, başka sekme açıkken o sekmenin
 *        `mal`'ını ezmesin); `allowed` → izin kararı dışarıdan ({@link AlarmsPermissionProbe} — sağlayıcının üstünde yaşayan
 *        App), verilmezse sayfanın kendi `usePermissions()`'ı
 */
export function useMonitorAlarmsView(src, { onSearch, enabled = true, allowed: allowedOpt } = {}) {
  const [tab, setTab] = useState(() => alarmTabFromUrl(readUrlParam(ALARM_VIEW_KEY, null)))
  const { perms, loaded } = usePermissions()
  const allowed = typeof allowedOpt === 'boolean' ? allowedOpt : alarmsAllowed(perms, loaded)
  useUrlQuerySync({ [ALARM_VIEW_KEY]: allowed ? tab : null }, { enabled })
  const searchRef = useRef(onSearch)
  searchRef.current = onSearch

  const open = useCallback(() => setTab((cur) => cur || 'open'), [])
  const close = useCallback(() => setTab(null), [])
  /** URL'deki görünümü yeniden okur — App'te yaşayan pano (geri tuşuyla dönüşte `mal` korunur). */
  const syncFromUrl = useCallback(() => setTab(alarmTabFromUrl(readUrlParam(ALARM_VIEW_KEY, null))), [])

  // Aynı sekmede izleme açma isteği (zil, komut paleti, "Bugün" paneli → `navigateTo(tab, {monitor})`): ayrıntı penceresi
  // sayfanın İzlemeler gövdesinde yaşar — Alarmlar görünümü açıksa kapatılır ki istek görünür bir pencere açsın.
  useEffect(() => {
    const on = (e) => {
      const d = e?.detail || {}
      if (d.monitor != null && d.monitor !== '') setTab(null)
    }
    window.addEventListener(TAB_PARAMS_EVENT, on)
    return () => window.removeEventListener(TAB_PARAMS_EVENT, on)
  }, [])
  /** Gömülü listenin sekme değişimi (aynı değeri yazmaz — gereksiz yeniden çizim yok). */
  const onTabChange = useCallback((v) => setTab((cur) => (cur && alarmTabFromUrl(v) && cur !== v ? v : cur)), [])

  /**
   * Bağlantı işleyicisi (AlertSourceNavContext): satırın izlemesi bu sayfadaysa tam sayfa yüklemeden İzlemeler görünümüne
   * dönüp izlemeyi açar (`useMonitorDeepLink` aynı sekme olayını dinler); Erişilebilirlik satırında sayfa araması.
   */
  const nav = useCallback((alert) => {
    const target = inPageTarget(alert, src, { canSearch: typeof searchRef.current === 'function' })
    if (!target) return false
    setTab(null)
    if (target.kind === 'monitor') {
      try { window.dispatchEvent(new CustomEvent(TAB_PARAMS_EVENT, { detail: { monitor: String(target.id) } })) } catch { /* jsdom eksikliği */ }
    } else {
      searchRef.current?.(target.q)
    }
    return true
  }, [src])

  return useMemo(() => ({ src, allowed, active: allowed && tab != null, tab, open, close, syncFromUrl, onTabChange, nav }),
    [src, allowed, tab, open, close, syncFromUrl, onTabChange, nav])
}

/**
 * Başlığın altındaki görünüm anahtarı — shadcn ToggleGroup (SegmentedControl, Sentetik sayfasının İzlemeler | Şablonlar
 * deseni). `monitorsLabel` sayfanın kendi adı (ör. Sertifika Panosu'nda "Sertifikalar").
 */
export function MonitorViewSwitch({ view, monitorsLabel, extraOptions = [], value, onChange, className }) {
  const t = useT()
  const current = value ?? (view.active ? 'alarms' : 'monitors')
  const options = [
    { value: 'monitors', label: monitorsLabel || t('mal.switch.monitors'), icon: LayoutGrid },
    ...extraOptions,
    ...(view.allowed === false ? [] : [{ value: 'alarms', label: t('mal.switch.alarms'), icon: Siren }]),
  ]
  // Alarm izni yoksa ve başka görünüm seçeneği de yoksa anahtar hiç çizilmez (tek seçenekli anahtar anlamsız).
  if (options.length < 2) return null
  return (
    <div data-slot="monitor-view-switch" className={cn('flex min-w-0 flex-wrap', className)}>
      <SegmentedControl value={current} ariaLabel={t('mal.switch.label')} options={options}
        onChange={(v) => {
          if (onChange) { onChange(v); return }
          if (v === 'alarms') view.open()
          else view.close()
        }} />
    </div>
  )
}

/** Görünüm gövdesi: Alarmlar görünümünde ortak alarm ekranı (tembel), aksi halde sayfanın kendi içeriği. */
export function MonitorPageBody({ view, children }) {
  if (!view?.active) return children
  return (
    <Suspense fallback={<AlarmsViewSkeleton />}>
      <MonitorAlarmsView view={view} />
    </Suspense>
  )
}

function AlarmsViewSkeleton() {
  return (
    <div data-slot="monitor-alarms-loading" className="flex min-w-0 flex-col gap-4" aria-busy="true">
      <Skeleton className="h-36 rounded-xl" />
      <Skeleton className="h-48 rounded-xl" />
    </div>
  )
}

