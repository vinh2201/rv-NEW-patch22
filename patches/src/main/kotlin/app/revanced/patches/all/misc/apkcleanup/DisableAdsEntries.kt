package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.iface.Method
import org.w3c.dom.Element
import java.util.logging.Logger

// region Manifest entries

/** Permissions that only exist for ad SDKs. */
private val AD_PERMISSIONS = setOf(
    "com.android.vending.CHECK_LICENSE",
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_TOPICS",
    "android.permission.ACCESS_ADSERVICES_CUSTOM_AUDIENCE",
    "android.permission.AD_SERVICES_CONFIG",
)

/**
 * Permissions that are NOT ad related. Many apps use them for real features, and calling the matching API without the
 * permission throws a SecurityException (for example CHANGE_NETWORK_STATE, RUN_USER_INITIATED_JOBS), so they are only
 * removed when the option "Remove generic permissions" is on.
 */
private val GENERIC_PERMISSIONS = setOf(
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.DETECT_SCREEN_CAPTURE",
    "android.permission.DETECT_SCREEN_RECORDING",
    "android.permission.KILL_BACKGROUND_PROCESSES",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.RUN_USER_INITIATED_JOBS",
)

/**
 * Components (activity, service, receiver, provider, meta-data, activity-alias) whose name or value starts with one of these
 * are removed. "com.facebook.bugreporter" is not an ad SDK: it is the Facebook bug report screen, listed on purpose to drop its upload/telemetry.
 */
private val AD_COMPONENT_PREFIXES = listOf(
    "com.google.ads",
    "com.google.android.gms.ads",
    "com.google.android.tv.ads",
    "com.google.android.gms.measurement",
    "com.facebook.ads",
    "com.facebook.adspayments",
    "com.facebook.adscomposer",
    "com.facebook.adsconsentvalue",
    "com.facebook.bugreporter",
    "com.facebook.adinterfaces",
    "com.facebook.adspreviewinjection",
    "com.facebook.adpreview",
    "com.facebook.adsexperiencetool",
    "com.facebook.feed.platformads",
    "com.facebook.messaging.business.ads",
    "com.facebook.messaging.nativepagereply.adcreation",
    "com.facebook.messaging.professionalmode.adscreation",
    "com.facebook.partnershipads",
    "com.applovin",
    "com.mbridge.msdk",
    "com.inmobi.ads",
    "com.unity3d.ads",
    "com.unity3d.services.ads",
    "com.vungle",
    "com.ironsource",
    "com.bytedance.sdk",
    "com.anythink",
    "com.qq.e.",
    "com.baidu.mobads",
    "com.kwad.sdk",
    "com.sigmob",
    "com.tradplus",
    "com.pangle",
    "com.adtima.ads",
)

private val MANIFEST_TAGS = listOf(
    "uses-permission",
    "uses-permission-sdk-23",
    "uses-permission-sdk-m",
    "uses-library",
    "property",
    "meta-data",
    "provider",
    "service",
    "receiver",
    "activity",
    // An alias whose target activity was removed makes the package fail to parse (app not installed).
    "activity-alias",
)

@Suppress("unused")
val removeAdManifestEntriesPatch = resourcePatch(
    name = "Remove ad manifest entries",
    description = "Removes ad SDK permissions, services, receivers, providers, activities, libraries and metadata from the manifest.",
    use = false,
) {
    val removeGenericPermissions by booleanOption(
        name = "Remove generic permissions",
        description = "Also remove permissions that are not ad related (CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE, " +
            "KILL_BACKGROUND_PROCESSES, RUN_USER_INITIATED_JOBS, ...). Risky: features that use them can crash.",
        default = false,
    )

    apply {
        val logger = Logger.getLogger(this::class.java.name)
        val permissions = if (removeGenericPermissions == true) AD_PERMISSIONS + GENERIC_PERMISSIONS else AD_PERMISSIONS
        var removed = 0

        document("AndroidManifest.xml").use { document ->
            for (tag in MANIFEST_TAGS) {
                val nodes = document.getElementsByTagName(tag)

                // The list is live, so go backwards to keep the indexes valid while removing.
                for (i in nodes.length - 1 downTo 0) {
                    val node = nodes.item(i) as? Element ?: continue

                    val name = node.getAttribute("android:name")
                    val value = node.getAttribute("android:value")
                    val target = node.getAttribute("android:targetActivity")

                    val remove = when (tag) {
                        "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m" -> name in permissions
                        "uses-library" -> name == "android.ext.adservices"
                        "property" -> name == "android.adservices.AD_SERVICES_CONFIG"
                        else -> AD_COMPONENT_PREFIXES.any { name.startsWith(it) || value.startsWith(it) || target.startsWith(it) }
                    }

                    if (remove) {
                        node.parentNode?.removeChild(node)
                        removed++
                        logger.fine("Removed <$tag> ${name.ifEmpty { value.ifEmpty { target } }}")
                    }
                }
            }
        }

        logger.info("Removed $removed ad entries from AndroidManifest.xml")
    }
}

