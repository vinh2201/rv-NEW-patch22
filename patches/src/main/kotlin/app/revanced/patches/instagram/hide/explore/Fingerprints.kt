package app.revanced.patches.instagram.hide.explore

import app.revanced.patcher.after
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode

private const val SECTIONAL_ITEMS_KEY = 3151L

internal val BytecodePatchContext.exploreResponseJsonParserMethodMatch by composingFirstMethod("clusters") {
    name("unsafeParseFromJson")
    instructions(
        SECTIONAL_ITEMS_KEY(),
        after(Opcode.MOVE_RESULT_OBJECT()),
        "next_max_id"(),
        "interests"(),
    )
}
