import { useState } from 'react'
import {
  Server, Shield, Cloud, Lock, Key, BadgeCheck, Building, Handshake, CircleCheck, Route, AlertTriangle, RefreshCw, ArrowRightLeft,
  ArrowUp, ArrowDown, Play, Inbox, FolderOpen,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate, formatDateOnly } from '../../api/client'
import { relativeTime } from '../admin/audit/auditFormat.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { INVENTORY_COLUMNS, filledContacts, activeFlags } from './inventoryModel.js'
import InventoryFilterRow from './InventoryFilterRow.jsx'   // kolon süzgeç satırı (2026-09-22)
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Switch } from '@/components/shadcn/switch'
import { Avatar, AvatarFallback, AvatarGroup } from '@/components/shadcn/avatar'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { expiredAgoText, expiresInText } from '../../utils/dayPhrases.js'
import ManualCertBadge from '../manualcert/ManualCertBadge.jsx'
import { isManualCert } from '../manualcert/manualCertModel.js'

export const FLAG_ICON = {
  netscaler: Server, waf_enabled: Shield, openshift: Cloud, ssl_pinning: Lock, jks_keystore: Key, ev_certificate: BadgeCheck,
  internal_cert: Building, external_vendor: Handshake, in_use: CircleCheck, use_proxy: Route, action_required: AlertTriangle,
  server_update: RefreshCw, transferred_to_sy: ArrowRightLeft,
}
/** Bayrak ikon rengi (eski .inv-flag--*): varsayılan birincil; eylem gerekli = kırmızı, kullanımda = yeşil. */
const FLAG_TONE = { action_required: 'text-destructive', in_use: 'text-success' }

function daysBetween(iso) {
  if (!iso) return null
  const d = new Date(/[zZ]$|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : iso + 'Z')
  return Number.isNaN(d.getTime()) ? null : Math.floor((Date.now() - d.getTime()) / 86400000)
}

// Kritiklik katmanı rozeti (eski paylaşılan .tier-badge-N) — dolu, beyaz yazı. Çekmece/takım görünümü/kartlar da kullanır.
const TIER_TONE = { 1: 'bg-indigo-600 text-white', 2: 'bg-sky-600 text-white', 3: 'bg-cyan-600 text-white', 4: 'bg-zinc-500 text-white' }
export function TierBadge({ tier, className, ...rest }) {
  if (!tier) return null
  return (
    <Badge data-slot="tier-badge" data-tier={tier} className={cn('rounded px-1.5 text-[11px] font-extrabold', TIER_TONE[tier] ?? TIER_TONE[4], className)} {...rest}>
      T{tier}
    </Badge>
  )
}

// Sertifika durum tonu (eski .inv-cert--*): nokta + kısa etiket, tonlu rozet.
const CERT_TONE = {
  ok: 'bg-success/15 text-success dark:bg-success/20',
  warn: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  crit: 'bg-orange-500/15 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300',
  err: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
}
const DOT_TONE = { ok: 'bg-success', warn: 'bg-amber-500', crit: 'bg-orange-500', err: 'bg-destructive', none: 'bg-muted-foreground/40' }

/**
 * Kaydın sertifika tonu: hata > dolmuş (negatif gün; tel biçiminde durum 'warning' kalır) > kritik/yüksek > uyarı > geçerli;
 * hiç kontrol edilmemişse 'none'. Tablo noktası, rozet ve kart hepsi bundan okur.
 */
export function certTone(r) {
  const s = (r?.cert_status || '').toLowerCase()
  if (!s) return 'none'
  if (s === 'error') return 'err'
  if (r.cert_days_remaining != null && r.cert_days_remaining < 0) return 'err'
  if (s === 'critical' || s === 'high') return 'crit'
  if (s === 'warning') return 'warn'
  return 'ok'
}
function certLabel(r, t) {
  const s = (r.cert_status || '').toLowerCase()
  if (!s) return t('inv.certNever')
  if (s === 'error') return t('inv.certError')
  if (r.cert_days_remaining != null && r.cert_days_remaining < 0) return t('inv.tileExpired')
  return s === 'critical' ? t('inv.certCritical') : s === 'high' ? t('inv.certHigh') : s === 'warning' ? t('inv.certWarning') : t('inv.certValid')
}

