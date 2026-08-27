import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'

/**
 * DİLLER-ARASI BEKÇİ: değişiklik geçmişindeki HER kaynak türü arayüzde tanınmalı.
 *
 * Bu tam olarak yaşandı: backend'e PAGESPEED türü eklendi, arayüz üç yerde birden güncellenmedi
 * ve ekranda kart adı olarak ham anahtar göründü — `chg.kind.pagespeed`. Sessiz bozulma:
 * hiçbir test kırılmadı, hiçbir hata çıkmadı; tür süzgeci listesinde de yoktu, yani kullanıcı
 * o türü açılır listeden HİÇ seçemiyordu ve kartı jenerik ikonla çiziliyordu.
 *
 * Tek kaynak backend'deki KIND_BY_PATH; sözleşme testi (MonitorHistoryServiceTest) onu zaten
 * sayıyor. Eksik olan halka arayüz tarafıydı: yeni bir tür eklenince ÜÇÜ de gerekiyor —
 * süzgeç listesi (KINDS), iki dilde etiket, ve kart ikonu.
 */
const BACKEND = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor')
const FRONT = path.resolve(__dirname, '..')

const read = (p) => fs.readFileSync(p, 'utf8')

/** KIND_BY_PATH = Map.ofEntries(Map.entry("port", PORT), ...) → ['port', 'dns', ...] */
function backendKinds() {
  const src = read(path.join(BACKEND, 'service/MonitorHistoryService.java'))
  const m = /KIND_BY_PATH\s*=\s*Map\.ofEntries\(([\s\S]*?)\);/.exec(src)
  if (!m) return []
  return [...m[1].matchAll(/Map\.entry\("([^"]+)"/g)].map(x => x[1])
}

/** const KINDS = ['port', ...] */
function frontendKinds() {
  const src = read(path.join(FRONT, 'components/admin/MonitorChangesConsole.jsx'))
  const m = /const KINDS\s*=\s*\[([\s\S]*?)\]/.exec(src)
  if (!m) return []
  return [...m[1].matchAll(/'([^']+)'/g)].map(x => x[1])
}

/** const ICONS = { http: Globe, ... } */
function iconKinds() {
  const src = read(path.join(FRONT, 'components/admin/ChangeKindCards.jsx'))
  const m = /const ICONS\s*=\s*\{([\s\S]*?)\}/.exec(src)
  if (!m) return []
  return [...m[1].matchAll(/(\w+)\s*:/g)].map(x => x[1])
}

describe('Değişiklik geçmişi tür listesi — backend ile senkron', () => {
  const kinds = backendKinds()

  it('backend KIND_BY_PATH okunabiliyor (boş liste = yol veya sözdizimi değişmiş)', () => {
    expect(kinds.length, 'KIND_BY_PATH okunamadı').toBeGreaterThan(8)
  })

  it('backend türlerinin TÜMÜ arayüzün süzgeç listesinde (KINDS)', () => {
    const front = frontendKinds()
    expect(front.length, 'KINDS okunamadı').toBeGreaterThan(8)
    expect(kinds.filter(k => !front.includes(k))).toEqual([])
  })

  it('arayüzde backend\'de OLMAYAN tür yok (ölü süzgeç seçeneği)', () => {
    expect(frontendKinds().filter(k => !kinds.includes(k))).toEqual([])
  })

  it('her türün TR ve EN etiketi var (yoksa ekranda ham anahtar görünür)', () => {
    expect(kinds.filter(k => !TR[`chg.kind.${k}`])).toEqual([])
    expect(kinds.filter(k => !EN[`chg.kind.${k}`])).toEqual([])
  })

  it('her türün kart ikonu var (yoksa jenerik ikona düşer, türler ayırt edilemez)', () => {
    const icons = iconKinds()
    expect(icons.length, 'ICONS okunamadı').toBeGreaterThan(8)
    expect(kinds.filter(k => !icons.includes(k))).toEqual([])
  })
})
