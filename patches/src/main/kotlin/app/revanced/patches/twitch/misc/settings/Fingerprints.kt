package app.revanced.patches.twitch.misc.settings

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.settingsActivityOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("/SettingsActivity;")
}

internal val BytecodePatchContext.mainSettingsFragmentOnCreateViewMethod by gettingFirstMethodDeclaratively {
    name("onCreateView")
    definingClass("/MainSettingsFragmentV2;")
}
