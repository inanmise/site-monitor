import { useCallback, useEffect, useRef, useState } from 'react'
import { Activity, ExternalLink, RotateCcw, Lock, Info } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { formatIncidentTime } from '../../utils/incidentMeta.js'
import { navigateTo } from '../../utils/navigate.js'
import { reasonLabel } from '../activity/activityModel.js'
import RelatedActivityPanel from '../activity/related/RelatedActivityPanel.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { evidenceQuery, EVIDENCE_LIMIT } from './incidentsModel.js'

const SECTION = 'mt-5 mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase'

/** Uygulama içi geçiş: değiştirici tuşla / orta tıkla yeni sekme (bağlantının `href`'i), düz tıkla aynı sekmede. */
function inApp(e, tab, params, onLeave) {
  if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button === 1) return
  e.preventDefault()
  onLeave?.()
  navigateTo(tab, params)
}

/**
 * "Kontrol kanıtları" (2026-11-13) — olay süresince bu izlemenin BAŞARISIZ ve ZAMAN AŞIMI kontrolleri, Aktivite
 * kaydından (`/api/activity?monitor=TYPE:id&from&to&status=ERROR,TIMEOUT`). Her satır Aktivite ayrıntı sayfasına
 * (`?tab=activity&act=<id>`) gider; "İzleme zaman çizelgesi" `amon` görünümünü açar; ilk başarısızlık için "Aynı anda
 * başka ne oldu?" (RelatedActivityPanel — yalnız görüş kapsamındaki kayıtlar).
 *
 * <p>Bilinmeyen ≠ yok: izin yoksa (`monitoring.read`) "görme yetkiniz yok", olay bir izleme kimliğine bağlanamadıysa
 * (sertifika / silinmiş izleme) "bağlanamadı", okuma başarısızsa "okunamadı + yeniden dene" — hiçbiri "başarısız kontrol
 * yok" demez. Pencere: açılıştan 15 dk önce (teyit kontrolleri) → çözüm (ya da şimdi).
 */
