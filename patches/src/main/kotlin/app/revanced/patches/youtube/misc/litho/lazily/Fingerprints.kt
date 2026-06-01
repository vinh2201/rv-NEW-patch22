package app.revanced.patches.youtube.misc.litho.lazily

import app.revanced.patcher.accessFlags
import app.revanced.patcher.allOf
import app.revanced.patcher.definingClass
import app.revanced.patcher.firstImmutableMethodDeclaratively
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.treeNodeResultListMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
        returnType("Ljava/util/List;")
        instructions(
            allOf(Opcode.INVOKE_STATIC(), method { name == "nCopies" })
        )
    }
} using {
    firstImmutableMethodDeclaratively {
        returnType("L")
        instructions(
            "Failed to parse Element proto."(),
            "Cannot read theme key from model."()
        )
    }
}

internal val BytecodePatchContext.lazilyConvertedElementPatchMethod by gettingFirstMethodDeclaratively {
    name("onLazilyConvertedElementLoaded")
    definingClass(EXTENSION_CLASS_DESCRIPTOR)
    accessFlags(AccessFlags.PRIVATE, AccessFlags.STATIC)
}
