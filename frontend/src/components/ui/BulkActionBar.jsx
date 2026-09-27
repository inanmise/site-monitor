import { useId, useState } from 'react'
import { Pause, Play, Users, FolderInput, Trash2, X, CheckSquare, Square, Headset, BellRing, BellOff } from 'lucide-react'
import { api as client } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from './Toast.jsx'
import { useDialog } from './Dialog.jsx'
import { Spinner } from './Progress.jsx'
import SearchableSelect from './SearchableSelect.jsx'
import { Button } from '@/components/shadcn/button'
import { ButtonGroup } from '@/components/shadcn/button-group'
import { Input } from '@/components/shadcn/input'
import { unwrap } from '../noc/nocModel.js'
import { bulkToast, chunk, mergeBulkResults } from '../noc/forms/nocFormModel.js'

/**
 * Toplu işlem çubuğu (2026-09-12, zenginleştirme #13) — izleme sayfalarında çoklu seçim:
 * duraklat / sürdür / takım değiştir / grup ata / sil. Sunucuya YENİ uç eklenmedi: her satır için
 * mevcut PUT (kısmi gövde: {active} | {teamId} | {groupName}) ya da DELETE çağrılır — yetki, denetim
 * kaydı ve alarm kapatma (closeAlertsOnPause) tek tek işlemle birebir aynı yoldan geçer.
 *
 * props: selected (Set<id>), items (görünür liste), onClear, onDone(reload), api: { update(id, body), remove(id) },
 *        teams [{id,name}], canDelete(m) → bool, label (tür adı)
 *
 * `nocType` (2026-09-27, isteğe bağlı): 7/24 izleme ekibi tür anahtarı (PING, HTTP, …). Verilirse çubukta
 * "7/24'e bildir: Aç / Kapat" grubu çıkar → TEK istek `POST /api/noc/monitors/bulk` ({ items: [{type,id}], enabled }).
 * Yetki ve "zaten öyle" elemesi sunucuda; sonuç bildirimi güncellenen sayıyı ve atlananları NEDENLERİYLE söyler.
 * Verilmezse çubuk bugünküyle birebir aynı (grup hiç çizilmez).
 */