/** Sertifika durum noktası + kısa etiket (#3) — shadcn Badge; test kancası `data-slot="inv-cert"` + `data-tone`. */
export function CertCell({ r, t }) {
  const tone = certTone(r)
  if (tone === 'none') return <span data-slot="inv-cert" data-tone="none" className="text-muted-foreground" title={t('inv.certNever')}>—</span>
  return (
    <Badge variant="secondary" data-slot="inv-cert" data-tone={tone} title={r.cert_error || r.cert_issuer || ''}
      className={cn('gap-1.5 font-semibold', CERT_TONE[tone])}>
      <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-current" />{certLabel(r, t)}
    </Badge>
  )
}

/** Alan adının önündeki küçük durum noktası — renk tonu `data-tone`; anlamı yanındaki rozet/metin taşır. */
export function StatusDot({ r, className }) {
  const tone = certTone(r)
  return <span data-slot="inv-dot" data-tone={tone} aria-hidden="true" className={cn('inline-block size-2 shrink-0 rounded-full', DOT_TONE[tone], className)} />
}

/**
 * Bitiş hücresi: göreli metin ("25 gün içinde" / "3 gün önce doldu" / "Bugün doluyor") + altında tarih. Hiç kontrol
 * edilmemişse "Hiç kontrol edilmedi"; kontrol var ama gün yoksa (bağlantı hatası) "—". Test kancası `data-slot="inv-expiry"`.
 */
export function ExpiryCell({ r, t, showDate = true, withStatus = false, className }) {
  const d = r.cert_days_remaining
  if (d == null) {
    // Gün yok: hiç kontrol edilmemiş ya da bağlantı hatası — birleşik hücrede durum rozeti (ör. "Hata") anlamı taşır
    if (withStatus && r.cert_status) return <span data-slot="inv-expiry" data-tone="none" className={className}><CertCell r={r} t={t} /></span>
    return <span data-slot="inv-expiry" data-tone="none" className={cn('text-muted-foreground', className)}>{r.cert_status ? '—' : t('inv.certNever')}</span>
  }
  const tone = d < 0 ? 'err' : d <= 30 ? 'warn' : 'ok'
  const text = d < 0 ? expiredAgoText(t, -d) : d === 0 ? t('inv.expiresToday') : expiresInText(t, d)
  return (
    <span data-slot="inv-expiry" data-tone={tone} data-days={d} className={cn('inline-flex min-w-0 flex-col gap-0.5 leading-tight', className)}>
      <span className="inline-flex flex-wrap items-center gap-1.5">
        {withStatus && <CertCell r={r} t={t} />}
        <span className={cn('font-semibold tabular-nums', d < 0 ? 'text-destructive' : d <= 30 && 'text-amber-700 dark:text-amber-400')}>{text}</span>
      </span>
      {showDate && r.cert_not_after && <span className="text-[.8em] text-muted-foreground">{formatDateOnly(r.cert_not_after)}</span>}
    </span>
  )
}

/** Bayrak ikon kümesi (#5) — açık olanlar renkli, tooltip etiket. */
export function FlagCluster({ r, t, compact }) {
  const on = activeFlags(r)
  if (on.length === 0) return <span className="text-[.85em] text-muted-foreground">—</span>
  return (
    <span data-slot="inv-flags" className="inline-flex flex-wrap items-center gap-1" title={on.map(({ labelKey }) => t(labelKey)).join(' · ')}>
      {on.slice(0, compact ? 4 : 13).map(({ key, labelKey }) => {
        const I = FLAG_ICON[key] || CircleCheck
        return <I key={key} size={13} aria-label={t(labelKey)} className={cn('shrink-0 text-primary', FLAG_TONE[key])} />
      })}
      {compact && on.length > 4 && <span className="text-[.74em] text-muted-foreground">+{on.length - 4}</span>}
    </span>
  )
}

