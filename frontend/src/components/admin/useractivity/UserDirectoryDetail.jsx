import { useState } from 'react'
import {
  Activity, AtSign, BarChart3, ChevronDown, Compass, Copy, ExternalLink, Globe, History, IdCard, LogIn, LogOut, Mail, Monitor,
  ShieldCheck, Smartphone, Tablet, Terminal, Unlock, UserX,
} from 'lucide-react'
import { navigateTo } from '../../../utils/navigate.js'
import ChannelBadge from '../loginmethods/stats/ChannelBadge.jsx'
import { channelOfLoginMethod, loginStatsParams } from '../loginmethods/stats/loginStatsModel.js'
import { useT } from '../../../i18n/index.jsx'
import { formatDateSec } from '../../../api/client'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { ProgressBar, Spinner } from '../../ui/Progress.jsx'
import { OrgRoleBadge, SystemRoleBadge } from '../ToneBadge.jsx'
import { AuthSourceBadge, Pill } from '../HealthUi.jsx'
import { idHidden, tabLabel } from './uactModel.js'
import { AccountBadges, DirField, IdleText, MaskedValue, OVER_MODAL_Z, PresenceAvatar, PresenceBadge, TourPill, useDirFormat } from './DirectoryParts.jsx'
import { parseUserAgent, rowActions, splitDisplayName, timeoutUsedPct } from './directoryModel.js'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı Dizini AYRINTI paneli (2026-09-28) — shadcn Sheet: masaüstü/tablette sağdan, telefonda alttan. Pencerenin
 * ÜSTÜNDE açılır (`OVER_MODAL_Z`). İçerik: kimlik başlığı (durum noktalı avatar, ad, kullanıcı adı · departman, rozetler),
 * hızlı eylemler (e-posta, kopyala, giriş geçmişi), oturum (açılış, süre, boşta, son sayfa, zaman aşımı çubuğu),
 * bağlantı (IP, konum, kuruluş, User-Agent'tan okunur tarayıcı / işletim sistemi / cihaz — sunucu bunları yalnız global
 * admin ve denetçiye gönderir; gelmediyse not), hesap, giriş geçmişi, ürün turu ve YETKİYE BAĞLI yönetici işlemleri
 * (`rowActions` — işlem menüsüyle TEK kaynak). Sonlandırma mevcut gerekçeli onay penceresine (TerminateModal) gider.
 *
 * Satır, dizin verisi yenilendiğinde KULLANICI ADIYLA yeniden bulunur (panel açık kalır); kişi listeden düştüyse
 * "artık dizinde yok" durumu çizilir. Test kancası: `data-slot="udir-detail"`.
 */

const DEVICE_ICON = { desktop: Monitor, mobile: Smartphone, tablet: Tablet, bot: Terminal }

function Section({ icon: Icon, title, children, className }) {
  return (
    <section className={cn('flex min-w-0 flex-col gap-2.5', className)}>
      <h3 className="flex items-center gap-1.5 text-[11px] font-bold tracking-wider text-muted-foreground uppercase">
        <Icon className="size-3.5" aria-hidden="true" />{title}
      </h3>
      {children}
    </section>
  )
}
/** İki sütun kaba göre (sağ panel ~416 px, telefon ~358 px → ikisi de iki sütun; çok dar kapta tek). */
const GRID = 'grid grid-cols-1 gap-x-5 gap-y-3 @xs:grid-cols-2'

function Stamp({ iso }) {
  const { rel } = useDirFormat()
  if (!iso) return '—'
  return <><span className="tabular-nums">{formatDateSec(iso)}</span> <span className="text-muted-foreground">· {rel(iso)}</span></>
}

