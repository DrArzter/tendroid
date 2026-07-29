package com.motandrwall.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryCatalogTest {
    @Test
    fun parsesAnEmptyVersionOneCatalog() {
        assertEquals(emptyList<GalleryWallpaper>(), parseGalleryCatalog("""{"schemaVersion":1,"wallpapers":[]}"""))
    }

    @Test
    fun acceptsAssetsFromTheGalleryRepository() {
        val url = "https://raw.githubusercontent.com/DrArzter/tendroid-gallery/main/wallpapers/alice.neon/wallpaper.tendies"

        assertEquals(url, validateGalleryAssetUrl(url, setOf("tendies")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAssetsFromAnotherRepository() {
        validateGalleryAssetUrl(
            "https://raw.githubusercontent.com/attacker/repo/main/wallpaper.tendies",
            setOf("tendies"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalInAssetUrls() {
        validateGalleryAssetUrl(
            "https://raw.githubusercontent.com/DrArzter/tendroid-gallery/main/wallpapers/../secret.tendies",
            setOf("tendies"),
        )
    }
}
