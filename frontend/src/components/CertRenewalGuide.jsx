import { useState, useEffect, useMemo, useCallback } from 'react'
import { BookOpen, BookOpenText, Boxes, FolderOpen, KeyRound, Link2, Plus, RefreshCw, RotateCcw, Search, SearchX, Server, ShoppingCart, Store } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { navigateTo } from '../utils/navigate.js'
import { useDialog } from './ui/Dialog.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import PageHeader from './ui/PageHeader.jsx'
import { CaFlowCard, GuideLinkCard, GuideStepCard, PlatformTabs } from './renewal/GuideParts.jsx'
import GuideLinkModal from './renewal/GuideLinkModal.jsx'
import { GUIDE_PLATFORMS, GUIDE_STEPS, groupLinks, readChecklist, readPlatform, writeChecklist, writePlatform } from './renewal/guideSteps.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'

/*
 * Sertifika Değişim Rehberi — bilgi mimarisi (2026-09-27, kullanıcı: "Kaynaklar sayfanın üstüne; adım adım sertifika
 * değişimi bu sayfanın ana konusu olmasın — sertifikaları genelde DigiCert gibi CA'lardan SATIN alıyoruz, elle üretim
 * oldukça nadir"). Yukarıdan aşağı:
 *
 *  1. ui/PageHeader — amaç (kurum kaynakları, CA portalları, platform yönergeleri), "N kaynak · M kategori", ilgili
 *     ekranlar (Yenileme Önerileri, Sertifika Envanteri) + yetkiliye BİRİNCİL "Link Ekle"; altında (telefon hariç)
 *     "Bu sayfada" atlama satırı.
 *  2. Kaynaklar (ANA İÇERİK) — arama (başlık/açıklama/adres/kategori) + kategori çipleri (ToggleGroup, sayılı;
 *     telefonda yatay kayar) + kart ızgarası (telefonda tek sütun). Kategori sırası CA portalı → platformlar →
 *     araçlar (renewal/guideSteps groupLinks). CA portalı kategorisi yoksa en üstte boş-durum yer tutucusu ("İlk
 *     bağlantıyı ekle", kategori önceden dolu).
 *  3. CA üzerinden alma / yenileme — kısa 6 adımlı akış kartı (CaFlowCard); adımlar Kaynaklar'daki CA kategorisine,
 *     platform notlarına ve doğrulama ekranlarına bağlanır.
 *  4. Platformunuza yükleme — platform sekmeleri (ui/CollapsibleSection, kapalı başlar).
 *  5. Gelişmiş: kendi anahtarınız ve CSR'niz — eski adım adım yol + "tamamlandı" işaretleri (localStorage anahtarı
 *     `renewal-guide-checklist` AYNI), katlanır, kapalı başlar, en altta.
 *
 * Eski çapalar çalışır: `#guide-steps`, `#guide-step-<id>` Gelişmiş bölümü, `#guide-platforms` platform bölümünü açıp
 * oraya kaydırır; `#guide-resources`, `#guide-cat-<i>` korunur. Bağlantı CRUD sözleşmesi (snake_case `sort_order`) ve
 * yetki kapısı (isAdmin) değişmedi — API burada, pencere renewal/GuideLinkModal.
 */

const ALL = '__all__'
const catValue = (name) => `cat:${name}`
const ADVANCED_ANCHOR = /^guide-(advanced|steps|step-.+)$/
const SECTIONS = [
  { id: 'guide-resources', icon: Link2, labelKey: 'guide.resourcesTitle' },
  { id: 'guide-ca-flow', icon: ShoppingCart, labelKey: 'guide.flowShort' },
  { id: 'guide-platforms', icon: Server, labelKey: 'guide.platformsShort' },
  { id: 'guide-advanced', icon: KeyRound, labelKey: 'guide.advancedShort' },
]

/** Kimliğe kaydırır; öğe yoksa false (katlanır bölüm henüz açılmamış / kaynaklar yüklenmemiş). */
function scrollToId(id) {
  const el = typeof document !== 'undefined' ? document.getElementById(id) : null
  if (!el) return false
  let smooth = true
  try { smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches } catch { /* yoksay */ }
  el.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' })
  return true
}

const linkMatches = (link, q) => [link.title, link.url, link.description, link.category].some((v) => String(v || '').toLowerCase().includes(q))

