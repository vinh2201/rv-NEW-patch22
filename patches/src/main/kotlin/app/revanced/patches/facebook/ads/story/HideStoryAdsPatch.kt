package app.revanced.patches.facebook.ads.story

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import java.util.logging.Logger

/*
 * Hide story ads  -  one self-contained file (no Fingerprints.kt needed; delete the old story/Fingerprints.kt).
 *
 * Covers Facebook 490.0.0.63.82 .. 585.0.0.47.54 with TWO independent layers. Whatever a build has gets patched, whatever
 * it lacks is skipped with a warning; the patch only fails when NO layer matched anything.
 *
 *   Layer 1  (verified present on 490 and 521): the story viewer's ad runnables, found through the original class name
 *            Redex leaves in the static field `__redex_internal_original_name`
 *            ("AdBucketDataSourceUtil$attemptFetchMoreAds$1" / "...$attemptAdsInsertion$1"). Their run() returns at once.
 *
 *   Layer 2  (ported from Hushfacebook/Morphe "Hide sponsored stories", which targets 585): the story bucket data sources.
 *            Each takes the organic bucket list and returns it with ad buckets spliced in; here each simply returns the
 *            list it was given (what Facebook itself does when a source has nothing to add). The sources are found by a
 *            string literal that is unique to each one, so no obfuscated name is used:
 *              "handling_inorganic_clash"                                   (inorganic clash resolver, newer builds)
 *              "uninsertedMainAdsQueue"                                     (AdBucketDataSource)
 *              "AdPaginatingBucketStaticInsertionDataSource.getBuckets"     (paginating inserter)
 *              "StoryViewerMidCardDataSource.getBuckets"                    (mid-card ads fetched while the viewer is open)
 *            On 521 the "uninsertedMainAdsQueue" interleave method is no longer anchored by that string, which is why
 *            Layer 1 stays.
 */

private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val REDEX_NAME_FIELD = "__redex_internal_original_name"
private const val REDEX_CLASS_MARKER = "AdBucket"
private val REDEX_RUNNABLE_KEYWORDS = listOf("attemptFetchMoreAds", "attemptAdsInsertion")

private val LIST_SOURCE_STRINGS = listOf(
    "handling_inorganic_clash",
    "uninsertedMainAdsQueue",
    "AdPaginatingBucketStaticInsertionDataSource.getBuckets",
    "StoryViewerMidCardDataSource.getBuckets",
)

/** A method found while only reading; changed later, so the class set is not modified during the search. */
private data class Target(
    val definingClass: String,
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val label: String,
)

private fun Method.toTarget(label: String) =
    Target(definingClass, name, parameterTypes.map { it.toString() }, returnType, label)

private fun MutableMethod.matches(target: Target) =
    name == target.name &&
        returnType == target.returnType &&
        parameterTypes.map { it.toString() } == target.parameterTypes

private fun BytecodePatchContext.mutableMethodOf(target: Target): MutableMethod? {
    val classDef = classDefs[target.definingClass] ?: return null
    return classDefs.getOrReplaceMutable(classDef).methods.firstOrNull { it.matches(target) }
}

private fun ClassDef.redexName(): String? = fields
    .firstOrNull { it.name == REDEX_NAME_FIELD }
    ?.let { (it.initialValue as? StringEncodedValue)?.value }

/** Register name (pN) of the last ImmutableList parameter, or null when the method takes none. */
private fun listParameterRegister(method: MutableMethod): String? {
    val types = method.parameterTypes.map { it.toString() }
    val index = types.indexOfLast { it == IMMUTABLE_LIST }
    if (index < 0) return null
    var register = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    for (i in 0 until index) register += if (types[i] == "J" || types[i] == "D") 2 else 1
    return "p$register"
}

@Suppress("unused")
val hideStoryAdsPatch = bytecodePatch(
    name = "Hide story ads",
    description = "Hides the ads in the Facebook app stories. Works from 490 up to 585.",
) {
    // No version restriction: the CLI must not refuse when Facebook changes version.
    compatibleWith("com.facebook.katana")

    apply {
        val log = Logger.getLogger("HideStoryAds")

        // ---- Find everything first (read only) -------------------------------------------------------------------
        val runnables = mutableListOf<Target>()
        // Copy: getOrReplaceMutable() later replaces classes inside classDefs.
        classDefs.toList().forEach { classDef ->
            val redexName = classDef.redexName() ?: return@forEach
            if (!redexName.contains(REDEX_CLASS_MARKER)) return@forEach
            val keyword = REDEX_RUNNABLE_KEYWORDS.firstOrNull { redexName.contains(it) } ?: return@forEach
            classDef.methods
                .filter { it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() && it.implementation != null }
                .forEach { runnables += it.toTarget("$keyword (${classDef.type})") }
        }

        val sources = LinkedHashMap<Target, Target>()
        LIST_SOURCE_STRINGS.forEach { marker ->
            classDefs.methodsByString[marker].orEmpty()
                .filter { method ->
                    method.returnType == IMMUTABLE_LIST &&
                        method.implementation != null &&
                        method.parameterTypes.any { it.toString() == IMMUTABLE_LIST }
                }
                .forEach { method -> method.toTarget("\"$marker\" (${method.definingClass})").let { sources.putIfAbsent(it, it) } }
        }

        // ---- Change ----------------------------------------------------------------------------------------------
        var applied = 0

        runnables.forEach { target ->
            val method = mutableMethodOf(target)
            if (method == null) {
                log.warning("Hide story ads: layer 1 ${target.label}: method not found when patching")
            } else {
                method.returnEarly()
                applied++
                log.info("Hide story ads: layer 1 stopped ${target.label}")
            }
        }
        if (runnables.isEmpty()) log.info("Hide story ads: layer 1 found no AdBucket runnable on this build")

        sources.keys.forEach { target ->
            val method = mutableMethodOf(target)
            val register = method?.let { listParameterRegister(it) }
            if (method == null || register == null) {
                log.warning("Hide story ads: layer 2 ${target.label}: method or its list parameter not found when patching")
            } else {
                method.addInstructions(0, "return-object $register")
                applied++
                log.info("Hide story ads: layer 2 passes the list through in ${target.label}")
            }
        }
        if (sources.isEmpty()) log.info("Hide story ads: layer 2 found no ad bucket data source on this build")

        if (applied == 0) {
            throw PatchException(
                "Hide story ads: neither the AdBucket runnables nor the ad bucket data sources exist in this build. " +
                    "Send the smali of the class that holds \"uninsertedMainAdsQueue\".",
            )
        }
    }
}
