import { useCallback, useMemo, useState } from 'react'
import { Building2, Check, LayoutGrid, Palette, Pencil, Plus, RadioTower, RefreshCw, RotateCcw, Tv, Users } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useUserPrefs } from '../../hooks/useUserPrefs.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { useCoarsePointer } from '../../hooks/useCoarsePointer.js'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemMedia, ItemTitle } from '@/components/shadcn/item'
import {
  MAX_WIDGETS, NOC_CONSOLE, NOC_PRESET, STAT_PRESET, addWidget, addableTypes, catalogEntry, effectiveLayout, moveWidget, moveWidgetTo,
  presetLayout, removeWidget, resizeWidget, setWidgetStyle, toDoc, typeVisible, visibleWidgets,
} from './myDashModel.js'
import { WIDGETS } from './widgets.jsx'
import MyDashTv from './MyDashTv.jsx'

/**
 * Izgara YOĞUN yerleşimli (`grid-flow-row-dense`): orta kutunun sığmadığı satır sonundaki boşluğu sonraki küçük kutu
 * doldurur — karışık boyutlarda delik kalmaz. Klavye/ekran okuyucu sırası liste sırasıdır (taşı düğmeleri onu değiştirir).
 *
 * "Panom" — kişisel pano (2026-10-11, kullanıcı kararı "Yeni 'Panom' sekmesi"; mevcut Pano değişmedi). Kullanıcı
 * katalogdan kutu ekler, kaldırır, boyutlandırır (küçük / orta / geniş) ve sıralar (masaüstünde sürükle-bırak, her
 * yerde ← → düğmeleri). Yerleşim kullanıcı tercihi `dashboard` anahtarında, sunucuda (her cihazda aynı pano).
 * Kutular mevcut uçları kullanır ve kendi kapsam/izin kurallarını taşır; yetkisi olmayan kutu ne katalogda ne panoda
 * görünür. "TV modu" (URL `md_tv=1`) duvar ekranı katmanını açar.
 */
