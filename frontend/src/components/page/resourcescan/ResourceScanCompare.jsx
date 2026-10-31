import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeftRight, CircleAlert, RefreshCw, TriangleAlert } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import {
  COMPARE_LIST_MAX, SEVERITIES, SEVERITY_TONE, categoryLabel, compareScans, comparableScans, deltasComparable, formatMs, formatNumber,
  formatPct, formatSize, gradeLetter, itemSizeText, plural, recTextKey, recommendationArgs,
} from './resourceScanModel.js'
import { CHIP, DeltaChip, Section, signed, TOUCH, UrlText, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'

/** KPI → biçimleyici ve etiket anahtarı. */
const KPI_LABEL = {
  grade: 'prs.grade', load: 'prs.kpi.load', ttfb: 'prs.kpi.ttfb', size: 'prs.kpi.size', requests: 'prs.kpi.requests',
  images: 'prs.kpi.images', broken: 'prs.kpi.broken',
}

/**
 * Kaynak analizi → İKİ ANALİZİ KARŞILAŞTIR (r177, İSTEMCİDE — sunucu ucu yok): iki tamamlanmış analizin ayrıntısı okunur;
 * not / süre / boyut / istek farkı, tür bazında bayt farkı, yeni çıkan ve çözülen öneriler, eklenen / kaldırılan /
 * büyüyen nesneler (adres eşleşmesi). Nesne ayrıntısı yalnız son 20 analizde saklandığından daha eskisiyle yalnız özet
 * karşılaştırılır (söylenir). Not algoritması sürümleri farklıysa not farkı yanıltıcı olabilir — uyarı. Yön her zaman
 * ESKİ → YENİ (başlangıç zamanına göre). Seçimler URL'de (`prs` = seçili, `prs_b` = karşılaştırılan).
 */
export default function ResourceScanCompare({ items, selectedId, otherId, onChange, getDetail }) {
  const t = useT()
  const locale = useDateLocale()
  const options = useMemo(() => comparableScans(items), [items])
  const a = options.some((s) => s.id === selectedId) ? selectedId : options[0]?.id ?? null
  const fallbackB = options.find((s) => s.id !== a)?.id ?? null
  const b = otherId != null && otherId !== a && options.some((s) => s.id === otherId) ? otherId : fallbackB
  const [state, setState] = useState({ key: null, status: 'idle', a: null, b: null, error: null })
  const [retry, setRetry] = useState(0)
  const seq = useRef(0)
  const key = `${a}|${b}`

  useEffect(() => {
    if (a == null || b == null) return undefined
    const my = ++seq.current
    setState({ key, status: 'loading', a: null, b: null, error: null })
    Promise.all([getDetail(a), getDetail(b)]).then(([da, db]) => {
      if (my !== seq.current) return
      if (!da?.scan || !db?.scan) setState({ key, status: 'error', a: null, b: null, error: (!da?.scan ? da : db) || null })
      else setState({ key, status: 'ok', a: da, b: db, error: null })
    }).catch((e) => { if (my === seq.current) setState({ key, status: 'error', a: null, b: null, error: e }) })
    return () => { seq.current += 1 }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, retry])

  const cmp = useMemo(() => (state.status === 'ok' && state.key === key ? compareScans(
    { scan: state.a.scan, analysis: state.a.analysis, items: state.a.items_kept === false ? null : state.a.items },
    { scan: state.b.scan, analysis: state.b.analysis, items: state.b.items_kept === false ? null : state.b.items },
  ) : null), [state, key])

  const label = (s) => `${formatDate(s.started_at)} · ${s.grade ?? '—'}${gradeLetter(s.grade, s.grade_letter) ? ` ${gradeLetter(s.grade, s.grade_letter)}` : ''}`

  return (
    <Section slot="prs-compare" icon={ArrowLeftRight} title={t('prs.cmp.title')} description={t('prs.cmp.desc')} titleFocusable>
      {options.length < 2 ? (
        <p data-slot="prs-cmp-few" className="m-0 text-sm text-muted-foreground">{t('prs.cmp.needTwo')}</p>
      ) : (
        <>
          <div data-slot="prs-cmp-pick" className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
            <label className="flex min-w-0 flex-col gap-1 text-xs font-medium text-muted-foreground">
              <span>{t('prs.cmp.pickA')}</span>
              <NativeSelect value={a ?? ''} data-slot="prs-cmp-a" className="w-full pointer-coarse:h-10"
                onChange={(e) => onChange?.({ scanId: Number(e.target.value), otherId: Number(e.target.value) === b ? null : b })}>
                {options.map((s) => <NativeSelectOption key={s.id} value={s.id}>{label(s)}</NativeSelectOption>)}
              </NativeSelect>
            </label>
            <label className="flex min-w-0 flex-col gap-1 text-xs font-medium text-muted-foreground">
              <span>{t('prs.cmp.pickB')}</span>
              <NativeSelect value={b ?? ''} data-slot="prs-cmp-b" className="w-full pointer-coarse:h-10"
                onChange={(e) => onChange?.({ scanId: a, otherId: Number(e.target.value) })}>
                {options.filter((s) => s.id !== a).map((s) => <NativeSelectOption key={s.id} value={s.id}>{label(s)}</NativeSelectOption>)}
              </NativeSelect>
            </label>
          </div>
          {state.status === 'loading' || state.key !== key ? (
            <LoadingBlock label={t('prs.cmp.loading')} className="py-6" />
          ) : state.status === 'error' ? (
            <div data-slot="prs-cmp-error">
              <StatusBlock tone="danger" icon={CircleAlert} title={t('prs.cmp.unreadable')}
                description={state.error?.error || state.error?.message || t('prs.detailUnreadableBody')} details={state.error || undefined}
                actions={<Button type="button" variant="outline" className={TOUCH} onClick={() => setRetry((n) => n + 1)}>
                  <RefreshCw aria-hidden="true" />{t('prs.retry')}</Button>} />
            </div>
          ) : cmp ? (
            <CompareBody cmp={cmp} t={t} locale={locale} />
          ) : null}
        </>
      )}
    </Section>
  )
}

function CompareBody({ cmp, t, locale }) {
  const o = cmp.older.scan
  const n = cmp.newer.scan
  const partial = !deltasComparable(n, o)
  const fmt = {
    grade: (v) => formatNumber(v, locale), load: (v) => formatMs(v, locale), ttfb: (v) => formatMs(v, locale),
    size: (v) => formatSize(v, { locale }), requests: (v) => formatNumber(v, locale), images: (v) => formatSize(v, { locale }),
    broken: (v) => formatNumber(v, locale),
  }
  return (
    <div data-slot="prs-cmp-body" className="flex min-w-0 flex-col gap-4">
      <p data-slot="prs-cmp-dir" className={cn('m-0 text-sm text-foreground', WRAP)}>{t('prs.cmp.direction', formatDate(o.started_at), formatDate(n.started_at))}</p>
      {cmp.algoMismatch && (
        <div data-slot="prs-cmp-algo">
          <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert}>{t('prs.cmp.algo', String(cmp.algoOld), String(cmp.algoNew))}</AlertBanner>
        </div>
      )}
      {partial && (
        <div data-slot="prs-cmp-capped"><AlertBanner tone="info" className="mb-0">{t('prs.cmp.capped')}</AlertBanner></div>
      )}
      <ul data-slot="prs-cmp-kpis" className="m-0 grid min-w-0 list-none grid-cols-1 gap-2 p-0 sm:grid-cols-2 xl:grid-cols-3">
        {cmp.kpis.map((k) => {
          const f = fmt[k.key]
          const d = k.key === 'grade' && cmp.algoMismatch ? null : k.d
          return (
            <li key={k.key} data-slot="prs-cmp-kpi" data-key={k.key} data-direction={d?.direction || undefined}
              className="flex min-w-0 flex-col gap-1 rounded-lg border bg-muted/30 px-3 py-2">
              <span className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{t(KPI_LABEL[k.key])}</span>
              <span className={cn('text-sm tabular-nums', WRAP)}>
                <span className="text-muted-foreground">{f(k.old)}</span>
                <span aria-hidden="true" className="px-1 text-muted-foreground">→</span>
                <span className="sr-only">{t('prs.cmp.to')}</span>
                <span className="font-bold text-foreground">{f(k.now)}</span>
              </span>
              {d && d.direction !== 'same' && (
                <DeltaChip d={d} text={signed(d.diff, f)} srText={t('prs.delta.sr', t(KPI_LABEL[k.key]), signed(d.diff, f))} sameText={t('prs.delta.same')} />
              )}
              {d && d.direction === 'same' && <span className="text-xs text-muted-foreground">{t('prs.delta.same')}</span>}
            </li>
          )
        })}
      </ul>

      <div className="flex min-w-0 flex-col gap-1.5">
        <h4 className="m-0 text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">{t('prs.cmp.categories')}</h4>
        <ul data-slot="prs-cmp-cats" className="m-0 flex list-none flex-col gap-1 p-0">
          {cmp.categories.map((c) => (
            <li key={c.category} data-category={c.category} className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 rounded-md border px-3 py-1.5 text-sm">
              <span className={cn('font-medium', WRAP)}>{categoryLabel(c.category, t)}</span>
              <span className="text-xs tabular-nums text-muted-foreground">
                {formatSize(c.oldBytes, { locale })} → <span className="font-semibold text-foreground">{formatSize(c.newBytes, { locale })}</span>
                {c.diff !== 0 && (
                  <span className={cn('ml-1.5 font-semibold', c.diff > 0 ? 'text-destructive' : 'text-success')}>
                    ({signed(c.diff, (v) => formatSize(v, { locale }))})
                  </span>
                )}
              </span>
            </li>
          ))}
        </ul>
      </div>

      <div className="grid min-w-0 grid-cols-1 gap-3 lg:grid-cols-2">
        <RecList slot="prs-cmp-rec-new" title={t('prs.cmp.recNew')} recs={cmp.recNew} empty={t('prs.cmp.recNewNone')} t={t} locale={locale} />
        <RecList slot="prs-cmp-rec-resolved" title={t('prs.cmp.recResolved')} recs={cmp.recResolved} empty={t('prs.cmp.recResolvedNone')} t={t} locale={locale} />
      </div>

      {cmp.objects ? (
        <div data-slot="prs-cmp-objects" className="grid min-w-0 grid-cols-1 gap-3 lg:grid-cols-3">
          <ObjList slot="prs-cmp-added" title={t('prs.cmp.added')} list={cmp.objects.added} t={t} locale={locale} />
          <ObjList slot="prs-cmp-removed" title={t('prs.cmp.removed')} list={cmp.objects.removed} t={t} locale={locale} />
          <ObjList slot="prs-cmp-grown" title={t('prs.cmp.grown')} list={cmp.objects.grown} t={t} locale={locale} grown />
        </div>
      ) : (
        <div data-slot="prs-cmp-no-objects"><AlertBanner tone="info" className="mb-0">{t('prs.cmp.noObjects')}</AlertBanner></div>
      )}
    </div>
  )
}