export default function BulkActionBar({ selected, items, onClear, onDone, api, teams = [], canDelete = () => false, onToggleAll, nocType }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [busy, setBusy] = useState(null)   // 'pause' | 'resume' | 'team' | 'group' | 'delete'
  const [teamId, setTeamId] = useState('')
  const [group, setGroup] = useState('')
  const nocLabelId = useId()
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

  /** 7/24 toplu aç/kapat — seçili satırların hepsi gönderilir; sunucu yanıtı (updated / skipped) bildirimin kaynağı. */
  async function noc(enabled) {
    if (chosen.length === 0) { toast.error(t('bulk.none')); return }
    setBusy(enabled ? 'noc-on' : 'noc-off')
    const results = []
    let failed = false
    for (const part of chunk(chosen.map((m) => ({ type: nocType, id: m.id })))) {
      try {
        const r = unwrap(await client.noc.bulk(part, enabled))
        if (r.ok && r.data) results.push(r.data); else failed = true
      } catch { failed = true }
    }
    setBusy(null)
    if (failed && results.length === 0) { toast.error(t('nocf.bulkError')); return }
    const { tone, text } = bulkToast(mergeBulkResults(results), enabled, t, failed)
    toast[tone](text)
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
    // Katman (2026-09-27, Playwright 390 ölçümü): kartların örtü üstü bölgeleri `relative z-10` (CARD_LAYER) — z-[6]'daki
    // yapışkan çubuk kaydırınca kart içeriğinin ALTINDA kalıyordu → z-20. Telefonda (<md) üstteki MobileTopBar (sticky, h-14,
    // z-30) çubuğun ilk satırını örtüyordu → top-16; masaüstünde eskisi gibi top-2.
    <div data-slot="bulk-action-bar" role="region" aria-label={t('bulk.aria')}
      className="sticky top-16 z-20 mb-2.5 flex flex-wrap items-center gap-2.5 rounded-[10px] border border-primary bg-card px-3 py-2 shadow-lg md:top-2">
      <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground pointer-coarse:size-10"
        onClick={onToggleAll} title={selectAllLabel} aria-label={selectAllLabel}>
        {allVisibleSelected ? <CheckSquare size={15} /> : <Square size={15} />}
      </Button>
      <span className="font-bold">{t('bulk.selected', ids.length)}</span>
      <div className="flex flex-wrap items-center gap-1.5">
        {/* Duraklat / Sürdür birbirinin karşıtı — tek bir düğme grubu. */}
        <ButtonGroup>
          <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" disabled={!!busy} onClick={() => run('pause', (m) => api.update(m.id, { active: false }), (m) => m.active !== false)}>
            {busy === 'pause' ? <Spinner size={12} inline decorative /> : <Pause size={13} />} {t('bulk.pause')}
          </Button>
          <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" disabled={!!busy} onClick={() => run('resume', (m) => api.update(m.id, { active: true }), (m) => m.active === false)}>
            {busy === 'resume' ? <Spinner size={12} inline decorative /> : <Play size={13} />} {t('bulk.resume')}
          </Button>
        </ButtonGroup>
        {nocType && (
          // 7/24 izleme ekibi (2026-09-27): açık/kapalı birbirinin karşıtı — tek düğme grubu, önünde grup etiketi.
          <span className={fieldCls} data-slot="bulk-noc">
            <Headset size={13} aria-hidden="true" />
            <span id={nocLabelId} className="text-xs font-semibold text-foreground">{t('nocf.bulkLabel')}</span>
            <ButtonGroup aria-labelledby={nocLabelId}>
              <Button type="button" variant="secondary" size="sm" disabled={!!busy} aria-busy={busy === 'noc-on' || undefined}
                className="pointer-coarse:h-10" aria-label={t('nocf.bulkOnAria')} onClick={() => noc(true)}>
                {busy === 'noc-on' ? <Spinner size={12} inline decorative /> : <BellRing size={13} />} {t('nocf.bulkOn')}
              </Button>
              <Button type="button" variant="secondary" size="sm" disabled={!!busy} aria-busy={busy === 'noc-off' || undefined}
                className="pointer-coarse:h-10" aria-label={t('nocf.bulkOffAria')} onClick={() => noc(false)}>
                {busy === 'noc-off' ? <Spinner size={12} inline decorative /> : <BellOff size={13} />} {t('nocf.bulkOff')}
              </Button>
            </ButtonGroup>
          </span>
        )}
        {teams.length > 0 && (
          <span className={fieldCls}>
            <Users size={13} aria-hidden="true" />
            <span className="min-w-[140px]">
              <SearchableSelect value={teamId} onChange={setTeamId} ariaLabel={t('bulk.team')} placeholder={t('bulk.team')}
                options={teams.map((tm) => ({ value: String(tm.id), label: tm.name }))} />
            </span>
            <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" disabled={!!busy || !teamId} onClick={() => run('team', (m) => api.update(m.id, { teamId: Number(teamId) }))}>{t('bulk.apply')}</Button>
          </span>
        )}
        <span className={fieldCls}>
          <FolderInput size={13} aria-hidden="true" />
          <ButtonGroup>
            <Input className="h-8 min-w-[140px] text-foreground pointer-coarse:h-10" value={group} onChange={(e) => setGroup(e.target.value)} placeholder={t('bulk.group')} aria-label={t('bulk.group')} />
            <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" disabled={!!busy || !group.trim()} onClick={() => run('group', (m) => api.update(m.id, { groupName: group.trim() }))}>{t('bulk.apply')}</Button>
          </ButtonGroup>
        </span>
        <Button type="button" variant="destructive" size="sm" className="pointer-coarse:h-10" disabled={!!busy} onClick={del}>
          {busy === 'delete' ? <Spinner size={12} inline decorative /> : <Trash2 size={13} />} {t('bulk.delete')}
        </Button>
      </div>
      <Button type="button" variant="ghost" size="icon-sm" className="ml-auto text-muted-foreground pointer-coarse:size-10"
        onClick={onClear} aria-label={t('bulk.clear')}>
        <X size={14} />
      </Button>
    </div>
  )
}
