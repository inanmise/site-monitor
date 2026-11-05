import { useEffect, useState } from 'react'
import { ArrowRight, Forward, Plus, RotateCw, Settings2 } from 'lucide-react'
import { api } from '../../api/client'
import { useDateLocale, useT } from '../../i18n/index.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { applyTabView, navigateTo } from '../../utils/navigate.js'
import { tabDeepLink } from '../../utils/monitorDeepLink.js'
import {
  STATE_KEY, STATE_TONE, activeCount, formatDay, forwardState, itemScopeText, manageTarget, pillKind, readForMonitor,
  recipientText,
} from './forwardModel.js'

/**
 * ALARM İLETİMİ ROZETİ (2026-11-05) — izleme detay penceresinin başlığında (MonitorDetailModal, 7/24 göstergesinin
 * hemen ardında; on izleme türünün hepsinde TEK yerden) ve sertifika penceresinin başlığında: bu izlemenin alarmları
 * sahip takımın yanı sıra başka bir takıma / kişilere de gidiyor mu?
 *
 * <p>Kaynak `GET /api/alert-forwards/for-monitor` (izleme başına tek istek; pencere açılınca). Rozet BİLGİ verir,
 * renkli alarm DEĞİLDİR:
 * <ul>
 *   <li><b>İletim: N</b> — N etkin iletim (birincil yumuşak ton);</li>
 *   <li><b>İletim: etkin değil</b> — iletim tanımlı ama hiçbiri şu an iletmiyor (duraklatılmış, süresi dolmuş …; sessiz);</li>
 *   <li><b>İletim ekle</b> — iletim yok ve görüntüleyici sahip takımı yönetiyor (`can_add`; sessiz);</li>
 *   <li>hiçbir şey — iletim yok ve eklenemez; görüntüleyici sahibi kendi kapsamında görmüyor (`hidden`: erişim paylaşımı /
 *       7/24 ile görenler yönlendirmeyi göremez); izleme yok, izin yok ya da eski sunucu (403 / 404 / 405).</li>
 * </ul>
 * Okuma hatası (ağ, zaman aşımı, 5xx) "iletim yok" sayılmaz: küçük "İletim: okunamadı" düğmesi tekrar dener.
 * Yükleme sessizdir (rozet yanıt gelince belirir).
 *
 * <p>Dokununca açılan açıklama (`ui/HintPopover` etkileşimli kip): her iletim — alıcı takım, "Tüm takım" / üye sayısı,
 * kapsam, durum (+ birden çok sahip takımda sahibi, bitiş günü) — ve eylemler: "Bu izleme için iletim ekle" (`can_add`;
 * Yönetim → Alarm iletimi penceresi bu izleme seçili açılır: `g_fnew=<tür>:<id>`, sertifikada `cert:<alanadı>`) ile
 * "Tümünü yönet". Bağlantılar gerçek `<a href>` (yeni sekmede açılabilir). Adlar sarar (kesilmez); dokunmatikte tetik ve
 * eylemler en az 40 px.
 *
 * <p>Test kancaları: `data-slot="monitor-forwards"` + `data-state` (active|inactive|add|error) + `data-count`; açıklama
 * `data-slot="monitor-forwards-detail"`, satır `data-slot="monitor-forwards-item"` + `data-state`; eylemler
 * `data-action="monitor-forwards-add|monitor-forwards-manage"`.
 *
 * @param {string} type      iletim tür anahtarı (http, ping, …, dbquery; sertifika "cert") — forwardModel.forwardTypeKey
 * @param {*}      id        izleme kimliği (sertifikada boş olabilir)
 * @param {string} domain    yalnız sertifika: alan adı (kimlik yerine)
 * @param {string} rowLabel  izlemeyi ayırt eden ad — erişilebilir ad "<ad> — Alarm iletimi: N"
 */
const PILL_TONE = {
  active: 'border-primary/30 bg-primary/5 text-primary group-hover/fwd:bg-primary/10 dark:bg-primary/15',
  inactive: 'border-border bg-transparent text-muted-foreground group-hover/fwd:bg-muted',
  add: 'border-dashed border-border bg-transparent text-muted-foreground group-hover/fwd:bg-muted',
  error: 'border-border bg-muted text-muted-foreground',
}

