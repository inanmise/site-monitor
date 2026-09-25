import { useState } from 'react'
import { Pause, Play, Users, FolderInput, Trash2, X, CheckSquare, Square } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from './Toast.jsx'
import { useDialog } from './Dialog.jsx'
import { Spinner } from './Progress.jsx'
import SearchableSelect from './SearchableSelect.jsx'
import { Button } from '@/components/shadcn/button'
import { ButtonGroup } from '@/components/shadcn/button-group'
import { Input } from '@/components/shadcn/input'

/**
 * Toplu işlem çubuğu (2026-09-12, zenginleştirme #13) — izleme sayfalarında çoklu seçim:
 * duraklat / sürdür / takım değiştir / grup ata / sil. Sunucuya YENİ uç eklenmedi: her satır için
 * mevcut PUT (kısmi gövde: {active} | {teamId} | {groupName}) ya da DELETE çağrılır — yetki, denetim
 * kaydı ve alarm kapatma (closeAlertsOnPause) tek tek işlemle birebir aynı yoldan geçer.
 *
 * props: selected (Set<id>), items (görünür liste), onClear, onDone(reload), api: { update(id, body), remove(id) },
 *        teams [{id,name}], canDelete(m) → bool, label (tür adı)
 */
export default function BulkActionBar({ selected, items, onClear, onDone, api, teams = [], canDelete = () => false, onToggleAll }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [busy, setBusy] = useState(null)   // 'pause' | 'resume' | 'team' | 'group' | 'delete'
  const [teamId, setTeamId] = useState('')
  const [group, setGroup] = useState('')
  const ids = [...selected]
  if (ids.length === 0) return null
  const chosen = items.filter((m) => selected.has(m.id))
  const allVisibleSelected = items.length > 0 && items.every((m) => selected.has(m.id))

  async function run(kind, fn, filterFn) {
    const targets = filterFn ? chosen.filter(filterFn) : chosen
    if (targets.length === 0) { toast.error(t('bulk.none')); return }
    setBusy(kind)
    let ok = 0, fail = 0
    for (const m of targets) {
      try { const r = await fn(m); if (r?.success === false) fail++; else ok++ } catch { fail++ }
    }
    setBusy(null)
    if (fail === 0) toast.success(t('bulk.done', ok)); else toast.error(t('bulk.partial', ok, fail))
    onClear?.(); onDone?.()
  }

  async function del() {
    const targets = chosen.filter(canDelete)
    if (targets.length === 0) { toast.error(t('bulk.noDeleteRight')); return }
    const ok = await showConfirm({ title: t('bulk.deleteTitle'), message: t('bulk.deleteMsg', targets.length), confirmText: t('bulk.deleteConfirm'), cancelText: t('app.cancel'), variant: 'danger' })
    if (!ok) return
    run('delete', (m) => api.remove(m.id), canDelete)
  }

  // Alan bölmesi: ikon + kontrol + "Uygula" — solunda ince ayraç (eski .bulkbar-field dili).
  const fieldCls = 'inline-flex items-center gap-1.5 border-l border-border pl-2 text-muted-foreground'
  const selectAllLabel = allVisibleSelected ? t('bulk.unselectAll') : t('bulk.selectAll')
  return (
    // Görünüm Tailwind + shadcn (Button / ButtonGroup / Input); App.css .bulkbar* kurallarına
    // bağlı değil — o aile yalnız CertBulkBar'da yaşıyor.
    <div data-slot="bulk-action-bar" role="region" aria-label={t('bulk.aria')}
      className="sticky top-2 z-[6] mb-2.5 flex flex-wrap items-center gap-2.5 rounded-[10px] border border-primary bg-card px-3 py-2 shadow-lg">
      <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground"
        onClick={onToggleAll} title={selectAllLabel} aria-label={selectAllLabel}>
        {allVisibleSelected ? <CheckSquare size={15} /> : <Square size={15} />}
      </Button>
      <span className="font-bold">{t('bulk.selected', ids.length)}</span>
      <div className="flex flex-wrap items-center gap-1.5">
        {/* Duraklat / Sürdür birbirinin karşıtı — tek bir düğme grubu. */}
        <ButtonGroup>
          <Button type="button" variant="secondary" size="sm" disabled={!!busy} onClick={() => run('pause', (m) => api.update(m.id, { active: false }), (m) => m.active !== false)}>
            {busy === 'pause' ? <Spinner size={12} inline decorative /> : <Pause size={13} />} {t('bulk.pause')}
          </Button>
          <Button type="button" variant="secondary" size="sm" disabled={!!busy} onClick={() => run('resume', (m) => api.update(m.id, { active: true }), (m) => m.active === false)}>
            {busy === 'resume' ? <Spinner size={12} inline decorative /> : <Play size={13} />} {t('bulk.resume')}
          </Button>
        </ButtonGroup>
        {teams.length > 0 && (
          <span className={fieldCls}>
            <Users size={13} aria-hidden="true" />
            <span className="min-w-[140px]">
              <SearchableSelect value={teamId} onChange={setTeamId} ariaLabel={t('bulk.team')} placeholder={t('bulk.team')}
                options={teams.map((tm) => ({ value: String(tm.id), label: tm.name }))} />
            </span>
            <Button type="button" variant="secondary" size="sm" disabled={!!busy || !teamId} onClick={() => run('team', (m) => api.update(m.id, { teamId: Number(teamId) }))}>{t('bulk.apply')}</Button>
          </span>
        )}
        <span className={fieldCls}>
          <FolderInput size={13} aria-hidden="true" />
          <ButtonGroup>
            <Input className="h-8 min-w-[140px] text-foreground" value={group} onChange={(e) => setGroup(e.target.value)} placeholder={t('bulk.group')} aria-label={t('bulk.group')} />
            <Button type="button" variant="secondary" size="sm" disabled={!!busy || !group.trim()} onClick={() => run('group', (m) => api.update(m.id, { groupName: group.trim() }))}>{t('bulk.apply')}</Button>
          </ButtonGroup>
        </span>
        <Button type="button" variant="destructive" size="sm" disabled={!!busy} onClick={del}>
          {busy === 'delete' ? <Spinner size={12} inline decorative /> : <Trash2 size={13} />} {t('bulk.delete')}
        </Button>
      </div>
      <Button type="button" variant="ghost" size="icon-sm" className="ml-auto text-muted-foreground"
        onClick={onClear} aria-label={t('bulk.clear')}>
        <X size={14} />
      </Button>
    </div>
  )
}
