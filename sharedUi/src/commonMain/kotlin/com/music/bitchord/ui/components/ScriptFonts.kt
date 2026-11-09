package com.music.bitchord.ui.components

/** Whether [text] is written right-to-left. */
fun isRtlText(text: String): Boolean = text.any { character ->
    character in '\u0590'..'\u05FF' || // Hebrew
        character in '\u0600'..'\u06FF' || // Arabic
        character in '\u0700'..'\u074F' || // Syriac
        character in '\u0750'..'\u077F' || // Arabic Supplement
        character in '\u0780'..'\u07BF' || // Thaana
        character in '\u07C0'..'\u07FF' || // NKo
        character in '\u0800'..'\u083F' || // Samaritan
        character in '\u0840'..'\u085F' || // Mandaic
        character in '\u08A0'..'\u08FF' || // Arabic Extended-A
        character in '\uFB1D'..'\uFDFF' || // Hebrew/Arabic presentation forms A
        character in '\uFE70'..'\uFEFF'    // Arabic presentation forms B
}
