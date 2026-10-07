import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Database, HardDrive, Trash2, Clock, PlayCircle, History, ShieldAlert,
  Lock, FileText, Users, Shield, FileBox, Activity, GitCompareArrows,
  DatabaseBackup, Archive,
} from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import PolicyRow, { fmtBytes, fmtNum } from './retention/PolicyRow.jsx'
import RetentionReviewModal from './retention/RetentionReviewModal.jsx'
import RetentionChangeLog from './retention/RetentionChangeLog.jsx'
import RetentionRunsPanel from './retention/RetentionRunsPanel.jsx'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { KpiCard } from './HealthUi.jsx'
import { SETTINGS_STACK, SettingsHeader, SettingsSection, ToggleRow } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/** Veri sınıfı sırası — uyum onayı gerektirenler üstte. */
const CLASSES = [
  { key: 'PERSONAL', Icon: Users },
  { key: 'SECURITY_AUDIT', Icon: Shield },
  { key: 'CONTENT', Icon: FileBox },
  { key: 'OPERATIONAL', Icon: Activity },
]

/**
 * Ayarlar → Veri Saklama. Politikalar veri sınıfına göre akordiyonlarda (varsayılan HEPSİ KAPALI);
 * her satır açılabilir; değişiklikler yapışkan bir çubukta toplanır ve kaydetmeden ÖNCE
 * "ne değişecek" özeti gösterilir. Değişiklikler politika bazında denetim kaydına yazılır.
 *
 * `readOnly` (kapsamlı müdür, globalAdmin=false; 2026-09-28): saklama BÜTÜN takımların verisini siler → sistem geneli.
 * Sunucu yazma/çalıştırma uçlarını ve saklama anahtarlarını (GLOBAL_ONLY) müdüre 403'ler; ekran her şeyi gösterir ama
 * eylemleri çizmez, girdileri kilitler (NocSettings deseni).
 */