function SessionSection({ row }) {
  const { t, rel, dur } = useDirFormat()
  if (!row.online) {
    return (
      <p className="text-sm text-muted-foreground" data-slot="udir-offline-note">
        {t('udir.notOnline')}{row.last_seen ? ` ${t('udir.lastSeenWas', rel(row.last_seen))}` : ''}
      </p>
    )
  }
  const pct = timeoutUsedPct(row)
  const left = Number(row.expires_in_sec)
  const tone = !(left >= 0) ? undefined : left < 600 ? 'crit' : left < 1800 ? 'warn' : 'ok'
  return (
    <>
      <dl className={GRID}>
        <DirField label={t('udir.sessionStarted')}><Stamp iso={row.login_at} /></DirField>
        <DirField label={t('udir.sessionLength')}>{dur((row.duration_min || 0) * 60)}</DirField>
        <DirField label={t('uact.colIdle')}><IdleText row={row} /></DirField>
        <DirField label={t('udir.lastPage')}>
          {row.last_tab ? <>{tabLabel(row.last_tab, t)}{row.last_tab_at ? <span className="text-muted-foreground"> · {rel(row.last_tab_at)}</span> : null}</> : '—'}
        </DirField>
      </dl>
      {pct != null && (
        <div className="flex flex-col gap-1.5" data-slot="udir-timeout">
          <div className="flex items-baseline justify-between gap-2 text-xs">
            <span className="font-medium text-muted-foreground">{t('udir.timeoutBar')}</span>
            <span className="tabular-nums">{t('udir.timesOutIn', dur(left))}</span>
          </div>
          {/* Değer hemen üstte METİN olarak duruyor → çubuk ekran okuyucuya ikinci kez duyurulmaz (decorative). */}
          <ProgressBar value={pct} tone={tone} size="sm" decorative />
        </div>
      )}
    </>
  )
}

