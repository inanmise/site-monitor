import { CheckCircle2, XCircle, AlertTriangle, MinusCircle, CircleDashed, ChevronRight } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import { cn } from '@/lib/utils'
import { headerRows, timingSegments } from './httpDiagnoseModel.js'

/**
 * HTTP tanılama penceresinin küçük sunum parçaları (2026-10-02) — adım hattı, zamanlama şelalesi, başlık listesi,
 * kod bloğu, anahtar/değer satırı. Tümü shadcn/Tailwind; legacy sınıf yok, durum SOL ŞERİTLE DEĞİL ikon + rozet +
 * `data-status` ile taşınır. Telefonda hiçbir parça yatay taşmaz: uzun değerler kırılır (`break-all`), kod blokları
 * satır sarar ve yalnız kendi kabında dikey kayar.
 */

/** Adım durumu → ikon + renk (metin rozeti ayrıca i18n'den). */
export const STATE_META = {
  ok:      { Icon: CheckCircle2, icon: 'text-success' },
  fail:    { Icon: XCircle, icon: 'text-destructive' },
  warn:    { Icon: AlertTriangle, icon: 'text-amber-600 dark:text-amber-400' },
  skip:    { Icon: MinusCircle, icon: 'text-muted-foreground' },
  pending: { Icon: CircleDashed, icon: 'text-muted-foreground' },
}
export const stateMeta = (s) => STATE_META[s] || STATE_META.pending

/** Bölüm başlığı (küçük, ikonlu). */
export function SectionTitle({ icon: Icon, children, id, className }) {
  return (
    <h4 id={id} className={cn('flex min-w-0 items-center gap-1.5 text-sm font-semibold', className)}>
      {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-primary" />}
      <span className="min-w-0 break-words">{children}</span>
    </h4>
  )
}

/** Anahtar/değer satırı — telefonda alt alta, geniş ekranda iki sütun. Değer `??` ile düşer (0 ms "—" olmaz). */
export function Kv({ label, children, mono = false, className }) {
  return (
    <div data-slot="httpdx-kv" className={cn('grid min-w-0 grid-cols-1 gap-x-3 gap-y-0.5 sm:grid-cols-[minmax(0,9.5rem)_minmax(0,1fr)]', className)}>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn('min-w-0 text-[13px] break-words', mono && 'font-mono text-xs break-all')}>{children ?? '—'}</dd>
    </div>
  )
}

/** Kv satırlarının kabı (`<dl>`). */
export function KvList({ children, className }) {
  return <dl className={cn('flex min-w-0 flex-col gap-1.5', className)}>{children}</dl>
}

/**
 * Adım hattı — adımlar sözleşme sırasında hap olarak (DNS → Vekile bağlantı → … → Gövde). Takılan adım kırmızı
 * çerçeve + `data-failed="true"`; atlanan adım soluk. Haplar SARAR (telefonda alt satıra iner, yatay kaydırma yok).
 * Ekran okuyucu her hapı "ad: durum, süre" diye okur.
 *
 * @param {Array} steps       normalizeSteps çıktısı
 * @param {string} failedStep yolun takıldığı adım (vurgulanır)
 * @param {boolean} compact   kart özeti (küçük haplar)
 */
export function StepPipeline({ steps, failedStep, compact = false, label, className }) {
  const t = useT()
  if (!Array.isArray(steps) || !steps.length) {
    return <p className="text-xs text-muted-foreground">{t('httpdx.steps.none')}</p>
  }
  return (
    <ol data-slot="httpdx-steps" aria-label={label || t('httpdx.steps.label')}
      className={cn('flex min-w-0 flex-wrap items-center gap-1', className)}>
      {steps.map((s, i) => {
        const m = stateMeta(s.state)
        const failed = s.state === 'fail' || (failedStep && s.key === failedStep)
        return (
          <li key={`${s.key}-${i}`} data-step={s.key} data-status={s.state} data-failed={failed ? 'true' : undefined}
            className="flex min-w-0 items-center gap-1">
            <span className={cn('inline-flex min-w-0 items-center gap-1 rounded-md border bg-card',
              compact ? 'px-1.5 py-0.5 text-[11px]' : 'px-2 py-1 text-xs',
              s.state === 'skip' && 'opacity-60',
              failed && 'border-destructive/60 bg-destructive/10 font-semibold text-destructive')}>
              <m.Icon aria-hidden="true" className={cn('shrink-0', compact ? 'size-3' : 'size-3.5', m.icon)} />
              <span className="min-w-0 truncate">{t(`httpdx.step.${s.key}`)}</span>
              {s.ms != null && <span className={cn('tabular-nums', failed ? 'text-destructive' : 'text-muted-foreground')}>{s.ms} ms</span>}
              <span className="sr-only">: {t(`httpdx.stepState.${s.state}`)}</span>
            </span>
            {i < steps.length - 1 && <ChevronRight aria-hidden="true" className="size-3 shrink-0 text-muted-foreground/60" />}
          </li>
        )
      })}
    </ol>
  )
}

