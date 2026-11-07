package app.revanced.patches.facebook.ads.story

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

// Linh hoat: chi can ten goc (redex) CHUA "AdBucket" va tu khoa cua ham, khong can khop tuyet doi.
// Vi du ten that: AdBucketDataSourceUtil$attemptAdsInsertion$1 , ...$attemptFetchMoreAds$1
internal val BytecodePatchContext.adsInsertionMethod by runMethod(keyword = "attemptAdsInsertion")

internal val BytecodePatchContext.fetchMoreAdsMethod by runMethod(keyword = "attemptFetchMoreAds")

internal fun runMethod(keyword: String) = gettingFirstMethodDeclaratively {
    name("run")
    returnType("V")
    parameterTypes()
    custom {
        immutableClassDef.anyField {
            val value = (initialValue as? StringEncodedValue)?.value
            name == "__redex_internal_original_name" &&
                value != null &&
                value.contains("AdBucket") &&
                value.contains(keyword)
        }
    }
}
