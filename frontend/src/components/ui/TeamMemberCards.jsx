import { memo, useId, useMemo, useState } from 'react'
import { Mail, PenLine, Search, SearchX, UserRoundSearch, UserX, Users, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CopyButton from './CopyButton.jsx'
import PaginationBar from './PaginationBar.jsx'
import StatusBlock from './StatusBlock.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import { OrgRoleBadge, SystemRoleBadge } from '../admin/ToneBadge.jsx'
import {
  FACET_ALL, FACET_SECONDARY, NO_ROLE, avatarSlot, avatarToneFor, buildSearchIndex, filterMembers, initialsOf,
  isInactiveMember, isSecondaryMember, memberActivityCounts, memberFacets, memberSeed, nameOf,
  sortMembers as sortMembersModel, sortMembersBy, withInactiveLast,
} from './teamMembersModel.js'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * Takım ÜYELERİ paneli — takım-üyeleri penceresinin (TeamMembersModal) "Üyeler" sekmesi. YALNIZ GERÇEK ÜYELER.
 *
 * <p><b>Prod hatası (2026-09-26):</b> eskiden üyelerden yukarı 2 kademe yönetim zinciri yürünüp müdürler üye
 * ızgarasına EKLENİYORDU. Zincir yürüyüşü yok; Takım Müdürü pencere başlığında AYRI çipte durur, üye listesine
 * girmez (gerçekten üyeyse satırında "Takım Müdürü" rozeti taşır). Üyenin kendi müdürü satırda ALAN ("Müdürü: …").
 *
 * <p><b>Yeniden tasarım (2026-09-28):</b> dizin görünümü — yapışkan araç çubuğu (arama: ad/unvan/birim/e-posta,
 * aksan duyarsız · sıralama: rol+kademe / ad / unvan · veriden türeyen rol çipleri + yönetim verisinde "Ek üyelik"),
 * masaüstünde (lg) sütunlu yoğun liste (Kişi · Birim · E-posta), telefonda/tablette yığılmış satır. Büyük takım
 * (100+) standart sayfalamayla (usePagination `modal` ön ayarı + PaginationBar, varsayılan {@link PAGE} satır;
 * "N kişi daha göster" açılımı sayfalama kapısına aykırıydı); sayı her zaman görünür ("N sonuç").
 *
 * <p><b>Gizlilik:</b> satır yalnız beyaz-liste alanlarını çizer (ad, unvan, birim, müdürlük, org rolü, SİSTEM ROLÜ,
 * e-posta, müdür adı) + fotoğraf (kullanıcı kararı 2026-09-28: fotoğraf ve sistem rolü görünür). Yönetim yükleyicisi
 * tam entity verse de telefon / sicil ÇİZİLMEZ. Fotoğrafı olmayanda baş harf (jeton tonlu, kişiye göre kararlı).
 *
 * <p><b>Kullanıcı detayına geçiş (2026-09-30, kullanıcı bildirimi: "Üyeler sekmesindeki kullanıcı üzerinden detay
 * bilgilerine erişim linki yok"):</b> `onView` verildiğinde satırın adı bir düğmedir (telefonda büyük dokunma hedefi)
 * ve satır sonunda "Kullanıcı detayını aç" ikon düğmesi durur; ikisi de `onView(üye)` çağırır — yönetim ekranı bunu
 * `UserDetailPanel`'e (üst üste açılan Sheet) bağlar. `onView` yoksa (kurum-geneli rozet penceresi, sıradan kullanıcı)
 * ad düz metindir ve düğme çizilmez.
 *
 * <p><b>Pasif üyeler (2026-10-02, kullanıcı kararı):</b> gizlenmez — satır soluk (ad + avatar), belirgin "Pasif" rozeti
 * (`team-member-inactive`, UserX), her sıralamada aktiflerden SONRA; sayı satırı "N aktif · M pasif".
 *
 * <p>Test kancaları: `team-member-cards` (liste), `team-member-card` (satır), `team-member-name`,
 * `team-member-leader`, `team-member-manager`, `team-member-open` (yalnız canManage), `team-member-view` +
 * `team-member-view-name` (yalnız onView), `team-member-results`.
 */

/** Varsayılan sayfa boyutu (modal ön ayarının listesinde) — 300 kişilik takımda ilk çizim hızlı kalsın. */
export const PAGE = 50

/** Ad + Soyad baş harfleri (AD'den); yoksa display_name'e düşer. Türkçe-uyumlu büyütme. */
export function adSoyadInitials(m) {
  const fn = (m.first_name || '').trim()
  const ln = (m.last_name || '').trim()
  if (fn || ln) {
    const ii = ((fn[0] || '') + (ln[0] || '')).toLocaleUpperCase('tr-TR')
    if (ii) return ii
  }
  return initialsOf(m.display_name || m.username)
}

const AVATAR_PALETTE = [
  'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
  'linear-gradient(135deg, #0ea5e9 0%, #06b6d4 100%)',
  'linear-gradient(135deg, #10b981 0%, #14b8a6 100%)',
  'linear-gradient(135deg, #f59e0b 0%, #ef4444 100%)',
  'linear-gradient(135deg, #ec4899 0%, #a855f7 100%)',
]
/** Kullanıcı menüsü / kullanıcı detayı gradyanı (dışa açık, 2026-09-27). Takım penceresi jeton tonlarını
 *  (`avatarToneFor`) kullanır; ikisi AYNI karmayı paylaşır → kişi her yüzeyde aynı ton ailesinde. */
export function avatarStyleFor(seed) {
  return { background: AVATAR_PALETTE[avatarSlot(seed, AVATAR_PALETTE.length)], color: '#fff' }
}

/** Geriye uyum: eski içe aktarımlar (`sortMembers`) çalışmaya devam etsin — kural modelde. */
export const sortMembers = sortMembersModel

/**
 * Kişinin fotoğraf kimliği (kullanıcı kararı 2026-09-28: takım penceresinde fotoğraf görünür). Rehber ucu
 * `has_photo` döner → fotoğrafı olmayan için istek atılmaz; yönetim verisinde bayrak yoksa istenir (yoksa 204 →
 * baş harf). Fotoğraf `/api/users/{id}/photo` — oturum açmış herkese açık uç (UserBadge ile aynı).
 */
export const photoIdOf = (m) => (m && m.id != null && m.has_photo !== false ? m.id : null)

/** Avatar: fotoğraf varsa o, yoksa / yüklenemezse baş harf (ton kişiye göre kararlı). Ad yanında durduğu için
 *  ekran okuyucudan gizli. */
export function PersonAvatar({ name, seed, initials, className, photoId = null }) {
  return (
    <Avatar data-slot="person-avatar" aria-hidden="true" className={cn('size-9', className)}>
      {photoId != null && <AvatarImage src={`/api/users/${photoId}/photo`} alt="" className="object-cover" />}
      <AvatarFallback className={cn('text-xs font-semibold', avatarToneFor(seed || name))}>
        {initials || initialsOf(name)}
      </AvatarFallback>
    </Avatar>
  )
}

/** Satır içi e-posta: mailto bağlantısı (adres görünür) + kişiyi adıyla anan kopyala düğmesi. */
export function EmailLine({ email, personName, t, className }) {
  if (!email) return null
  return (
    <div className={cn('flex min-w-0 items-center gap-1', className)}>
      <Mail aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
      <a href={`mailto:${email}`} data-slot="person-email"
        className="inline-flex min-h-10 min-w-0 items-center text-sm text-primary underline-offset-4 hover:underline lg:min-h-0">
        <span className="break-all">{email}</span>
      </a>
      <CopyButton value={email} variant="ghost" buttonSize="icon-sm"
        label={t('a11y.rowAction', personName || email, t('team.copyEmail'))}
        copiedLabel={t('a11y.rowAction', personName || email, t('team.emailCopied'))}
        className="shrink-0 text-muted-foreground max-sm:size-10 pointer-coarse:size-10" />
    </div>
  )
}

const facetLabel = (t, f) => {
  if (f.value === FACET_ALL) return t('team.filterAll')
  if (f.value === FACET_SECONDARY) return t('team.filterSecondary')
  return f.role === NO_ROLE ? t('team.filterNoRole') : t('usr.orgRoleVal.' + f.role)
}

/** Masaüstü sütun şablonu — başlık satırı ve üye satırları AYNI şablonu kullanır. */
const LG_COLS = 'lg:grid-cols-[2.25rem_minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,1.3fr)_auto]'

const MemberRow = memo(function MemberRow({ m, t, isLeader, isManager, secondary, canManage, onSelect, onView, managerLabel, sharedUnit }) {
  const nameId = useId()
  const name = nameOf(m) || '—'
  // Takımın ORTAK müdürlüğü başlıkta bir kez yazılır; satırda tekrar edilmez (her satırda aynı metin = gürültü).
  const unit = m.mudurluk_name && m.mudurluk_name !== m.department && m.mudurluk_name !== sharedUnit ? m.mudurluk_name : null
  const unitLine = [m.department, unit].filter(Boolean).join(' · ')
  const hasUnit = Boolean(unitLine || managerLabel)
  // Pasif üye (2026-10-02, kullanıcı kararı): gizlenmez — soluk ad + avatar, belirgin "Pasif" rozeti, listede sonda.
  const inactive = isInactiveMember(m)
  return (
    <li data-slot="team-member-card" aria-labelledby={nameId} data-inactive={inactive ? 'true' : undefined}
      className={cn('grid grid-cols-[2.25rem_minmax(0,1fr)_auto] items-start gap-x-3 gap-y-0.5 px-3 py-2.5 lg:items-center lg:gap-y-0', LG_COLS,
        inactive && 'bg-muted/40')}>
      <PersonAvatar name={name} seed={memberSeed(m)} initials={adSoyadInitials(m)} photoId={photoIdOf(m)}
        className={cn('row-span-3 mt-0.5 lg:row-span-1 lg:mt-0', inactive && 'opacity-50 grayscale')} />
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
          {onView ? (
            // Ad = detayı açan düğme (telefonda satır yüksekliğinde hedef; bağlantı görünümü, ad metni erişilebilir ad)
            <Button type="button" variant="link" size="sm" data-slot="team-member-view-name" onClick={() => onView(m)}
              title={t('team.memberViewDetail')}
              className={cn('h-auto min-h-0 min-w-0 justify-start p-0 text-left font-medium whitespace-normal max-sm:min-h-10 pointer-coarse:min-h-10',
                inactive ? 'text-muted-foreground' : 'text-foreground')}>
              <span id={nameId} data-slot="team-member-name" className="min-w-0 break-words">{name}</span>
            </Button>
          ) : (
            <span id={nameId} data-slot="team-member-name"
              className={cn('min-w-0 font-medium break-words', inactive && 'text-muted-foreground')}>{name}</span>
          )}
          {inactive && (
            <Badge variant="destructive" data-slot="team-member-inactive" title={t('team.memberInactiveTip')}>
              <UserX aria-hidden="true" /> {t('team.memberInactive')}
            </Badge>
          )}
          {isManager && <Badge variant="secondary" data-slot="team-member-manager">{t('team.colManager')}</Badge>}
          {isLeader && <Badge variant="warning" data-slot="team-member-leader">{t('team.leaderBadge')}</Badge>}
          {m.org_role && m.org_role !== 'TECH' && <OrgRoleBadge role={m.org_role}>{t('usr.orgRoleVal.' + m.org_role)}</OrgRoleBadge>}
          {m.system_role && <SystemRoleBadge role={m.system_role} data-slot="team-member-system-role" />}
          {secondary && <Badge variant="outline" data-slot="team-member-secondary">{t('team.memberSecondary')}</Badge>}
        </div>
        {m.title && <div className="truncate text-sm text-muted-foreground" title={m.title}>{m.title}</div>}
      </div>
      <div className={cn('col-start-2 min-w-0 text-sm lg:col-start-auto', !hasUnit && 'max-lg:hidden')}>
        {unitLine && <div className="line-clamp-2 break-words">{unitLine}</div>}
        {managerLabel && <div className="truncate text-xs text-muted-foreground">{t('team.reportsTo', managerLabel)}</div>}
      </div>
      <div className={cn('col-start-2 min-w-0 lg:col-start-auto', !m.email && 'max-lg:hidden')}>
        <EmailLine email={m.email} personName={name} t={t} />
      </div>
      <div className="col-start-3 row-start-1 flex justify-end gap-1 lg:col-start-auto lg:row-start-auto">
        {onView && (
          <Button type="button" variant="ghost" size="icon-sm" data-slot="team-member-view"
            aria-label={t('a11y.rowAction', name, t('team.memberViewDetail'))} title={t('team.memberViewDetail')}
            onClick={() => onView(m)} className="text-muted-foreground max-sm:size-10 pointer-coarse:size-10">
            <UserRoundSearch aria-hidden="true" />
          </Button>
        )}
        {canManage && (
          <Button type="button" variant="ghost" size="icon-sm" data-slot="team-member-open"
            aria-label={t('a11y.rowAction', name, t('usr.editTitle'))} title={t('usr.editTitle')}
            onClick={() => onSelect?.(m)} className="text-muted-foreground max-sm:size-10 pointer-coarse:size-10">
            <PenLine aria-hidden="true" />
          </Button>
        )}
      </div>
    </li>
  )
})

/**
 * @param members        takımın ÜYELERİ (sunucunun döndürdüğü; müdür zinciri EKLENMEZ)
 * @param leaderId       takım lideri (satırında "Lider" rozeti)
 * @param managerUserId  Takım Müdürü üyeyse satırında rozet (üye değilse listede hiç yoktur)
 * @param teamId         ek üyelik tespiti (yönetim verisi `team_id` taşır)
 * @param managerLabelFor üyenin müdür etiketi (yönetim ekranı: ad ya da sicil); yoksa `manager_display_name`
 * @param sharedUnit     başlıkta gösterilen ortak müdürlük — satırlarda tekrar edilmez
 * @param onView         üyenin kullanıcı detayını açar (yalnız yönetim ekranı, yetkili görüntüleyici); yoksa düğme çizilmez
 */
export default function TeamMemberCards({ members = [], leaderId, managerUserId = null, teamId = null, teamName = '',
  canManage = false, onSelect, onView, managerLabelFor, sharedUnit = null }) {
  const t = useT()
  const searchId = useId()
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState('role')
  const [facet, setFacet] = useState(FACET_ALL)

  const index = useMemo(() => buildSearchIndex(members), [members])
  const facets = useMemo(() => memberFacets(members, teamId), [members, teamId])
  // Yeniden yüklemede seçili çip kaybolduysa (üye ayrıldı) sessizce "Tümü"ne dön.
  const activeFacet = facets.some((f) => f.value === facet) ? facet : FACET_ALL
  // Pasif üyeler her sıralamada SONDA (2026-10-02, kullanıcı kararı); grup içi sıra seçilen sıralamadır.
  const visible = useMemo(
    () => withInactiveLast(sortMembersBy(filterMembers(members, { query, facet: activeFacet, teamId, index }), sort)),
    [members, query, activeFacet, teamId, index, sort])
  const activity = useMemo(() => memberActivityCounts(members), [members])
  // Görünüm (arama/çip/sıra) değişince 1. sayfaya dönülür. Hook erken return'den ÖNCE.
  const pager = usePagination(visible, { listKey: 'team-members', preset: 'modal', defaultSize: PAGE,
    resetDeps: [query, activeFacet, sort] })

  if (!members.length) return <StatusBlock icon={Users} title={t('team.noMembers')} />

  const filtering = Boolean(query.trim()) || activeFacet !== FACET_ALL
  const clearAll = () => { setQuery(''); setFacet(FACET_ALL) }
  const mgrLabel = (m) => (managerLabelFor ? managerLabelFor(m) : null) || m.manager_display_name || null
  const count = visible.length
  const sameId = (a, b) => a != null && b != null && String(a) === String(b)

  return (
    <div className="flex min-w-0 flex-col gap-2">
      {/* Yapışkan araç çubuğu: pencere gövdesi kayarken (telefonda tam ekran) arama hep elde. */}
      <div data-slot="team-member-toolbar" className="sticky top-0 z-10 -mx-1 flex flex-col gap-2 bg-background px-1 pt-1 pb-2">
        <div className="flex min-w-0 items-center gap-2">
          <InputGroup className="min-w-0 flex-1 max-sm:h-10 pointer-coarse:h-10">
            <InputGroupInput id={searchId} type="search" value={query} onChange={(e) => setQuery(e.target.value)}
              placeholder={t('team.memberSearchPh')} aria-label={t('team.memberSearchLabel')} autoComplete="off" enterKeyHint="search"
              className="[&::-webkit-search-cancel-button]:appearance-none" />
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            {query && (
              <InputGroupAddon align="inline-end">
                <InputGroupButton size="icon-xs" aria-label={t('team.clearSearch')} title={t('team.clearSearch')}
                  onClick={() => setQuery('')} className="max-sm:size-10 pointer-coarse:size-10">
                  <X aria-hidden="true" />
                </InputGroupButton>
              </InputGroupAddon>
            )}
          </InputGroup>
          <NativeSelect value={sort} onChange={(e) => setSort(e.target.value)} aria-label={t('team.sortLabel')}
            title={t('team.sortLabel')} className="w-[8.75rem] max-sm:h-10 sm:w-44 pointer-coarse:h-10">
            <NativeSelectOption value="role">{t('team.sortRole')}</NativeSelectOption>
            <NativeSelectOption value="name">{t('team.sortName')}</NativeSelectOption>
            <NativeSelectOption value="title">{t('team.sortTitle')}</NativeSelectOption>
          </NativeSelect>
        </div>
        {facets.length > 2 && (
          // Veriden türeyen hızlı süzgeç: telefonda tek satır yatay kayar, geniş ekranda sarar.
          <ToggleGroup type="single" variant="outline" size="sm" spacing={1} value={activeFacet}
            onValueChange={(v) => v && setFacet(v)} aria-label={t('team.filterLabel')} data-slot="team-member-facets"
            className="w-full flex-nowrap justify-start overflow-x-auto pb-0.5 sm:flex-wrap sm:overflow-visible">
            {facets.map((f) => (
              <ToggleGroupItem key={f.value} value={f.value} aria-label={`${facetLabel(t, f)} (${f.count})`}
                className="shrink-0 gap-1.5 rounded-full px-3 max-sm:h-10 pointer-coarse:h-10 data-[state=on]:border-primary/50 data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
                {facetLabel(t, f)}
                <span className="text-xs tabular-nums opacity-70">{f.count}</span>
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        )}
      </div>

      {/* Sonuç satırı yalnız süzerken GÖRÜNÜR (sayı zaten başlıkta ve sekme hapında); canlı bölge hep DOM'da → duyuru kaçmaz. */}
      <div className={cn('flex min-h-8 items-center justify-between gap-2 text-sm text-muted-foreground', !filtering && 'sr-only')}>
        <span data-slot="team-member-results" role="status" aria-live="polite" className="tabular-nums">
          {filtering
            ? t(count === 1 ? 'team.results.one' : 'team.results', count)
            : activity.inactive > 0
              ? t('team.membersActiveInactive', activity.active, activity.inactive)
              : t(count === 1 ? 'team.membersCount.one' : 'team.membersCount', count)}
        </span>
        {filtering && count > 0 && (
          <Button type="button" variant="link" size="sm" onClick={clearAll} className="h-auto px-0 max-sm:min-h-10 pointer-coarse:min-h-10">
            {t('team.clearFilters')}
          </Button>
        )}
      </div>

      {count === 0 ? (
        <StatusBlock icon={SearchX} title={t('team.noResultsTitle')} description={t('team.noResultsDesc')}
          className="rounded-lg border border-dashed py-8"
          actions={<Button type="button" variant="outline" onClick={clearAll} className="max-sm:h-10 pointer-coarse:h-10">{t('team.clearFilters')}</Button>} />
      ) : (
        <div className="min-w-0 overflow-hidden rounded-lg border bg-card">
          {/* Sütun başlıkları yalnız masaüstünde (görsel; ekran okuyucu satırın adını ve içeriğini okur). */}
          <div aria-hidden="true" className={cn('hidden gap-x-3 border-b bg-muted/40 px-3 py-2 text-xs font-medium text-muted-foreground lg:grid', LG_COLS)}>
            <span className="col-span-2">{t('team.colPerson')}</span>
            <span>{t('team.colUnit')}</span>
            <span>{t('team.colContact')}</span>
            <span />
          </div>
          <ul data-slot="team-member-cards" aria-label={t('team.membersListLabel', teamName)} className="m-0 list-none divide-y p-0">
            {pager.pageItems.map((m) => (
              <MemberRow key={m.id ?? m.username} m={m} t={t}
                isLeader={sameId(leaderId, m.id)} isManager={sameId(managerUserId, m.id)}
                secondary={isSecondaryMember(m, teamId)} canManage={canManage} onSelect={onSelect} onView={onView}
                managerLabel={mgrLabel(m)} sharedUnit={sharedUnit} />
            ))}
          </ul>
        </div>
      )}

      {count > 0 && <PaginationBar {...pager} />}
    </div>
  )
}
