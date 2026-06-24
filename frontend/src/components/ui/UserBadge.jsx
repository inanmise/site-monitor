import { useState } from 'react'
import { useUserDirectory } from './UserDirectory.jsx'

// Deterministik avatar tonu + baş harfler (resim yoksa fallback). Diğer bileşenler de kullanabilir.
export function avatarBg(s) {
  let h = 0
  for (let i = 0; i < (s || '').length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return `hsl(${h} 45% 52%)`
}
export function initialsOf(name, username) {
  const base = (name || username || '?').trim()
  const p = base.split(/\s+/)
  return (((p[0]?.[0] || '') + (p[1]?.[0] || '')).toUpperCase()) || base[0]?.toUpperCase() || '?'
}

/**
 * Proje geneli kullanıcı rozeti: avatar (AD foto + baş-harf fallback) + ad-soyad (+ kimlik alt satırı).
 * `username` ve/veya `email` verilir; `userId`/`displayName` verilmezse UserDirectory'den (username→
 * sonra email) çözülür. Böylece username YA DA e-posta görünen her yer ad-soyad+resme dönüşür.
 *
 * props:
 *  - username | email (en az biri) — dizinden çözüm anahtarı + fallback metin
 *  - userId, displayName (opsiyonel) — verilirse dizine bakılmaz
 *  - inline: tek satır (yalnız ad); showUsername: ad altında kimlik (default true, inline'da yok)
 *  - size: 'sm'|'md'; count: sağda sayı; systemLabel: 'system'/boş actor metni
 */
export default function UserBadge({
  username, email, userId, displayName, count,
  size = 'md', showUsername = true, inline = false, systemLabel,
}) {
  const dir = useUserDirectory()
  const [imgErr, setImgErr] = useState(false)

  // Sistem/boş actor → düz metin (avatar yok).
  if (username === 'system') return <span style={{ color: 'var(--text-muted)' }}>{systemLabel || 'Sistem'}</span>
  if (!username && !email && !displayName) return <span style={{ color: 'var(--text-muted)' }}>{systemLabel || '—'}</span>

  const needLookup = (userId == null || displayName == null)
  const resolved = needLookup ? (dir.lookup(username) || dir.lookupByEmail(email)) : null
  const id = userId != null ? userId : resolved?.id
  const ident = username || email
  const name = displayName || resolved?.display_name || ident
  const px = size === 'sm' ? 20 : 26
  const fs = size === 'sm' ? 9 : 11

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, minWidth: 0, maxWidth: '100%' }}>
      {id != null && !imgErr ? (
        <img src={`/api/users/${id}/photo`} alt="" onError={() => setImgErr(true)}
          style={{ width: px, height: px, borderRadius: '50%', objectFit: 'cover', flexShrink: 0 }} />
      ) : (
        <span style={{ width: px, height: px, borderRadius: '50%', flexShrink: 0, color: '#fff',
          fontSize: fs, fontWeight: 700, display: 'inline-flex', alignItems: 'center',
          justifyContent: 'center', background: avatarBg(ident || name) }}>{initialsOf(name, ident)}</span>
      )}
      {inline ? (
        <span style={{ minWidth: 0, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</span>
      ) : (
        <span style={{ minWidth: 0, lineHeight: 1.2 }}>
          <span style={{ fontWeight: 600 }}>{name}</span>
          {showUsername && ident && name !== ident && (
            <span className="sys-mono sys-small" style={{ display: 'block', opacity: 0.6, fontSize: '.78em' }}>{ident}</span>
          )}
        </span>
      )}
      {count != null && <span style={{ marginLeft: 'auto', fontWeight: 700, flexShrink: 0 }}>{count}</span>}
    </span>
  )
}
