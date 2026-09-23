import { useT } from '../../i18n/index.jsx'

/**
 * Bilinen portların hizmet adı — kartı okuyan "5432 neydi?" diye düşünmesin. Yalnız ipucu: eşleşme yoksa
 * hiçbir şey yazılmaz, sunucudaki gerçek hizmeti iddia etmez (8080'de başka bir şey de dinliyor olabilir).
 */
export const WELL_KNOWN_PORTS = {
  21: 'FTP', 22: 'SSH', 23: 'Telnet', 25: 'SMTP', 53: 'DNS', 80: 'HTTP', 110: 'POP3', 123: 'NTP', 143: 'IMAP',
  161: 'SNMP', 389: 'LDAP', 443: 'HTTPS', 445: 'SMB', 465: 'SMTPS', 514: 'Syslog', 587: 'SMTP Submission',
  636: 'LDAPS', 993: 'IMAPS', 995: 'POP3S', 1433: 'SQL Server', 1521: 'Oracle DB', 2049: 'NFS', 3306: 'MySQL',
  3389: 'RDP', 5432: 'PostgreSQL', 5672: 'AMQP', 6379: 'Redis', 8080: 'HTTP (alt)', 8443: 'HTTPS (alt)',
  9092: 'Kafka', 9200: 'Elasticsearch', 11211: 'Memcached', 27017: 'MongoDB',
}

/**
 * Port İzleme uç noktası (2026-09-24, kullanıcı: "izlenen port ve protokol daha belirgin olsun"): büyük `:port`,
 * türüne göre renkli protokol rozeti, HTTP türünde denetlenen yol ve bilinen portun hizmet adı. Kart ve detay
 * penceresi başlığı aynı bileşeni kullanır. Renk tek sinyal değil: rozet metni + title (tam tür açıklaması).
 */
export default function PortEndpoint({ host, port, protocol, path, size }) {
  const t = useT()
  const proto = String(protocol || 'TCP').toUpperCase()
  const svc = WELL_KNOWN_PORTS[Number(port)]
  const tip = [host ? `${host}:${port}` : `:${port}`, t(`port.type.${proto}`), svc].filter(Boolean).join(' · ')
  return (
    <span className={`port-ep${size === 'lg' ? ' port-ep--lg' : ''}`} title={tip}>
      <span className="port-ep-port">:{port}</span>
      <span className={`port-ep-proto port-ep-proto--${proto.toLowerCase()}`}>{proto}</span>
      {proto === 'HTTP' && path && <span className="port-ep-path">{path}</span>}
      {svc && <span className="port-ep-svc">{svc}</span>}
    </span>
  )
}
