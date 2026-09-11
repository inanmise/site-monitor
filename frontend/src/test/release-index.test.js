import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Yayın indeksi tazelik/sağlık kapısı (whitepaper manifest deseninin eşleniği).
 *
 * `docs/releases/index.json` CI'da her release'te `scripts/gen-release-index.mjs --append` ile
 * büyür ve Dockerfile imaja /app/releases.json olarak kopyalar. Bu test dosyanın var, geçerli,
 * azalan sıralı, tavanlı ve gerçek kimlikten arınmış olduğunu kilitler. Kırmızıysa:
 * `node scripts/gen-release-index.mjs --full` (yerelde tam klon + tag'ler gerekir).
 */
const ROOT = path.resolve(__dirname, '..', '..', '..')
const FILE = path.join(ROOT, 'docs', 'releases', 'index.json')
const VERSION = fs.readFileSync(path.join(ROOT, 'VERSION'), 'utf8').trim()

// IdentityLeakGuardTest ile aynı bölünmüş-literal hilesi — test dosyası kendisi sızıntı olmasın.
const FORBIDDEN = ['akb' + 'ank', 'ak' + 'net', 'ocp' + 'int', 'dijit' + 'alsy']

const cmp = (a, b) => {
  const pa = a.split('.').map(Number), pb = b.split('.').map(Number)
  for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return pa[i] - pb[i]
  return 0
}

describe('docs/releases/index.json', () => {
  const raw = fs.readFileSync(FILE, 'utf8')
  const idx = JSON.parse(raw)

  it('şema 1, en az 600 sürüm, boyut < 2 MB (büyüme kararı için tavan)', () => {
    expect(idx.schema).toBe(1)
    expect(idx.releases.length).toBeGreaterThan(600)
    expect(idx.count).toBe(idx.releases.length)
    expect(raw.length).toBeLessThan(2 * 1024 * 1024)
  })

  it('azalan semver sıralı ve tekil; en yenisi VERSION dosyasından ileri değil', () => {
    const vs = idx.releases.map((r) => r.version)
    for (let i = 1; i < vs.length; i++) expect(cmp(vs[i - 1], vs[i]), `${vs[i - 1]} > ${vs[i]}`).toBeGreaterThan(0)
    expect(cmp(vs[0], VERSION)).toBeLessThanOrEqual(0)
  })

  it('her kayıt: tag, UTC tarih, tam commit, bump ∈ {initial,major,minor,patch}, changes ≤ 60', () => {
    for (const r of idx.releases) {
      expect(r.tag).toBe('v' + r.version)
      expect(r.releasedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/)
      expect(r.commit).toMatch(/^[0-9a-f]{40}$/)
      expect(['initial', 'major', 'minor', 'patch']).toContain(r.bump)
      expect(r.changes.length).toBeLessThanOrEqual(60)
      for (const c of r.changes) expect(c.subject.length).toBeLessThanOrEqual(200)
    }
  })

  it('gerçek kimlik, e-posta ve URL yok (scrub); skip-ci belirteci yok', () => {
    const lower = raw.toLowerCase()
    for (const bad of FORBIDDEN) expect(lower.includes(bad.toLowerCase()), bad).toBe(false)
    expect(raw).not.toMatch(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/)
    expect(raw).not.toMatch(/https?:\/\//)
    expect(raw).not.toMatch(/\[skip ci\]/i)
    expect(raw).not.toMatch(/chore\(release\)/)
  })
})
