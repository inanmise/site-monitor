import { Network, Globe } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from './SearchableSelect.jsx'

/**
 * HTTP tabanlı izleme formlarında (HTTP/Website, Keyword, Sayfa Bütünlüğü) kurumsal vekil tercihi (2026-09-21).
 * Üç kip — sertifika envanteri ve sentetik izlemeyle aynı sözleşme:
 *  • AUTO  "Envanterle aynı": URL'nin alan adı sertifika envanterinde "Proxy üzerinden kontrol et = Evet" ise vekil,
 *          değilse doğrudan (bugünkü davranış; mevcut izlemeler yol değiştirmez).
 *  • ON    her zaman vekil (vekil tanımlı ve hedef NO_PROXY'de değilse — sertifika kontrolüyle aynı kural).
 *  • OFF   her zaman doğrudan.
 * `effective` (liste satırından: proxy_effective / proxy_source / proxy_bypassed / mode=use_proxy) verilirse kararın
 * gerçekte ne olduğu ipucu olarak yazılır — "AUTO seçtim ama neden doğrudan?" sorusu formda cevaplansın.
 * Kip formda DEĞİŞTİRİLMİŞSE ipucu gizlenir (QA ISSUE-001, 2026-09-21): karar kaydedilmiş kipe aittir; "Her zaman
 * vekil" seçilmişken altında "Doğrudan · envanter kaydından" yazması kaydedilmemiş seçim için yanlış iddia olurdu.
 */
export const PROXY_MODES = ['AUTO', 'ON', 'OFF']

export default function MonitorProxyField({ value = 'AUTO', onChange, effective = null, disabled = false }) {
  const t = useT()
  const mode = PROXY_MODES.includes(value) ? value : 'AUTO'
  const options = PROXY_MODES.map((m) => ({ value: m, label: t(`mon.proxy.${m}`) }))
  // `mode` anahtarı verilmişse kaydedilmiş kiptir (null/bilinmeyen = AUTO, backend sözleşmesi); verilmemişse eski çağıran.
  const hasSaved = !!effective && Object.prototype.hasOwnProperty.call(effective, 'mode')
  const savedMode = hasSaved ? (PROXY_MODES.includes(effective.mode) ? effective.mode : 'AUTO') : null
  const showEffective = !!effective && (!hasSaved || savedMode === mode)
  return (
    <label className="full-width mon-proxy-field">
      <span>{t('mon.proxy.label')}</span>
      <SearchableSelect value={mode} onChange={onChange} options={options} searchThreshold={99} ariaLabel={t('mon.proxy.label')} disabled={disabled} />
      <span className="field-hint">{t(`mon.proxy.hint.${mode}`)}</span>
      {showEffective && (
        <span className={`field-hint mon-proxy-eff mon-proxy-eff--${effective.via === 'proxy' ? 'proxy' : 'direct'}`}>
          {effective.via === 'proxy' ? <Network size={12} /> : <Globe size={12} />}
          {effective.via === 'proxy' ? t('mon.proxy.effProxy') : t('mon.proxy.effDirect')}
          {effective.source && <> · {t(`mon.proxy.src.${effective.source}`)}</>}
          {effective.bypassed && <> · {t('mon.proxy.bypassed')}</>}
        </span>
      )}
    </label>
  )
}

/** Kart / detay rozeti — sertifika kartındaki "Proxy / Doğrudan" ile aynı dil. */
export function ProxyViaBadge({ via, source, bypassed, size = 11 }) {
  const t = useT()
  if (!via) return null
  const proxy = via === 'proxy'
  const title = [proxy ? t('mon.proxy.effProxy') : t('mon.proxy.effDirect'), source ? t(`mon.proxy.src.${source}`) : null, bypassed ? t('mon.proxy.bypassed') : null].filter(Boolean).join(' · ')
  return (
    <span className={`mon-proxy-badge${proxy ? ' is-proxy' : ''}`} title={title}>
      {proxy ? <Network size={size} /> : <Globe size={size} />}
      {proxy ? t('card.viaProxy') : t('card.viaDirect')}
    </span>
  )
}