/** Düz sol tık uygulama içinde gezinir; Ctrl/⌘/Shift/Alt ya da orta tık tarayıcıya kalır (yeni sekme / pencere). */
function spaClick(e, go) {
  e.stopPropagation()
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
  e.preventDefault()
  go()
}

/**
 * Yönetim sekmesine git. Başka sekmedeyken `navigateTo` (sertifika penceresi gibi App düzeyindeki pencere
 * `closeOnNavigate` ile kapanır; izleme penceresi sayfasıyla sökülür); zaten Yönetim'deyse `applyTabView` (sekme
 * yeniden bağlanır, `g_*` paramlarını okur).
 */
function goTo(target) {
  let current = null
  try { current = new URLSearchParams(window.location.search).get('tab') } catch { /* yoksay */ }
  if (current === target.tab) applyTabView(target.tab, target.params)
  else navigateTo(target.tab, target.params)
}

function ActionLink({ target, action, icon: Icon, close, children }) {
  return (
    <Button asChild variant="outline" size="sm" className="h-auto min-h-8 w-full justify-between py-1.5 whitespace-normal text-left pointer-coarse:min-h-10">
      <a href={tabDeepLink(target.tab, target.params)} data-action={action}
        onClick={(e) => spaClick(e, () => { close(); goTo(target) })}>
        <span className="inline-flex min-w-0 items-center gap-1.5">
          <Icon aria-hidden="true" className="size-3.5 shrink-0" />
          <span className="min-w-0 [overflow-wrap:anywhere]">{children}</span>
        </span>
        <ArrowRight aria-hidden="true" className="shrink-0" />
      </a>
    </Button>
  )
}

function ForwardItem({ item, showOwner }) {
  const t = useT()
  const locale = useDateLocale()
  const st = forwardState(item)
  return (
    <li data-slot="monitor-forwards-item" data-state={st} className="flex min-w-0 flex-col gap-1 rounded-md border px-2.5 py-2">
      <div className="flex min-w-0 flex-wrap items-start gap-x-2 gap-y-1">
        <span className="min-w-0 flex-1 text-[13px] leading-snug font-semibold text-foreground [overflow-wrap:anywhere]">
          {item.recipient_team_name || t('fwdx.teamUnnamed')}
        </span>
        <ToneBadge tone={STATE_TONE[st]} data-slot="monitor-forwards-state"
          className="h-auto shrink-0 overflow-visible rounded-md whitespace-normal">{t(STATE_KEY[st])}</ToneBadge>
      </div>
      <p className="text-xs leading-relaxed text-muted-foreground [overflow-wrap:anywhere]">
        {recipientText(t, item)} · {itemScopeText(t, item)}
      </p>
      {showOwner && item.owner_team_name && (
        <p data-slot="monitor-forwards-owner" className="text-xs leading-relaxed text-muted-foreground [overflow-wrap:anywhere]">
          {t('fwdx.ownerLine', item.owner_team_name)}
        </p>
      )}
      {item.expires_on && (
        <p data-slot="monitor-forwards-until" className="text-xs leading-relaxed text-muted-foreground">
          {t('fwdx.until', formatDay(item.expires_on, locale))}
        </p>
      )}
    </li>
  )
}

function ForwardsDetail({ view, kind, rowLabel, type, id, domain, close }) {
  const t = useT()
  const items = view.items
  const owners = view.ownerTeams
  const monitorName = rowLabel || view.monitor?.name || ''
  const lead = kind === 'add' ? t('fwdx.leadNone') : kind === 'inactive' ? t('fwdx.leadInactive') : t('fwdx.lead')
  return (
    <div data-slot="monitor-forwards-detail" data-state={kind} className="flex flex-col gap-2.5 whitespace-normal">
      <div className="flex min-w-0 items-center gap-2.5">
        <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary dark:bg-primary/20">
          <Forward className="size-4" />
        </span>
        <div className="min-w-0">
          <p className="text-[13px] leading-tight font-semibold text-foreground">{t('fwdx.title')}</p>
          {monitorName && <p className="mt-0.5 font-mono text-[11px] text-muted-foreground [overflow-wrap:anywhere]">{monitorName}</p>}
        </div>
      </div>
      <p className="text-xs leading-relaxed text-muted-foreground">{lead}</p>
      {items.length > 0 && (
        <ul data-slot="monitor-forwards-list" className="flex max-h-[min(18rem,45dvh)] flex-col gap-1.5 overflow-y-auto">
          {items.map((it, i) => <ForwardItem key={it.id ?? i} item={it} showOwner={owners.length > 1} />)}
        </ul>
      )}
      <div className="flex flex-col gap-1.5">
        {view.canAdd && (
          <ActionLink target={manageTarget({ type, id, domain, ownerTeams: owners, add: true })} action="monitor-forwards-add"
            icon={Plus} close={close}>{t('fwdx.addForMonitor')}</ActionLink>
        )}
        <ActionLink target={manageTarget({ type, id, domain, ownerTeams: owners })} action="monitor-forwards-manage"
          icon={Settings2} close={close}>{t('fwdx.manageAll')}</ActionLink>
      </div>
    </div>
  )
}

