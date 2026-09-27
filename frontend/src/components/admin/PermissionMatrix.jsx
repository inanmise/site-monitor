import { useState, useEffect, useLayoutEffect, useMemo, useCallback, useRef } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { ShieldCheck, Lock, Eye, RotateCcw, X, History, Info, SearchX, ArrowLeftRight } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Switch } from '@/components/shadcn/switch'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Kbd } from '@/components/shadcn/kbd'
import { cn } from '@/lib/utils'
import PageHeader from '../ui/PageHeader.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import RoleSummary from './permissions/RoleSummary.jsx'
import PermissionToolbar from './permissions/PermissionToolbar.jsx'
import MatrixTable, { LockedBadge } from './permissions/MatrixTable.jsx'
import MobileRoleList, { RolePicker } from './permissions/MobileRoleList.jsx'
import { ChangesBar, ReviewSheet, SaveButton } from './permissions/PendingChanges.jsx'
import {
  ROLES, ACTIONS, ACTION_KEYS, GROUP_ORDER, cellKey, parseCellKey, mergeCatalog, groupResources, indexGrants,
  lastChange, roleSummary, filterResources,
} from './permissions/permissionModel.js'

/**
 * Yetki Yönetimi (Yönetim → Yetki Yönetimi, `?tab=permissions`) — 2026-09-27 shadcn yeniden tasarımı.
 *
 * <p>Sayfa: PageHeader (sayaçlar, son değişiklik, Sıfırla · Vazgeç · Kaydet) → rol özet kartları → araç çubuğu
 * (arama, tür süzgeci, kaydedilmemiş/hassas süzgeci, tümünü aç/kapat) → matris (≥768 px) ya da rol seçicili kart
 * listesi (telefon) → açıklama → Rol Modeli. Değişiklikler ANINDA gönderilmez: hücre çevirmek "bekleyen" kümeye
 * yazar, yapışkan alt çubuk ve gözden geçirme paneli (Sheet, tek tek geri alma) üzerinden TOPLU kaydedilir.
 *
 * <p>Sunucu sözleşmesi değişmedi: yazma HÜCRE BAŞINA PUT (`{ role, resource_key, action, allowed }`) — toplu kaydetme
 * istemcide SIRALI yapılır (ilerleme N/M; kısmi hatada başarılılar uygulanır, başarısızlar listede kalır).
 * Hassas yetki VERİLİRKEN onay çevirme anında istenir (iptal → bekleyen değişiklik oluşmaz).
 *
 * <p>Salt okunur kip (2026-09-25 kullanıcı kararı): matrisi herkes görür, yalnız global admin değiştirir — kaynak
 * sunucunun `can_edit` alanı (kapsamlı müdür de false); arayüz rol tahmini yapmaz. Yükleme hatası AYRI durum (R10).
 * Gruplar varsayılan KAPALI (2026-09-26); `initialOpenGroup` yalnız belirli bir grupla açmak gereken yerler için.
 */
