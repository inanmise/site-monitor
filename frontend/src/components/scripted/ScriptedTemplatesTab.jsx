import { useEffect, useMemo, useState } from 'react'
import { Plus, Pencil, Eye, History, Copy, Globe2, Users, Trash2, RotateCcw, Search, FlaskConical, ChevronRight } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useScriptedTemplates } from '../../hooks/useScriptedTemplates.js'
import { CATEGORY_ORDER } from '../../utils/templateCategories.js'
import { pickLang } from '../../utils/scriptSourceOptions.js'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ScriptedTemplateEditor from './ScriptedTemplateEditor.jsx'
import ScriptedTemplateVersions from './ScriptedTemplateVersions.jsx'
import Field from '../ui/Field.jsx'
import { VersionChip } from './VersionTimeline.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'

/**
 * Şablon kütüphanesi yönetim yüzeyi — Sentetik İzleme sayfasının "Şablonlar" görünümü.
 *
 * <h3>Yetki UI'da HESAPLANMAZ</h3>
 * Her kart, sunucunun o satır için gönderdiği bayrakları kullanır (`can_edit`, `can_promote`,
 * `can_demote`, `can_permanent_delete`). Yetkiyi burada yeniden türetmek, iki tarafın kaçınılmaz
 * olarak ayrışması ve kullanıcının basabildiği ama 403 yiyen bir düğme görmesi demektir.
 *
 * <h3>Kapsam süzgeci ve çöp kutusu</h3>
 * "Yerleşik" ayrı bir KAPSAM değil, Genel şablonların bir alt kümesidir (`builtin` bayrağı) —
 * seçicideki grup sırasıyla aynı sözlük. Çöp kutusu ise sunucudan AYRI bir istekle gelir
 * (`scope=trash`, yalnız admin): silinmiş satırlar normal listeye karışmaz.
 */
