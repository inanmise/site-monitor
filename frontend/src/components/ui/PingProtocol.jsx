import { useT } from '../../i18n/index.jsx'

/**
 * Ping İzleme protokol satırı (2026-09-24, kullanıcı: "kart üstündeki paket adı daha belirgin olsun"): renkli
 * ICMP rozeti (IPv6'da ICMPv6 — IPv6 ping ICMPv6 echo kullanır), seçilmişse IP sürümü ve kontrol başına paket
 * sayısı. Eskiden sağ üstte 11px gri yazıydı ve kayıtlı değer `v4`/`v6` büyük harfe çevrilip "V4" diye
 * görünüyordu. Kart ve detay penceresi başlığı aynı bileşeni kullanır (Port'taki PortEndpoint'in ikizi).
 */
export default function PingProtocol({ host, ipVersion, packetCount, size }) {
  const t = useT()
  const v = String(ipVersion || 'auto').toLowerCase()
  const v6 = v === 'v6' || v === 'ipv6'
  const v4 = v === 'v4' || v === 'ipv4'
  const proto = v6 ? 'ICMPv6' : 'ICMP'
  const family = v6 ? 'IPv6' : v4 ? 'IPv4' : null
  const packets = Number(packetCount) > 0 ? Number(packetCount) : null
  const tip = [host, t('ping.protoTip', proto), family || t('ping.ipAutoTip'), packets && t('ping.packetsTip', packets)]
    .filter(Boolean).join(' · ')
  return (
    <span className={`port-ep${size === 'lg' ? ' port-ep--lg' : ''}`} title={tip}>
      <span className="port-ep-proto port-ep-proto--icmp port-ep-proto--lead">{proto}</span>
      {family && <span className="port-ep-fam">{family}</span>}
      {packets && <span className="port-ep-svc">{t('ping.packetsN', packets)}</span>}
    </span>
  )
}
