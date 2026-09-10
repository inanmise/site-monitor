import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Bekçi: hiçbir <img src> admin'e özel bir uca (/api/admin/...) bakmamalı.
 *
 * Neden: Yönetim panelindeki "Takımlar" ve "Kullanıcılar" sekmeleri USER rolüne de AÇIK
 * (AdminPanel.jsx, adminOnly:false). Bu sekmelerdeki avatarlar /api/admin/users/{id}/photo
 * çağırıyordu; uç requireAdminOrTeamAdmin ile korunduğu için USER rolündeki her ziyarette
 * ÜYE SAYISI KADAR ACCESS_DENIED/BLOCKED denetim kaydı üretiliyordu (2026-08 prod bulgusu:
 * tek saniyede 5+ kayıt, üstelik OFF HOURS anomali etiketiyle). Gerçek güvenlik olayları bu
 * gürültünün içinde kayboluyordu.
 *
 * Doğru uç /api/users/{id}/photo — UserDirectoryController'da tam bu amaçla açıldı: oturum
 * açmış herkese görünür, yalnız foto döner (rol/e-posta gibi hassas alan yok). Admin'e özel
 * uç yerinde duruyor; yalnız <img> tüketicileri authenticated uca bağlandı.
 *
 * Kural görsellere özgüdür: <img> tarayıcının kendi isteğidir, uygulamanın 403'ü yakalayıp
 * sessizce yutma şansı yoktur — her deneme denetim kaydına düşer. fetch/XHR ile yapılan
 * admin çağrıları bu testin kapsamı DIŞINDADIR (onlar zaten yetkiye göre koşuluyor).
 */
const SRC = path.resolve(__dirname, '..')
const SKIP_DIRS = new Set(['test'])

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(path.join(dir, entry.name), acc)
    } else if (/\.jsx?$/.test(entry.name)) {
      acc.push(path.join(dir, entry.name))
    }
  }
  return acc
}

/** src={`/api/admin/...`} veya src="/api/admin/..." — tek/çift tırnak ve template literal. */
const ADMIN_IMG_SRC = /src=\{?\s*[`'"]\/api\/admin\//

describe('img src yetki bekçisi', () => {
  const files = walk(SRC)

  it('kaynak ağacı taranabiliyor (boş liste = yanlış kök)', () => {
    expect(files.length).toBeGreaterThan(50)
  })

  it('hiçbir <img src> /api/admin/ ucuna bakmıyor (USER rolünde 403 gürültüsü üretir)', () => {
    const hits = []
    for (const file of files) {
      const lines = fs.readFileSync(file, 'utf8').split('\n')
      lines.forEach((line, i) => {
        if (!line.includes('<img') && !line.includes('src=')) return
        if (ADMIN_IMG_SRC.test(line)) {
          hits.push(`${path.relative(SRC, file)}:${i + 1} → ${line.trim()}`)
        }
      })
    }
    expect(hits, 'Avatar/görsel uçları /api/users/{id}/photo gibi authenticated bir uca '
      + 'bağlanmalı; admin uçları <img> ile tüketilmemeli').toEqual([])
  })

  it('kullanıcı avatarları authenticated foto ucunu kullanıyor', () => {
    const consumers = [
      'components/ui/TeamMemberCards.jsx',   // üye kartları TeamManager'dan buraya taşındı (2026-09-10, takım modalı)
      'components/admin/UserManager.jsx',
      'components/admin/UserEditModal.jsx',
      'components/ui/UserBadge.jsx',
    ]
    for (const rel of consumers) {
      const src = fs.readFileSync(path.join(SRC, rel), 'utf8')
      expect(src, `${rel} foto ucu`).toMatch(/\/api\/users\/\$\{[^}]+\}\/photo/)
    }
  })
})