function ConnectionSection({ row }) {
  const t = useT()
  const [showUa, setShowUa] = useState(false)
  if (row.conn_masked) return <AlertBanner tone="info">{t('udir.connMasked')}</AlertBanner>
  const ua = parseUserAgent(row.user_agent)
  const DevIcon = DEVICE_ICON[ua?.device] || Monitor
  const place = [row.city, row.country].filter(Boolean).join(', ')
  return (
    <>
      <dl className={GRID}>
        <DirField label={t('uact.colIp')} mono>{row.ip || '—'}</DirField>
        <DirField label={t('uact.colLocation')}>{place || '—'}</DirField>
        <DirField label={t('udir.org')}>{row.org || '—'}</DirField>
        <DirField label={t('uact.colBrowser')}>{ua ? [ua.browser, ua.version].filter(Boolean).join(' ') || '—' : '—'}</DirField>
        <DirField label={t('udir.os')}>{ua?.os || '—'}</DirField>
        <DirField label={t('udir.device')}>
          {ua ? <span className="inline-flex items-center gap-1.5"><DevIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />{t(`udir.device.${ua.device}`)}</span> : '—'}
        </DirField>
      </dl>
      {row.user_agent && (
        <Collapsible open={showUa} onOpenChange={setShowUa}>
          <CollapsibleTrigger asChild>
            <Button type="button" variant="link" size="sm" className="h-auto gap-1 p-0 text-xs has-[>svg]:px-0 pointer-coarse:min-h-10">
              <ChevronDown className={cn('size-3.5 transition-transform motion-reduce:transition-none', showUa && 'rotate-180')} aria-hidden="true" />
              {showUa ? t('udir.hideUa') : t('uact.reveal')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <p className="mt-1.5 rounded-md bg-muted px-2.5 py-2 font-mono text-[11px] leading-relaxed break-all">{row.user_agent}</p>
          </CollapsibleContent>
        </Collapsible>
      )}
    </>
  )
}

function AccountSection({ row, globalAdmin }) {
  const t = useT()
  const extra = [...new Set((row.team_ids || []).map(String))].filter((id) => id !== String(row.team_id ?? ''))
  return (
    <dl className={GRID}>
      <DirField label={t('uact.detailAccountState')}><AccountBadges row={row} /></DirField>
      <DirField label={t('uact.colCreated')}><Stamp iso={row.created_at} /></DirField>
      <DirField label={t('uact.detailEmail')} mono full>{row.email || '—'}</DirField>
      <DirField label={t('uact.colTeam')}>{row.team_name ? <TeamBadge teamId={row.team_id} teamName={row.team_name} /> : '—'}</DirField>
      <DirField label={t('uact.detailExtraTeams')}>
        {extra.length ? <span className="inline-flex flex-wrap gap-1">{extra.map((id) => <TeamBadge key={id} teamId={id} size={11} />)}</span> : '—'}
      </DirField>
      <DirField label={t('uact.detailTitle')}>{[row.title, row.department].filter(Boolean).join(' · ') || '—'}</DirField>
      <DirField label={t('uact.detailEmployeeId')} mono={globalAdmin}>{globalAdmin ? (row.employee_id || '—') : t('uact.masked')}</DirField>
    </dl>
  )
}

function SignInSection({ row, masked = false, globalAdmin = false }) {
  const t = useT()
  // Giriş IP'leri yalnız global yönetici + denetçiye (ve kişinin kendi satırında) gelir; gelmediyse "Gizli" — "kayıt yok"
  // ("—") ile karışmasın (2026-09-28c ek-6).
  const ip = (key) => (idHidden(row, key, masked) ? <MaskedValue /> : (row[key] || '—'))
  const failed = Number(row.failed_since_login) || 0
  const before = Number(row.failed_before_login) || 0
  // 2026-10-03: son giriş yöntemi KANAL rozetiyle (PASSWORD → hesap kaynağına göre LDAP / yerel şifre)
  const channel = channelOfLoginMethod(row.last_login_method, row.auth_source)
  return (
    <>
    <dl className={GRID}>
      <DirField label={t('udir.lastSignIn')}>{row.last_login_at ? <Stamp iso={row.last_login_at} /> : <Pill tone="never" status="never">{t('uact.st.never')}</Pill>}</DirField>
      <DirField label={t('udir.signInMethod')}>{channel ? <ChannelBadge channel={channel} /> : (row.last_login_method || '—')}</DirField>
      <DirField label={t('udir.lastSignInIp')} mono>{ip('last_login_ip')}</DirField>
      <DirField label={t('udir.prevSignIn')}><Stamp iso={row.prev_login_at} /></DirField>
      <DirField label={t('udir.prevSignInIp')} mono>{ip('prev_login_ip')}</DirField>
      <DirField label={t('udir.lastFailed')}>
        {row.last_failed_at ? <><Stamp iso={row.last_failed_at} />{row.last_failed_reason ? <span className="block text-xs text-muted-foreground">{row.last_failed_reason}</span> : null}</> : '—'}
      </DirField>
      <DirField label={t('udir.lastFailedIp')} mono>{ip('last_failed_ip')}</DirField>
      <DirField label={t('udir.failedSinceLbl')}><span data-failed-count={failed} className={cn('tabular-nums', failed > 0 && 'font-semibold text-destructive')}>{failed}</span></DirField>
      {before > 0 && <DirField label={t('udir.failedBeforeLbl')}><span className="tabular-nums">{before}</span></DirField>}
    </dl>
    {/* Giriş istatistikleri (Ayarlar → Giriş Yöntemleri → İstatistikler) yalnız global yöneticiye açık — bağlantı da öyle */}
    {globalAdmin && (
      <Button type="button" variant="outline" className="h-10 justify-start sm:pointer-fine:h-8" data-slot="udir-login-stats"
        onClick={() => navigateTo('settings', loginStatsParams(row.username))}>
        <BarChart3 aria-hidden="true" />{t('lm.stats.user.openStats')}
      </Button>
    )}
    </>
  )
}

function Header({ row, self }) {
  const t = useT()
  const { name, dept } = splitDisplayName(row.display_name, row.department)
  return (
    <>
      <div className="flex min-w-0 items-start gap-3">
        <PresenceAvatar row={row} size="lg" />
        <div className="min-w-0 flex-1">
          <SheetTitle className="flex flex-wrap items-center gap-1.5 text-lg leading-tight">
            <span className="min-w-0 break-words">{name || row.username}</span>
            {self && <Pill>{t('uact.selfSession')}</Pill>}
          </SheetTitle>
          <SheetDescription className="mt-0.5 truncate">
            <span className="font-mono">{row.username}</span>{dept ? ` · ${dept}` : ''}
          </SheetDescription>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        <PresenceBadge row={row} />
        {row.system_role && <SystemRoleBadge role={row.system_role} />}
        {row.org_role && <OrgRoleBadge role={row.org_role}>{t('usr.orgRoleVal.' + row.org_role)}</OrgRoleBadge>}
        <AuthSourceBadge source={row.auth_source} localLabel={t('usr.authLocal')} />
        <AccountBadges row={row} />
      </div>
    </>
  )
}

export default function UserDirectoryDetail({ open, row, phone, ctx, busy, onClose, onCopy, onHistory, onOpenAdmin, onTerminate, onTourReset, onUnlock }) {
  const t = useT()
  const a = row ? rowActions(row, ctx) : null
  const admin = a && (a.openAdmin || a.tourReset || a.unlock || a.terminate)
  const btn = 'h-10 sm:pointer-fine:h-8'
  const icon = 'size-10 shrink-0 sm:pointer-fine:size-8'
  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side={phone ? 'bottom' : 'right'} data-slot="udir-detail" data-user={row?.username}
        {...(row ? {} : { 'aria-describedby': undefined })}
        overlayClassName={OVER_MODAL_Z}
        className={cn(OVER_MODAL_Z, 'gap-0 p-0', phone ? 'max-h-[92dvh] rounded-t-xl' : 'w-full sm:max-w-md')}>
        {!row ? (
          <>
            <SheetHeader className="border-b pr-14">
              <SheetTitle>{t('udir.detailTitle')}</SheetTitle>
            </SheetHeader>
            <StatusBlock icon={UserX} title={t('udir.gone')} />
          </>
        ) : (
          <>
            <SheetHeader className="gap-3 border-b p-4 pr-14">
              <Header row={row} self={a.self} />
              <div className="flex gap-2" data-slot="udir-quick" role="group" aria-label={t('udir.quickActions')}>
                <Button type="button" variant="outline" size="sm" className={cn(btn, 'min-w-0 flex-1')} onClick={() => onHistory(row)}>
                  <History aria-hidden="true" /><span className="truncate">{t('udir.actHistoryShort')}</span>
                </Button>
                {a.mail && (
                  <Button asChild variant="outline" size="icon-sm" className={icon}>
                    <a href={`mailto:${row.email}`} aria-label={t('uact.actMail')} title={t('uact.actMail')}><Mail aria-hidden="true" /></a>
                  </Button>
                )}
                {a.mail && (
                  <Button type="button" variant="outline" size="icon-sm" className={icon} onClick={() => onCopy('email', row)}
                    aria-label={t('udir.actCopyEmail')} title={t('udir.actCopyEmail')}><Copy aria-hidden="true" /></Button>
                )}
                <Button type="button" variant="outline" size="icon-sm" className={icon} onClick={() => onCopy('user', row)}
                  aria-label={t('uact.actCopyUser')} title={t('uact.actCopyUser')}><AtSign aria-hidden="true" /></Button>
              </div>
            </SheetHeader>
            <div className="@container flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
              <Section icon={Activity} title={t('uact.detailSession')}><SessionSection row={row} /></Section>
              {row.online && <Section icon={Globe} title={t('udir.secConnection')}><ConnectionSection row={row} /></Section>}
              <Section icon={IdCard} title={t('uact.detailAccount')}><AccountSection row={row} globalAdmin={!!ctx?.globalAdmin} /></Section>
              <Section icon={LogIn} title={t('uact.detailLoginHistory')}><SignInSection row={row} masked={!!ctx?.identityMasked} globalAdmin={!!ctx?.globalAdmin} /></Section>
              <Section icon={Compass} title={t('udir.secTour')}>
                <dl className={GRID}>
                  <DirField label={t('uact.colTour')}><TourPill row={row} withLabel={false} /></DirField>
                  <DirField label={t('udir.tourAt')}>{row.tour_at ? <Stamp iso={row.tour_at} /> : '—'}</DirField>
                </dl>
              </Section>
              {admin && (
                <Section icon={ShieldCheck} title={t('udir.secAdmin')}>
                  <div className="flex flex-col gap-2" data-slot="udir-admin-actions">
                    {a.openAdmin && <Button type="button" variant="outline" className="h-10 justify-start" onClick={() => onOpenAdmin(row)}><ExternalLink aria-hidden="true" />{t('uact.actOpenAdmin')}</Button>}
                    {a.tourReset && (
                      <Button type="button" variant="outline" className="h-10 justify-start" disabled={busy} aria-busy={busy || undefined} onClick={() => onTourReset(row)}>
                        {busy ? <Spinner size={14} decorative /> : <Compass aria-hidden="true" />}{t('usr.tourReset')}
                      </Button>
                    )}
                    {a.unlock && (
                      <Button type="button" variant="outline" className="h-10 justify-start" disabled={busy} aria-busy={busy || undefined} onClick={() => onUnlock(row)}>
                        {busy ? <Spinner size={14} decorative /> : <Unlock aria-hidden="true" />}{t('uact.actUnlock')}
                      </Button>
                    )}
                    {a.terminate && <Button type="button" variant="destructive" className="h-10 justify-start" onClick={() => onTerminate(row)}><LogOut aria-hidden="true" />{t('udir.actTerminate')}</Button>}
                  </div>
                </Section>
              )}
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  )
}
