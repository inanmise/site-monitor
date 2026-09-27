import { useState, useEffect } from 'react'
import { Activity } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

const RANGE_OPTIONS = [1, 7, 15, 30]

/** Kova durum dolguları (eski `.hb-tl-ok/-partial/-low/-missing`) ve ayrıntı metni tonları. */
const CELL_BG = { ok: 'bg-emerald-500', partial: 'bg-amber-500', low: 'bg-orange-500', missing: 'bg-red-600' }
/** Aynı dolgu üzerine gelince de korunur (ghost Button'ın `hover:bg-accent`'ı ezilir). */
const CELL_HOVER = { ok: 'hover:bg-emerald-500', partial: 'hover:bg-amber-500', low: 'hover:bg-orange-500', missing: 'hover:bg-red-600' }
const STATUS_TEXT = { ok: 'text-emerald-600 dark:text-emerald-400', partial: 'text-amber-600 dark:text-amber-400', low: 'text-orange-600 dark:text-orange-400', missing: 'text-red-600 dark:text-red-400' }

/**
 * Heartbeat geçmişi — zaman çizelgesi ızgarası. Çizim shadcn: ModalShell (Dialog; Escape/odak/scrim
 * kabukta), aralık SegmentedControl, kovalar shadcn Button (aria-pressed), ayrıntı Card.
 * Test kancaları: `data-hb-cell` + `data-status`, seçili kova `aria-pressed="true"`, kayıp işareti `data-hb-x`.
 */