// endregion

// region Ad SDK calls

/** Smali type prefixes of the ad / analytics SDK packages whose entry points are neutralized. */
private val AD_PACKAGES = listOf(
    "Lcom/applovin/",
    "Lcom/facebook/ads/",
    "Lcom/facebook/appevents/",
    "Lcom/facebook/feed/ads/",
    "Lcom/facebook/feed/platformads/",
    "Lcom/fyber/inneractive/sdk/",
    "Lcom/google/ads/",
    "Lcom/google/android/gms/ads/",
    "Lcom/google/firebase/crashlytics/",
    "Lcom/mbridge/msdk/",
    "Lcom/inmobi/ads/",
    "Lcom/smaato/sdk/",
    "Lcom/tradplus/ads/",
    "Lcom/unity3d/ads/",
    "Lcom/unity3d/analytics/",
    "Lcom/unity3d/ironsourceads/",
    "Lcom/vungle/",
    "Lcom/ironsource/",
    "Lcom/bytedance/sdk/",
    "Lcom/anythink/",
    "Lcom/qq/e/",
    "Lcom/baidu/mobads/",
    "Lcom/kwad/sdk/",
    "Lcom/sigmob/",
    "Lcom/pangle/",
    "Lcom/huawei/hms/ads/",
    "Lcom/mixpanel/android/mpmetrics/",
    "Lcom/adtima/",
)

/** Methods returning void that become "return-void". */
private val VOID_METHOD_NAMES = setOf(
    "loadAd",
    "loadAds",
    "load",
    "show",
    "showAd",
    "fetchAd",
    "init",
    "start",
    "initSDK",
    "initialize",
    "initializeSdk",
    "loadSplashAd",
    "loadRewardVideoAd",
    "loadInterstitialAd",
    "loadBannerAd",
    "loadNativeAd",
    "loadFeedAd",
    "loadNativeExpressAd",
    "loadBannerExpressAd",
    "loadDrawFeedAd",
    "loadExpressDrawFeedAd",
    "loadSplashScreenAd",
    "requestBannerAd",
    "requestInterstitialAd",
    "requestNativeAd",
    "showSplashView",
    "showSplashClickEyeView",
    "showSplashCardView",
    "showRewardVideoAd",
    "showFullScreenVideoAd",
    "showInterstitial",
    "showInterstitialAd",
    "showSplashMiniWindow",
    "showSplashMiniWindowIfNeeded",
    "showNativeAd",
    "negativeFeedback",
    "startLoadAd",
)

/** Methods returning boolean that become "return false" (ad is not loaded, SDK is not ready). */
private val FALSE_METHOD_NAMES = setOf("isAdLoaded", "isInitSuccess", "isSdkReady")

/** Methods returning an object that become "return null". */
private val NULL_METHOD_NAMES = setOf(
    "getSplashView",
    "getSplashClickEyeView",
    "getSplashCardView",
    "getBannerView",
    "getFeedView",
)

/** True when the method is one of the entry points to neutralize. Abstract/native methods and constructors are never touched. */
private fun Method.isAdEntryPoint(): Boolean {
    val registers = implementation?.registerCount ?: return false // no body: abstract or native

    if (name == "<init>" || name == "<clinit>") return false

    return when {
        returnType == "V" -> name in VOID_METHOD_NAMES
        // The replacement needs v0 to hold the constant.
        returnType == "Z" -> name in FALSE_METHOD_NAMES && registers >= 1
        returnType.startsWith("L") -> name in NULL_METHOD_NAMES && registers >= 1
        else -> false
    }
}

@Suppress("unused")
val disableAdSdkCallsPatch = bytecodePatch(
    name = "Disable ad SDK calls",
    description = "Makes the load/show/init/start entry points of bundled ad SDK packages do nothing.",
    use = false,
) {
    apply {
        val logger = Logger.getLogger(this::class.java.name)

        var classCount = 0
        var methodCount = 0

        // Copy the list first: getOrReplaceMutable() replaces classes inside classDefs.
        classDefs.filter { classDef -> AD_PACKAGES.any { classDef.type.startsWith(it) } }.forEach { classDef ->
            // Only turn the class into a mutable one when something in it is going to change.
            if (classDef.methods.none { it.isAdEntryPoint() }) return@forEach

            val mutableClass = classDefs.getOrReplaceMutable(classDef)

            mutableClass.methods.filter { it.isAdEntryPoint() }.forEach { method ->
                method.returnEarly()
                methodCount++

                logger.fine("Disabled ${mutableClass.type}->${method.name}")
            }

            classCount++
        }

        logger.info("Disabled $methodCount ad SDK methods in $classCount classes")
    }
}

// endregion
