import { useState, useEffect, useRef, Fragment, lazy, Suspense } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { CheckFailureCell, CheckFailurePanel } from './checks/CheckFailurePanel.jsx'
import useFailureRows, { failurePanelId, failureRowKey } from './checks/useFailureRows.js'
import { Clock, Server, FileText, Route, Stethoscope, CheckCircle2, XCircle } from 'lucide-react'
import { MON_ACT, MON_ACT_TONE } from './ui/CheckRunning.jsx'
import { Button } from '@/components/shadcn/button'
import AlertHistory from './admin/AlertHistory'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import MonitorNotes from './MonitorNotes.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import MonitorModalActions from './ui/MonitorModalActions.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailTabs } from './monitoring/MonitorDetail.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
// Süre grafiği artık paylaşımlı ResponseTimeChart (ping/keyword/port ile aynı: 90g/özel aralık + avg/min-max/p95).
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

const RECORD_TYPES = ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS']

// Tanım listesi (SOA / çözümleyici) — eski .dns-soa-grid düzeni Tailwind ile.
const DL = 'm-0 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[13px]'
const DT = 'font-semibold text-muted-foreground'
const DD = 'm-0 min-w-0 tabular-nums [overflow-wrap:anywhere]'
const MONO = 'font-mono text-[13px]'

function computeDiff(prev, next) {
  const prevLines = (prev || '').split('\n').filter(Boolean)
  const nextLines = (next || '').split('\n').filter(Boolean)
  const prevSet = new Set(prevLines)
  const nextSet = new Set(nextLines)
  return {
    removed: prevLines.filter(x => !nextSet.has(x)),
    added:   nextLines.filter(x => !prevSet.has(x)),
  }
}

// Beklenen-set flip'i (backend withinExpected aynası): satırın TÜM değerleri monitörün beklenen listesindeyse
// true — "beklenen değerler arasında" rozetiyle gösterilir. Not: geçmiş satırlar GÜNCEL beklenen sete göre değerlendirilir.
function withinExpected(expectedJoined, valueJoined) {
  const expected = new Set((expectedJoined || '').split('\n').map(s => s.trim()).filter(Boolean))
  if (expected.size === 0) return false
  const values = (valueJoined || '').split('\n').map(s => s.trim()).filter(Boolean)
  return values.length > 0 && values.every(v => expected.has(v))
}

/**
 * DNS değişim rozeti (eski .dns-changed-badge / .dns-rotated-badge / .dns-expected-flip-badge /
 * .dns-nochange-badge) — shadcn Badge. İzleme kartı (DnsMonitorPage) ile geçmiş satırı AYNI dili
 * konuşsun diye tek yerde. `kind`: changed | rotated | expected | none. Açıklamalı olanların
 * ipucu shadcn Tooltip'tir (eski `title`). Test kancası: `data-change`.
 */
export function DnsChangeBadge({ kind, className }) {
  const t = useT()
  if (kind === 'changed') {
    return (
      <Badge variant="secondary" data-change="changed"
        className={cn('bg-destructive/10 font-bold tracking-[.03em] text-destructive uppercase dark:bg-destructive/20', className)}>
        {t('dns.changed')}
      </Badge>
    )
  }
  if (kind === 'rotated') {
    return (
      <SimpleTooltip content={t('dns.rotationTitle')}>
        <Badge variant="warning" data-change="rotated" className={cn('cursor-help', className)}>{t('dns.rotated')}</Badge>
      </SimpleTooltip>
    )
  }
  if (kind === 'expected') {
    return (
      <SimpleTooltip content={t('dns.withinExpectedTitle')}>
        <Badge variant="outline" data-change="expected" className={cn('cursor-help border-dashed text-muted-foreground', className)}>
          {t('dns.withinExpected')}
        </Badge>
      </SimpleTooltip>
    )
  }
  // Hata teşhisi (2026-10-05): başarısız sorgu "Değişiklik Yok" DEĞİLDİR — kendi durum rozeti (neden ayrı hücrede).
  if (kind === 'failed') {
    return (
      <Badge variant="destructive" data-change="failed" className={cn('font-semibold', className)}>
        {t('chkhist.dnsFailedBadge')}
      </Badge>
    )
  }
  return (
    <Badge variant="secondary" data-change="none" className={cn('bg-success/15 text-success dark:bg-success/20', className)}>
      {t('dns.noChange')}
    </Badge>
  )
}

