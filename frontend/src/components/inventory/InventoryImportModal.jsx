import { useMemo, useState } from 'react'
import { Upload, FileSpreadsheet, Download } from 'lucide-react'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { useT } from '../../i18n/index.jsx'
import { api } from '../../api/client'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { parseCsv, mapCsv, importTemplateCsv, IMPORT_COLUMNS } from './inventoryModel.js'
import { Button } from '@/components/shadcn/button'

/**
 * CSV içe aktarma (2026-09-12, #6): dosya ya da yapıştırılan metin → istemcide ayrıştır → sunucuda KURU
 * koşu (ne olacak) → onayla işle. Yerelleştirilmiş dışa aktarma başlıkları da tanınır (dışa aktar → düzelt →
 * içe aktar döngüsü). Sunucu satır başına kapsam uygular; sonuç satır satır listelenir.
 */
const ACTION_LABEL = { create: 'inv.importCreate', update: 'inv.importUpdate', skip: 'inv.importSkip', error: 'inv.importErr' }

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
      <p className="field-hint">{t('inv.importHint')}</p>
      <div className="inv-import-src">
        <Button asChild variant="secondary" size="sm">
          <label>
            <FileSpreadsheet size={13} /> {t('inv.importFile')}
            <input type="file" accept=".csv,text/csv,text/plain" onChange={onFile} style={{ display: 'none' }} />
          </label>
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={downloadTemplate}><Download size={13} /> {t('inv.importTemplate')}</Button>
        <span className="inv-import-cols" title={IMPORT_COLUMNS.join(', ')}>{t('inv.importCols', IMPORT_COLUMNS.length)}</span>
      </div>
      <textarea className="input inv-import-text" rows={6} value={text} placeholder={t('inv.importPaste')}
        onChange={(e) => { setText(e.target.value); setPlan(null); setResult(null) }} spellCheck={false} />
      {parsed.unknown.length > 0 && <AlertBanner tone="warning">{t('inv.importUnknownCols', parsed.unknown.join(', '))}</AlertBanner>}
      {text && parsed.rows.length === 0 && <AlertBanner tone="warning">{t('inv.importNoRows')}</AlertBanner>}
      {error && <AlertBanner tone="danger">{error}</AlertBanner>}
      {summary && (
        <div className="inv-import-result">
          <div className="inv-import-sum">
            <span className="badge badge-ok">{t('inv.importCreate')}: {summary.created}</span>
            <span className="badge badge-warn">{t('inv.importUpdate')}: {summary.updated}</span>
            <span className="badge">{t('inv.importSkip')}: {summary.skipped}</span>
            <span className="badge badge-err">{t('inv.importErr')}: {summary.errors}</span>
            <span className="inv-import-mode">{result ? t('inv.importDone') : t('inv.importDryRunDone')}</span>
          </div>
          <div className="admin-table-wrap inv-import-rows">
            <table className="admin-table">
              <thead><tr><th>#</th><th>{t('inv.colDomain')}</th><th>{t('inv.importAction')}</th><th>{t('inv.importDetail')}</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.line} className={`inv-import-row--${r.action}`}>
                    <td>{r.line}</td><td>{r.domain}</td>
                    <td><span className={`badge ${r.action === 'create' ? 'badge-ok' : r.action === 'update' ? 'badge-warn' : r.action === 'error' ? 'badge-err' : ''}`}>{t(ACTION_LABEL[r.action] || 'inv.importSkip')}</span></td>
                    <td className="inv-dim">{reasonText(r)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </ModalShell>
  )
}
