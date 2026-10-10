import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Presentation, RefreshCw, Download, CircleAlert, Lock, History, Radio, Info, FileText, Send, Building2, Users } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import PageHeader from '../ui/PageHeader.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import ExecReportView from './ExecReportView.jsx'
import ExecDeliveryView from './ExecDeliveryView.jsx'
import {
  EX_CFG, EX_LIVE, EX_MONTH, EX_SEL, EX_TEAM, EX_VIEW, MONTH_RE, SEL_ORG, TEAM_ID_RE, VIEW_DELIVERY, VIEW_REPORT,
  fmtDateTime, isSummaryPayload, isTeamScope, monthOptions, scopeOptions,
} from './executiveModel.js'

function PageSkeleton({ label }) {
  return (
    <div role="status" aria-live="polite" data-slot="ex-skeleton" className="flex flex-col gap-4">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-20 w-full rounded-xl motion-reduce:animate-none" />
      <Skeleton className="h-56 w-full rounded-xl motion-reduce:animate-none" />
      {Array.from({ length: 2 }, (_, i) => <Skeleton key={i} className="h-64 w-full rounded-xl motion-reduce:animate-none" />)}
    </div>
  )
}

function readTeamParam() {
  const v = readUrlParam(EX_TEAM, '')
  return v === SEL_ORG || TEAM_ID_RE.test(v) ? v : null
}

/**
 * AYLIK YÖNETİCİ ÖZETİ (2026-10-10; takım kapsamı ve yeniden tasarım aynı gün, kullanıcı isteği) — `?tab=executive`.
 *
 * <p><b>İki görünüm</b> (shadcn Tabs; yalnız yapılandırabilen görür): <b>Rapor</b> — kapsam (kurum geneli ya da takım) ve
 * ay seçimi, üst kart (genel durum, bölüm sağlığı, ana göstergeler ve hükümler), bölümler (e-posta / PDF ile AYNI içerik),
 * geniş ekranda yapışkan içindekiler; <b>Alıcılar ve gönderim</b> — kurum geneli ayarları (global yönetici) ve takım
 * alıcıları (global yönetici her takım, takım müdürü yönettiği takımlar).
 *
 * <p><b>Erişim:</b> sunucu karar verir (kurum: global yönetici + AUDIT; takım: ayrıca takımın müdürü); 403 → "erişim yok".
 * <b>Kaynak:</b> gönderilmiş ay varsayılan olarak GÖNDERİLEN rapordan çizilir; "Canlı hesapla" yeniden hesaplar.
 * <b>URL:</b> `ex_m` ay (varsayılan yazılmaz), `ex_team` kapsam takımı, `ex_live=1`, `ex_view=delivery`, `ex_sel`
 * gönderim görünümündeki seçim; `ex_cfg=1` (e-postadaki ayar bağlantısı) gönderim görünümünü açar.
 */