export default function RetentionSettings({ readOnly = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm, showPrompt } = useDialog()

  const [data, setData] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [edited, setEdited] = useState({})            // settingKey → yeni değer (string)
  const [openClasses, setOpenClasses] = useState(() => new Set())   // çoklu açılabilir
  const [openRows, setOpenRows] = useState(() => new Set())
  const [openPanel, setOpenPanel] = useState(null)     // 'runs' | 'changes' | null
  const [saving, setSaving] = useState(false)
  const [busy, setBusy] = useState(null)               // 'dry' | 'run'
  const [review, setReview] = useState(null)           // gözden geçirme penceresi içeriği
  const [lastRun, setLastRun] = useState(null)
  const [runsNonce, setRunsNonce] = useState(0)   // 'Şimdi çalıştır' sonrası koşum listesi tazelensin
  const [changes, setChanges] = useState(null)

  const load = useCallback(async (estimate = true) => {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getRetentionOverview(estimate)
      if (res?.success) { setData(res.data); setEdited({}); setLoadError(null) }
      else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }, [toast, t])

  useEffect(() => { load(true) }, [load])

  // Paneller yalnız açılınca yüklenir (SystemHealth deseni). Koşum listesi kendi panelinde
  // (RetentionRunsPanel) sunucu-taraflı sayfalanır ve kendini yükler.
  useEffect(() => {
    if (openPanel === 'changes' && !changes) api.admin.getRetentionChanges(25).then(r => r?.success && setChanges(r.data)).catch(() => {})
  }, [openPanel, changes])

  const policies = data?.policies ?? []
  const holdOn = !!data?.hold_active
  const totals = data?.totals ?? {}

  const byClass = useMemo(() => {
    const m = {}
    for (const p of policies) (m[p.data_class] ||= []).push(p)
    return m
  }, [policies])

  const maxRows = useMemo(
    () => Math.max(1, ...policies.map(p => Number(p.rows) || 0)), [policies])

  const originalOf = (p) => String(p.days ?? p.default_days ?? '')
  const valueOf = (p) => (p.setting_key != null && edited[p.setting_key] != null
    ? edited[p.setting_key] : originalOf(p))

  /** Bekleyen değişiklikler — gözden geçirme penceresinin ve sayaçların kaynağı. */
  const pending = useMemo(() => {
    const out = []
    for (const p of policies) {
      if (!p.configurable || p.setting_key == null) continue
      const nv = edited[p.setting_key]
      if (nv == null || nv === '') continue
      if (String(nv) === originalOf(p)) continue
      out.push({
        id: p.id, table: p.table, dataClass: p.data_class, key: p.setting_key,
        from: Number(originalOf(p)), to: Number(nv), purgeable: p.purgeable,
      })
    }
    return out
  }, [policies, edited])

  const pendingByClass = useMemo(() => {
    const m = {}
    for (const c of pending) m[c.dataClass] = (m[c.dataClass] || 0) + 1
    return m
  }, [pending])

  const toggleClass = (k) => setOpenClasses(s => {
    const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n
  })
  const toggleRow = (id) => setOpenRows(s => {
    const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n
  })

  async function confirmSave() {
    setSaving(true)
    try {
      const values = {}
      for (const c of pending) values[c.key] = String(c.to)
      const res = await api.admin.saveRetentionSettings(values)
      setReview(null)
      if (res?.success) {
        toast.success(res.message || t('settings.saved'))
        setChanges(null)                       // geçmiş tazelensin
        load(true)
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  async function dryRun() {
    setBusy('dry')
    const res = await api.admin.retentionDryRun()
    setBusy(null)
    if (res?.success) { setLastRun(res.data); toast.success(res.message) }
    else toast.error(res?.error || t('ret.actionFailed'))
  }

  async function runNow() {
    const ok = await showConfirm({
      title: t('ret.runTitle'),
      message: t('ret.runBody', fmtNum(totals.purgeable)),
      variant: 'danger',
      confirmText: t('ret.runConfirm'),
    })
    if (!ok) return
    setBusy('run')
    const res = await api.admin.retentionRunNow()
    setBusy(null)
    if (res?.success) { setLastRun(res.data); setRunsNonce(n => n + 1); toast.success(res.message); load(true) }
    else toast.error(res?.error || t('ret.actionFailed'))
  }

  /** Saatlik özeti geriye doldurur. Ham seri hâlâ elde olduğu için tüm saklama penceresi tek
   *  seferde kurtarılabilir — kısaltmadan ÖNCE çalıştırılmalı. Hiçbir satır silmez. */
  async function backfillHourly() {
    const ok = await showConfirm({
      title: t('ret.backfillTitle'),
      message: t('ret.backfillBody'),
      confirmText: t('ret.backfillConfirm'),
    })
    if (!ok) return
    setBusy('backfill')
    const res = await api.admin.retentionBackfillHourly()
    setBusy(null)
    if (res?.success) { toast.success(res.message); load(true) }
    else toast.error(res?.error || t('ret.actionFailed'))
  }

  async function approve(p) {
    const note = await showPrompt({
      title: t('ret.approveTitle'),
      message: t('ret.approveBody', p.table),
      placeholder: t('ret.approvePlaceholder'),
    })
    if (note == null) return
    const res = await api.admin.saveRetentionApproval(p.id, note)
    if (res?.success) { toast.success(res.message); load(false) }
    else toast.error(res?.error || t('settings.saveError'))
  }

  async function toggleHold(on) {
    const res = await api.admin.saveRetentionSettings({ [data.hold_key]: on ? 'true' : 'false' })
    if (res?.success) { toast.success(res.message); load(false) }
    else toast.error(res?.error || t('settings.saveError'))
  }

  // (2026-10-07) Envanter çöp kutusu otomatik boşaltma kartı kalktı: silme kalıcı, çöp kutusu yok.

  if (!data) {
    // Yukleme BASARISIZ olduysa spinner sonsuza kadar donerdi: load() try/catch tasimadigi
    // icin ag hatasinda promise reject oluyor, hicbir durum guncellenmiyordu. Artik ayni
    // yerde hatanin KENDISI gosteriliyor (SystemHealth.jsx:163 loadErrors deseninin esdegeri).
    if (loadError) {
      return <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
    }
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  /** Açılır bölüm — ortak ui/CollapsibleSection (shadcn Collapsible). Kapalıyken içerik DOM'da YOK. */
  const section = ({ id, open, onToggle, Icon, label, extra, hint, children }) => (
    <CollapsibleSection key={id} data-section={id} open={open} onOpenChange={onToggle} icon={Icon}
      label={<>{label}{extra}</>} hint={hint} className="mb-3" contentClassName="pt-2.5"
      triggerClassName="whitespace-normal [&>span:first-of-type]:min-w-0 [&>span:first-of-type]:flex-[1_1_0%] sm:[&>span:first-of-type]:flex-initial">
      {children}
    </CollapsibleSection>
  )

  return (
    <div className={cn(SETTINGS_STACK, 'gap-4 pb-2')} data-testid="retention-settings" data-readonly={readOnly ? 'true' : 'false'}>
      {/* ── Başlık (SettingsHeader): amaç + canlı ipucu; sayılar meta çipleri. Saat dilimi de gösteriliyor:
             zone'suz bir "03:00" pod'un GMT'sinde 06:00 İstanbul demekti. ── */}
      <SettingsHeader icon={Archive} title={t('ret.title')} description={t('ret.desc')}
        hint={t('ret.liveHint', data.cleanup_zone ? `${data.cleanup_cron} · ${data.cleanup_zone}` : data.cleanup_cron)}
        meta={<>
          <Badge variant="outline" className="font-normal text-muted-foreground uppercase tracking-wider">{t('ret.eyebrow')}</Badge>
          <Badge variant="outline" className="font-normal tabular-nums">{fmtNum(totals.rows)} {t('ret.colRows')}</Badge>
          <Badge variant="outline" className="font-normal tabular-nums">{fmtBytes(totals.bytes)}</Badge>
          <Badge variant="outline" className="font-normal tabular-nums">{t('ret.kpiPolicies', totals.policies ?? 0)}</Badge>
        </>} />

      {holdOn && (
        <AlertBanner tone="danger" role="alert" icon={Lock} title={t('ret.holdActiveTitle')} className="mb-0">
          <span data-testid="ret-hold-banner">{t('ret.holdActiveBody')}</span>
        </AlertBanner>
      )}

      {readOnly && (
        <AlertBanner tone="info" icon={Lock} title={t('ret.readOnlyTitle')} className="mb-0">
          <span data-testid="ret-readonly">{t('ret.readOnlyBody')}</span>
        </AlertBanner>
      )}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <KpiCard kpiKey="rows" icon={Database} value={fmtNum(totals.rows)} label={t('ret.kpiRows')} sub={t('ret.kpiTables', totals.tables ?? 0)} mini />
        <KpiCard kpiKey="size" icon={HardDrive} value={fmtBytes(totals.bytes)} label={t('ret.kpiSize')} mini />
        <KpiCard kpiKey="purgeable" icon={Trash2} value={fmtNum(totals.purgeable)} label={t('ret.kpiPurgeable')}
          tone={totals.purgeable > 0 ? 'danger' : undefined} mini />
        <KpiCard kpiKey="lastRun" icon={Clock} value={data.last_run ? fmtNum(data.last_run.total_deleted) : '—'} label={t('ret.kpiLastRun')}
          sub={data.last_run ? formatDateSec(data.last_run.started_at) : t('ret.neverRun')}
          tone={data.last_run?.failed_count > 0 ? 'danger' : 'ok'} mini />
      </div>

      {/* ── Aksiyonlar ── (salt okunurda çizilmez: sunucu müdüre 403'ler) */}
      {!readOnly && <div className="flex flex-wrap items-center gap-2">
        <Button variant="secondary" onClick={dryRun} disabled={busy != null} aria-busy={busy === 'dry' || undefined}>
          {busy === 'dry' ? <Spinner size={15} inline decorative /> : <PlayCircle size={15} />}
          {t('ret.dryRun')}
        </Button>
        <SimpleTooltip content={t('ret.backfillHint')}>
          <Button variant="secondary" onClick={backfillHourly} disabled={busy != null} aria-busy={busy === 'backfill' || undefined}>
            {busy === 'backfill' ? <Spinner size={15} inline decorative /> : <DatabaseBackup size={15} />}
            {t('ret.backfill')}
          </Button>
        </SimpleTooltip>
        {/* Devre dışı düğme ipucu almaz → span tetik; kilidin nedeni zaten üstteki hold şeridinde görünür metin */}
        <SimpleTooltip content={holdOn ? t('ret.holdBlocks') : null}>
          <span className="inline-flex">
            <Button variant="destructive" onClick={runNow} disabled={busy != null || holdOn} aria-busy={busy === 'run' || undefined}>
              {busy === 'run' ? <Spinner size={15} inline decorative /> : <Trash2 size={15} />}
              {t('ret.runNow')}
            </Button>
          </span>
        </SimpleTooltip>
      </div>}

      {lastRun && (
        <AlertBanner tone={lastRun.failed_count > 0 ? 'danger' : 'success'} className="mb-0">
          {lastRun.dry_run ? t('ret.dryRunResult', fmtNum(lastRun.total_rows))
                           : t('ret.runResult', fmtNum(lastRun.total_rows), lastRun.duration_ms)}
        </AlertBanner>
      )}

      {/* ── Veri sınıfı akordiyonları (varsayılan hepsi kapalı, çoklu açılabilir) ── */}
      <div>
        {CLASSES.map(({ key, Icon }) => {
          const list = byClass[key] || []
          if (!list.length) return null
          const open = openClasses.has(key)
          const rows = list.reduce((s, p) => s + (Number(p.rows) || 0), 0)
          const bytes = list.reduce((s, p) => s + (Number(p.bytes) || 0), 0)
          const dirty = pendingByClass[key] || 0
          return section({
            id: key, open, onToggle: () => toggleClass(key), Icon, label: t(`ret.class.${key}`),
            extra: (
              <>
                <Badge variant="secondary" className="ml-2 rounded-full align-middle tabular-nums">{list.length}</Badge>
                {dirty > 0 && <Badge variant="warning" data-dirty={dirty} className="ml-2 rounded-full align-middle font-extrabold">{t('ret.dirtyBadge', dirty)}</Badge>}
              </>
            ),
            hint: `${fmtNum(rows)} ${t('ret.colRows').toLocaleLowerCase('tr')} · ${fmtBytes(bytes)}`,
            children: (
              <div className="pb-1">
                <p className="mx-0.5 mb-2.5 text-[12.5px] leading-normal text-muted-foreground">{t(`ret.classDesc.${key}`)}</p>
                {list.map(p => (
                  <PolicyRow key={p.id} policy={p} maxRows={maxRows}
                    value={valueOf(p)} original={originalOf(p)}
                    expanded={openRows.has(p.id)} onToggle={() => toggleRow(p.id)}
                    onChange={(v) => setEdited(e => ({ ...e, [p.setting_key]: v }))}
                    approval={data.approvals?.[p.id]} onApprove={() => approve(p)}
                    disabled={saving || readOnly} />
                ))}
              </div>
            ),
          })
        })}

        {/* ── Çalışma geçmişi ── */}
        {section({
          id: 'runs', open: openPanel === 'runs', onToggle: () => setOpenPanel(v => v === 'runs' ? null : 'runs'),
          Icon: History, label: t('ret.historyTitle'),
          hint: data.last_run ? formatDateSec(data.last_run.started_at) : t('ret.neverRun'),
          children: <RetentionRunsPanel policies={policies} holdOn={holdOn} refreshKey={runsNonce} />,
        })}

        {/* ── Değişiklik geçmişi ── */}
        {section({
          id: 'changes', open: openPanel === 'changes', onToggle: () => setOpenPanel(v => v === 'changes' ? null : 'changes'),
          Icon: GitCompareArrows, label: t('ret.changesTitle'), hint: t('ret.changesHint'),
          children: <RetentionChangeLog rows={changes} />,
        })}
      </div>

      {/* ── Legal hold ── */}
      <SettingsSection title={<span className="inline-flex items-center gap-2"><ShieldAlert size={15} aria-hidden="true" /> {t('ret.holdTitle')}</span>}
        description={t('ret.holdDesc')} contentClassName="flex flex-col gap-2">
        <ToggleRow major checked={holdOn} onChange={toggleHold} label={t('ret.holdToggle')} disabled={readOnly}
          helpKey="help.set.site.monitor.retention.hold-enabled" />
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><FileText size={12} aria-hidden="true" /> {t('ret.docHint')}</p>
      </SettingsSection>

      {/* ── Yapışkan gözden geçir/kaydet çubuğu ── (telefonda güvenli alan payı) */}
      {pending.length > 0 && (
        <div data-slot="retention-sticky-bar"
          className="sticky bottom-0 z-[60] mt-2 flex flex-wrap items-center gap-2.5 rounded-xl border border-primary bg-card px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] shadow-[0_-4px_18px_rgba(0,0,0,.10)]">
          <span className="mr-auto inline-flex items-center gap-2 text-[13px] font-bold">
            <span aria-hidden="true" className="size-2.5 rounded-full bg-amber-500 ring-3 ring-amber-500/25" />
            {t('ret.pendingCount', pending.length)}
          </span>
          <Button variant="secondary" onClick={() => setEdited({})}>{t('ret.discard')}</Button>
          <Button onClick={() => setReview(pending)}>{t('ret.reviewOpen')}</Button>
        </div>
      )}

      {review && (
        <RetentionReviewModal changes={review} saving={saving}
          onCancel={() => setReview(null)} onConfirm={confirmSave} />
      )}
    </div>
  )
}