export default function ScriptedTemplatesTab({ t, lang, teams = [], teamName, onUseTemplate }) {
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [scope, setScope] = useState('all')
  const [search, setSearch] = useState('')
  const [tag, setTag] = useState('all')
  const [editing, setEditing] = useState(null)      // {} = yeni · satır = düzenle
  const [viewing, setViewing] = useState(null)      // salt-okunur görüntüleme
  const [versionsFor, setVersionsFor] = useState(null)
  const [demoting, setDemoting] = useState(null)    // genelden takıma indirilecek şablon

  // Çöp kutusu ayrı bir sorgudur; normal liste her zaman aktif satırları taşır.
  const listScope = scope === 'trash' ? 'trash' : undefined
  const { templates, meta, loading, error, reload } = useScriptedTemplates(listScope)

  const tagOptions = useMemo(() => {
    const all = new Set()
    templates.forEach(x => (x.tags || []).forEach(g => all.add(g)))
    return [{ value: 'all', label: t('tpl.tagAll') },
            ...[...all].sort().map(g => ({ value: g, label: g }))]
  }, [templates, t])

  const shown = useMemo(() => {
    const q = search.trim().toLowerCase()
    return templates.filter(x => {
      if (scope === 'general' && !(x.scope === 'general' && !x.builtin)) return false
      if (scope === 'builtin' && !x.builtin) return false
      if (scope === 'team' && x.scope !== 'team') return false
      if (tag !== 'all' && !(x.tags || []).includes(tag)) return false
      if (!q) return true
      const hay = [x.name, x.name_en, x.description, x.description_en, x.team_name, ...(x.tags || [])]
      return hay.some(v => (v || '').toLowerCase().includes(q))
    })
  }, [templates, scope, tag, search])

  /**
   * Kategori ağacı. Sıra SUNUCUDAKİ sıradır (ScriptedTemplateCategories.ORDER): bir siteyi
   * izlemeye baştan başlayan birinin ilerleyeceği yol. Alfabetik sıralamak bu bilgiyi yok ederdi.
   * Kategorisiz şablonlar sonda "Diğer" dalında toplanır — hiçbir kayıt ağacın dışında kalmaz.
   */
  const grouped = useMemo(() => {
    const out = CATEGORY_ORDER.map(key => ({ key, items: shown.filter(x => x.category === key) }))
    const others = shown.filter(x => CATEGORY_ORDER.indexOf(x.category) < 0)
    if (others.length > 0) out.push({ key: 'other', items: others })
    return out.filter(g => g.items.length > 0)
  }, [shown])

  // Varsayılan KAPALI (ürün kararı): 10 dal tek ekrana sığar, kullanıcı aradığı yeri seçer.
  const [openCats, setOpenCats] = useState(() => new Set())
  const toggleCat = (key) => setOpenCats(prev => {
    const next = new Set(prev)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    return next
  })

  /**
   * Arama/filtre varken eşleşen dallar KENDİLİĞİNDEN açılır. Aksi halde kullanıcı arar, sonuç
   * bulunur ama hepsi kapalı dalların içinde kalır ve arama bozuk görünür — kapalı varsayılanın
   * en klasik tuzağı budur.
   */
  const filtering = search.trim() !== '' || tag !== 'all'
  useEffect(() => {
    if (!filtering) return
    setOpenCats(new Set(grouped.map(g => g.key)))
  }, [filtering, grouped])

  const canCreate = meta.can_create_general || (meta.writable_team_ids || []).length > 0

  async function remove(row) {
    const ok = await showConfirm({
      title: t('tpl.deleteTitle'), message: t('tpl.deleteConfirm', row.name),
      variant: 'danger', confirmText: t('tpl.delete'), cancelText: t('tpl.cancel'),
    })
    if (!ok) return
    const res = await api.monitoring.deleteScriptedTemplate(row.id)
    if (res?.success) { toast.success(t('tpl.deleted')); reload() }
    else toast.error(res?.error || t('tpl.deleteError'))
  }

  async function removeForever(row) {
    const ok = await showConfirm({
      title: t('tpl.purgeTitle'), message: t('tpl.purgeConfirm', row.name),
      variant: 'danger', confirmText: t('tpl.purge'), cancelText: t('tpl.cancel'),
    })
    if (!ok) return
    const res = await api.monitoring.deleteScriptedTemplate(row.id, true)
    if (res?.success) { toast.success(t('tpl.purged')); reload() }
    else toast.error(res?.error || t('tpl.deleteError'))
  }

  async function undelete(row) {
    const res = await api.monitoring.undeleteScriptedTemplate(row.id)
    if (res?.success) { toast.success(t('tpl.undeleted')); reload() }
    else toast.error(res?.error || t('tpl.undeleteError'))
  }

  async function promote(row) {
    const ok = await showConfirm({
      title: t('tpl.promoteTitle'), message: t('tpl.promoteConfirm', row.name),
      variant: 'info', confirmText: t('tpl.promote'), cancelText: t('tpl.cancel'),
    })
    if (!ok) return
    const res = await api.monitoring.promoteScriptedTemplate(row.id)
    if (res?.success) { toast.success(t('tpl.promoted')); reload() }
    else toast.error(res?.error || t('tpl.promoteError'))
  }

  /**
   * Genelden takıma indirme HEDEF takım ister (sunucu `teamId` olmadan 400 döner) — bu yüzden
   * onay diyaloğu değil, seçim modalı. Köken takımı biliniyorsa (`source_team_name`) o hazır gelir:
   * geri alma işlemi çoğu zaman şablonun geldiği yere döndürmektir.
   */
  async function demote(row, targetTeamId) {
    // try/finally ŞART: request() ağ hatasında THROW eder; yakalanmazsa setDemoting(null) hiç koşmuyor ve
    // pencere "meşgul" (düğmeler kilitli) kalıyordu — kapatmanın tek yolu sayfayı yenilemekti.
    try {
      const res = await api.monitoring.demoteScriptedTemplate(row.id, Number(targetTeamId))
      if (res?.success) { toast.success(t('tpl.demoted')); reload() }
      else toast.error(res?.error || t('tpl.demoteError'))
    } catch (e) {
      toast.error(e?.message || t('tpl.demoteError'))
    } finally {
      setDemoting(null)
    }
  }

  /** Kopyala: gövdeyi tekil uçtan çeker ve YENİ şablon taslağı olarak editöre koyar. */
  async function duplicate(row) {
    const res = await api.monitoring.getScriptedTemplate(row.id)
    if (!res?.success) { toast.error(res?.error || t('tpl.loadError')); return }
    const src = res.data
    setEditing({ ...src, id: null, name: t('tpl.copyOf', src.name), builtin: false, builtin_key: null })
  }

  const scopeOptions = [
    { value: 'all', label: t('tpl.scopeAll') },
    { value: 'team', label: t('tpl.scopeTeamShort') },
    { value: 'general', label: t('tpl.scopeGeneralShort') },
    { value: 'builtin', label: t('tpl.scopeBuiltin') },
    ...(meta.can_view_trash ? [{ value: 'trash', label: t('tpl.scopeTrash') }] : []),
  ]

  return (
    <div className="sc-templates">
      <div className="upt-toolbar sc-tpl-toolbar">
        <SegmentedControl value={scope} onChange={setScope} options={scopeOptions} ariaLabel={t('tpl.scope')} />
        {tagOptions.length > 1 &&
          <SearchableSelect value={tag} onChange={setTag} options={tagOptions} searchThreshold={6} ariaLabel={t('flt.tag')} />}
        <Input type="text" className="w-auto min-w-[200px]" placeholder={t('tpl.searchPlaceholder')}
          value={search} onChange={e => setSearch(e.target.value)} aria-label={t('tpl.searchPlaceholder')} />
        {canCreate &&
          <Button size="sm" onClick={() => setEditing({})}>
            <Plus size={14} />{t('tpl.new')}
          </Button>}
      </div>

      {error && <AlertBanner tone="danger" title={t('tpl.loadError')}>{error}</AlertBanner>}

      {loading
        ? <LoadingBlock label={t('modal.loading')} />
        : shown.length === 0
          ? <StatusBlock
              tone="neutral"
              icon={Search}
              title={templates.length === 0 ? t('tpl.emptyTitle') : t('tpl.noMatchTitle')}
              description={templates.length === 0 ? t('tpl.emptyText') : t('tpl.noMatchText')}
              actions={templates.length === 0 && canCreate
                ? <Button onClick={() => setEditing({})}>
                    <Plus size={14} />{t('tpl.emptyCta')}
                  </Button>
                : null} />
          : (<div className="flex flex-col gap-2">
            {grouped.map(group => {
              const open = openCats.has(group.key)
              return (
                // Dal: shadcn Collapsible. Kapalı dal İÇERİĞİ HİÇ ÇİZİLMEZ (Radix kapalı içeriği boş
                // bırakır; CSS ile gizlenmez): 100 şablonun tamamını DOM'a koyup saklamak, ağacın
                // çözdüğü sorunu geri getirirdi.
                <Collapsible key={group.key} open={open} onOpenChange={() => toggleCat(group.key)}
                  data-branch={group.key} className="overflow-hidden rounded-[10px] border bg-card">
                  <CollapsibleTrigger asChild>
                    <Button type="button" variant="ghost"
                      className={cn('h-auto w-full justify-start gap-2.5 rounded-none bg-muted/40 px-3.5 py-2.5 text-left text-[13.5px] font-bold hover:bg-muted',
                        open && 'border-b')}>
                      <ChevronRight size={15} aria-hidden="true"
                        className={cn('shrink-0 text-muted-foreground transition-transform duration-150 motion-reduce:transition-none', open && 'rotate-90 text-primary')} />
                      <span className="min-w-0 flex-1 truncate">{t('tpl.cat.' + group.key)}</span>
                      <Badge variant="outline" data-slot="branch-count" className="bg-muted/60 font-bold tabular-nums text-muted-foreground">{group.items.length}</Badge>
                    </Button>
                  </CollapsibleTrigger>
                  <CollapsibleContent>
                    <div className="cards-container p-3.5">
                      {group.items.map(row => (
                        <TemplateCard key={row.id} t={t} lang={lang} row={row}
                          onView={() => setViewing(row)}
                          onEdit={() => setEditing(row)}
                          onVersions={() => setVersionsFor(row)}
                          onDuplicate={() => duplicate(row)}
                          onPromote={() => promote(row)}
                          onDemote={() => setDemoting(row)}
                          onDelete={() => remove(row)}
                          onUndelete={() => undelete(row)}
                          onPurge={() => removeForever(row)}
                          onUse={onUseTemplate ? () => onUseTemplate(row) : null} />
                      ))}
                    </div>
                  </CollapsibleContent>
                </Collapsible>
              )
            })}
          </div>)}

      {/* `editing` YENİ kayıtta da dolu olabilir: "Kopyala" akışı id'siz bir taslak taşır — bu
          yüzden editöre koşulsuz geçilir; id'ye bakıp null göndermek kopyalanan içeriği yutardı. */}
      {editing && (
        <ScriptedTemplateEditor
          t={t} k6Version={meta.k6_version} template={editing}
          meta={meta} teams={teams} teamName={teamName}
          onClose={() => setEditing(null)}
          onSaved={(_saved, opts) => {
            toast.success(t('tpl.saved'))
            reload()
            if (!opts?.keepOpen) setEditing(null)
          }} />
      )}

      {viewing && (
        <ScriptedTemplateEditor
          t={t} lang={lang} k6Version={meta.k6_version} template={viewing} meta={meta}
          teams={teams} teamName={teamName} readOnly onClose={() => setViewing(null)} />
      )}

      {demoting && (
        <DemoteModal t={t} row={demoting} teams={teams}
          onClose={() => setDemoting(null)}
          onConfirm={teamId => demote(demoting, teamId)} />
      )}

      {versionsFor && (
        <ScriptedTemplateVersions
          t={t} template={versionsFor} canEdit={!!versionsFor.can_edit}
          onClose={() => setVersionsFor(null)}
          onRestored={(_saved, version) => {
            toast.success(t('tpl.restored', version))
            setVersionsFor(null)
            reload()
          }} />
      )}
    </div>
  )
}

