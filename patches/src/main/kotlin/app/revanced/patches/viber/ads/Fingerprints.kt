package app.revanced.patches.viber.ads

import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.findVPlusMainMatch by composingFirstMethod {
    // Tìm trực tiếp chuỗi định danh "vPlus_Main" trong khối khởi tạo cờ
    instructions(
        "viber_plus_debug_ads_free_flag"(),
        Opcode.CONST_STRING(),
    )
}