export default function CertRenewalGuide({ isAdmin }) {
  const t = useT()
  const isMobile = useIsMobile()
  const { showConfirm } = useDialog()
  const [links, setLinks] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [modal, setModal] = useState(null)   // null | { mode: 'add', category? } | { mode: 'edit', link }
  const [query, setQuery] = useState('')
  const [cat, setCat] = useState(ALL)
  const [done, setDone] = useState(readChecklist)
  const [platform, setPlatform] = useState(readPlatform)
  const [platformsOpen, setPlatformsOpen] = useState(false)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [pending, setPending] = useState(null)   // açıldıktan SONRA kaydırılacak kimlik
  const [active, setActive] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await api.guideLinks.list()
      if (res?.success) { setLinks(res.data || []); setLoadError(null) }
      else setLoadError(res?.error || t('guide.loadError'))
    } catch (e) {
      setLoadError(e?.message || t('guide.loadError'))
    } finally {
      setLoading(false)
    }
  }, [t])
  useEffect(() => { load() }, [])   // eslint-disable-line react-hooks/exhaustive-deps

  // ── Kategoriler: CA → platform → araçlar; CA kategorisi yoksa yer tutucu (boş durum + "İlk bağlantıyı ekle") ──
  const groups = useMemo(() => groupLinks(links), [links])
  const caGroup = groups.find((g) => g.kind === 'ca') || null
  const caName = caGroup ? caGroup.name : t('guide.caCategoryDefault')
  const chipGroups = useMemo(() => (caGroup || links.length === 0
    ? groups
    : [{ id: 'guide-cat-ca', name: caName, kind: 'ca', items: [], count: 0, placeholder: true }, ...groups]), [groups, caGroup, caName, links.length])

  const q = query.trim().toLowerCase()
  const matchCount = (g) => (q ? g.items.filter((l) => linkMatches(l, q)).length : g.count)
  const totalMatches = q ? links.filter((l) => linkMatches(l, q)).length : links.length
  // Seçili kategori silindiyse (son bağlantısı gitti) Tümü'ne düş
  const activeCat = cat !== ALL && chipGroups.some((g) => catValue(g.name) === cat) ? cat : ALL

  // ── Sayfa içi geçiş: katlanır bölümü aç, çizildikten sonra kaydır ──
  const reveal = useCallback((id) => {
    if (ADVANCED_ANCHOR.test(id)) setAdvancedOpen(true)
    if (id === 'guide-platforms') setPlatformsOpen(true)
    setActive(id)
    setPending(id)
  }, [])
  useEffect(() => {
    if (!pending) return
    // Öğe yoksa kaynaklar yükleniyor olabilir (`#guide-cat-2`) — yükleme bitince bir kez daha denenir
    if (scrollToId(pending) || !loading) setPending(null)
  }, [pending, loading])
  // Derin bağlantı: adresteki `#guide-…` çapası (eski çapalar dahil) ilgili bölümü açar
  useEffect(() => {
    let hash = ''
    try { hash = decodeURIComponent(window.location.hash.slice(1)) } catch { /* yoksay */ }
    if (/^guide-/.test(hash)) reveal(hash)
  }, [reveal])

  const goCategory = (name) => { setQuery(''); setCat(catValue(name)); reveal('guide-resources') }
  const showPlatforms = () => reveal('guide-platforms')

  const toggleStep = (id, on) => setDone((cur) => {
    const next = on ? [...new Set([...cur, id])] : cur.filter((x) => x !== id)
    writeChecklist(next)
    return next
  })
  const resetChecklist = () => { setDone([]); writeChecklist([]) }
  const choosePlatform = (v) => { setPlatform(v); writePlatform(v) }
  const doneCount = GUIDE_STEPS.filter((s) => done.includes(s.id)).length

  const openAdd = (category) => setModal({ mode: 'add', category: category || '' })
  const openEdit = (link) => setModal({ mode: 'edit', link })

  // Pencere yükü zaten SNAKE_CASE (`sort_order`) — uç @RequestBody GuideLink (Jackson) camelCase anahtarı sessizce düşürürdü.
  async function saveLink(payload) {
    const res = modal?.mode === 'edit' ? await api.guideLinks.update(modal.link.id, payload) : await api.guideLinks.create(payload)
    if (res?.success) { setModal(null); load() }
    return res
  }

  async function deleteLink(link) {
    const res = await api.guideLinks.delete(link.id)
    if (res?.success) { setModal(null); load() }
    return res
  }

  /** Kart menüsündeki Sil: onay burada (pencere içindeki Sil kendi onayını sorar). */
  async function del(link) {
    const ok = await showConfirm({
      title: t('guide.deleteTitle'),
      message: t('guide.deleteMsg', link.title),
      variant: 'danger',
      confirmText: t('guide.deleteConfirm'),
      cancelText: t('guide.deleteCancel'),
    })
    if (!ok) return
    await deleteLink(link)
  }

  const categoryNames = useMemo(() => [...new Set(links.map((l) => String(l.category || '').trim()).filter(Boolean))], [links])

  // ── Kaynaklar gövdesi ──
  const renderGroup = (g, items) => (
    <section key={g.id} id={g.id} data-slot="guide-category" data-kind={g.kind} data-empty={g.placeholder ? 'true' : undefined}
      aria-labelledby={`${g.id}-title`} className="flex min-w-0 scroll-mt-4 flex-col gap-2.5">
      <h4 id={`${g.id}-title`} className="flex items-center gap-2 text-sm font-semibold">
        {g.kind === 'ca'
          ? <Store aria-hidden="true" className="size-4 text-muted-foreground" />
          : <FolderOpen aria-hidden="true" className="size-4 text-muted-foreground" />}
        <span className="min-w-0 break-words">{g.name}</span>{' '}
        <Badge variant="secondary" className="tabular-nums">{items.length}</Badge>
        <span aria-hidden="true" className="h-px flex-1 bg-border" />
      </h4>
      {g.placeholder ? (
        <StatusBlock tone="neutral" icon={Store} title={t('guide.caEmptyTitle')}
          description={isAdmin ? t('guide.caEmptyAdmin') : t('guide.caEmptyViewer')}
          className="rounded-xl border border-dashed py-6 md:py-6"
          actions={isAdmin ? <Button type="button" onClick={() => openAdd(g.name)}><Plus aria-hidden="true" />{t('guide.addFirst')}</Button> : null} />
      ) : (
        <ul className="grid list-none grid-cols-[repeat(auto-fill,minmax(min(300px,100%),1fr))] gap-3">
          {items.map((link) => (
            <li key={link.id} className="min-w-0">
              <GuideLinkCard t={t} link={link} isAdmin={isAdmin} onEdit={openEdit} onDelete={del} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )

  let resources
  if (loading) {
    resources = (
      <div className="grid grid-cols-[repeat(auto-fill,minmax(min(300px,100%),1fr))] gap-3" aria-busy="true">
        <span role="status" className="sr-only">{t('guide.loading')}</span>
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-28 rounded-xl" />)}
      </div>
    )
  } else if (loadError && links.length === 0) {
    resources = (
      <AlertBanner tone="danger" role="alert" title={t('guide.loadError')} className="mb-0"
        actions={<Button type="button" variant="outline" size="sm" onClick={load}><RefreshCw aria-hidden="true" />{t('guide.retry')}</Button>}>
        {String(loadError)}
      </AlertBanner>
    )
  } else if (links.length === 0) {
    resources = (
      <StatusBlock tone="neutral" icon={BookOpen} title={t('guide.empty')} className="rounded-xl border border-dashed"
        actions={isAdmin ? <Button type="button" onClick={() => openAdd(t('guide.caCategoryDefault'))}><Plus aria-hidden="true" />{t('guide.addFirst')}</Button> : null} />
    )
  } else {
    const shown = chipGroups
      .filter((g) => activeCat === ALL || catValue(g.name) === activeCat)
      .map((g) => ({ g, items: q ? g.items.filter((l) => linkMatches(l, q)) : g.items }))
      // Tümü görünümünde aramayla boşalan kategori gizlenir; yer tutucu yalnız arama yokken (ya da seçiliyken)
      .filter(({ g, items }) => (g.placeholder ? (!q || activeCat !== ALL) : items.length > 0))
    resources = shown.length === 0 ? (
      <StatusBlock tone="neutral" icon={SearchX} title={t('guide.noMatch')} className="rounded-xl border border-dashed"
        actions={<Button type="button" variant="outline" size="sm" onClick={() => { setQuery(''); setCat(ALL) }}>{t('app.clearFilters')}</Button>} />
    ) : shown.map(({ g, items }) => renderGroup(g, items))
  }

  return (
    // `@container`: akış ızgarası ve adım girintisi KAP genişliğine bağlı (kenar çubuğu açık tablette içerik ~480 px)
    // `.tab-content h2` (App.css, katmansız) PageHeader başlığına alt boşluk/boy basıyordu → yerel sıfırlama (Envanter deseni)
    <div data-slot="cert-renewal-guide"
      className="@container flex w-full min-w-0 flex-col gap-8 pb-8 [&_[data-slot=page-title]]:mb-0! [&_[data-slot=page-title]]:text-xl! sm:[&_[data-slot=page-title]]:text-2xl!">
      <PageHeader icon={BookOpenText} title={t('guide.title')} description={t('guide.purpose')} className="mb-0"
        meta={!loading && links.length > 0 ? (
          <Badge variant="outline" data-slot="guide-meta-links" className="gap-1 font-normal">
            <Link2 aria-hidden="true" />{t('guide.metaLinks', links.length, groups.length)}
          </Badge>
        ) : null}
        actions={(
          <>
            <Button type="button" variant="outline" onClick={() => navigateTo('renewal')}>
              <RefreshCw aria-hidden="true" />{t('nav.renewal')}
            </Button>
            <Button type="button" variant="outline" onClick={() => navigateTo('domains')}>
              <Boxes aria-hidden="true" />{t('nav.domains')}
            </Button>
            {isAdmin && (
              <Button type="button" onClick={() => openAdd()}>
                <Plus aria-hidden="true" />{t('guide.addLink')}
              </Button>
            )}
          </>
        )}>
        {/* "Bu sayfada" — telefonda yok (bölümler zaten art arda, yer dar); yeni sırayı yansıtır */}
        {!isMobile && (
          <nav aria-label={t('guide.toc')} data-slot="guide-toc" className="hidden min-w-0 flex-wrap items-center gap-1 sm:flex">
            <span className="mr-1 text-xs text-muted-foreground">{t('guide.toc')}:</span>
            {SECTIONS.map((s) => (
              <Button key={s.id} type="button" variant="ghost" size="sm" data-slot="guide-toc-item" data-target={s.id}
                aria-current={active === s.id ? 'location' : undefined} onClick={() => reveal(s.id)}
                className="h-8 px-2.5 font-normal text-muted-foreground aria-[current=location]:bg-accent aria-[current=location]:text-foreground pointer-coarse:h-10">
                <s.icon aria-hidden="true" />{t(s.labelKey)}
              </Button>
            ))}
          </nav>
        )}
      </PageHeader>

      {/* ── 1. Kaynaklar (ana içerik) ── */}
      <section id="guide-resources" data-slot="guide-resources" aria-labelledby="guide-resources-title" className="flex min-w-0 scroll-mt-4 flex-col gap-4">
        {/* Satıra geçiş KAP genişliğiyle: kenar çubuğu açık tablette (~416 px) arama tam genişlik alt satırda */}
        <div className="flex min-w-0 flex-col gap-3 @2xl:flex-row @2xl:items-end @2xl:justify-between">
          <div className="min-w-0">
            <h3 id="guide-resources-title" className="text-lg font-semibold">{t('guide.resourcesTitle')}</h3>
            <p className="max-w-prose text-sm text-muted-foreground">{t('guide.resourcesIntro')}</p>
          </div>
          {links.length > 0 && (
            <InputGroup className="w-full @2xl:max-w-xs">
              <InputGroupInput type="search" value={query} onChange={(e) => setQuery(e.target.value)}
                placeholder={t('guide.search')} aria-label={t('guide.search')} />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            </InputGroup>
          )}
        </div>
        {!loading && links.length > 0 && (
          // Telefonda tek satır, yatay kayar (çipler küçülmez); geniş ekranda sarar
          <ToggleGroup type="single" variant="outline" spacing={2} value={activeCat} onValueChange={(v) => v && setCat(v)}
            aria-label={t('guide.categoryFilter')} data-slot="guide-category-filter"
            className="w-full min-w-0 flex-nowrap justify-start overflow-x-auto pb-1 sm:flex-wrap sm:overflow-visible sm:pb-0">
            {[{ id: 'all', value: ALL, name: t('guide.allCategories'), n: totalMatches },
              ...chipGroups.map((g) => ({ id: g.id, value: catValue(g.name), name: g.name, n: matchCount(g) }))].map((c) => (
              <ToggleGroupItem key={c.id} value={c.value}
                className="h-9 gap-1.5 rounded-full px-3 font-normal data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary pointer-coarse:h-10">
                <span className="max-w-[16rem] truncate">{c.name}</span>{' '}
                <Badge variant="secondary" className="h-5 min-w-5 px-1.5 tabular-nums">{c.n}</Badge>
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        )}
        {resources}
      </section>

      {/* ── 2. CA üzerinden alma / yenileme ── */}
      <section id="guide-ca-flow" aria-labelledby="guide-ca-flow-title" className="min-w-0 scroll-mt-4">
        <CaFlowCard t={t} caCount={caGroup ? caGroup.count : 0} onGoCa={() => goCategory(caName)} onShowPlatforms={showPlatforms} />
      </section>

      <div className="flex min-w-0 flex-col gap-3">
        {/* ── 3. Platformunuza yükleme ── */}
        <CollapsibleSection id="guide-platforms" data-slot="guide-platforms" className="scroll-mt-4"
          open={platformsOpen} onOpenChange={setPlatformsOpen} icon={Server} label={t('guide.platformsTitle')}
          hint={GUIDE_PLATFORMS.map((p) => p.label).join(' · ')} toggleLabel={t('guide.platformsToggle')}
          contentClassName="mt-2 flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-4 sm:p-5">
          <p className="max-w-prose text-sm leading-relaxed">{t('guide.platformsIntro')}</p>
          <PlatformTabs t={t} platform={platform} onPlatform={choosePlatform} categories={groups} onGoCategory={goCategory} />
          <AlertBanner tone="warning" title={t('guide.step.deploy.warnTitle')} className="mb-0 max-w-prose">
            {t('guide.step.deploy.warnText')}
          </AlertBanner>
        </CollapsibleSection>

        {/* ── 4. Gelişmiş: kendi anahtarınız ve CSR'niz (nadiren; en altta, kapalı) ── */}
        <CollapsibleSection id="guide-advanced" data-slot="guide-advanced" className="scroll-mt-4"
          open={advancedOpen} onOpenChange={setAdvancedOpen} icon={KeyRound} label={t('guide.advancedTitle')}
          /* Kapalıyken işaretlenmiş adım varsa ilerleme de görünür (yarım kalan elle yol unutulmasın) */
          hint={doneCount > 0 ? `${t('guide.advancedHint')} · ${t('guide.progress', doneCount, GUIDE_STEPS.length)}` : t('guide.advancedHint')}
          toggleLabel={t('guide.advancedToggle')} contentClassName="mt-3">
          <section id="guide-steps" aria-labelledby="guide-steps-title" className="flex min-w-0 scroll-mt-4 flex-col gap-4">
            <AlertBanner tone="info" className="mb-0 max-w-prose">{t('guide.advancedNote')}</AlertBanner>
            {/* Başlık + işaret eylemleri: dar kapta alt alta; satıra geçiş KAP genişliğiyle */}
            <div className="flex min-w-0 flex-col gap-2 @2xl:flex-row @2xl:items-end @2xl:justify-between">
              <div className="min-w-0">
                <h3 id="guide-steps-title" className="text-lg font-semibold">{t('guide.stepsTitle')}</h3>
                <p className="max-w-prose text-sm text-muted-foreground">{t('guide.advancedIntro')}</p>
              </div>
              <div className="flex flex-wrap items-center gap-2 @2xl:shrink-0">
                <Badge variant="secondary" data-slot="guide-progress-badge"
                  className={doneCount === GUIDE_STEPS.length ? 'h-7 bg-success/15 px-2.5 text-success' : 'h-7 px-2.5'}>
                  {t('guide.progress', doneCount, GUIDE_STEPS.length)}
                </Badge>
                <span className="text-xs text-muted-foreground">{t('guide.checklistNote')}</span>
                <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10" onClick={resetChecklist} disabled={doneCount === 0}>
                  <RotateCcw aria-hidden="true" />{t('guide.resetChecklist')}
                </Button>
              </div>
            </div>
            <ol data-slot="guide-steps" className="flex list-none flex-col gap-3">
              {GUIDE_STEPS.map((step, i) => (
                <GuideStepCard key={step.id} t={t} step={step} n={i + 1} done={done.includes(step.id)} onToggle={toggleStep}
                  onShowPlatforms={showPlatforms} />
              ))}
            </ol>
          </section>
        </CollapsibleSection>
      </div>

      {/* key: her açılışta taze form (ekle ↔ düzenle, farklı bağlantı, önceden dolu kategori) */}
      {modal !== null && (
        <GuideLinkModal key={modal.mode === 'edit' ? `edit-${modal.link.id}` : `add-${modal.category}`} open mode={modal.mode}
          link={modal.mode === 'edit' ? modal.link : (modal.category ? { category: modal.category } : null)} categories={categoryNames}
          onClose={() => setModal(null)} onSave={saveLink} onDelete={deleteLink} />
      )}
    </div>
  )
}