function RecList({ slot, title, recs, empty, t, locale }) {
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-1.5">
      <h4 className="m-0 text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">{title}</h4>
      {recs.length === 0 ? <p className="m-0 text-sm text-muted-foreground">{empty}</p> : (
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {recs.map((r, i) => {
            const sev = SEVERITIES.includes(r.severity) ? r.severity : 'low'
            return (
              <li key={`${r.code}#${i}`} data-code={r.code} className="flex min-w-0 flex-wrap items-center gap-2 rounded-md border px-3 py-1.5 text-sm">
                <ToneBadge tone={SEVERITY_TONE[sev]} className={CHIP}>{t(`prs.sev.${sev}`)}</ToneBadge>
                <span className={WRAP}>{t(recTextKey(r, 'title'), ...recommendationArgs(r, locale, 'title'))}</span>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

function ObjList({ slot, title, list, t, locale, grown = false }) {
  const shown = list.slice(0, COMPARE_LIST_MAX)
  return (
    <div data-slot={slot} data-count={list.length} className="flex min-w-0 flex-col gap-1.5">
      <h4 className="m-0 text-xs font-semibold tracking-[.04em] text-muted-foreground uppercase">{plural(t, 'prs.cmp.listTitle', list.length, title, formatNumber(list.length, locale))}</h4>
      {list.length === 0 ? <p className="m-0 text-sm text-muted-foreground">{t('prs.cmp.listNone')}</p> : (
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {shown.map((it, i) => (
            <li key={`${it.url}#${i}`} className="flex min-w-0 flex-col gap-0.5 rounded-md border px-3 py-1.5">
              <UrlText url={it.url} t={t} className="text-xs" />
              <span className="text-[11px] text-muted-foreground tabular-nums">
                {categoryLabel(it.category, t)} · {grown
                  ? t('prs.cmp.grownBy', formatSize(it.old_bytes, { locale }), formatSize(it.bytes, { locale }),
                    it.old_bytes > 0 ? formatPct((it.diff / it.old_bytes) * 100, locale, 0) : '—')
                  : itemSizeText(it, t, locale)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {list.length > shown.length && <p className="m-0 text-xs text-muted-foreground">{t('prs.cmp.more', formatNumber(list.length - shown.length, locale))}</p>}
    </div>
  )
}
