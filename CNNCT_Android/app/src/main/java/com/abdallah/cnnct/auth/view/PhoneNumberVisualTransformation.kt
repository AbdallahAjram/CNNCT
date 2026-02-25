package com.abdallah.cnnct.auth.view

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class PhoneNumberVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // Input `text` is expected to be digits only (or at least raw chars)
        // We want to format it as:
        // 2 digits -> "XX-"
        // 3+ digits -> "XX-XXXXXX"
        
        val digits = text.text
        val out = StringBuilder()
        
        // Map from transformed index to original index
        val offsetMap = mutableListOf<Int>()
        
        digits.forEachIndexed { index, char ->
            if (index == 2) {
                out.append("-")
                // The hyphen doesn't exist in original, so we map it to the current index
                // Wait, logic for OffsetMapping usually needs us to map transformed -> original.
                // At index 2 in transformed (which is '-'), it corresponds to index 2 in original (the 3rd digit).
            }
            out.append(char)
        }
        
        // Let's refine the mapping logic.
        // Original: 12345
        // Formatted: 12-345
        // T0('1') -> O0
        // T1('2') -> O1
        // T2('-') -> O2
        // T3('3') -> O2
        // T4('4') -> O3
        
        val formatted = if (digits.length >= 3) {
            digits.substring(0, 2) + "-" + digits.substring(2)
        } else if (digits.length == 2) {
            digits + "-"
        } else {
            digits
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                // If cursor is at 0, 1, 2 -> returns 0, 1, 2
                // If cursor is at 3 (after 3rd char) -> formatted has hyphen at 2, so it's index 4?
                // 123 -> 12-3
                // idx=3 -> '3' is at 3 in formatted.
                
                if (offset <= 2) return offset
                return offset + 1
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 2) return offset
                return offset - 1
            }
        }
        
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}
