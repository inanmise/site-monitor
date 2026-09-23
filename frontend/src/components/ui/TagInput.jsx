import { useState, useEffect, useMemo, useRef } from 'react'
import { useT } from '../../i18n/index.jsx'

const randomHue = () => Math.floor(Math.random() * 360)
/** Deterministik ton — henüz renk atanmamış (düzenlemede yüklenen) etiketler için stabil fallback. */
const tagHue = (s) => {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return h
}

/**
 * Paylaşımlı etiket chip input — text yazıp Enter (veya virgül) → chip. Değer CSV string
 * ("a, b, c") olarak saklanır; onChange(nextCsv). placeholder field içi ipucudur.
 * IncidentHistoryPage'teki TagInput'tan lift edildi; .tag-chips/.tag-chip CSS'i paylaşır.
 *
 * Giriş kutusu `.input` sınıfını KENDİ taşır — dış kapsayıcıya güvenmez. Eskiden sınıfsızdı ve
 * yalnız `.form-grid label input` kuralının kapsamında doğru görünüyordu; ilk kez bir `.form-grid`
 * DIŞINDA (Bildirim Grupları modalı) kullanılınca tarayıcı varsayılanı olarak çizildi. `.form-grid`
 * içindeki mevcut kullanımlar değişmez: o kural daha yüksek özgüllükte ve `.input`'u ezmeye
 * devam eder.
 *
 * Bekleyen metin bir ÖNİZLEME chip'i olarak çizilir (`tag-chip--pending`). Bu kozmetik değil,
 * bir tıklama hatasının çözümü: `onBlur` ile işleme alma chip satırını O ANDA büyütüyordu.
 * Kullanıcı değeri yazıp ALTTAKİ bir denetime (örn. "varsayılan yap" kutusu) bastığında sıra
 * şuydu — mousedown → blur → chip eklenir → altındaki her şey bir satır AŞAĞI kayar → mouseup
 * artık başka bir öğenin üstündedir → tarayıcı `click`'i ortak ataya verir ve kutu HİÇ
 * işaretlenmez; kullanıcı ikinci kez basmak zorunda kalırdı. Önizleme yeri baştan ayırdığı için
 * işleme alma anında yükseklik DEĞİŞMEZ ve tıklama hedefinde kalır.
 */
/**
 * `suggestions` (2026-09-22, kullanıcı isteği): takımın kullanımdaki etiketleri — kutuya odaklanınca / yazınca
 * süzülmüş açılır liste; seçilen chip olur. Seçilmiş olanlar listelenmez; eşleşme yoksa liste kapanır ve
 * Enter yine YENİ etiket ekler (eski davranış korunur). Ok tuşları + Enter ile seçim; Escape kapatır.
 * Öğeler string ya da { name, count } olabilir (count sağda soluk sayı).
 */
