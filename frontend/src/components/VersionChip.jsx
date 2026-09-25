import { useEffect, useState } from 'react'
import { useAppVersion } from '../contexts/BrandingProvider.jsx'
import { useT } from '../i18n/index.jsx'
import { isNewVersion, readLastSeenVersion, writeLastSeenVersion } from '../utils/releaseUi.js'
import VersionPopover from './VersionPopover.jsx'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'

/**
 * Nav'daki sürüm çipi (K1): tıklanınca "en son geçerli sürüm hangisi ve ne zaman devreye alındı?"
 * sorusunu tek popover'da cevaplar. Eskiden düz bir <span> idi.
 *
 * <p>E1 — yeni-sürüm noktası: son görülen sürüm damgası localStorage'da (try/catch; kapalı depolamada
 * özellik sessizce devre dışı). İlk ziyarette karşılaştırılacak damga yok → nokta ÇIKMAZ, damga
 * yazılır. Popover açılınca damga güncellenir (nokta söner).
 *
 * <p>shadcn Popover (Radix): portal, dış tıklama, Escape ve odak yönetimi bileşenden. Veri YALNIZ
 * açılınca çekilir (VersionPopover yalnız açıkken çizilir; 60 sn modül önbelleği tutar).
 */
export default function VersionChip({ onTabChange }) {
  const t = useT()
  const appVersion = useAppVersion()
  const [open, setOpen] = useState(false)
  const [seen, setSeen] = useState(readLastSeenVersion)
  const [sinceVersion, setSinceVersion] = useState('')   // popover açılırken yakalanan eski damga

  const fresh = isNewVersion(seen, appVersion)

  // İlk ziyaret: damga yok → bu sürümü sessizce yaz (bir sonraki sürümde nokta çıkabilsin).
  useEffect(() => {
    if (appVersion && !seen) { writeLastSeenVersion(appVersion); setSeen(appVersion) }
  }, [appVersion, seen])

  function onOpenChange(next) {
    setOpen(next)
    if (next && appVersion) {
      setSinceVersion(fresh ? seen : '')            // damga güncellenmeden ÖNCE yakala
      writeLastSeenVersion(appVersion); setSeen(appVersion)
    }
  }

  function go(tab, extra) {
    setOpen(false)
    onTabChange?.(tab, extra)
  }

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="xs" title={t('version.chipTitle')}
          className="-ml-1.5 h-auto w-fit gap-1.5 px-1.5 py-0 text-xs font-normal text-muted-foreground hover:text-sidebar-accent-foreground data-[state=open]:text-sidebar-accent-foreground">
          v{appVersion}
          {fresh && (
            <span data-new-version="" className="inline-block size-1.5 rounded-full bg-success ring-2 ring-success/25"
              aria-label={t('version.newDot')} title={t('version.newDot')} />
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" sideOffset={8} aria-label={t('version.chipTitle')}
        className="z-(--z-menu) w-80 text-sm">
        {open && <VersionPopover appVersion={appVersion} previousSeen={sinceVersion} onNavigate={go} />}
      </PopoverContent>
    </Popover>
  )
}
