import { History } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { formatMs, formatNumber, formatSize, gradeLetter, gradeTone, plural } from './resourceScanModel.js'
import { CHIP, Section, TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

const STATUS_TONE = { DONE: 'success', RUNNING: 'info', FAILED: 'danger', INTERRUPTED: 'warning' }

/**
 * Kaynak analizi → GEÇMİŞ (2026-10-31): her analiz (yeniden eskiye) — ne zaman, kim başlattı, not, tahmini yüklenme,
 * sayfa boyutu, nesne sayısı, durum. Satıra basınca o analiz panoda açılır; seçili satır `aria-current`. Tek duyarlı
 * liste (telefonda satır sarar, geniş ekranda sütun hizalı) — tablo ↔ kart ikiliği yok.
 */
export default function ResourceScanHistory({ items, selectedId, onSelect, limits }) {
  const t = useT()
  const locale = useDateLocale()
  const list = Array.isArray(items) ? items : []
  return (
    <Section slot="prs-history" icon={History} title={t('prs.hist.title')}
      description={plural(t, 'prs.hist.desc', limits?.detail_runs ?? 20, formatNumber(limits?.detail_runs ?? 20, locale))}>
      {list.length === 0 ? (
        <p data-slot="prs-history-empty" className="m-0 text-sm text-muted-foreground">{t('prs.hist.none')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
          {list.map((s) => {
            const sel = s.id === selectedId
            const letter = gradeLetter(s.grade, s.grade_letter)
            const done = s.status === 'DONE'
            const when = s.started_at ? formatDate(s.started_at) : '—'
            const objects = done ? (Number(s.request_count) || 0) + (Number(s.document_count) || 0) + (Number(s.link_count) || 0) : null
            return (
              <li key={s.id}>
                <Button type="button" variant={sel ? 'secondary' : 'ghost'} data-slot="prs-history-row" data-id={s.id}
                  data-status={s.status} aria-current={sel ? 'true' : undefined} onClick={() => onSelect?.(s.id)}
                  aria-label={t('prs.hist.openFor', when, s.status === 'DONE' ? t('prs.hist.gradeShort', s.grade ?? '—') : t(`prs.status.run.${STATUS_TONE[s.status] ? s.status : 'UNKNOWN'}`))}
                  className={cn('h-auto w-full justify-start border px-3 py-2 text-left font-normal whitespace-normal',
                    sel ? 'border-primary/40' : 'border-border', TOUCH)}>
                  <span className="grid w-full min-w-0 grid-cols-1 gap-1.5 sm:grid-cols-[minmax(0,1.4fr)_auto_minmax(0,1fr)] sm:items-center sm:gap-3">
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <span className="text-sm font-semibold text-foreground tabular-nums">{when}</span>
                      <span className={cn('text-xs text-muted-foreground', WRAP)}>
                        {s.requested_by ? t('prs.hist.by', s.requested_by) : t('prs.hist.byUnknown')}
                      </span>
                    </span>
                    <span className="flex flex-wrap items-center gap-1.5">
                      {done ? (
                        <ToneBadge tone={gradeTone(letter)} className={cn(CHIP, 'font-bold tabular-nums')} data-slot="prs-history-grade">
                          {s.grade ?? '—'}{letter ? ` · ${letter}` : ''}
                        </ToneBadge>
                      ) : (
                        <ToneBadge tone={STATUS_TONE[s.status] || 'muted'} className={cn(CHIP, 'gap-1')} data-slot="prs-history-status">
                          {s.status === 'RUNNING' && <Spinner size={10} inline decorative />}
                          {t(`prs.status.run.${STATUS_TONE[s.status] ? s.status : 'UNKNOWN'}`)}
                        </ToneBadge>
                      )}
                      {sel && <ToneBadge tone="info" className={CHIP}>{t('prs.hist.showing')}</ToneBadge>}
                    </span>
                    <span className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground tabular-nums sm:justify-end">
                      {done ? (
                        <>
                          <span>{t('prs.hist.load', formatMs(s.load_ms, locale))}</span>
                          <span>{t('prs.hist.size', formatSize(s.page_bytes, { locale }))}</span>
                          <span>{plural(t, 'prs.hist.objects', objects, formatNumber(objects, locale))}</span>
                        </>
                      ) : s.status === 'RUNNING' ? (
                        <span>{plural(t, 'prs.run.progress', s.progress?.total ?? 0, formatNumber(s.progress?.done ?? 0, locale), formatNumber(s.progress?.total ?? 0, locale))}</span>
                      ) : (
                        <span className={WRAP}>{s.error || t('prs.hist.noResult')}</span>
                      )}
                    </span>
                  </span>
                </Button>
              </li>
            )
          })}
        </ul>
      )}
    </Section>
  )
}
