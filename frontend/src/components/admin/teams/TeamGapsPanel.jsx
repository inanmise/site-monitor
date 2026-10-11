import { useId } from 'react'
import { BellOff, Boxes, Briefcase, FileText, FilterX, ListChecks, MailX, Siren, UserMinus, UserX, UsersRound } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { TEAM_GAPS } from './teamGaps.js'

/** Eksik anahtarı → ikon (panel çipleri, satır rozeti penceresi ve kartlar aynı ikonu kullanır). */
export const GAP_ICONS = Object.freeze({
  email: MailX, leader: UserX, members: UsersRound, contacts: Siren, manager: Briefcase,
  inactive_members: UserMinus, groups: BellOff, description: FileText, assets: Boxes,
})

/**
 * Dokunmatikte küçük çipin dokunma alanı: görünür boy DEĞİŞMEZ, `::after` dikeyde 10'ar px taşar (≥ 40 px) — sertifika
 * kartlarındaki TOUCH_CHIP kalıbı (certcard/CertCardParts.jsx; oradan içe aktarmak yönetim paketine kart kodunu çekerdi).
 * Çipte `relative` gerekir.
 */
export const TEAM_TOUCH_CHIP = 'pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-2.5'

/** Önem → sayı hapı tonu (sayı > 0 iken). */
export const GAP_SEVERITY_TONE = Object.freeze({
  high: 'bg-destructive/12 text-destructive',
  medium: 'bg-amber-500/15 text-amber-800 dark:text-amber-300',
  low: 'bg-muted text-muted-foreground',
})

/**
 * "Eksik bilgi" süzgeci (2026-10-11, kullanıcı isteği): takımda eksik olan her bilgi bir çip; çip o eksikle kaç takım
 * olduğunu sayar ve tıklanınca listeyi o takımlara indirir. Birden çok seçimde eşleşme "herhangi biri" / "hepsi".
 * Sayılar arama + diğer süzgeçlerden geçen takımlar üzerindendir (seçilen eksik kendi sayısını değiştirmez).
 *
 * Erişilebilirlik: çip düğmesi `aria-pressed`; erişilebilir ad = görünür etiket + sayı (aria-label YOK — sayfanın diğer
 * etiketleriyle çakışmasın); neden açıklaması `title`. Dokunmatikte 40 px.
 *
 * @param counts      `{ [gapKey]: number }`
 * @param selected    seçili eksik anahtarları
 * @param mode        'any' | 'all'
 * @param known       `(gapKey) => boolean` — sayaç henüz bilinmiyorsa (yükleniyor) sayı yerine "…"
 */
export default function TeamGapsPanel({ counts = {}, selected = [], mode = 'any', known = () => true, onToggle, onMode, onClear }) {
  const t = useT()
  const titleId = useId()
  return (
    <section data-slot="team-gaps" aria-labelledby={titleId} className="rounded-lg border bg-card p-3 shadow-xs sm:p-4">
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
        <div className="min-w-0 flex-[1_1_16rem]">
          <h4 id={titleId} className="flex items-center gap-1.5 text-sm font-semibold">
            <ListChecks aria-hidden="true" className="size-4 text-muted-foreground" />
            {t('team.gap.title')}
          </h4>
          <p className="mt-0.5 text-xs text-muted-foreground">{t('team.gap.desc')}</p>
        </div>
        {selected.length > 0 && (
          <div className="flex flex-wrap items-center gap-2">
            {selected.length >= 2 && (
              <ToggleGroup type="single" variant="outline" size="sm" value={mode} aria-label={t('team.gap.mode')}
                data-slot="team-gap-mode" onValueChange={(v) => v && onMode?.(v)}>
                <ToggleGroupItem value="any" className="max-sm:h-10 pointer-coarse:h-10">{t('team.gap.modeAny')}</ToggleGroupItem>
                <ToggleGroupItem value="all" className="max-sm:h-10 pointer-coarse:h-10">{t('team.gap.modeAll')}</ToggleGroupItem>
              </ToggleGroup>
            )}
            <Button type="button" variant="ghost" size="sm" className="max-sm:h-10 pointer-coarse:h-10" data-slot="team-gap-clear" onClick={onClear}>
              <FilterX aria-hidden="true" /> {t('team.gap.clear')}
            </Button>
          </div>
        )}
      </div>
      <div role="group" aria-labelledby={titleId}
        className="mt-3 grid grid-cols-1 gap-2 min-[420px]:grid-cols-2 md:flex md:flex-wrap">
        {TEAM_GAPS.map((g) => {
          const Icon = GAP_ICONS[g.key]
          const on = selected.includes(g.key)
          const isKnown = known(g.key)
          const n = Number(counts[g.key] ?? 0)
          return (
            <Button key={g.key} type="button" variant="outline" aria-pressed={on}
              data-slot="team-gap-chip" data-gap={g.key} data-severity={g.severity} data-count={isKnown ? n : undefined}
              title={t(`team.gap.${g.key}.why`)} onClick={() => onToggle?.(g.key)}
              className={cn('h-auto min-h-9 justify-start gap-2 px-2.5 py-1.5 text-left whitespace-normal max-sm:min-h-10 pointer-coarse:min-h-10',
                on && 'border-primary bg-primary/10 text-primary hover:bg-primary/15 hover:text-primary dark:bg-primary/15',
                !on && isKnown && n === 0 && 'text-muted-foreground')}>
              <Icon aria-hidden="true" className="size-4 shrink-0" />
              <span className="min-w-0 flex-1 text-[13px] leading-tight font-medium">{t(`team.gap.${g.key}.label`)}</span>
              <span data-slot="team-gap-count"
                className={cn('ml-1 shrink-0 rounded-full px-1.5 py-px text-xs font-bold tabular-nums',
                  isKnown && n > 0 ? GAP_SEVERITY_TONE[g.severity] : 'text-muted-foreground')}>
                {isKnown ? n : '…'}
              </span>
            </Button>
          )
        })}
      </div>
    </section>
  )
}
