import { Timer } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { ipFamily, intervalText, packetsText } from './pingCardModel.js'

const CHIP = 'h-5 rounded-md px-1.5 text-[10.5px] font-semibold'

/**
 * Ping kartının yapılandırma çipleri — shadcn Badge (outline): protokol (ICMP; IPv6'da ICMPv6 — IPv6 ping ICMPv6 echo
 * kullanır), seçilmişse IP ailesi (otomatikte YAZILMAZ — "Otomatik" gürültü olurdu), kontrol başına paket sayısı ve
 * kontrol sıklığı. Protokol çipi hafif camgöbeği tonunu korur (2026-09-24 kullanıcı isteği: "paket adı belirgin olsun").
 * Test kancaları: `data-slot="ping-protocol"`, çip başına `data-chip="proto|family|packets|interval"`.
 */
export default function PingProtocolChips({ monitor, className }) {
  const t = useT()
  const fam = ipFamily(monitor?.ip_version)
  const proto = fam === 'v6' ? 'ICMPv6' : 'ICMP'
  const packets = packetsText(monitor?.packet_count, t)
  const every = intervalText(monitor?.interval_seconds, t)
  return (
    <div data-slot="ping-protocol" className={cn('flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      <Badge variant="outline" data-chip="proto"
        className={cn(CHIP, 'border-cyan-600/30 bg-cyan-500/10 font-mono font-bold tracking-[.03em] text-cyan-800 dark:border-cyan-400/30 dark:text-cyan-300')}>
        {proto}
      </Badge>
      {fam && <Badge variant="outline" data-chip="family" className={cn(CHIP, 'font-mono')}>{fam === 'v6' ? 'IPv6' : 'IPv4'}</Badge>}
      {packets && <Badge variant="outline" data-chip="packets" className={cn(CHIP, 'font-medium text-muted-foreground')}>{packets}</Badge>}
      {every && (
        <Badge variant="outline" data-chip="interval" className={cn(CHIP, 'font-medium text-muted-foreground')}>
          <Timer aria-hidden="true" />{every}
        </Badge>
      )}
    </div>
  )
}