export default function PermissionMatrix({ initialOpenGroup = null }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const { refresh: refreshSelf } = usePermissions()
  const phone = useIsMobile()

  const [catalog, setCatalog] = useState([])
  const [grants, setGrants] = useState([])
  const [loading, setLoading] = useState(true)
  const [canEdit, setCanEdit] = useState(false)
  const [loadError, setLoadError] = useState(false)

  /** Kaydedilmemiş değişiklikler: hücre anahtarı → yeni değer. Sunucudaki değere dönen hücre kümeden çıkar. */
  const [pending, setPending] = useState(() => new Map())
  /** Son kaydetmede başarısız olan hücreler → sunucu mesajı (hâlâ bekleyen kümede). */
  const [failures, setFailures] = useState(() => new Map())
  const [saving, setSaving] = useState(null)   // { done, total } | null
  const [reviewOpen, setReviewOpen] = useState(false)

  const [openGroups, setOpenGroups] = useState(() => new Set(initialOpenGroup ? [initialOpenGroup] : []))
  const [query, setQuery] = useState('')
  const [kinds, setKinds] = useState(ACTION_KEYS)
  const [onlyPending, setOnlyPending] = useState(false)
  const [onlySensitive, setOnlySensitive] = useState(false)
  const [focusRole, setFocusRole] = useState(null)        // masaüstü: vurgulanan rol sütunu
  const [mobileRole, setMobileRole] = useState('TEAM_ADMIN') // telefon: seçili rol
  const [roleModelOpen, setRoleModelOpen] = useState(false)
  const rootRef = useRef(null)
  const [barBox, setBarBox] = useState(null)   // geniş ekranda yüzen çubuğun hizası (bileşen kutusu)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      const res = await api.admin.getPermissionMatrix()
      if (res?.success) {
        setCatalog(res.catalog || [])
        setGrants(res.grants || [])
        setCanEdit(res.can_edit === true)
        setLoadError(false)
        setPending(new Map())
        setFailures(new Map())
      } else {
        setLoadError(true)
        setCanEdit(false)   // bayat veriyle anahtar çevrilmesin
      }
    } catch {
      setLoadError(true)
      setCanEdit(false)
    } finally {
      setLoading(false)
    }
  }

  const resources = useMemo(() => mergeCatalog(catalog), [catalog])
  const base = useMemo(() => indexGrants(grants), [grants])
  const last = useMemo(() => lastChange(grants), [grants])
  const isOn = useCallback((role, resourceKey, action) => {
    const k = cellKey(role, resourceKey, action)
    return pending.has(k) ? pending.get(k) : base.get(k) === true
  }, [pending, base])

  const filters = { query, kinds, onlyPending, onlySensitive, pending, t }
  const visible = filterResources(resources, filters)
  const grouped = groupResources(visible)
  const visibleKinds = ACTIONS.filter((a) => kinds.includes(a.key))
  const summaries = Object.fromEntries(ROLES.map((r) => [r.key, roleSummary(resources, r, isOn)]))
  const allOpen = grouped.length > 0 && grouped.every(([g]) => openGroups.has(g))
  const filtering = query.trim() !== '' || onlyPending || onlySensitive || kinds.length < ACTION_KEYS.length
  const dirty = pending.size > 0

  // Kaydedilmemiş değişiklikle sayfadan çıkış (yenile / sekmeyi kapat) tarayıcı uyarısı alır.
  useEffect(() => {
    if (!dirty) return undefined
    const warn = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  // Bekleyen kalmadıysa "yalnız kaydedilmemişler" süzgeci kendiliğinden kapanır (boş liste kalmasın).
  useEffect(() => { if (!dirty && onlyPending) setOnlyPending(false) }, [dirty, onlyPending])

  // Yüzen çubuk (fixed) geniş ekranda bileşenin kutusuna hizalanır: kenar çubuğu açılıp kapanınca / pencere
  // değişince yeniden ölçülür. Sağ altta yardım düğmesi (18 px + 42 px) için 76 px boş bırakılır.
  const showBar = canEdit && (dirty || !!saving) && !reviewOpen
  useLayoutEffect(() => {
    const el = rootRef.current
    if (phone || !showBar || !el) return undefined
    const measure = () => {
      const r = el.getBoundingClientRect()
      const right = Math.min(r.right, document.documentElement.clientWidth - 76)
      const next = r.width > 0 ? { left: Math.round(r.left), width: Math.max(0, Math.round(right - r.left)) } : null
      setBarBox((prev) => (prev?.left === next?.left && prev?.width === next?.width ? prev : next))
    }
    measure()
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    ro?.observe(el)
    window.addEventListener('resize', measure)
    return () => { ro?.disconnect(); window.removeEventListener('resize', measure) }
  }, [phone, showBar])

  /** Süzgeç/arama sonucu grupları AÇAR — sonuç kapalı başlıkların altında saklı kalmasın. */
  function reveal(next) {
    const f = { ...filters, ...next }
    const active = f.query.trim() !== '' || f.onlyPending || f.onlySensitive
    if (!active) return
    const groups = groupResources(filterResources(resources, f)).map(([g]) => g)
    setOpenGroups((prev) => new Set([...prev, ...groups]))
  }
  const onQuery = (v) => { setQuery(v); reveal({ query: v }) }
  const onOnlyPending = (v) => { setOnlyPending(v); reveal({ onlyPending: v }) }
  const onOnlySensitive = (v) => { setOnlySensitive(v); reveal({ onlySensitive: v }) }
  function clearFilters() {
    setQuery(''); setKinds(ACTION_KEYS); setOnlyPending(false); setOnlySensitive(false)
  }

  function toggleGroup(group) {
    setOpenGroups((prev) => {
      const next = new Set(prev)
      if (next.has(group)) next.delete(group); else next.add(group)
      return next
    })
  }
  function toggleAll() {
    const names = grouped.map(([g]) => g)
    setOpenGroups((prev) => {
      const next = new Set(prev)
      for (const g of names) { if (allOpen) next.delete(g); else next.add(g) }
      return next
    })
  }

  async function toggle(role, item, action) {
    if (!canEdit || saving) return
    const k = cellKey(role, item.resource_key, action)
    const original = base.get(k) === true
    const nextValue = !isOn(role, item.resource_key, action)
    // Hassas yetki VERİLİRKEN onay (sunucudaki değere geri dönmek değişiklik değildir → sorulmaz).
    if (nextValue && !original && item.sensitive?.includes(action)) {
      const ok = await showConfirm({
        title: t('perm.sensitiveTitle'),
        message: t('perm.sensitiveMsg', role, item.resource_key),
        variant: 'danger',
        confirmText: t('perm.sensitiveConfirm'),
        cancelText: t('perm.cancel'),
      })
      if (!ok) return
    }
    setPending((prev) => {
      const next = new Map(prev)
      if (nextValue === original) next.delete(k); else next.set(k, nextValue)
      return next
    })
    setFailures((prev) => { if (!prev.has(k)) return prev; const next = new Map(prev); next.delete(k); return next })
  }

  function undo(k) {
    setPending((prev) => { const next = new Map(prev); next.delete(k); return next })
    setFailures((prev) => { if (!prev.has(k)) return prev; const next = new Map(prev); next.delete(k); return next })
  }

  async function discardAll() {
    if (!dirty || saving) return
    const ok = await showConfirm({
      title: t('perm.discardTitle'),
      message: t('perm.discardMsg', pending.size),
      variant: 'danger',
      confirmText: t('perm.discardConfirm'),
      cancelText: t('perm.cancel'),
    })
    if (!ok) return
    setPending(new Map())
    setFailures(new Map())
    setReviewOpen(false)
  }

  /** Toplu kaydetme — hücre başına SIRALI PUT (sunucu sözleşmesi), ilerleme N/M, kısmi hata raporu. */
  async function save() {
    if (!canEdit || saving || pending.size === 0) return
    const entries = [...pending.entries()]
    setSaving({ done: 0, total: entries.length })
    const saved = []
    const failed = new Map()
    for (let i = 0; i < entries.length; i++) {
      const [k, allowed] = entries[i]
      const { role, resource_key, action } = parseCellKey(k)
      try {
        // Bilinçli SIRALI (paralel değil): denetim kaydı tıklama sırasıyla yazılsın, tek pod boğulmasın.
        const res = await api.admin.updatePermissionGrant({ role, resource_key, action, allowed })
        if (res?.success) saved.push([k, res.data?.role ? res.data : { role, resource_key, action, allowed }])
        else failed.set(k, res?.error || t('perm.errorGeneric'))
      } catch (e) {
        failed.set(k, e?.message || t('perm.errorGeneric'))
      }
      setSaving({ done: i + 1, total: entries.length })
    }
    if (saved.length) {
      const done = new Set(saved.map(([k]) => k))
      setGrants((prev) => [
        ...prev.filter((g) => !done.has(cellKey(g.role, g.resource_key, g.action))),
        ...saved.map(([, g]) => g),
      ])
      setPending((prev) => { const next = new Map(prev); for (const k of done) next.delete(k); return next })
      refreshSelf()
    }
    setFailures(failed)
    setSaving(null)
    if (failed.size === 0) {
      toast.success(saved.length === 1 ? t('perm.savedOne') : t('perm.savedN', saved.length))
      setReviewOpen(false)
    } else {
      toast.error(t('perm.saveFailedTitle', failed.size, entries.length))
      setReviewOpen(true)
    }
  }

  async function resetDefaults() {
    const ok = await showConfirm({
      title: t('perm.resetTitle'),
      message: dirty ? `${t('perm.resetMsg')} ${t('perm.resetPending', pending.size)}` : t('perm.resetMsg'),
      variant: 'danger',
      confirmText: t('perm.resetConfirm'),
      cancelText: t('perm.cancel'),
    })
    if (!ok) return
    const res = await api.admin.resetPermissionsToDefaults()
    if (res?.success) {
      setPending(new Map()); setFailures(new Map()); setReviewOpen(false)
      load(); refreshSelf(); toast.success(t('perm.resetDone'))
    } else toast.error(res?.error || t('perm.errorGeneric'))
  }

  /** Rol kartı: masaüstünde sütunu vurgula + görünür kıl; telefonda rol seçicisini o role çevir. */
  function pickRole(roleKey) {
    if (phone) { setMobileRole(roleKey); return }
    setFocusRole((prev) => (prev === roleKey ? null : roleKey))
    requestAnimationFrame(() => {
      document.querySelector(`[data-testid="perm-matrix"] th[data-role="${roleKey}"]`)
        ?.scrollIntoView?.({ block: 'nearest', inline: 'center', behavior: 'smooth' })
    })
  }

  const changes = useMemo(() => {
    const byKey = new Map(resources.map((r) => [r.resource_key, r]))
    const rank = (g) => { const i = GROUP_ORDER.indexOf(g); return i === -1 ? 99 : i }
    const roleRank = (r) => ROLES.findIndex((x) => x.key === r)
    return [...pending.entries()].map(([key, next]) => {
      const c = parseCellKey(key)
      const res = byKey.get(c.resource_key)
      return { key, next, ...c, group: res?.group, sensitive: !!res?.sensitive?.includes(c.action) }
    }).sort((a, b) => rank(a.group) - rank(b.group) || a.resource_key.localeCompare(b.resource_key)
      || roleRank(a.role) - roleRank(b.role) || ACTION_KEYS.indexOf(a.action) - ACTION_KEYS.indexOf(b.action))
  }, [pending, resources])

  const ready = !loading && !loadError
  const showActions = ready && canEdit
  const failedCount = [...failures.keys()].filter((k) => pending.has(k)).length
  const mobileRoleObj = ROLES.find((r) => r.key === mobileRole) ?? ROLES[1]

  return (
    // Çubuk görünürken alt boşluk: yüzen çubuk son içeriği (Rol Modeli) örtmesin.
    <div ref={rootRef} className={cn('flex min-w-0 flex-col gap-4', showBar && 'pb-32 md:pb-20')} data-testid="permission-matrix">
      <PageHeader icon={ShieldCheck} title={t('perm.title')} description={t('perm.pageDesc')} className="mb-0"
        meta={ready && (
          <>
            <Badge variant="outline" className="font-normal text-muted-foreground tabular-nums">{t('perm.metaResources', resources.length)}</Badge>
            <Badge variant="outline" className="font-normal text-muted-foreground tabular-nums">{t('perm.metaRoles', ROLES.length)}</Badge>
            {last && (
              <Badge variant="outline" data-slot="perm-last-change" className="gap-1 font-normal whitespace-normal text-muted-foreground">
                <History aria-hidden="true" />{t('perm.metaLastChange', last.by || '—', formatDate(last.at))}
              </Badge>
            )}
            {!canEdit && (
              <Badge variant="secondary" className="gap-1 font-semibold text-muted-foreground"><Lock aria-hidden="true" />{t('perm.readOnlyBadge')}</Badge>
            )}
          </>
        )}
        actions={showActions && (
          <>
            <Button type="button" variant="outline" onClick={resetDefaults} disabled={!!saving}
              className="border-destructive/40 text-destructive hover:bg-destructive/10 hover:text-destructive dark:border-destructive/50">
              <RotateCcw aria-hidden="true" />{t('perm.resetBtn')}
            </Button>
            <Button type="button" variant="ghost" onClick={discardAll} disabled={!dirty || !!saving}>
              <X aria-hidden="true" />{t('perm.discard')}
            </Button>
            <SaveButton saving={saving} disabled={!dirty} onSave={save} />
          </>
        )}>
        {!loading && loadError && (
          <AlertBanner tone="danger" title={t('perm.loadErrorTitle')} className="mb-0"
            actions={<Button variant="outline" size="sm" onClick={load}>{t('perm.retry')}</Button>}>
            {t('perm.loadErrorText')}
          </AlertBanner>
        )}
        {/* Salt okunur bandı YALNIZ başarılı yüklemeden sonra: sunucu can_edit=false dedi demektir. */}
        {ready && !canEdit && (
          <AlertBanner tone="info" icon={Eye} title={t('perm.readOnlyTitle')} className="mb-0">{t('perm.readOnlyText')}</AlertBanner>
        )}
      </PageHeader>

      {loading && <LoadingSkeleton label={t('perm.loading')} />}

      {ready && resources.length === 0 && (
        <StatusBlock icon={ShieldCheck} title={t('perm.emptyTitle')} description={t('perm.emptyText')} className="rounded-xl border border-dashed" />
      )}

      {ready && resources.length > 0 && (
        <>
          <RoleSummary roles={ROLES} summaries={summaries} active={phone ? mobileRole : focusRole} onPick={pickRole} />

          <div className="flex flex-col gap-3 rounded-xl border bg-card p-2 shadow-xs sm:p-3">
            <PermissionToolbar query={query} onQuery={onQuery} kinds={kinds} onKinds={setKinds}
              canEdit={canEdit} pendingCount={pending.size} onlyPending={onlyPending} onOnlyPending={onOnlyPending}
              onlySensitive={onlySensitive} onOnlySensitive={onOnlySensitive}
              allOpen={allOpen} onToggleAll={toggleAll} disableGroups={grouped.length === 0} />
            {phone && <RolePicker roles={ROLES} value={mobileRole} onChange={setMobileRole} />}
          </div>

          {grouped.length === 0 ? (
            <StatusBlock icon={SearchX} title={t('perm.noResults')} description={t('perm.noResultsHint')}
              className="rounded-xl border border-dashed"
              actions={filtering && <Button type="button" variant="outline" onClick={clearFilters}>{t('perm.clearFilters')}</Button>} />
          ) : phone ? (
            <MobileRoleList groups={grouped} openGroups={openGroups} onToggleGroup={toggleGroup} role={mobileRoleObj}
              kinds={kinds} isOn={isOn} pending={pending} failures={failures} canEdit={canEdit} busy={!!saving} onToggle={toggle} />
          ) : (
            <MatrixTable groups={grouped} openGroups={openGroups} onToggleGroup={toggleGroup} roles={ROLES} kinds={visibleKinds}
              isOn={isOn} pending={pending} failures={failures} canEdit={canEdit} busy={!!saving} onToggle={toggle} focusRole={focusRole} />
          )}

          <Legend phone={phone} />

          <CollapsibleSection open={roleModelOpen} onOpenChange={setRoleModelOpen} icon={Info} label={t('perm.roleModelTitle')}>
            <div className="mt-2 flex flex-col gap-2 rounded-xl border bg-card px-4 py-3 text-sm">
              <p className="flex items-start gap-2 text-muted-foreground"><Lock aria-hidden="true" className="mt-0.5 size-4 shrink-0" />{t('perm.adminLockedNote')}</p>
              <ul className="flex list-disc flex-col gap-1 pl-5">
                <li><strong>ADMIN</strong> — {t('perm.roleModelAdmin')}</li>
                <li><strong>{t('perm.roleModelManagerName')}</strong> — {t('perm.roleModelManager')}</li>
                <li><strong>TEAM_ADMIN / PO</strong> — {t('perm.roleModelTeamAdmin')}</li>
                <li><strong>USER</strong> — {t('perm.roleModelUser')}</li>
                <li><strong>AUDIT</strong> — {t('perm.roleModelAudit')}</li>
              </ul>
              <p className="text-xs text-muted-foreground">{t('perm.roleModelNote')}</p>
            </div>
          </CollapsibleSection>

          {showBar && (
            <ChangesBar count={pending.size} failedCount={failedCount} saving={saving} phone={phone} box={barBox}
              onReview={() => setReviewOpen(true)} onDiscard={discardAll} onSave={save} />
          )}
          {canEdit && (
            <ReviewSheet open={reviewOpen} onOpenChange={setReviewOpen} phone={phone} changes={changes} failures={failures}
              saving={saving} onUndo={undo} onSave={save} />
          )}
        </>
      )}
    </div>
  )
}

/** Sembollerin anlamı — tek satır, sarar. Klavye ipucu yalnız matris görünümünde (telefonda ok tuşu yok). */
function Legend({ phone }) {
  const t = useT()
  return (
    <div data-slot="perm-legend" aria-label={t('perm.legendTitle')} role="group"
      className="flex flex-wrap items-center gap-x-5 gap-y-2 rounded-xl border bg-muted/30 px-3.5 py-2.5 text-xs text-muted-foreground">
      <span className="inline-flex items-center gap-2">
        <Switch checked tabIndex={-1} aria-hidden="true" className="pointer-events-none" />{t('perm.legendOn')}
      </span>
      <span className="inline-flex items-center gap-2">
        <Switch checked={false} tabIndex={-1} aria-hidden="true" className="pointer-events-none" />{t('perm.legendOff')}
      </span>
      <span className="inline-flex items-center gap-2"><LockedBadge />{t('perm.legendLocked')}</span>
      <span className="inline-flex items-center gap-2">
        <span aria-hidden="true" className="inline-flex w-6 justify-center text-base">—</span>{t('perm.legendNa')}
      </span>
      <span className="inline-flex items-center gap-2">
        <span aria-hidden="true" className="size-2 rounded-full bg-amber-500" />{t('perm.legendChanged')}
      </span>
      {!phone && (
        <span className="inline-flex items-center gap-2">
          <ArrowLeftRight aria-hidden="true" className="size-3.5" />
          <span><Kbd>←</Kbd> <Kbd>↑</Kbd> <Kbd>→</Kbd> <Kbd>↓</Kbd> {t('perm.legendKeys')}</span>
        </span>
      )}
    </div>
  )
}

/** İlk yükleme iskeleti — özet kartları + araç çubuğu + satırlar (gerçek yerleşimle aynı boy, zıplama yok). */
function LoadingSkeleton({ label }) {
  return (
    <div className="flex flex-col gap-4">
      <span role="status" className="sr-only">{label}</span>
      <div className="grid grid-cols-2 gap-2 sm:gap-3 lg:grid-cols-4">
        {ROLES.map((r) => <Skeleton key={r.key} className="h-[132px] rounded-xl" />)}
      </div>
      <Skeleton className="h-14 rounded-xl" />
      <div className="flex flex-col gap-2 rounded-xl border p-3">
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-9 w-full" />)}
      </div>
    </div>
  )
}
