package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.TabletSplit;
import org.telegram.ui.ActionBar.Theme;

/**
 * MikGram: drag handle on the border between the chats list and the chat in the tablet layout,
 * see {@link TabletSplit}. A small grip is drawn in the middle of the border; a horizontal drag
 * starting near the border (on either side) moves it. Taps and vertical scrolls there still reach
 * the chats list and the chat: the drag only takes over once the finger clearly moves sideways.
 */
public class TabletSplitHandle {

    private static final int TOUCH_DP = 14;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean candidate;
    private boolean dragging;
    private boolean cancellingChildren;
    private float downX, downY;
    private int startLeft;

    /**
     * Called first in the layout's dispatchTouchEvent; true means the event is consumed.
     * {@code split} is false when there is a single column or a layer covers both.
     */
    public boolean dispatchTouchEvent(ViewGroup parent, MotionEvent ev, boolean split, int insetLeft, int insetRight) {
        if (cancellingChildren) {
            return false;
        }
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            candidate = false;
            dragging = false;
            if (split) {
                final int border = AndroidUtilities.getTabletLeftFragmentSize(parent.getWidth(), insetLeft, insetRight);
                if (Math.abs(ev.getX() - border) <= dp(TOUCH_DP)) {
                    candidate = true;
                    downX = ev.getX();
                    downY = ev.getY();
                    startLeft = border - insetLeft;
                }
            }
            return false;
        }
        if (!candidate) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            final float dx = ev.getX() - downX;
            final float dy = ev.getY() - downY;
            if (!dragging) {
                final int slop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
                if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
                    candidate = false;
                    return false;
                } else if (Math.abs(dx) <= slop) {
                    return false;
                }
                dragging = true;
                if (parent.getParent() != null) {
                    parent.getParent().requestDisallowInterceptTouchEvent(true);
                }
                final MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                cancellingChildren = true;
                try {
                    parent.dispatchTouchEvent(cancel);
                } finally {
                    cancellingChildren = false;
                    cancel.recycle();
                }
            }
            TabletSplit.setLeftWidth(parent.getWidth() - insetLeft - insetRight, startLeft + Math.round(dx));
            parent.requestLayout();
            parent.invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            candidate = false;
            if (dragging) {
                dragging = false;
                TabletSplit.save();
                parent.invalidate();
                return true;
            }
            return false;
        }
        return dragging;
    }

    /**
     * True from a touch down near the border until the gesture ends or turns out to be a scroll,
     * so the layout keeps receiving it even when no column took the touch down (empty chat pane).
     */
    public boolean isTracking() {
        return candidate || dragging;
    }

    /** Draws the grip over both columns, after them. */
    public void draw(ViewGroup parent, Canvas canvas, boolean split, int insetLeft, int insetRight) {
        if (!split) {
            return;
        }
        final int border = AndroidUtilities.getTabletLeftFragmentSize(parent.getWidth(), insetLeft, insetRight);
        final float halfWidth = dp(dragging ? 3 : 2);
        final float halfHeight = dp(24);
        final float centerY = parent.getHeight() / 2f;
        rect.set(border - halfWidth, centerY - halfHeight, border + halfWidth, centerY + halfHeight);
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
        paint.setAlpha(dragging ? 230 : 140);
        canvas.drawRoundRect(rect, halfWidth, halfWidth, paint);
    }
}