/** Detay bölümü (eski .dns-section + .dns-section-title) — shadcn Card; başlık h4 anlamını korur. */
function DnsSection({ icon: Icon, title, children }) {
  return (
    <Card data-slot="dns-section" className="gap-2.5 rounded-lg px-3.5 py-3 shadow-none">
      <CardHeader className="px-0">
        <CardTitle role="heading" aria-level={4}
          className="flex items-center gap-2 text-[11px] font-bold tracking-[.06em] text-muted-foreground uppercase">
          {Icon && <Icon size={14} aria-hidden="true" className="shrink-0 text-primary" />}
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="min-w-0 px-0">{children}</CardContent>
    </Card>
  )
}

/**
 * DNS izleme DETAY penceresi — diğer sekiz türün detay penceresiyle aynı kabuk
 * (monitoring/MonitorDetail `MonitorDetailModal` → ui/ModalShell): durum kenarı + rozet + başlık,
 * sağ üstte kartın eylemleri (MonitorModalActions), sekmeler shadcn Tabs. Escape / örtü / odak
 * iadesi kabuktan gelir (eski `useEscapeKey` gerekmez).
 *
 * `status` / `badge` sayfadan gelir (kartla AYNI durum sözlüğü, DnsMonitorPage.statusKey/statusBadge);
 * `children` pencerenin İÇİNDE çizilir — sayfa düzenleme formunu buraya verir, böylece form detay
 * penceresinin üstünde katmanlanır (ModalShell derinliği React ağacından okur).
 */
