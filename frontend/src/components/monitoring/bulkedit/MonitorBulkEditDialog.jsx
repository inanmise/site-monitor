import { useEffect, useMemo, useState } from 'react'
import { SlidersHorizontal } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api } from '../../../api/client'
import BulkPreviewDialog from '../../admin/bulk/BulkPreviewDialog.jsx'
import { summarizeResult } from '../../admin/bulk/bulkModel.js'
import Field from '../../ui/Field.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import TagInput from '../../ui/TagInput.jsx'
import NotificationGroupSelect from '../../ui/NotificationGroupSelect.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { durationText } from '../../dbquery/scheduleModel.js'
import {
  ACTIONS, EMPTY_FORM, LEVELS, MAX_ITEMS, buildBody, commonTeam, formProblem, intervalOptions, isUnsupportedAnswer,
  planRows, tagsOf,
} from './monitorBulkEditModel.js'

/**
 * İzleme sayfası GENİŞLETİLMİŞ toplu düzenleme penceresi (2026-11-19) — kontrol aralığı, alarm seviyesi, bildirim kanalları
 * + grup, etiketler. Önizleme istemcide (`planRows`), uygulama TEK istek (`POST /api/monitoring/bulk-edit`), sonuç satır satır
 * (`BulkPreviewDialog`). Tembel yüklenir (BulkActionBar `lazy`).
 *
 * props: open, onClose, type (sunucu türü: http …), rows (görünür seçili liste satırları), teams [{id, name}], onFinished(result).
 * Test kancaları: `data-slot="mbe-action|mbe-interval|mbe-level|mbe-email|mbe-webhook|mbe-group-mode|mbe-need|mbe-cap|
 * mbe-server-error|mbe-row-change"`.
 */
const CRON_TYPES = new Set(['scripted', 'pagespeed', 'dbquery'])

