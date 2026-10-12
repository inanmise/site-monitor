import { useState } from 'react'
import { ArrowRight, CircleAlert, CircleCheck, CircleDashed, TriangleAlert } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'
import { gapSeverity, worstSeverity } from './teamGaps.js'
import { GAP_ICONS, GAP_SEVERITY_TONE, TEAM_TOUCH_CHIP } from './TeamGapsPanel.jsx'

const TRIGGER_TONE = {
  high: 'border-destructive/40 bg-destructive/10 text-destructive hover:bg-destructive/15 hover:text-destructive',
  medium: 'border-amber-500/45 bg-amber-500/10 text-amber-800 hover:bg-amber-500/15 hover:text-amber-800 dark:text-amber-300 dark:hover:text-amber-300',
  low: 'border-border bg-muted text-muted-foreground hover:bg-muted/80',
}

/**
 * Satırın eksik bilgi rozeti (2026-10-11): eksik yoksa yeşil "Eksiksiz"; varsa "N eksik" düğmesi (ton = en ağır
 * eksiğin önemi) — tıklanınca her eksiği nedeniyle ve düzeltme kısayoluyla listeleyen pencere açılır.
 *
 * @param team     takım kaydı (ad başlıkta TAM yazılır — kesilmez)
 * @param gaps     eksik anahtarları (katalog sırası)
 * @param actions  `{ [gapKey]: () => void }` — yalnız kullanıcının yapabildiği düzeltmeler; yoksa yalnız açıklama
 * @param partial  bazı kurallar değerlendirilemedi — pencere ve rozet bunu söyler
 * @param partialReason `pending` (sayaçlar / kullanıcı listesi henüz gelmedi) | `failed` (okunamadı) | `scope` (lider / müdür görüş alanı dışında)
 */
export default function TeamGapBadge({ team, gaps = [], actions = {}, partial = false, partialReason = 'pending' }) {
  const t = useT()
  // Açık pencere HANGİ kipe ait (inceleme 5): "henüz denetlenmedi" penceresi açıkken veriler gelip satır "N eksik"e dönerse
  // eksik penceresi kendiliğinden açılmasın — açık durum kipe bağlı, kip değişince kapanır.
  const mode = gaps.length > 0 ? 'gaps' : partial ? 'unknown' : 'complete'
  const [openMode, setOpenMode] = useState(null)
  const open = openMode === mode
  const setOpen = (v) => setOpenMode(v ? mode : null)
  // Bilinmeyen ne eksik ne eksiksizdir (2026-10-12): sayaçlar / kullanıcı listesi gelmeden (ya da hiç gelmezse) eksik
  // görünmeyen satır yeşil "Eksiksiz" demez — nötr "henüz tam denetlenmedi".
  const reasonText = partialReason === 'scope' ? t('team.gap.scopeUnknown')
    : partialReason === 'failed' ? t('team.gap.loadFailed') : t('team.gap.statsPending')
  if (gaps.length === 0 && partial) {
    // Neden dokunmatiğe de ulaşır (inceleme 4): title dokunmatikte görünmez, sr-only görünmez — dokununca açılan Popover
    // nedeni yazar (eksik rozetiyle aynı kalıp). title fare kullanıcıları için kalır.
    return (
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button type="button" variant="ghost" size="xs" data-slot="team-gap-badge" data-state="unknown" data-reason={partialReason}
            title={reasonText} aria-label={`${t('team.gap.unknown')}: ${reasonText}`}
            className={cn('relative h-auto gap-1 rounded-md overflow-visible whitespace-normal px-2 py-0.5 text-left text-xs font-medium text-muted-foreground', TEAM_TOUCH_CHIP)}>
            <CircleDashed aria-hidden="true" className="size-3.5 shrink-0" />{t('team.gap.unknown')}
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" collisionPadding={8} data-slot="team-gap-unknown-pop"
          className="z-(--z-menu) w-[min(20rem,calc(100vw-2rem))] p-3 text-xs [overflow-wrap:anywhere]">
          {team?.name && <p className="mb-1 text-sm font-semibold">{team.name}</p>}
          <p className="m-0 text-muted-foreground">{reasonText}</p>
        </PopoverContent>
      </Popover>
    )
  }
  if (gaps.length === 0) {
    return (
      <span data-slot="team-gap-badge" data-state="complete"
        className="inline-flex items-center gap-1 text-xs font-medium text-success">
        <CircleCheck aria-hidden="true" className="size-3.5 shrink-0" />{t('team.gap.none')}
      </span>
    )
  }
  const sev = worstSeverity(gaps)
  const countText = t('team.gap.count', gaps.length)
  const list = gaps.map((k) => t(`team.gap.${k}.label`)).join(', ')
  const SevIcon = sev === 'high' ? TriangleAlert : CircleAlert
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="xs" data-slot="team-gap-badge" data-severity={sev}
          aria-label={t('team.gap.badgeAria', countText, team?.name ?? '', list)}
          className={cn('relative h-auto gap-1 rounded-full px-2 py-0.5 text-xs font-semibold', TEAM_TOUCH_CHIP, TRIGGER_TONE[sev])}>
          <SevIcon aria-hidden="true" className="size-3.5" />{countText}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={8} data-slot="team-gap-pop"
        className="z-(--z-menu) w-[min(23rem,calc(100vw-2rem))] p-0">
        <div className="border-b px-3 py-2.5">
          <p className="text-sm font-semibold [overflow-wrap:anywhere]">{team?.name}</p>
          <p className="text-xs text-muted-foreground">{t('team.gap.popSub', gaps.length)}</p>
        </div>
        <ul className="max-h-[min(60vh,26rem)] divide-y overflow-y-auto">
          {gaps.map((k) => {
            const Icon = GAP_ICONS[k]
            const fix = actions[k]
            const s = gapSeverity(k)
            return (
              <li key={k} data-gap={k} className="flex gap-2.5 px-3 py-2.5">
                <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[13px] font-semibold">
                    {t(`team.gap.${k}.label`)}
                    <span className={cn('rounded-full px-1.5 py-px text-[10.5px] font-semibold', GAP_SEVERITY_TONE[s])}>{t(`team.gap.sev.${s}`)}</span>
                  </p>
                  <p className="mt-0.5 text-xs leading-snug text-muted-foreground">{t(`team.gap.${k}.why`)}</p>
                  {fix && (
                    <Button type="button" variant="link" size="sm" data-slot="team-gap-fix"
                      className="mt-1 h-auto gap-1 p-0 text-xs max-sm:min-h-10 pointer-coarse:min-h-10"
                      onClick={() => { setOpen(false); fix() }}>
                      {t(`team.gap.${k}.fix`)} <ArrowRight aria-hidden="true" className="size-3.5" />
                    </Button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
        {partial && <p className="border-t px-3 py-2 text-xs text-muted-foreground">{reasonText}</p>}
      </PopoverContent>
    </Popover>
  )
}
