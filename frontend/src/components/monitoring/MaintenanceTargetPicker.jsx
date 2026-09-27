import { useMemo, useState } from 'react'
import { Search, X, Check } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { Toggle } from '@/components/shadcn/toggle'
import { cn } from '@/lib/utils'

/**
 * Bakım penceresi hedef seçici (2026-09-17, kullanıcı isteği): ÖNCE izleme tipi, SONRA o tipin
 * monitörleri. Çizim shadcn: türler dikey Tabs, monitör satırları Toggle (basılı = seçili),
 * arama InputGroup, seçim çipleri Badge.
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
 *
 * <p>Bileşende `<label>` YOK: çağıran form `.form-grid label` kuralı (katmansız) taşıyor olabilir ve o
 * kural etiketleri dikey kolona çevirir. Erişilebilir adlar aria-label / düğme içeriğinden gelir.
 * Test kancaları: tür sekmesi `role="tab"` (`data-type`), satır `data-slot="mtp-item"` (aria-pressed),
 * çip `data-slot="mtp-chip"`, seçim sayacı `data-slot="mtp-selected-count"`.
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

  if (!options.length) {
    return <StatusBlock tone="neutral" description={t('mtp.noMonitors')} className="my-1.5 py-3 md:py-3" />
  }

  return (
    <div className="flex flex-col gap-2.5">
      <Tabs value={current ?? ''} onValueChange={(v) => { setActiveType(v); setQ('') }} orientation="vertical"
        className="grid grid-cols-1 items-start gap-2.5 sm:grid-cols-[190px_minmax(0,1fr)]">
        <TabsList aria-label={t('mtp.typesAria')}
          className="flex h-auto w-full flex-row justify-start gap-0.5 overflow-x-auto bg-transparent p-0 sm:flex-col sm:items-stretch sm:overflow-visible">
          {types.map((ty) => (
            <TabsTrigger key={ty.type} value={ty.type} data-type={ty.type}
              className="h-auto flex-none justify-between gap-2 px-2 py-1.5 font-normal data-[state=active]:bg-primary/10 data-[state=active]:font-semibold data-[state=active]:shadow-none sm:w-full">
              <span className="min-w-0 truncate">{label(ty.type)}</span>
              <Badge variant={ty.picked > 0 ? 'default' : 'secondary'} className="px-1.5 py-0 text-[11px] tabular-nums">
                {ty.picked > 0 ? `${ty.picked}/${ty.total}` : ty.total}
              </Badge>
            </TabsTrigger>
          ))}
        </TabsList>

        {current != null && (
          <TabsContent value={current} className="min-w-0 rounded-lg border p-1.5">
            <div className="mb-1.5 flex flex-wrap items-center gap-2">
              <InputGroup className="h-8 min-w-0 flex-1">
                <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)}
                  placeholder={t('mtp.searchPh')} aria-label={t('mtp.searchPh')} />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              </InputGroup>
              <Button type="button" variant="secondary" size="sm" onClick={toggleAllOfType} disabled={!currentAll.length}>
                {allPicked ? t('mtp.clearType', label(current)) : t('mtp.selectType', label(current))}
              </Button>
            </div>
            <ul className="m-0 flex max-h-[220px] list-none flex-col gap-0.5 overflow-y-auto p-0">
              {list.map((o) => {
                const on = selected.has(o.value)
                return (
                  <li key={o.value}>
                    <Toggle pressed={on} onPressedChange={() => toggle(o.value)} data-slot="mtp-item"
                      className="h-auto w-full justify-start gap-2 px-2 py-1 font-normal hover:bg-muted hover:text-foreground data-[state=on]:bg-success/10 data-[state=on]:text-foreground">
                      <span aria-hidden="true"
                        className={cn('inline-flex size-4 shrink-0 items-center justify-center rounded border',
                          on ? 'border-success text-success' : 'border-input')}>
                        {on && <Check size={12} />}
                      </span>
                      {/* value = KİMLİK ("tur:hedef"); ekranda hedefin kendisi yazar. */}
                      <span data-slot="mtp-item-name" className="min-w-0 flex-1 truncate text-left">{o.name || o.target || o.value}</span>
                      {o.name && o.target && o.name !== o.target && (
                        <span className="max-w-[45%] truncate text-xs text-muted-foreground">{o.target}</span>
                      )}
                    </Toggle>
                  </li>
                )
              })}
              {list.length === 0 && <li className="px-2 py-1.5 text-[13px] text-muted-foreground">{t('mtp.noMatch')}</li>}
            </ul>
          </TabsContent>
        )}
      </Tabs>

      <div className="flex flex-wrap items-center gap-1.5">
        <span data-slot="mtp-selected-count" className="text-xs font-semibold text-muted-foreground">{t('mtp.selected', chips.length)}</span>
        {chips.length === 0 ? (
          <span className="px-2 py-1.5 text-[13px] text-muted-foreground">{t('mtp.nothing')}</span>
        ) : (
          <>
            {chips.map((o) => (
              <Badge key={o.value} variant="outline" data-slot="mtp-chip" className="gap-1 py-0.5 pr-1 pl-2 font-normal">
                <b className="font-semibold text-muted-foreground">{label(o.type)}</b> {o.name || o.target || o.value}
                <Button type="button" variant="ghost" size="icon-xs"
                  className="size-4 rounded-full text-muted-foreground hover:text-destructive"
                  aria-label={t('mtp.remove', o.name || o.target || o.value)}
                  onClick={() => toggle(o.value)}><X size={11} aria-hidden="true" /></Button>
              </Badge>
            ))}
            <Button type="button" variant="link" size="xs" className="h-auto px-1 text-xs" onClick={() => emit(new Set())}>{t('mtp.clearAll')}</Button>
          </>
        )}
      </div>
      <p className="m-0 text-xs text-muted-foreground">{t('mtp.hint')}</p>
    </div>
  )
}
