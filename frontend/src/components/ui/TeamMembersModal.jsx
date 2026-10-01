import { useEffect, useRef, useState } from 'react'
import { Building2, Mail, PhoneCall, RefreshCw, ShieldAlert, UserRound, Users } from 'lucide-react'
import ModalShell from './ModalShell.jsx'
import TeamMemberCards, { PersonAvatar, adSoyadInitials, photoIdOf } from './TeamMemberCards.jsx'
import { CallListPanel, EscalationPanel } from './TeamContactPanels.jsx'
import AlertBanner from './AlertBanner.jsx'
import CopyButton from './CopyButton.jsx'
import { Spinner } from './Progress.jsx'
import { TONE_CLASS } from '../admin/ToneBadge.jsx'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { commonUnit, memberSeed, resolveModalLeader, resolveModalManager } from './teamMembersModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'

/**
 * Takım üyeleri penceresi — takım adı (TeamBadge) tıklanınca her yüzeyden açılır; yönetim ekranı (TeamManager)
 * aynı pencereyi kendi kapsamlı yükleyicisi + `canManage`/`onEditUser` ile açar.
 *
 * <p><b>Yeniden tasarım (2026-09-28, shadcn + mweb):</b>
 * <ul>
 *   <li>Başlık özeti: açıklama (varsa), üye sayısı, pasif takım rozeti, üyelerin ORTAK müdürlüğü (tekse) ve üç kişi
 *       çipi — Takım Müdürü (TEK kişi), Takım Lideri, takım e-postası (mailto + kopyala). Müdür ve lider AYRI
 *       kavramlardır: lider `leader_id`; müdür elle atanmış `manager_id` ya da üyelerin yönetim zincirinden türetilir
 *       (`utils/teamManager.js`); yönetim ekranı sütunla aynı kaydı `teamManager` ile verir.</li>
 *   <li>Sekmeler: Üyeler (arama/sıralama/rol çipleri, masaüstünde sütunlu yoğun liste) · Eskalasyon kişileri
 *       (seviyeye göre gruplu) · 7/24 arama listesi (salt okunur; yalnız görebilen kullanıcıda — 403/404'te sekme
 *       hiç çıkmaz; telefon numarası ASLA çizilmez).</li>
 *   <li>Telefonda (&lt;640) tam ekran; araç çubuğu gövde kayarken yapışkan; dokunma hedefleri ≥40 px.</li>
 * </ul>
 *
 * <p><b>Takım Müdürü üye listesine ASLA eklenmez</b> (prod hatası 2026-09-26): yalnız başlık çipinde durur; gerçekten
 * üyeyse satırında "Takım Müdürü" rozeti taşır.
 *
 * <p><b>Yarış koruması:</b> yükleme sırası sayacı (`seq`) — `refreshKey` art arda değişince ya da takım değişince eski
 * yanıt yenisini EZMEZ. Aynı takımın yeniden yüklenmesinde eski veri ekranda kalır (başlıkta küçük döner simge).
 *
 * <p><b>Gizlilik:</b> kurum-geneli uç beyaz-listelidir (telefon/sicil DÖNMEZ; sistem rolü + `has_photo` 2026-09-28
 * kullanıcı kararıyla döner); yönetim yükleyicisi tam entity verse de bu pencere telefon/sicil çizmez. Fotoğraf
 * `/api/users/{id}/photo`'dan.
 *
 * Test kancaları: `team-overview`, `team-member-count`, `team-manager`, `team-leader`, `team-mailbox`, `team-unit`,
 * ayrıca `TeamMemberCards` ve `TeamContactPanels` kancaları.
 */

/** Telefonda (<640 px) tam ekran — ReportParts/DiagnosticsModal ile aynı kalıp; geniş ekranda ModalShell `lg`. */
const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-[100dvh] max-sm:max-h-none max-sm:max-w-none max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:p-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'

const EMPTY_STATE = { teamId: null, loading: true, data: null, error: null }
const EMPTY_CALLS = { teamId: null, status: 'loading', rows: [], error: null }
/** Arama listesi bu durumlarda HİÇ gösterilmez: yetki yok (403), takım yok (404), oturum yok (401 → null yanıt). */
const CALLS_HIDDEN_STATUS = new Set([401, 403, 404])

const sameId = (a, b) => a != null && b != null && String(a) === String(b)

function CountPill({ n }) {
  return (
    <Badge variant="secondary" className="h-5 min-w-5 rounded-full px-1.5 text-[11px] tabular-nums">{n}</Badge>
  )
}

