package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * MikGram: in the discussion group of a channel, the copy of a signed post looks like the post
 * in the channel: its author is the sender name, instead of the small signature next to the time,
 * and the channel title moves to the tag on the right of the name.
 * When the channel shows author profiles, the avatar and the name are the author's own,
 * read from the post in the channel (the copy in the group only carries the channel).
 */
public class DiscussionAuthor {

    // "account_channel_post" -> user id of the author, 0 when the post has none.
    private static final HashMap<String, Long> authors = new HashMap<>();
    private static final HashSet<String> requested = new HashSet<>();
    // account -> channel id -> posts to read.
    private static final HashMap<Integer, HashMap<Long, ArrayList<Integer>>> queue = new HashMap<>();
    private static final Runnable loadQueued = DiscussionAuthor::loadQueued;

    /** Name to show as the sender of a channel post copied into its discussion group, null when Telegram's own display stays. */
    public static String getName(MessageObject message, boolean isMegagroup) {
        if (!isCopy(message, isMegagroup)) {
            return null;
        }
        final long userId = getUserId(message, isMegagroup);
        if (userId != 0) {
            final TLRPC.User user = MessagesController.getInstance(message.currentAccount).getUser(userId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        }
        final String author = message.messageOwner.fwd_from.post_author;
        return TextUtils.isEmpty(author) ? null : author.replace('\n', ' ');
    }

    /** Peer whose avatar and profile stand for the sender, in place of {@code from_id} when the channel shows author profiles. */
    public static long getPeerDialogId(MessageObject message, boolean isMegagroup) {
        final long userId = getUserId(message, isMegagroup);
        return userId != 0 ? userId : DialogObject.getPeerDialogId(message.messageOwner.from_id);
    }

    /** Author of the post in the channel, 0 while unknown; the first call for a post reads it from the channel. */
    public static long getUserId(MessageObject message, boolean isMegagroup) {
        if (!isCopy(message, isMegagroup)) {
            return 0;
        }
        final TLRPC.MessageFwdHeader fwd = message.messageOwner.fwd_from;
        final long channelId = fwd.saved_from_peer.channel_id;
        final TLRPC.Chat channel = MessagesController.getInstance(message.currentAccount).getChat(channelId);
        if (channel == null || !channel.signature_profiles) {
            return 0;
        }
        if (fwd.from_id instanceof TLRPC.TL_peerUser) {
            return fwd.from_id.user_id;
        }
        if (fwd.saved_from_id instanceof TLRPC.TL_peerUser) {
            return fwd.saved_from_id.user_id;
        }
        final int postId = fwd.saved_from_msg_id != 0 ? fwd.saved_from_msg_id : fwd.channel_post;
        final String key = message.currentAccount + "_" + channelId + "_" + postId;
        final Long userId = authors.get(key);
        if (userId != null) {
            return userId;
        }
        if (requested.add(key)) {
            HashMap<Long, ArrayList<Integer>> channels = queue.get(message.currentAccount);
            if (channels == null) {
                queue.put(message.currentAccount, channels = new HashMap<>());
            }
            ArrayList<Integer> posts = channels.get(channelId);
            if (posts == null) {
                channels.put(channelId, posts = new ArrayList<>());
            }
            posts.add(postId);
            AndroidUtilities.cancelRunOnUIThread(loadQueued);
            AndroidUtilities.runOnUIThread(loadQueued, 100);
        }
        return 0;
    }

    private static boolean isCopy(MessageObject message, boolean isMegagroup) {
        return isMegagroup && message != null && message.messageOwner != null && message.isForwardedChannelPost();
    }

    // One request per channel for the posts met while laying out the chat.
    private static void loadQueued() {
        for (HashMap.Entry<Integer, HashMap<Long, ArrayList<Integer>>> account : queue.entrySet()) {
            final int currentAccount = account.getKey();
            for (HashMap.Entry<Long, ArrayList<Integer>> channel : account.getValue().entrySet()) {
                final long channelId = channel.getKey();
                final ArrayList<Integer> posts = channel.getValue();
                for (int start = 0; start < posts.size(); start += 100) {
                    final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
                    req.channel = MessagesController.getInstance(currentAccount).getInputChannel(channelId);
                    req.id = new ArrayList<>(posts.subList(start, Math.min(start + 100, posts.size())));
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        for (Integer postId : req.id) {
                            authors.put(currentAccount + "_" + channelId + "_" + postId, 0L);
                        }
                        if (response instanceof TLRPC.messages_Messages) {
                            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                            MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                            MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                            for (TLRPC.Message post : res.messages) {
                                if (post.from_id instanceof TLRPC.TL_peerUser) {
                                    authors.put(currentAccount + "_" + channelId + "_" + post.id, post.from_id.user_id);
                                }
                            }
                        }
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_NAME);
                    }));
                }
            }
        }
        queue.clear();
    }
}
