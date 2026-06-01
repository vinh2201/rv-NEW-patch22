package app.revanced.patches.youtube.misc.imageurlhook

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags

internal val BytecodePatchContext.onFailureMethod by getting {
    firstMethodDeclaratively {
        name("onFailed")
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes(
            "Lorg/chromium/net/UrlRequest;",
            "Lorg/chromium/net/UrlResponseInfo;",
            "Lorg/chromium/net/CronetException;",
        )
    }
} using { onResponseStartedMethod }

// Acts as a parent method.
internal val BytecodePatchContext.onResponseStartedMethod by gettingFirstMethodDeclaratively(
    "Content-Length",
    "Content-Type",
    "identity",
    "application/x-protobuf",
) {
    name("onResponseStarted")
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;")
}

internal val BytecodePatchContext.onSucceededMethod by getting {
    firstMethodDeclaratively {
        name("onSucceeded")
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("V")
        parameterTypes("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;")
    }
} using { onResponseStartedMethod }

internal const val CRONET_URL_REQUEST_CLASS_DESCRIPTOR = "Lorg/chromium/net/impl/CronetUrlRequest;"

internal val BytecodePatchContext.requestMethod by gettingFirstMethodDeclaratively {
    definingClass(CRONET_URL_REQUEST_CLASS_DESCRIPTOR)
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
}

internal val BytecodePatchContext.messageDigestImageURLMethod by getting {
    firstMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
        parameterTypes("Ljava/lang/String;", "L")
    }
} using {
    firstImmutableMethodDeclaratively {
        accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
        returnType("Ljava/lang/String;")
        parameterTypes()
        instructions(
            anyOf(
                string { equals("@#&=*+-_.,:!?()/~'%;$") },
                string { equals("@#&=*+-_.,:!?()/~'%;$[]") }, // 20.38+
            ),
        )
    }
}
