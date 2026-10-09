import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Activity, History, ShieldCheck, UserX } from 'lucide-react'
import { api, formatDateSec } from '../../../../api/client'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { useIsMobile } from '../../../../hooks/use-mobile.js'
import { relTimeOrRaw } from '../../../../utils/relativeTime.js'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import MaskedValue from '../../../ui/MaskedValue.jsx'
import TeamBadge from '../../../ui/TeamBadge.jsx'
import { ProgressBar } from '../../../ui/Progress.jsx'
import { eventLabel, OUTCOME_KEYS } from '../../audit/auditFormat.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { ChartContainer, BarChart, Bar, XAxis } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import ChannelBadge from './ChannelBadge.jsx'
import { CHANNEL_COLOR, channelLabel, fmtNum, fmtPct, miniPoints, reasonLabel, suppressedLabel } from './loginStatsModel.js'

/** Sheet, sayfanın yapışkan öğelerinin (kaydet çubuğu, üst çubuk) ÜSTÜNDE açılsın. */
const SHEET_Z = 'z-(--z-modal)'
const OUTCOME_VARIANT = { SUCCESS: 'secondary', FAILURE: 'destructive', BLOCKED: 'warning' }

function Section({ icon: Icon, title, children }) {
  return (
    <section className="flex min-w-0 flex-col gap-2.5">
      <h3 className="m-0 flex items-center gap-1.5 text-[11px] font-bold tracking-wider text-muted-foreground uppercase">
        <Icon className="size-3.5" aria-hidden="true" />{title}
      </h3>
      {children}
    </section>
  )
}

/** Son olay satırı: olay + sonuç + kanal, neden, zaman (göreli + tam), IP / konum / cihaz (gizliyse "Gizli"), anomali. */
function EventRow({ e, masked }) {
  const t = useT()
  const place = [e.city, e.country].filter(Boolean).join(', ')
  const hidden = masked && !('ip' in e)
  const reason = e.reason ? (e.event === 'LOGIN_OTP_REQUESTED' ? suppressedLabel(e.reason, t) : reasonLabel(e.reason, t)) : null
  return (
    <li data-slot="lm-user-event" data-event={e.event} className="flex min-w-0 flex-col gap-1 rounded-lg border bg-card px-3 py-2 text-sm">
      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{eventLabel(e.event, t)}</span>
        {e.outcome && OUTCOME_KEYS[e.outcome] && <Badge variant={OUTCOME_VARIANT[e.outcome] || 'outline'}>{t(OUTCOME_KEYS[e.outcome])}</Badge>}
        <ChannelBadge channel={e.channel} estimated={!!e.channel_estimated} />
      </div>
      {reason && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{reason}</p>}
      <div className="flex min-w-0 flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
        <span title={formatDateSec(e.time)} className="tabular-nums">{relTimeOrRaw(e.time, t)} · {formatDateSec(e.time)}</span>
        {hidden ? <MaskedValue /> : (
          <>
            {e.ip && <span className="font-mono [overflow-wrap:anywhere]">{e.ip}{place ? ` (${place})` : ''}</span>}
            {e.ua_summary && <span className="[overflow-wrap:anywhere]">{e.ua_summary}</span>}
          </>
        )}
        {e.flags && String(e.flags).split(',').filter(Boolean).map((f) => (
          <Badge key={f} variant="warning" className="font-normal">{t(`uact.anom_${f}`)}</Badge>
        ))}
      </div>
    </li>
  )
}

/**
 * Kullanıcının giriş istatistiği ayrıntısı (2026-10-03) — shadcn Sheet (geniş ekranda sağdan, telefonda alttan); URL
 * `lm_user=<kullanıcı adı>` ile derin bağlanır (Kullanıcı/Oturum giriş geçmişinden "Giriş istatistikleri" bağlantısı).
 * Başlık: ad, kullanıcı adı, takım, hesap kaynağı, aktif / pasif. Gövde: mini KPI'lar, kanal çubukları, başarısızlık
 * nedenleri, küçük trend, son olaylar (≤ 100; IP / cihaz sunucuda IdentityMask'ten geçer).
 * Test kancası: `data-slot="lm-user-sheet"` + `data-user`.
 */
