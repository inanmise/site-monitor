import { useId } from 'react'
import { useT } from '../../i18n/index.jsx'

/**
 * "Şablonun son hâlini uygula" onayında SİLİNECEKLERİN ADLARI (2026-11-20, B4). Sunucunun önizlemesi (`template_update`)
 * `lost_fields` / `lost_slots` listelerini taşır: aynı kaynaktan sayılan değerler ve görseller, etiketler kanal / ay / yıl
 * yer tutucuları çözülmüş, en çok 100 ad + `lost_*_more`. Liste kaydırılır (klavyeyle de: odaklanabilir bölge), adlar
 * sarar (kesilmez). Eski sunucu listeleri göndermez → hiçbir şey çizilmez (onay metni sayıları söyler).
 */
export function hasLossLists(upd) {
  return (Array.isArray(upd?.lost_fields) && upd.lost_fields.length > 0) || (Array.isArray(upd?.lost_slots) && upd.lost_slots.length > 0)
}

/** Bir değer kaleminin metni: ad, bölüm ve (birden çok değerse) ne kaybolduğu. */
export function lossFieldText(item, t) {
  const name = typeof item?.label === 'string' && item.label.trim() ? item.label.trim() : t('te.tplLoss.unnamed')
  const count = Number(item?.count) > 1 ? Number(item.count) : 1
  let what = null
  switch (item?.kind) {
    case 'target': what = t('te.tplLoss.kind.target'); break
    case 'text': what = t('te.tplLoss.kind.text'); break
    case 'topic': what = t(count === 1 ? 'te.tplLoss.kind.topic.one' : 'te.tplLoss.kind.topic', count); break
    case 'topic_answers': what = t(count === 1 ? 'te.tplLoss.kind.answers.one' : 'te.tplLoss.kind.answers', count); break
    case 'table': what = t(count === 1 ? 'te.tplLoss.kind.cells.one' : 'te.tplLoss.kind.cells', count); break
    default: what = null
  }
  return what ? `${name} — ${what}` : name
}

/** Bir görsel kaleminin metni: yuva adı ya da konu başlığı + görselin sırası. */
export function lossSlotText(item, t) {
  const name = typeof item?.label === 'string' && item.label.trim() ? item.label.trim() : t('te.tplLoss.unnamed')
  if (item?.kind === 'topic_image') return t('te.tplLoss.topicImage', name, Number(item?.image_no) || 1)
  return name
}

function Group({ title, items, more, render, slot }) {
  const t = useT()
  const id = useId()
  if (!items.length) return null
  return (
    <div className="flex min-w-0 flex-col gap-1" data-slot={slot}>
      <h3 id={id} className="m-0 text-sm font-semibold">{title}</h3>
      <ul aria-labelledby={id} className="m-0 flex list-disc flex-col gap-1 ps-5 text-sm">
        {items.map((it, i) => (
          <li key={`${it?.id ?? i}:${i}`} className="[overflow-wrap:anywhere]" data-slot={`${slot}-item`} data-id={it?.id}>
            {render(it, t)}
            {/* Bölüm adı ancak kalemin adından farklıysa yazılır (metin bölümü / tablo: ad zaten bölümün adı) */}
            {typeof it?.section === 'string' && it.section.trim() && it.section.trim() !== String(it?.label ?? '').trim() && (
              <span className="text-muted-foreground"> · {it.section.trim()}</span>
            )}
          </li>
        ))}
      </ul>
      {more > 0 && <p className="m-0 text-xs text-muted-foreground" data-slot={`${slot}-more`}>{t('te.tplLoss.more', more)}</p>}
    </div>
  )
}

export default function TemplateLossList({ upd }) {
  const t = useT()
  const fields = Array.isArray(upd?.lost_fields) ? upd.lost_fields : []
  const slots = Array.isArray(upd?.lost_slots) ? upd.lost_slots : []
  if (!fields.length && !slots.length) return null
  return (
    <div role="region" tabIndex={0} aria-label={t('te.tplLoss.region')} data-slot="te-tpl-loss"
      className="flex max-h-60 min-w-0 flex-col gap-3 overflow-y-auto rounded-md border bg-muted/30 p-3 outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
      <Group title={t('te.tplLoss.values')} items={fields} more={Number(upd?.lost_fields_more) || 0} render={lossFieldText}
        slot="te-tpl-loss-fields" />
      <Group title={t('te.tplLoss.images')} items={slots} more={Number(upd?.lost_slots_more) || 0} render={lossSlotText}
        slot="te-tpl-loss-slots" />
    </div>
  )
}
