package neth.iecal.curbox.hardcoded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BrowserUrlBarIdsTest {

    @Test
    fun vivaldiUsesChromiumUrlBarId() {
        val vivaldi = URL_BAR_ID_LIST["com.vivaldi.browser"]

        assertNotNull(vivaldi)
        assertEquals("com.vivaldi.browser:id/url_bar", vivaldi?.displayUrlBarId)
    }
}
