import { useCallback, useEffect, useRef, useState } from 'react'
import { Camera, ChevronDown, FileDiff, Info, Lock, Minus, Plus, RotateCcw, RotateCw } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { bytesText, maskList } from '../content/contentChecksModel.js'
import { changedLineCount, summaryOf } from './contentDetailModel.js'
import { changeNoteOf } from '../keywordCardModel.js'

const statusOf = (r) => Number(r?.status ?? errorInfoOf(r)?.status)
const codeOf = (r) => r?.code ?? errorInfoOf(r)?.code

/**
 * Değişim farkı — kaldırılan (temelde olup şimdi olmayan) ve eklenen (yeni) satırlar; telefonda alt alta, geniş ekranda
 * yan yana. Satırlar eş aralıklı, SARAR (kırpılmaz). Listelenmeyen fazlalık "+N satır daha"; temel 64 000 karakterde
 * kesilmişse not. Kancalar: `kw-change-diff`, `kw-change-removed`, `kw-change-added`, `kw-change-truncated`.
 */
export function ChangeDiff({ summary }) {
  const t = useT()
  const s = summaryOf(summary)
  if (!s) return null
  const empty = !s.removed.length && !s.added.length
  return (
    <div data-slot="kw-change-diff" className="flex min-w-0 flex-col gap-2">
      {empty ? (
        <p className="m-0 text-xs text-muted-foreground">{t('keyword.change.noLineDiff')}</p>
      ) : (
        <div className="grid min-w-0 grid-cols-1 gap-2 md:grid-cols-2">
          <DiffList slot="kw-change-removed" tone="removed" title={t('keyword.change.removed', s.removedTotal)} lines={s.removed} more={s.removedMore} />
          <DiffList slot="kw-change-added" tone="added" title={t('keyword.change.added', s.addedTotal)} lines={s.added} more={s.addedMore} />
        </div>
      )}
      {s.truncated && <p data-slot="kw-change-truncated" className="m-0 text-xs text-amber-700 dark:text-amber-300">{t('keyword.change.truncated')}</p>}
    </div>
  )
}

function DiffList({ slot, tone, title, lines, more }) {
  const t = useT()
  const Icon = tone === 'removed' ? Minus : Plus
  return (
    <section data-slot={slot} aria-label={title}
      className={cn('flex min-w-0 flex-col gap-1 rounded-md border p-2',
        tone === 'removed' ? 'border-destructive/30 bg-destructive/5' : 'border-success/30 bg-success/5')}>
      <span className={cn('text-xs font-semibold', tone === 'removed' ? 'text-destructive' : 'text-success')}>{title}</span>
      {lines.length === 0 ? (
        <span className="text-xs text-muted-foreground">—</span>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
          {lines.map((l, i) => (
            <li key={i} className="flex min-w-0 items-start gap-1.5 font-mono text-xs leading-snug">
              <Icon aria-hidden="true" className={cn('mt-0.5 size-3 shrink-0', tone === 'removed' ? 'text-destructive' : 'text-success')} />
              <span className="min-w-0 whitespace-pre-wrap [overflow-wrap:anywhere]">{l || ' '}</span>
            </li>
          ))}
        </ul>
      )}
      {more > 0 && <span className="text-xs text-muted-foreground">{t('keyword.change.more', more)}</span>}
    </section>
  )
}

/**
 * İÇERİK DEĞİŞİMİ paneli (kw paket D2) — `GET /keyword/{id}/change`. Temel bilgisi (ne zaman, kim, otomatik / elle,
 * boyut, satır), son karşılaştırma (aynı / farklı / henüz yok), fark görünümü ve iki onaylı eylem: "Yeni temel al" (sayfa
 * canlı okunur; açık değişim alarmı kapanır) ve "Temeli sıfırla" (sonraki zamanlanmış kontrol yeniden alır). Eylemler
 * yalnız `canEdit` ile; dönüş metni sunucudan (409 kapalı / sayfa okunamadı). Okunamayan durum "okunamadı" + Yeniden dene;
 * eski sunucu (404 / 405) "desteklemiyor" — asla "temel yok" DEĞİL.
 */
