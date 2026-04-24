package app.revanced.patches.twitch.misc.fixupattrs

import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element

val fixupMissingAttrsPatch = resourcePatch(
    description = "Re-declares Material attributes (state_dragged) lost during apktool decode.",
) {
    execute {
        val missing = listOf(
            "state_dragged" to "boolean",
        )

        document("res/values/attrs.xml").use { document ->
            val resources = document.documentElement
            val existing = mutableSetOf<String>()
            val attrNodes = resources.getElementsByTagName("attr")
            for (i in 0 until attrNodes.length) {
                val el = attrNodes.item(i) as? Element ?: continue
                el.getAttribute("name")?.let { existing += it }
            }
            for ((name, format) in missing) {
                if (name in existing) continue
                val attr = document.createElement("attr")
                attr.setAttribute("name", name)
                attr.setAttribute("format", format)
                resources.appendChild(attr)
            }
        }
    }
}
