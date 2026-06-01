package app.revanced.patches.duolingo.ad

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.initializeMonetizationDebugSettingsMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
        returnType("V")
        // Parameters have not been reliable for matching between versions.
        opcodes(Opcode.IPUT_BOOLEAN)
    }
} using {
    firstImmutableMethodDeclaratively {
        name("toString")
        instructions(string("MonetizationDebugSettings(", String::contains))
    }
}
