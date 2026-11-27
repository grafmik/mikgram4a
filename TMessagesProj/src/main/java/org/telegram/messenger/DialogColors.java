package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.support.LongSparseIntArray;

import java.util.Map;

/**
 * MikGram: per-conversation background tint in the chats list.
 * Maps a dialog id to an index into {@link #PALETTE}; same palette as MikGram Desktop.
 */
public class DialogColors {

    public static final int NONE = -1;

    public static final int[] PALETTE = {
        0xFFE8746B, // red
        0xFFE8A24E, // orange
        0xFFE3C74B, // yellow
        0xFF6FB86B, // green
        0xFF4FB0A5, // teal
        0xFF5AA0E0, // blue
        0xFF9B7BD6, // purple
        0xFFDE7FB0, // pink
    };

    private static final int TINT_ALPHA = 56;

    private static LongSparseIntArray colors;

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mikgram_dialog_colors", Activity.MODE_PRIVATE);
    }

    // DialogCell asks on every draw, so the preferences are read once and kept in memory.
    private static LongSparseIntArray getColors() {
        if (colors == null) {
            LongSparseIntArray loaded = new LongSparseIntArray();
            for (Map.Entry<String, ?> entry : getPreferences().getAll().entrySet()) {
                if (!(entry.getValue() instanceof Integer)) {
                    continue;
                }
                try {
                    loaded.put(Long.parseLong(entry.getKey()), (Integer) entry.getValue());
                } catch (NumberFormatException ignore) {

                }
            }
            colors = loaded;
        }
        return colors;
    }

    public static int get(long dialogId) {
        int index = getColors().get(dialogId, NONE);
        return index >= 0 && index < PALETTE.length ? index : NONE;
    }

    /** Translucent color to paint over the row background, or 0 when the dialog has none. */
    public static int getTint(long dialogId) {
        int index = get(dialogId);
        return index == NONE ? 0 : (PALETTE[index] & 0x00FFFFFF) | (TINT_ALPHA << 24);
    }

    /**
     * The row background with the dialog's tint over it, opaque. For what paints the background to
     * hide the row's content (the fade under the reorder icon): the plain background would show as
     * a band on a tinted row.
     */
    public static int background(long dialogId, int background) {
        final int tint = getTint(dialogId);
        return tint == 0 ? background : ColorUtils.compositeColors(tint, background);
    }

    public static void set(long dialogId, int index) {
        applySynced(dialogId, index);
        MikGramSync.noteColorChanged(dialogId);
    }

    /** Dialog id -> palette index, for {@link MikGramSync}. */
    public static java.util.HashMap<Long, Integer> forSync() {
        java.util.HashMap<Long, Integer> result = new java.util.HashMap<>();
        LongSparseIntArray all = getColors();
        for (int a = 0, N = all.size(); a < N; a++) {
            result.put(all.keyAt(a), all.valueAt(a));
        }
        return result;
    }

    /** A color chosen on another device: same as {@link #set} but not synced back. */
    public static void applySynced(long dialogId, int index) {
        SharedPreferences.Editor editor = getPreferences().edit();
        if (index < 0 || index >= PALETTE.length) {
            getColors().delete(dialogId);
            editor.remove(String.valueOf(dialogId));
        } else {
            getColors().put(dialogId, index);
            editor.putInt(String.valueOf(dialogId), index);
        }
        editor.apply();
    }
}
