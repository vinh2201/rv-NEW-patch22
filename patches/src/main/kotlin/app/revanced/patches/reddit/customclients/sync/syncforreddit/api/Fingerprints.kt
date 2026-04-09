package app.revanced.patches.reddit.customclients.sync.syncforreddit.api

import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.string
import app.revanced.util.getting
import app.revanced.util.using

internal val BytecodePatchContext.getAuthorizationStringMethodMatch by composingFirstMethod {
    instructions(string { contains("authorize.compact?client_id") })
}

internal val BytecodePatchContext.bearerTokenMethodMatch by getting {
    firstMethodComposite {
        instructions(string { contains("Basic") })
    }
} using { getAuthorizationStringMethodMatch.immutableMethod }

internal val BytecodePatchContext.getRedirectUriMethod by gettingFirstMethodDeclaratively(
    "http://redditsync/auth"
)

internal val BytecodePatchContext.getUserAgentMethod by gettingFirstMethodDeclaratively {
    instructions("android:com.laurencedawson.reddit_sync"(String::contains))
}

internal val BytecodePatchContext.imgurImageAPIMethodMatch by composingFirstMethod {
    instructions("https://imgur-apiv3.p.rapidapi.com/3/image"())
}
