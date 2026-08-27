package app.revanced.patches.viber.ads

import app.revanced.patcher.definingClass
import app.revanced.patcher.firstMethodDeclarativelyOrNull
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType
import app.revanced.util.returnEarly
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.revanced.util.fingerprint.mutableClassOrThrow
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.util.MethodUtil

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide Ads",
    description = "Enable native Viber Plus flag to clean ad containers and fix screen freeze.",
) {
    compatibleWith("com.viber.voip")

    apply {
        // Lấy class chứa phương thức khởi tạo cờ vPlus_Main
        val targetClass = findVPlusMainMatch.immutableClass.type

        // Can thiệp tất cả các hàm kiểm tra trạng thái (isEnabled / boolean value)
        firstMethodDeclarativelyOrNull {
            definingClass(targetClass)
            returnType("Z")
            parameterTypes()
        }?.returnEarly(true)
    }
}