export default function DnsDetailModal({ monitor, onClose, teamNames = {}, canManage = false,
                                        status = 'unknown', badge = null,
                                        // Hızlı eylemler DnsMonitorPage'ten prop olarak gelir: tetikleyiciler
                                        // (checkNow/openEdit/openDuplicate/deleteMonitor) orada yaşıyor ve
                                        // burada ikinci bir kopyası ÜRETİLMEZ.
                                        running = false, onCheck, onEdit, onDuplicate, onDelete, deleting = false,
                                        // Duraklatılmış izlemede "Sürdür" (sayfanın useMonitorResume'u) — kartla aynı.
                                        onResume, resuming = false,
                                        // Uçtan uca tanılama (2026-10-05): sayfa satırın `can_diagnose` bayrağına göre verir;
                                        // verilmezse başlıkta düğme ve geçmiş panelinde "Bu kontrolü tanıla" çizilmez.
                                        canDiagnose = false, onDiagnose,
                                        // Değişiklik geçmişinden geri alma sonrası (2026-10-09): sayfa listeyi + bu pencerenin kopyasını tazeler.
                                        onRestored,
                                        histReload = 0, children }) {
  const t = useT()
  // Ayrıntı yanıtı ve AİT OLDUĞU izleme (2026-10-09): { id, data, error }. Başka izlemenin yanıtı bu pencerede çizilmez.
  const [detailsState, setDetailsState] = useState({ id: null, data: null, error: null })
  // Kontrol geçmişi hata teşhisi (2026-10-05): açık hata panelleri (satır anahtarıyla)
  const failRows = useFailureRows()
  // Yalnız EN SON istek yazar — D12: monitör hızla değiştirilirse eskinin geç yanıtı yeni modalı doldurmasın; aynı
  // izlemede art arda "Şimdi kontrol et" yanıtları da sırayla gelmeyebilir.
  const detailsSeq = useRef(0)
  const monitorId = monitor?.id ?? null
  const recordType = monitor?.record_type
  const [activeTab, setActiveTab] = useState(
    monitor?.record_type && RECORD_TYPES.includes(monitor.record_type) ? monitor.record_type : 'A'
  )
  const [detailTab, setDetailTab] = useState(() => readUrlParam('mtab', 'control'))   // üst tab: control | alerts | chart | notes | changes

  // Paylaşılabilir URL: modal-içi konum (üst sekme) — geçmiş aralığı/filtreyi CheckHistoryTab senkronlar.
  useUrlQuerySync({
    mtab: detailTab !== 'control' ? detailTab : null,
  })

  // Kayıt türü alt sekmesi YALNIZ izleme ya da kayıt türü değişince izlenen türe döner (2026-10-09). Eskiden ayrıntı
  // efektinin içindeydi ve `monitor` NESNESİNE bağlıydı: "Şimdi kontrol et" / "Sürdür" sayfanın kopyasını değiştirince
  // kullanıcının seçtiği alt sekme sıfırlanıyordu.
  useEffect(() => {
    if (recordType && RECORD_TYPES.includes(recordType)) setActiveTab(recordType)
  }, [monitorId, recordType])

  // Ayrıntı: pencere açılınca ve sayfa kopyayı tazeleyince (Şimdi kontrol et / kayıt / geri alma) yeniden okunur — canlı
  // kayıtlar ve özet yeni sonuçla gelsin. Ama SESSİZCE (2026-10-09): eldeki ayrıntı yeni yanıt gelene kadar çizili kalır.
  // Eskiden her okumada Kontrol sekmesi bekleme göstergesine dönüyor, altındaki Kontrol Geçmişi SÖKÜLÜYORDU — seçilen
  // aralık / sayfa / süzgeç ve adresteki parametreleri kayboluyordu.
  // .catch ŞART: request() ağ hatasında throw eder; yoksa pencere "ayrıntılar yükleniyor"da asılı kalırdı.
  useEffect(() => {
    if (!monitor) return
    const reqId = monitor.id
    const my = ++detailsSeq.current
    const kept = (prev) => (prev.id === reqId ? prev.data : null)   // aynı izlemenin eldeki ayrıntısı hata anında kalır
    api.monitoring.getDnsDetails(reqId)
      .then(d => {
        if (my !== detailsSeq.current) return   // D12: uçuşan (bayat) yanıt guard'ı
        setDetailsState(prev => (d?.success
          ? { id: reqId, data: d.data, error: null }
          : { id: reqId, data: kept(prev), error: d?.error || 'load failed' }))
      })
      .catch(e => {
        if (my !== detailsSeq.current) return
        setDetailsState(prev => ({ id: reqId, data: kept(prev), error: e?.message || 'network error' }))
      })
  }, [monitor])

  const mine = monitorId != null && detailsState.id === monitorId
  const details = mine ? detailsState.data : null
  const loadError = mine ? detailsState.error : null
  const loading = !mine   // bu izlemenin İLK yanıtı henüz gelmedi (yeniden okuma göstergesiz)

  if (!monitor) return null

  const soa = details?.soa
  const ns  = details?.authoritative_servers || []
  const live = details?.records?.[monitor.record_type]

  // Seçili kayıt türünün değer listesi (eski .dns-record-list / .dns-record-row).
  function recordList(rt) {
    const rec = details?.records?.[rt]
    return (
      <div className="rounded-lg border border-border bg-muted/30 px-3.5 py-2.5">
        {rec?.values?.length > 0 ? (
          <>
            <div className="mb-2 flex gap-2 text-xs text-muted-foreground">
              {rec.ttl != null && <span>TTL: {rec.ttl}s</span>}
              {rec.response_ms != null && <span>· {rec.response_ms}ms</span>}
            </div>
            {rec.values.map((v, i) => (
              <div key={i} className={cn('flex items-center gap-2.5 py-1 text-sm', i > 0 && 'border-t border-dashed border-border')}>
                <Badge className="min-w-12 rounded-sm px-1.5 font-mono text-[10px]">{rt}</Badge>
                <code className={cn(MONO, 'rounded border border-border bg-card px-2 py-0.5 break-all')}>{v}</code>
              </div>
            ))}
          </>
        ) : (
          <p className="py-1 text-sm text-muted-foreground italic">{t('dns.noRecords')}</p>
        )}
      </div>
    )
  }

  return (
    <MonitorDetailModal onClose={onClose} status={status} badge={badge} noc={{ type: 'DNS', monitor, canEdit: canManage }}
      // "DNS Detayları" alt başlığı eskiden başlık satırında `hidden sm:inline` idi (telefonda kayboluyordu).
      title={monitor.domain} subtitle={t('dns.detailTitle')}
      actions={
        /* Eylemler KARTIN aynısı (MonitorModalActions) — sekiz izleme türüyle tek desen. */
        <MonitorModalActions
          onResume={onResume} resuming={resuming}
          running={running}
          onCheck={onCheck} checkTitle={t('dns.check')}
          onEdit={onEdit} editTitle={t('dns.edit')}
          onDuplicate={onDuplicate}
          onDelete={onDelete} deleting={deleting} deleteTitle={t('dns.delete')}
          onClose={onClose} closeLabel={t('dns.close')}>
          {canDiagnose && onDiagnose && (
            <Button type="button" variant="outline" size="icon-sm" data-slot="ndx-open"
              className={cn(MON_ACT, 'pointer-coarse:size-10', MON_ACT_TONE.edit)}
              onClick={onDiagnose} title={t('ndx.open')} aria-label={t('ndx.open')}>
              <Stethoscope size={13} aria-hidden="true" />
            </Button>
          )}
          <CopyLinkButton iconOnly variant="outline" />
        </MonitorModalActions>
      }>
      <DetailDivider className="mt-0" />
      {/* Canlı sorgu özeti (eski .dns-modal-summary). Yüklenirken sonuç henüz yok → "—" (eskiden "✗ ERROR" yanıp sönüyordu).
          Durum metni arayüz dilinde + lucide simgesi (2026-10-09; eskiden sabit "✓ SUCCESS" / "✗ ERROR"). */}
      <DetailSummary items={[
        { key: 'live', label: t('dns.status'),
          value: loading ? '—' : (
            <span data-slot="dns-live-status" data-ok={live?.success ? 'true' : 'false'} className="inline-flex items-center gap-1">
              {live?.success
                ? <CheckCircle2 size={15} aria-hidden="true" className="shrink-0" />
                : <XCircle size={15} aria-hidden="true" className="shrink-0" />}
              <span className="min-w-0 [overflow-wrap:anywhere]">{live?.success ? t('dns.liveOk') : (live?.error || t('dns.liveFail'))}</span>
            </span>
          ),
          valueClassName: loading ? undefined : live?.success ? 'text-success' : 'text-destructive' },
        { key: 'ms', value: live?.response_ms != null ? `${live.response_ms}ms` : '—', label: t('dns.responseMs') },
        { key: 'ttl', value: live?.ttl != null ? `${live.ttl}s` : '—', label: t('dns.ttl') },
        { key: 'type', value: monitor.record_type, label: t('dns.recordType') },
      ]} />

      <DetailTabs value={detailTab} onValueChange={setDetailTab}
        countsFor={{ kind: 'dns', monitorId: monitor.id, notesType: 'DNS', notesTarget: monitor.domain, openAlerts: monitor.active_alarm ? 1 : 0 }}
        tabs={[['control', t('dns.tabControl')], ['alerts', t('dns.tabAlerts')], ['chart', t('dns.tabChart')],
          ['notes', t('dns.tabGuide')], ['changes', t('chg.tab')]]}>
        <TabsContent value="control">
          {/* Bekleme göstergesi YALNIZ bu izlemenin ilk ayrıntısı gelene kadar; sonraki okumalar içeriği (ve altındaki
              Kontrol Geçmişi'ni) sökmez. Yeniden okuma düşerse eldeki ayrıntı kalır, hata üstte söylenir. */}
          {loading ? (
            <LoadingBlock label={t('dns.loadingDetails')} />
          ) : loadError && !details ? (
            <AlertBanner tone="danger" title={t('mon.loadError')} role="alert">{String(loadError)}</AlertBanner>
          ) : (
            <div className="flex flex-col gap-4">
              {loadError && <AlertBanner tone="danger" title={t('mon.loadError')} role="alert">{String(loadError)}</AlertBanner>}
              {/* Kayıt türleri — alt sekmeler (shadcn Tabs); boş tür soluk ama seçilebilir */}
              <DnsSection title={t('dns.recordTypes')}>
                <Tabs value={activeTab} onValueChange={setActiveTab} className="gap-2.5">
                  <TabsList aria-label={t('dns.recordTypes')} className="max-w-full justify-start overflow-x-auto">
                    {RECORD_TYPES.map(rt => {
                      const n = details?.records?.[rt]?.values?.length || 0
                      return (
                        <TabsTrigger key={rt} value={rt} data-empty={n === 0 ? 'true' : undefined}
                          className="flex-none px-3 font-mono data-[empty=true]:opacity-50">
                          {rt}
                          {n > 0 && <Badge variant="secondary" className="h-4 min-w-4 px-1 font-sans text-[10px] tabular-nums">{n}</Badge>}
                        </TabsTrigger>
                      )
                    })}
                  </TabsList>
                  {RECORD_TYPES.map(rt => (
                    <TabsContent key={rt} value={rt}>{recordList(rt)}</TabsContent>
                  ))}
                </Tabs>
              </DnsSection>

              {/* Authoritative Servers */}
              {ns.length > 0 && (
                <DnsSection icon={Server} title={t('dns.authServers')}>
                  <ul className="m-0 grid list-none grid-cols-[repeat(auto-fit,minmax(min(220px,100%),1fr))] gap-1.5 p-0">
                    {ns.map((n, i) => (
                      <li key={i} className="min-w-0">
                        <code className={cn(MONO, 'block w-full rounded border border-border bg-muted/30 px-2.5 py-1 [overflow-wrap:anywhere]')}>{n}</code>
                      </li>
                    ))}
                  </ul>
                </DnsSection>
              )}

              {/* Resolver şeffaflığı: sorguların hangi DNS sunucularına gittiği (yapılandırma görünümü) */}
              {details?.resolver_config && (
                <DnsSection icon={Route} title={t('dns.resolverConfigTitle')}>
                  <dl className={DL}>
                    <dt className={DT}>{t('dns.resolverServers')}</dt>
                    <dd className={DD}>
                      {(details.resolver_config.servers || []).length > 0
                        ? details.resolver_config.servers.map((s, i) => (
                            <Fragment key={i}>
                              {i > 0 && <span className="font-bold text-muted-foreground"> → </span>}
                              <code className={MONO}>{s}</code>
                            </Fragment>
                          ))
                        : '—'}
                    </dd>
                    <dt className={DT}>{t('dns.resolverSource')}</dt>
                    <dd className={DD}>{t('dns.resolverSourceOs')}</dd>
                    <dt className={DT}>{t('dns.resolverTimeout')}</dt>
                    <dd className={DD}>{details.resolver_config.timeout_ms != null ? `${details.resolver_config.timeout_ms}ms` : '—'}</dd>
                    {details.resolver_config.propagation_enabled && (
                      <>
                        <dt className={DT}>{t('dns.resolverPropagation')}</dt>
                        <dd className={DD}>
                          {(details.resolver_config.propagation_resolvers || []).map((s, i) => (
                            <Fragment key={i}>
                              {i > 0 && ', '}
                              <code className={MONO}>{s}</code>
                            </Fragment>
                          ))}
                        </dd>
                      </>
                    )}
                  </dl>
                </DnsSection>
              )}

              {/* SOA */}
              {soa?.success && (
                <DnsSection icon={FileText} title={t('dns.soaTitle')}>
                  <dl className={DL}>
                    <dt className={DT}>{t('dns.primaryNs')}</dt>     <dd className={DD}><code className={MONO}>{soa.primary_ns}</code></dd>
                    <dt className={DT}>{t('dns.adminEmail')}</dt>    <dd className={DD}><code className={MONO}>{soa.admin_email}</code></dd>
                    <dt className={DT}>{t('dns.serial')}</dt>        <dd className={DD}>{soa.serial}</dd>
                    <dt className={DT}>{t('dns.refresh')}</dt>       <dd className={DD}>{soa.refresh}s</dd>
                    <dt className={DT}>{t('dns.retry')}</dt>         <dd className={DD}>{soa.retry}s</dd>
                    <dt className={DT}>{t('dns.expire')}</dt>        <dd className={DD}>{soa.expire}s</dd>
                    <dt className={DT}>{t('dns.minimumTtl')}</dt>    <dd className={DD}>{soa.minimum_ttl}s</dd>
                  </dl>
                </DnsSection>
              )}

              {/* Recent history — paylaşılan Kontrol Geçmişi (filterMode=changed: "Değişenler" chip'i) */}
              <DnsSection icon={Clock} title={t('dns.recentChecks')}>
                {/* Hata teşhisi (2026-10-05): başarısız sorgu ("" değer) artık "Değişiklik Yok" görünmez — değer hücresinde
                    "Sorgu başarısız" rozeti, durum hücresinde neden + aç/kapa, açılınca satırın altında panel; ikinci süzgeç
                    kutucuğu "Başarısız sorgu" (counts.errors, status=fail) — "Değişenler" süzgeci aynen kalır. */}
                <CheckHistoryTab kind="dns" monitorId={monitor.id} listKey="dns-history" reloadSignal={histReload}
                  filterMode="changed" gridClass="dns-rt-grid"
                  errorTile={{ label: t('chkhist.dnsFailTile'), hint: t('chkhist.dnsFailHint') }}
                  columns={[t('dns.lastCheck'), t('dns.currentValue'), t('dns.ttl'), t('dns.responseMs'), t('dns.status')]}
                  renderRow={(h) => {
                    const isChanged = h.changed
                    const isRotated = !isChanged && h.rotated
                    const prevVal = h.previous_value
                    const showDiff = (isChanged || isRotated) && prevVal && prevVal !== h.value
                    const diff = showDiff ? computeDiff(prevVal, h.value) : null
                    const isExpectedFlip = isChanged && withinExpected(monitor.expected_value, h.value)
                    const failed = !String(h.value ?? '').trim()
                    const k = failureRowKey(h)
                    const open = failed && failRows.isOpen(k)
                    const when = formatDate(h.checked_at)
                    const pid = failurePanelId('dns', k)
                    return (<>
                      <span className="upt-rt-time">{when}</span>
                      {failed
                        ? <span><DnsChangeBadge kind="failed" /></span>
                        : <span className="dns-history-value" title={h.value || '—'}><code>{h.value || '—'}</code></span>}
                      <span className="upt-rt-ms">{h.ttl != null ? `${h.ttl}s` : '—'}</span>
                      <span className="upt-rt-ms">{h.response_ms != null ? `${h.response_ms}ms` : '—'}</span>
                      {failed ? (
                        <CheckFailureCell type="dns" check={h} monitor={monitor} open={open} when={when} panelId={pid}
                          onToggle={() => failRows.toggle(k)} />
                      ) : (
                        <span className="flex flex-wrap items-center gap-1.5">
                          {isChanged && <DnsChangeBadge kind="changed" />}
                          {isExpectedFlip && <DnsChangeBadge kind="expected" />}
                          {isRotated && <DnsChangeBadge kind="rotated" />}
                          {!isChanged && !isRotated && <DnsChangeBadge kind="none" />}
                        </span>
                      )}
                      {open && <CheckFailurePanel type="dns" check={h} monitor={monitor} id={pid}
                        canDiagnose={canDiagnose} onDiagnose={onDiagnose} />}
                      {showDiff && diff && (
                        <div className={isRotated ? 'dns-diff-cell dns-diff-row-rotated' : 'dns-diff-cell'}
                          style={{ gridColumn: '1 / -1' }}>
                          <div className="dns-diff-when">
                            <Clock size={13} />
                            <span>{t('dns.diffDetectedAt')}</span>
                            <strong>{formatDate(h.checked_at)}</strong>
                          </div>
                          <div className="dns-diff-grid">
                            <div className="dns-diff-col">
                              <div className="dns-diff-col-title">{t('dns.previousValue')}</div>
                              <pre className="dns-diff-pre">{prevVal || '—'}</pre>
                            </div>
                            <div className="dns-diff-col">
                              <div className="dns-diff-col-title">{t('dns.newValue')}</div>
                              <pre className="dns-diff-pre">{h.value || '—'}</pre>
                            </div>
                          </div>
                          {(diff.added.length > 0 || diff.removed.length > 0) && (
                            <div className="dns-diff-summary">
                              <span className="dns-diff-label">{t('dns.lineDiff')}</span>
                              {diff.removed.map((line, idx) => (
                                <div key={'rm'+idx} className="dns-diff-line dns-diff-removed">
                                  <span className="dns-diff-sign">−</span><code>{line}</code>
                                </div>
                              ))}
                              {diff.added.map((line, idx) => (
                                <div key={'ad'+idx} className="dns-diff-line dns-diff-added">
                                  <span className="dns-diff-sign">+</span><code>{line}</code>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </>)
                  }} />
              </DnsSection>
            </div>
          )}
        </TabsContent>

        <TabsContent value="alerts"><AlertHistory domain={monitor.domain} types={alertTypesFor('dns')} /></TabsContent>

        <TabsContent value="chart">
          <Suspense fallback={<LoadingBlock label={t('dns.loadingDetails')} />}>
            <ResponseTimeChart monitorId={monitor.id} kind="dns" slowThreshold={details?.monitor?.id === monitor.id ? details.slow_threshold_ms : null} />
          </Suspense>
        </TabsContent>

        <TabsContent value="notes"><MonitorNotes type="DNS" target={monitor.domain} /></TabsContent>

        <TabsContent value="changes">
          <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
            {/* Takım adları sayfadan gelir; modal takım listesini kendisi çekmez (tek istek yeter). */}
            <ChangeHistoryTab t={t} kind="dns" monitorId={monitor.id} teamNames={teamNames} canManage={canManage}
              onRestored={onRestored} />
          </Suspense>
        </TabsContent>
      </DetailTabs>

      {children}
    </MonitorDetailModal>
  )
}
