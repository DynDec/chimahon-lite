package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TableOfContentsTest {

    @Test
    fun `reads nested toc nav and skips landmarks and heading-only entries`() {
        val document = xml(
            """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="landmarks"><ol><li><a href="cover.xhtml">Cover</a></li></ol></nav>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="p-001.xhtml">表紙</a></li>
                    <li>
                      <span>Part 1</span>
                      <ol>
                        <li><a href="p-004.xhtml#top"> 第1話 </a></li>
                      </ol>
                    </li>
                  </ol>
                </nav>
              </body>
            </html>
            """,
        )

        assertEquals(
            listOf(
                TocLink("表紙", "p-001.xhtml", 0),
                TocLink("第1話", "p-004.xhtml#top", 1),
            ),
            parseNavToc(document),
        )
    }

    @Test
    fun `reads nested ncx nav points`() {
        val document = xml(
            """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="a" playOrder="1">
                  <navLabel><text>Chapter 1</text></navLabel>
                  <content src="Text/p1.xhtml"/>
                  <navPoint id="b" playOrder="2">
                    <navLabel><text>Section</text></navLabel>
                    <content src="Text/p2.xhtml"/>
                  </navPoint>
                </navPoint>
              </navMap>
            </ncx>
            """,
        )

        assertEquals(
            listOf(
                TocLink("Chapter 1", "Text/p1.xhtml", 0),
                TocLink("Section", "Text/p2.xhtml", 1),
            ),
            parseNcxToc(document),
        )
    }

    @Test
    fun `maps documents and images to page indices`() {
        val spine = listOf(
            SpineDocument("p1.xhtml", listOf("1.jpg")),
            SpineDocument("text.xhtml", emptyList()),
            SpineDocument("p2.xhtml", listOf("2.jpg", "3.jpg")),
            SpineDocument("end.xhtml", emptyList()),
        )
        val links = listOf(
            TocLink("Start", "p1.xhtml", 0),
            TocLink("Text", "text.xhtml", 0),
            TocLink("Image", "3.jpg", 1),
            TocLink("After last image", "end.xhtml", 0),
            TocLink("Missing", "nowhere.xhtml", 0),
        )

        assertEquals(
            listOf(
                TocEntry("Start", 0, 0),
                TocEntry("Text", 1, 0),
                TocEntry("Image", 2, 1),
            ),
            mapTocLinks(links, spine),
        )
    }

    @Test
    fun `reads comic info bookmarks in page order`() {
        val comicInfo = """
            <ComicInfo>
              <Pages>
                <Page Image="5" Bookmark="Chapter 2"/>
                <Page Image="0" Type="FrontCover" Bookmark="Cover"/>
                <Page Image="1"/>
                <Page Image="2" Bookmark="  "/>
                <Page Image="99" Bookmark="Out of range"/>
                <Page Image="x" Bookmark="Not a number"/>
              </Pages>
            </ComicInfo>
        """.trimIndent()

        assertEquals(
            listOf(
                TocEntry("Cover", 0, 0),
                TocEntry("Chapter 2", 5, 0),
            ),
            parseComicInfoToc(comicInfo.byteInputStream(), pageCount = 10),
        )
    }

    private fun xml(text: String) = Jsoup.parse(text.trimIndent(), "", Parser.xmlParser())
}