/** "Ad Soyad - ad@example.com" gibi serbest metinden baş harfler (avatar yedeği); yalnız adres varsa yerel kısmın ilk iki harfi. */
export function contactInitials(value) {
  const raw = String(value ?? '').trim()
  if (!raw) return '?'
  const name = (raw.split(/[-–|(<,]/)[0].trim() || raw.split('@')[0]).replace(/@.*$/, '')
  const parts = name.split(/\s+/).filter(Boolean)
  const ini = parts.length >= 2 ? parts[0][0] + parts[parts.length - 1][0] : name.slice(0, 2)
  return (ini || '?').toUpperCase()
}

const CONTACT_TONE = {
  ok: 'bg-success/15 text-success dark:bg-success/20',
  warn: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  bad: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
}
/**
 * Sorumlular: baş harf avatarları (en çok 3) + n/4 sayaç rozeti. Dokun/tıkla → rol: değer listesi (HintPopover; yalnız-hover
 * bilgi yok). Erişilebilir ad "n/4 sorumlu". Test kancası `data-slot="inv-contacts"` + `data-tone`.
 */
export function ContactsCell({ r, t }) {
  const filled = filledContacts(r)
  const n = filled.length
  const total = CONTACT_FIELDS.length
  const cls = n === 0 ? 'bad' : n < total ? 'warn' : 'ok'
  const content = n ? filled.map(({ key, labelKey }) => `${t(labelKey)}: ${r[key]}`).join('\n') : t('inv.contactsNone')
  return (
    <HintPopover content={content} side="bottom" triggerClassName="gap-1.5 whitespace-nowrap"
      data-slot="inv-contacts" data-tone={cls} data-count={n} aria-label={t('inv.contactsCount', n, total)}>
      {n > 0 && (
        <AvatarGroup className="-space-x-1">
          {filled.slice(0, 2).map(({ key }) => (   /* en çok 2 avatar + sayaç: sütun dar kalsın; tamamı açılan listede */
            <Avatar key={key} size="sm"><AvatarFallback className="bg-sky-100 text-[10px] font-bold text-sky-800 dark:bg-sky-900 dark:text-sky-100">{contactInitials(r[key])}</AvatarFallback></Avatar>
          ))}
        </AvatarGroup>
      )}
      <Badge variant="secondary" aria-hidden="true" className={cn('font-bold tabular-nums', CONTACT_TONE[cls])}>{n}/{total}</Badge>
    </HintPopover>
  )
}

/** Etiket çipi (eski .inv-tag) — tıklayınca aramaya yazar. */
export function TagChip({ tag, onClick }) {
  return (
    <Button type="button" variant="outline" size="xs" data-inv-tag={tag} onClick={(e) => { e.stopPropagation(); onClick?.(e) }}
      className="h-auto rounded-full px-1.5 py-px text-[.74em] font-normal text-muted-foreground shadow-none hover:border-primary hover:bg-transparent hover:text-primary dark:hover:bg-transparent">
      {tag}
    </Button>
  )
}

/** Grup çipi — tıklayınca grup süzgecini uygular (grup sütunu kapalıyken alan adı hücresinde; kartta her zaman). */
export function GroupChip({ name, onClick }) {
  return (
    <Button type="button" variant="outline" size="xs" data-inv-group={name} onClick={(e) => { e.stopPropagation(); onClick?.(e) }}
      className="h-auto gap-1 rounded-full px-1.5 py-px text-[.74em] font-medium text-foreground shadow-none hover:border-primary hover:bg-transparent hover:text-primary dark:hover:bg-transparent has-[>svg]:px-1.5">
      <FolderOpen aria-hidden="true" className="size-3" />{name}
    </Button>
  )
}

/** Aktif/pasif rozeti — kart ve tablo aynı. */
export function ActiveBadge({ r, t }) {
  return (
    <Badge variant="secondary" data-slot="inv-active" data-active={r.active ? 'true' : 'false'}
      className={r.active ? 'bg-success/15 text-success dark:bg-success/20' : 'bg-destructive/10 text-destructive dark:bg-destructive/20'}>
      {r.active ? t('inv.active') : t('inv.inactive')}
    </Badge>
  )
}

/** Silinmiş kaydın künyesi: "Silindi · N gün önce · kim". */
export function DeletedBadge({ r, t }) {
  const ago = daysBetween(r.deleted_at)
  return (
    <Badge variant="secondary" data-slot="inv-deleted" title={formatDate(r.deleted_at)} className="whitespace-normal">
      {t('inv.deletedBadge')}{ago != null ? ` · ${t('inv.deletedAgo', ago)}` : ''}{r.updated_by_name ? ` · ${r.updated_by_name}` : ''}
    </Badge>
  )
}

export const tagList = (r) => String(r.tags || '').split(',').map((x) => x.trim()).filter(Boolean)
export const platformLabel = (code, names = {}) => names[code] || code

/**
 * Satır/kart "İşlem" menüsü — tablo ve telefon kartı AYNI listeyi kullanır. Salt okunur (başka takımın) kayıtta yalnız
 * "Göster"; silinmişte Göster / Geri getir / Kalıcı sil; canlıda Göster / Kontrol et / Tanılama / Düzenle / Kopyala / Devret / Sil.
 */
export function rowMenuItems({ r, t, ro, isAdmin, canManage, canEditRow, teamsCount, onShow, onCheckNow, onDiagnose, onEdit, onDuplicate, onTransfer, onDelete, onRestore, onPurge }) {
  if (ro) return [{ label: t('inv.show'), onClick: () => onShow(r) }]
  if (r.deleted_at) {
    return [
      { label: t('inv.show'), onClick: () => onShow(r) },
      { label: t('inv.restore'), onClick: () => onRestore(r.id), hidden: !canManage },
      { label: t('inv.purge'), danger: true, onClick: () => onPurge(r.id), hidden: !isAdmin },
    ]
  }
  return [
    { label: t('inv.show'), onClick: () => onShow(r) },
    { label: (isManualCert(r) ? t('mcert.reevaluate') : t('inv.checkNow')), onClick: () => onCheckNow(r) },
    // Manuel (dosyadan yüklenen) kayıt (2026-10-06): ağ tanılaması ve kopyalama yok — adres yok, sertifika dosyadan.
    { label: t('inv.diagnose'), onClick: () => onDiagnose(r), hidden: !isAdmin || isManualCert(r) },
    // Düzenle/Kopyala satır bazlı (2026-09-18): USER kendi takımının kaydını düzenler; silme canManage'de kalır
    { label: t('inv.edit'), onClick: () => onEdit(r), hidden: !canEditRow(r) },
    { label: t('mon.duplicate'), onClick: () => onDuplicate(r), hidden: !canEditRow(r) || isManualCert(r) },
    { label: t('inv.transfer'), onClick: () => onTransfer(r), hidden: !(isAdmin && teamsCount > 1) },
    { label: t('inv.delete'), danger: true, onClick: () => onDelete(r.id), hidden: !canManage },
  ]
}

/** Satırdan sonraki/önceki odaklanabilir satıra geç (klavyeyle satır gezinme). */
function focusRow(tr, dir) {
  let el = dir > 0 ? tr.nextElementSibling : tr.previousElementSibling
  while (el && !el.hasAttribute('tabindex')) el = dir > 0 ? el.nextElementSibling : el.previousElementSibling
  el?.focus()
}

const stop = (e) => e.stopPropagation()

/**
 * Envanter tablosu (#3 canlı durum · #4 sütun seçici + sıralama · #5 ikonlar · #8 satır-içi düzenleme · #9 alan adı bitişi ·
 * #10 çöp kutusu künyesi · #11 şimdi kontrol et · #14 etiket çipleri · #15 yoğunluk). 2026-09-27 yeniden tasarım: satıra
 * tıklamak/Enter çekmeceyi açar, ↑/↓ satırlar arasında gezer; başlık ve alan adı sütunu kendi kaydırma kabında YAPIŞKAN
 * (kap `max-h` ile dikey kayar); alan adı hücresi durum noktası + 443 dışı port rozeti + açıklama; bitiş göreli + tarih;
 * sorumlular baş harf avatarları + sayaç. Telefonda bu tablo çizilmez (InventoryCardList).
 *
 * Çizim shadcn: Table ailesi, Checkbox (seçim), Switch (aktif), NativeSelect (satır-içi katman), Badge, Avatar, Button.
 * Test kancaları: satır `[data-inv-row]`, alan adı düğmesi `[data-inv-domain]`, boş satır `data-slot="table-empty-row"`.
 */
export default function InventoryTable({
  rows, cols, sort, onSort, density, canManage, canEditRow = () => canManage, isAdmin, teamsCount, teamMap = {}, selected, onToggle, onToggleAll, allOnPage,
  canManageRow = () => true,   // org geneli görünürlük (2026-09-26): false → başka takımın kaydı, HER değiştiren kontrol gizli + salt okunur rozet
  onShow, onEdit, onDuplicate, onTransfer, onDelete, onRestore, onPurge, onDiagnose, onCheckNow, onInline, onTagClick, onGroupClick, statusFilter,
  filters = null, onFilters = null, allRows = [], showFilters = false, onClearFilters = null,   // kolon süzgeç satırı (2026-09-22)
  platformNames = {},   // kod → ad (Ayarlar → Platformlar); yoksa kod gösterilir
}) {
  const t = useT()
  const [editing, setEditing] = useState(null)   // { id, field }
  // Kontrol-et meşgul KÜMESİ (alan adları; useRunningChecks deseni): tek yuvada A sürerken B'ye basınca A'nın
  // spinner'ı sönüyor, önce biten diğerinin kilidini de açıyordu.
  const [busy, setBusy] = useState(() => new Set())
  const show = (k) => cols.includes(k)
  const [sortKey, sortDir] = String(sort || '').split('|')
  const compact = density === 'compact'
  // Başlık yapışkan (kabın üstünde), alan adı sütunu yapışkan (solda); ikisinin kesişimi en üstte.
  const headCls = cn('sticky top-0 z-[2] bg-muted font-medium text-muted-foreground', compact ? 'h-8 px-2' : 'h-10 px-2.5')
  const cellCls = compact ? 'px-2 py-1 text-[.86em]' : 'px-2.5 py-2'
  const STICKY = 'sticky left-0 z-[1] bg-card group-hover/row:bg-muted group-data-[state=selected]/row:bg-muted'

  const header = (key, labelKey, extra = '') => {
    const col = INVENTORY_COLUMNS.find((c) => c.key === key)
    if (!col) return <TableHead key={key} className={cn(headCls, extra)}>{t(labelKey)}</TableHead>
    const on = sortKey === key
    return (
      <TableHead key={key} className={cn(headCls, extra)} aria-sort={on ? (sortDir === 'desc' ? 'descending' : 'ascending') : 'none'}>
        <Button type="button" variant="ghost" size="sm" onClick={() => onSort(`${key}|${on && sortDir === 'asc' ? 'desc' : 'asc'}`)}
          className="group/sort -ml-2 h-7 gap-1 px-2 font-medium whitespace-nowrap text-muted-foreground hover:text-foreground">
          {t(labelKey)}
          {on
            ? (sortDir === 'desc' ? <ArrowDown aria-hidden="true" className="size-3" /> : <ArrowUp aria-hidden="true" className="size-3" />)
            : null /* boşta ok YOK: her başlıkta 16 px yer ayırıyordu (1440'ta tablo taşıyordu); sıralanabilirlik düğmenin hover zemininden */}
        </Button>
      </TableHead>
    )
  }

  async function checkNow(r) {
    setBusy((prev) => { const next = new Set(prev); next.add(r.domain); return next })
    try { await onCheckNow(r) } finally { setBusy((prev) => { const next = new Set(prev); next.delete(r.domain); return next }) }
  }

  const inlineTier = (r) => (
    <NativeSelect size="sm" className="min-w-[90px]" autoFocus value={r.tier ?? ''} aria-label={t('inv.colTier')}
      onBlur={() => setEditing(null)}
      onChange={(e) => { const v = e.target.value === '' ? null : Number(e.target.value); setEditing(null); if (v !== (r.tier ?? null)) onInline(r, { tier: v }) }}>
      <NativeSelectOption value="">{t('inv.tierNone')}</NativeSelectOption>
      {[1, 2, 3, 4].map((n) => <NativeSelectOption key={n} value={n}>T{n}</NativeSelectOption>)}
    </NativeSelect>
  )

  return (
    // Kap: yatay + dikey kaydırma kendi içinde (shadcn Table kabı `data-slot="table-container"`); başlık/alan adı yapışkan kalır.
    <div data-slot="inv-table" data-density={density}
      className="min-w-0 overflow-hidden rounded-[10px] border bg-card *:data-[slot=table-container]:max-h-[calc(100dvh-13rem)] *:data-[slot=table-container]:overflow-auto">
      <Table className="min-w-[720px] text-[.9em]">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            {canManage && (
              <TableHead className={cn(headCls, 'w-7')}>
                <Checkbox checked={allOnPage} onCheckedChange={onToggleAll} disabled={rows.filter((r) => !r.deleted_at && canManageRow(r)).length === 0}
                  aria-label={t('inv.bulkSelectAll')} />
              </TableHead>
            )}
            {header('domain', 'inv.colDomain', 'left-0 z-[3]')}
            {show('port') && header('port', 'inv.colPort')}
            {show('tier') && header('tier', 'inv.colTier')}
            {show('team') && header('team', 'inv.colTeam')}
            {show('ug_team') && header('ug_team', 'inv.colUgTeam')}
            {show('cert') && header('cert', 'inv.colCert')}
            {show('days') && header('days', 'inv.colDays')}
            {show('checked') && header('checked', 'inv.colLastCheck')}
            {show('group') && header('group', 'inv.colGroup')}
            {show('contacts') && header('contacts', 'inv.colContacts')}
            {show('flags') && header('flags', 'inv.colFlags')}
            {show('domain_exp') && header('domain_exp', 'inv.colDomainExpiry')}
            {show('interval') && header('interval', 'inv.colInterval')}
            {show('tags') && header('tags', 'inv.colTags')}
            {show('platform') && header('platform', 'inv.colPlatform')}
            {show('updated') && header('updated', 'inv.colUpdated')}
            {header('active', statusFilter === 'deleted' ? 'inv.colDeleted' : 'inv.colActive')}
            <TableHead className={cn(headCls, 'text-right')}>{t('inv.colActions')}</TableHead>
          </TableRow>
          {showFilters && filters && onFilters && (
            <InventoryFilterRow filters={filters} onFilters={onFilters} allRows={allRows} cols={cols} canManage={canManage} statusFilter={statusFilter} platformNames={platformNames} />
          )}
        </TableHeader>
        <TableBody>
          {rows.map((r) => {
            const del = !!r.deleted_at
            const ro = !canManageRow(r)              // başka takımın kaydı: salt okunur satır
            const editable = !ro && canEditRow(r)
            const dexp = r.domain_expiry ? -daysBetween(r.domain_expiry) : null
            const dim = cn(cellCls, 'text-[.88em] text-muted-foreground')
            const port = r.port ?? 443
            const isSel = selected.has(r.id)
            return (
              // Satır: tıkla / Enter / Boşluk → çekmece; ↑↓ satırlar arasında gezer. Hücre içi kontroller yayılımı durdurur.
              <TableRow key={r.id} tabIndex={0} data-inv-row={r.domain} data-deleted={del ? 'true' : undefined}
                data-state={isSel ? 'selected' : undefined}
                onClick={() => onShow(r)}
                onKeyDown={(e) => {
                  if (e.target !== e.currentTarget) return
                  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onShow(r) }
                  else if (e.key === 'ArrowDown') { e.preventDefault(); focusRow(e.currentTarget, 1) }
                  else if (e.key === 'ArrowUp') { e.preventDefault(); focusRow(e.currentTarget, -1) }
                }}
                className={cn('group/row cursor-pointer outline-none focus-visible:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:ring-inset', del && '[&>td]:opacity-55')}>
                {canManage && (
                  <TableCell className={cellCls} onClick={stop}>
                    {!del && !ro && <Checkbox checked={isSel} onCheckedChange={() => onToggle(r.id)} aria-label={t('bulk.selectOneFor', r.domain)} />}
                  </TableCell>
                )}
                <TableCell className={cn(cellCls, STICKY, 'max-w-[26rem]')}>
                  {/* Nokta + (alan adı · port · açıklama · etiketler) — genişlik SINIRLI (13–19rem): uzun alan adı METİN gibi
                      sarar (Button `inline`), nokta yalnız kalmaz, sütun bitişik sütunlara binmez */}
                  <div className="flex w-max max-w-[15rem] min-w-[12rem] items-start gap-2">
                    <StatusDot r={r} className="mt-[.45em]" />
                    <div className="flex min-w-0 flex-1 flex-col gap-0.5 whitespace-normal">
                      <span className="min-w-0">
                        <Button type="button" variant="link" data-inv-domain="true" onClick={(e) => { e.stopPropagation(); onShow(r) }}
                          className={cn('inline h-auto p-0 text-left leading-snug font-bold break-all whitespace-normal text-foreground hover:text-primary', del && 'line-through')}>
                          {r.domain}
                        </Button>
                        {port !== 443 && <Badge variant="outline" className="ml-1.5 px-1.5 align-middle font-mono text-[.72em] text-muted-foreground">:{port}</Badge>}
                        {isManualCert(r) && <span className="ml-1.5 inline-flex align-middle"><ManualCertBadge version={r.manual_version ?? null} uploadedAt={r.manual_uploaded_at ?? null} rowLabel={r.domain} /></span>}
                      </span>
                      {!compact && r.description && <span className="truncate text-[.8em] text-muted-foreground" title={r.description}>{r.description}</span>}
                      {((!show('group') && r.group_name) || (!show('tags') && r.tags)) && (
                        <span className="flex flex-wrap gap-1">
                          {!show('group') && r.group_name && <GroupChip name={r.group_name} onClick={() => onGroupClick?.(r.group_name)} />}
                          {!show('tags') && tagList(r).slice(0, 3).map((tag) => <TagChip key={tag} tag={tag} onClick={() => onTagClick(tag)} />)}
                        </span>
                      )}
                    </div>
                  </div>
                </TableCell>
                {show('port') && <TableCell className={cn(cellCls, 'tabular-nums')}>{port}</TableCell>}
                {show('tier') && (
                  <TableCell className={cellCls} onClick={stop} onDoubleClick={() => editable && !del && setEditing({ id: r.id, field: 'tier' })}>
                    {editing?.id === r.id && editing.field === 'tier' ? inlineTier(r) : (
                      <Button type="button" variant="ghost" size="xs" data-inv-tier-edit="true"
                        className="h-auto rounded p-0 text-muted-foreground hover:bg-transparent disabled:opacity-100 has-[>svg]:px-0"
                        disabled={!editable || del} onClick={() => setEditing({ id: r.id, field: 'tier' })}
                        title={editable ? t('inv.inlineEditTip') : undefined}
                        aria-label={t('a11y.rowAction', r.domain, `${t('inv.colTier')}: ${r.tier ? `T${r.tier}` : t('tier.unclassified')}`)}>
                        {r.tier ? <TierBadge tier={r.tier} /> : '—'}
                      </Button>
                    )}
                  </TableCell>
                )}
                {show('team') && <TableCell className={cellCls} onClick={stop}>{(r.team_name || teamMap[String(r.team_id)]) ? <TeamBadge teamId={r.team_id} teamName={r.team_name || teamMap[String(r.team_id)]} /> : r.team_id != null ? '—' : <span className="text-[.85em] text-amber-700 dark:text-amber-400">{t('inv.hy.no_team')}</span>}</TableCell>}
                {show('ug_team') && <TableCell className={cellCls} onClick={stop}>{(r.ug_team_name || teamMap[String(r.ug_team_id)]) ? <TeamBadge teamId={r.ug_team_id} teamName={r.ug_team_name || teamMap[String(r.ug_team_id)]} /> : '—'}</TableCell>}
                {show('cert') && <TableCell className={cellCls}><CertCell r={r} t={t} /></TableCell>}
                {show('days') && <TableCell className={cn(cellCls, 'whitespace-nowrap')}><ExpiryCell r={r} t={t} showDate={!compact} withStatus={!show('cert')} /></TableCell>}
                {show('checked') && <TableCell className={cn(dim, 'whitespace-nowrap')} title={r.cert_checked_at ? formatDate(r.cert_checked_at) : undefined}>{r.cert_checked_at ? (relativeTime(r.cert_checked_at, t) || formatDate(r.cert_checked_at)) : <span title={t('inv.certNever')}>—<span className="sr-only">{t('inv.certNever')}</span></span>}</TableCell>}
                {show('group') && <TableCell className={cellCls}>{r.group_name ? <Badge variant="outline" className="font-normal">{r.group_name}</Badge> : <span className="text-muted-foreground">—</span>}</TableCell>}
                {show('contacts') && <TableCell className={cellCls} onClick={stop}><ContactsCell r={r} t={t} /></TableCell>}
                {show('flags') && <TableCell className={cellCls}><FlagCluster r={r} t={t} compact={compact} /></TableCell>}
                {show('domain_exp') && <TableCell className={cn(dim, 'whitespace-nowrap')}>{r.domain_expiry ? <span className={cn('font-bold', dexp != null && dexp <= 30 && 'text-amber-700 dark:text-amber-400')}>{formatDateOnly(r.domain_expiry)}{r.domain_registrar ? ` · ${r.domain_registrar}` : ''}</span> : '—'}</TableCell>}
                {show('interval') && <TableCell className={cn(dim, 'whitespace-nowrap')}>{r.check_interval_hours ? t('inv.intervalHours', r.check_interval_hours) : t('inv.intervalInherit')}</TableCell>}
                {show('tags') && <TableCell className={cellCls} onClick={stop}><span className="flex flex-wrap gap-1">{r.tags ? tagList(r).map((tag) => <TagChip key={tag} tag={tag} onClick={() => onTagClick(tag)} />) : '—'}</span></TableCell>}
                {show('platform') && <TableCell className={cellCls}>{r.platform ? <span className="whitespace-nowrap" title={r.platform_detail || ''}>{platformLabel(r.platform, platformNames)}{r.platform_detail ? <span className="text-muted-foreground"> · {r.platform_detail}</span> : null}</span> : <span className="text-muted-foreground">—</span>}</TableCell>}
                {show('updated') && <TableCell className={cn(dim, 'whitespace-nowrap')}>{r.updated_at ? formatDate(r.updated_at) : '—'}{r.updated_by_name ? <span> · {r.updated_by_name}</span> : null}</TableCell>}
                <TableCell className={cellCls} onClick={stop}>
                  {del
                    ? <DeletedBadge r={r} t={t} />
                    : canManage && !ro
                      ? (
                        // Anahtarın kendisi durumu taşır (görsel + role=switch, erişilebilir ad satırı içerir); rozet yalnız salt okunurda
                        // (2026-09-27: ayrı "Aktif/Pasif" rozeti sütunu 1440'ta tabloyu taşırıyordu)
                        <Switch size="sm" checked={!!r.active} onCheckedChange={(on) => onInline(r, { active: on })}
                          title={t('inv.inlineEditTip')} aria-label={`${r.domain} — ${t('inv.colActive')}`} />
                      )
                      : <ActiveBadge r={r} t={t} />}
                </TableCell>
                <TableCell className={cn(cellCls, 'text-right')} onClick={stop}>
                  {ro ? (
                    // Salt okunur satır: rozet (takım sütunu gizliyse sahibi takımla) + yalnız "Göster"
                    <div className="inline-flex flex-wrap items-center justify-end gap-1.5">
                      <ReadOnlyBadge compact teamId={show('team') ? undefined : r.team_id}
                        teamName={show('team') ? undefined : (r.team_name || teamMap[String(r.team_id)])} />
                      <KebabMenu label={t('inv.colActions')} rowLabel={r.domain} items={rowMenuItems({ r, t, ro, onShow })} />
                    </div>
                  ) : (
                    <div className="inline-flex items-center justify-end gap-1">
                      {!del && (
                        <SimpleTooltip content={(isManualCert(r) ? t('mcert.reevaluate') : t('inv.checkNow'))}>
                          <Button type="button" variant="ghost" size="icon-sm" disabled={busy.has(r.domain)} aria-busy={busy.has(r.domain) || undefined}
                            className="text-muted-foreground pointer-coarse:size-10"
                            onClick={() => checkNow(r)} aria-label={`${r.domain} — ${(isManualCert(r) ? t('mcert.reevaluate') : t('inv.checkNow'))}`}>
                            {busy.has(r.domain) ? <Spinner size={12} inline decorative /> : <Play size={12} aria-hidden="true" />}
                          </Button>
                        </SimpleTooltip>
                      )}
                      <KebabMenu label={t('inv.colActions')} rowLabel={r.domain}
                        items={rowMenuItems({ r, t, ro, isAdmin, canManage, canEditRow, teamsCount, onShow, onCheckNow: checkNow, onDiagnose, onEdit, onDuplicate, onTransfer, onDelete, onRestore, onPurge })} />
                    </div>
                  )}
                </TableCell>
              </TableRow>
            )
          })}
          {/* Hiç satır kalmadığında tabloyu KALDIRMIYORUZ (2026-09-22 QA): süzgeç satırı ekrandan
              silinince kullanıcı ne yazdığını göremiyor ve geri dönecek bir şey bulamıyordu. */}
          {rows.length === 0 && (
            <TableRow data-slot="table-empty-row" className="hover:bg-transparent">
              <TableCell colSpan={99} className="px-3 py-5 text-center">
                <div className="flex flex-wrap items-center justify-center gap-2.5 text-muted-foreground">
                  <Inbox aria-hidden="true" className="size-3.5" />
                  <span>{t('inv.noMatch')}</span>
                  {onClearFilters && (
                    <Button type="button" variant="secondary" size="sm" onClick={onClearFilters}>{t('inv.filterClear')}</Button>
                  )}
                </div>
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  )
}
