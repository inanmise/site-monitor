import { Layers } from 'lucide-react'
import TeamBadge from './ui/TeamBadge.jsx'
import { ProxyViaBadge } from './ui/MonitorProxyField.jsx'

/**
 * İzleme kartındaki takım ve grup rozetleri.
 *
 * <p>Bu on satır altı izleme sayfasında (domain/http/keyword/page/ping/scripted) birebir aynı
 * kopyalanmıştı — üstelik satır içi {@code style} nesnesi de dahil. Satır içi stilin kopyalanması
 * ayrı bir tuzak: rozetlerin görünümü değişmek istendiğinde altı dosyada altı ayrı yerde
 * düzeltilmesi gerekiyordu ve birinin atlanması ancak o sayfaya bakan biri fark edene kadar
 * görünmüyordu.
 *
 * <p>Davranış aynen korundu: alan boşsa (null/undefined/boş string) o rozet HİÇ çizilmez —
 * "Takım: —" gibi boş bir satır bırakılmaz. Port ve DNS sayfaları bu bloğu kullanmıyor
 * (kart düzenleri farklı), bu yüzden onlara dokunulmadı.
 *
 * @param {{team_name?: string, group_name?: string}} monitor
 */
export default function MonitorCardMeta({ monitor }) {
  const rowStyle = {
    display: 'flex', alignItems: 'center', gap: 5,
    fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2,
  }
  return (
    <>
      {monitor.team_name && (
        <div style={rowStyle}><TeamBadge teamId={monitor.team_id} teamName={monitor.team_name} /></div>
      )}
      {monitor.group_name && (
        <div style={rowStyle}><Layers size={12} />{monitor.group_name}</div>
      )}
      {/* Vekil rozeti (2026-09-21): yalnız HTTP/Keyword/Sayfa satırları proxy_effective taşır — sertifika kartındaki dil */}
      {monitor.proxy_effective && (
        <div style={rowStyle}><ProxyViaBadge via={monitor.proxy_effective} source={monitor.proxy_source} bypassed={monitor.proxy_bypassed} size={12} /></div>
      )}
    </>
  )
}
