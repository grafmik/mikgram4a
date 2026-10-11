package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MikGram: local ban list for channels and groups, same behavior as MikGram Desktop (mikgram/banned_peers.cpp).
 * A banned chat leaves the chats list, the search, the suggestions and the notifications.
 * Its messages seen elsewhere (forwards, replies, quotes) stay visible: only the name
 * gets a "(banned)" suffix and stops being clickable.
 * Nothing is sent to Telegram, the list lives in the preferences of this device.
 */
public class BannedPeers {

    public static class Entry {
        public final long dialogId;
        public final String title;

        Entry(long dialogId, String title) {
            this.dialogId = dialogId;
            this.title = title;
        }
    }

    // Read from the UI, storage and notifications threads: dialog id -> title.
    private static volatile ConcurrentHashMap<Long, String> banned;

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mikgram_banned_peers", Activity.MODE_PRIVATE);
    }

    private static ConcurrentHashMap<Long, String> getBanned() {
        ConcurrentHashMap<Long, String> result = banned;
        if (result == null) {
            synchronized (BannedPeers.class) {
                result = banned;
                if (result == null) {
                    result = new ConcurrentHashMap<>();
                    for (Map.Entry<String, ?> entry : getPreferences().getAll().entrySet()) {
                        try {
                            result.put(Long.parseLong(entry.getKey()), String.valueOf(entry.getValue()));
                        } catch (NumberFormatException ignore) {

                        }
                    }
                    banned = result;
                }
            }
        }
        return result;
    }

    public static boolean hasBanned() {
        return !getBanned().isEmpty();
    }

    public static boolean isBanned(long dialogId) {
        return dialogId < 0 && hasBanned() && getBanned().containsKey(dialogId);
    }

    /** Banned, and shown somewhere else than in its own history. */
    public static boolean isBannedIn(long dialogId, long historyDialogId) {
        return dialogId != historyDialogId && isBanned(dialogId);
    }

    public static String suffix(long dialogId, long historyDialogId) {
        return isBannedIn(dialogId, historyDialogId) ? " (" + MikGramLang.get(MikGramLang.Phrase.BANNED) + ")" : "";
    }

    public static String withSuffix(String name, TLRPC.Chat chat, long historyDialogId) {
        return name == null || chat == null ? name : name + suffix(-chat.id, historyDialogId);
    }

    /** Name shown in the reply panel of a message: marked when the reply or the quote leads to a banned chat. */
    public static CharSequence replyName(CharSequence name, MessageObject messageObject) {
        if (name == null || messageObject == null || messageObject.messageOwner == null || !hasBanned()) {
            return name;
        }
        final long historyDialogId = messageObject.getDialogId();
        final TLRPC.MessageReplyHeader reply = messageObject.messageOwner.reply_to;
        boolean isBanned = false;
        if (reply != null && reply.reply_to_peer_id != null) {
            isBanned = isBannedIn(DialogObject.getPeerDialogId(reply.reply_to_peer_id), historyDialogId);
        }
        if (!isBanned && reply != null && reply.reply_from != null && reply.reply_from.from_id != null) {
            isBanned = isBannedIn(DialogObject.getPeerDialogId(reply.reply_from.from_id), historyDialogId);
        }
        if (!isBanned && messageObject.replyMessageObject != null) {
            isBanned = isBannedIn(messageObject.replyMessageObject.getFromChatId(), historyDialogId);
        }
        return isBanned ? TextUtils.concat(name, " (" + MikGramLang.get(MikGramLang.Phrase.BANNED) + ")") : name;
    }

    public static <T extends TLRPC.Chat> void removeBanned(List<T> chats) {
        if (!hasBanned()) {
            return;
        }
        for (int a = chats.size() - 1; a >= 0; a--) {
            if (chats.get(a) != null && isBanned(-chats.get(a).id)) {
                chats.remove(a);
            }
        }
    }

    /** Channels and groups can be banned, private chats and communities cannot. */
    public static boolean canBan(TLRPC.Chat chat) {
        return chat != null && !ChatObject.isCommunity(chat) && !chat.monoforum;
    }

    public static boolean isGroup(TLRPC.Chat chat) {
        return !ChatObject.isChannelAndNotMegaGroup(chat);
    }

    /** Label of the menu entry that bans or unbans this chat. */
    public static String actionText(TLRPC.Chat chat) {
        final boolean group = isGroup(chat);
        if (isBanned(-chat.id)) {
            return MikGramLang.get(group ? MikGramLang.Phrase.UNBAN_GROUP : MikGramLang.Phrase.UNBAN_CHANNEL);
        }
        return MikGramLang.get(group ? MikGramLang.Phrase.BAN_GROUP : MikGramLang.Phrase.BAN_CHANNEL);
    }

    public static void ban(TLRPC.Chat chat) {
        if (!canBan(chat)) {
            return;
        }
        final long dialogId = -chat.id;
        final String title = chat.title == null ? "" : chat.title;
        getBanned().put(dialogId, title);
        getPreferences().edit().putString(String.valueOf(dialogId), title).apply();
        applied(dialogId, true);
    }

    public static void unban(long dialogId) {
        if (getBanned().remove(dialogId) != null) {
            getPreferences().edit().remove(String.valueOf(dialogId)).apply();
            applied(dialogId, false);
        }
    }

    public static void toggle(TLRPC.Chat chat) {
        if (chat == null) {
            return;
        }
        if (isBanned(-chat.id)) {
            unban(-chat.id);
        } else {
            ban(chat);
        }
    }

    public static ArrayList<Entry> list() {
        final ArrayList<Entry> result = new ArrayList<>();
        for (Map.Entry<Long, String> entry : getBanned().entrySet()) {
            result.add(new Entry(entry.getKey(), entry.getValue()));
        }
        Collections.sort(result, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return result;
    }

    // The list is shared by all the accounts of the app: a channel has the same id in each of them.
    private static void applied(long dialogId, boolean nowBanned) {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                continue;
            }
            if (nowBanned) {
                NotificationsController.getInstance(account).removeNotificationsForDialog(dialogId);
            }
            MessagesController.getInstance(account).sortDialogs(null);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }
}
