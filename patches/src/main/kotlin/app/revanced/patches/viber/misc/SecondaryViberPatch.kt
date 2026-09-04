package app.revanced.patches.viber.misc

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

@Suppress("unused")
val secondaryViberDevicePatch = bytecodePatch(
    name = "Secondary Viber Device",
    description = "Forces Viber to detect the device as a tablet, enabling the 'Link as secondary device' flow.",
) {
    compatibleWith("com.viber.voip")

    execute {
        val targetClassName = "Lcom/google/android/gms/common/util/DeviceProperties;"

        val targetClass = classes.firstOrNull { it.type == targetClassName }
            ?: error("Target class $targetClassName not found in Viber APK")

        val tabletMethods = targetClass.methods.filter {
            it.name == "isTablet" && it.returnType == "Z"
        }.filterIsInstance<MutableMethod>()

        if (tabletMethods.isEmpty()) {
            error("Target tablet detection methods not found in $targetClassName")
        }

        tabletMethods.forEach { method ->
            val impl = method.implementation ?: return@forEach

            // Xóa sạch bytecode cũ và ép trả về true
            impl.instructions.clear()
            method.addInstructions(
                0,
                """
                const/4 v0, 0x1
                return v0
                """.trimIndent()
            )
        }
    }
}
