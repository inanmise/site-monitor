import { useMemo, useState } from 'react'
import { Upload, FileSpreadsheet, Download } from 'lucide-react'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { useT } from '../../i18n/index.jsx'
import { api } from '../../api/client'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { parseCsv, mapCsv, importTemplateCsv, IMPORT_COLUMNS } from './inventoryModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * CSV içe aktarma (2026-09-12, #6): dosya ya da yapıştırılan metin → istemcide ayrıştır → sunucuda KURU
 * koşu (ne olacak) → onayla işle. Yerelleştirilmiş dışa aktarma başlıkları da tanınır (dışa aktar → düzelt →
 * içe aktar döngüsü). Sunucu satır başına kapsam uygular; sonuç satır satır listelenir.
 */
const ACTION_LABEL = { create: 'inv.importCreate', update: 'inv.importUpdate', skip: 'inv.importSkip', error: 'inv.importErr' }
/** Eylem rozeti tonu (eski .badge-ok/-warn/-err) — shadcn Badge. */
const ACTION_TONE = {
  create: 'bg-success/15 text-success dark:bg-success/20',
  update: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  error: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  skip: '',
}

export default function InventoryImportModal({ onClose, onDone }) {
  const t = useT()
  const [text, setText] = useState('')
  const [plan, setPlan] = useState(null)      // dry-run sonucu
  const [result, setResult] = useState(null)  // commit sonucu
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  // Dışa aktarma başlıkları (arayüz dilinde) → anahtar
  const labels = useMemo(() => ({
    [t('inv.formDomain')]: 'domain', [t('inv.formPort')]: 'port', [t('inv.formTeam')]: 'team', [t('inv.formTier')]: 'tier',
    [t('inv.formPurchasedBy')]: 'purchased_by', [t('inv.formPlatform')]: 'platform', [t('inv.formPlatformDetail')]: 'platform_detail', [t('inv.formSvcMgmt')]: 'svc_mgmt_contact', [t('inv.formAppDev')]: 'app_dev_contact',
    [t('inv.formIisAdmin')]: 'iis_admin_contact', [t('inv.formWafAdmin')]: 'waf_admin_contact', [t('inv.colActive')]: 'active',
    [t('inv.formChangeDesc')]: 'change_description', [t('inv.colUgTeam')]: 'ug_team', [t('inv.colGroup')]: 'group', [t('inv.colTags')]: 'tags',
    ...Object.fromEntries(INVENTORY_FLAGS.map(({ key, labelKey }) => [t(labelKey), key])),
    [t('nocf.csvNotify')]: 'noc_notify', [t('nocf.csvGroups')]: 'noc_groups',   // 7/24 dışa aktarma başlıkları (2026-09-27)
  }), [t])

  const parsed = useMemo(() => { try { return mapCsv(parseCsv(text), labels) } catch { return { rows: [], unknown: [], columns: [] } } }, [text, labels])

  async function onFile(e) {
    const f = e.target.files?.[0]; if (!f) return
    setText(await f.text()); setPlan(null); setResult(null); setError(null)
  }
  async function run(dryRun) {
    setBusy(true); setError(null)
    try {
      const r = await api.admin.importInventory(parsed.rows, dryRun)
      if (!r?.success) { setError(r?.error || t('inv.importError')); return }
      if (dryRun) setPlan(r.data)
      else { setResult(r.data); onDone?.(r.data) }
    } catch (e) { setError(e?.message || t('inv.importError')) }
    finally { setBusy(false) }
  }
  function downloadTemplate() {
    try {
      const blob = new Blob(['\uFEFF' + importTemplateCsv()], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = 'site-monitor-inventory-template.csv'; document.body.appendChild(a); a.click(); a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
  }

  const rows = result?.rows || plan?.rows || []
  const summary = result || plan
  const reasonText = (r) => r.reason ? t(`inv.importReason.${r.reason}`) : (r.changes?.length ? r.changes.join(', ') : '')

  return (
    <ModalShell open onClose={onClose} title={t('inv.importTitle')} icon={Upload} size="lg"
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>{result ? t('app.close') : t('inv.cancel')}</Button>
          {!result && <Button type="button" variant="secondary" disabled={busy || parsed.rows.length === 0} onClick={() => run(true)}>{t('inv.importPreview')}</Button>}
          {!result && <Button type="button" disabled={busy || !plan || (plan.created + plan.updated) === 0} onClick={() => run(false)} title={!plan ? t('inv.importPreviewFirst') : undefined}>
            {busy ? t('inv.saving') : t('inv.importCommit', plan ? plan.created + plan.updated : 0)}
          </Button>}
        </>
      }>
      <p className="mb-3 text-[.85em] text-muted-foreground">{t('inv.importHint')}</p>
      <div className="mb-2.5 flex flex-wrap items-center gap-2">
        {/* Dosya seçici: shadcn Input type="file" (etiketli); içerik metin kutusuna okunur */}
        <label className="inline-flex max-w-full items-center gap-1.5 text-[.88em] font-semibold">
          <FileSpreadsheet size={13} aria-hidden="true" />
          <span className="sr-only sm:not-sr-only">{t('inv.importFile')}</span>
          <Input type="file" accept=".csv,text/csv,text/plain" onChange={onFile} aria-label={t('inv.importFile')}
            className="h-8 w-auto max-w-full cursor-pointer py-1 text-[.95em] font-normal" />
        </label>
        <Button type="button" variant="secondary" size="sm" onClick={downloadTemplate}><Download size={13} /> {t('inv.importTemplate')}</Button>
        <span className="text-[.82em] text-muted-foreground" title={IMPORT_COLUMNS.join(', ')}>{t('inv.importCols', IMPORT_COLUMNS.length)}</span>
      </div>
      <Textarea rows={6} value={text} placeholder={t('inv.importPaste')} aria-label={t('inv.importPaste')}
        className="mb-2.5 min-h-32 font-mono md:text-[.85em]"
        onChange={(e) => { setText(e.target.value); setPlan(null); setResult(null) }} spellCheck={false} />
      {parsed.unknown.length > 0 && <AlertBanner tone="warning">{t('inv.importUnknownCols', parsed.unknown.join(', '))}</AlertBanner>}
      {text && parsed.rows.length === 0 && <AlertBanner tone="warning">{t('inv.importNoRows')}</AlertBanner>}
      {error && <AlertBanner tone="danger">{error}</AlertBanner>}
      {summary && (
        <div data-slot="inv-import-result" className="mt-1">
          <div className="mb-2 flex flex-wrap items-center gap-1.5">
            <Badge variant="secondary" className={ACTION_TONE.create}>{t('inv.importCreate')}: {summary.created}</Badge>
            <Badge variant="secondary" className={ACTION_TONE.update}>{t('inv.importUpdate')}: {summary.updated}</Badge>
            <Badge variant="secondary">{t('inv.importSkip')}: {summary.skipped}</Badge>
            <Badge variant="secondary" className={ACTION_TONE.error}>{t('inv.importErr')}: {summary.errors}</Badge>
            <span className="text-[.85em] font-semibold text-muted-foreground">{result ? t('inv.importDone') : t('inv.importDryRunDone')}</span>
          </div>
          <div className="max-h-[40vh] overflow-y-auto rounded-[10px] border">
            <Table className="text-[.9em]">
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="bg-muted/60 text-muted-foreground">#</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('inv.colDomain')}</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('inv.importAction')}</TableHead>
                  <TableHead className="bg-muted/60 text-muted-foreground">{t('inv.importDetail')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.line} data-action={r.action}>
                    <TableCell className="tabular-nums">{r.line}</TableCell>
                    <TableCell className="whitespace-normal break-all">{r.domain}</TableCell>
                    <TableCell><Badge variant="secondary" className={cn(ACTION_TONE[r.action])}>{t(ACTION_LABEL[r.action] || 'inv.importSkip')}</Badge></TableCell>
                    <TableCell className="text-[.88em] whitespace-normal text-muted-foreground">
                      {reasonText(r)}
                      {/* Mevcut kayda çarpan satır (başka ekip / çöp kutusu, 2026-09-28): kaydın SAHİBİ ekip — rozet üyeleri açar */}
                      {r.team_name && (
                        <span data-slot="inv-import-owner" className="mt-0.5 flex min-w-0 flex-wrap items-center gap-1">
                          <span>{t('dupx.owner')}:</span>
                          <TeamBadge teamId={r.team_id} teamName={r.team_name} />
                        </span>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      )}
    </ModalShell>
  )
}
