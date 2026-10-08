import MDEditor, { commands } from '@uiw/react-md-editor/common'
import { createMdEditor } from './createMdEditor.jsx'

/**
 * Vurgulu editör (tembel parça) — envanter "Değişiklik açıklaması" yazarken markdown kaynağını renklendirir
 * (`highlightEnable` varsayılan açık). `common` girişi tüm Prism dillerini (~300) değil yaygın ~36 dili yükler:
 * markdown vurgusu ve önizlemedeki ham HTML aynen kalır, yalnız nadir dillerdeki kod blokları renksiz çizilir.
 */
export default createMdEditor(MDEditor, commands)