export default function UserStatsSheet({ username, days, onClose }) {
  const t = useT()
  const locale = useDateLocale()
  const phone = useIsMobile()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const open = !!username

  // Yalnız EN SON isteğin yanıtı uygulanır: kişi / dönem hızlı değişince önceki kişinin geç gelen yanıtı yenisini ezmesin.
  const seqRef = useRef(0)
  const load = useCallback(async () => {
    const seq = ++seqRef.current
    if (!username) return
    setError(null)
    try {
      const r = await api.loginMethodsAdmin.statsUser(username, days)
      if (seq !== seqRef.current) return
      if (r?.success && r.data) setData(r.data)
      else setError(r?.error || t('lm.stats.user.err'))
    } catch (e) {
      if (seq !== seqRef.current) return
      setError(e?.message || t('lm.stats.user.err'))
    }
  }, [username, days, t])
  useEffect(() => { setData(null); load() }, [load])

  const u = data?.user || {}
  const tot = data?.totals || {}
  const channels = (data?.channels || []).filter((c) => Number(c.attempts) > 0)
  const points = useMemo(() => miniPoints(data?.series, data?.granularity, locale), [data, locale])
  const masked = data?.identity_masked === true
  const name = u.display_name || u.username || username

  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side={phone ? 'bottom' : 'right'} data-slot="lm-user-sheet" data-user={username || undefined}
        overlayClassName={SHEET_Z}
        className={cn(SHEET_Z, 'gap-0 p-0', phone ? 'max-h-[92dvh] rounded-t-xl' : 'w-full sm:max-w-xl')}>
        <SheetHeader className="gap-2 border-b p-4 pr-14">
          <SheetTitle className="text-lg leading-tight break-words">{name || t('lm.stats.user.title')}</SheetTitle>
          <SheetDescription className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <span className="font-mono">{u.username || username}</span>
            {u.team_name && <TeamBadge teamId={u.team_id} teamName={u.team_name} />}
          </SheetDescription>
          {data && (
            <div className="flex flex-wrap gap-1.5">
              {u.source && <Badge variant="outline">{u.source === 'LDAP' ? t('lm.stats.source.LDAP') : t('lm.stats.source.LOCAL')}</Badge>}
              {u.active === false && <Badge variant="destructive">{t('lm.stats.passive')}</Badge>}
              {u.active === true && <Badge variant="secondary">{t('lm.stats.active')}</Badge>}
            </div>
          )}
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          {error ? (
            <AlertBanner tone="danger" className="mb-0" title={t('lm.stats.user.err')}
              actions={<Button type="button" variant="outline" size="sm" onClick={load} className="min-h-10 sm:min-h-8">{t('lm.retry')}</Button>}>
              {error}
            </AlertBanner>
          ) : !data ? (
            <div data-slot="lm-user-loading" aria-busy="true" className="flex flex-col gap-3">
              <Skeleton className="h-16 w-full" /><Skeleton className="h-24 w-full" /><Skeleton className="h-32 w-full" />
            </div>
          ) : (
            <>
              {!data.found && (
                <div data-slot="lm-user-not-found"><AlertBanner tone="info" className="mb-0">{t('lm.stats.user.notFound')}</AlertBanner></div>
              )}
              <dl data-slot="lm-user-kpis" className="m-0 grid grid-cols-2 gap-3 sm:grid-cols-4">
                {[['attempts', fmtNum(tot.attempts, locale)], ['success', fmtNum(tot.success, locale)],
                  ['failed', fmtNum(tot.failed, locale)], ['rate', fmtPct(tot.success_rate, locale)]].map(([k, v]) => (
                  <div key={k} className="flex min-w-0 flex-col gap-0.5 rounded-lg border px-3 py-2">
                    <dt className="truncate text-xs text-muted-foreground">{t(`lm.stats.kpi.${k}`)}</dt>
                    <dd className={cn('m-0 text-lg font-semibold tabular-nums', k === 'failed' && Number(tot.failed) > 0 && 'text-destructive')}>{v}</dd>
                  </div>
                ))}
              </dl>
              {Number(data.estimated) > 0 && (
                <p className="m-0 text-xs text-muted-foreground" data-slot="lm-user-estimated">{t('lm.stats.estimated', fmtNum(data.estimated, locale))}</p>
              )}

              <Section icon={Activity} title={t('lm.stats.user.channels')}>
                {channels.length === 0 ? <p className="m-0 text-sm text-muted-foreground">{t('lm.stats.ch.noData')}</p> : (
                  <ul className="m-0 flex list-none flex-col gap-3 p-0">
                    {channels.map((c) => (
                      <li key={c.channel} data-slot="lm-user-channel" data-channel={c.channel} className="flex min-w-0 flex-col gap-1">
                        <div className="flex min-w-0 items-baseline justify-between gap-2 text-sm">
                          <span className="min-w-0 truncate">{channelLabel(c.channel, t)}</span>
                          <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                            {t('lm.stats.user.channelLine', fmtNum(c.success, locale), fmtNum(c.failed, locale), fmtPct(c.success_rate, locale))}
                          </span>
                        </div>
                        <ProgressBar value={c.success_rate == null ? 0 : Math.round(Number(c.success_rate) * 1000) / 10} size="sm" decorative
                          tone={c.success_rate >= 0.9 ? 'ok' : c.success_rate >= 0.6 ? 'warn' : 'crit'} />
                      </li>
                    ))}
                  </ul>
                )}
              </Section>

              <Section icon={ShieldCheck} title={t('lm.stats.reasons.title')}>
                {(data.failure_reasons || []).length === 0 ? <p className="m-0 text-sm text-muted-foreground">{t('lm.stats.reasons.empty')}</p> : (
                  <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
                    {data.failure_reasons.map((r) => (
                      <li key={r.reason}>
                        <Badge variant="outline" data-reason={r.reason} className="gap-1 font-normal">
                          {reasonLabel(r.reason, t)} <span className="font-semibold tabular-nums">{fmtNum(r.count, locale)}</span>
                        </Badge>
                      </li>
                    ))}
                  </ul>
                )}
              </Section>

              {points.some((p) => p.success > 0 || p.failed > 0) && (
                <Section icon={Activity} title={t('lm.stats.user.trend')}>
                  <ChartContainer config={{ ok: { label: t('lm.stats.kpi.success'), color: 'var(--chart-2)' }, bad: { label: t('lm.stats.kpi.failed'), color: CHANNEL_COLOR.failed } }}
                    className="aspect-auto h-[110px] w-full">
                    <BarChart data={points} margin={{ top: 4, right: 4, left: 4, bottom: 0 }} barCategoryGap="20%">
                      <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={16} tick={{ fontSize: 10 }} />
                      <Bar dataKey="success" stackId="u" fill="var(--color-ok)" isAnimationActive={false} />
                      <Bar dataKey="failed" stackId="u" fill="var(--color-bad)" radius={[2, 2, 0, 0]} isAnimationActive={false} />
                    </BarChart>
                  </ChartContainer>
                </Section>
              )}

              <Section icon={History} title={t('lm.stats.user.recent')}>
                {(data.recent || []).length === 0 ? (
                  <StatusBlock tone="neutral" icon={UserX} title={t('lm.stats.user.recentEmpty')} />
                ) : (
                  <ol className="m-0 flex list-none flex-col gap-2 p-0">
                    {data.recent.map((e, i) => <EventRow key={e.id ?? `${e.time}-${i}`} e={e} masked={masked} />)}
                  </ol>
                )}
              </Section>
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
