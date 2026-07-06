package app.revanced.patches.twitch.chat.haptics

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patches.twitch.chat.style.chatMessageViewHolderBindMethod

internal val BytecodePatchContext.chatHapticsRowMethod
    get() = chatMessageViewHolderBindMethod