export default function MyDashPage({ nocConsole = false }) {
  const t = useT()
  const prefs = useUserPrefs()
  const { perms, scope, canView: canViewPerm, loaded: permsLoaded = true, failed: permsFailed = false, refresh: refreshPerms } = usePermissions()
  // 7/24 konsolu kapısı: sunucunun CANLI kapsamı (60 sn'de bir tazelenir — takıma eklenen / çıkarılan yeniden girmeden
  // görür); eski sunucu `scope` göndermezse App'in giriş anındaki bilgisi (`nocConsole`). İzin matrisinde anahtarı yok —
  // katalog bunu sahte izinle sorar (2026-10-13).
  const consoleOk = typeof scope?.noc_console === 'boolean' ? scope.noc_console : !!nocConsole
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const canView = useCallback((p) => (p === NOC_CONSOLE ? consoleOk : canViewPerm(p)), [perms, consoleOk])
  const { showConfirm } = useDialog()
  const coarse = useCoarsePointer()
  const [refreshKey, setRefreshKey] = useState(0)
  const [edit, setEdit] = useState(false)
  const [catalogOpen, setCatalogOpen] = useState(false)
  const [tv, setTv] = useState(() => readUrlParam('md_tv') === '1')
  const [dragId, setDragId] = useState(null)
  const [overId, setOverId] = useState(null)
  const [announce, setAnnounce] = useState('')
  const [retryingPerms, setRetryingPerms] = useState(false)

  useUrlQuerySync({ md_tv: tv ? '1' : null }, { debounceMs: 0 })

  const list = useMemo(() => effectiveLayout(prefs.dashboard, canView), [prefs.dashboard, canView])
  const shown = useMemo(() => visibleWidgets(list, canView), [list, canView])
  const addable = useMemo(() => addableTypes(list, canView), [list, canView])
  const isVisible = useCallback((w) => typeVisible(w.type, canView), [canView])
  const presetAvailable = typeVisible('noc_light', canView)
  const statWallAvailable = STAT_PRESET.some((p) => typeVisible(p.type, canView))
  // Katalog iki grupta: kutular ve renkli paneller (tek gösterge)
  const addableWidgets = addable.filter((type) => catalogEntry(type)?.group !== 'stat')
  const addableStats = addable.filter((type) => catalogEntry(type)?.group === 'stat')

  // Yetkiler gelmeden düzenleme / kayıt yok (2026-10-12): varsayılan düzen yetkiye göre süzülür; boş snapshot'la
  // kaydedilirse yetkili kutular belgeden düşerdi.
  const canEdit = prefs.ready && permsLoaded
  const titleOf = (type) => t('mydash.w.' + type)

  const apply = (next, message) => {
    if (!canEdit) return
    prefs.setDashboard(toDoc(next))
    if (message) setAnnounce(message)
  }

  const move = (w, dir) => {
    const next = moveWidget(list, w.id, dir, isVisible)
    const pos = visibleWidgets(next, canView).findIndex((x) => x.id === w.id) + 1
    apply(next, t('mydash.moved', titleOf(w.type), pos, shown.length))
  }

  const reset = async () => {
    const ok = await showConfirm({ title: t('mydash.resetTitle'), message: t('mydash.resetMsg'),
      confirmText: t('mydash.reset'), cancelText: t('dlg.cancel'), variant: 'warning' })
    if (!ok) return
    prefs.setDashboard(null)
    setAnnounce(t('mydash.resetDone'))
  }

  const add = (type) => {
    apply(addWidget(list, type), t('mydash.added', titleOf(type)))
  }

  // 7/24 komuta düzeni: tek tıkla hazır yerleşim (onaylı — mevcut düzen değişir; çizilemeyen kutular korunur)
  const applyPreset = async (preset = NOC_PRESET, keys = 'mydash.preset') => {
    if (!canEdit) return
    const ok = await showConfirm({ title: t(keys + '.confirmTitle'), message: t('mydash.preset.confirmMsg'),
      confirmText: t('mydash.preset.apply'), cancelText: t('dlg.cancel'), variant: 'warning' })
    if (!ok) return
    apply(presetLayout(preset, list, canView), t(keys + '.done'))
    setCatalogOpen(false)
  }

  const dndFor = (w) => {
    if (!edit || coarse) return null
    return {
      dragging: dragId === w.id,
      over: !!dragId && dragId !== w.id && overId === w.id,
      onDragStart: (e) => {
        setDragId(w.id)
        try { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', w.id) } catch { /* jsdom */ }
      },
      onDragEnd: () => { setDragId(null); setOverId(null) },
      onDragOver: (e) => {
        if (!dragId || dragId === w.id) return
        e.preventDefault()
        try { e.dataTransfer.dropEffect = 'move' } catch { /* jsdom */ }
        if (overId !== w.id) setOverId(w.id)
      },
      onDragLeave: () => { if (overId === w.id) setOverId(null) },
      onDrop: (e) => {
        e.preventDefault()
        let from = dragId
        try { from = from || e.dataTransfer.getData('text/plain') } catch { /* jsdom */ }
        setDragId(null); setOverId(null)
        if (!from || from === w.id) return
        const next = moveWidgetTo(list, from, w.id)
        const moved = next.find((x) => x.id === from)
        const pos = visibleWidgets(next, canView).findIndex((x) => x.id === from) + 1
        apply(next, moved ? t('mydash.moved', titleOf(moved.type), pos, shown.length) : null)
      },
    }
  }

  // Yetkiler gelmeden görünür kutu yoksa "pano boş" DENMEZ (bilinmiyor ≠ boş) — yükleniyor gösterilir; yetkiler
  // OKUNAMADIYSA sonsuz "yükleniyor" yerine hata + yeniden dene (inceleme 4)
  const permsUnavailable = !permsLoaded && permsFailed
  const loadingPrefs = prefs.status === 'loading' || (!permsLoaded && !permsFailed && shown.length === 0)
  const editHint = !permsLoaded ? t('mydash.editUnavailablePerms') : t('mydash.editUnavailable')
  // Yeniden dene geri bildirim verir (inceleme 4): istek sürerken düğme meşgul — yine başarısız olursa "hiçbir şey olmadı" gibi durmaz
  const retryPerms = () => {
    if (retryingPerms) return
    setRetryingPerms(true)
    Promise.resolve().then(() => refreshPerms()).catch(() => {}).finally(() => setRetryingPerms(false))
  }

  return (
    <div className="tab-content active" data-slot="mydash-page" data-edit={edit ? 'on' : 'off'}>
      <PageHeader icon={LayoutGrid} title={t('mydash.title')} description={t('mydash.desc')}
        // Görüş kapsamı (2026-10-13, kullanıcı isteği: 7/24 ekibi tüm sistemleri görür) — kutular bu kapsamı okur
        meta={typeof scope?.monitoring_all === 'boolean' ? (
          <span data-slot="mydash-scope" data-scope={scope.monitoring_all ? 'all' : 'teams'}
            title={t(scope.monitoring_all ? (scope.noc_operator ? 'mydash.scope.nocHint' : 'mydash.scope.allHint') : 'mydash.scope.teamsHint')}
            className="inline-flex items-center gap-1 rounded-md border border-border bg-muted px-1.5 py-0.5 font-medium text-foreground">
            {scope.monitoring_all ? <Building2 className="size-3.5" aria-hidden="true" /> : <Users className="size-3.5" aria-hidden="true" />}
            {t(scope.monitoring_all ? (scope.noc_operator ? 'mydash.scope.noc' : 'mydash.scope.all') : 'mydash.scope.teams')}
          </span>
        ) : null}
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={() => setRefreshKey((k) => k + 1)} data-slot="mydash-refresh">
              <RefreshCw aria-hidden="true" /> {t('mydash.refresh')}
            </Button>
            {edit && (
              <>
                <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={() => setCatalogOpen(true)}
                  disabled={!addable.length || list.length >= MAX_WIDGETS} data-slot="mydash-add">
                  <Plus aria-hidden="true" /> {t('mydash.add')}
                </Button>
                <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={reset} data-slot="mydash-reset">
                  <RotateCcw aria-hidden="true" /> {t('mydash.reset')}
                </Button>
              </>
            )}
            <Button type="button" variant={edit ? 'default' : 'outline'} className="pointer-coarse:h-10" disabled={!canEdit}
              title={canEdit ? undefined : editHint} aria-pressed={edit}
              onClick={() => { setEdit((v) => !v); setAnnounce('') }} data-slot="mydash-edit">
              {edit ? <Check aria-hidden="true" /> : <Pencil aria-hidden="true" />} {edit ? t('mydash.done') : t('mydash.edit')}
            </Button>
            <Button type="button" className="pointer-coarse:h-10" onClick={() => { setEdit(false); setTv(true) }} disabled={!shown.length} data-slot="mydash-tv-open">
              <Tv aria-hidden="true" /> {t('mydash.tv.open')}
            </Button>
          </div>
        )} />

      <p className="sr-only" aria-live="polite" data-slot="mydash-announce">{announce}</p>

      {edit && (
        <p className="mb-3 text-sm text-muted-foreground" data-slot="mydash-edit-hint">
          {coarse ? t('mydash.editHintTouch') : t('mydash.editHint')}
        </p>
      )}

      {permsUnavailable && shown.length > 0 && (
        <div data-slot="mydash-perms-failed" className="mb-3">
          <AlertBanner tone="warning" className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={retryPerms}
              disabled={retryingPerms} aria-busy={retryingPerms || undefined}>
              <RotateCcw aria-hidden="true" className={retryingPerms ? 'animate-spin' : undefined} /> {t('hist.retry')}</Button>}>
            {t('mydash.permsFailedBody')}
          </AlertBanner>
        </div>
      )}

      {loadingPrefs ? (
        <LoadingBlock label={t('mydash.loading')} />
      ) : permsUnavailable && shown.length === 0 ? (
        <div data-slot="mydash-perms-failed">
          <StatusBlock tone="danger" icon={LayoutGrid} title={t('mydash.permsFailed')} description={t('mydash.permsFailedBody')}
            actions={<Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={retryPerms} disabled={retryingPerms} aria-busy={retryingPerms || undefined}>
              <RotateCcw aria-hidden="true" className={retryingPerms ? 'animate-spin' : undefined} /> {t('hist.retry')}</Button>} />
        </div>
      ) : shown.length === 0 ? (
        <StatusBlock icon={LayoutGrid} title={t('mydash.emptyTitle')} description={t('mydash.emptyBody')}
          actions={canEdit ? (
            <>
              <Button type="button" className="pointer-coarse:h-10" onClick={() => { setEdit(true); setCatalogOpen(true) }} disabled={!addable.length}>
                <Plus aria-hidden="true" /> {t('mydash.add')}
              </Button>
              <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={reset}><RotateCcw aria-hidden="true" /> {t('mydash.reset')}</Button>
            </>
          ) : null} />
      ) : tv ? null : (
        // TV kipi açıkken altındaki ızgara çizilmez: her kutu iki kez yoklanmasın (2026-10-12)
        <div className="@container" data-slot="mydash-grid-wrap">
          <div className="grid grid-flow-row-dense grid-cols-1 gap-4 @2xl:grid-cols-2 @4xl:grid-cols-3 @7xl:grid-cols-4" data-slot="mydash-grid">
            {shown.map((w, i) => {
              const def = WIDGETS[w.type]
              if (!def) return null
              const C = def.Component
              const frame = {
                widget: w, edit, tv: false, canPrev: i > 0, canNext: i < shown.length - 1,
                onMove: (dir) => move(w, dir),
                onResize: (size) => apply(resizeWidget(list, w.id, size), t('mydash.resized', titleOf(w.type), t('mydash.size.' + size))),
                onRemove: () => apply(removeWidget(list, w.id), t('mydash.removed', titleOf(w.type))),
                onStyle: (style) => apply(setWidgetStyle(list, w.id, style), t(style ? 'mydash.styleOn' : 'mydash.styleOff', titleOf(w.type))),
                stat: w.style === 'stat',
                dnd: dndFor(w),
              }
              return <C key={w.id} frame={frame} refreshKey={refreshKey} />
            })}
          </div>
        </div>
      )}

      {catalogOpen && (
        <ModalShell open onClose={() => setCatalogOpen(false)} title={t('mydash.catalogTitle')} icon={Plus} size="lg" scrollBody
          footer={<Button type="button" variant="outline" onClick={() => setCatalogOpen(false)}>{t('mydash.close')}</Button>}>
          <div data-slot="mydash-catalog">
            {presetAvailable && (
              <Item variant="muted" className="mb-4 flex-wrap" data-slot="mydash-preset">
                <ItemMedia variant="icon"><RadioTower aria-hidden="true" /></ItemMedia>
                <ItemContent className="min-w-[12rem]">
                  <ItemTitle className="[overflow-wrap:anywhere]">{t('mydash.preset.title')}</ItemTitle>
                  <ItemDescription className="line-clamp-none [overflow-wrap:anywhere]">{t('mydash.preset.desc')}</ItemDescription>
                </ItemContent>
                <ItemActions>
                  <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => applyPreset()} disabled={!canEdit}
                    aria-label={`${t('mydash.preset.apply')}: ${t('mydash.preset.title')}`} data-slot="mydash-preset-apply">
                    {t('mydash.preset.apply')}
                  </Button>
                </ItemActions>
              </Item>
            )}
            {statWallAvailable && (
              <Item variant="muted" className="mb-4 flex-wrap" data-slot="mydash-preset-stat">
                <ItemMedia variant="icon"><Palette aria-hidden="true" /></ItemMedia>
                <ItemContent className="min-w-[12rem]">
                  <ItemTitle className="[overflow-wrap:anywhere]">{t('mydash.statWall.title')}</ItemTitle>
                  <ItemDescription className="line-clamp-none [overflow-wrap:anywhere]">{t('mydash.statWall.desc')}</ItemDescription>
                </ItemContent>
                <ItemActions>
                  <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => applyPreset(STAT_PRESET, 'mydash.statWall')}
                    disabled={!canEdit} aria-label={`${t('mydash.preset.apply')}: ${t('mydash.statWall.title')}`} data-slot="mydash-preset-stat-apply">
                    {t('mydash.preset.apply')}
                  </Button>
                </ItemActions>
              </Item>
            )}
            <p className="mb-3 text-sm text-muted-foreground">{t('mydash.catalogHint')}</p>
            {addable.length === 0 ? (
              <p className="m-0 text-sm text-muted-foreground" data-slot="mydash-catalog-empty">{t('mydash.catalogEmpty')}</p>
            ) : (
              [['widgets', addableWidgets], ['stats', addableStats]].filter(([, types]) => types.length > 0).map(([group, types]) => (
                <section key={group} className="mb-4 last:mb-0" data-slot="mydash-catalog-group" data-group={group}>
                  <h3 className="mb-1 text-sm font-semibold">{t('mydash.catalogGroup.' + group)}</h3>
                  <p className="mb-2 text-xs text-muted-foreground">{t('mydash.catalogGroup.' + group + 'Hint')}</p>
                  <ItemGroup className="gap-2">
                    {types.map((type) => {
                      const Icon = WIDGETS[type]?.Icon
                      return (
                        <Item key={type} variant="outline" data-slot="mydash-catalog-item" data-type={type} className="flex-wrap">
                          {Icon && <ItemMedia variant="icon"><Icon aria-hidden="true" /></ItemMedia>}
                          <ItemContent className="min-w-[12rem]">
                            <ItemTitle className="[overflow-wrap:anywhere]">{titleOf(type)}</ItemTitle>
                            <ItemDescription className="line-clamp-none [overflow-wrap:anywhere]">{t('mydash.wd.' + type)}</ItemDescription>
                          </ItemContent>
                          <ItemActions>
                            <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={() => add(type)}
                              disabled={list.length >= MAX_WIDGETS} aria-label={t('mydash.addOne', titleOf(type))}>
                              <Plus aria-hidden="true" /> {t('mydash.addShort')}
                            </Button>
                          </ItemActions>
                        </Item>
                      )
                    })}
                  </ItemGroup>
                </section>
              ))
            )}
          </div>
        </ModalShell>
      )}

      {tv && <MyDashTv widgets={shown} refreshKey={refreshKey} onExit={() => setTv(false)}
        permsFailed={permsUnavailable} onRetryPerms={retryPerms} />}
    </div>
  )
}

