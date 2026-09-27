import { useT } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { cn } from '@/lib/utils'

/**
 * Zamanlama şeritleri — DNS çözümlemesi + her yolun (doğrudan/vekil × TLS modu) toplam süresi, en uzun süreye
 * oranlı yatay çubuklar. Sondalar PARALEL koştuğu için toplamlar üst üste yığılmaz; toplam koşu süresi ayrıca
 * yazılır. Renk = sonuç (başarılı yeşil, düşen kırmızı); değer metin olarak da verilir (renk tek taşıyıcı değil).
 * Test kancası: `data-slot="diag-timing"`, satırlar `data-lane` + `data-ms`.
 */
export default function DiagTiming({ timing, className }) {
  const t = useT()
  if (!timing || !timing.lanes?.length) return null
  const { lanes, max, total } = timing
  const label = (l) => (l.kind === 'dns' ? t('inv.diagDns') : `${l.via === 'proxy' ? t('inv.diagViaProxy') : t('inv.diagViaDirect')} · ${l.mode}`)
  return (
    <div data-slot="diag-timing" className={cn('flex min-w-0 flex-col gap-2', className)}>
      <ol className="flex flex-col gap-1.5" aria-label={t('diag.timing')}>
        {lanes.map((l) => {
          const pct = l.ms != null ? Math.max(1, Math.round((l.ms / max) * 100)) : 0
          return (
            <li key={l.key} data-lane={l.key} data-ms={l.ms ?? ''} data-status={l.status}
              className="grid min-w-0 grid-cols-[minmax(0,7.5rem)_minmax(0,1fr)_auto] items-center gap-2 text-xs sm:grid-cols-[minmax(0,10rem)_minmax(0,1fr)_auto]">
              <span className="truncate" title={label(l)}>{label(l)}</span>
              {/* ui/ProgressBar (progress-guard kapısı: elle yüzde çubuğu yok); değer yanında metin → decorative */}
              <ProgressBar value={pct} size="md" decorative tone={l.status === 'ok' ? 'ok' : 'crit'} className="rounded-full" />
              <span className="tabular-nums text-muted-foreground">{l.ms != null ? `${l.ms} ms` : '—'}</span>
            </li>
          )
        })}
      </ol>
      <p className="text-xs text-muted-foreground">{t('diag.timingHint', total ?? '—')}</p>
    </div>
  )
}
