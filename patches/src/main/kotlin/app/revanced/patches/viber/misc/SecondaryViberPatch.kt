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
        // Trỏ chính xác vào class chứa logic check thiết bị mà bạn đã decompile được
        val targetClassName = "Lcom/google/android/gms/common/util/DeviceProperties;"
        
        // Sửa lỗi 1: Dùng `it.type` thay vì `it.name`
        val targetClass = classes.firstOrNull { it.type == targetClassName }
            ?: error("Target class $targetClassName not found in Viber APK")

        // Tìm tất cả các hàm tên là isTablet và trả về Boolean (Z) trong class này
        val tabletMethods = targetClass.methods.filter { 
            it.name == "isTablet" && it.returnType == "Z" 
        }.filterIsInstance<MutableMethod>()

        if (tabletMethods.isEmpty()) {
            error("Target tablet detection methods not found in $targetClassName")
        }

        tabletMethods.forEach { method ->
            // Sửa lỗi 2: Ép kiểu sang MutableMethodImplementation để có thể thay đổi registerCount
            val impl = method.implementation as? MutableMethodImplementation ?: return@forEach
            
            // Đảm bảo method có đủ register để chạy biến v0
            if (impl.registerCount < 1) {
                impl.registerCount = 1
            }
            
            // Xóa sạch mã cũ và ép nó trả về true ngay lập tức
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
