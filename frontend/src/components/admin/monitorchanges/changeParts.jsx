import { useEffect, useRef, useState } from 'react'
import { FilePlus2, Pencil, Trash2, RotateCcw, FolderPen, Copy, Check, Layers, Bot, Pause, Play } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { navigateTo } from '../../../utils/navigate.js'
import { copyText } from '../../../utils/copyText.js'
import { flushUrlQuerySync } from '../../../hooks/useUrlQuerySync.js'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { ICONS as KIND_ICONS } from '../ChangeKindCards.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import HintPopover from '../../ui/HintPopover.jsx'
import MaskedValue from '../../ui/MaskedValue.jsx'
import { clockOf, eventLabel } from './changeModel.js'

export { eventLabel }

/**
 * İzleme Değişiklikleri ekranının satır parçaları — masaüstü tablo, telefon kartı ve ayrıntı paneli AYNI parçaları
 * çizer (bir ekranda iki farklı "olay rozeti" ya da "zaman" biçimi olmasın).
 */

/**
 * Olay → rozet tonu + ikon. Renk olayın YÖNÜNÜ söyler: ekleme yeşil, silme kırmızı, geri alma mavi; düzenleme
 * NÖTR (listenin çoğu düzenleme — hepsi renkli olsaydı renk sinyal olmaktan çıkardı). İkon renk körü için ikinci
 * kanal. Sol renk şeridi YOK (kullanıcı kuralı 2026-09-26): durum yalnız rozetle.
 */
export const EVENT_META = {
  CREATE: { tone: 'success', Icon: FilePlus2 },
  UPDATE: { tone: 'muted', Icon: Pencil },
  DELETE: { tone: 'danger', Icon: Trash2 },
  RESTORE: { tone: 'info', Icon: RotateCcw },
  GROUP_RENAME: { tone: 'muted', Icon: FolderPen },
  // Türetilmiş (active alanını çeviren güncelleme) — İzleme Değişiklikleri 2026-09-28
  PAUSE: { tone: 'warning', Icon: Pause },
  RESUME: { tone: 'info', Icon: Play },
}

/** Olay rozeti (shadcn Badge, ToneBadge). Test kancası: `data-event` (küçük harf olay adı) + `data-tone`. */
export function EventBadge({ t, ev, className }) {
  const meta = EVENT_META[ev] || { tone: 'muted', Icon: Pencil }
  return (
    <ToneBadge tone={meta.tone} data-event={String(ev ?? '').toLowerCase()}
      className={cn('shrink-0 rounded-sm font-semibold', className)}>
      <meta.Icon aria-hidden="true" />{eventLabel(t, ev)}
    </ToneBadge>
  )
}

/** Tür ikonu — Nav/ChangeKindCards ile aynı ikon sözlüğü (kullanıcı türü menüdeki şekliyle tanır). */
export function KindIcon({ kind, className }) {
  const Icon = KIND_ICONS[String(kind || '').toLowerCase()] || Layers
  return <Icon aria-hidden="true" className={cn('size-4 shrink-0 text-muted-foreground', className)} />
}

export const kindLabel = (t, kind) => t('chg.kind.' + String(kind || '').toLowerCase())

/** Sunucu UTC saklar ve Z'siz gönderir; göreli zaman için Z eklenir. */
function toMs(iso) {
  if (!iso) return NaN
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
  return new Date(s).getTime()
}

/** "3 dk önce" — Aktivite Logu ile aynı `act.rel.*` dağarcığı; tam zaman ipucunda ve ayrıntı panelinde. */
export function relTime(iso, t, now = Date.now()) {
  const then = toMs(iso)
  if (Number.isNaN(then)) return iso || '—'
  const sec = Math.floor(Math.max(0, now - then) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60); if (hr < 24) return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}

/** Göreli zaman; makine-okunur tam değer `<time dateTime>`, insan-okunur tam değer ipucunda. */
export function TimeAgo({ at, t, now, className }) {
  return (
    <SimpleTooltip content={formatDateSec(at)}>
      <time dateTime={at} className={cn('cursor-default whitespace-nowrap tabular-nums', className)}>{relTime(at, t, now)}</time>
    </SimpleTooltip>
  )
}

/**
 * Zaman çizelgesi damgası: yerel saat ("14:03") + son 24 saatteyse göreli ("3 dk önce"). Tam tarih-saat DOKUN-GÖR
 * balonunda (ui/HintPopover — telefonda da açılır; SimpleTooltip yalnız hover'dır). Gün başlığı tarihi zaten verir.
 * Düğme kart örtüsünün (stretched button) ÜSTÜNDE: `relative z-10`.
 */
