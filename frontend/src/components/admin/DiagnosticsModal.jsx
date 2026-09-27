import { useEffect, useMemo, useState } from 'react'
import { Stethoscope, History, ClipboardCopy, Server, Activity, Timer, ListChecks, RefreshCw } from 'lucide-react'
import { api, formatDate, getRecentFailures } from '../../api/client'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { copyText } from '../../utils/copyText.js'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { buildSteps, buildVerdict, buildLanes, buildReport, wasRateLimited } from '../diagnostics/diagModel.js'
import { Disclosure, Running } from '../diagnostics/DiagParts.jsx'
import DiagSkeleton from '../diagnostics/DiagSkeleton.jsx'
import DiagVerdict from '../diagnostics/DiagVerdict.jsx'
import DiagPipeline from '../diagnostics/DiagPipeline.jsx'
import DiagTiming from '../diagnostics/DiagTiming.jsx'
import DiagHistory from '../diagnostics/DiagHistory.jsx'
import RateLimitBanner from '../diagnostics/RateLimitBanner.jsx'
import { ClientIpResult, CombosTable, HstsResult, NetworkResult, OpensslResult, TlsClientInfo } from '../diagnostics/DiagAdvanced.jsx'

/**
 * Bağlantı/SSL/ağ derin tanılama penceresi — Domain Envanteri, Sertifika detayı, Durum kartları ve Yenileme
 * Önerileri'nden açılır. Props: { domain, port, onClose } (çağıranlar değişmedi). Mount'ta temel koşu otomatik.
 *
 * <p>2026-09-26 yeniden tasarım ("bağlantı erişilebilirlik çözümleyicisi"):
 *   • Hüküm şeridi — erişilebilir mi, hangi adımda takıldı, en olası neden, sonraki adım (+ sertifika notu).
 *   • Adım hattı — DNS → TCP → Proxy → TLS → Sertifika → HTTP; geniş ekranda yatay şerit + kart ızgarası,
 *     telefonda dikey kart listesi; her kartta durum rozeti, süre, kilit bulgular, ham ayrıntılar.
 *   • Zamanlama şeritleri — DNS + her yolun süresi.
 *   • Kombinasyon matrisi (ham gerçek) katlanır; ek analizler (JDK parmak izi, openssl, ağ, HSTS, istemci IP)
 *     "Ek analizler" bölümünde isteğe bağlı koşar; HSTS/ağ koşunca HTTP adımı dolar.
 *   • Geçmiş — pencerenin içinde katlanır bölüm (iç içe ikinci pencere yok), `diagnostics.history` yetkisiyle.
 *   • Rapor kopyala (düz metin), 429 → geri sayımlı dostça şerit, telefonda tam ekran pencere.
 * Backend uçları ve davranışı DEĞİŞMEDİ (POST /admin/diagnostics[/openssl|/network|/hsts], GET history).
 */
const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-dvh max-sm:max-h-dvh max-sm:w-full max-sm:max-w-full max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:px-4 max-sm:pt-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'

