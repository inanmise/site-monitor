import { describe, it, expect } from 'vitest'
import { movedSummary } from '../components/admin/TeamDeleteImpactModal.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Takım taşıma bildirimi (2026-10-22, son inceleme FE-8): yalnız kanal / şablon / bakım penceresi olan takım
 * "Taşındı: 0 domain, 0 izleme, 0 kullanıcı" diyordu. Sunucunun saydığı her taşınan şey yazılır, sıfırlar yazılmaz.
 */
const tOf = (dict) => (key, ...args) => String(dict[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)]))

describe('movedSummary', () => {
  it('yalnız belge taşıyan takım: kanal + bakım penceresi yazılır, "0 domain" yazılmaz', () => {
    const msg = movedSummary({ domains: 0, monitors: 0, users: 0, te_channels: 2, te_reports: 7, maintenance_windows: 1 }, tOf(EN))
    expect(msg).toBe('Moved: 2 technical error report channels, 1 maintenance windows')
    expect(msg).not.toMatch(/\b0\b/)
  })

  it('karışık taşıma sıralı; Türkçe metin', () => {
    expect(movedSummary({ domains: 3, monitors: 2, users: 1, contacts: 4, open_alerts: 5 }, tOf(TR)))
      .toBe('Taşındı: 3 sertifika, 2 izleme, 1 kullanıcı, 4 eskalasyon kişisi, 5 açık alarm')
  })

  it('hiçbir şey taşınmadıysa / yanıt boşsa "taşınacak kayıt yoktu"', () => {
    expect(movedSummary({ domains: 0 }, tOf(EN))).toBe(EN['team.movedNothing'])
    expect(movedSummary(null, tOf(TR))).toBe(TR['team.movedNothing'])
  })

  it('her anahtarın TR ve EN metni var', () => {
    for (const k of ['domains', 'ug_domains', 'monitors', 'users', 'contacts', 'groups', 'te_channels', 'te_templates', 'maintenance_windows', 'open_alerts']) {
      expect(TR[`team.mv.${k}`]).toBeTruthy()
      expect(EN[`team.mv.${k}`]).toBeTruthy()
    }
  })
})
