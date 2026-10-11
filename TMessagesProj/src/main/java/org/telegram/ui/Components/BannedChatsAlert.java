package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BannedPeers;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MikGramLang;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * MikGram: menu entries and list of the locally banned chats, see {@link BannedPeers}.
 */
public class BannedChatsAlert {

    /** Same as {@link #show(BaseFragment)}, over the screen currently displayed. */
    public static void show() {
        show(LaunchActivity.getSafeLastFragment());
    }

    /** The list of banned chats, with an "Unban" button on each row. */
    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(MikGramLang.get(MikGramLang.Phrase.BANNED_CHATS));
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);

        final ArrayList<BannedPeers.Entry> entries = BannedPeers.list();
        if (entries.isEmpty()) {
            builder.setMessage(MikGramLang.get(MikGramLang.Phrase.NO_BANNED_CHATS));
        } else {
            LinearLayout rows = new LinearLayout(context);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPadding(dp(24), dp(4), dp(12), 0);
            for (int a = 0; a < entries.size(); a++) {
                final BannedPeers.Entry entry = entries.get(a);

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = new TextView(context);
                title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setText(entry.title);
                row.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

                TextView button = new TextView(context);
                button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                button.setTypeface(AndroidUtilities.bold());
                button.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider));
                button.setGravity(Gravity.CENTER);
                button.setPadding(dp(12), 0, dp(12), 0);
                button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
                button.setText(MikGramLang.get(MikGramLang.Phrase.UNBAN));
                button.setOnClickListener(view -> {
                    BannedPeers.unban(entry.dialogId);
                    rows.removeView(row);
                });
                row.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40));

                rows.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            }
            ScrollView scrollView = new ScrollView(context);
            scrollView.addView(rows, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            builder.setView(scrollView);
        }
        fragment.showDialog(builder.create());
    }

    /** Adds the "Ban channel / Ban group" entry to the menu of a selection of dialogs, see {@link #updateBanItem}. */
    public static ActionBarMenuSubItem addBanItem(ActionBarMenuItem menu, int currentAccount, ArrayList<Long> dialogIds, Runnable onDone) {
        ActionBarMenuSubItem item = menu.addSubItem(0, R.drawable.msg_block, MikGramLang.get(MikGramLang.Phrase.BAN_CHANNEL));
        item.setOnClickListener(view -> {
            final ArrayList<Long> ids = new ArrayList<>(dialogIds);
            menu.closeSubMenu();
            for (int a = 0; a < ids.size(); a++) {
                BannedPeers.ban(MessagesController.getInstance(currentAccount).getChat(-ids.get(a)));
            }
            if (onDone != null) {
                onDone.run();
            }
        });
        return item;
    }

    /** Shown only when the selection holds nothing but channels, or nothing but groups. */
    public static void updateBanItem(ActionBarMenuSubItem item, int currentAccount, ArrayList<Long> dialogIds) {
        if (item == null) {
            return;
        }
        int channels = 0;
        int groups = 0;
        for (int a = 0; a < dialogIds.size(); a++) {
            final long dialogId = dialogIds.get(a);
            final TLRPC.Chat chat = dialogId < 0 ? MessagesController.getInstance(currentAccount).getChat(-dialogId) : null;
            if (!BannedPeers.canBan(chat)) {
                channels = groups = 0;
                break;
            } else if (BannedPeers.isGroup(chat)) {
                groups++;
            } else {
                channels++;
            }
        }
        if (channels + groups == 0 || channels != 0 && groups != 0) {
            item.setVisibility(View.GONE);
            return;
        }
        item.setVisibility(View.VISIBLE);
        item.setText(MikGramLang.get(groups != 0 ? MikGramLang.Phrase.BAN_GROUP : MikGramLang.Phrase.BAN_CHANNEL));
    }
}
