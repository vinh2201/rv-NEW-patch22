package app.revanced.patches.brave.layout.flicker

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode

internal val BytecodePatchContext.preferenceSetVisibleMethod by gettingFirstMethodDeclaratively {
    definingClass("Landroidx/preference/Preference;")
    returnType("V")
    parameterTypes("Z")
    instructions(
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method { name == "removeCallbacks" },
        ),
    )
}

internal val BytecodePatchContext.promoCardConstructorMethod by gettingFirstMethodDeclaratively {
    definingClass("Lorg/chromium/chrome/browser/ui/settings_promo_card/SettingsPromoCardPreference;")
    name("<init>")
}
