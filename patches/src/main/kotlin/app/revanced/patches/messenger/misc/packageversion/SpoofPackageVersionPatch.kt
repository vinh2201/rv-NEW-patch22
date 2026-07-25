package app.revanced.patches.messenger.misc.packageversion

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringOption
import app.revanced.util.getNode
import org.w3c.dom.Element

@Suppress("unused")
val spoofPackageVersionPatch = resourcePatch(
    name = "Spoof package version",
    description = "Sets a very high version code so the Play Store treats the app as already up " +
        "to date and never offers an update.",
    use = false,
) {
    compatibleWith("com.facebook.orca")

    val versionCodeOption = stringOption(
        default = "2147483647",
        name = "Version code",
        description = "The version code to set. Must be higher than the Play Store version " +
            "to suppress updates. Defaults to the maximum value.",
        required = true,
    ) { it != null && it.matches(Regex("^\\d{1,10}$")) && it.toLong() <= Int.MAX_VALUE.toLong() }

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element
            manifest.setAttribute("android:versionCode", versionCodeOption.value!!)
        }
    }
}