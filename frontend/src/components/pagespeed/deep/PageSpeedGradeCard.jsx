import { Gauge, Lightbulb } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { formatBytes } from '../../../utils/formatBytes.js'
import { cn } from '@/lib/utils'

const WRAP = '[overflow-wrap:anywhere]'

/** Harf → ton (durum tonu yalnız ölçüm tamken; kısmi ölçümde nötr). */
const LETTER_TONE = Object.freeze({
  A: 'text-success', B: 'text-success', C: 'text-warning', D: 'text-warning', E: 'text-destructive', F: 'text-destructive',
})

/** Bileşen değerinin okunur biçimi. */
function valueText(key, v, t) {
  if (v === null || v === undefined) return '—'
  switch (key) {
    case 'load': case 'ttfb': return t('pspd.grade.ms', Math.round(v))
    case 'weight': case 'images': case 'js': return formatBytes(v)
    case 'requests': case 'unloaded': return String(v)
    default: return String(v)
  }
}

/** Öneri metni (parametreler biçimlenmiş). */
export function recText(r, t) {
  const [p0, p1] = r.params || []
  switch (r.code) {
    case 'FAILED_RESOURCES': return t('pspd.grade.rec.FAILED_RESOURCES', p0)
    case 'SLOW_TTFB': return t('pspd.grade.rec.SLOW_TTFB', Math.round(p0))
    case 'HEAVY_PAGE': return t('pspd.grade.rec.HEAVY_PAGE', formatBytes(p0))
    case 'LARGE_IMAGES': return t('pspd.grade.rec.LARGE_IMAGES', p0, formatBytes(p1))
    case 'HEAVY_JS': return t('pspd.grade.rec.HEAVY_JS', formatBytes(p0))
    case 'THIRD_PARTY_HEAVY': return t('pspd.grade.rec.THIRD_PARTY_HEAVY', formatBytes(p0), p1)
    case 'TOO_MANY_REQUESTS': return t('pspd.grade.rec.TOO_MANY_REQUESTS', p0)
    case 'SLOW_RESOURCES': return t('pspd.grade.rec.SLOW_RESOURCES', p0)
    default: return t(`pspd.grade.rec.${r.code}`)
  }
}

/**
 * Hız notu kartı (Sayfa Hızı 2. sürüm madde 18): 0–100 puan + harf, bileşen puanları (yalnız ölçülenler), öneriler (ağır
 * görseller / yavaş kaynaklar adresleriyle). Yük süresi bilinmiyorsa not yok ("hesaplanamadı"); ölçüm süre / bayt tavanında
 * kesildiyse not ÜST SINIRDIR — nötr tonda "kısmi" denir, yeşil gösterilmez. Hafif bir tahmindir (tarayıcı çalıştırılmaz).
 */
export default function PageSpeedGradeCard({ grade, checkedAt, isLatest, older = false }) {
  const t = useT()
  if (!grade) return null
  // Kısmi ölçüm ya da son ölçümden ESKİ bir ölçüm (son ölçümde kaynak saklanmadı — örn. sayfa açılamadı): durum tonu yok
  const tone = grade.partial || older || grade.letter == null ? 'text-muted-foreground' : LETTER_TONE[grade.letter]
  const comps = grade.components.filter((c) => c.score !== null)
  return (
    <section data-slot="pspd-grade" data-letter={grade.letter || undefined} data-partial={grade.partial ? 'true' : undefined}
      data-older={older ? 'true' : undefined}
      className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-3 sm:p-4">
      <div className="flex min-w-0 flex-wrap items-center gap-3">
        <div className="flex size-16 shrink-0 flex-col items-center justify-center rounded-full border-4 border-border" aria-hidden="true">
          <span className={cn('text-xl font-bold tabular-nums leading-none', tone)}>{grade.score ?? '—'}</span>
          {grade.letter && <span className={cn('text-xs font-semibold', tone)}>{grade.letter}</span>}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <h3 className="m-0 flex items-center gap-1.5 text-sm font-semibold text-foreground">
            <Gauge aria-hidden="true" className="size-4" />{t('pspd.grade.title')}
          </h3>
          <p data-slot="pspd-grade-summary" className={cn('m-0 text-sm text-foreground', WRAP)}>
            {grade.score == null ? t('pspd.grade.unknown')
              : grade.partial ? t('pspd.grade.partialScore', grade.score, grade.letter)
                : t('pspd.grade.score', grade.score, grade.letter)}
          </p>
          <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
            {t(older ? 'pspd.grade.basisOlder' : isLatest ? 'pspd.grade.basisLatest' : 'pspd.grade.basisSnapshot', checkedAt ? formatDate(checkedAt) : '—')}
          </p>
        </div>
      </div>
      {grade.partial && (
        <p data-slot="pspd-grade-partial" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.grade.partialNote')}</p>
      )}
      {comps.length > 0 && (
        <ul data-slot="pspd-grade-components" className="m-0 grid list-none grid-cols-1 gap-1.5 p-0 @container sm:grid-cols-2">
          {comps.map((c) => (
            <li key={c.key} data-slot="pspd-grade-component" data-key={c.key} data-score={c.score}
              className="flex min-w-0 flex-col gap-1 rounded-md bg-muted/40 px-2.5 py-1.5">
              <div className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-2 text-xs">
                <span className={cn('font-medium text-foreground', WRAP)}>{t(`pspd.grade.comp.${c.key}`)}</span>
                <span className="tabular-nums text-muted-foreground">{valueText(c.key, c.value, t)} · {t('pspd.grade.points', c.score)}</span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-border" aria-hidden="true">
                <div className="h-full rounded-full bg-[var(--chart-1)]" style={{ width: `${c.score}%` }} />
              </div>
            </li>
          ))}
        </ul>
      )}
      {grade.recs.length > 0 && (
        <div data-slot="pspd-grade-recs" className="flex min-w-0 flex-col gap-1.5">
          <h4 className="m-0 flex items-center gap-1.5 text-xs font-semibold text-foreground">
            <Lightbulb aria-hidden="true" className="size-3.5" />{t('pspd.grade.recsTitle')}
          </h4>
          <ul className="m-0 flex list-disc flex-col gap-1.5 pl-5 text-xs text-muted-foreground">
            {grade.recs.map((r) => (
              <li key={r.code} data-slot="pspd-grade-rec" data-code={r.code} className={WRAP}>
                <span>{recText(r, t)}</span>
                {r.urls.length > 0 && (
                  <ul className="m-0 mt-0.5 flex list-none flex-col gap-0.5 p-0">
                    {r.urls.map((u) => <li key={u} className={cn('font-mono text-[11px] text-foreground/80', WRAP)}>{u}</li>)}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      <p className={cn('m-0 text-[11px] text-muted-foreground', WRAP)}>{t('pspd.grade.method')}</p>
    </section>
  )
}
