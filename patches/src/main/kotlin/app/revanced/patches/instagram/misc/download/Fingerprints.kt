package app.revanced.patches.instagram.misc.download

import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.patch.BytecodePatchContext

internal const val MEDIA_OPTION =
    "Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"

/**
 * Anchors the post overflow menu creator class (`MediaOptionsOverflowMenuCreator`), which builds the
 * list of option rows shown in a post's "..." menu.
 */
internal val BytecodePatchContext.mediaOptionsMenuCreatorAnchor by
    gettingFirstMethodDeclaratively("MediaOptionsOverflowMenuCreator")

/**
 * Anchors the post overflow helper class (`MediaOptionsOverflowHelper`), which holds the tapped
 * media and dispatches option clicks. The bare class name string is shared with other classes, so a
 * log message unique to this class is used instead.
 */
internal val BytecodePatchContext.mediaOptionsOverflowHelperAnchor by
    gettingFirstMethodDeclaratively(
        "MediaOptionsOverflowHelper:navigateToCamera: Failed to pass the media list into camera due to TransactionTooLarge for %s",
    )

/**
 * Anchors the reels (clips) overflow helper class (`ClipsOrganicMoreOptionsHelper`), which holds the
 * reel media and builds/shows the reel "..." options sheet.
 */
internal val BytecodePatchContext.clipsMoreOptionsAnchor by
    gettingFirstMethodDeclaratively("ClipsOrganicMoreOptionsHelper")