export default function ExecutiveSummaryPage({ globalAdmin = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const monthSelectId = useId()
  const scopeLabelId = useId()
  const cfgLink = readUrlParam(EX_CFG, '') === '1'
  const [month, setMonth] = useState(() => {
    const m = readUrlParam(EX_MONTH, '')
    return MONTH_RE.test(m) ? m : null
  })
  const [team, setTeam] = useState(readTeamParam)
  const [live, setLive] = useState(() => readUrlParam(EX_LIVE, '') === '1')
  const [view, setView] = useState(() => (cfgLink || readUrlParam(EX_VIEW, '') === VIEW_DELIVERY ? VIEW_DELIVERY : VIEW_REPORT))
  const [deliveryVisited, setDeliveryVisited] = useState(view === VIEW_DELIVERY)
  const [sel, setSel] = useState(() => {
    const s = readUrlParam(EX_SEL, '')
    if (s === SEL_ORG || TEAM_ID_RE.test(s)) return s
    const tp = readTeamParam()
    return cfgLink && tp && tp !== SEL_ORG ? tp : null
  })
  const [state, setState] = useState({ loading: true, error: null, status: null, data: null, months: [], defaultMonth: null,
    scopes: null, canConfigure: false, canConfigureAny: false })
  const [pdfBusy, setPdfBusy] = useState(false)
  const seq = useRef(0)

  const allowDelivery = state.canConfigure || state.canConfigureAny
  const activeView = allowDelivery ? view : VIEW_REPORT

  useUrlQuerySync({
    [EX_MONTH]: month && month !== state.defaultMonth ? month : null,
    [EX_TEAM]: team && team !== SEL_ORG ? team : null,
    [EX_LIVE]: live ? '1' : null,
    [EX_VIEW]: activeView === VIEW_DELIVERY ? VIEW_DELIVERY : null,
    [EX_SEL]: activeView === VIEW_DELIVERY && sel ? sel : null,
    [EX_CFG]: null,
  })

  const load = useCallback(async ({ fresh = false } = {}) => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.executiveSummary.get({ month, team, live, fresh })
      if (my !== seq.current) return
      if (res?.success && isSummaryPayload(res.data)) {
        setState({ loading: false, error: null, status: null, data: res.data, months: res.months || [],
          defaultMonth: res.default_month || null, scopes: res.scopes || null, canConfigure: !!res.can_configure,
          canConfigureAny: !!res.can_configure_any_team })
      } else {
        setState((s) => ({ ...s, loading: false, error: res?.error || t('exec.loadError'), status: res?.status ?? null }))
      }
    } catch (e) {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('exec.loadError'), status: 0 }))
    }
  }, [month, team, live, t])
  useEffect(() => { load() }, [load])

  const data = state.data
  const options = useMemo(() => monthOptions(state.months, lang), [state.months, lang])
  const scopeOpts = useMemo(() => scopeOptions(state.scopes, t), [state.scopes, t])
  const selected = month || data?.month || state.defaultMonth || ''
  const sentStatus = options.find((o) => o.value === selected)?.status
  const scopeValue = team || (isTeamScope(data) ? String(data.scope.team_id) : SEL_ORG)
  const forbidden = state.status === 403

  function changeView(v) {
    setView(v)
    if (v === VIEW_DELIVERY) setDeliveryVisited(true)
  }

  async function downloadPdf() {
    setPdfBusy(true)
    try {
      const res = await api.executiveSummary.downloadPdf({ month: selected, team: isTeamScope(data) ? String(data.scope.team_id) : null, live })
      if (!res?.success) {
        toast.error(res?.status === 403 ? t('exec.pdfForbidden') : t('exec.pdfFailed'))
      }
    } finally {
      setPdfBusy(false)
    }
  }

  const headerActions = activeView === VIEW_REPORT ? (
    <>
      <Button type="button" variant="outline" size="sm" onClick={() => load({ fresh: true })} disabled={forbidden}
        aria-busy={state.loading || undefined} data-slot="ex-refresh" className="h-10 lg:h-8 pointer-coarse:h-10">
        <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />{t('exec.refresh')}
      </Button>
      <Button type="button" size="sm" onClick={downloadPdf} disabled={!data || pdfBusy} aria-busy={pdfBusy || undefined}
        data-slot="ex-pdf" className="h-10 lg:h-8 pointer-coarse:h-10">
        {pdfBusy ? <Spinner decorative size={14} /> : <Download aria-hidden="true" />}{t('exec.downloadPdf')}
      </Button>
    </>
  ) : null

  const sourceBadge = data && activeView === VIEW_REPORT ? (
    <Badge variant="outline" data-slot="ex-source" data-source={data.source} className="gap-1.5 font-normal text-muted-foreground">
      {data.source === 'snapshot' ? <History aria-hidden="true" className="size-3.5" /> : <Radio aria-hidden="true" className="size-3.5" />}
      {t(data.source === 'snapshot' ? 'exec.source.snapshot' : 'exec.source.live', fmtDateTime(data.generated_at, lang))}
    </Badge>
  ) : null

  const scopeBar = data && (
    <Card data-slot="ex-scopebar" className="gap-0 py-3 shadow-xs">
      <CardContent className="grid min-w-0 gap-3 px-4 sm:grid-cols-[minmax(0,1fr)_minmax(0,15rem)] sm:items-end sm:px-5">
        <div className="flex min-w-0 flex-col gap-1.5 [&_[role=combobox]]:h-10 lg:[&_[role=combobox]]:h-9">
          <Label id={scopeLabelId} className="text-xs font-medium text-muted-foreground">{t('exec.scope.label')}</Label>
          {scopeOpts.length > 1 ? (
            <div data-slot="ex-scope">
              <SearchableSelect value={scopeValue} options={scopeOpts} ariaLabelledBy={scopeLabelId} searchThreshold={8}
                onChange={(v) => { setTeam(v || null); setLive(false) }} />
            </div>
          ) : (
            <p data-slot="ex-scope-static" className="m-0 flex min-h-10 items-center gap-2 text-sm font-medium lg:min-h-9">
              {isTeamScope(data) ? <Users aria-hidden="true" className="size-4 text-muted-foreground" />
                : <Building2 aria-hidden="true" className="size-4 text-muted-foreground" />}
              <span className="truncate">{isTeamScope(data) ? (data.scope.team_name || `#${data.scope.team_id}`) : t('exec.scope.org')}</span>
            </p>
          )}
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor={monthSelectId} className="text-xs font-medium text-muted-foreground">{t('exec.month')}</Label>
          <NativeSelect id={monthSelectId} data-slot="ex-month" value={selected} disabled={!options.length || forbidden}
            onChange={(e) => { setMonth(e.target.value); setLive(false) }}
            className="h-10 w-full lg:h-9">
            {options.map((o) => (
              <NativeSelectOption key={o.value} value={o.value}>
                {o.current ? t('exec.monthCurrent', o.label) : o.status === 'SENT' || o.status === 'PARTIAL' ? t('exec.monthSent', o.label) : o.label}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
      </CardContent>
    </Card>
  )

  const reportPanel = (
    <>
      {!forbidden && state.loading && !data && <PageSkeleton label={t('exec.loading')} />}
      {!forbidden && state.error && !data && (
        <div data-slot="ex-error">
          <StatusBlock tone="danger" icon={CircleAlert} title={t('exec.loadError')} description={state.error}
            actions={<Button type="button" variant="outline" onClick={() => load()} className="h-10 lg:h-9">{t('exec.retry')}</Button>} />
        </div>
      )}
      {!forbidden && data && (
        <div className="flex min-w-0 flex-col gap-4">
          {scopeBar}
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
          <ExecReportView data={data} loading={state.loading} />
        </div>
      )}
    </>
  )

  return (
    <div data-slot="ex-page" className="min-w-0" aria-busy={state.loading || undefined}>
      <PageHeader icon={Presentation} title={t('exec.title')} description={t('exec.subtitle')} meta={sourceBadge}
        actions={headerActions} />

      {forbidden && (
        <div data-slot="ex-forbidden">
          <StatusBlock tone="neutral" icon={Lock} title={t('exec.noAccess.title')} description={t('exec.noAccess.desc')}
            className="rounded-xl border py-10" />
        </div>
      )}

      {allowDelivery && !forbidden ? (
        // İki görünüm: Rapor / Alıcılar ve gönderim. İçerikler zorla bağlı kalır (pasif olan gizlenir) — gönderim
        // görünümündeki kaydedilmemiş form sekme değişince kaybolmasın; gönderim görünümü İLK ziyarette bağlanır.
        <Tabs value={activeView} onValueChange={changeView} className="gap-4">
          <TabsList variant="line" aria-label={t('exec.view.label')} data-slot="ex-views"
            className="h-auto w-full justify-start gap-1 overflow-x-auto border-b pb-0">
            <TabsTrigger value={VIEW_REPORT} data-view={VIEW_REPORT} className="min-h-10 flex-none px-3 lg:min-h-9">
              <FileText aria-hidden="true" />{t('exec.view.report')}
            </TabsTrigger>
            <TabsTrigger value={VIEW_DELIVERY} data-view={VIEW_DELIVERY} className="min-h-10 flex-none px-3 lg:min-h-9">
              <Send aria-hidden="true" />{t('exec.view.delivery')}
            </TabsTrigger>
          </TabsList>
          <TabsContent value={VIEW_REPORT} forceMount data-view-panel={VIEW_REPORT}
            className="min-w-0 data-[state=inactive]:hidden">
            {reportPanel}
          </TabsContent>
          {deliveryVisited && (
            <TabsContent value={VIEW_DELIVERY} forceMount data-view-panel={VIEW_DELIVERY}
              className="min-w-0 data-[state=inactive]:hidden">
              <ExecDeliveryView month={selected} canConfigureOrg={globalAdmin && state.canConfigure}
                selected={sel} onSelect={setSel} />
            </TabsContent>
          )}
        </Tabs>
      ) : (
        <div data-view-panel={VIEW_REPORT} className="min-w-0">{!forbidden && reportPanel}</div>
      )}
    </div>
  )
}
