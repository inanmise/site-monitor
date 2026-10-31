import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BookOpenCheck, ChevronDown, CircleAlert, FileCode2, RefreshCw, Search } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import ModalShell from '../ui/ModalShell.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { DbTypeBadge } from './dbqueryParts.jsx'
import { ruleSummary } from './ruleModel.js'
import { foldSearchText } from '../../utils/monitorFilters.js'

/** Takımın şablonlarını okur (sıra numaralı — geç yanıt düşer). */
export function useTeamTemplates(teamId, enabled = true) {
  const t = useT()
  const [load, setLoad] = useState({ state: 'loading', list: [], message: null })
  const seq = useRef(0)
  const reload = useCallback(async () => {
    const n = ++seq.current
    if (teamId == null) { setLoad({ state: 'noTeam', list: [], message: null }); return }
    setLoad((s) => ({ ...s, state: 'loading' }))
    try {
      const res = await api.monitoring.dbQueryTemplates(teamId)
      if (n !== seq.current) return
      if (res?.success && Array.isArray(res.data)) setLoad({ state: 'ok', list: res.data, message: null })
      else setLoad({ state: 'error', list: [], message: res?.error || res?.message || t('dbq.tpl.loadFailedBody') })
    } catch (e) {
      if (n !== seq.current) return
      setLoad({ state: 'error', list: [], message: e?.message || t('dbq.tpl.loadFailedBody') })
    }
  }, [teamId, t])
  useEffect(() => { if (enabled) reload() }, [enabled, reload])
  return [load, reload]
}

/** Şablon önizlemesi — sorgu kod bloğu + kural özetleri (satır verisi YOK). */
export function TemplatePreview({ tpl }) {
  const t = useT()
  const { lang } = useLanguage()
  const rules = Array.isArray(tpl?.rules) ? tpl.rules : []
  return (
    <div data-slot="dbq-tpl-preview" className="flex min-w-0 flex-col gap-2">
      {tpl?.purpose && <p className="m-0 text-sm [overflow-wrap:anywhere]"><span className="font-semibold">{t('dbq.tpl.purpose')} </span>{tpl.purpose}</p>}
      <pre className="m-0 max-h-60 overflow-auto rounded-md border bg-muted/50 px-3 py-2 font-mono text-xs leading-5 whitespace-pre text-foreground">{tpl?.sql_text || ''}</pre>
      {rules.length > 0 ? (
        <div className="flex flex-col gap-1">
          <span className="text-xs font-semibold text-muted-foreground">
            {t('dbq.tpl.rulesHeading', rules.length)} · {t(`dbq.rule.logic.${tpl?.rule_logic === 'ALL' ? 'ALL' : 'ANY'}`)}
          </span>
          <ul className="m-0 flex list-disc flex-col gap-0.5 pl-5 text-sm">
            {rules.map((r, i) => <li key={r.id || i} className="[overflow-wrap:anywhere]">{ruleSummary(r, t, lang)}</li>)}
          </ul>
        </div>
      ) : <p className="m-0 text-xs text-muted-foreground">{t('dbq.tpl.noRules')}</p>}
    </div>
  )
}

/**
 * "Şablondan başla" (Veritabanı Sorgu İzleme, 2026-10-31) — takımın sorgu şablonları; arama, önizleme (sorgu + kural
 * özeti). Seçilen şablonun DB türü seçili bağlantınınkinden farklıysa onay istenir (sorgu söz dizimi türe göre değişir).
 * `onPick(template)` sorguyu / kuralları forma çağıran uygular.
 */
export default function TemplatePicker({ teamId, dbType, onPick, disabled = false }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button type="button" variant="outline" disabled={disabled || teamId == null} onClick={() => setOpen(true)}
        className="pointer-coarse:h-10" data-slot="dbq-tpl-pick">
        <BookOpenCheck aria-hidden="true" />{t('dbq.tpl.startFrom')}
      </Button>
      {open && <PickerDialog teamId={teamId} dbType={dbType} onClose={() => setOpen(false)} onPick={(tpl) => { setOpen(false); onPick?.(tpl) }} />}
    </>
  )
}

