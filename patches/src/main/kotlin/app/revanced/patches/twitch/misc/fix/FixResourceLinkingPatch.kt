package app.revanced.patches.twitch.misc.fix

import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val SETTINGS_ACTIVITY_NAME = "tv.twitch.android.settings.SettingsActivity"

val fixResourceLinkingPatch = resourcePatch(
    description = "Defines attributes that are referenced by the app but stripped from its resources " +
        "and adjusts the settings activity so ReVanced settings open in their own instance.",
) {
    apply {
        document("res/values/attrs.xml").use { document ->
            val attributes = document.getElementsByTagName("attr")

            var exists = false
            for (index in 0 until attributes.length) {
                if ((attributes.item(index) as Element).getAttribute("name") == "state_dragged") {
                    exists = true
                    break
                }
            }

            if (!exists) {
                val attribute = document.createElement("attr")
                attribute.setAttribute("name", "state_dragged")
                attribute.setAttribute("format", "boolean")
                document.documentElement.appendChild(attribute)
            }
        }

        document("AndroidManifest.xml").use { document ->
            val activities = document.getElementsByTagName("activity")

            for (index in 0 until activities.length) {
                val activity = activities.item(index)
                val name = activity.attributes?.getNamedItem("android:name")?.nodeValue

                if (name == SETTINGS_ACTIVITY_NAME) {
                    val launchMode = activity.attributes?.getNamedItem("android:launchMode")
                    if (launchMode != null) {
                        launchMode.nodeValue = "standard"
                    }
                }
            }
        }
    }
}
