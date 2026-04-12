package app.revanced.patches.zwanoo.speedtest.ads

import app.revanced.patcher.accessFlags
import app.revanced.patcher.definingClass
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import com.android.tools.smali.dexlib2.AccessFlags

// bannerad/a - public boolean i() { return this.f; }
internal val BytecodePatchContext.bannerAdEnabledMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ookla/speedtest/bannerad/a;")
    name("i")
    returnType("Z")
    accessFlags(AccessFlags.PUBLIC)
}

// nativead/google/k - protected void d()
internal val BytecodePatchContext.nativeAdCreateMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ookla/speedtest/nativead/google/k;")
    name("d")
    returnType("V")
    accessFlags(AccessFlags.PROTECTED)
}

// nativead/e - public void execute()
internal val BytecodePatchContext.nativeAdExecuteMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ookla/speedtest/nativead/e;")
    name("execute")
    returnType("V")
    accessFlags(AccessFlags.PUBLIC)
}

// purchase/google/D - public boolean b()
internal val BytecodePatchContext.premiumStatusMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ookla/speedtest/purchase/google/D;")
    name("b")
    returnType("Z")
    accessFlags(AccessFlags.PUBLIC)
}
