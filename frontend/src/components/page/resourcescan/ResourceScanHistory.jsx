import { History } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { comparableScans, formatMs, formatNumber, formatSize, gradeLetter, isCancelled, isCapped, plural, scanTone } from './resourceScanModel.js'
import { CHIP, Section, TOUCH, WRAP } from './ResourceScanParts.jsx'
import ResourceScanCompare from './ResourceScanCompare.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

const STATUS_TONE = { DONE: 'success', RUNNING: 'info', FAILED: 'danger', INTERRUPTED: 'warning' }

/** Geçmiş satırının durum metni anahtarı (iptal edilen yarıda kalmış ayrı). */
function statusKey(s) {
  if (s.status === 'INTERRUPTED' && isCancelled(s)) return 'prs.status.run.CANCELLED'
  return `prs.status.run.${STATUS_TONE[s.status] ? s.status : 'UNKNOWN'}`
}

/**
 * Kaynak analizi → GEÇMİŞ (2026-10-31; r177): her analiz (yeniden eskiye) — ne zaman, kim başlattı, not, tahmini
 * yüklenme, sayfa boyutu, nesne sayısı, durum. Satıra basınca o analiz panoda açılır; seçili satır `aria-current`.
 * Süren analizin satırı seçimi DEĞİŞTİRMEZ (r177 H4) — ilerleme kartına götürür. "Liste | Karşılaştır" anahtarı: iki
 * tamamlanmış analizi istemcide karşılaştırır (ResourceScanCompare). Satırın erişilebilir adı görünen bilginin TAMAMI.
 */
export default function ResourceScanHistory({
  items, selectedId, onSelect, onShowRunning, limits, mode = 'list', onModeChange, otherId, onCompareChange, getDetail,
}) {
  const t = useT()
  const locale = useDateLocale()
  const list = Array.isArray(items) ? items : []
  const canCompare = comparableScans(list).length >= 2
  const effectiveMode = mode === 'compare' && canCompare ? 'compare' : 'list'
  const days = limits?.retention_days ?? limits?.history_days ?? null
  return (
    <div data-slot="prs-history-wrap" className="flex min-w-0 flex-col gap-3">
      {canCompare && onModeChange && (
        <span data-slot="prs-history-mode" data-mode={effectiveMode} className="w-fit">
          <SegmentedControl value={effectiveMode} onChange={onModeChange} ariaLabel={t('prs.hist.mode')}
            options={[{ value: 'list', label: t('prs.hist.modeList') }, { value: 'compare', label: t('prs.hist.modeCompare') }]} />
        </span>
      )}
      {effectiveMode === 'compare' ? (
        <ResourceScanCompare items={list} selectedId={selectedId} otherId={otherId} onChange={onCompareChange} getDetail={getDetail} />
      ) : (
        <Section slot="prs-history" icon={History} title={t('prs.hist.title')} titleFocusable
          description={days ? plural(t, 'prs.hist.descDays', limits?.detail_runs ?? 20, formatNumber(limits?.detail_runs ?? 20, locale), formatNumber(days, locale))
            : plural(t, 'prs.hist.desc', limits?.detail_runs ?? 20, formatNumber(limits?.detail_runs ?? 20, locale))}>
          {list.length === 0 ? (
            <p data-slot="prs-history-empty" className="m-0 text-sm text-muted-foreground">{t('prs.hist.none')}</p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
              {list.map((s) => <HistoryRow key={s.id} s={s} sel={s.id === selectedId} t={t} locale={locale}
                onClick={() => (s.status === 'RUNNING' ? onShowRunning?.(s.id) : onSelect?.(s.id))} />)}
            </ul>
          )}
        </Section>
      )}
    </div>
  )
}

function HistoryRow({ s, sel, t, locale, onClick }) {
  const letter = gradeLetter(s.grade, s.grade_letter)
  const done = s.status === 'DONE'
  const capped = done && isCapped(s)
  const when = s.started_at ? formatDate(s.started_at) : '—'
  const objects = done ? (Number(s.request_count) || 0) + (Number(s.document_count) || 0) + (Number(s.link_count) || 0) : null
  const by = s.requested_by ? t('prs.hist.by', s.requested_by) : t('prs.hist.byUnknown')
  const statusText = t(statusKey(s))
  const facts = done
    ? [t('prs.hist.load', formatMs(s.load_ms, locale)), t('prs.hist.size', formatSize(s.page_bytes, { locale })),
      plural(t, 'prs.hist.objects', objects, formatNumber(objects, locale))]
    : s.status === 'RUNNING'
      ? [plural(t, 'prs.run.progress', s.progress?.total ?? 0, formatNumber(s.progress?.done ?? 0, locale), formatNumber(s.progress?.total ?? 0, locale))]
      : [s.error || t('prs.hist.noResult')]
  const grade = done ? (capped ? t('prs.hist.gradeShortCapped', s.grade ?? '—') : t('prs.hist.gradeShort', s.grade ?? '—')) : statusText
  // Görünen bilginin tamamı (r177 H15 — eski etiket yalnız tarih + not okuyordu)
  const aria = [t('prs.hist.openFor', when, grade), by, ...facts, sel ? t('prs.hist.showing') : null].filter(Boolean).join(' · ')
  return (
    <li>
      <Button type="button" variant={sel ? 'secondary' : 'ghost'} data-slot="prs-history-row" data-id={s.id}
        data-status={s.status} aria-current={sel ? 'true' : undefined} onClick={onClick} aria-label={aria}
        className={cn('h-auto w-full justify-start border px-3 py-2 text-left font-normal whitespace-normal', sel ? 'border-primary/40' : 'border-border', TOUCH)}>
        <span className="grid w-full min-w-0 grid-cols-1 gap-1.5 sm:grid-cols-[minmax(0,1.4fr)_auto_minmax(0,1fr)] sm:items-center sm:gap-3">
          <span className="flex min-w-0 flex-col gap-0.5">
            <span className="text-sm font-semibold text-foreground tabular-nums">{when}</span>
            <span className={cn('text-xs text-muted-foreground', WRAP)}>{by}</span>
          </span>
          <span className="flex flex-wrap items-center gap-1.5">
            {done ? (
              <ToneBadge tone={scanTone(s)} className={cn(CHIP, 'font-bold tabular-nums')} data-slot="prs-history-grade">
                {s.grade ?? '—'}{letter ? ` · ${letter}` : ''}
              </ToneBadge>
            ) : (
              <ToneBadge tone={isCancelled(s) ? 'muted' : (STATUS_TONE[s.status] || 'muted')} className={cn(CHIP, 'gap-1')} data-slot="prs-history-status">
                {s.status === 'RUNNING' && <Spinner size={10} inline decorative />}
                {statusText}
              </ToneBadge>
            )}
            {capped && <ToneBadge tone="muted" className={CHIP} data-slot="prs-history-capped">{t('prs.hist.capped')}</ToneBadge>}
            {sel && <ToneBadge tone="info" className={CHIP}>{t('prs.hist.showing')}</ToneBadge>}
          </span>
          <span className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground tabular-nums sm:justify-end">
            {facts.map((f, i) => <span key={i} className={WRAP}>{f}</span>)}
          </span>
        </span>
      </Button>
    </li>
  )
}