export default function DiagnosticsModal({ domain, port, onClose }) {
  const t = useT()
  const toast = useToast()
  const { canView } = usePermissions()
  const isMobile = useIsMobile()
  const item = useMemo(() => ({ domain, port: port || 443 }), [domain, port])
  const [diag, setDiag] = useState({ item, loading: true }) // { item, loading?, data?, error?, rateLimited?, suggested?, ossl?, net?, hsts?, cip? }
  const [historyOpen, setHistoryOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)

  useEffect(() => { runDiag(item) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function runDiag(it) {
    setDiag({ item: it, loading: true })
    try {
      const res = await api.admin.runDiagnostics(it.domain, it.port || 443)
      if (res?.success) { setDiag({ item: it, data: res.data }); return }
      if (wasRateLimited(res, '/admin/diagnostics', getRecentFailures())) { setDiag({ item: it, rateLimited: true }); return }
      // suggested_host YAPISAL alan: Türkçe hata metnini ayrıştırmak TR/EN arasında ve mesaj her düzenlendiğinde
      // sessizce kırılırdı.
      setDiag({ item: it, error: res?.error || t('inv.diagError'), suggested: res?.suggested_host || null })
    } catch {
      setDiag({ item: it, error: t('inv.diagError') })
    }
  }

  /** Ek analiz koşucusu: `key` ∈ ossl | net | hsts | cip. */
  const runExtra = (key, call) => async () => {
    setDiag((d) => ({ ...d, [key]: { loading: true } }))
    try {
      const res = await call()
      setDiag((d) => ({ ...d, [key]: res?.success ? { data: res.data } : { error: res?.error || t('inv.diagError') } }))
    } catch {
      setDiag((d) => ({ ...d, [key]: { error: t('inv.diagError') } }))
    }
  }
  const runOpenssl = runExtra('ossl', () => api.admin.runOpensslDiagnostics(diag.item.domain, diag.item.port || 443))
  const runNetwork = runExtra('net', () => api.admin.runNetworkDiagnostics(diag.item.domain, diag.item.port || 443))
  const runHsts = runExtra('hsts', () => api.admin.runHstsDiagnostics(diag.item.domain, diag.item.port || 443))
  const runClientIp = runExtra('cip', () => api.admin.clientIpDebug())

  const steps = useMemo(
    () => (diag.data ? buildSteps(diag.data, { hsts: diag.hsts?.data, net: diag.net?.data, ossl: diag.ossl?.data }) : null),
    [diag.data, diag.hsts?.data, diag.net?.data, diag.ossl?.data],
  )
  const verdict = useMemo(() => (diag.data && steps ? buildVerdict(diag.data, steps, t) : null), [diag.data, steps, t])
  const timing = useMemo(() => (diag.data ? buildLanes(diag.data) : null), [diag.data])

  async function copyReport() {
    if (!diag.data || !steps || !verdict) return
    const text = buildReport({ data: diag.data, steps, verdict, t, formatDate })
    if (await copyText(text)) toast.success(t('diag.reportCopied'))
    else toast.error(t('diag.copyFailed'))
  }

  const src = diag.data?.source
  const canHistory = canView('diagnostics.history')

  /** Bölüm eylemi: çalıştır düğmesi → yükleniyor → hata / sonuç. */
  const runSection = (state, runLabel, onRun, render) => (
    <>
      {!state && <Button type="button" variant="secondary" size="sm" className="mt-1.5" onClick={onRun}>{runLabel}</Button>}
      {state?.loading && <Running label={t('inv.diagRunning')} />}
      {state?.error && <AlertBanner tone="danger">{state.error}</AlertBanner>}
      {state?.data && render(state.data)}
    </>
  )

  return (
    <ModalShell open onClose={onClose} icon={Stethoscope} size="lg" scrollBody closeLabel={t('app.dismiss')}
      className={PHONE_FULLSCREEN}
      title={<>
        <span className="min-w-0 break-words">{t('inv.diagTitle', diag.item.domain)}</span>
        <Badge variant="secondary" className="font-mono">:{diag.item.port || 443}</Badge>
      </>}
      headerExtra={(
        <Button type="button" variant="ghost" size="icon-sm" className="-my-1 shrink-0 text-muted-foreground"
          onClick={copyReport} disabled={!diag.data} aria-label={t('diag.copyReport')} title={t('diag.copyReport')}>
          <ClipboardCopy aria-hidden="true" />
        </Button>
      )}
      footer={<>
        <Button type="button" variant="secondary" onClick={onClose}>{t('app.dismiss')}</Button>
        <Button type="button" onClick={() => runDiag(diag.item)} disabled={!!diag.loading} aria-busy={diag.loading || undefined}>
          {diag.loading ? <Spinner size={14} inline decorative /> : <RefreshCw aria-hidden="true" />} {t('inv.diagRerun')}
        </Button>
      </>}>

      <div className="flex min-w-0 flex-col gap-4" data-slot="diag-body">
        {/* Kaynak: sondanın koştuğu pod — her durumda (hata dahil) görünür ki ağ ekibi neyi izleyeceğini bilsin */}
        {src && (
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground" data-slot="diag-source">
            <Server aria-hidden="true" className="size-3.5 shrink-0" />
            <span>{t('diag.fromPod', src.hostname || '—')}</span>
            {src.ips?.length > 0 && <code className="font-mono break-all">{src.ips.join(', ')}</code>}
            {src.node_name && <span>· {t('inv.diagSourceNode')}: <code className="font-mono">{src.node_name}</code></span>}
            <HintPopover content={t('diag.sourceHint')} side="bottom">
              <span className="underline decoration-dotted underline-offset-2">{t('diag.sourceWhy')}</span>
            </HintPopover>
          </div>
        )}

        {diag.loading && <DiagSkeleton />}

        {diag.rateLimited && <RateLimitBanner onRetry={() => runDiag(diag.item)} />}

        {diag.error && (
          <AlertBanner tone="danger" role="alert">
            {diag.error}
            {/* Tek tık: kullanıcı host'u elle yeniden yazmasın. Sunucu ÖNERMEDİYSE düğme hiç çıkmaz. */}
            {diag.suggested && (
              <div className="mt-2">
                <Button type="button" size="sm" onClick={() => runDiag({ ...diag.item, domain: diag.suggested })}>
                  {t('inv.diagTrySuggested', diag.suggested)}
                </Button>
              </div>
            )}
          </AlertBanner>
        )}

        {diag.data && steps && (
          <>
            <DiagVerdict verdict={verdict} />

            <section aria-labelledby="diag-pipeline-h" className="flex min-w-0 flex-col gap-2">
              <h3 id="diag-pipeline-h" className="flex items-center gap-1.5 text-sm font-semibold"><ListChecks aria-hidden="true" className="size-4 text-primary" /> {t('diag.pipeline')}</h3>
              <DiagPipeline steps={steps} focusKey={verdict?.stage} mobile={isMobile} onRunHttp={runHsts} httpBusy={!!diag.hsts?.loading} />
            </section>

            <Disclosure bordered defaultOpen={!isMobile} data-slot="diag-timing-section"
              summary={<span className="flex items-center gap-1.5 text-sm font-semibold"><Timer aria-hidden="true" className="size-4 text-primary" /> {t('diag.timing')}</span>}>
              <DiagTiming timing={timing} className="pt-1" />
            </Disclosure>

            <Disclosure bordered data-slot="diag-matrix-section"
              summary={<span className="flex items-center gap-1.5 text-sm font-semibold"><Activity aria-hidden="true" className="size-4 text-primary" /> {t('inv.diagMatrix')}</span>}>
              <CombosTable combos={diag.data.combos} detailed item={diag.item} proxyAddress={diag.data.proxy_address} />
            </Disclosure>

            <CollapsibleSection open={moreOpen} onOpenChange={setMoreOpen} icon={Stethoscope} label={t('diag.moreAnalyses')}
              hint={t('diag.moreAnalysesHint')} toggleLabel={t('diag.moreAnalysesToggle')} data-slot="diag-more" contentClassName="flex flex-col gap-2 pt-2">
              <Disclosure bordered defaultOpen={!!diag.ossl?.data} summary={<strong className="text-sm">{t('inv.osslTitle')}</strong>}>
                {runSection(diag.ossl, t('inv.osslRun'), runOpenssl, (d) => <OpensslResult data={d} />)}
              </Disclosure>
              <Disclosure bordered defaultOpen={!!diag.net?.data} summary={<strong className="text-sm">{t('inv.netTitle')}</strong>}>
                {runSection(diag.net, t('inv.netRun'), runNetwork, (d) => <NetworkResult data={d} />)}
              </Disclosure>
              <Disclosure bordered defaultOpen={!!diag.hsts?.data} summary={<strong className="text-sm">{t('inv.hstsTitle')}</strong>}>
                {runSection(diag.hsts, t('inv.hstsRun'), runHsts, (d) => <HstsResult data={d} />)}
              </Disclosure>
              <Disclosure bordered defaultOpen={!!diag.cip?.data} summary={<strong className="text-sm">{t('inv.cipTitle')}</strong>}>
                {runSection(diag.cip, t('inv.cipRun'), runClientIp, (d) => <ClientIpResult data={d} />)}
              </Disclosure>
              <Disclosure bordered summary={<strong className="text-sm">{t('inv.diagTlsClient')}</strong>}>
                <TlsClientInfo tc={diag.data.tls_client} />
              </Disclosure>
            </CollapsibleSection>
          </>
        )}

        {/* Geçmiş — pencerenin içinde; yetki kapısı burada (backend de requirePerm ile aynı kapıyı kurar) */}
        {canHistory && !diag.loading && (
          <CollapsibleSection open={historyOpen} onOpenChange={setHistoryOpen} icon={History} label={t('inv.diagHistory')}
            hint={t('diag.historyHint')} toggleLabel={t('diag.historyToggle')} data-slot="diag-history-section"
            className={cn(!diag.data && 'mt-1')} contentClassName="pt-2">
            <DiagHistory domain={diag.item.domain} active={historyOpen} />
          </CollapsibleSection>
        )}
      </div>
    </ModalShell>
  )
}
