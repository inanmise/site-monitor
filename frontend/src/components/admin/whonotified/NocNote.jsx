import { useEffect, useState } from 'react'
import { ArrowRight, Check, Headset, Info, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { ChannelCard } from './ChannelCards.jsx'

const LEVEL_VALUE = { WARNING: 1, HIGH: 2, CRITICAL: 3 }
const SSL = 'SSL'

/**
 * 7/24 izleme ekibi (NOC) notu — "Kim bilgilendirilir?" (2026-09-28). 7/24 e-postası kişi zincirinden AYRIDIR ve
 * İZLEME BAŞINA karar verilir (`noc_notify` + tür anahtarı + asgari seviye + aktif grup; `.migration/noc/CONTRACT.md`).
 * Senaryo bir izleme seçmediği için burada izleme sonucu UYDURULMAZ: yalnız sunucunun takım özeti
 * (`GET /api/noc/coverage?team_id=`) — asgari seviye, kapalı türler, aktif grup sayısı ve takımda 7/24'e gerçekten
 * bildirilen izleme sayısı — ve ayrıntı için "7/24 Kapsamı" bağlantısı.
 */
export default function NocNote({ teamId, kind, level, className }) {
  const t = useT()
  const [state, setState] = useState({ key: '', summary: null, error: false })
  const key = `${teamId}:${kind}`

  useEffect(() => {
    if (!teamId) return undefined
    let alive = true
    Promise.resolve(api.noc?.coverage?.({ teamId, type: kind === 'CERT' ? SSL : undefined }))
      .then((res) => {
        if (!alive) return
        const summary = res?.success ? res.data?.summary : null
        setState({ key, summary: summary && typeof summary === 'object' ? summary : null, error: !summary })
      })
      .catch(() => { if (alive) setState({ key, summary: null, error: true }) })
    return () => { alive = false }
  }, [teamId, kind, key])

  const loaded = state.key === key
  const s = loaded ? state.summary : null
  const lines = []
  if (s) {
    const min = LEVEL_VALUE[s.min_level] ? s.min_level : 'CRITICAL'
    const levelOk = (LEVEL_VALUE[level] || 0) >= LEVEL_VALUE[min]
    lines.push({ id: 'level', ok: levelOk, text: t(levelOk ? 'wn.nocLevelOk' : 'wn.nocLevelLow', t(`sim.level.${min}`)) })

    const disabled = Array.isArray(s.disabled_types) ? s.disabled_types : []
    if (kind === 'CERT') {
      lines.push(disabled.includes(SSL)
        ? { id: 'types', ok: false, text: t('wn.nocTypeOffCert') }
        : { id: 'types', ok: true, text: t('wn.nocTypesOn') })
    } else {
      const off = disabled.filter((k) => k !== SSL)
      lines.push(off.length > 0
        ? { id: 'types', ok: false, text: t('wn.nocTypesOff', off.map((k) => t(`noc.type.${k}`)).join(', ')) }
        : { id: 'types', ok: true, text: t('wn.nocTypesOn') })
    }
    if (Number(s.active_groups) === 0) lines.push({ id: 'groups', ok: false, text: t('wn.nocNoGroup') })

    // Takımda gerçekten 7/24'e bildirilen izleme (sunucu sayar); izleme türü senaryosunda sertifika satırları hariç.
    const byType = s.by_type && typeof s.by_type === 'object' ? s.by_type : {}
    let total = 0
    let covered = 0
    for (const [type, c] of Object.entries(byType)) {
      if ((kind === 'CERT') !== (type === SSL)) continue
      total += Number(c?.total) || 0
      covered += Number(c?.covered) || 0
    }
    lines.push({ id: 'coverage', ok: null, text: t('wn.nocTeamCoverage', covered, total) })
  }

  return (
    <ChannelCard channel="noc" icon={Headset} title={t('wn.nocTitle')} description={t('wn.nocDesc')} className={className}>
      <div className="flex min-w-0 flex-col gap-3 px-1">
        {!loaded ? (
          <div data-slot="wn-noc-loading" className="flex flex-col gap-2">
            <Skeleton className="h-4 w-3/4" /><Skeleton className="h-4 w-2/3" /><Skeleton className="h-4 w-1/2" />
          </div>
        ) : state.error ? (
          <p className="text-sm text-muted-foreground">{t('wn.nocUnavailable')}</p>
        ) : (
          <ul data-slot="wn-noc-status" className="flex flex-col gap-2">
            {lines.map((l) => {
              const Icon = l.ok == null ? Info : (l.ok ? Check : X)
              return (
                <li key={l.id} data-noc={l.id} data-ok={l.ok == null ? undefined : String(l.ok)} className="flex items-start gap-2 text-sm">
                  <Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0',
                    l.ok == null ? 'text-muted-foreground' : (l.ok ? 'text-success' : 'text-amber-600 dark:text-amber-400'))} />
                  <span className="min-w-0">{l.text}</span>
                </li>
              )
            })}
          </ul>
        )}
        <p className="text-xs text-muted-foreground">{t('wn.nocPerMonitor')}</p>
        <div>
          <Button type="button" variant="outline" size="sm" className="h-10 w-full sm:w-auto md:h-8" data-action="wn-open-noc"
            onClick={() => navigateTo('noc', { n_team: String(teamId), ...(kind === 'CERT' ? { n_type: SSL } : {}) })}>
            {t('wn.nocOpen')} <ArrowRight aria-hidden="true" />
          </Button>
        </div>
      </div>
    </ChannelCard>
  )
}
