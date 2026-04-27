package app.revanced.patches.twitch.misc.settings

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags

internal val BytecodePatchContext.menuGroupsOnClickMethod by gettingFirstMethodDeclarativelyOrNull {
    name { contains("render") }
    definingClass("/SettingsMenuViewDelegate;")
    accessFlags(AccessFlags.PRIVATE, AccessFlags.STATIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "L", "L")
}

internal val BytecodePatchContext.menuGroupsUpdatedMethod by gettingFirstMethodDeclarativelyOrNull {
    name("<init>")
    definingClass("/SettingsMenuPresenter\$Event\$MenuGroupsUpdated;")
}

internal val BytecodePatchContext.settingsActivityOnCreateMethod by gettingFirstMethodDeclarativelyOrNull {
    name("onCreate")
    definingClass("/SettingsActivity;")
}

internal val BytecodePatchContext.settingsMenuItemEnumMethod by gettingFirstMethodDeclarativelyOrNull {
    name("<clinit>")
    definingClass("/SettingsMenuItem;")
}