/** Kişi çipi (Takım Müdürü / Takım Lideri): baş harf avatarı + görev + ad + (üyeyse) unvan. Boşsa "Tanımlı değil". */
function PersonChip({ slot, caption, person, t, alsoLeader = false }) {
  const member = person?.member || null
  return (
    <div data-slot={slot} data-empty={person ? undefined : 'true'} data-also-leader={alsoLeader ? 'true' : undefined}
      className="flex min-w-0 items-center gap-2.5 rounded-lg border bg-card px-2.5 py-2.5 sm:gap-3 sm:px-3">
      {person
        ? <PersonAvatar name={person.label} seed={member ? memberSeed(member) : person.label} initials={member ? adSoyadInitials(member) : undefined}
            photoId={member ? photoIdOf(member) : (person.userId ?? null)} />
        : (
          <span aria-hidden="true" className="grid size-9 shrink-0 place-items-center rounded-full border border-dashed text-muted-foreground">
            <UserRound className="size-4" />
          </span>
        )}
      <div className="min-w-0 flex-1 leading-snug">
        <div className="text-xs font-medium text-muted-foreground">{caption}</div>
        {person
          ? <div className="text-sm font-semibold break-words">{person.label}</div>
          : <div className="text-sm text-muted-foreground">{t('team.notSet')}</div>}
        {member?.title && <div className="text-xs break-words text-muted-foreground">{member.title}</div>}
      </div>
    </div>
  )
}

/** Takım e-postası çipi: metin bloğunun TAMAMI mailto bağlantısı (dokunma hedefi büyük) + kopyala. */
function MailboxChip({ email, teamName, t }) {
  return (
    <div data-slot="team-mailbox" className="col-span-2 flex min-w-0 items-center gap-3 rounded-lg border bg-card px-3 py-2.5 lg:col-span-1">
      <span aria-hidden="true" className="grid size-9 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground">
        <Mail className="size-4" />
      </span>
      <a href={`mailto:${email}`} className="flex min-h-10 min-w-0 flex-1 flex-col justify-center leading-snug no-underline">
        <span className="text-xs font-medium text-muted-foreground">{t('team.mailboxLabel')}</span>
        <span className="text-sm font-semibold break-all text-primary hover:underline">{email}</span>
      </a>
      <CopyButton value={email} variant="ghost" buttonSize="icon-sm"
        label={t('a11y.rowAction', teamName || email, t('team.copyMailbox'))}
        copiedLabel={t('a11y.rowAction', teamName || email, t('team.emailCopied'))}
        className="shrink-0 text-muted-foreground max-sm:size-10 pointer-coarse:size-10" />
    </div>
  )
}

