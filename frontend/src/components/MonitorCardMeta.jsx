import { Layers } from 'lucide-react'
import TeamBadge from './ui/TeamBadge.jsx'
import { ProxyViaBadge } from './ui/MonitorProxyField.jsx'

/**
 * İzleme kartındaki takım, grup ve vekil rozetleri.
 *
 * <p>Bu on satır altı izleme sayfasında (domain/http/keyword/page/ping/scripted) birebir aynı
 * kopyalanmıştı — üstelik satır içi {@code style} nesnesi de dahil. Tek kopya burada.
 *
 * <p>Davranış: alan boşsa (null/undefined/boş string) o rozet HİÇ çizilmez — "Takım: —" gibi boş bir
 * öğe bırakılmaz; hiçbiri yoksa satır da çizilmez (kartta boşluk açılmaz).
 *
 * <p>2026-09-26 kart incelemesi: rozetler alt alta üç ayrı satırdı (her kartta ~60 px dikey yer); artık TEK satırda
 * yan yana, dar kartta SARAR. Satır içi stil yerine Tailwind jetonları (koyu tema dâhil). Test kancaları:
 * `data-slot="monitor-card-meta"` ve öğe başına `meta-team` / `meta-group` / `meta-proxy`.
 *
 * @param {{team_id?: number, team_name?: string, group_name?: string, proxy_effective?: string}} monitor
 */
export default function MonitorCardMeta({ monitor }) {
  const team = monitor.team_name
  const group = monitor.group_name
  const proxy = monitor.proxy_effective
  if (!team && !group && !proxy) return null
  return (
    <div data-slot="monitor-card-meta" className="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-[.78em] text-muted-foreground">
      {team && (
        <span data-slot="meta-team" className="flex min-w-0 items-center"><TeamBadge teamId={monitor.team_id} teamName={team} /></span>
      )}
      {group && (
        <span data-slot="meta-group" className="flex min-w-0 items-center gap-[5px]">
          <Layers size={12} aria-hidden="true" className="shrink-0" /><span className="min-w-0 truncate">{group}</span>
        </span>
      )}
      {/* Vekil rozeti (2026-09-21): yalnız HTTP/Keyword/Sayfa satırları proxy_effective taşır — sertifika kartındaki dil */}
      {proxy && (
        <span data-slot="meta-proxy" className="flex min-w-0 items-center">
          <ProxyViaBadge via={proxy} source={monitor.proxy_source} bypassed={monitor.proxy_bypassed} size={12} />
        </span>
      )}
    </div>
  )
}
