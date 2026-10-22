package org.telegram.messenger;

import android.text.TextUtils;

/**
 * MikGram: in the discussion group of a channel, the copy of a signed post shows its author
 * as the sender name, instead of the small signature next to the time.
 * The channel title moves to the tag on the right of the name.
 */
public class DiscussionAuthor {

    /** Author of a channel post copied into its discussion group, null when there is none to show. */
    public static String get(MessageObject message, boolean isMegagroup) {
        if (!isMegagroup || message == null || !message.isForwardedChannelPost()) {
            return null;
        }
        final String author = message.messageOwner.fwd_from.post_author;
        return TextUtils.isEmpty(author) ? null : author.replace('\n', ' ');
    }
}
