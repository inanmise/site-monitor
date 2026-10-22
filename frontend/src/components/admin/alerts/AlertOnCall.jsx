import { useEffect, useState } from 'react'
import { UserRoundCheck } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'

/**
 * Alarm ayrıntısında sahip takım(lar)ın şu anki nöbetçisi (2026-10-11). Yalnız AÇIK alarmda ve yalnız çizelgesi olan
 * takımda görünür ("kimi aramalı"); istek başarısız / çizelge yoksa hiç çizilmez. Ad sarar, kesilmez.
 * Test kancası: `data-slot="alert-oncall"`.
 */
export default function AlertOnCall({ alert: a, Fact }) {
  const t = useT()
  const [rows, setRows] = useState(null)
  // Sahip takımlar sunucudan (2026-10-12): bağımsız alarmda yalnız damgalı takım — envanterin SY/UG'si o alarmın sahibi
  // değildir, onların nöbetçisi "kimi aramalı" diye gösterilmez. Alan yoksa (eski yanıt) eski davranış.
  const ids = (Array.isArray(a?.owner_team_ids) ? a.owner_team_ids : [a?.team_id, a?.sy_team_id, a?.ug_team_id])
    .filter((x) => x != null)
  const key = [...new Set(ids.map(String))].join(',')
  const open = a && !a.resolved
  useEffect(() => {
    let off = false
    if (!open || !key || !api.oncall?.current) { setRows(null); return undefined }
    api.oncall.current(key.split(','))
      .then((r) => { if (!off) setRows(r?.success && Array.isArray(r.data) ? r.data : null) })
      .catch(() => { if (!off) setRows(null) })
    return () => { off = true }
  }, [open, key])
  if (!rows || rows.length === 0) return null
  return (
    <Fact label={t('oncall.short')} className="sm:col-span-2">
      <ul data-slot="alert-oncall" className="m-0 flex list-none flex-col gap-1 p-0">
        {rows.map((p) => (
          <li key={`${p.team_id}-${p.user_id}`} className="flex min-w-0 items-start gap-1.5">
            <UserRoundCheck aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-primary" />
            <span className="min-w-0 [overflow-wrap:anywhere]">
              <span className="font-semibold">{p.name}</span>
              <span className="text-xs text-muted-foreground"> · {t('oncall.untilShort', formatDate(p.until))}</span>
              {/* Pasif (devre dışı) nöbetçi (2026-10-22, L-N1 / PKG-3): aranmaz — alan yoksa bugünkü davranış */}
              {p.active === false && (
                <Badge variant="outline" data-slot="alert-oncall-passive"
                  className="ml-1.5 h-auto overflow-visible rounded-md border-destructive/40 bg-destructive/10 align-middle text-destructive whitespace-normal">
                  {t('noc.passiveNoCall')}
                </Badge>
              )}
            </span>
          </li>
        ))}
      </ul>
    </Fact>
  )
}
