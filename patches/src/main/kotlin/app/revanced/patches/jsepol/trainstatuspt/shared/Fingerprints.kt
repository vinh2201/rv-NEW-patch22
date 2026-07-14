package app.revanced.patches.jsepol.trainstatuspt.shared

import app.revanced.patcher.gettingFirstClassDef
import app.revanced.patcher.patch.BytecodePatchContext

internal const val MAIN_ACTIVITY_CLASS = "Lcom/jsepol/trainstatuspt/MainActivity;"
internal const val TRAIN_DETAILS_ACTIVITY_CLASS = "Lcom/jsepol/trainstatuspt/TrainDetailsActivity;"

internal val BytecodePatchContext.initClassDef by gettingFirstClassDef(MAIN_ACTIVITY_CLASS)
internal val BytecodePatchContext.trainDetailsClassDef by gettingFirstClassDef(TRAIN_DETAILS_ACTIVITY_CLASS)
