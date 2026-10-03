import { History, ExternalLink } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { eventLabel, parseDetail, relativeTime, eventDate, OUTCOME_KEYS } from '../audit/auditFormat.js'
import { SettingsSection } from '../SettingsControls.jsx'

/** Denetim Logu derin bağlantısı (`?tab=system` + `a_` süzgeçleri, son 7 gün). */
export function auditLink(types) {
  const p = new URLSearchParams()
  p.set('tab', 'system')
  p.set('a_eventType', (types || []).join(','))
  p.set('a_range', '7d')
  return `/?${p.toString()}`
}

const OUTCOME_VARIANT = { SUCCESS: 'secondary', FAILURE: 'destructive', BLOCKED: 'warning' }

/** Kanal / sonuç / neden — ayrıntı JSON'undan (kod ASLA yoktur; sunucu yazmaz). */
function detailBits(row) {
  const { detailObj } = parseDetail(row)
  const bits = []
  if (detailObj?.method) bits.push(detailObj.method)
  if (detailObj?.channel) bits.push(detailObj.channel)
  if (detailObj?.result) bits.push(detailObj.result)
  if (detailObj?.reason) bits.push(detailObj.reason)
  if (detailObj?.attempts_left != null) bits.push(`↺ ${detailObj.attempts_left}`)
  return bits
}

/**
 * Son kodla giriş etkinliği (2026-10-02) — denetim kaydındaki son 20 OTP olayı (istek / teslim hatası / yanlış kod /
 * süre doldu / kilit, kodla yapılan girişler ve doğru koddan sonra reddedilen girişler). Telefonda tablo değil KART
 * listesi (taşma yok); her satırda olay etiketi + sonuç rozeti, kullanıcı, zaman, IP ve kanal / iç neden.
 *
 * Test kancaları: `data-slot="lm-activity"`, satır `lm-activity-row` (`data-type`), boş `lm-activity-empty`.
 */
export default function LoginMethodsActivity({ rows, types }) {
  const t = useT()
  const list = Array.isArray(rows) ? rows : []
  return (
    <SettingsSection title={<span className="inline-flex items-center gap-2"><History aria-hidden="true" className="size-4" />{t('lm.activity.title')}</span>}
      description={t('lm.activity.desc')} contentClassName="flex min-w-0 flex-col gap-3">
      <div data-slot="lm-activity" className="flex min-w-0 flex-col gap-3">
        {list.length === 0 ? (
          <div data-slot="lm-activity-empty"><StatusBlock tone="neutral" title={t('lm.activity.empty')} /></div>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t('lm.activity.title')}>
            {list.map((r) => {
              const when = eventDate(r.event_time)
              const bits = detailBits(r)
              return (
                <li key={r.id} data-slot="lm-activity-row" data-type={r.event_type}
                  className="flex min-w-0 flex-col gap-1 rounded-lg border bg-card px-3 py-2 text-sm">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{eventLabel(r.event_type, t)}</span>
                    {r.outcome && OUTCOME_KEYS[r.outcome] && (
                      <Badge variant={OUTCOME_VARIANT[r.outcome] || 'outline'}>{t(OUTCOME_KEYS[r.outcome])}</Badge>
                    )}
                    <span className="ml-auto shrink-0 text-xs text-muted-foreground" title={when ? when.toLocaleString() : undefined}>
                      {relativeTime(r.event_time, t) || '—'}
                    </span>
                  </div>
                  <div className="flex min-w-0 flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                    <span className="font-mono text-foreground [overflow-wrap:anywhere]">{r.actor || '—'}</span>
                    {r.ip_address && <span className="font-mono [overflow-wrap:anywhere]">{r.ip_address}</span>}
                    {bits.length > 0 && <span className="[overflow-wrap:anywhere]">{bits.join(' · ')}</span>}
                  </div>
                  {r.failure_reason && (
                    <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{r.failure_reason}</p>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        <div className="flex justify-end">
          <Button asChild variant="link" className="min-h-10 px-0">
            <a href={auditLink(types)} data-slot="lm-activity-audit-link">
              <ExternalLink aria-hidden="true" /> {t('lm.activity.openAudit')}
            </a>
          </Button>
        </div>
      </div>
    </SettingsSection>
  )
}