function PickerDialog({ teamId, dbType, onClose, onPick }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [load, reload] = useTeamTemplates(teamId)
  const [q, setQ] = useState('')
  const [openId, setOpenId] = useState(null)
  const shown = useMemo(() => {
    const f = foldSearchText(q.trim())
    if (!f) return load.list
    return load.list.filter((x) => foldSearchText(`${x.name} ${x.description || ''} ${x.purpose || ''}`).includes(f))
  }, [load.list, q])

  const pick = async (tpl) => {
    if (dbType && tpl.db_type && tpl.db_type !== dbType) {
      const ok = await showConfirm({
        title: t('dbq.tpl.typeMismatchTitle'),
        message: t('dbq.tpl.typeMismatchBody', t(`dbq.conn.type.${tpl.db_type}`), t(`dbq.conn.type.${dbType}`)),
        confirmText: t('dbq.tpl.useAnyway'), variant: 'warning',
      })
      if (!ok) return
    }
    onPick(tpl)
  }

  return (
    <ModalShell open onClose={onClose} size="lg" scrollBody icon={BookOpenCheck} title={t('dbq.tpl.pickTitle')}>
      <div data-slot="dbq-tpl-picker" className="flex min-w-0 flex-col gap-3">
        <p className="m-0 text-sm text-muted-foreground">{t('dbq.tpl.pickHint')}</p>
        {load.state === 'ok' && load.list.length > 3 && (
          <InputGroup className="h-9 max-w-sm">
            <InputGroupInput type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('dbq.tpl.search')} aria-label={t('dbq.tpl.search')} />
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          </InputGroup>
        )}
        {load.state === 'loading' && <div className="flex flex-col gap-2"><Skeleton className="h-16 w-full" /><Skeleton className="h-16 w-full" /></div>}
        {load.state === 'error' && (
          <StatusBlock tone="danger" icon={CircleAlert} title={t('dbq.tpl.loadFailedTitle')} description={load.message}
            actions={<Button type="button" variant="outline" onClick={reload} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.tpl.retry')}</Button>} />
        )}
        {load.state === 'ok' && load.list.length === 0 && (
          <StatusBlock tone="neutral" icon={FileCode2} title={t('dbq.tpl.emptyTitle')} description={t('dbq.tpl.emptyBody')} />
        )}
        {load.state === 'ok' && load.list.length > 0 && shown.length === 0 && (
          <p className="m-0 text-sm text-muted-foreground">{t('dbq.tpl.noMatch')}</p>
        )}
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {shown.map((tpl) => (
            <li key={tpl.id}>
              <Card data-slot="dbq-tpl-item" data-id={tpl.id} className="gap-2 px-3 py-3 shadow-none">
                <Collapsible open={openId === tpl.id} onOpenChange={(o) => setOpenId(o ? tpl.id : null)}>
                  <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start">
                    <div className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="font-semibold [overflow-wrap:anywhere]">{tpl.name}</span>
                      <div className="flex flex-wrap items-center gap-1.5">
                        <DbTypeBadge type={tpl.db_type} />
                        {dbType && tpl.db_type && tpl.db_type !== dbType && (
                          <span className="text-xs text-amber-700 dark:text-amber-300">{t('dbq.tpl.otherType')}</span>
                        )}
                        <span className="text-xs text-muted-foreground">{t('dbq.tpl.ruleCount', (tpl.rules || []).length)}</span>
                      </div>
                      {tpl.description && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{tpl.description}</span>}
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <CollapsibleTrigger asChild>
                        <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10" aria-label={t('dbq.tpl.previewOf', tpl.name)}>
                          <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', openId === tpl.id && 'rotate-180')} />
                          {t('dbq.tpl.preview')}
                        </Button>
                      </CollapsibleTrigger>
                      <Button type="button" size="sm" onClick={() => pick(tpl)} className="pointer-coarse:h-10" aria-label={t('dbq.tpl.useOf', tpl.name)}>
                        {t('dbq.tpl.use')}
                      </Button>
                    </div>
                  </div>
                  <CollapsibleContent className="pt-2"><TemplatePreview tpl={tpl} /></CollapsibleContent>
                </Collapsible>
              </Card>
            </li>
          ))}
        </ul>
      </div>
    </ModalShell>
  )
}
