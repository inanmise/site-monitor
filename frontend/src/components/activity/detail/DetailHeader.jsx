import { lazy, Suspense, useState } from 'react'
import { ExternalLink, History, FileDiff, Siren, ChartGantt, Stethoscope, User, Bot, Clock, Info, HelpCircle } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { relTimeOrRaw } from '../../../utils/relativeTime.js'
import { tabDeepLink } from '../../../utils/monitorDeepLink.js'
import { actorOf } from '../activityModel.js'
import TeamBadge from '../../ui/TeamBadge.jsx'
import CopyableRef from '../../ui/CopyableRef.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { ApproxBadge, Fact, LinkButton, StatusBadge, TypeBadge } from './detailParts.jsx'
import { actionLabel, diagnoseKindOf, formatIstanbul, localizeSummary, statusLabel } from './activityDetailModel.js'

// Uçtan uca tanılama pencereleri izleme sayfalarındaki AYNI bileşenler — yalnız düğmeye basılınca yüklenir.
const DIAG_DIALOGS = {
  http: lazy(() => import('../../http/diagnose/HttpDiagnoseDialog.jsx')),
  keyword: lazy(() => import('../../keyword/diagnose/KeywordDiagnoseDialog.jsx')),
  page: lazy(() => import('../../page/diagnose/PageDiagnoseDialog.jsx')),
  pagespeed: lazy(() => import('../../pagespeed/diagnose/PageSpeedDiagnoseDialog.jsx')),
  net: lazy(() => import('../../diagnose/NetDiagnoseDialog.jsx')),
}

const ACTOR_ICON = { person: User, system: Bot, scheduler: Bot, unknown: HelpCircle }

/**
 * BAŞLIK KARTI (A-1) + BAĞLANTILAR (A-7): izleme adı (h2, sayfa açılınca odak burada), tür / durum / eylem rozetleri,
 * hedef, sonuç özeti, künye (zaman İstanbul + göreli, takım, tetikleyen + istek kimliği kopyala, yanıt süresi, HTTP kodu,
 * kalan gün, alarm no) ve eylem satırı: izleme ayrıntısı, kontrol geçmişi, değişiklikler, alarm (gerçek `href` — yeni
 * sekmede açılabilir), bu izlemenin zaman çizelgesi, uçtan uca tanılama (yalnız sunucu `can_diagnose` derse).
 * Ad ve hedef KESİLMEZ (sarar). Aynı adrese giden bağlantılar tekilleşir (sertifikada izleme / geçmiş / tanılama aynı
 * pencereye gider — inceleme CT D5); ilk gelen kalır. `failed`: kontrol başarısız ama kayıtlı durum WARNING → rozet
 * "Kontrol başarısız" (tehlike tonu; "Kontrol sonucu" bölümüyle tutarlı — inceleme CT Y1). Kişi `actorOf` kuralıyla
 * (elle kontrolde kişi saklanmamışsa "Bilinmiyor", asla "Zamanlayıcı" — inceleme FR D7).
 * Test kancaları: `act-dt-header`, `act-dt-actions`, `act-dt-request-id`, `act-dt-actor` (`data-kind`).
 */