export function TimeStamp({ at, t, now = Date.now(), className }) {
  const ms = toMs(at)
  const recent = Number.isFinite(ms) && now - ms < 24 * 3600 * 1000
  return (
    <HintPopover content={formatDateSec(at)} align="end" data-slot="chg-time"
      triggerClassName={cn('relative z-10 rounded-sm px-0.5 text-xs whitespace-nowrap text-muted-foreground hover:text-foreground pointer-coarse:min-h-10 max-md:min-h-10 max-md:px-1.5', className)}>
      <time dateTime={at} className="font-medium text-foreground tabular-nums">{clockOf(at)}</time>
      {recent && <span className="max-sm:hidden">&nbsp;· {relTime(at, t, now)}</span>}
    </HintPopover>
  )
}

/** Kim — avatarlı kullanıcı rozeti; aktörsüz (zamanlanmış/geri doldurma) ya da `system` satır "SİSTEM" rozeti. */
export function ActorBadge({ r, t, full = false }) {
  if (!r.actor || r.actor === 'system') {
    return (
      <Badge variant="outline" data-actor="system" className="gap-1 font-normal text-muted-foreground">
        <Bot aria-hidden="true" />{t('audit.systemActor')}
      </Badge>
    )
  }
  return (
    <UserBadge username={r.actor} userId={r.actor_id ?? undefined} displayName={r.actor_name ?? undefined}
      size="sm" inline={!full} nameOnly={!full} />
  )
}

export const resourceName = (r) => r.resource_name || `#${r.resource_id}`

/**
 * İzlemenin adı — izlemenin kendi sayfası varsa (`link`) ona giden bağlantı: gerçek `<a href>` (yeni sekmede açma,
 * adres kopyalama çalışır), düz sol tıklama uygulama içi geçiş (`navigateTo`, sayfa yeniden yüklenmez). Satırın
 * tıklaması (ayrıntı paneli) bağlantıya SIZMAZ. Uzun ad kırpılır; tamamı ipucunda (bağlantı odaklanınca da açılır).
 *
 * @param link  `{ tab, href, params }` ya da null (izleme olmayan tür / silinmiş kayıt)
 * @param wrap  true → ad satıra sarar (telefon kartı, panel başlığı)
 */
export function MonitorName({ r, link, wrap = false, className }) {
  const name = resourceName(r)
  // Sarma kipinde en fazla 3 satır (uzun URL kartı doldurmasın); tamamı ayrıntı panelinde
  const text = wrap ? 'line-clamp-3 break-words [overflow-wrap:anywhere]' : 'truncate'
  if (!link) {
    const span = <span className={cn('block min-w-0 font-semibold', text, className)}>{name}</span>
    return wrap ? span : <SimpleTooltip content={name}>{span}</SimpleTooltip>
  }
  const onClick = (e) => {
    e.stopPropagation()
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    e.preventDefault()
    // Son 300 ms'de değişen süzgeç o anki geçmiş kaydına yazılsın: Geri tuşu konsolu aynı süzgeçle geri getirir.
    flushUrlQuerySync()
    navigateTo(link.tab, link.params)
  }
  const anchor = (
    <Button asChild variant="link" size="xs"
      className={cn('h-auto max-w-full min-w-0 shrink justify-start p-0 text-left text-[1em] font-semibold text-foreground decoration-dotted underline-offset-[3px] hover:text-primary',
        wrap && 'whitespace-normal', className)}>
      <a href={link.href} onClick={onClick}><span className={cn('min-w-0', text)}>{name}</span></a>
    </Button>
  )
  return wrap ? anchor : <SimpleTooltip content={name}>{anchor}</SimpleTooltip>
}

/**
 * IP adresi + kopyala düğmesi (shadcn ghost Button). Kopyalanınca ikon 2 sn onaya döner (Toast yok — yerinde geri
 * bildirim). Satır tıklamasına SIZMAZ. Erişilebilir ad IP'yi taşır (satırlar ayırt edilir). `masked`: IP sunucuda
 * bu görüntüleyici için düşürüldü (satır `identity_masked`, 2026-09-28c) → "—" (kayıt yok) DEĞİL, "Gizli".
 */
export function IpCopy({ ip, t, className, masked = false }) {
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)
  useEffect(() => () => clearTimeout(timer.current), [])
  if (masked && !ip) return <MaskedValue className={className} />
  if (!ip) return <span className="text-muted-foreground">—</span>
  const onCopy = async (e) => {
    e.stopPropagation()
    if (!(await copyText(ip))) return
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 2000)
  }
  return (
    <Button type="button" variant="ghost" size="xs" title={t('chg.ipTitle')}
      aria-label={copied ? t('chg.ipCopied') : t('chg.ipCopyLabel', ip)}
      className={cn('h-auto gap-1 px-1 py-0.5 font-mono font-normal text-muted-foreground hover:text-primary', className)}
      onClick={onCopy}>
      {ip}{copied ? <Check aria-hidden="true" className="size-3 text-success" /> : <Copy aria-hidden="true" className="size-3" />}
    </Button>
  )
}
