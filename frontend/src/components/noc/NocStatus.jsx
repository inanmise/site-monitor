import { ArrowRight, HeadphoneOff, Headset } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { navigateTo } from '../../utils/navigate.js'
import { tabDeepLink } from '../../utils/monitorDeepLink.js'
import { requestNocFieldFocus } from './forms/nocFieldFocus.js'
import { useNocState } from './useNocState.js'
import {
  NOC_LEVEL_PHRASE, NOC_STATUS_LABEL, NOC_STATUS_WORD, nocCoverageTarget, nocEditTarget, nocStatusOf,
} from './nocStatusModel.js'

/**
 * 7/24 DURUM GÖSTERGESİ (2026-09-28) — her izleme kartında (dokuz tür, iki yoğunluk), Genel Bakış sertifika kartında ve
 * izleme detay penceresinin başlığında AYNI bileşen: izlemenin kritik uyarıları 7/24 izleme ekibine gidiyor mu?
 *
 * <p>Durum TEK kaynaktan (noc/nocStatusModel + paylaşılan noc/useNocState — sayfa başına TEK istek):
 * <ul>
 *   <li><b>7/24 açık</b> — birincil ton, kulaklık ikonu;</li>
 *   <li><b>7/24 açık · iletilmiyor</b> — amber ton; neden (tür Ayarlar'dan kapalı / aktif 7/24 grubu yok) açıklamada;</li>
 *   <li><b>7/24 kapalı</b> — soluk, üstü çizili kulaklık; alarm tonu YOK.</li>
 * </ul>
 * 7/24 durumu okunamazsa (403 / ağ) iki durumlu kalır (açık / kapalı) — "iletilmiyor" iddiası yalnız GERÇEK yanıttan.
 * Satır `noc_notify` taşımıyorsa hiçbir şey çizilmez ("bilinmiyor" ≠ "kapalı").
 *
 * <p><b>Görünüm:</b> Zengin = küçük hap (ikon + "7/24" + durum sözcüğü); Kompakt (`compact`) = yalnız ikon + durum noktası.
 * Dokun-gör açıklama `ui/HintPopover` (etkileşimli kip; telefonda dokunuşla açılır): durum, neden, alıcı gruplar ve bir
 * eylem — izlemeyi düzenleyebilene "7/24 ayarını düzenle" (izleme türlerinde `open=noc` derin bağlantısı: form 7/24
 * alanına kaydırılmış açılır; sertifika kartında kartın kendi düzenleme işleyicisi `onEdit` + odak isteği), diğerlerine
 * "7/24 Kapsamı'nda gör" (tür + hedef süzgeçli). Bağlantılar gerçek `<a href>` (yeni sekmede açılabilir).
 *
 * <p><b>Kartta:</b> tetik kartın örtüsünün ÜSTÜNDE durmalı — MonitorCardTop onu `CARD_LAYER` taşıyan sağ gruba koyar;
 * tıklamak detayı AÇMAZ. Dokunmatikte tetik en az 40 px (yerleşim kaymaz: hap/ikon görsel boyutu aynı kalır).
 * SOL RENK ŞERİDİ YOK (kalıcı kural) — durum hap/nokta ile.
 *
 * <p>Test kancaları: `data-slot="noc-status"` + `data-state` (on|blocked|off) + `data-reason` + `data-verified` +
 * `data-compact`; açıklama `data-slot="noc-status-detail"`; eylem `data-action="noc-status-edit|noc-status-coverage"`.
 *
 * @param {string} type        7/24 tür anahtarı (HTTP, PING, KEYWORD, PAGE, PAGESPEED, SCRIPTED, DNS, PORT, DOMAIN, SSL)
 * @param {object} monitor     satır (snake_case): `id`, `noc_notify`, `noc_group_ids`, `active`
 * @param {string} rowLabel    satırı ayırt eden ad (hedef) — erişilebilir ad "<hedef> — 7/24 açık"
 * @param {boolean} canEdit    bu kullanıcı izlemeyi düzenleyebilir mi (sayfanın kart eylemleriyle AYNI kapı)
 * @param {Function} onEdit    (isteğe bağlı) düzenleme işleyicisi — verilirse derin bağlantı yerine o çağrılır
 * @param {boolean} compact    yalnız ikon + nokta (Kompakt kart)
 */