export default function MonitorForwardsPill({ type, id, domain, rowLabel, className }) {
  const t = useT()
  const forDomain = type === 'cert' ? (domain || '') : ''
  const key = type && (id != null || forDomain) ? `${type}|${id ?? ''}|${forDomain}` : ''
  const [state, setState] = useState({ key: '', view: null })
  const [nonce, setNonce] = useState(0)
  const [retrying, setRetrying] = useState(false)

  useEffect(() => {
    if (!key) return undefined
    const fetchFn = api.alertForwards?.forMonitor
    if (typeof fetchFn !== 'function') return undefined
    let alive = true
    Promise.resolve()
      .then(() => fetchFn(type, id ?? null, forDomain || undefined))
      .then((res) => { if (alive) setState({ key, view: readForMonitor(res) }) })
      .catch(() => { if (alive) setState({ key, view: { status: 'error' } }) })
      .finally(() => { if (alive) setRetrying(false) })
    return () => { alive = false }
  }, [key, nonce])   // eslint-disable-line react-hooks/exhaustive-deps -- tür / kimlik / alan adı anahtarın içinde

  const view = state.key === key ? state.view : null
  if (!view) return null
  const label = rowLabel || view.monitor?.name || ''

  if (view.status === 'error') {
    const name = label ? t('a11y.rowAction', label, t('fwdx.unreadableRetry')) : t('fwdx.unreadableRetry')
    return (
      <Button type="button" variant="ghost" aria-label={name} title={t('fwdx.unreadableRetry')} disabled={retrying}
        aria-busy={retrying || undefined} data-action="monitor-forwards-retry"
        onClick={(e) => { e.stopPropagation(); setRetrying(true); setNonce((n) => n + 1) }}
        className={cn('group/fwd h-auto min-h-0 shrink-0 rounded-md p-0 font-[inherit] hover:bg-transparent dark:hover:bg-transparent pointer-coarse:min-h-10', className)}>
        <Badge variant="outline" data-slot="monitor-forwards" data-state="error"
          className={cn('h-5 gap-1 rounded-md px-1.5 text-[10.5px] leading-none font-semibold', PILL_TONE.error)}>
          <RotateCw aria-hidden="true" className="size-3" />
          <span>{t('fwdx.pill.unreadable')}</span>
        </Badge>
      </Button>
    )
  }

  const kind = pillKind(view)
  if (!kind) return null
  const n = activeCount(view.items)
  const text = kind === 'active' ? t('fwdx.pill.active', n) : kind === 'inactive' ? t('fwdx.pill.inactive') : t('fwdx.pill.add')
  const name = label ? t('a11y.rowAction', label, text) : text
  const Icon = kind === 'add' ? Plus : Forward
  return (
    <HintPopover interactive aria-label={name} contentLabel={t('fwdx.title')} side="bottom" align="end"
      content={({ close }) => (
        <ForwardsDetail view={view} kind={kind} rowLabel={label} type={type} id={id} domain={forDomain} close={close} />
      )}
      triggerClassName={cn('group/fwd shrink-0 rounded-md pointer-coarse:min-h-10', className)}
      className="w-80 p-3">
      <Badge variant="outline" data-slot="monitor-forwards" data-state={kind} data-count={n}
        className={cn('h-5 gap-1 rounded-md px-1.5 text-[10.5px] leading-none font-bold transition-colors motion-reduce:transition-none', PILL_TONE[kind])}>
        {/* size-3 AÇIK: hap bir shadcn Button'ın (tetik) içinde — Button boyutsuz iç SVG'yi size-4'e zorlar (SHADCN.md §2.3) */}
        <Icon aria-hidden="true" className="size-3" />
        <span>{text}</span>
      </Badge>
    </HintPopover>
  )
}
