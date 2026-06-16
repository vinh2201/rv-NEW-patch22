package app.revanced.patches.brave.premium

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstructions

internal fun MutableMethod.returnEarlyTrue() {
    val impl = implementation ?: return
    val count = impl.instructions.count()
    removeInstructions(0, count)
    addInstructions(0, "const/4 v0, 0x1\nreturn v0")
}

internal fun MutableMethod.returnEarlyFalse() {
    val impl = implementation ?: return
    val count = impl.instructions.count()
    removeInstructions(0, count)
    addInstructions(0, "const/4 v0, 0x0\nreturn v0")
}