export default function IncidentCheckEvidence({ incident, onLeave }) {
  const t = useT()
  const dateLocale = useDateLocale()
  const perms = usePermissions()
  const query = evidenceQuery(incident)
  // İzin anlık görüntüsü yüklenmiş VE kaynağı içeriyorsa karar ondan; değilse (sağlayıcı yok / eski sunucu) sorulur ve
  // sunucunun 403'ü aynı "yetkiniz yok" durumuna düşer.
  const known = perms?.loaded !== false && Object.prototype.hasOwnProperty.call(perms?.perms || {}, 'monitoring.read')
  const permAllowed = !known || perms.canView('monitoring.read')
  const [state, setState] = useState({ loading: true, failed: false, denied: false, rows: [], total: null })
  const allowed = permAllowed && !state.denied
  const [showRelated, setShowRelated] = useState(false)
  const seq = useRef(0)
  const key = query ? JSON.stringify(query) : null

  const load = useCallback(() => {
    if (!key || !permAllowed) return
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, failed: false }))
    Promise.resolve().then(() => api.getActivity({ ...JSON.parse(key), page: 0, size: EVIDENCE_LIMIT }))
      .catch(() => null)
      .then((res) => {
        if (my !== seq.current) return
        if (res?.success && Array.isArray(res.data)) {
          setState({ loading: false, failed: false, denied: false, rows: res.data,
            total: Number.isFinite(Number(res.total)) ? Number(res.total) : res.data.length })
        } else {
          const denied = Number(errorInfoOf(res)?.status ?? res?.status) === 403
          setState({ loading: false, failed: !denied, denied, rows: [], total: null })
        }
      })
  }, [key, permAllowed])
  useEffect(() => { load() }, [load])

  const monitor = incident?.monitor
  // Zaman çizelgesi özel aralıkla: olay penceresi (atlr=custom; bitiş yoksa "şimdi")
  const timelineParams = query
    ? { amon: query.monitor, atlr: 'custom', atlf: query.from, ...(query.to ? { atlt: query.to } : {}) }
    : null

  let body
  if (!allowed) {
    body = (
      <p data-slot="incident-evidence-state" data-state="no-access" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
        <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('incov.evidence.noAccess')}
      </p>
    )
  } else if (!query) {
    body = (
      <p data-slot="incident-evidence-state" data-state="no-monitor" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
        <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('incov.evidence.noMonitor')}
      </p>
    )
  } else if (state.loading && state.rows.length === 0) {
    body = <div aria-hidden="true" className="flex flex-col gap-1.5"><Skeleton className="h-9 w-full" /><Skeleton className="h-9 w-full" /></div>
  } else if (state.failed) {
    body = (
      <div data-slot="incident-evidence-state" data-state="failed" role="alert" className="flex flex-wrap items-center gap-2 text-xs text-destructive">
        <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{t('incov.evidence.failed')}</span>
        <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" onClick={load}>
          <RotateCcw aria-hidden="true" />{t('incov.retry')}
        </Button>
      </div>
    )
  } else if (state.rows.length === 0) {
    body = <p data-slot="incident-evidence-state" data-state="none" className="m-0 text-xs text-muted-foreground">{t('incov.evidence.none')}</p>
  } else {
    const first = state.rows[state.rows.length - 1]   // en eski (liste yeniden eskiye)
    body = (
      <>
        <p className="m-0 mb-1.5 text-xs text-muted-foreground">{t('incov.evidence.count', state.total ?? state.rows.length)}</p>
        <ul data-slot="incident-evidence" className="m-0 flex list-none flex-col gap-1.5 p-0">
          {state.rows.map((r) => {
            const when = formatIncidentTime(r.activity_time, dateLocale)
            const reason = r.failure_reason ? reasonLabel(r.failure_reason, t) : null
            return (
              <li key={r.id} data-slot="incident-evidence-row" data-status={r.result_status} className="min-w-0">
                <a href={`?tab=activity&act=${encodeURIComponent(r.id)}`}
                  aria-label={t('incov.evidence.openDetail', when)}
                  onClick={(e) => inApp(e, 'activity', { act: String(r.id) }, onLeave)}
                  className="flex min-h-10 min-w-0 flex-wrap items-center gap-x-2 gap-y-1 rounded-md border bg-card px-2.5 py-1.5 text-xs no-underline hover:bg-muted/50 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
                  <Badge variant="outline" className="border-destructive/40 bg-destructive/10 font-semibold text-destructive">
                    {t(`act.st.${r.result_status}`) !== `act.st.${r.result_status}` ? t(`act.st.${r.result_status}`) : r.result_status}
                  </Badge>
                  {reason && <span className="min-w-0 font-medium text-foreground [overflow-wrap:anywhere]">{reason}</span>}
                  {r.http_status != null && <span className="text-muted-foreground">{t('incov.evidence.httpStatus', r.http_status)}</span>}
                  {r.result_summary && !reason && <span className="min-w-0 text-muted-foreground [overflow-wrap:anywhere]">{r.result_summary}</span>}
                  <span className="ml-auto whitespace-nowrap text-muted-foreground tabular-nums" title={r.activity_time}>{when}</span>
                </a>
              </li>
            )
          })}
        </ul>
        {state.total != null && state.total > state.rows.length && (
          <p className="m-0 mt-1.5 text-xs text-muted-foreground">{t('incov.evidence.more', state.total - state.rows.length)}</p>
        )}
        <div className="mt-2 flex flex-wrap gap-2">
          {timelineParams && (
            <Button asChild variant="outline" size="sm" className="h-8 pointer-coarse:h-10">
              <a href={`?tab=activity&${new URLSearchParams(timelineParams).toString()}`}
                onClick={(e) => inApp(e, 'activity', timelineParams, onLeave)}>
                <Activity aria-hidden="true" />{t('incov.evidence.openTimeline')}
              </a>
            </Button>
          )}
          {first && !showRelated && (
            <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" data-slot="incident-evidence-related"
              onClick={() => setShowRelated(true)}>
              <ExternalLink aria-hidden="true" />{t('incov.evidence.related')}
            </Button>
          )}
        </div>
        {first && showRelated && (
          <div className="mt-2">
            <RelatedActivityPanel activityId={first.id}
              onOpenActivity={(id) => { onLeave?.(); navigateTo('activity', { act: String(id) }) }} />
          </div>
        )}
      </>
    )
  }

  return (
    <section data-slot="incident-check-evidence" data-monitor-type={monitor?.type || undefined} aria-labelledby={`inc-ev-${incident?.id}`}>
      <h3 id={`inc-ev-${incident?.id}`} className={SECTION}>{t('incov.evidence.title')}</h3>
      <p className="m-0 mb-2 text-xs text-muted-foreground">{t('incov.evidence.hint')}</p>
      {body}
    </section>
  )
}