export default function MonitorBulkEditDialog({ open, onClose, type, rows = [], teams = [], onFinished }) {
  const t = useT()
  const [action, setAction] = useState(EMPTY_FORM.action)
  const [intervalSec, setIntervalSec] = useState('')
  const [level, setLevel] = useState('')
  const [email, setEmail] = useState('keep')
  const [webhook, setWebhook] = useState('keep')
  const [groupMode, setGroupMode] = useState('keep')
  const [groupId, setGroupId] = useState('')
  const [addCsv, setAddCsv] = useState('')
  const [removeCsv, setRemoveCsv] = useState('')
  const [serverError, setServerError] = useState(null)

  // Her açılış temiz form (önceki seçim / hata kalmaz)
  useEffect(() => {
    if (!open) return
    setAction(EMPTY_FORM.action); setIntervalSec(''); setLevel(''); setEmail('keep'); setWebhook('keep')
    setGroupMode('keep'); setGroupId(''); setAddCsv(''); setRemoveCsv(''); setServerError(null)
  }, [open])

  const used = useMemo(() => rows.slice(0, MAX_ITEMS), [rows])
  const capped = rows.length > MAX_ITEMS
  const team = useMemo(() => commonTeam(used), [used])

  const form = useMemo(() => ({
    action, interval: intervalSec, level, email, webhook, groupMode, groupId, groupTeamId: team,
    tagsAdd: tagsOf(addCsv), tagsRemove: tagsOf(removeCsv),
  }), [action, intervalSec, level, email, webhook, groupMode, groupId, team, addCsv, removeCsv])

  const problem = formProblem(type, form)
  const plan = useMemo(() => (problem ? null : planRows(used, form)), [problem, used, form])

  // Takım adları: sayfanın takım listesi + satırın kendi takım adı (yazılabilir takım listesinde olmayan görünür takım)
  const teamNames = useMemo(() => {
    const m = new Map()
    for (const tm of teams) if (tm?.id != null) m.set(String(tm.id), tm.name)
    for (const r of used) if (r.team_id != null && r.team_name && !m.has(String(r.team_id))) m.set(String(r.team_id), r.team_name)
    return m
  }, [teams, used])
  const teamNameOf = (id) => (id == null ? null : teamNames.get(String(id)) ?? null)

  // Seçili izlemelerin mevcut etiketleri (çıkarma / ekleme önerisi)
  const tagPool = useMemo(() => {
    const seen = new Map()
    for (const r of used) for (const tg of tagsOf(r.tags)) { const k = tg.toLocaleLowerCase('en'); if (!seen.has(k)) seen.set(k, tg) }
    return [...seen.values()].sort((a, b) => a.localeCompare(b, 'tr'))
  }, [used])

  const levelText = (l) => t(`notify.level.${l}`)
  const triOptions = [
    { value: 'keep', label: t('mbe.keep') }, { value: 'on', label: t('mbe.on') }, { value: 'off', label: t('mbe.off') },
  ]

  const extraRowInfo = (r) => {
    if (r.state !== 'change') return null
    let text = null
    if (action === 'interval') text = `${durationText(r.from, t)} → ${durationText(r.to, t)}`
    else if (action === 'alert_level') text = `${levelText(r.from)} → ${levelText(r.to)}`
    else if (action === 'channels') text = r.parts.map((p) => t(`mbe.part.${p}`)).join(' · ')
    else if (action === 'tags') text = `${r.from.join(', ') || '—'} → ${r.to.join(', ')}`
    return text && (
      <span data-slot="mbe-row-change" className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{text}</span>
    )
  }

  async function confirm() {
    setServerError(null)
    const res = await api.monitoring.bulkEdit(buildBody(type, used.map((m) => m.id), form))
    if (res?.success) {
      const r = summarizeResult(res.data)
      if (r) return r
      throw new Error(t('adm.bulk.requestFailed'))
    }
    if (isUnsupportedAnswer(res)) { setServerError(t('mbe.unsupported')); return false }
    if (res?.field || res?.errorInfo?.field) { setServerError(res?.error || t('adm.bulk.requestFailed')); return false }
    throw new Error(res?.error || t('adm.bulk.requestFailed'))
  }

  const actionOptions = ACTIONS.map((a) => ({ value: a, label: t(`mbe.action.${a}`) }))

  return (
    <BulkPreviewDialog open={open} onClose={onClose} title={t('mbe.title')} icon={SlidersHorizontal}
      description={t('mbe.desc', used.length)} plan={plan} confirmLabel={t('mbe.confirm')} confirmDisabled={!!problem}
      onConfirm={confirm} onFinished={onFinished} teamNameOf={teamNameOf} extraRowInfo={extraRowInfo}>
      <div className="flex min-w-0 flex-col gap-1">
        {capped && (
          <AlertBanner tone="info" className="mb-0">
            <span data-slot="mbe-cap">{t('mbe.capNote', rows.length, MAX_ITEMS)}</span>
          </AlertBanner>
        )}
        <Field label={t('mbe.actionLabel')} className="mb-2">
          {() => (
            <div data-slot="mbe-action">
              <SegmentedControl value={action} onChange={(v) => { setAction(v); setServerError(null) }} options={actionOptions}
                ariaLabel={t('mbe.actionLabel')} className="flex w-full flex-wrap"
                itemClassName="h-auto min-h-7 pointer-coarse:h-auto pointer-coarse:min-h-10 flex-[1_1_calc(50%-4px)] whitespace-normal py-1 text-center sm:flex-1" />
            </div>
          )}
        </Field>

        {action === 'interval' && (
          <Field label={t('mbe.intervalLabel')} hint={CRON_TYPES.has(type) ? t('mbe.intervalCronHint') : null} className="mb-1">
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} data-slot="mbe-interval" value={intervalSec}
                onChange={(e) => setIntervalSec(e.target.value)} className="w-full min-w-48">
                <NativeSelectOption value="" disabled>{t('mbe.intervalPick')}</NativeSelectOption>
                {intervalOptions(type).map((s) => (
                  <NativeSelectOption key={s} value={String(s)}>{durationText(s, t)}</NativeSelectOption>
                ))}
              </NativeSelect>
            )}
          </Field>
        )}

        {action === 'alert_level' && (
          <Field label={t('mbe.levelLabel')} hint={t('mbe.levelHint')} className="mb-1">
            {() => (
              <div data-slot="mbe-level">
                <SegmentedControl value={level} onChange={setLevel} ariaLabel={t('mbe.levelLabel')}
                  options={LEVELS.map((l) => ({ value: l, label: levelText(l) }))} className="flex w-full flex-wrap"
                  itemClassName="flex-1" />
              </div>
            )}
          </Field>
        )}

        {action === 'channels' && (
          <div className="flex min-w-0 flex-col gap-1">
            <Field label={t('mbe.emailLabel')} className="mb-2">
              {() => (
                <div data-slot="mbe-email">
                  <SegmentedControl value={email} onChange={setEmail} options={triOptions} ariaLabel={t('mbe.emailLabel')}
                    className="flex w-full flex-wrap" itemClassName="flex-1" />
                </div>
              )}
            </Field>
            <Field label={t('mbe.webhookLabel')} className="mb-2">
              {() => (
                <div data-slot="mbe-webhook">
                  <SegmentedControl value={webhook} onChange={setWebhook} options={triOptions} ariaLabel={t('mbe.webhookLabel')}
                    className="flex w-full flex-wrap" itemClassName="flex-1" />
                </div>
              )}
            </Field>
            <Field label={t('mbe.groupLabel')} hint={team == null ? t('mbe.groupOneTeam') : t('mbe.groupHint')} className="mb-1">
              {() => (
                <div data-slot="mbe-group-mode" className="flex min-w-0 flex-col gap-2">
                  <SegmentedControl value={groupMode} onChange={(v) => { setGroupMode(v); if (v !== 'set') setGroupId('') }}
                    ariaLabel={t('mbe.groupLabel')} className="flex w-full flex-wrap"
                    itemClassName="h-auto min-h-7 pointer-coarse:h-auto pointer-coarse:min-h-10 flex-1 whitespace-normal py-1 text-center"
                    options={[
                      { value: 'keep', label: t('mbe.keep') },
                      { value: 'default', label: t('mbe.groupDefault') },
                      { value: 'set', label: t('mbe.groupSet'), disabled: team == null, title: team == null ? t('mbe.groupOneTeam') : undefined },
                    ]} />
                  {groupMode === 'set' && team != null && (
                    <div className="min-w-0" data-slot="mbe-group-select">
                      <NotificationGroupSelect teamId={team} value={groupId} onChange={(v) => setGroupId(v ?? '')} />
                    </div>
                  )}
                </div>
              )}
            </Field>
          </div>
        )}

        {action === 'tags' && (
          <div className="flex min-w-0 flex-col gap-2" data-slot="mbe-tags">
            <TagInput label={t('mbe.tagsAdd')} value={addCsv} onChange={setAddCsv} placeholder={t('mbe.tagsPlaceholder')}
              suggestions={tagPool} suggestLabel={t('mbe.tagsAdd')} />
            <TagInput label={t('mbe.tagsRemove')} value={removeCsv} onChange={setRemoveCsv} placeholder={t('mbe.tagsPlaceholder')}
              suggestions={tagPool} suggestLabel={t('mbe.tagsRemove')} />
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('mbe.tagsHint')}</p>
          </div>
        )}

        {problem && (
          <p data-slot="mbe-need" data-need={problem} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {t(`mbe.need.${problem}`)}
          </p>
        )}
        {serverError && (
          <AlertBanner tone="danger" role="alert" className="mb-0">
            <span data-slot="mbe-server-error">{serverError}</span>
          </AlertBanner>
        )}
      </div>
    </BulkPreviewDialog>
  )
}
