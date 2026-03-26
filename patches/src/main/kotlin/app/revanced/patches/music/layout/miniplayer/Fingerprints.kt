package app.revanced.patches.music.layout.miniplayer

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patches.shared.misc.mapping.ResourceType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef

internal val BytecodePatchContext.miniPlayerConstructorMethodMatch by composingFirstMethod(
    "sharedToggleMenuItemMutations"
) {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    instructions(ResourceType.ID("music_playback_controls"))
}

/**
 * Matches to the class found in [miniPlayerConstructorMethodMatch].
 */
internal val ClassDef.switchToggleColorMethodMatch by ClassDefComposing.composingFirstMethod {
    accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "J")
    instructions(
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method { returnType.startsWith("L") && parameterTypes.isEmpty() }
        ),
        after(Opcode.MOVE_RESULT_OBJECT()),
        after(Opcode.CHECK_CAST()),
        afterAtMost(5, Opcode.GOTO()),
        allOf(Opcode.IGET(), field { type == "I" }),
        after(Opcode.INVOKE_VIRTUAL())
    )
}

internal val BytecodePatchContext.minimizedPlayerMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "L")
    instructions("w_st"())
}
