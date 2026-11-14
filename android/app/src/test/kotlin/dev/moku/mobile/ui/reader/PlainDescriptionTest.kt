package dev.moku.mobile.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainDescriptionTest {
    @Test
    fun stripsMarkdown() {
        val raw = "A story.\n\n**Alternative Names:**\n- Peek\n- [Other](https://example.com)\n"
        assertEquals("A story.\n\nAlternative Names:\n• Peek\n• Other", plainDescription(raw))
    }

    @Test
    fun leavesPlainTextAlone() {
        assertEquals("2 * 3 - 1 = 5", plainDescription("2 * 3 - 1 = 5"))
    }
}
