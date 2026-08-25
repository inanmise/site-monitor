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
  return (
    <label className="full-width">
      {label && <span>{label}</span>}
      {!disabled && (
        <input type="text" className="input" value={text} placeholder={placeholder}
          onChange={e => setText(e.target.value)} onBlur={add}
          onKeyDown={e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } }} />
      )}
      {tags.length > 0 && (
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
        </div>
      )}
    </label>
  )
}
