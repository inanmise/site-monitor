import { useMemo, useState } from 'react'
import { Search, X, Check } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Bakım penceresi hedef seçici (2026-09-17, kullanıcı isteği): ÖNCE izleme tipi, SONRA o tipin
 * monitörleri.
 *
 * <p>Eskiden bütün türlerin monitörleri tek düz listedeydi ("ad · tür" etiketiyle): 200+ satırlık
 * bir listede "hangi tür izlemeyi durduruyorum" görünmüyor, bir türü tümden susturmak için tek tek
 * seçmek gerekiyordu. Artık sol sütun türleri (her birinde toplam + seçili sayısı), sağ sütun o
 * türün monitörlerini gösterir; "bu türün tamamı" tek tıkla seçilir/kaldırılır.
 *
 * <p>Değer sözleşmesi DEĞİŞMEDİ: dışarıya yine hedef dizisi (`value`/`onChange` string[]), böylece
 * kaydetme yolu (targetObjs → {type, target, name}) aynı kalır.
 *
 * <p>"Tamamı" seçimi O ANKİ monitörleri ekler; sonradan eklenen bir monitör kapsanmaz — bunun için
 * pencerede "Tüm monitörler" kutusu var. İpucu metni bunu söyler.
 */
export default function MaintenanceTargetPicker({ options = [], value = [], onChange, typeLabel }) {
  const t = useT()
  const [activeType, setActiveType] = useState(null)
  const [q, setQ] = useState('')

  const selected = useMemo(() => new Set(value), [value])
  const byType = useMemo(() => {
    const map = new Map()
    for (const o of options) {
      if (!map.has(o.type)) map.set(o.type, [])
      map.get(o.type).push(o)
    }
    return map
  }, [options])

  const types = useMemo(() => [...byType.entries()].map(([type, list]) => ({
    type,
    total: list.length,
    picked: list.filter((o) => selected.has(o.value)).length,
  })).sort((a, b) => a.type.localeCompare(b.type)), [byType, selected])

  const current = activeType ?? types[0]?.type ?? null
  const list = useMemo(() => {
    const all = byType.get(current) || []
    const needle = q.trim().toLocaleLowerCase('tr')
    return needle ? all.filter((o) => `${o.name || ''} ${o.target || o.value}`.toLocaleLowerCase('tr').includes(needle)) : all
  }, [byType, current, q])

  const label = (type) => (typeLabel ? typeLabel(type) : type)
  const emit = (next) => onChange?.([...next])

  function toggle(v) {
    const next = new Set(selected)
    if (next.has(v)) next.delete(v); else next.add(v)
    emit(next)
  }

  function toggleAllOfType() {
    const all = byType.get(current) || []
    const next = new Set(selected)
    const everyPicked = all.length > 0 && all.every((o) => next.has(o.value))
    for (const o of all) { if (everyPicked) next.delete(o.value); else next.add(o.value) }
    emit(next)
  }

  const currentAll = byType.get(current) || []
  const currentPicked = currentAll.filter((o) => selected.has(o.value)).length
  const allPicked = currentAll.length > 0 && currentPicked === currentAll.length
  const chips = options.filter((o) => selected.has(o.value))

  if (!options.length) return <p className="hint mtp-empty">{t('mtp.noMonitors')}</p>

  return (
    <div className="mtp">
      <div className="mtp-cols">
        <ul className="mtp-types" role="tablist" aria-label={t('mtp.typesAria')}>
          {types.map((ty) => (
            <li key={ty.type}>
              <button type="button" role="tab" aria-selected={current === ty.type}
                className={`mtp-type${current === ty.type ? ' is-active' : ''}${ty.picked > 0 ? ' has-picked' : ''}`}
                onClick={() => { setActiveType(ty.type); setQ('') }}>
                <span className="mtp-type-name">{label(ty.type)}</span>
                <span className="mtp-type-count">{ty.picked > 0 ? `${ty.picked}/${ty.total}` : ty.total}</span>
              </button>
            </li>
          ))}
        </ul>

        <div className="mtp-list-wrap">
          <div className="mtp-list-head">
            {/* <label> DEĞİL: modaldaki `.form-grid label` kuralı etiketi dikey kolona çeviriyor ve
                büyüteç ikonunu kutunun ÜSTÜNE itiyordu. Erişilebilirlik aria-label ile korunur. */}
            <div className="mtp-search">
              <Search size={13} aria-hidden="true" />
              <input className="input" value={q} onChange={(e) => setQ(e.target.value)}
                placeholder={t('mtp.searchPh')} aria-label={t('mtp.searchPh')} />
            </div>
            <Button type="button" variant="secondary" size="sm" onClick={toggleAllOfType} disabled={!currentAll.length}>
              {allPicked ? t('mtp.clearType', label(current)) : t('mtp.selectType', label(current))}
            </Button>
          </div>
          <ul className="mtp-list">
            {list.map((o) => {
              const on = selected.has(o.value)
              return (
                <li key={o.value}>
                  <button type="button" className={`mtp-item${on ? ' is-on' : ''}`} aria-pressed={on} onClick={() => toggle(o.value)}>
                    <span className="mtp-item-check">{on && <Check size={12} aria-hidden="true" />}</span>
                    {/* value = KİMLİK ("tur:hedef"); ekranda hedefin kendisi yazar. */}
                    <span className="mtp-item-name">{o.name || o.target || o.value}</span>
                    {o.name && o.target && o.name !== o.target && <span className="mtp-item-target">{o.target}</span>}
                  </button>
                </li>
              )
            })}
            {list.length === 0 && <li className="mtp-none">{t('mtp.noMatch')}</li>}
          </ul>
        </div>
      </div>

      <div className="mtp-selected">
        <span className="mtp-selected-label">{t('mtp.selected', chips.length)}</span>
        {chips.length === 0 ? (
          <span className="mtp-none">{t('mtp.nothing')}</span>
        ) : (
          <>
            {chips.map((o) => (
              <span key={o.value} className="mtp-chip">
                <b>{label(o.type)}</b> {o.name || o.target || o.value}
                <button type="button" className="mtp-chip-x" aria-label={t('mtp.remove', o.name || o.target || o.value)}
                  onClick={() => toggle(o.value)}><X size={11} aria-hidden="true" /></button>
              </span>
            ))}
            <button type="button" className="mtp-clear" onClick={() => emit(new Set())}>{t('mtp.clearAll')}</button>
          </>
        )}
      </div>
      <p className="hint mtp-hint">{t('mtp.hint')}</p>
    </div>
  )
}