export default function TagInput({ label, value, onChange, disabled, placeholder, suggestions = null, suggestLabel = null }) {
  const t = useT()
  const [text, setText] = useState('')
  const [hues, setHues] = useState({})
  const tags = (value || '').split(',').map(s => s.trim()).filter(Boolean)
  // Görünen ama rengi olmayan etiketlere rastgele ton ata, o oturumda stabil kalsın.
  useEffect(() => {
    setHues(prev => {
      let changed = false
      const next = { ...prev }
      for (const tag of tags) if (next[tag] == null) { next[tag] = randomHue(); changed = true }
      return changed ? next : prev
    })
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps
  const add = () => {
    const v = text.trim()
    if (v && !tags.some(x => x.toLowerCase() === v.toLowerCase())) {
      setHues(prev => ({ ...prev, [v]: randomHue() }))
      onChange([...tags, v].join(', '))
    }
    setText('')
  }
  const remove = (tag) => onChange(tags.filter(x => x !== tag).join(', '))
  const pending = text.trim()
  // ── Öneri listesi ─────────────────────────────────────────────────────────────
  const [open, setOpen] = useState(false)
  const [hi, setHi] = useState(0)
  const listRef = useRef(null)
  const pool = useMemo(() => (Array.isArray(suggestions) ? suggestions : [])
    .map(x => (typeof x === 'string' ? { name: x, count: null } : { name: String(x?.name ?? ''), count: x?.count ?? null }))
    .filter(x => x.name), [suggestions])
  const matches = useMemo(() => {
    if (!pool.length) return []
    const q = pending.toLowerCase()
    const taken = new Set(tags.map(x => x.toLowerCase()))
    return pool.filter(x => !taken.has(x.name.toLowerCase()) && (!q || x.name.toLowerCase().includes(q))).slice(0, 50)
  }, [pool, pending, value]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setHi(0) }, [pending, matches.length])
  const pick = (name) => {
    if (!tags.some(x => x.toLowerCase() === name.toLowerCase())) {
      setHues(prev => ({ ...prev, [name]: randomHue() }))
      onChange([...tags, name].join(', '))
    }
    setText('')
  }
  const showList = open && !disabled && matches.length > 0
  // Yinelenen girdi `add` tarafından zaten eklenmiyor; önizlemesi de gösterilmez. Böylece
  // "önizlemede görünen ne ise işleme alınan odur" eşitliği — dolayısıyla yükseklik
  // kararlılığı — her iki dalda da korunur.
  const pendingIsNew = !!pending && !tags.some(x => x.toLowerCase() === pending.toLowerCase())
  return (
    <label className="full-width">
      {label && <span>{label}</span>}
      {!disabled && (
        <span className="tag-input-wrap">
          <input type="text" className="input" value={text} placeholder={placeholder}
            role={pool.length ? 'combobox' : undefined} aria-expanded={pool.length ? showList : undefined} aria-autocomplete={pool.length ? 'list' : undefined}
            onChange={e => { setText(e.target.value); setOpen(true) }}
            onFocus={() => setOpen(true)}
            onBlur={() => { setOpen(false); add() }}
            onKeyDown={e => {
              if (showList && e.key === 'ArrowDown') { e.preventDefault(); setHi(h => Math.min(h + 1, matches.length - 1)); return }
              if (showList && e.key === 'ArrowUp') { e.preventDefault(); setHi(h => Math.max(h - 1, 0)); return }
              if (e.key === 'Escape') { setOpen(false); return }
              if (e.key === 'Enter' || e.key === ',') {
                e.preventDefault()
                // Liste açıkken Enter vurgulanan öneriyi alır; yazılan metin tam eşleşmiyorsa bile mevcut etiketi tercih et
                if (showList && matches[hi]) pick(matches[hi].name); else add()
              }
            }} />
          {showList && (
            <ul className="tag-suggest" role="listbox" ref={listRef} aria-label={suggestLabel || undefined}>
              {matches.map((m, i) => (
                <li key={m.name} role="option" aria-selected={i === hi}
                  className={`tag-suggest-item${i === hi ? ' is-active' : ''}`}
                  // mousedown'da blur ENGELLENİR: aksi hâlde önce blur→add yazılan metni chip yapar, sonra tıklama boşa düşer
                  onMouseDown={e => e.preventDefault()}
                  onMouseEnter={() => setHi(i)}
                  onClick={() => pick(m.name)}>
                  <span className="tag-suggest-name">{m.name}</span>
                  {m.count != null && <span className="tag-suggest-count">{m.count}</span>}
                </li>
              ))}
            </ul>
          )}
        </span>
      )}
      {(tags.length > 0 || pendingIsNew) && (
        <div className="tag-chips">
          {tags.map(tag => {
            const h = hues[tag] ?? tagHue(tag)
            return (
              <span key={tag} className="tag-chip"
                style={{ background: `hsl(${h},70%,93%)`, color: `hsl(${h},65%,30%)`, borderColor: `hsl(${h},70%,78%)` }}>
                {tag}
                {/* Ad hem çevrilir hem ETİKETİ taşır: sabit "remove" 8 etiketli bir kayıtta
                    8 özdeş düğme demekti (hangisinin kaldırılacağı duyulmuyordu) ve TR
                    arayüzde İngilizce okunuyordu. */}
                {!disabled && <button type="button" className="tag-chip-x" aria-label={t('tag.removeTag', tag)}
                  style={{ color: `hsl(${h},60%,38%)` }} onClick={() => remove(tag)}>×</button>}
              </span>
            )
          })}
          {pendingIsNew && (
            <span className="tag-chip tag-chip--pending">
              {pending}
              {/* Gerçek chip ile AYNI öğe türü: farklı bir etiket kullanmak (span) düğme
                  yazı tipi kalıtımı yüzünden birkaç piksellik genişlik farkı üretir ve
                  önizlemenin ayırdığı yer tam oturmaz. */}
              <button type="button" className="tag-chip-x" tabIndex={-1} aria-label={t('tag.discardPending')}
                // mousedown'da blur ENGELLENİR: aksi halde metin önce chip'e dönüşür, bu
                // düğme kaybolur ve tıklama boşa düşerdi.
                onMouseDown={e => e.preventDefault()}
                onClick={() => setText('')}>×</button>
            </span>
          )}
        </div>
      )}
    </label>
  )
}
