package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

/**
 * MikGram: width of the chats list column in the tablet layout, which the user can change by
 * dragging the border between the two columns (see {@link org.telegram.ui.Components.TabletSplitHandle}),
 * like the chats list of MikGram Desktop.
 * The width is kept as a share of the screen width, so it follows rotation. Until the border is
 * dragged once, Telegram's own width is used.
 */
public class TabletSplit {

    private static final int MIN_LEFT_DP = 260;
    private static final int MIN_RIGHT_DP = 320;
    private static final String KEY_RATIO = "left_ratio";

    // Read on the UI thread only; NaN = not read yet, negative = never dragged.
    private static float ratio = Float.NaN;

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mikgram_tablet_split", Activity.MODE_PRIVATE);
    }

    private static float getRatio() {
        if (Float.isNaN(ratio)) {
            ratio = getPreferences().getFloat(KEY_RATIO, -1f);
        }
        return ratio;
    }

    /** Replaces the body of AndroidUtilities.getTabletLeftFragmentSize. */
    public static int leftSize(int fullWidth, int insetLeft, int insetRight) {
        final int available = fullWidth - insetLeft - insetRight;
        final float current = getRatio();
        if (current < 0) {
            return insetLeft + Math.max(AndroidUtilities.dp(320), available * 35 / 100);
        }
        return insetLeft + clamp(available, Math.round(available * current));
    }

    private static int clamp(int available, int width) {
        final int min = AndroidUtilities.dp(MIN_LEFT_DP);
        final int max = Math.max(min, available - AndroidUtilities.dp(MIN_RIGHT_DP));
        return Math.max(min, Math.min(max, width));
    }

    /** Sets the column width while dragging, in pixels without the left inset. */
    public static void setLeftWidth(int available, int width) {
        if (available > 0) {
            ratio = clamp(available, width) / (float) available;
        }
    }

    public static void save() {
        if (!Float.isNaN(ratio) && ratio >= 0) {
            getPreferences().edit().putFloat(KEY_RATIO, ratio).apply();
        }
    }
}