export default function HeartbeatHistoryModal({ onClose }) {
  const t = useT()
  const [rangeDays, setRangeDays] = useState(1)
  const [timeline, setTimeline] = useState(null)
  const [loading, setLoading] = useState(true)
  const [hovered, setHovered] = useState(null)
  const [selected, setSelected] = useState(null)
  const [loadError, setLoadError] = useState(null)

  // .catch YOKTU: request() ag hatasinda throw eder, promise reject olunca setLoading(false)
  // HIC calismiyor ve modal SONSUZA KADAR "yukleniyor" kaliyordu — kullanicinin tek cikisi
  // modali kapatmakti, hata hakkinda hicbir sey gormeden.
  useEffect(() => {
    setLoading(true)
    setSelected(null)
    api.admin.getHeartbeatTimeline(rangeDays)
      .then(res => {
        if (res?.success) { setTimeline(res.data); setLoadError(null) }
        else setLoadError(res?.error || 'load failed')
      })
      .catch(e => setLoadError(e?.message || 'network error'))
      .finally(() => setLoading(false))
  }, [rangeDays])

  const buckets = timeline?.buckets || []

  function statusOf(b) {
    if (b.received === 0) return 'missing'
    const ratio = b.received / Math.max(1, b.expected)
    if (ratio >= 0.9) return 'ok'
    if (ratio >= 0.5) return 'partial'
    return 'low'
  }

  const totalReceived = buckets.reduce((s, b) => s + b.received, 0)
  const totalExpected = buckets.reduce((s, b) => s + b.expected, 0)
  const bucketMin = timeline?.bucket_minutes || 1

  function endOf(b) {
    const utcIso = b.start.endsWith('Z') || b.start.includes('+') ? b.start : b.start + 'Z'
    const d = new Date(utcIso)
    d.setUTCMinutes(d.getUTCMinutes() + bucketMin)
    return d.toISOString().slice(0, 19)
  }

  function statusLabel(s) {
    if (s === 'ok')      return t('health.hbLegOk')
    if (s === 'partial') return t('health.hbLegPartial')
    if (s === 'low')     return t('health.hbLegPartial')
    return t('health.hbLegMissing')
  }

  const legend = (s, label) => (
    <span className="inline-flex items-center gap-1.5">
      <span aria-hidden="true" className={cn('inline-block size-3.5 rounded-[3px]', CELL_BG[s])} />{label}
    </span>
  )

  return (
    <ModalShell open onClose={onClose} title={t('health.hbHistoryTitle')} icon={Activity} size="lg" scrollBody>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <SegmentedControl value={rangeDays} onChange={setRangeDays} ariaLabel={t('sml.rangeLabel')}
          options={RANGE_OPTIONS.map((d) => ({ value: d, label: t('forecast.chartDays', d) }))} />
        <div className="flex flex-wrap items-center gap-3.5 text-[.82em] text-muted-foreground">
          {legend('ok', t('health.hbLegOk'))}
          {legend('partial', t('health.hbLegPartial'))}
          {legend('missing', t('health.hbLegMissing'))}
        </div>
      </div>

      {loading ? (
        <LoadingBlock label={t('sys.loading')} />
      ) : loadError ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert">
          {String(loadError)}
        </AlertBanner>
      ) : (
        <>
          {/* Telefonda 12, geniş ekranda 24 sütun — kova dokunulabilir boyutta kalsın. */}
          <div data-slot="hb-timeline" className="grid grid-cols-12 gap-0.5 rounded-md border bg-muted/40 p-1.5 sm:grid-cols-24 border-border">
            {buckets.map((b, i) => {
              const s = statusOf(b)
              const isSelected = selected?.i === i
              return (
                <Button
                  key={i}
                  type="button"
                  variant="ghost"
                  data-hb-cell=""
                  data-status={s}
                  aria-pressed={isSelected}
                  aria-label={`${t('health.hbHistoryTitle')} — ${formatDate(b.start)} — ${statusLabel(s)}`}
                  title={`${formatDate(b.start)} · ${b.received} / ${b.expected} ${t('health.hbBeats')}`}
                  className={cn('relative aspect-square h-auto min-h-0 w-full min-w-0 rounded-[2px] p-0 text-white transition-transform hover:z-[5] hover:scale-125 hover:text-white motion-reduce:transition-none motion-reduce:hover:scale-100',
                    CELL_BG[s], CELL_HOVER[s],
                    isSelected && 'z-[6] scale-125 outline-2 outline-offset-1 outline-primary')}
                  onMouseEnter={() => setHovered(b)}
                  onMouseLeave={() => setHovered(null)}
                  onClick={() => setSelected(prev => prev?.i === i ? null : { i, b, s })}
                >
                  {s === 'missing' && <span data-hb-x="" aria-hidden="true" className="text-[11px] leading-none font-bold">×</span>}
                </Button>
              )
            })}
          </div>
          {!selected && hovered && (
            <div className="mt-2.5 text-[.85em]">
              <strong>{formatDate(hovered.start)}</strong>
              {' · '}
              {hovered.received} / {hovered.expected} {t('health.hbBeats')}
            </div>
          )}
          <div className="mt-2 text-right text-[.82em] text-muted-foreground">
            {t('health.hbTotal')}: {totalReceived} / {totalExpected} {t('health.hbBeats')}
          </div>
          {selected && (() => {
            const b = selected.b
            const missed = Math.max(0, b.expected - b.received)
            const lossPct = b.expected > 0 ? Math.round((missed / b.expected) * 100) : 0
            const cell = (label, value, cls) => (
              <div className="flex flex-col gap-0.5">
                <span className="text-[.72em] font-semibold tracking-wide text-muted-foreground uppercase">{label}</span>
                <b className={cn('text-[1.15em] tabular-nums', cls)}>{value}</b>
              </div>
            )
            return (
              <Card data-slot="hb-detail" data-status={selected.s} className="sticky bottom-0 z-[2] mt-3 gap-2 px-3.5 py-3 text-[.88em] shadow-md">
                <div className="text-[.92em]">
                  <strong>{t('health.hbSelectedRange')}:</strong>
                  {' '}
                  {formatDate(b.start)} — {formatDate(endOf(b))}
                </div>
                <div className="grid grid-cols-2 gap-x-[18px] gap-y-2.5 sm:grid-cols-4">
                  {cell(t('health.hbDetailExpected'), b.expected)}
                  {cell(t('health.hbDetailReceived'), b.received)}
                  {cell(t('health.hbDetailMissed'), `${missed} (%${lossPct})`)}
                  {cell(t('health.hbDetailStatus'), statusLabel(selected.s), STATUS_TEXT[selected.s])}
                </div>
              </Card>
            )
          })()}
        </>
      )}
    </ModalShell>
  )
}
