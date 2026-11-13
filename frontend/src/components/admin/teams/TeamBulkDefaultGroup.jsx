import { useEffect, useMemo, useState } from 'react'
import { Star, CircleAlert } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import BulkPreviewDialog from '../bulk/BulkPreviewDialog.jsx'
import { defaultGroupPlan, defaultGroupRequests, deliverableCount, summarizeResult } from '../bulk/bulkModel.js'

/**
 * Takımlar → toplu düzeltme: "varsayılan bildirim grubu ata" (2026-11-13, yönetim zenginleştirmesi (h)).
 *
 * Bir takımın varsayılan grubu O TAKIMA ait olmalı (sunucu `overrideFor` sahiplik denetimi) — bu yüzden "tek grup herkese"
 * değil, her takım için KENDİ grupları arasından seçim: kullanılabilir mevcut varsayılan seçili gelir (değişiklik yok),
 * tek adaylı takımda aday önceden seçilir, çok adaylıda kullanıcı seçer. Grubu olmayan takıma grup oluşturma kısayolu
 * (Bildirim Grupları sekmesi, takım süzülü). Gönderim `POST /notification-groups/bulk` `make_default` — yalnız DEĞİŞEN
 * takımlar; sonuç satır satır. Gruplar okunamazsa "okunamadı + yeniden dene" — "grup yok" denmez.
 */
export default function TeamBulkDefaultGroup({ open, onClose, teams = [], onDone, onCreateGroup }) {
  const t = useT()
  const [groups, setGroups] = useState(null)
  const [state, setState] = useState('loading')   // loading | ready | failed
  const [choices, setChoices] = useState({})

  async function load() {
    setState('loading')
    try {
      const res = await api.notificationGroups.list()
      if (res?.success) { setGroups(res.data?.groups ?? []); setState('ready') } else setState('failed')
    } catch { setState('failed') }
  }
  useEffect(() => { if (open) { setChoices({}); load() } }, [open])

  const plan = useMemo(() => (groups ? defaultGroupPlan(teams, groups) : []), [teams, groups])
  const choiceOf = (p) => choices[String(p.teamId)] ?? p.initial
  const previewPlan = useMemo(() => {
    const rows = plan.map((p) => {
      const choice = choiceOf(p)
      const base = { id: p.teamId, name: p.teamName, teamId: p.teamId }
      if (p.candidates.length === 0) return { ...base, state: 'skip', reason: 'no_group' }
      if (!choice) return { ...base, state: 'skip', reason: 'no_choice' }
      if (p.current && String(p.current.id) === String(choice)) return { ...base, state: 'skip', reason: 'unchanged' }
      return { ...base, state: 'change', reason: null }
    })
    return { rows, change: rows.filter((r) => r.state === 'change').length, skip: rows.filter((r) => r.state === 'skip').length, check: 0 }
  }, [plan, choices]) // eslint-disable-line react-hooks/exhaustive-deps

  async function confirm() {
    const ids = defaultGroupRequests(plan, choices)
    const res = await api.notificationGroups.bulk({ action: 'make_default', ids })
    if (res?.success) return summarizeResult(res.data)
    throw new Error(res?.error || t('adm.bulk.requestFailed'))
  }

  const byTeam = new Map(plan.map((p) => [String(p.teamId), p]))
  const rowInfo = (r) => {
    const p = byTeam.get(String(r.teamId))
    if (!p) return null
    const current = p.current
      ? (p.currentUsable ? t('team.bulk.dg.current', p.current.name) : t('team.bulk.dg.currentUnusable', p.current.name))
      : t('team.bulk.dg.noCurrent')
    return (
      <span className="mt-1 flex min-w-0 flex-col gap-1.5">
        <span data-slot="tbdg-current" className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{current}</span>
        {p.candidates.length > 0 ? (
          <span className="w-full max-w-sm min-w-0 max-sm:[&_button]:min-h-10 pointer-coarse:[&_button]:min-h-10">
            <SearchableSelect wrapLabels value={choiceOf(p)} searchThreshold={6} ariaLabel={t('team.bulk.dg.pickFor', p.teamName)}
              placeholder={t('team.bulk.dg.pick')}
              onChange={(v) => setChoices((prev) => ({ ...prev, [String(p.teamId)]: v }))}
              options={[{ value: '', label: t('team.bulk.dg.pick') },
                ...p.candidates.map((g) => ({ value: String(g.id), label: deliverableCount(g) === 1 ? t('team.bulk.dg.option.one', g.name) : t('team.bulk.dg.option', g.name, deliverableCount(g)) }))]} />
          </span>
        ) : onCreateGroup && (
          <Button type="button" variant="link" className="h-auto w-fit p-0 text-xs pointer-coarse:min-h-10"
            data-slot="tbdg-create" onClick={() => onCreateGroup(p.teamId)}>{t('team.bulk.dg.create')}</Button>
        )}
      </span>
    )
  }

  return (
    <BulkPreviewDialog open={open} onClose={onClose} title={t('team.bulk.dg.title')} icon={Star}
      description={t('team.bulk.dg.desc')} plan={state === 'ready' ? previewPlan : null}
      keepOrder extraRowInfo={state === 'ready' ? rowInfo : null}
      confirmLabel={t('team.bulk.dg.confirm', previewPlan.change)} confirmDisabled={state !== 'ready'}
      onConfirm={confirm} onFinished={onDone}>
      {state === 'loading' && <StatusBlock tone="neutral" title={t('team.bulk.loading')} />}
      {state === 'failed' && (
        <StatusBlock tone="danger" role="alert" icon={CircleAlert} description={t('team.bulk.dg.loadFailed')}
          actions={<Button type="button" variant="outline" onClick={load}>{t('ng.retry')}</Button>} />
      )}
    </BulkPreviewDialog>
  )
}