export default function KeywordChangePanel({ monitorId, canEdit = false, onChanged }) {
  const t = useT()
  const { lang } = useLanguage()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const [state, setState] = useState({ status: 'loading' })
  const [busy, setBusy] = useState(null)   // 'take' | 'clear'
  const [actionError, setActionError] = useState(null)
  const [textOpen, setTextOpen] = useState(false)
  const seq = useRef(0)
  const alive = useRef(true)
  // Bağlanınca YENİDEN true: StrictMode (geliştirme) efektleri bağla → sök → yeniden bağla sırasıyla koşar; yalnız sökümde
  // false yazılsaydı panel sonsuza dek "yükleniyor"da kalırdı (e2e'de yakalandı)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ status: 'loading', data: s.data ?? null }))
    try {
      const r = await api.monitoring.keywordChange(monitorId)
      if (my !== seq.current || !alive.current) return
      if (r?.success) setState({ status: 'ok', data: r.data || {} })
      else if (statusOf(r) === 404 || statusOf(r) === 405) setState({ status: 'unsupported' })
      else setState((s) => ({ status: 'error', error: r?.error || t('keyword.change.loadFailed'), data: s.data ?? null }))
    } catch (e) {
      if (my === seq.current && alive.current) setState((s) => ({ status: 'error', error: e?.message || t('keyword.change.loadFailed'), data: s.data ?? null }))
    }
  }, [monitorId, t])

  useEffect(() => { load() }, [load])

  async function act(kind) {
    const take = kind === 'take'
    const ok = await showConfirm({
      title: t(take ? 'keyword.change.takeTitle' : 'keyword.change.clearTitle'),
      message: t(take ? 'keyword.change.takeBody' : 'keyword.change.clearBody'),
      confirmText: t(take ? 'keyword.change.takeConfirm' : 'keyword.change.clearConfirm'),
      variant: take ? 'warning' : 'danger',
    })
    if (!ok) return
    setBusy(kind)
    setActionError(null)
    try {
      const r = take ? await api.monitoring.takeKeywordBaseline(monitorId) : await api.monitoring.clearKeywordBaseline(monitorId)
      if (!alive.current) return
      if (r?.success) {
        const closed = r.data?.closed_alarm === true
        toast.success(t(take ? (closed ? 'keyword.change.takenClosed' : 'keyword.change.taken') : (closed ? 'keyword.change.clearedClosed' : 'keyword.change.cleared')))
        onChanged?.()
        await load()
      } else if (codeOf(r) === 'KEYWORD_BASELINE_FETCH_FAILED') {
        // Sunucu ayrıca NEDEN ölçülemediğini kodla söyler (`change_skipped`) — metnine eklenir, ham kod gösterilmez
        const note = changeNoteOf(r?.change_skipped ?? r?.data?.change_skipped)
        const why = note ? ` ${t('keyword.change.notMeasured', t(`keyword.change.note.${note}`))}` : ''
        setActionError(`${t('keyword.change.fetchFailed', r?.error || '')}${why}`)
      } else {
        setActionError(t('keyword.change.actionFailed', r?.error || ''))
      }
    } catch (e) {
      if (alive.current) setActionError(t('keyword.change.actionFailed', e?.message || ''))
    } finally {
      if (alive.current) setBusy(null)
    }
  }

  const data = state.data
  // Sunucu yanıtındaki `can_edit` (be2) karar verir; alan yoksa (eski sunucu) satır bayrağından gelen `canEdit`
  const editable = typeof data?.can_edit === 'boolean' ? data.can_edit : canEdit
  let body
  if (state.status === 'unsupported') {
    body = <div data-slot="kw-change-unsupported"><StatusBlock tone="neutral" title={t('keyword.change.unsupported')} className="py-5" /></div>
  } else if (state.status === 'error' && !data) {
    body = (
      <div data-slot="kw-change-error">
        <AlertBanner tone="danger" role="alert" className="mb-0" title={t('keyword.change.loadFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={load} className="pointer-coarse:h-10"><RotateCw aria-hidden="true" />{t('hist.retry')}</Button>}>
          {String(state.error)}
        </AlertBanner>
      </div>
    )
  } else if (!data) {
    body = <div data-slot="kw-change-skeleton" className="flex flex-col gap-2"><Skeleton className="h-5 w-2/3" /><Skeleton className="h-16 w-full" /></div>
  } else if (data.enabled === false) {
    body = <div data-slot="kw-change-disabled"><StatusBlock tone="neutral" title={t('keyword.change.disabled')} className="py-5" /></div>
  } else {
    const b = data.baseline && typeof data.baseline === 'object' ? data.baseline : null
    const latest = data.latest && typeof data.latest === 'object' ? data.latest : null
    // Son kontrolde değişim ÖLÇÜLMEDİ (hata sayfası, boş gövde, seçici öğe bulamadı …): son karşılaştırmadan YENİYSE "temelle
    // aynıydı" yeşili gösterilmez — nötr not (kw son inceleme O-3 / D-7). Açık "değişti" uyarısı kalır (alarm sürer).
    const skNote = data.skipped && typeof data.skipped === 'object' ? changeNoteOf(data.skipped.note) : null
    const sk = skNote ? { checked_at: data.skipped.checked_at, note: skNote } : null
    const skippedNewer = !!sk && (!latest || !(Date.parse(latest.checked_at) > Date.parse(sk.checked_at)))
    const summary = latest?.summary ?? null
    const n = changedLineCount(summaryOf(summary))
    const masks = maskList(data.mask)
    body = (
      <div className="flex min-w-0 flex-col gap-3">
        {(masks.length > 0 || (data.selector && String(data.selector).trim())) && (
          <p data-slot="kw-change-settings" className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {[masks.length ? t('keyword.change.maskOn', masks.map((m) => t(`keyword.content.mask.${m}`)).join(', ')) : null,
              data.selector && String(data.selector).trim() ? t('keyword.change.selectorOn', String(data.selector).trim()) : null].filter(Boolean).join(' · ')}
          </p>
        )}
        {skippedNewer && (
          <p data-slot="kw-change-skipped" data-note={sk.note} className="m-0 flex min-w-0 items-start gap-1.5 text-sm text-muted-foreground [overflow-wrap:anywhere]">
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span>{sk.checked_at ? t('keyword.change.skippedAt', formatDate(sk.checked_at), t(`keyword.change.note.${sk.note}`))
              : t(`keyword.change.note.${sk.note}`)}</span>
          </p>
        )}
        {!b ? (
          <AlertBanner tone="info" className="mb-0" icon={Camera}><span data-slot="kw-change-no-baseline">{t('keyword.change.noBaseline')}</span></AlertBanner>
        ) : (
          <>
            <dl data-slot="kw-change-baseline" className="m-0 grid min-w-0 grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
              <dt className="font-semibold text-muted-foreground">{t('keyword.change.baseline')}</dt>
              <dd className="m-0 min-w-0 [overflow-wrap:anywhere]">
                {[b.taken_at ? t('keyword.change.takenAt', formatDate(b.taken_at)) : null,
                  b.taken_by_name ? t('keyword.change.takenBy', b.taken_by_name) : null,
                  b.source === 'AUTO' || b.source === 'MANUAL' ? t(`keyword.change.source.${b.source}`) : null].filter(Boolean).join(' · ')}
              </dd>
              <dt className="font-semibold text-muted-foreground">{t('keyword.change.size')}</dt>
              <dd className="m-0 tabular-nums">
                {[typeof b.bytes === 'number' ? bytesText(b.bytes, lang) : null, typeof b.line_count === 'number' ? t('keyword.change.lines', b.line_count) : null]
                  .filter(Boolean).join(' · ') || '—'}
              </dd>
            </dl>
            {latest == null ? (
              <p data-slot="kw-change-latest" data-state="none" className="m-0 text-sm text-muted-foreground">{t('keyword.change.latestNone')}</p>
            ) : latest.changed === true ? (
              <AlertBanner tone="warning" className="mb-0" icon={FileDiff}
                title={t('keyword.change.latestChanged', formatDate(latest.checked_at))}>
                <span data-slot="kw-change-latest" data-state="changed">{n != null ? t('keyword.change.changedLines', n) : null}</span>
              </AlertBanner>
            ) : latest.changed === false && !skippedNewer ? (
              <p data-slot="kw-change-latest" data-state="same" className="m-0 text-sm text-success">{t('keyword.change.latestSame', formatDate(latest.checked_at))}</p>
            ) : latest.changed === false ? (
              <p data-slot="kw-change-latest" data-state="older" className="m-0 text-sm text-muted-foreground">{t('keyword.change.latestSameOlder', formatDate(latest.checked_at))}</p>
            ) : (
              <p data-slot="kw-change-latest" data-state="unknown" className="m-0 text-sm text-muted-foreground">{t('keyword.change.latestUnknown')}</p>
            )}
            {latest?.changed === true && summary != null && <ChangeDiff summary={summary} />}
            {/* Temel metni yalnız düzenleyebilene döner (sunucu `text: null` + `text_hidden`) — bilinmiyor ≠ boş */}
            {b.text_hidden === true && (
              <p data-slot="kw-change-text-hidden" className="m-0 flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('keyword.change.textHidden')}
              </p>
            )}
            {typeof b.text === 'string' && b.text.length > 0 && (
              <Collapsible open={textOpen} onOpenChange={setTextOpen}>
                <CollapsibleTrigger asChild>
                  <Button type="button" variant="ghost" size="sm" data-slot="kw-change-text-toggle" aria-expanded={textOpen ? 'true' : 'false'}
                    className="h-auto gap-1 px-1.5 py-1 text-xs font-semibold text-primary hover:text-primary pointer-coarse:min-h-10">
                    {textOpen ? t('keyword.change.hideText') : t('keyword.change.showText')}
                    <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', textOpen && 'rotate-180')} />
                  </Button>
                </CollapsibleTrigger>
                <CollapsibleContent>
                  <p className="m-0 mb-1 text-xs text-muted-foreground">{t('keyword.change.textNote')}</p>
                  <pre data-slot="kw-change-text" className="m-0 max-h-72 min-w-0 overflow-y-auto rounded-md border bg-muted/50 px-3 py-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">
                    {b.text}
                  </pre>
                  {b.text_truncated && <p className="m-0 mt-1 text-xs text-amber-700 dark:text-amber-300">{t('keyword.change.truncated')}</p>}
                </CollapsibleContent>
              </Collapsible>
            )}
          </>
        )}
        {actionError && <AlertBanner tone="danger" role="alert" className="mb-0"><span data-slot="kw-change-action-error">{actionError}</span></AlertBanner>}
        {editable && (
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" data-slot="kw-change-take" onClick={() => act('take')} disabled={!!busy} aria-busy={busy === 'take' || undefined}
              className="pointer-coarse:h-10">
              <Camera aria-hidden="true" />{busy === 'take' ? t('keyword.change.taking') : t('keyword.change.take')}
            </Button>
            {b && (
              <Button type="button" size="sm" variant="outline" data-slot="kw-change-clear" onClick={() => act('clear')} disabled={!!busy}
                aria-busy={busy === 'clear' || undefined} className="pointer-coarse:h-10">
                <RotateCcw aria-hidden="true" />{t('keyword.change.clear')}
              </Button>
            )}
          </div>
        )}
        {state.status === 'error' && <p className="m-0 text-xs text-destructive">{t('keyword.change.refreshFailed')}</p>}
      </div>
    )
  }

  return (
    <section data-slot="kw-change-panel" aria-label={t('keyword.change.title')} className="flex min-w-0 flex-col gap-2">
      <h4 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><FileDiff aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('keyword.change.title')}</h4>
      {body}
    </section>
  )
}
