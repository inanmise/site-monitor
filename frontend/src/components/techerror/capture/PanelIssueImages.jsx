import { useCallback, useMemo, useRef } from 'react'
import { useT } from '../../../i18n/index.jsx'
import { CollapsibleCard } from '../SectionCollapse.jsx'
import { ImageSlotTile } from '../ReportImages.jsx'
import { ISSUE_IMAGES, issueDisplayTitle, issueOptions, issueSlotId } from '../techErrorModel.js'
import { postImagesChanged } from './imageSync.js'

const EMPTY = Object.freeze([])

/** Görsel alan anomali bölümü mü (Görsel panelinde konu görselleri listelenir)? */
export function panelIssueSection(section, lang) {
  return section?.kind === 'issues' && issueOptions(section, lang).allowImages
}

/**
 * GÖRSEL PANELİNDE ANOMALİ KONU GÖRSELLERİ (2026-10-18, ekrandan görsel alma × anomali konuları): görsel alan bir anomali
 * bölümünün KAYITLI konuları (panel raporu sunucudan okur — sunucu da yalnız kayıtlı konuya görsel alır) konu başlığının
 * altında, düzenleyicideki yuvanın AYNISIYLA (`ImageSlotTile`: yükleme, sürükle-bırak, yapıştırma, ekrandan alma, değiştirme
 * / silme, büyük görünüm). İkinci yuva ilki dolunca açılır (düzenleyiciyle aynı). Konu eklemek, açıklama ve görsel açıklaması
 * yazmak ana penceredeki rapordan (değer kaydı); panel taslak tutmaz, bu yüzden `beforeUpload` yok. Her değişiklik öteki
 * pencerelere duyurulur (ImagesBlock gibi).
 */
export default function PanelIssueImages({ section, issues = EMPTY, reportId, images = EMPTY, uiLang, readOnly, onImagesChange, resolve }) {
  const t = useT()
  const sid = section.id
  const pattern = section.item_title
  const notifyChange = useCallback((fn) => { onImagesChange(fn); postImagesChanged(reportId) }, [onImagesChange, reportId])
  // Rapor toplamı (40 MB) yükleme ANINDA okunur — hazırlama sürerken başka yuvaya yüklenen görsel de sayılır
  const imagesRef = useRef(images)
  imagesRef.current = images
  const otherBytes = (slotId) => (imagesRef.current || []).reduce((a, i) => a + (i.slot_id === slotId ? 0 : (i.size_bytes || 0)), 0)
  const bySlot = useMemo(() => new Map(images.map((i) => [i.slot_id, i])), [images])
  return (
    <CollapsibleCard sectionId={sid} slot="te-panel-issue-images" title={resolve(section.title)} contentClassName="flex flex-col gap-4"
      hint={<p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('te.cap.panelTopicsHint')}</p>}>
      {issues.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-panel-no-topics">{t('te.cap.panelNoTopics')}</p>
      ) : (
        <ol className="m-0 flex list-none flex-col gap-4 p-0">
          {issues.map((iss, idx) => {
            const topic = issueDisplayTitle(iss, pattern) || t('te.iss.topicN', idx + 1)
            const slots = Array.from({ length: ISSUE_IMAGES }, (_, k) => {
              const id = issueSlotId(sid, iss.id, k + 1)
              return { id, n: k + 1, image: bySlot.get(id) || null }
            })
            return (
              <li key={iss.id} className="flex min-w-0 flex-col gap-3 rounded-xl border p-3" data-slot="te-panel-issue" data-issue={iss.id}>
                <h4 className="m-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere]" data-slot="te-panel-issue-title">{topic}</h4>
                {slots.map((s, k) => {
                  // İkinci yuva ilki dolunca açılır; dolu ikinci yuva her zaman görünür (düzenleyiciyle aynı)
                  if (k > 0 && !slots[k - 1].image && !s.image) return null
                  return (
                    <ImageSlotTile key={s.id} slot={{ id: s.id, label: t('te.iss.imageN', s.n), width: 'full' }} image={s.image}
                      reportId={reportId} resolve={(x) => x} uiLang={uiLang} readOnly={readOnly} onImagesChange={notifyChange}
                      hideWidth contextLabel={topic} otherBytes={() => otherBytes(s.id)} inPanel />
                  )
                })}
              </li>
            )
          })}
        </ol>
      )}
    </CollapsibleCard>
  )
}
