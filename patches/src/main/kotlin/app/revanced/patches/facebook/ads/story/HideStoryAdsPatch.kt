package app.revanced.patches.facebook.ads.story

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import java.util.logging.Logger

@Suppress("unused")
val hideStoryAdsPatch = bytecodePatch(
    name = "Hide story ads",
    description = "Hides the ads in the Facebook app stories.",
) {
    compatibleWith("com.facebook.katana")

    apply {
        val log = Logger.getLogger("HideStoryAds")
        var applied = 0

        val targets = listOf<Pair<String, () -> MutableMethod>>(
            "attemptFetchMoreAds" to { fetchMoreAdsMethod },
            "attemptAdsInsertion" to { adsInsertionMethod },
        )

        targets.forEach { (label, method) ->
            runCatching { method().returnEarly() }
                .onSuccess {
                    applied++
                    log.info("Hide story ads: da vo hieu hoa $label")
                }
                .onFailure { log.warning("Hide story ads: khong tim thay $label (${it.message})") }
        }

        // Chi bao loi khi KHONG co ham nao khop (it nhat 1 ham la du de patch co tac dung).
        if (applied == 0) {
            throw PatchException("Hide story ads: khong tim thay lop AdBucketDataSourceUtil nao tren phien ban nay.")
        }
    }
}
