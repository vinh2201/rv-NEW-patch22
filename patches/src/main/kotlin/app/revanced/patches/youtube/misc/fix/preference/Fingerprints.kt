package app.revanced.patches.youtube.misc.fix.preference

import app.revanced.patcher.accessFlags
import app.revanced.patcher.after
import app.revanced.patcher.allOf
import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.custom
import app.revanced.patcher.definingClass
import app.revanced.patcher.field
import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.findPreferenceByIndexMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("Landroidx/preference/Preference;")
    definingClass("Landroidx/preference/PreferenceGroup;")
    parameterTypes("I")

    var methodDefiningClass = ""
    custom {
        methodDefiningClass = definingClass
        true
    }

    instructions(
        allOf(
            Opcode.IGET_OBJECT(),
            field { definingClass == methodDefiningClass && type == "Ljava/util/List;" }),
    )
}

internal val BytecodePatchContext.preferenceScreenSyntheticMethodMatch by composingFirstMethod {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    name("run")
    parameterTypes()
    instructions(
        ":android:show_fragment_args"(),
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method { returnType == "Landroidx/preference/PreferenceScreen;" && parameterTypes.isEmpty() }),
        Opcode.RETURN_VOID(),
    )
}

internal val BytecodePatchContext.setPreferenceIconMethod by gettingFirstImmutableMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    definingClass("Landroidx/preference/Preference;")
    parameterTypes("Landroid/graphics/drawable/Drawable;")
}

internal val BytecodePatchContext.setPreferenceIconSpaceReservedMethod by gettingFirstImmutableMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    definingClass("Landroidx/preference/Preference;")
    parameterTypes("Z")

    var methodDefiningClass = ""
    custom {
        methodDefiningClass = definingClass
        true
    }

    instructions(
        allOf(
            Opcode.IGET_BOOLEAN(),
            field { definingClass == methodDefiningClass }
        ),
        after(Opcode.IF_EQ()),
        after(
            allOf(
                Opcode.IPUT_BOOLEAN(),
                field { definingClass == methodDefiningClass }
            )
        ),
        after(
            allOf(
                Opcode.INVOKE_VIRTUAL(),
                method { definingClass == methodDefiningClass && returnType == "V" && parameterTypes.isEmpty() }
            )
        ),
        after(Opcode.RETURN_VOID()),
    )
}