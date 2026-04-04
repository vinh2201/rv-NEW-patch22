package app.revanced.patches.tiktok.misc.clipboard

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

// TikTok's clipboard wrapper class (obfuscated as X.h1Q) wraps
// android.content.ClipboardManager and hooks 9 ClipboardManager APIs
// via ByteDance's Helios framework, including a real-time
// addPrimaryClipChangedListener. This lets it read everything the
// user copies — passwords, messages, links from other apps.
//
// We identify the wrapper by its ClipboardManager field and patch
// getText() to return null and hasText() to return false.

internal val BytecodePatchContext.clipboardGetTextMethod by gettingFirstMethodDeclaratively {
    name("getText")
    returnType("L")
    custom {
        immutableClassDef.fields.any { it.type == "Landroid/content/ClipboardManager;" }
    }
}

internal val BytecodePatchContext.clipboardHasTextMethod by gettingFirstMethodDeclaratively {
    returnType("Z")
    custom {
        immutableClassDef.fields.any { it.type == "Landroid/content/ClipboardManager;" } &&
            implementation?.instructions?.any { inst ->
                inst.toString().contains("getPrimaryClipDescription")
            } == true
    }
}
