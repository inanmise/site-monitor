import { useState, useEffect, useId } from 'react'
import { Trash2, ArrowRightLeft } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardTitle } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'

const SECTIONS = ['domains', 'monitors', 'users', 'contacts', 'groups']

/**
 * Takım silme ETKİ önizlemesi (2026-09-20): silmeden önce "bu takıma bağlı N domain, M izleme, K kullanıcı,
 * C kişi, G grup, A açık alarm" listesi + hedef takıma taşı. Bildirim grubundaki "409 → taşı" deseni.
 * Bağlı varlık varken düz silme sunucuda da engelli; burada engel görünür ve çözümü yanındadır.
 */
export default function TeamDeleteImpactModal({ team, teams = [], onClose, onDeleted }) {
  const t = useT()
  const toast = useToast()
  const [impact, setImpact] = useState(null)
  const [error, setError] = useState(null)
  const [target, setTarget] = useState('')
  const [busy, setBusy] = useState(false)
  const targetId = useId()

  useEffect(() => {
    let alive = true
    api.admin.teamImpact(team.id)
      .then(res => { if (!alive) return; if (res?.success) setImpact(res.data); else setError(res?.error || t('team.impactError')) })
      .catch(e => { if (alive) setError(e?.message || t('team.impactError')) })
    return () => { alive = false }
  }, [team.id])   // eslint-disable-line react-hooks/exhaustive-deps

  const others = teams.filter(x => x.id !== team.id)
  const empty = !!impact?.empty

  async function moveThenDelete(deleteAfter) {
    setBusy(true)
    try {
      if (!empty) {
        const mv = await api.admin.teamMove(team.id, Number(target))
        if (!mv?.success) { toast.error(mv?.error || t('team.moveError')); return }
        const m = mv.data || {}
        toast.success(t('team.moved', m.domains ?? 0, m.monitors ?? 0, m.users ?? 0))
      }
      if (deleteAfter) {
        const res = await api.admin.deleteTeam(team.id)
        if (!res?.success) { toast.error(res?.error || t('team.deleteError')); onDeleted?.(false); return }
        toast.success(t('team.deleted'))
      }
      onDeleted?.(true)
    } finally { setBusy(false) }
  }

  const footer = (
    <>
      <Button variant="secondary" onClick={onClose} disabled={busy}>{t('team.deleteCancel')}</Button>
      {impact && !empty && (
        <Button variant="secondary" onClick={() => moveThenDelete(false)} disabled={busy || !target} aria-busy={busy || undefined}>
          <ArrowRightLeft size={14} /> {t('team.moveOnly')}
        </Button>
      )}
      {impact && (
        <Button variant="destructive" onClick={() => moveThenDelete(true)} disabled={busy || (!empty && !target)} aria-busy={busy || undefined}>
          <Trash2 size={14} /> {empty ? t('team.deleteConfirm') : t('team.moveAndDelete')}
        </Button>
      )}
    </>
  )

  return (
    <ModalShell open onClose={onClose} title={t('team.impactTitle', team.name)} icon={Trash2} size="lg" busy={busy} footer={footer}>
      {error && <AlertBanner tone="danger" role="alert">{error}</AlertBanner>}
      {!impact && !error && <LoadingBlock label={t('team.impactLoading')} size={16} />}
      {impact && (
        <div className="flex flex-col gap-3" data-testid="team-impact">
          {empty ? (
            <AlertBanner tone="info">{t('team.impactEmpty')}</AlertBanner>
          ) : (
            <AlertBanner tone="warning">{t('team.impactWarn')}</AlertBanner>
          )}
          {Number(impact.open_alerts) > 0 && <p className="text-xs text-muted-foreground">{t('team.impactOpenAlerts', impact.open_alerts)}</p>}
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(220px,100%),1fr))] gap-2.5">
            {SECTIONS.map(k => {
              const s = impact[k] || { count: 0, items: [] }
              if (!s.count) return null
              return (
                <Card key={k} className="gap-1.5 px-2.5 py-2 shadow-none">
                  <CardTitle role="heading" aria-level={4} className="text-[0.88em]">{t(`team.impact.${k}`, s.count)}</CardTitle>
                  <ul className="max-h-40 list-disc overflow-y-auto pl-4 text-[0.84em]">
                    {(s.items || []).map(x => <li key={x}>{x}</li>)}
                    {s.count > (s.items || []).length && <li className="text-xs text-muted-foreground">{t('team.impactMore', s.count - s.items.length)}</li>}
                  </ul>
                </Card>
              )
            })}
          </div>
          {!empty && (
            <div className="flex max-w-[420px] flex-col gap-1.5">
              <Label htmlFor={targetId} className="text-xs font-normal text-muted-foreground">{t('team.moveTarget')}</Label>
              <SearchableSelect id={targetId} value={target} onChange={setTarget} placeholder={t('team.movePick')}
                searchThreshold={4}
                options={[{ value: '', label: t('team.movePick') }, ...others.map(x => ({ value: String(x.id), label: x.name }))]} />
              {others.length === 0 && <span className="text-xs text-muted-foreground">{t('team.moveNoTarget')}</span>}
            </div>
          )}
        </div>
      )}
    </ModalShell>
  )
}
