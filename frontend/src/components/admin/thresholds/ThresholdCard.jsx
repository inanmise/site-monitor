import { Layers, Pencil, Repeat, Trash2 } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { countsOf, daysOf, hoursKey, tierOf } from './thresholdModel.js'
import ThresholdScale, { LevelLegend } from './ThresholdScale.jsx'

/**
 * Tek eşik satırı kartı — başlık + kapsam rozeti + görsel ölçek + seviye lejantı (+ "şu an N alan") +
 * yeniden uyarı bilgisi. Sol renk şeridi YOK (kullanıcı kuralı 2026-09-26): kapsam/ton rozetle taşınır.
 *
 * <p>Telefonda (useIsMobile) eylemler başlığın sağına sıkışmaz; kartın altında tam genişlik, 40 px düğmeler
 * (CardFooter). Masaüstünde CardAction. Aynı düğmelerin iki kopyası DOM'a yazılmaz (yapı farkı → hook).
 *
 * @param row      sunucu satırı (`_builtin` = kaydedilmemiş yerleşik varsayılan)
 * @param impact   önizleme ucunun bu kapsam için yanıtı (`current` sayımları, `scope_total`, `unchecked`) ya da undefined
 * @param defaultReAlert  varsayılan satırın yeniden uyarı aralığı (saat) — her alarm için geçerli olan
 */
export default function ThresholdCard({ row, title, impact, defaultReAlert, canEdit, phone, onEdit, onDelete }) {
  const t = useT()
  const tier = tierOf(row)
  const values = daysOf(row)
  const counts = impact ? countsOf(impact.current) : null
  const hours = t(...hoursKey(defaultReAlert))

  const actions = canEdit ? (
    <div data-slot="threshold-actions" className={cn('flex items-center gap-2', phone && 'w-full')}>
      <Button type="button" variant="outline" size={phone ? 'lg' : 'sm'} className={cn(phone && 'flex-1')}
        onClick={onEdit} aria-label={t('a11y.rowAction', row._builtin ? t('thr.setUp') : t('thr.edit'), title)}>
        <Pencil aria-hidden="true" /> {row._builtin ? t('thr.setUp') : t('thr.edit')}
      </Button>
      {tier != null && (
        <Button type="button" variant="outline" size={phone ? 'icon-lg' : 'icon-sm'} title={t('thr.delete')}
          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
          onClick={onDelete} aria-label={t('a11y.rowAction', t('thr.delete'), title)}>
          <Trash2 aria-hidden="true" />
        </Button>
      )}
    </div>
  ) : null

  // Kart geniş ekranda yan yana dizilir (auto-fit ızgara); lejant sütunları görünüm alanına değil KARTIN
  // genişliğine göre (kap sorgusu @lg): dar kartta 2, geniş kartta 4 sütun.
  return (
    <Card data-slot="threshold-card" data-tier={tier ?? 'default'} className="gap-4 bg-background py-4 shadow-none">
      <CardHeader className="gap-1.5 px-4 sm:px-5">
        <CardTitle role="heading" aria-level={4} className="flex min-w-0 items-center gap-2 text-base leading-snug">
          <Layers size={16} aria-hidden="true" className="shrink-0 text-muted-foreground" />
          <span className="min-w-0">{title}</span>
        </CardTitle>
        <CardDescription className="flex flex-wrap items-center gap-1.5">
          <ToneBadge tone={tier ? 'info' : 'muted'} data-slot="threshold-scope">
            {tier ? t('thr.scopeTier', tier) : t('thr.scopeDefault')}
          </ToneBadge>
          {row._builtin && <ToneBadge tone="warning">{t('thr.builtinBadge')}</ToneBadge>}
          {row.active === false && <ToneBadge tone="warning">{t('thr.inactive')}</ToneBadge>}
          {impact && (
            <span data-slot="threshold-scope-count" className="text-xs tabular-nums">
              {t('thr.domainsInScope', impact.scope_total ?? 0)}
              {impact.unchecked > 0 && <> · {t('thr.previewUnchecked', impact.unchecked)}</>}
            </span>
          )}
        </CardDescription>
        {!phone && actions && <CardAction>{actions}</CardAction>}
      </CardHeader>
      <CardContent className="@container flex flex-col gap-4 px-4 sm:px-5">
        <ThresholdScale values={values} />
        <LevelLegend values={values} counts={counts} />
        <p data-slot="threshold-realert" className="flex items-start gap-2 text-xs text-muted-foreground">
          <Repeat size={14} aria-hidden="true" className="mt-px shrink-0" />
          <span>{tier == null ? t('thr.reAlertCard', hours) : t('thr.reAlertCardTier', hours)}</span>
        </p>
      </CardContent>
      {phone && actions && <CardFooter className="px-4">{actions}</CardFooter>}
    </Card>
  )
}
