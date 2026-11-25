package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MikGramLang;
import org.telegram.messenger.MikGramSync;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.LaunchActivity;

import java.util.HashMap;

/**
 * MikGram: the Sync screen, see {@link MikGramSync}. Shows this account's QR code once synced,
 * or scans the one of an already synced device.
 */
public class MikGramSyncAlert {

    private static final int CAMERA_REQUEST = 5892;

    public static void show() {
        show(LaunchActivity.getSafeLastFragment());
    }

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final int account = UserConfig.selectedAccount;
        final MikGramSync.Syncer syncer = MikGramSync.syncer(account);

        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(4), dp(24), 0);
        final ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(MikGramLang.get(MikGramLang.Phrase.SYNC_TITLE));
        builder.setView(scrollView);
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        final AlertDialog dialog = builder.create();

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> fill(fragment, activity, content, syncer, resourcesProvider);
        final MikGramSync.Listener listener = () -> AndroidUtilities.runOnUIThread(rebuild[0]);
        MikGramSync.addListener(listener);
        dialog.setOnDismissListener(d -> MikGramSync.removeListener(listener));
        rebuild[0].run();
        fragment.showDialog(dialog);
        if (!syncer.hasKey()) {
            syncer.refresh();
        }
    }

    private static TextView addText(Context context, LinearLayout content, String text, Theme.ResourcesProvider resourcesProvider) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        view.setText(text);
        content.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));
        return view;
    }

    private static void addButton(Context context, LinearLayout content, String text, Runnable action, Theme.ResourcesProvider resourcesProvider) {
        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        content.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
    }

    private static Bitmap qr(String text, int size) {
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static void confirm(BaseFragment fragment, String text, Runnable action) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> action.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void pair(Activity activity, MikGramSync.Syncer syncer, String text) {
        if (!syncer.pair(text)) {
            Toast.makeText(activity, MikGramLang.get(MikGramLang.Phrase.SYNC_WRONG_KEY), Toast.LENGTH_LONG).show();
        }
    }

    private static void scan(BaseFragment fragment, Activity activity, MikGramSync.Syncer syncer) {
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        CameraScanActivity.showAsSheet(fragment, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                pair(activity, syncer, text);
            }
        });
    }

    private static void fill(BaseFragment fragment, Activity activity, LinearLayout content, MikGramSync.Syncer syncer, Theme.ResourcesProvider resourcesProvider) {
        content.removeAllViews();
        final MikGramSync.Status status = syncer.getStatus();
        if (syncer.hasKey()) {
            final int size = dp(220);
            Bitmap bitmap = qr(syncer.shareText(), size);
            if (bitmap != null) {
                ImageView image = new ImageView(activity);
                image.setImageBitmap(bitmap);
                content.addView(image, LayoutHelper.createLinear(220, 220, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 8));
            }
            addText(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_SCAN_ABOUT), resourcesProvider);
            addButton(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_COPY_KEY), () -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("MikGram", syncer.shareText()));
                Toast.makeText(activity, MikGramLang.get(MikGramLang.Phrase.SYNC_COPIED), Toast.LENGTH_SHORT).show();
            }, resourcesProvider);
            addButton(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_NOW), syncer::refresh, resourcesProvider);
            addButton(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_NEW_KEY), () ->
                confirm(fragment, MikGramLang.get(MikGramLang.Phrase.SYNC_NEW_KEY_CONFIRM), syncer::resetKey), resourcesProvider);
            addButton(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_DELETE), () ->
                confirm(fragment, MikGramLang.get(MikGramLang.Phrase.SYNC_DELETE_CONFIRM), syncer::removeRemote), resourcesProvider);
        } else if (status == MikGramSync.Status.PAIRING || status == MikGramSync.Status.KEY_REPLACED) {
            addText(activity, content, MikGramLang.get(status == MikGramSync.Status.PAIRING
                ? MikGramLang.Phrase.SYNC_PAIRING_ABOUT
                : MikGramLang.Phrase.SYNC_KEY_REPLACED), resourcesProvider);
            addButton(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_SCAN), () -> scan(fragment, activity, syncer), resourcesProvider);
            addButton(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_PASTE), () -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = clipboard.getPrimaryClip();
                CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(activity) : null;
                pair(activity, syncer, text == null ? null : text.toString());
            }, resourcesProvider);
        } else {
            addText(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_ABOUT), resourcesProvider);
            if (status != MikGramSync.Status.WORKING && status != MikGramSync.Status.UNKNOWN) {
                addButton(activity, content, MikGramLang.get(MikGramLang.Phrase.SYNC_ENABLE), syncer::enable, resourcesProvider);
            }
        }
        String line = null;
        if (status == MikGramSync.Status.WORKING || status == MikGramSync.Status.UNKNOWN) {
            line = MikGramLang.get(MikGramLang.Phrase.SYNC_WORKING);
        } else if (status == MikGramSync.Status.OFFLINE) {
            line = MikGramLang.get(MikGramLang.Phrase.SYNC_OFFLINE);
        } else if (status == MikGramSync.Status.FAILED) {
            line = MikGramLang.get(MikGramLang.Phrase.SYNC_ERROR) + " (" + syncer.getError() + ")";
        } else if (status == MikGramSync.Status.ACTIVE && syncer.getLastSync() != 0) {
            line = MikGramLang.get(MikGramLang.Phrase.SYNC_LAST) + " " + DateFormat.getTimeFormat(activity).format(syncer.getLastSync());
        }
        if (line != null) {
            TextView view = addText(activity, content, line, resourcesProvider);
            view.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        }
    }
}
