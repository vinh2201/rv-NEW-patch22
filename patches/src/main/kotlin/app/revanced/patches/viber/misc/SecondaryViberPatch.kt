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

        // Chỉ filter theo tên và kiểu trả về, BỎ .filterIsInstance đi
        val tabletMethods = targetClass.methods.filter {
            it.name == "isTablet" && it.returnType == "Z"
        }

        if (tabletMethods.isEmpty()) {
            error("Target tablet detection methods not found in $targetClassName")
        }

        tabletMethods.forEach { method ->
            // 1. Ép method về MutableMethod để dùng được addInstructions
            val mutableMethod = method as? MutableMethod ?: return@forEach
            
            // 2. Lấy implementation như cũ
            val impl = mutableMethod.implementation as? MutableMethodImplementation ?: return@forEach

            // 3. Xóa sạch bytecode cũ và ép trả về true
            impl.instructions.clear()
            mutableMethod.addInstructions(
                2,
                """
                const/16 v1, 0x258
                iput v1, v0, Landroid/content/res/Configuration;->smallestScreenWidthDp:I
            
                const/4 v1, 0x0f
                iget v2, v0, Landroid/content/res/Configuration;->screenLayout:I
                and-int/2addr v2, v1
                if-gez v2, :cond_viber_tablet_0
                not-int v1, v1
                and-int/2addr v0, v1
                const/4 v1, 0x02
                or-int/2addr v0, v1
                :cond_viber_tablet_0
                """
            )
        }
    }
}