/**
 * Tek şablon kartı.
 *
 * <p>Kapsam rozeti ADIN yanındadır ve renk taşır: aynı ada sahip bir Genel ve bir Takım şablonu
 * yan yana durabilir; hangisine baktığını rozet olmadan ayırt etmek imkânsızdır.
 */
function TemplateCard({ t, lang, row, onView, onEdit, onVersions, onDuplicate, onPromote,
                        onDemote, onDelete, onUndelete, onPurge, onUse }) {
  const name = pickLang(row.name, row.name_en, lang)
  const when = pickLang(row.when_to_use, row.when_to_use_en, lang)
  const desc = pickLang(row.description, row.description_en, lang)
  const deleted = row.active === false

  // KebabMenu `icon`'u ELEMAN bekler (bileşen değil) — bileşen geçilirse React onu çocuk olarak
  // render etmeye çalışır ve menü sessizce bozulur.
  const items = [
    { label: t('tpl.actView'), icon: <Eye size={13} />, onClick: onView },
    { label: t('tpl.actUse'), icon: <FlaskConical size={13} />, onClick: onUse, hidden: !onUse || deleted },
    { label: t('tpl.actEdit'), icon: <Pencil size={13} />, onClick: onEdit, hidden: !row.can_edit || deleted },
    { label: t('tpl.actVersions'), icon: <History size={13} />, onClick: onVersions },
    { label: t('tpl.actDuplicate'), icon: <Copy size={13} />, onClick: onDuplicate, hidden: deleted },
    { label: t('tpl.actPromote'), icon: <Globe2 size={13} />, onClick: onPromote, hidden: !row.can_promote },
    { label: t('tpl.actDemote'), icon: <Users size={13} />, onClick: onDemote, hidden: !row.can_demote },
    { label: t('tpl.actUndelete'), icon: <RotateCcw size={13} />, onClick: onUndelete, hidden: !deleted },
    { label: t('tpl.actDelete'), icon: <Trash2 size={13} />, onClick: onDelete, danger: true, hidden: !row.can_delete || deleted },
    { label: t('tpl.actPurge'), icon: <Trash2 size={13} />, onClick: onPurge, danger: true, hidden: !deleted || !row.can_permanent_delete },
  ]

  return (
    <Card data-template-card="true" data-deleted={deleted ? 'true' : undefined}
      className={cn('gap-2 rounded-[10px] px-4 py-3.5 shadow-none transition-[box-shadow,translate] duration-150 hover:-translate-y-px hover:shadow-md motion-reduce:transition-none motion-reduce:hover:translate-y-0',
        deleted && 'border-dashed opacity-70')}>
      <CardHeader className="px-0">
        <CardTitle className="flex min-w-0 flex-wrap items-center gap-1.5 text-sm">
          <span className="[overflow-wrap:anywhere]">{name}</span>
          <ScopeBadge t={t} row={row} />
          {row.current_version && <VersionChip>v{row.current_version}</VersionChip>}
          {deleted && <Badge variant="secondary" className={SCOPE_TONE.deleted}>{t('tpl.badgeDeleted')}</Badge>}
        </CardTitle>
        {/* placement="right": aşağı açılan menü kartın kendi içeriğini örtüyordu ve kullanıcı
            hangi şablonun menüsünü açtığını göremiyordu. */}
        <CardAction>
          <KebabMenu items={items} label={t('tpl.actions')} rowLabel={name} placement="right" />
        </CardAction>
      </CardHeader>

      <CardContent className="flex flex-col gap-1.5 px-0 text-[13px] leading-relaxed text-muted-foreground">
        {/* İki satır kırpma: kartlar aynı yükseklikte kalsın, uzun açıklama ızgarayı bozmasın. */}
        {desc && <p className="line-clamp-2">{desc}</p>}
        {when && <p className="line-clamp-2"><b className="text-foreground">{t('scripted.templateWhen')}</b> {when}</p>}

        {(row.tags || []).length > 0 &&
          <div className="my-1 flex flex-wrap gap-1.5">
            {row.tags.map(g => <Badge key={g} variant="outline" className="font-semibold">{g}</Badge>)}
          </div>}

        {(row.env || []).length > 0 &&
          <p><span className="font-semibold text-foreground">{t('scripted.templateEnvNeeded')}</span>{' '}
            {row.env.map(e => e.name).join(', ')}</p>}
      </CardContent>

      <CardFooter className="mt-auto justify-between gap-2 px-0 text-xs text-muted-foreground">
        <span>{t('tpl.updatedBy', row.updated_by_name || row.updated_by || '—')}</span>
        <span>{formatDateSec(row.updated_at)}</span>
      </CardFooter>
    </Card>
  )
}