export default function DetailHeader({ detail, type, monitor, headingRef, locale, onOpenTimeline, failed = false }) {
  const t = useT()
  const [diagOpen, setDiagOpen] = useState(false)
  const a = detail.data
  const links = detail.links || {}
  const actor = actorOf(detail.actor?.name || a.actor, a.action, t)
  const ActorIcon = ACTOR_ICON[actor.kind] || User
  const failedWarning = failed && a.result_status === 'WARNING'
  const requestId = detail.actor?.requestId
  const summary = localizeSummary(a.result_summary, t, a)
  const diagKind = diagnoseKindOf(type)
  const Dialog = diagKind ? DIAG_DIALOGS[['ping', 'port', 'dns'].includes(diagKind) ? 'net' : diagKind] : null
  const canTimeline = a.monitor_id != null && !!onOpenTimeline
  // Aynı hedefe giden ikinci bağlantı çizilmez (adres karşılaştırması; ilk gelen kalır)
  const seen = new Set()
  const unique = (target) => {
    if (!target?.tab) return null
    const href = tabDeepLink(target.tab, target.params) || `${target.tab}?${JSON.stringify(target.params || {})}`
    if (seen.has(href)) return null
    seen.add(href)
    return target
  }
  const monitorLink = unique(links.monitor)
  const historyLink = unique(links.history)
  const changesLink = unique(links.changes)
  const alertLink = unique(links.alert)
  // Tanılama yalnız izleme penceresindeyse (penceresi olmayan tür) izleme bağlantısı TEK düğme olarak onu söyler
  const diagnoseInMonitor = links.canDiagnose && !(Dialog && a.monitor_id != null) && !!monitorLink
  const actions = [
    monitorLink && (diagnoseInMonitor
      ? <LinkButton key="m" target={monitorLink} slot="diagnose" variant="secondary"><Stethoscope aria-hidden="true" />{t('act.dt.lnk.diagnoseInMonitor')}</LinkButton>
      : <LinkButton key="m" target={monitorLink} slot="monitor"><ExternalLink aria-hidden="true" />{t('act.dt.lnk.monitor')}</LinkButton>),
    historyLink && <LinkButton key="h" target={historyLink} slot="history"><History aria-hidden="true" />{t('act.dt.lnk.history')}</LinkButton>,
    changesLink && <LinkButton key="c" target={changesLink} slot="changes"><FileDiff aria-hidden="true" />{t('act.dt.lnk.changes')}</LinkButton>,
    alertLink && <LinkButton key="a" target={alertLink} slot="alert"><Siren aria-hidden="true" />{t('act.dt.lnk.alert')}</LinkButton>,
    canTimeline && (
      <Button key="tl" type="button" variant="outline" size="sm" className="pointer-coarse:h-10" data-link="timeline"
        onClick={() => onOpenTimeline(a.monitor_type, a.monitor_id)}>
        <ChartGantt aria-hidden="true" />{t('act.dt.lnk.timeline')}
      </Button>
    ),
    links.canDiagnose && Dialog && a.monitor_id != null && (
      <Button key="dx" type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" data-link="diagnose"
        onClick={() => setDiagOpen(true)}>
        <Stethoscope aria-hidden="true" />{t('act.dt.lnk.diagnose')}
      </Button>
    ),
  ].filter(Boolean)

  return (
    <Card data-slot="act-dt-header" data-status={a.result_status || 'UNKNOWN'} className="min-w-0 gap-4 py-4 shadow-xs">
      <CardContent className="flex min-w-0 flex-col gap-3 px-3 sm:px-4">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <TypeBadge type={a.monitor_type} />
          <StatusBadge status={a.result_status} failed={failedWarning}
            label={failedWarning ? t('act.dt.checkBadStatus') : statusLabel(a.result_status, t)} />
          <Badge variant="outline" data-slot="act-dt-action" className="h-auto rounded-md font-normal whitespace-normal">{actionLabel(a.action, t)}</Badge>
          {detail.check?.match === 'approximate' && <ApproxBadge />}
          {detail.legacy && <ToneBadge tone="muted" data-slot="act-dt-legacy" className="h-auto rounded-md whitespace-normal">{t('act.dt.legacyBadge')}</ToneBadge>}
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <h2 ref={headingRef} tabIndex={-1} data-slot="act-dt-title"
            className="m-0 text-lg leading-snug font-semibold [overflow-wrap:anywhere] outline-none focus-visible:ring-2 focus-visible:ring-ring/50 sm:text-xl">
            {a.monitor_name || a.target || t('act.dt.untitled', a.id)}
          </h2>
          {a.target && a.target !== a.monitor_name && (
            <span data-slot="act-dt-target" className="font-mono text-xs break-all text-muted-foreground">{a.target}</span>
          )}
          {summary && <p data-slot="act-dt-summary" className="m-0 text-sm [overflow-wrap:anywhere]">{summary}</p>}
        </div>

        <dl className="m-0 grid grid-cols-1 gap-x-5 gap-y-3 min-[420px]:grid-cols-2 lg:grid-cols-4">
          <Fact label={t('act.d.time')}>
            <time dateTime={a.activity_time || undefined} className="tabular-nums">{formatIstanbul(a.activity_time, locale)}</time>
            <span className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground"><Clock aria-hidden="true" className="size-3" />{relTimeOrRaw(a.activity_time, t)} · {t('act.dt.tzNote')}</span>
          </Fact>
          <Fact label={t('act.d.team')}>
            {a.team_id != null ? <TeamBadge teamId={a.team_id} teamName={a.team_name || undefined} wrap /> : <span className="text-muted-foreground">—</span>}
          </Fact>
          <Fact label={t('act.d.actor')}>
            <span data-slot="act-dt-actor" data-kind={actor.kind} className="inline-flex min-w-0 items-center gap-1">
              <ActorIcon aria-hidden="true" className="size-3.5 shrink-0" />
              <span className={actor.kind === 'unknown' ? '[overflow-wrap:anywhere] text-muted-foreground' : '[overflow-wrap:anywhere]'}>{actor.text}</span>
            </span>
          </Fact>
          {requestId && (
            <Fact label={t('act.dt.requestId')}>
              <span data-slot="act-dt-request-id">
                <CopyableRef value={requestId} copyLabel={t('act.dt.copyRequestId')} copiedLabel={t('share.copied')}
                  codeClassName="font-normal" buttonClassName="pointer-coarse:size-10" />
              </span>
            </Fact>
          )}
          {a.response_ms != null && <Fact label={t('act.d.response')}><span className="tabular-nums">{a.response_ms} ms</span></Fact>}
          {a.http_status != null && <Fact label={t('act.dt.m.httpStatus')}><span className="tabular-nums">HTTP {a.http_status}</span></Fact>}
          {a.days_remaining != null && <Fact label={t('act.d.days')}><span className="tabular-nums">{t('act.days', a.days_remaining)}</span></Fact>}
          {a.alert_event_id != null && <Fact label={t('act.dt.alertNo')}><span className="tabular-nums">#{a.alert_event_id}</span></Fact>}
        </dl>

        {actions.length > 0 && (
          <div data-slot="act-dt-actions" className="flex min-w-0 flex-wrap gap-2 border-t pt-3" role="group" aria-label={t('act.dt.lnk.group')}>
            {actions}
          </div>
        )}
        {detail.links?.source === 'derived' && !detail.legacy && (
          <p className="m-0 flex items-center gap-1.5 text-xs text-muted-foreground"><Info aria-hidden="true" className="size-3.5 shrink-0" />{t('act.dt.lnk.derived')}</p>
        )}
      </CardContent>
      {diagOpen && Dialog && (
        <Suspense fallback={null}>
          {diagKind === 'ping' || diagKind === 'port' || diagKind === 'dns'
            ? <Dialog type={diagKind} monitor={monitor} onClose={() => setDiagOpen(false)} />
            : <Dialog monitor={monitor} onClose={() => setDiagOpen(false)} />}
        </Suspense>
      )}
    </Card>
  )
}
