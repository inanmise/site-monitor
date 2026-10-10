import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Presentation, RefreshCw, Download, Settings2, CircleAlert, Lock, History, Radio, Info } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import PageHeader from '../ui/PageHeader.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import { SectionCard, StatusBadge, KpiTile, VerdictItem } from './ExecParts.jsx'
import ExecSettingsDialog from './ExecSettingsDialog.jsx'
import {
  EX_CFG, EX_LIVE, EX_MONTH, MONTH_RE, fmtDateTime, fmtPct, isSummaryPayload, monthLabel, monthOptions, orderedSections,
  sectionTitle,
} from './executiveModel.js'

function PageSkeleton({ label }) {
  return (
    <div role="status" aria-live="polite" data-slot="ex-skeleton" className="flex flex-col gap-4">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-40 w-full rounded-xl motion-reduce:animate-none" />
      {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-56 w-full rounded-xl motion-reduce:animate-none" />)}
    </div>
  )
}

/**
 * AYLIK YÖNETİCİ ÖZETİ (2026-10-10) — `?tab=executive`. Ay seçimi, özetin bölümleri (e-posta / PDF ile AYNI içerik),
 * PDF indirme ve (global yönetici) zamanlama / alıcılar / hedefler / test postası.
 *
 * <p><b>Erişim:</b> kurum geneli rapor — global yönetici + AUDIT (`executive_summary.view`); sunucu 403 dönerse "erişim
 * yok" ekranı. <b>Kaynak:</b> gönderilmiş ay varsayılan olarak GÖNDERİLEN rapordan çizilir ("Gönderilen rapor" rozeti);
 * "Canlı hesapla" yeniden hesaplar. <b>URL:</b> `ex_m` (ay; varsayılan son tamamlanan ay yazılmaz), `ex_live=1`,
 * `ex_cfg=1` (ayar penceresi — e-postadaki bağlantı).
 *
 * <p>Düzen mobil-önce: başlık eylemleri telefonda sarar; üst kart (genel durum + hükümler + göstergeler) → bölüm
 * kartları; tablolar ≥ 768 px tablo, telefonda kart listesi. Sol renk şeridi YOK; durum rozet + metinle.
 */
export default function ExecutiveSummaryPage({ globalAdmin = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const headId = useId()
  const monthSelectId = useId()
  const [month, setMonth] = useState(() => {
    const m = readUrlParam(EX_MONTH, '')
    return MONTH_RE.test(m) ? m : null
  })
  const [live, setLive] = useState(() => readUrlParam(EX_LIVE, '') === '1')
  const [cfgOpen, setCfgOpen] = useState(() => globalAdmin && readUrlParam(EX_CFG, '') === '1')
  const [state, setState] = useState({ loading: true, error: null, status: null, data: null, months: [], defaultMonth: null,
    canConfigure: false, at: null })
  const [pdfBusy, setPdfBusy] = useState(false)
  const seq = useRef(0)

  useUrlQuerySync({
    [EX_MONTH]: month && month !== state.defaultMonth ? month : null,
    [EX_LIVE]: live ? '1' : null,
    [EX_CFG]: cfgOpen ? '1' : null,
  })

  const load = useCallback(async ({ fresh = false } = {}) => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.executiveSummary.get({ month, live, fresh })
      if (my !== seq.current) return
      if (res?.success && isSummaryPayload(res.data)) {
        setState({ loading: false, error: null, status: null, data: res.data, months: res.months || [],
          defaultMonth: res.default_month || null, canConfigure: !!res.can_configure, at: new Date() })
      } else {
        setState((s) => ({ ...s, loading: false, error: res?.error || t('exec.loadError'), status: res?.status ?? null }))
      }
    } catch (e) {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('exec.loadError'), status: 0 }))
    }
  }, [month, live, t])
  useEffect(() => { load() }, [load])

  const data = state.data
  const sections = useMemo(() => orderedSections(data), [data])
  // Üst şerit hükümleri: her bölümün İLK hükmü (sunucunun `headline`'ı ile aynı kural; bölüm anahtarı i18n için gerekli)
  const headline = useMemo(() => sections.filter((s) => s.verdicts?.length).map((s) => ({ key: s.key, verdict: s.verdicts[0] })), [sections])
  const options = useMemo(() => monthOptions(state.months, lang), [state.months, lang])
  const selected = month || data?.month || state.defaultMonth || ''
  const sentStatus = options.find((o) => o.value === selected)?.status
  const canConfigure = globalAdmin && state.canConfigure
  const target = data?.settings?.availability_target

  async function downloadPdf() {
    setPdfBusy(true)
    try {
      const res = await api.executiveSummary.downloadPdf({ month: selected, live })
      if (!res?.success) {
        toast.error(res?.status === 403 ? t('exec.pdfForbidden') : t('exec.pdfFailed'))
      }
    } finally {
      setPdfBusy(false)
    }
  }

  const forbidden = state.status === 403
  const headerActions = (
    <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto sm:justify-end">
      <Label htmlFor={monthSelectId} className="sr-only">{t('exec.month')}</Label>
      <NativeSelect id={monthSelectId} data-slot="ex-month" value={selected} disabled={!options.length || forbidden}
        onChange={(e) => { setMonth(e.target.value); setLive(false) }}
        className="h-10 max-w-[calc(100vw-2rem)] lg:h-8">
        {options.map((o) => (
          <NativeSelectOption key={o.value} value={o.value}>
            {o.current ? t('exec.monthCurrent', o.label) : o.status === 'SENT' || o.status === 'PARTIAL' ? t('exec.monthSent', o.label) : o.label}
          </NativeSelectOption>
        ))}
      </NativeSelect>
      <Button type="button" variant="outline" size="sm" onClick={() => load({ fresh: true })} disabled={forbidden}
        aria-busy={state.loading || undefined} data-slot="ex-refresh" className="h-10 lg:h-8 pointer-coarse:h-10">
        <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />{t('exec.refresh')}
      </Button>
      {canConfigure && (
        <Button type="button" variant="outline" size="sm" onClick={() => setCfgOpen(true)} data-slot="ex-open-settings"
          className="h-10 lg:h-8 pointer-coarse:h-10">
          <Settings2 aria-hidden="true" />{t('exec.settings')}
        </Button>
      )}
      <Button type="button" size="sm" onClick={downloadPdf} disabled={!data || pdfBusy} aria-busy={pdfBusy || undefined}
        data-slot="ex-pdf" className="h-10 lg:h-8 pointer-coarse:h-10">
        {pdfBusy ? <Spinner decorative size={14} /> : <Download aria-hidden="true" />}{t('exec.downloadPdf')}
      </Button>
    </div>
  )

  return (
    <div data-slot="ex-page" className="min-w-0" aria-busy={state.loading || undefined}>
      <PageHeader icon={Presentation} title={t('exec.title')} description={t('exec.subtitle')}
        meta={data ? (
          <Badge variant="outline" data-slot="ex-source" data-source={data.source} className="gap-1.5 font-normal text-muted-foreground">
            {data.source === 'snapshot' ? <History aria-hidden="true" className="size-3.5" /> : <Radio aria-hidden="true" className="size-3.5" />}
            {t(data.source === 'snapshot' ? 'exec.source.snapshot' : 'exec.source.live', fmtDateTime(data.generated_at, lang))}
          </Badge>
        ) : null}
        actions={headerActions} />

      {forbidden && (
        <div data-slot="ex-forbidden">
          <StatusBlock tone="neutral" icon={Lock} title={t('exec.noAccess.title')} description={t('exec.noAccess.desc')}
            className="rounded-xl border py-10" />
        </div>
      )}
      {!forbidden && state.loading && !data && <PageSkeleton label={t('exec.loading')} />}
      {!forbidden && state.error && !data && (
        <div data-slot="ex-error">
          <StatusBlock tone="danger" icon={CircleAlert} title={t('exec.loadError')} description={state.error}
            actions={<Button type="button" variant="outline" onClick={() => load()} className="h-10 lg:h-9">{t('exec.retry')}</Button>} />
        </div>
      )}

      {!forbidden && data && (
        <div className={cn('flex min-w-0 flex-col gap-4 transition-opacity motion-reduce:transition-none', state.loading && 'opacity-70')}>
          {state.error && (
            <AlertBanner tone="warning" title={t('exec.staleTitle')}>{state.error}</AlertBanner>
          )}
          {!data.complete && <AlertBanner tone="info" icon={Info}>{t('exec.partialMonth')}</AlertBanner>}
          {data.source === 'snapshot' && (
            <AlertBanner tone="info" icon={History}
              actions={<Button type="button" variant="outline" size="sm" onClick={() => setLive(true)} className="h-10 lg:h-8 pointer-coarse:h-10">{t('exec.showLive')}</Button>}>
              {t('exec.snapshotNote')}
            </AlertBanner>
          )}
          {data.source !== 'snapshot' && live && (sentStatus === 'SENT' || sentStatus === 'PARTIAL') && (
            <AlertBanner tone="info" icon={Radio}
              actions={<Button type="button" variant="outline" size="sm" onClick={() => setLive(false)} className="h-10 lg:h-8 pointer-coarse:h-10">{t('exec.showSnapshot')}</Button>}>
              {t('exec.liveNote')}
            </AlertBanner>
          )}

          <Card data-slot="ex-headline" data-status={data.status} role="region" aria-labelledby={headId}
            className="min-w-0 gap-4 py-4 shadow-xs">
            <CardContent className="flex min-w-0 flex-col gap-4 px-4 sm:px-5">
              <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <h3 id={headId} className="m-0 text-lg leading-tight font-semibold">{t('exec.headline.title', monthLabel(data.month, lang))}</h3>
                  <p className="m-0 mt-0.5 text-sm text-muted-foreground">
                    {target != null ? t('exec.headline.desc', fmtPct(target, lang)) : null}
                  </p>
                </div>
                <StatusBadge status={data.status} className="text-sm" />
              </div>
              {data.headline_kpis?.length > 0 && (
                <div role="group" aria-label={t('exec.headline.kpis')} data-slot="ex-headline-kpis"
                  className="grid grid-cols-2 gap-2 lg:grid-cols-4">
                  {data.headline_kpis.map((h) => <KpiTile key={h.section} sectionKey={h.section} kpi={h.kpi} emphasis />)}
                </div>
              )}
              {headline.length > 0 && (
                <ul data-slot="ex-headline-verdicts" className="m-0 flex list-none flex-col gap-1.5 p-0">
                  {headline.map(({ key, verdict }) => <VerdictItem key={key} sectionKey={key} verdict={verdict} />)}
                </ul>
              )}
              {sections.length > 1 && (
                <nav aria-label={t('exec.jump')} className="flex flex-wrap gap-2 border-t pt-3">
                  {sections.map((s) => (
                    <Button key={s.key} asChild variant="ghost" size="sm" className="h-10 lg:h-8 pointer-coarse:h-10">
                      <a href={`#ex-sec-${s.key}`} data-slot="ex-jump" data-key={s.key}>{sectionTitle(t, s)}</a>
                    </Button>
                  ))}
                </nav>
              )}
            </CardContent>
          </Card>

          {sections.map((s) => <SectionCard key={s.key} section={s} />)}
          <p className="m-0 text-xs text-muted-foreground">{t('exec.footer')}</p>
        </div>
      )}

      {canConfigure && (
        <ExecSettingsDialog open={cfgOpen} onClose={() => setCfgOpen(false)} month={selected} />
      )}
    </div>
  )
}
