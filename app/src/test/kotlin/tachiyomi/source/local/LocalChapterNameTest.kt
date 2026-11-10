package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalChapterNameTest {

    @Test
    fun `bracketed notes are dropped with their brackets`() {
        assertEquals("踊り場にスカートが鳴る：1", LocalChapterName.clean("踊り場にスカートが鳴る：1【イラスト特典付】"))
        assertEquals("Series 3", LocalChapterName.clean("【期間限定】Series 3"))
        assertEquals("Series 3", LocalChapterName.clean("Series 【電子版】 3 【特典付き】"))
    }

    @Test
    fun `names without notes are only trimmed`() {
        assertEquals("Series v01", LocalChapterName.clean(" Series v01 "))
        assertEquals("Series [Digital] (v01)", LocalChapterName.clean("Series [Digital] (v01)"))
    }

    @Test
    fun `a name that is only a note is kept`() {
        assertEquals("【特典】", LocalChapterName.clean("【特典】"))
    }

    @Test
    fun `an unclosed bracket is left alone`() {
        assertEquals("Series 【特典 1", LocalChapterName.clean("Series 【特典 1"))
    }
}