/**
 * "Genelden takıma indir" — hedef takım seçimi.
 *
 * <p>Sunucu `teamId` olmadan 400 döner; bu yüzden basit bir onay diyaloğu YETMEZ. Köken takımı
 * (`source_team_name`) listede varsa seçili gelir — indirme çoğunlukla geldiği yere iadedir.
 */
function DemoteModal({ t, row, teams, onClose, onConfirm }) {
  const origin = teams.find(tm => tm.name === row.source_team_name)
  const [teamId, setTeamId] = useState(origin ? String(origin.id) : '')
  const [busy, setBusy] = useState(false)
  return (
    <ModalShell open onClose={onClose} title={t('tpl.demoteTitle')} icon={Users} size="sm" busy={busy}
      footer={<>
        <Button variant="secondary" onClick={onClose}>{t('tpl.cancel')}</Button>
        <Button disabled={!teamId || busy}
          onClick={async () => {
            // Bayrak try/finally ile söner: onConfirm reddederse (ağ hatası) pencere kalıcı "meşgul" kalmasın.
            setBusy(true)
            try { await onConfirm(teamId) } finally { setBusy(false) }
          }}>{t('tpl.demote')}</Button>
      </>}>
      <p className="mb-3 text-sm">{t('tpl.demoteText', row.name)}</p>
      <Field label={t('tpl.demoteTarget')} required className="mb-0">
        {({ id }) => (
          <SearchableSelect id={id} value={teamId} onChange={setTeamId} searchThreshold={4}
            options={[{ value: '', label: t('tpl.scopePick') },
                      ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]} />
        )}
      </Field>
    </ModalShell>
  )
}