/** Şelale çubuğu rengi — ölçülen evre birincil, bekleme (yanıt/gövde gelmedi) kırmızı, artık gri. */
const BAR = { phase: 'bg-primary/70', stalled: 'bg-destructive', other: 'bg-muted-foreground/40' }

/**
 * Zamanlama şelalesi — her evre kendi başlangıç noktasından süresi kadar (gantt). Ölçek yolun toplam süresi; değer
 * metin olarak da yazılır (renk tek taşıyıcı değil). Konum+genişlik birlikte verildiği için ui/ProgressBar (yalnız
 * doluluk) karşılamaz — çubuk süsleyicidir (`aria-hidden`), bilgi satırın metnindedir.
 */
export function TimingWaterfall({ timeline, className }) {
  const t = useT()
  const { segments, total } = timingSegments(timeline)
  if (!segments.length) return <p className="text-xs text-muted-foreground">{t('httpdx.timing.none')}</p>
  return (
    <div data-slot="httpdx-waterfall" className={cn('flex min-w-0 flex-col gap-2', className)}>
      <ol className="flex flex-col gap-1.5" aria-label={t('httpdx.timing.title')}>
        {segments.map((s) => (
          <li key={s.key} data-segment={s.key} data-ms={s.ms} data-kind={s.kind}
            className="grid min-w-0 grid-cols-[minmax(0,7.5rem)_minmax(0,1fr)_auto] items-center gap-2 text-xs sm:grid-cols-[minmax(0,11rem)_minmax(0,1fr)_auto]">
            <span className={cn('truncate', s.kind === 'stalled' && 'font-semibold text-destructive')} title={t(`httpdx.timing.${s.key}`)}>
              {t(`httpdx.timing.${s.key}`)}
            </span>
            <span aria-hidden="true" className="relative block h-2.5 min-w-0 overflow-hidden rounded-full bg-muted">
              <span className={cn('absolute inset-y-0 rounded-full', BAR[s.kind] || BAR.phase)}
                style={{ left: `${s.startPct}%`, width: `${s.widthPct}%` }} />
            </span>
            <span className={cn('tabular-nums', s.kind === 'stalled' ? 'font-semibold text-destructive' : 'text-muted-foreground')}>{s.ms} ms</span>
          </li>
        ))}
      </ol>
      {total != null && <p className="text-xs text-muted-foreground">{t('httpdx.timing.total', total)}</p>}
    </div>
  )
}

/**
 * Başlık listesi — ad/değer satırları (telefonda alt alta, genişte iki sütun); gizlenen değerde "gizli" rozeti.
 * Tablo yerine `<dl>`: başlık değerleri (çerez, CSP) çok uzun olabilir; satır sararak okunur, yatay kaydırma yok.
 */
export function HeaderList({ headers, label, className }) {
  const t = useT()
  const rows = headerRows(headers)
  if (!rows.length) return <p className="text-xs text-muted-foreground">{t('httpdx.hdr.none')}</p>
  return (
    <dl data-slot="httpdx-headers" aria-label={label} className={cn('min-w-0 divide-y overflow-hidden rounded-md border text-xs', className)}>
      {rows.map((r) => (
        <div key={r.i} data-slot="httpdx-header" data-masked={r.masked ? 'true' : undefined}
          className="grid min-w-0 grid-cols-1 gap-0.5 px-2.5 py-1.5 odd:bg-muted/30 sm:grid-cols-[minmax(0,12rem)_minmax(0,1fr)] sm:gap-3">
          <dt className="min-w-0 font-mono font-semibold break-all">{r.name}</dt>
          <dd className="flex min-w-0 flex-wrap items-center gap-1.5">
            <span className="min-w-0 font-mono break-all">{r.display}</span>
            {r.masked && <ToneBadge tone="muted" data-slot="httpdx-masked" title={t('httpdx.hdr.maskedHint')}>{t('httpdx.hdr.masked')}</ToneBadge>}
          </dd>
        </div>
      ))}
    </dl>
  )
}

/**
 * Tek biçimli metin kutusu (istek satırı, gövde önizlemesi, hata zinciri). Satır SARAR (`pre-wrap` + her yerde
 * kırılma) → telefonda yatay kaydırma yok; uzun içerik kendi kabında dikey kayar. `copy` verilirse sağ üstte kopyala.
 */
export function CodeBlock({ children, copy, copyLabel, maxH = 'max-h-80', className, ...rest }) {
  const t = useT()
  return (
    <div className="relative min-w-0">
      <pre className={cn('min-w-0 overflow-y-auto rounded-md border bg-muted/50 px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]',
        maxH, copy != null && 'pr-12', className)} {...rest}>
        {children}
      </pre>
      {copy != null && (
        <CopyButton value={copy} variant="ghost" buttonSize="icon-sm" label={copyLabel || t('httpdx.copy')} copiedLabel={t('httpdx.copied')}
          className="absolute top-1 right-1 text-muted-foreground hover:text-primary pointer-coarse:size-10" />
      )}
    </div>
  )
}
