package app.revanced.patches.duolingo.energy

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// Class name currently is not obfuscated, but it may be in the future.
internal val BytecodePatchContext.energyConfigToStringMethod by gettingFirstMethodDeclaratively {
    name("toString")
    parameterTypes()
    returnType("Ljava/lang/String;")
    instructions(
        predicates=unorderedAllOf(
            "EnergyConfig("(String::contains),
            "maxEnergy="(String::contains),
        )
    )
}

internal val BytecodePatchContext.initializeEnergyConfigMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
        opcodes(Opcode.RETURN_VOID)
    }
} using { energyConfigToStringMethod }