/** İlk yüklemede satır iskeleti — gerçek satırla aynı yükseklikte (zıplama yok); duyuru `role=status`. */
function RowsSkeleton({ label, rows = 5 }) {
  return (
    <div role="status" aria-live="polite" data-slot="team-loading" className="overflow-hidden rounded-lg border">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} aria-hidden="true" className="flex items-center gap-3 border-b px-3 py-3 last:border-b-0">
          <Skeleton className="size-9 shrink-0 rounded-full motion-reduce:animate-none" />
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <Skeleton className="h-3.5 w-2/5 motion-reduce:animate-none" />
            <Skeleton className="h-3 w-3/5 motion-reduce:animate-none" />
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * @param onViewUser  (yönetim ekranı, 2026-09-30) Üyeler sekmesinde üyenin kullanıcı detayını açar — ad düğmesi + satır
 *                    sonu ikonu (`TeamMemberCards.onView`). Yoksa üye satırı salt bilgidir (kurum-geneli rozet penceresi).
 */
export default function TeamMembersModal({ team, open, onClose, canManage = false, onEditUser, onViewUser, loadMembers, managerLabelFor, refreshKey = 0, teamManager }) {
  const t = useT()
  const teamId = team?.id ?? null
  const [state, setState] = useState(EMPTY_STATE)
  const [calls, setCalls] = useState(EMPTY_CALLS)
  const [tabState, setTabState] = useState({ teamId: null, tab: 'members' })
  const [retry, setRetry] = useState(0)
  const seq = useRef(0)
  const callSeq = useRef(0)

  // Üyeler + eskalasyon kişileri. Aynı takımın yeniden yüklenmesinde (refreshKey) eski veri kalır; takım değişince sıfır.
  useEffect(() => {
    if (!open || teamId == null) return
    const id = ++seq.current
    setState((prev) => (prev.teamId === teamId && prev.data
      ? { ...prev, loading: true, error: null }
      : { teamId, loading: true, data: null, error: null }))
    const loader = loadMembers || ((tid) => api.teams.members(tid))
    const fail = (msg) => setState((prev) => ({
      teamId, loading: false, data: prev.teamId === teamId ? prev.data : null, error: msg || t('team.membersLoadError'),
    }))
    Promise.resolve()
      .then(() => loader(teamId))
      .then((res) => {
        if (id !== seq.current) return
        if (res?.success) setState({ teamId, loading: false, data: res.data || {}, error: null })
        else fail(res?.error)
      })
      .catch((e) => { if (id === seq.current) fail(e?.message) })
  }, [open, teamId, loadMembers, refreshKey, retry, t])

  // 7/24 arama listesi — salt okunur; görme yetkisi yoksa sekme hiç çizilmez.
  useEffect(() => {
    if (!open || teamId == null) return
    const id = ++callSeq.current
    setCalls((prev) => (prev.teamId === teamId && prev.status === 'ok' ? prev : { teamId, status: 'loading', rows: [], error: null }))
    Promise.resolve()
      .then(() => api.noc?.getCallList?.(teamId))
      .then((res) => {
        if (id !== callSeq.current) return
        if (res?.success === true) setCalls({ teamId, status: 'ok', rows: Array.isArray(res.data) ? res.data : [], error: null })
        else if (res == null || CALLS_HIDDEN_STATUS.has(res.status)) setCalls({ teamId, status: 'hidden', rows: [], error: null })
        else setCalls({ teamId, status: 'error', rows: [], error: res.error || t('noc.clLoadError') })
      })
      .catch(() => { if (id === callSeq.current) setCalls({ teamId, status: 'error', rows: [], error: t('noc.clLoadError') }) })
  }, [open, teamId, refreshKey, retry, t])

  if (!open || !team) return null

  const data = state.teamId === teamId ? state.data : null
  // Sunucunun takım satırı esastır; rehberin/yönetim satırının taşıdığı ek alanlar (manager_id, description) korunur.
  const info = { ...team, ...(data?.team || {}) }
  const name = info.name || team.name || ''
  const members = data?.members || []
  const contacts = data?.escalation_contacts || []
  const loadingFirst = !data && state.loading
  const callRows = calls.teamId === teamId ? calls.rows : []
  const callStatus = calls.teamId === teamId ? calls.status : 'loading'
  const showCalls = callStatus === 'ok' || callStatus === 'error'
  const wanted = tabState.teamId === teamId ? tabState.tab : 'members'
  const tab = wanted === 'calllist' && !showCalls ? 'members' : wanted
  const setTab = (v) => setTabState({ teamId, tab: v })

  const leader = data || info.leader_display_name
    ? resolveModalLeader({ leaderId: info.leader_id ?? info.leaderId, leaderName: info.leader_display_name, members })
    : null
  const manager = data
    ? resolveModalManager({ teamManager, managerId: info.manager_id ?? info.managerId, leaderId: info.leader_id ?? info.leaderId, members, labelFor: managerLabelFor })
    : null
  const managerIsLeader = manager && leader && sameId(manager.userId, leader.userId)
  const unit = commonUnit(members)
  const count = members.length

  // Aynı kişi üç sekmede aynı avatar rengini alsın: eskalasyon kişisi e-postasıyla, arama listesi kimliğiyle üyeye bağlanır.
  const seedByEmail = new Map()
  const seedById = new Map()
  for (const m of members) {
    if (m.email) seedByEmail.set(String(m.email).toLowerCase(), memberSeed(m))
    if (m.id != null) seedById.set(String(m.id), memberSeed(m))
  }
  const contactSeed = (c) => seedByEmail.get(String(c.email || '').toLowerCase()) || c.email || c.name
  const callSeed = (r) => seedById.get(String(r.user_id)) || r.display_name

  const openCoverage = () => {
    onClose?.()
    navigateTo('noc', { n_ct: String(teamId) })
  }
  const retryButton = (
    <Button type="button" variant="outline" size="sm" onClick={() => setRetry((n) => n + 1)} className="max-sm:h-10 pointer-coarse:h-10">
      <RefreshCw aria-hidden="true" /> {t('team.retry')}
    </Button>
  )

  return (
    <ModalShell open={open} onClose={onClose} title={t('team.membersModalTitle', name)} icon={Users} size="lg" scrollBody
      className={`${PHONE_FULLSCREEN} sm:h-[min(88vh,calc(100dvh-2rem))]`}
      headerExtra={state.loading && data ? <Spinner size={14} label={t('team.refreshing')} className="text-muted-foreground" /> : null}>
      <div className="flex min-w-0 flex-col gap-4">
        <section data-slot="team-overview" aria-label={t('team.overviewLabel')} className="flex min-w-0 flex-col gap-3">
          {info.description && <p className="m-0 text-sm text-muted-foreground">{info.description}</p>}
          <div className="flex min-w-0 flex-wrap items-center gap-1.5">
            {loadingFirst
              ? <Skeleton className="h-5 w-20 rounded-full motion-reduce:animate-none" />
              : data && (
                <Badge variant="secondary" data-slot="team-member-count" className="tabular-nums">
                  <Users aria-hidden="true" /> {t(count === 1 ? 'team.membersCount.one' : 'team.membersCount', count)}
                </Badge>
              )}
            {info.active === false && <Badge variant="outline" className={TONE_CLASS.danger}>{t('team.inactive')}</Badge>}
            {unit && (
              <Badge variant="outline" data-slot="team-unit" className="max-w-full font-normal">
                <Building2 aria-hidden="true" /> <span className="truncate">{unit}</span>
              </Badge>
            )}
          </div>
          <div className="grid min-w-0 grid-cols-2 gap-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,1.4fr)]">
            {loadingFirst ? (
              <>
                <Skeleton className="h-[3.75rem] rounded-lg motion-reduce:animate-none" />
                <Skeleton className="h-[3.75rem] rounded-lg motion-reduce:animate-none" />
              </>
            ) : managerIsLeader ? (
              <PersonChip slot="team-leader" alsoLeader caption={`${t('team.colManager')} · ${t('team.colLeader')}`} person={manager} t={t} />
            ) : (
              <>
                <PersonChip slot="team-manager" caption={t('team.colManager')} person={manager} t={t} />
                <PersonChip slot="team-leader" caption={t('team.colLeader')} person={leader} t={t} />
              </>
            )}
            {info.email && <MailboxChip email={info.email} teamName={name} t={t} />}
          </div>
        </section>

        <Tabs value={tab} onValueChange={setTab} className="min-w-0 gap-3">
          <TabsList variant="line"
            className="h-auto! w-full justify-start gap-1 overflow-x-auto overflow-y-hidden rounded-none border-b p-0 pb-[5px]">
            <TabsTrigger value="members" className="min-h-9 flex-none px-2.5 max-sm:min-h-10 max-sm:px-2 pointer-coarse:min-h-10 max-sm:[&>svg]:hidden">
              <Users aria-hidden="true" /> {t('team.tabMembers')} {data && <CountPill n={count} />}
            </TabsTrigger>
            <TabsTrigger value="escalation" className="min-h-9 flex-none px-2.5 max-sm:min-h-10 max-sm:px-2 pointer-coarse:min-h-10 max-sm:[&>svg]:hidden">
              <ShieldAlert aria-hidden="true" />
              <span className="sm:hidden">{t('team.tabEscalationShort')}</span>
              <span className="hidden sm:inline">{t('team.tabEscalation')}</span>
              {data && <CountPill n={contacts.length} />}
            </TabsTrigger>
            {showCalls && (
              <TabsTrigger value="calllist" className="min-h-9 flex-none px-2.5 max-sm:min-h-10 max-sm:px-2 pointer-coarse:min-h-10 max-sm:[&>svg]:hidden">
                <PhoneCall aria-hidden="true" />
                <span className="sm:hidden">{t('team.tabCallListShort')}</span>
                <span className="hidden sm:inline">{t('team.tabCallList')}</span>
                {callStatus === 'ok' && <CountPill n={callRows.length} />}
              </TabsTrigger>
            )}
          </TabsList>

          {state.error && tab !== 'calllist' && (
            <AlertBanner tone="danger" role="alert" actions={retryButton}>{state.error}</AlertBanner>
          )}

          <TabsContent value="members" className="min-w-0">
            {loadingFirst ? <RowsSkeleton label={t('team.loadingMembers')} /> : data && (
              <TeamMemberCards key={teamId} members={members} leaderId={info.leader_id ?? info.leaderId}
                managerUserId={manager?.member ? manager.userId : null} teamId={teamId} teamName={name}
                canManage={canManage} onSelect={onEditUser} onView={onViewUser} managerLabelFor={managerLabelFor} sharedUnit={unit} />
            )}
          </TabsContent>
          <TabsContent value="escalation" className="min-w-0">
            {loadingFirst ? <RowsSkeleton label={t('team.loadingMembers')} rows={3} /> : data && (
              <EscalationPanel contacts={contacts} t={t} seedFor={contactSeed} />
            )}
          </TabsContent>
          {showCalls && (
            <TabsContent value="calllist" className="min-w-0">
              {callStatus === 'error'
                ? <AlertBanner tone="danger" role="alert" actions={retryButton}>{calls.error}</AlertBanner>
                : <CallListPanel rows={callRows} t={t} teamName={name} seedFor={callSeed} onOpenCoverage={openCoverage} />}
            </TabsContent>
          )}
        </Tabs>
      </div>
    </ModalShell>
  )
}
