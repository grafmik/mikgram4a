package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.DialogColors;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MikGramLang;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * MikGram: picker for the per-conversation background tint, see {@link DialogColors}.
 */
public class DialogColorMenu {

    /**
     * Adds "Background color" to a menu, opening a submenu of swatches.
     * The list of dialogs is read when a swatch is drawn or picked, so the caller can keep filling it.
     */
    public static void addTo(ActionBarMenuItem menu, ArrayList<Long> dialogIds, Runnable onChanged, Theme.ResourcesProvider resourcesProvider) {
        final Context context = menu.getContext();
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourcesProvider);
        layout.setFitItems(true);

        ActionBarMenuSubItem backItem = ActionBarMenuItem.addItem(layout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, resourcesProvider);
        backItem.setOnClickListener(view -> menu.getPopupLayout().getSwipeBack().closeForeground());

        layout.addView(new SwatchesView(context, dialogIds, resourcesProvider, index -> {
            final ArrayList<Long> ids = new ArrayList<>(dialogIds);
            for (int a = 0; a < ids.size(); a++) {
                DialogColors.set(ids.get(a), index);
            }
            menu.closeSubMenu();
            if (onChanged != null) {
                onChanged.run();
            }
        }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        menu.addSwipeBackItem(R.drawable.msg_colors, null, MikGramLang.get(MikGramLang.Phrase.DIALOG_COLOR), layout);
    }

    private interface OnColorChosen {
        void run(int index);
    }

    /** A grid of swatches, led by a "none" swatch that clears the color. */
    private static class SwatchesView extends View {

        private static final int MIXED = -2;

        private static final int PER_ROW = 5;
        private static final int PADDING = 14;
        private static final int DIAMETER = 34;
        private static final int SPACING = 14;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Long> dialogIds;
        private final Theme.ResourcesProvider resourcesProvider;
        private final OnColorChosen onChosen;

        SwatchesView(Context context, ArrayList<Long> dialogIds, Theme.ResourcesProvider resourcesProvider, OnColorChosen onChosen) {
            super(context);
            this.dialogIds = dialogIds;
            this.resourcesProvider = resourcesProvider;
            this.onChosen = onChosen;
        }

        private int getCount() {
            return 1 + DialogColors.PALETTE.length;
        }

        /** The color shared by all the dialogs, MIXED when they differ. */
        private int getSelected() {
            if (dialogIds.isEmpty()) {
                return MIXED;
            }
            final int selected = DialogColors.get(dialogIds.get(0));
            for (int a = 1; a < dialogIds.size(); a++) {
                if (DialogColors.get(dialogIds.get(a)) != selected) {
                    return MIXED;
                }
            }
            return selected;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int rows = (getCount() + PER_ROW - 1) / PER_ROW;
            setMeasuredDimension(
                dp(PADDING) * 2 + PER_ROW * dp(DIAMETER) + (PER_ROW - 1) * dp(SPACING),
                dp(PADDING) * 2 + rows * dp(DIAMETER) + (rows - 1) * dp(SPACING)
            );
        }

        private float getCenterX(int position) {
            return dp(PADDING) + (position % PER_ROW) * dp(DIAMETER + SPACING) + dp(DIAMETER) / 2f;
        }

        private float getCenterY(int position) {
            return dp(PADDING) + (position / PER_ROW) * dp(DIAMETER + SPACING) + dp(DIAMETER) / 2f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int selected = getSelected();
            final float radius = dp(DIAMETER) / 2f;
            for (int position = 0; position < getCount(); position++) {
                int index = position - 1; // position 0 is "none"
                float cx = getCenterX(position);
                float cy = getCenterY(position);
                if (index == selected) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    paint.setColor(Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider));
                    canvas.drawCircle(cx, cy, radius + dp(4), paint);
                }
                if (index == DialogColors.NONE) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.5f));
                    paint.setColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
                    canvas.drawCircle(cx, cy, radius - dp(1), paint);
                    float offset = radius * 0.5f;
                    canvas.drawLine(cx + offset, cy - offset, cx - offset, cy + offset, paint);
                } else {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(DialogColors.PALETTE[index]);
                    canvas.drawCircle(cx, cy, radius, paint);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float reach = dp(DIAMETER + SPACING) / 2f;
                for (int position = 0; position < getCount(); position++) {
                    if (Math.abs(event.getX() - getCenterX(position)) <= reach && Math.abs(event.getY() - getCenterY(position)) <= reach) {
                        onChosen.run(position - 1);
                        break;
                    }
                }
            }
            return true;
        }
    }
}
