package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * MikGram: pinned chats of the main chats list kept on this device, same behavior as
 * MikGram Desktop (mikgram/local_pins.cpp). No limit, any order, and nothing is sent to
 * Telegram: neither the pinned chats nor their order. Until the first pin, unpin or move,
 * the chats pinned on Telegram are shown; they become the starting list of this device.
 * The archive and the custom folders keep Telegram's pins.
 */
public class LocalPins {

    // Account (user id) -> pinned dialogs, the top one first. Absent: Telegram's pins.
    private static final HashMap<Long, ArrayList<Long>> orders = new HashMap<>();
    private static final HashMap<Long, HashMap<Long, Integer>> indexes = new HashMap<>();
    private static boolean loaded;

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mikgram_local_pins", Activity.MODE_PRIVATE);
    }

    private static long accountKey(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (Map.Entry<String, ?> entry : getPreferences().getAll().entrySet()) {
            try {
                long account = Long.parseLong(entry.getKey());
                ArrayList<Long> order = new ArrayList<>();
                String value = String.valueOf(entry.getValue());
                if (!TextUtils.isEmpty(value)) {
                    for (String dialogId : value.split(",")) {
                        order.add(Long.parseLong(dialogId));
                    }
                }
                setOrder(account, order);
            } catch (NumberFormatException ignore) {

            }
        }
    }

    private static void setOrder(long account, ArrayList<Long> order) {
        HashMap<Long, Integer> index = new HashMap<>();
        for (int a = 0, N = order.size(); a < N; a++) {
            index.put(order.get(a), a);
        }
        orders.put(account, order);
        indexes.put(account, index);
    }

    /** Called before every sort of the chats list: the pins of this device win over Telegram's. */
    public static void apply(MessagesController controller) {
        load();
        long account = accountKey(controller.currentAccount);
        HashMap<Long, Integer> index = indexes.get(account);
        if (index == null) {
            return;
        }
        int count = index.size();
        LongSparseArray<TLRPC.Dialog> dialogs = controller.dialogs_dict;
        for (int a = 0, N = dialogs.size(); a < N; a++) {
            TLRPC.Dialog dialog = dialogs.valueAt(a);
            if (dialog == null || dialog.folder_id != 0 || dialog instanceof TLRPC.TL_dialogFolder) {
                continue;
            }
            Integer position = index.get(dialog.id);
            dialog.pinned = position != null;
            dialog.pinnedNum = position != null ? count - position : 0;
        }
    }

    /**
     * Instead of sending the pinned chats of the main list to Telegram: they are kept here,
     * in the order shown (the top one first). False for the other lists.
     */
    public static boolean keepOrder(int account, int folderId, ArrayList<Long> dialogIds) {
        if (folderId != 0) {
            return false;
        }
        load();
        long key = accountKey(account);
        ArrayList<Long> order = new ArrayList<>(dialogIds);
        setOrder(key, order);
        getPreferences().edit().putString(String.valueOf(key), TextUtils.join(",", order)).apply();
        return true;
    }

    /** A chat just pinned (on top) or unpinned, before the list is sorted again. */
    public static void onPinChanged(int account, TLRPC.Dialog dialog) {
        if (dialog.folder_id != 0) {
            return;
        }
        load();
        long key = accountKey(account);
        ArrayList<Long> current = orders.get(key);
        if (current == null) {
            return; // Telegram's pins until the order is kept, right after.
        }
        ArrayList<Long> order = new ArrayList<>(current);
        order.remove(Long.valueOf(dialog.id));
        if (dialog.pinned) {
            order.add(0, dialog.id);
        }
        keepOrder(account, 0, order);
    }

    /** While a pinned chat is dragged: the order shown now is kept, a new sort keeps it too. */
    public static void keepShownOrder(MessagesController controller, int folderId) {
        if (folderId != 0) {
            return;
        }
        ArrayList<Long> dialogIds = new ArrayList<>();
        ArrayList<TLRPC.Dialog> dialogs = controller.getDialogs(folderId);
        for (int a = 0, N = dialogs.size(); a < N; a++) {
            TLRPC.Dialog dialog = dialogs.get(a);
            if (dialog instanceof TLRPC.TL_dialogFolder) {
                continue;
            }
            if (!dialog.pinned) {
                if (!controller.isPromoDialog(dialog.id, false)) {
                    break;
                }
                continue;
            }
            dialogIds.add(dialog.id);
        }
        keepOrder(controller.currentAccount, folderId, dialogIds);
    }

    /** No limit for the main list: the pins stay on this device. */
    public static boolean noLimit(int folderId, MessagesController.DialogFilter filter) {
        return folderId == 0 && filter == null;
    }
}
