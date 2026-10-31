package com.techgv.cineverse.ui

import androidx.compose.ui.text.LinkAnnotation
import com.techgv.cineverse.ui.components.linkifyUrls
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewLinksTest {
    private fun links(text: String) = linkifyUrls(text).let { linked ->
        assertEquals(text, linked.text)
        linked.getLinkAnnotations(0, linked.length).map { (it.item as LinkAnnotation.Url).url to text.substring(it.start, it.end) }
    }

    @Test fun textWithoutAUrlHasNoLinks() {
        assertEquals(emptyList(), links("No links here, just www.example.com and ftp://old.host."))
    }

    @Test fun everyHttpAndHttpsUrlBecomesALinkOverItsOwnText() {
        val urls = links("Full review: https://msbreviews.com/movie-reviews/verity-spoiler-free-review and http://a.io/x?y=1#z")
        assertEquals(listOf("https://msbreviews.com/movie-reviews/verity-spoiler-free-review", "http://a.io/x?y=1#z"), urls.map { it.first })
        assertEquals(urls.map { it.first }, urls.map { it.second })
    }

    @Test fun trailingPunctuationAndAnUnbalancedParenStayText() {
        assertEquals(listOf("https://a.io/review"), links("Read it at https://a.io/review.").map { it.first })
        assertEquals(listOf("https://a.io/b"), links("(see https://a.io/b), thanks!").map { it.first })
        assertEquals(listOf("https://en.wikipedia.org/wiki/Verity_(novel)"),
            links("Based on https://en.wikipedia.org/wiki/Verity_(novel).").map { it.first })
    }
}