const PILL_TONE = {
  on: { variant: 'outline', cls: 'border-primary/30 bg-primary/5 text-primary group-hover/noc:bg-primary/10 dark:bg-primary/15' },
  blocked: { variant: 'warning', cls: 'border-amber-600/30 group-hover/noc:bg-amber-500/25 dark:border-amber-400/40' },
  off: { variant: 'outline', cls: 'border-border bg-transparent text-muted-foreground group-hover/noc:bg-muted' },
}
const ICON_TONE = {
  on: 'text-primary',
  blocked: 'text-amber-700 dark:text-amber-300',
  off: 'text-muted-foreground',
}
const DOT_TONE = {
  on: 'bg-success',
  blocked: 'bg-amber-500',
  off: 'bg-muted-foreground/45',
}
const BADGE_TONE = {
  on: 'bg-primary/10 text-primary dark:bg-primary/20',
  blocked: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  off: 'bg-muted text-muted-foreground',
}
const MAX_GROUPS = 3

/** Düz sol tık uygulama içinde gezinir; Ctrl/⌘/Shift/Alt ya da orta tık tarayıcıya kalır (yeni sekme / pencere). */
function spaClick(e, go) {
  e.stopPropagation()
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
  e.preventDefault()
  go()
}

function bodyText(view, type, t) {
  if (view.state === 'off') return t('nocs.offBody')
  if (view.state === 'blocked') {
    return view.reason === 'TYPE_DISABLED' ? t('nocf.typeOffBody', t(`noc.type.${type}`)) : t('nocf.noGroups')
  }
  if (!view.verified) return t('nocs.onUnverified')
  return view.minLevel ? t('nocs.onBody', t(NOC_LEVEL_PHRASE[view.minLevel])) : t('nocs.onBodyAny')
}

function NocStatusAction({ type, monitor, rowLabel, canEdit, onEdit, close }) {
  const t = useT()
  const cls = 'mt-0.5 w-full justify-between pointer-coarse:h-10'
  if (canEdit && typeof onEdit === 'function') {
    return (
      <Button type="button" variant="outline" size="sm" data-action="noc-status-edit" className={cls}
        onClick={(e) => { e.stopPropagation(); close(); requestNocFieldFocus(type); onEdit() }}>
        {t('noc.editNoc')}<ArrowRight aria-hidden="true" />
      </Button>
    )
  }
  const edit = canEdit ? nocEditTarget(type, monitor?.id) : null
  const target = edit || nocCoverageTarget(type, rowLabel)
  const href = tabDeepLink(target.tab, target.params)
  return (
    <Button asChild variant="outline" size="sm" className={cls}>
      <a href={href} data-action={edit ? 'noc-status-edit' : 'noc-status-coverage'}
        onClick={(e) => spaClick(e, () => { close(); navigateTo(target.tab, target.params) })}>
        {t(edit ? 'noc.editNoc' : 'nocs.viewCoverage')}<ArrowRight aria-hidden="true" />
      </a>
    </Button>
  )
}

function NocStatusDetail({ view, type, label, rowLabel, ...action }) {
  const t = useT()
  const Icon = view.state === 'off' ? HeadphoneOff : Headset
  const shown = view.groups.slice(0, MAX_GROUPS).join(', ')
  const more = view.groups.length - MAX_GROUPS
  return (
    <div data-slot="noc-status-detail" data-state={view.state} className="flex flex-col gap-2 whitespace-normal">
      <div className="flex min-w-0 items-center gap-2.5">
        <span aria-hidden="true" className={cn('flex size-8 shrink-0 items-center justify-center rounded-full', BADGE_TONE[view.state])}>
          <Icon className="size-4" />
        </span>
        <div className="min-w-0">
          <p className="text-[13px] leading-tight font-semibold text-foreground">{label}</p>
          {rowLabel && <p className="mt-0.5 truncate font-mono text-[11px] text-muted-foreground" title={rowLabel}>{rowLabel}</p>}
        </div>
      </div>
      <p className="text-xs leading-relaxed text-muted-foreground">{bodyText(view, type, t)}</p>
      {view.groups.length > 0 && (
        <p data-slot="noc-status-groups" className="text-xs leading-relaxed text-muted-foreground">
          <span className="font-medium text-foreground">{t('nocs.groups')}</span>{' '}
          {shown}{more > 0 ? ` ${t('nocs.groupsMore', more)}` : ''}
        </p>
      )}
      {view.paused && <p data-slot="noc-status-paused" className="text-xs leading-relaxed text-muted-foreground">{t('noc.reasonHint.PAUSED')}</p>}
      <NocStatusAction type={type} rowLabel={rowLabel} {...action} />
    </div>
  )
}

