package app.revanced.patches.twitch.misc.override

import app.revanced.patcher.gettingFirstClassDef
import app.revanced.patcher.patch.BytecodePatchContext

internal val BytecodePatchContext.channelInfoClassDef by gettingFirstClassDef("Ltv/twitch/android/models/channel/ChannelInfo;")
internal val BytecodePatchContext.channelModelClassDef by gettingFirstClassDef("Ltv/twitch/android/models/channel/ChannelModel;")
internal val BytecodePatchContext.userModelClassDef by gettingFirstClassDef("Ltv/twitch/android/models/UserModel;")
internal val BytecodePatchContext.partialChannelModelClassDef by gettingFirstClassDef("Ltv/twitch/android/models/PartialChannelModel;")
internal val BytecodePatchContext.channelInfoModClassDef by gettingFirstClassDef("Ltv/twitch/android/feature/mod/view/model/ChannelInfo;")
internal val BytecodePatchContext.fallbackChannelInfoClassDef by gettingFirstClassDef("Ltv/twitch/android/models/channel/FallbackChannelInfo;")
internal val BytecodePatchContext.adjacentItemClassDef by gettingFirstClassDef("Ltv/twitch/android/shared/ads/models/edge/api/AdjacentItem;")
internal val BytecodePatchContext.contentClassificationCategoryModelClassDef by gettingFirstClassDef("Ltv/twitch/android/models/contentclassification/ContentClassificationCategoryModel;")
internal val BytecodePatchContext.moderationActionBundleClassDef by gettingFirstClassDef("Ltv/twitch/android/shared/chat/moderation/ModerationActionBundle;")
internal val BytecodePatchContext.chatUserDialogInfoClassDef by gettingFirstClassDef("Ltv/twitch/android/shared/chat/chatuserdialog/ChatUserDialogInfo;")
internal val BytecodePatchContext.chatRestrictionsStateClassDef by gettingFirstClassDef("Ltv/twitch/android/shared/messageinput/impl/chatrestrictions/ChatRestrictionsState;")
internal val BytecodePatchContext.communityPointsRewardCustomClassDef by gettingFirstClassDef($$"Ltv/twitch/android/models/communitypoints/CommunityPointsReward$Custom;")