// Kapsam rozeti tonları (eski .sc-tpl-badge--*): yerleşik nötr, genel marka, takım yeşil, silinmiş kırmızı.
const SCOPE_TONE = {
  builtin: 'bg-muted text-muted-foreground',
  general: 'bg-primary/15 text-primary',
  team: 'bg-success/15 text-success',
  deleted: 'bg-destructive/15 text-destructive',
}

function ScopeBadge({ t, row }) {
  if (row.builtin) return <Badge variant="secondary" data-scope="builtin" className={SCOPE_TONE.builtin}>{t('tpl.badgeBuiltin')}</Badge>
  if (row.scope === 'general') {
    return (<>
      <Badge variant="secondary" data-scope="general" className={SCOPE_TONE.general}>{t('tpl.badgeGeneral')}</Badge>
      {/* Köken rozeti: genele açılmış şablonun nereden geldiği (K5) — güven sinyali. */}
      {row.source_team_name && (
        <Badge variant="ghost" className="px-0 text-[11px] font-semibold text-muted-foreground">{t('scripted.tplFromTeam', row.source_team_name)}</Badge>
      )}
    </>)
  }
  return <Badge variant="secondary" data-scope="team" className={SCOPE_TONE.team}>{row.team_name || t('tpl.badgeTeam')}</Badge>
}
