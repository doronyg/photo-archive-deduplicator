package com.doronyg.photoarchivededuplicator

import kotlin.test.Test
import kotlin.test.assertTrue

class AppTitleTest {
    @Test
    fun appTitleStartsWithAppNameAndIncludesPlatform() {
        val title = appTitle()
        assertTrue(title.startsWith(APP_NAME), "was: $title")
        assertTrue(title.contains(platformName()), "was: $title")
    }
}
