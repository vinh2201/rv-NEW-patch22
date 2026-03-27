package app.revanced.patches.duolingo.debug

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.debugCategoryAllowOnReleaseBuildsMethod by gettingFirstMethodDeclaratively {
    name("getAllowOnReleaseBuilds")
    definingClass("Lcom/duolingo/debug/DebugCategory;")
    returnType("Z")
    parameterTypes()
}

internal val BytecodePatchContext.buildConfigProviderConstructorMethodMatch by getting {
    firstMethodComposite {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
        parameterTypes()
        opcodes(Opcode.CONST_4)
    }
} using {
    firstImmutableMethodDeclaratively {
        name("toString")
        parameterTypes()
        returnType("Ljava/lang/String;")
        instructions(string("BuildConfigProvider(", String::contains))
    }
}