export default function NocStatus(props) {
  // Satır `noc_notify` taşımıyorsa ("bilinmiyor") hiçbir şey çizilmez — ve paylaşılan duruma ABONE de olunmaz:
  // alanı taşımayan satırlar (sertifika önizlemesi, eski sunucu yanıtı) 7/24 isteği tetiklemesin.
  if (typeof props.monitor?.noc_notify !== 'boolean') return null
  return <NocStatusIndicator {...props} />
}

function NocStatusIndicator({ type, monitor, rowLabel, canEdit = false, onEdit, compact = false, className, triggerClassName }) {
  const t = useT()
  const noc = useNocState()
  const view = nocStatusOf({ notify: monitor?.noc_notify, type, groupIds: monitor?.noc_group_ids, active: monitor?.active }, noc)
  if (!view) return null
  const label = t(NOC_STATUS_LABEL[view.state])
  const name = rowLabel ? t('a11y.rowAction', rowLabel, label) : label
  const Icon = view.state === 'off' ? HeadphoneOff : Headset
  const hooks = {
    'data-slot': 'noc-status',
    'data-state': view.state,
    'data-reason': view.reason || undefined,
    'data-verified': view.verified ? 'true' : 'false',
    'data-compact': compact ? 'true' : undefined,
  }
  return (
    <HintPopover interactive aria-label={name} contentLabel={name} side="bottom" align="end"
      content={({ close }) => (
        <NocStatusDetail view={view} type={type} label={label} rowLabel={rowLabel}
          monitor={monitor} canEdit={canEdit} onEdit={onEdit} close={close} />
      )}
      triggerClassName={cn('group/noc shrink-0', compact ? 'size-6 rounded-full pointer-coarse:size-10' : 'rounded-md pointer-coarse:min-h-10', triggerClassName)}
      className="w-72 p-3">
      {compact ? (
        <span {...hooks} className={cn('relative inline-flex size-6 items-center justify-center rounded-full transition-colors group-hover/noc:bg-accent motion-reduce:transition-none', ICON_TONE[view.state], className)}>
          <Icon aria-hidden="true" className="size-3.5" />
          <span aria-hidden="true" data-slot="noc-status-dot"
            className={cn('absolute top-0.5 right-0.5 size-[7px] rounded-full ring-2 ring-card', DOT_TONE[view.state])} />
        </span>
      ) : (
        <Badge variant={PILL_TONE[view.state].variant} {...hooks}
          className={cn('h-5 gap-1 rounded-md px-1.5 text-[10.5px] leading-none font-bold transition-colors motion-reduce:transition-none', PILL_TONE[view.state].cls, className)}>
          {/* size-3 AÇIK: hap bir shadcn Button'ın (tetik) içinde — Button boyutsuz iç SVG'yi size-4'e zorlar (SHADCN.md §2.3) */}
          <Icon aria-hidden="true" className="size-3" />
          {/* Boşluk metin düğümü esnek kapta çizilmez (aralık gap-1'den) ama kopyalanan/okunan metni "7/24 açık" yapar */}
          <span>{t('nocf.badge')}</span>{' '}
          <span className="font-semibold">{t(NOC_STATUS_WORD[view.state])}</span>
        </Badge>
      )}
    </HintPopover>
  )
}
