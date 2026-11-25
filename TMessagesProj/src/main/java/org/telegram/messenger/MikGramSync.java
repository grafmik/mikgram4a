package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MikGram: background colors, local pins and banned chats kept in sync between the devices of
 * a Telegram account, through mikgram.9mp.org, encrypted on the devices. Same format as
 * MikGram Desktop: PROTOCOL.md of grafmik/mikgram-sync.
 */
public class MikGramSync {

    public enum Status { UNKNOWN, DISABLED, PAIRING, KEY_REPLACED, WORKING, ACTIVE, OFFLINE, FAILED }

    public interface Listener {
        void onUpdate();
    }

    private static final String SERVER = "https://mikgram.9mp.org";
    private static final String BOT = "MikGramBot";
    private static final String SHARE_SCHEME = "mikgram-sync";
    private static final long START_DELAY = 10_000;
    private static final long DEBOUNCE = 3_000;
    private static final long PERIOD = 15 * 60_000;
    private static final long TOMBSTONE_MS = 180L * 86400 * 1000;
    private static final int KEY_SIZE = 32;
    private static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_ATTEMPTS = 3;

    private static final SecureRandom random = new SecureRandom();
    private static final HashMap<Integer, Syncer> syncers = new HashMap<>();
    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static boolean started;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mikgram_sync", Activity.MODE_PRIVATE);
    }

    private static long userId(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    // ---- Local timestamps (colors and bans are shared by all accounts) ----

    private static HashMap<Long, Long> readTimes(String name) {
        HashMap<Long, Long> result = new HashMap<>();
        try {
            JSONObject object = new JSONObject(prefs().getString(name, "{}"));
            for (Iterator<String> i = object.keys(); i.hasNext(); ) {
                String key = i.next();
                result.put(Long.parseLong(key), object.getLong(key));
            }
        } catch (Exception ignore) {
        }
        return result;
    }

    private static void writeTimes(String name, HashMap<Long, Long> times) {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<Long, Long> entry : times.entrySet()) {
                object.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } catch (Exception ignore) {
        }
        prefs().edit().putString(name, object.toString()).apply();
    }

    private static void noteTime(String name, long dialogId) {
        HashMap<Long, Long> times = readTimes(name);
        times.put(dialogId, System.currentTimeMillis());
        writeTimes(name, times);
    }

    private static byte[] getKey(long user) {
        String value = prefs().getString("key_" + user, null);
        return TextUtils.isEmpty(value) ? null : Base64.decode(value, Base64.NO_WRAP);
    }

    private static void setKey(long user, byte[] key) {
        if (key == null) {
            prefs().edit().remove("key_" + user).apply();
        } else {
            prefs().edit().putString("key_" + user, Base64.encodeToString(key, Base64.NO_WRAP)).apply();
        }
    }

    private static String getToken(long user) {
        return prefs().getString("token_" + user, null);
    }

    private static void setToken(long user, String token) {
        prefs().edit().putString("token_" + user, token).apply();
    }

    private static long getPinsTime(long user) {
        return prefs().getLong("pins_" + user, 0);
    }

    private static void setPinsTime(long user, long time) {
        prefs().edit().putLong("pins_" + user, time).apply();
    }

    // ---- Called by the MikGram features after a change made on this device ----

    public static void noteColorChanged(long dialogId) {
        noteTime("colors", dialogId);
        scheduleAll();
    }

    public static void noteBanChanged(long dialogId) {
        noteTime("banned", dialogId);
        scheduleAll();
    }

    public static void notePinsChanged(int account) {
        setPinsTime(userId(account), System.currentTimeMillis());
        syncer(account).schedule();
    }

    // ---- Crypto ----

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static String keyId(byte[] key) {
        try {
            byte[] prefix = "mikgram-sync-key".getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[prefix.length + key.length];
            System.arraycopy(prefix, 0, data, 0, prefix.length);
            System.arraycopy(key, 0, data, prefix.length, key.length);
            byte[] hash = sha256(data);
            StringBuilder hex = new StringBuilder();
            for (int a = 0; a < 16; a++) {
                hex.append(String.format("%02x", hash[a] & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String encrypt(byte[] key, long user, byte[] plain) throws Exception {
        byte[] nonce = new byte[NONCE_SIZE];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(("mikgram-sync:" + user).getBytes(StandardCharsets.UTF_8));
        byte[] sealed = cipher.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("MGS1".getBytes(StandardCharsets.US_ASCII));
        out.write(nonce);
        out.write(sealed);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    static byte[] decrypt(byte[] key, long user, String blob) {
        try {
            byte[] data = Base64.decode(blob, Base64.DEFAULT);
            if (data.length < 4 + NONCE_SIZE + 16 || data[0] != 'M' || data[1] != 'G' || data[2] != 'S' || data[3] != '1') {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, data, 4, NONCE_SIZE));
            cipher.updateAAD(("mikgram-sync:" + user).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(data, 4 + NONCE_SIZE, data.length - 4 - NONCE_SIZE);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Content ----

    static class ColorEntry { int color; long time; ColorEntry(int c, long t) { color = c; time = t; } }
    static class BanEntry { boolean banned; String title; long time; BanEntry(boolean b, String s, long t) { banned = b; title = s; time = t; } }
    static class PinsEntry { ArrayList<String> order = new ArrayList<>(); long time; }
    static class Payload {
        TreeMap<String, ColorEntry> colors = new TreeMap<>();
        TreeMap<String, BanEntry> banned = new TreeMap<>();
        PinsEntry pins;
    }

    static String peerKey(long dialogId) {
        return dialogId > 0 ? "u" + dialogId : "g" + (-dialogId);
    }

    static long dialogFromKey(String key) {
        try {
            long id = Long.parseLong(key.substring(1));
            if (id <= 0) {
                return 0;
            }
            return key.startsWith("u") ? id : key.startsWith("g") ? -id : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static JSONObject toJson(Payload payload) throws Exception {
        JSONObject colors = new JSONObject();
        for (Map.Entry<String, ColorEntry> entry : payload.colors.entrySet()) {
            colors.put(entry.getKey(), new JSONObject().put("c", entry.getValue().color).put("t", entry.getValue().time));
        }
        JSONObject banned = new JSONObject();
        for (Map.Entry<String, BanEntry> entry : payload.banned.entrySet()) {
            banned.put(entry.getKey(), new JSONObject().put("b", entry.getValue().banned).put("title", entry.getValue().title).put("t", entry.getValue().time));
        }
        JSONObject result = new JSONObject().put("v", 1).put("colors", colors).put("banned", banned);
        if (payload.pins != null) {
            JSONArray order = new JSONArray();
            for (String key : payload.pins.order) {
                order.put(key);
            }
            result.put("pins", new JSONObject().put("order", order).put("t", payload.pins.time));
        }
        return result;
    }

    static Payload fromJson(JSONObject object) {
        Payload result = new Payload();
        JSONObject colors = object.optJSONObject("colors");
        if (colors != null) {
            for (Iterator<String> i = colors.keys(); i.hasNext(); ) {
                String key = i.next();
                JSONObject data = colors.optJSONObject(key);
                if (data != null) {
                    result.colors.put(key, new ColorEntry(data.optInt("c", -1), data.optLong("t")));
                }
            }
        }
        JSONObject banned = object.optJSONObject("banned");
        if (banned != null) {
            for (Iterator<String> i = banned.keys(); i.hasNext(); ) {
                String key = i.next();
                JSONObject data = banned.optJSONObject(key);
                if (data != null) {
                    result.banned.put(key, new BanEntry(data.optBoolean("b"), data.optString("title"), data.optLong("t")));
                }
            }
        }
        JSONObject pins = object.optJSONObject("pins");
        if (pins != null) {
            result.pins = new PinsEntry();
            result.pins.time = pins.optLong("t");
            JSONArray order = pins.optJSONArray("order");
            for (int a = 0; order != null && a < order.length(); a++) {
                result.pins.order.add(order.optString(a));
            }
        }
        return result;
    }

    /** Canonical form, to know if the merged content differs from the server's. */
    static String canonical(Payload payload) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, ColorEntry> entry : payload.colors.entrySet()) {
            result.append('c').append(entry.getKey()).append(':').append(entry.getValue().color).append(':').append(entry.getValue().time).append(';');
        }
        for (Map.Entry<String, BanEntry> entry : payload.banned.entrySet()) {
            result.append('b').append(entry.getKey()).append(':').append(entry.getValue().banned).append(':').append(entry.getValue().title).append(':').append(entry.getValue().time).append(';');
        }
        if (payload.pins != null) {
            result.append('p').append(TextUtils.join(",", payload.pins.order)).append(':').append(payload.pins.time);
        }
        return result.toString();
    }

    static Payload localPayload(int account) {
        long now = System.currentTimeMillis();
        Payload result = new Payload();

        HashMap<Long, Integer> colors = DialogColors.forSync();
        HashMap<Long, Long> colorTimes = readTimes("colors");
        HashSet<Long> colorPeers = new HashSet<>(colors.keySet());
        colorPeers.addAll(colorTimes.keySet());
        for (long dialogId : colorPeers) {
            Integer color = colors.get(dialogId);
            Long time = colorTimes.get(dialogId);
            long t = time != null ? time : 0;
            if (color != null || now - t <= TOMBSTONE_MS) {
                result.colors.put(peerKey(dialogId), new ColorEntry(color != null ? color : -1, t));
            }
        }

        HashMap<Long, String> banned = new HashMap<>();
        for (BannedPeers.Entry entry : BannedPeers.list()) {
            banned.put(entry.dialogId, entry.title);
        }
        HashMap<Long, Long> banTimes = readTimes("banned");
        HashSet<Long> banPeers = new HashSet<>(banned.keySet());
        banPeers.addAll(banTimes.keySet());
        for (long dialogId : banPeers) {
            String title = banned.get(dialogId);
            Long time = banTimes.get(dialogId);
            long t = time != null ? time : 0;
            if (title != null || now - t <= TOMBSTONE_MS) {
                result.banned.put(peerKey(dialogId), new BanEntry(title != null, title != null ? title : "", t));
            }
        }

        ArrayList<Long> order = LocalPins.forSync(account);
        if (order != null) {
            result.pins = new PinsEntry();
            result.pins.time = getPinsTime(userId(account));
            for (long dialogId : order) {
                result.pins.order.add(peerKey(dialogId));
            }
        }
        return result;
    }

    static Payload merge(Payload local, Payload remote) {
        Payload result = new Payload();
        result.colors.putAll(remote.colors);
        result.banned.putAll(remote.banned);
        result.pins = remote.pins;
        for (Map.Entry<String, ColorEntry> entry : local.colors.entrySet()) {
            ColorEntry current = result.colors.get(entry.getKey());
            if (current == null || entry.getValue().time > current.time) {
                result.colors.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, BanEntry> entry : local.banned.entrySet()) {
            BanEntry current = result.banned.get(entry.getKey());
            if (current == null || entry.getValue().time > current.time) {
                result.banned.put(entry.getKey(), entry.getValue());
            }
        }
        if (local.pins != null && (result.pins == null || local.pins.time > result.pins.time)) {
            result.pins = local.pins;
        }
        long now = System.currentTimeMillis();
        result.colors.entrySet().removeIf(e -> e.getValue().color < 0 && now - e.getValue().time > TOMBSTONE_MS);
        result.banned.entrySet().removeIf(e -> !e.getValue().banned && now - e.getValue().time > TOMBSTONE_MS);
        return result;
    }

    static void apply(int account, Payload merged, Payload local) {
        HashMap<Long, Long> colorTimes = readTimes("colors");
        for (Map.Entry<String, ColorEntry> entry : merged.colors.entrySet()) {
            long dialogId = dialogFromKey(entry.getKey());
            if (dialogId == 0) {
                continue;
            }
            ColorEntry current = local.colors.get(entry.getKey());
            int currentColor = current != null ? current.color : -1;
            if (currentColor != entry.getValue().color) {
                DialogColors.applySynced(dialogId, entry.getValue().color);
            }
            Long time = colorTimes.get(dialogId);
            colorTimes.put(dialogId, Math.max(time != null ? time : 0, entry.getValue().time));
        }
        writeTimes("colors", colorTimes);

        HashMap<Long, Long> banTimes = readTimes("banned");
        for (Map.Entry<String, BanEntry> entry : merged.banned.entrySet()) {
            long dialogId = dialogFromKey(entry.getKey());
            if (dialogId == 0) {
                continue;
            }
            BanEntry current = local.banned.get(entry.getKey());
            boolean currentBanned = current != null && current.banned;
            if (currentBanned != entry.getValue().banned) {
                BannedPeers.applySynced(dialogId, entry.getValue().banned, entry.getValue().title);
            }
            Long time = banTimes.get(dialogId);
            banTimes.put(dialogId, Math.max(time != null ? time : 0, entry.getValue().time));
        }
        writeTimes("banned", banTimes);

        if (merged.pins != null) {
            long user = userId(account);
            boolean changed = local.pins == null || !local.pins.order.equals(merged.pins.order);
            if (changed && merged.pins.time >= getPinsTime(user)) {
                ArrayList<Long> order = new ArrayList<>();
                for (String key : merged.pins.order) {
                    long dialogId = dialogFromKey(key);
                    if (dialogId != 0) {
                        order.add(dialogId);
                    }
                }
                LocalPins.applySynced(account, order);
            }
            setPinsTime(user, Math.max(getPinsTime(user), merged.pins.time));
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    // ---- Network ----

    interface HttpDone {
        void run(int status, JSONObject body);
    }

    static void http(String method, String path, String token, JSONObject body, HttpDone done) {
        Utilities.globalQueue.postRunnable(() -> {
            int status = 0;
            JSONObject result = new JSONObject();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(SERVER + path).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Content-Type", "application/json");
                if (!TextUtils.isEmpty(token)) {
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                status = connection.getResponseCode();
                InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
                if (in != null) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        bytes.write(buffer, 0, read);
                    }
                    in.close();
                    String text = bytes.toString("UTF-8");
                    if (!TextUtils.isEmpty(text)) {
                        result = new JSONObject(text);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final int finalStatus = status;
            final JSONObject finalResult = result;
            AndroidUtilities.runOnUIThread(() -> done.run(finalStatus, finalResult));
        });
    }

    // ---- One account ----

    public static class Syncer {
        private final int account;
        private Status status = Status.UNKNOWN;
        private String error = "";
        private long lastSync;
        private boolean busy;
        private boolean again;
        private TLRPC.User bot;
        private final Runnable debounced = this::syncNow;

        Syncer(int account) {
            this.account = account;
        }

        public Status getStatus() { return status; }
        public String getError() { return error; }
        public long getLastSync() { return lastSync; }
        public boolean hasKey() { return getKey(userId(account)) != null; }

        public String shareText() {
            byte[] key = getKey(userId(account));
            return SHARE_SCHEME + ":1?u=" + userId(account) + "&k="
                + (key == null ? "" : Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        }

        void schedule() {
            if (hasKey()) {
                AndroidUtilities.cancelRunOnUIThread(debounced);
                AndroidUtilities.runOnUIThread(debounced, DEBOUNCE);
            }
        }

        public void syncNow() {
            if (hasKey()) {
                refresh();
            }
        }

        public void refresh() {
            if (begin()) {
                fetch(0);
            }
        }

        private boolean begin() {
            if (busy) {
                again = true;
                return false;
            }
            busy = true;
            again = false;
            status = Status.WORKING;
            notifyListeners();
            return true;
        }

        private void finish(Status newStatus, String newError) {
            busy = false;
            status = newStatus;
            error = newError == null ? "" : newError;
            if (newStatus == Status.ACTIVE) {
                lastSync = System.currentTimeMillis();
            }
            notifyListeners();
            if (again && hasKey()) {
                again = false;
                schedule();
            }
        }

        private void fail(int httpStatus, String details) {
            finish(httpStatus == 0 ? Status.OFFLINE : Status.FAILED, TextUtils.isEmpty(details) ? String.valueOf(httpStatus) : details);
        }

        private void forgetKey() {
            setKey(userId(account), null);
        }

        private void withBot(Utilities.Callback<TLRPC.User> then) {
            if (bot != null) {
                then.run(bot);
                return;
            }
            TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
            req.username = BOT;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                    MessagesController.getInstance(account).putUsers(resolved.users, false);
                    MessagesController.getInstance(account).putChats(resolved.chats, false);
                    for (TLRPC.User user : resolved.users) {
                        if (resolved.peer != null && user.id == resolved.peer.user_id) {
                            bot = user;
                        }
                    }
                }
                if (bot != null) {
                    then.run(bot);
                } else {
                    fail(-1, err != null ? err.text : "bot");
                }
            }));
        }

        private void authenticate(Runnable then) {
            withBot(user -> {
                // The main Mini App, set in @BotFather: a simple web view does not get the
                // signed user data (tgWebAppData).
                TLRPC.TL_messages_requestMainWebView req = new TLRPC.TL_messages_requestMainWebView();
                req.bot = MessagesController.getInstance(account).getInputUser(user);
                req.peer = MessagesController.getInputPeer(user);
                req.platform = "android";
                ConnectionsManager.getInstance(account).sendRequest(req, (response, err) -> AndroidUtilities.runOnUIThread(() -> {
                    if (!(response instanceof TLRPC.TL_webViewResultUrl)) {
                        fail(-1, err != null ? err.text : "webview");
                        return;
                    }
                    String initData = initDataFrom(((TLRPC.TL_webViewResultUrl) response).url);
                    if (TextUtils.isEmpty(initData)) {
                        fail(-1, "webview");
                        return;
                    }
                    JSONObject body = new JSONObject();
                    try {
                        body.put("initData", initData);
                    } catch (Exception ignore) {
                    }
                    http("POST", "/v1/auth", null, body, (status, result) -> {
                        String token = result.optString("token");
                        if (status == 200 && !TextUtils.isEmpty(token)) {
                            setToken(userId(account), token);
                            then.run();
                        } else {
                            fail(status, "auth");
                        }
                    });
                }));
            });
        }

        private void call(String method, String path, JSONObject body, HttpDone done, boolean retried) {
            Runnable send = () -> http(method, path, getToken(userId(account)), body, (status, result) -> {
                if (status == 401 && !retried) {
                    setToken(userId(account), null);
                    call(method, path, body, done, true);
                } else {
                    done.run(status, result);
                }
            });
            if (TextUtils.isEmpty(getToken(userId(account)))) {
                authenticate(send);
            } else {
                send.run();
            }
        }

        private void fetch(int attempt) {
            call("GET", "/v1/space", null, (status, result) -> {
                if (status != 200) {
                    fail(status, null);
                    return;
                }
                long user = userId(account);
                byte[] key = getKey(user);
                if (!result.optBoolean("exists")) {
                    if (key != null) {
                        forgetKey();
                    }
                    finish(Status.DISABLED, null);
                    return;
                } else if (key == null) {
                    finish(Status.PAIRING, null);
                    return;
                } else if (!keyId(key).equals(result.optString("keyId"))) {
                    forgetKey();
                    finish(Status.KEY_REPLACED, null);
                    return;
                }
                byte[] plain = decrypt(key, user, result.optString("blob"));
                if (plain == null) {
                    finish(Status.FAILED, "decrypt");
                    return;
                }
                try {
                    Payload remote = fromJson(new JSONObject(new String(plain, StandardCharsets.UTF_8)));
                    Payload local = localPayload(account);
                    Payload merged = merge(local, remote);
                    apply(account, merged, local);
                    if (canonical(merged).equals(canonical(remote))) {
                        finish(Status.ACTIVE, null);
                    } else {
                        upload(merged, result.optInt("version"), attempt);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    finish(Status.FAILED, "content");
                }
            }, false);
        }

        private void upload(Payload payload, int version, int attempt) {
            long user = userId(account);
            byte[] key = getKey(user);
            JSONObject body = new JSONObject();
            try {
                body.put("keyId", keyId(key));
                body.put("version", version);
                body.put("blob", encrypt(key, user, toJson(payload).toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                finish(Status.FAILED, "encrypt");
                return;
            }
            call("PUT", "/v1/space", body, (status, result) -> {
                String error = result.optString("error");
                if (status == 200) {
                    finish(Status.ACTIVE, null);
                } else if (status == 409 && version == 0) {
                    forgetKey();
                    finish(Status.PAIRING, null);
                } else if (status == 409 && "key".equals(error)) {
                    forgetKey();
                    finish(Status.KEY_REPLACED, null);
                } else if (status == 409 && attempt + 1 < MAX_ATTEMPTS) {
                    fetch(attempt + 1);
                } else {
                    fail(status, null);
                }
            }, false);
        }

        public void enable() {
            if (!begin()) {
                return;
            }
            byte[] key = new byte[KEY_SIZE];
            random.nextBytes(key);
            setKey(userId(account), key);
            upload(localPayload(account), 0, 0);
        }

        /** The text of the QR code shown by another device. False when it is not for this account. */
        public boolean pair(String text) {
            if (TextUtils.isEmpty(text)) {
                return false;
            }
            try {
                Uri uri = Uri.parse(text.trim());
                if (!SHARE_SCHEME.equals(uri.getScheme())) {
                    return false;
                }
                Uri query = Uri.parse("x://x?" + uri.getEncodedSchemeSpecificPart().replaceFirst("^1\\?", ""));
                long user = Long.parseLong(query.getQueryParameter("u"));
                byte[] key = Base64.decode(query.getQueryParameter("k"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                if (user != userId(account) || key.length != KEY_SIZE) {
                    return false;
                }
                setKey(user, key);
                refresh();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public void resetKey() {
            if (!begin()) {
                return;
            }
            long user = userId(account);
            byte[] key = new byte[KEY_SIZE];
            random.nextBytes(key);
            setKey(user, key);
            JSONObject body = new JSONObject();
            try {
                body.put("keyId", keyId(key));
                body.put("blob", encrypt(key, user, toJson(localPayload(account)).toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                finish(Status.FAILED, "encrypt");
                return;
            }
            call("POST", "/v1/space/reset", body, (status, result) -> {
                if (status == 200) {
                    finish(Status.ACTIVE, null);
                } else {
                    fail(status, null);
                }
            }, false);
        }

        public void removeRemote() {
            if (!begin()) {
                return;
            }
            call("DELETE", "/v1/space", null, (status, result) -> {
                if (status == 200) {
                    forgetKey();
                    finish(Status.DISABLED, null);
                } else {
                    fail(status, null);
                }
            }, false);
        }
    }

    static String initDataFrom(String url) {
        try {
            String fragment = Uri.parse(url).getEncodedFragment();
            if (fragment == null) {
                return null;
            }
            for (String part : fragment.split("&")) {
                if (part.startsWith("tgWebAppData=")) {
                    return Uri.decode(part.substring("tgWebAppData=".length()));
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    // ---- Registry ----

    public static Syncer syncer(int account) {
        Syncer result = syncers.get(account);
        if (result == null) {
            result = new Syncer(account);
            syncers.put(account, result);
        }
        return result;
    }

    private static void scheduleAll() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                syncer(account).schedule();
            }
        }
    }

    private static void syncAll() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                syncer(account).syncNow();
            }
        }
        AndroidUtilities.runOnUIThread(MikGramSync::syncAll, PERIOD);
    }

    /** At the start of the app: accounts already synced catch up, then every 15 minutes. */
    public static void start() {
        if (started) {
            return;
        }
        started = true;
        AndroidUtilities.runOnUIThread(MikGramSync::syncAll, START_DELAY);
    }

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onUpdate();
        }
    }
}
