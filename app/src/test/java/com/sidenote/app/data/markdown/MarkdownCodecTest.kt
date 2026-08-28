package com.sidenote.app.data.markdown

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

class MarkdownCodecTest {
    private val codec = MarkdownCodec()

    @Test
    fun createsHeaderAndFormatsMultilineCapture() {
        val result = codec.appendEntry(
            codec.createDailyFile(LocalDate.parse("2026-08-27")),
            NewCapture(LocalTime.of(14, 30), "@Home-Renovation First line\nSecond line"),
        )

        assertThat(result).isEqualTo(
            "# 2026-08-27\n\n- [ ] **14:30** @Home-Renovation First line\n  Second line\n",
        )
    }

    @Test
    fun parsesHebrewAndMixedBidiTextWithItsProjectTokens() {
        val source = "# 2026-08-27\n\n- [ ] **14:30** לקנות צבע @שיפוץ-הבית and @SideNote\n"

        val entry = codec.parse(LocalDate.parse("2026-08-27"), source).entries.single()

        assertThat(entry.text).isEqualTo("לקנות צבע @שיפוץ-הבית and @SideNote")
        assertThat(entry.projects.map { it.display }).containsExactly("שיפוץ-הבית", "SideNote")
    }

    @Test
    fun parsesCrLfAndRewritesOnlyTheVerifiedCheckbox() {
        val source = "# 2026-08-27\r\n\r\n- [ ] **09:00** One\r\n\r\nUnrelated [ ] text\r\n"
        val parsed = codec.parse(LocalDate.parse("2026-08-27"), source)

        val result = codec.rewriteProcessed(source, parsed.entries.single().source, true)

        assertThat(result).isEqualTo(
            RewriteResult.Updated(
                "# 2026-08-27\r\n\r\n- [x] **09:00** One\r\n\r\nUnrelated [ ] text\r\n",
            ),
        )
    }

    @Test
    fun ignoresMalformedTasksAndStopsAtUnindentedContinuationBoundary() {
        val source = """
            # 2026-08-27

            - [ ] **9:00** malformed time
            - [y] **10:00** malformed checkbox
            - [ ] **11:00** Valid first line
              Valid continuation
            Unindented material
              Not part of the task
        """.trimIndent() + "\n"

        val entries = codec.parse(LocalDate.parse("2026-08-27"), source).entries

        assertThat(entries).hasSize(1)
        assertThat(entries.single().text).isEqualTo("Valid first line\nValid continuation")
        assertThat(entries.single().source.rawTask).isEqualTo(
            "- [ ] **11:00** Valid first line\n  Valid continuation",
        )
    }

    @Test
    fun duplicateTimestampsUseTheExactSourceOrdinalForRewrite() {
        val source = """
            # 2026-08-27

            - [ ] **09:00** First
            - [ ] **09:00** Second
        """.trimIndent() + "\n"
        val second = codec.parse(LocalDate.parse("2026-08-27"), source).entries[1]

        val result = codec.rewriteProcessed(source, second.source, true)

        assertThat(result).isEqualTo(
            RewriteResult.Updated(
                "# 2026-08-27\n\n- [ ] **09:00** First\n- [x] **09:00** Second\n",
            ),
        )
    }

    @Test
    fun midnightEntryKeepsTheSuppliedDailyFileDate() {
        val date = LocalDate.parse("2026-08-28")
        val source = codec.appendEntry(codec.createDailyFile(date), NewCapture(LocalTime.MIDNIGHT, "Midnight"))

        val entry = codec.parse(date, source).entries.single()

        assertThat(entry.date).isEqualTo(date)
        assertThat(entry.time).isEqualTo(LocalTime.MIDNIGHT)
    }

    @Test
    fun sourceMismatchReturnsConflictWithoutChangingText() {
        val original = "# 2026-08-27\n\n- [ ] **09:00** Original\n"
        val source = codec.parse(LocalDate.parse("2026-08-27"), original).entries.single().source
        val externallyChanged = "# 2026-08-27\n\n- [ ] **09:00** Changed elsewhere\n"

        assertThat(codec.rewriteProcessed(externallyChanged, source, true))
            .isEqualTo(RewriteResult.Conflict)
        assertThat(externallyChanged).isEqualTo("# 2026-08-27\n\n- [ ] **09:00** Changed elsewhere\n")
    }
}
