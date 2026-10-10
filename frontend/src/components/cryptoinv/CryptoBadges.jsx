import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/**
 * Kripto envanteri rozetleri (2026-10-10) — durum metin + ton ile taşınır (sol şerit YOK). Tonlar `ToneBadge`
 * jetonlarından; kod → ton eşlemesi tek yerde.
 */
const TONE = { bad: 'danger', warn: 'warning', ok: 'info', pqc: 'success', muted: 'muted', info: 'info' }
const CAT_TONE = { BROKEN: 'bad', LEGACY: 'warn', MODERN: 'ok', PQC_READY: 'pqc', UNKNOWN: 'muted' }
const BAND_TONE = { P1: 'bad', P2: 'warn', P3: 'info', P4: 'muted', DONE: 'pqc' }
const PQC_TONE = { VULNERABLE: 'warn', HYBRID: 'pqc', PQC: 'pqc', UNKNOWN: 'muted' }

export function CategoryBadge({ category, className }) {
  const t = useT()
  return (
    <ToneBadge tone={TONE[CAT_TONE[category]] || 'muted'} data-category={category} className={cn('font-semibold', className)}>
      {t(`cinv.cat.${category || 'UNKNOWN'}`)}
    </ToneBadge>
  )
}

export function PqcBadge({ pqc, className }) {
  const t = useT()
  return (
    <ToneBadge tone={TONE[PQC_TONE[pqc]] || 'muted'} data-pqc={pqc} className={cn('font-normal', className)}>
      {t(`cinv.pqc.${pqc || 'UNKNOWN'}`)}
    </ToneBadge>
  )
}

export function BandBadge({ band, className }) {
  const t = useT()
  return (
    <ToneBadge tone={TONE[BAND_TONE[band]] || 'muted'} data-band={band} className={cn('font-bold', className)}>
      {t(`cinv.band.${band || 'P4'}`)}
    </ToneBadge>
  )
}

/** Kaynak: ağ uç noktası (port) ya da yüklenen sertifika (sürüm). */
export function SourceBadge({ row, className }) {
  const t = useT()
  if (row.source === 'MANUAL') {
    return (
      <ToneBadge tone="info" data-source="MANUAL" className={cn('font-normal', className)}>
        {t('cinv.src.MANUAL')}{row.manual_version ? ` · v${row.manual_version}` : ''}
      </ToneBadge>
    )
  }
  return (
    <ToneBadge tone="muted" data-source="NETWORK" className={cn('font-normal', className)}>
      {t('cinv.src.NETWORK')}{row.port && row.port !== 443 ? ` · :${row.port}` : ''}
    </ToneBadge>
  )
}

/**
 * Öncelik puanı — dokununca/tıklayınca bileşen dökümü açılır (Popover: dokunmatikte de çalışır; yalnız-hover bilgi
 * yok). Düğmenin erişilebilir adı satırı (alan adını) içerir.
 */
export function PriorityChip({ row }) {
  const t = useT()
  const p = row.priority || {}
  const parts = [
    ['exposure', p.exposure, 40], ['strength', p.strength, 30], ['renewal', p.renewal, 20], ['hndl', p.hndl, 10],
  ]
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" data-slot="cinv-priority" data-band={p.band}
          aria-label={t('cinv.priorityAria', row.domain, p.score ?? 0, t(`cinv.band.${p.band || 'P4'}`))}
          className="h-auto min-h-10 gap-1.5 px-2 py-1 sm:min-h-8">
          <span className="text-base leading-none font-extrabold tabular-nums">{p.score ?? 0}</span>
          <BandBadge band={p.band} className="pointer-events-none" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={8} className="z-(--z-menu) w-[min(19rem,calc(100vw-1rem))]">
        <div className="mb-2 text-sm font-semibold">{t('cinv.breakdownTitle', p.score ?? 0)}</div>
        {p.band === 'DONE' ? (
          <p className="m-0 text-sm text-muted-foreground">{t('cinv.breakdownDone')}</p>
        ) : (
          <dl className="m-0 grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1.5 text-sm">
            {parts.map(([k, v, max]) => (
              <div key={k} className="contents">
                <dt className="text-muted-foreground">{t(`cinv.rule.${k}`)}</dt>
                <dd className="m-0 text-right font-semibold tabular-nums">{v ?? 0} / {max}</dd>
              </div>
            ))}
          </dl>
        )}
        <p className="mt-2 mb-0 text-xs text-muted-foreground">{t('cinv.breakdownHint')}</p>
      </PopoverContent>
    </Popover>
  )
}
