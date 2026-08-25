import { useState, useEffect } from 'react'

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
export default function TagInput({ label, value, onChange, disabled, placeholder }) {
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
  // Yinelenen girdi `add` tarafından zaten eklenmiyor; önizlemesi de gösterilmez. Böylece
  // "önizlemede görünen ne ise işleme alınan odur" eşitliği — dolayısıyla yükseklik
  // kararlılığı — her iki dalda da korunur.
  const pendingIsNew = !!pending && !tags.some(x => x.toLowerCase() === pending.toLowerCase())
  return (
    <label className="full-width">
      {label && <span>{label}</span>}
      {!disabled && (
        <input type="text" className="input" value={text} placeholder={placeholder}
          onChange={e => setText(e.target.value)} onBlur={add}
          onKeyDown={e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } }} />
      )}
      {(tags.length > 0 || pendingIsNew) && (
        <div className="tag-chips">
          {tags.map(tag => {
            const h = hues[tag] ?? tagHue(tag)
            return (
              <span key={tag} className="tag-chip"
                style={{ background: `hsl(${h},70%,93%)`, color: `hsl(${h},65%,30%)`, borderColor: `hsl(${h},70%,78%)` }}>
                {tag}
                {!disabled && <button type="button" className="tag-chip-x" aria-label="remove"
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
              <button type="button" className="tag-chip-x" tabIndex={-1} aria-label="discard"
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
