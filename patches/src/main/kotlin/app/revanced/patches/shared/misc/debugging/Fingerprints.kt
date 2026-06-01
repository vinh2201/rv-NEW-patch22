package app.revanced.patches.shared.misc.debugging

import app.revanced.patcher.gettingFirstImmutableMethodDeclaratively
import app.revanced.patcher.custom
import app.revanced.patcher.firstMethodComposite
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.util.getting
import app.revanced.util.using
import com.android.tools.smali.dexlib2.AccessFlags

internal val BytecodePatchContext.experimentalFeatureFlagUtilMethod by gettingFirstImmutableMethodDeclaratively(
    "Unable to parse proto typed experiment flag: "
) {
    returnType("L")
    custom {
        // 'public static' or 'public static final'.
        AccessFlags.STATIC.isSet(accessFlags)
                && AccessFlags.PUBLIC.isSet(accessFlags)
                // "L", "J", "[B" or "L", "J"
                && parameters.let { (it.size == 2 || it.size == 3) && it[1].type == "J" }
    }
}

internal val BytecodePatchContext.experimentalBooleanFeatureFlagMethodMatch by getting {
    firstMethodComposite {
        returnType("Z")
        parameterTypes("L", "J", "Z")
        custom {
            // 'public static' or 'public static final'.
            AccessFlags.STATIC.isSet(accessFlags) && AccessFlags.PUBLIC.isSet(accessFlags)
        }
    }
} using { experimentalFeatureFlagUtilMethod }

internal val BytecodePatchContext.experimentalDoubleFeatureFlagMethod by getting {
    firstMethodDeclaratively {
        returnType("D")
        parameterTypes("L", "J", "D")
        custom { AccessFlags.STATIC.isSet(accessFlags) }
    }
} using { experimentalFeatureFlagUtilMethod }

internal val BytecodePatchContext.experimentalLongFeatureFlagMethod by getting {
    firstMethodDeclaratively {
        returnType("J")
        parameterTypes("L", "J", "J")
        custom { AccessFlags.STATIC.isSet(accessFlags) }
    }
} using { experimentalFeatureFlagUtilMethod }

internal val BytecodePatchContext.experimentalStringFeatureFlagMethod by getting {
    firstMethodDeclaratively {
        returnType("Ljava/lang/String;")
        parameterTypes("L", "J", "Ljava/lang/String;")
        custom { AccessFlags.STATIC.isSet(accessFlags) }
    }
} using { experimentalFeatureFlagUtilMethod }