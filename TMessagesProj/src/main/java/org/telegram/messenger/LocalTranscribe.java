package org.telegram.messenger;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.icu.util.ULocale;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.LocaleList;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranscribeButton;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * MikGram: transcribes voice and round video messages on the device with Android's own speech
 * recognition, in place of Telegram's server transcription (a Premium feature). Nothing is sent
 * to Telegram: the audio is decoded here, then recognized by LocalSpeech in the chat's language.
 */
public class LocalTranscribe {

    private static final int PERMISSION_REQUEST = 5891;
    private static final long DOWNLOAD_WAIT_MS = 60_000;
    private static final int MIN_CHAT_TEXT = 20;
    private static final int MAX_CHAT_TEXT = 2000;
    // Bumped when the recognition changes: older local transcriptions are redone when reopened.
    private static final int ENGINE_VERSION = 2;

    private static Boolean recognitionAvailable;

    /** Local transcription replaces the server one: Android 13+, a recognizer, no Premium. */
    public static boolean enabled(int account) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || UserConfig.getInstance(account).isPremium()) {
            return false;
        }
        if (recognitionAvailable == null) {
            final Context context = ApplicationLoader.applicationContext;
            try {
                recognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    || SpeechRecognizer.isRecognitionAvailable(context);
            } catch (Exception e) {
                FileLog.e(e);
                recognitionAvailable = false;
            }
        }
        return recognitionAvailable;
    }

    /**
     * Called by TranscribeButton in place of messages.transcribeAudio.
     * Returns false when the server request should be sent as usual.
     */
    public static boolean transcribe(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !enabled(messageObject.currentAccount)) {
            return false;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Android's recognition service refuses callers without the microphone permission,
            // even when the audio comes from a file.
            AndroidUtilities.runOnUIThread(() -> {
                fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_NEEDS_MICROPHONE));
                if (LaunchActivity.instance != null) {
                    LaunchActivity.instance.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
                }
            });
            return true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final File file = waitForFile(messageObject);
                if (file == null) {
                    AndroidUtilities.runOnUIThread(() -> fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_FAILED) + " (download)"));
                    return;
                }
                final byte[] pcm = decode(file);
                MessagesStorage.getInstance(messageObject.currentAccount).getStorageQueue().postRunnable(() -> {
                    final String chatText = chatText(messageObject);
                    AndroidUtilities.runOnUIThread(() -> detectLanguage(chatText, language ->
                        LocalSpeech.recognize(context, pcm, languages(language), new LocalSpeech.Listener() {
                            @Override
                            public void onText(String text) {
                                succeed(messageObject, text);
                            }

                            @Override
                            public void onDownloading(String language) {
                                final Locale app = LocaleController.getInstance().getCurrentLocale();
                                final String name = Locale.forLanguageTag(language).getDisplayLanguage(app != null ? app : Locale.getDefault());
                                Toast.makeText(context, String.format(MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_DOWNLOADING), name), Toast.LENGTH_LONG).show();
                            }

                            @Override
                            public void onFailure(String details) {
                                fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_FAILED) + (TextUtils.isEmpty(details) ? "" : " (" + details + ")"));
                            }
                        })));
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_FAILED) + " (decode)"));
            }
        });
        return true;
    }

    private static File findFile(MessageObject messageObject) {
        final String attachPath = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(attachPath)) {
            final File file = new File(attachPath);
            if (file.exists() && file.length() > 0) {
                return file;
            }
        }
        final File file = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        return file != null && file.exists() && file.length() > 0 ? file : null;
    }

    /** Voice messages are usually downloaded already; otherwise start the download and wait for it. */
    private static File waitForFile(MessageObject messageObject) throws InterruptedException {
        File file = findFile(messageObject);
        if (file != null) {
            return file;
        }
        final TLRPC.Document document = messageObject.getDocument();
        if (document == null) {
            return null;
        }
        AndroidUtilities.runOnUIThread(() -> FileLoader.getInstance(messageObject.currentAccount).loadFile(document, messageObject, FileLoader.PRIORITY_HIGH, 0));
        final long until = SystemClock.elapsedRealtime() + DOWNLOAD_WAIT_MS;
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(500);
            file = findFile(messageObject);
            if (file != null && !FileLoader.getInstance(messageObject.currentAccount).isLoadingFile(FileLoader.getAttachFileName(document))) {
                return file;
            }
        }
        return null;
    }

    /** Decodes the audio track (Ogg Opus for voice, AAC for round videos) to 16 kHz mono 16-bit PCM. */
    private static byte[] decode(File file) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat format = null;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat trackFormat = extractor.getTrackFormat(i);
                final String trackMime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (trackMime != null && trackMime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = trackFormat;
                    mime = trackMime;
                    break;
                }
            }
            if (format == null) {
                throw new IllegalStateException("no audio track");
            }
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long inCount = 0, outCount = 0, sum = 0;
            int summed = 0;
            short last = 0;
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    final int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat outFormat = codec.getOutputFormat();
                    rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (outIndex >= 0) {
                    final ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        final ShortBuffer samples = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        while (samples.remaining() >= channels) {
                            int mono = 0;
                            for (int c = 0; c < channels; c++) {
                                mono += samples.get();
                            }
                            sum += mono / channels;
                            summed++;
                            inCount++;
                            // Box-filter resampling to 16 kHz: average the input samples of each output sample.
                            while ((outCount + 1) * rate <= inCount * LocalSpeech.SAMPLE_RATE) {
                                if (summed > 0) {
                                    last = (short) (sum / summed);
                                    sum = 0;
                                    summed = 0;
                                }
                                out.write(last & 0xFF);
                                out.write((last >> 8) & 0xFF);
                                outCount++;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            return out.toByteArray();
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignore) {}
                codec.release();
            }
            extractor.release();
        }
    }

    /**
     * Recent texts of the chat, the voice message's sender first: their voice messages are most
     * likely in the language they write in. Runs on the storage queue.
     */
    private static String chatText(MessageObject messageObject) {
        final MessagesStorage storage = MessagesStorage.getInstance(messageObject.currentAccount);
        final long senderId = messageObject.getSenderId();
        final StringBuilder sender = new StringBuilder();
        final StringBuilder others = new StringBuilder();
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d ORDER BY date DESC LIMIT 200", messageObject.getDialogId()));
            while (cursor.next() && sender.length() < MAX_CHAT_TEXT) {
                final NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                data.reuse();
                if (message == null || TextUtils.isEmpty(message.message)) {
                    continue;
                }
                final StringBuilder target = MessageObject.getFromChatId(message) == senderId ? sender : others;
                if (target.length() < MAX_CHAT_TEXT) {
                    target.append(message.message).append('\n');
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return sender.length() >= MIN_CHAT_TEXT ? sender.toString() : sender.append(others).toString();
    }

    /** The language of the chat's texts ("fr"), or null when there is too little text to tell. */
    private static void detectLanguage(String text, Utilities.Callback<String> done) {
        if (text == null || text.length() < MIN_CHAT_TEXT) {
            done.run(null);
            return;
        }
        final boolean[] called = {false};
        final Utilities.Callback<String> once = language -> {
            if (!called[0]) {
                called[0] = true;
                done.run(TextUtils.isEmpty(language) || "und".equals(language) ? null : language.split("-")[0]);
            }
        };
        LanguageDetector.detectLanguage(text, once::run, e -> once.run(null));
    }

    /**
     * The chat's language first, then the app's, each with a region ("fr-FR"). LocalSpeech moves to
     * the app's language only when no service knows the chat's.
     */
    private static ArrayList<String> languages(String chatLanguage) {
        final ArrayList<String> result = new ArrayList<>();
        if (chatLanguage != null) {
            result.add(withRegion(chatLanguage));
        }
        final String app = language();
        if (chatLanguage == null || !chatLanguage.equals(app.split("-")[0])) {
            result.add(app);
        }
        return result;
    }

    /** The region the user already has for this language (system languages, then the app's), else the likeliest. */
    private static String withRegion(String language) {
        final LocaleList locales = LocaleList.getDefault();
        for (int i = 0; i < locales.size(); i++) {
            final Locale locale = locales.get(i);
            if (language.equals(locale.getLanguage()) && !TextUtils.isEmpty(locale.getCountry())) {
                return language + "-" + locale.getCountry();
            }
        }
        final Locale app = LocaleController.getInstance().getCurrentLocale();
        if (app != null && language.equals(app.getLanguage()) && !TextUtils.isEmpty(app.getCountry())) {
            return language + "-" + app.getCountry();
        }
        return withLikelyRegion(language);
    }

    /** "fr" → "fr-FR", "en" → "en-US", "pt" → "pt-BR": the region recognizers most likely have a model for. */
    private static String withLikelyRegion(String language) {
        final String country = ULocale.addLikelySubtags(new ULocale(language)).getCountry();
        return TextUtils.isEmpty(country) ? language : language + "-" + country;
    }

    /** The app's language with a region, as recognizers expect ("fr-FR", not "fr"). */
    private static String language() {
        final Locale app = LocaleController.getInstance().getCurrentLocale();
        final Locale system = Locale.getDefault();
        final String lang = app != null ? app.getLanguage() : system.getLanguage();
        if (lang.equals(system.getLanguage()) && !TextUtils.isEmpty(system.getCountry())) {
            return lang + "-" + system.getCountry();
        }
        if (app != null && !TextUtils.isEmpty(app.getCountry())) {
            return lang + "-" + app.getCountry();
        }
        return "en".equals(lang) ? "en-US" : lang + "-" + lang.toUpperCase(Locale.ROOT);
    }

    private static SharedPreferences versions() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mikgram_transcriptions", Activity.MODE_PRIVATE);
    }

    private static String versionKey(MessageObject messageObject) {
        return messageObject.currentAccount + "_" + messageObject.getDialogId() + "_" + messageObject.getId();
    }

    /**
     * True when the message has a local transcription made by an older version of the recognition:
     * reopening it transcribes it again. Server transcriptions (with an id) are kept.
     */
    public static boolean redo(MessageObject messageObject) {
        return messageObject != null && messageObject.messageOwner != null
            && messageObject.messageOwner.voiceTranscriptionId == 0
            && enabled(messageObject.currentAccount)
            && versions().getInt(versionKey(messageObject), 0) < ENGINE_VERSION;
    }

    private static void succeed(MessageObject messageObject, String text) {
        versions().edit().putInt(versionKey(messageObject), ENGINE_VERSION).apply();
        TranscribeButton.openVideoTranscription(messageObject);
        messageObject.messageOwner.voiceTranscriptionOpen = true;
        TranscribeButton.finishTranscription(messageObject, 0, text);
    }

    /** Shows the error, then forgets the attempt so that the next tap tries again. */
    private static void fail(MessageObject messageObject, String message) {
        Toast.makeText(ApplicationLoader.applicationContext, message, Toast.LENGTH_LONG).show();
        TranscribeButton.finishTranscription(messageObject, 0, "");
        // finishTranscription posts its notification to the main thread: reset after it.
        AndroidUtilities.runOnUIThread(() -> {
            final TLRPC.Message owner = messageObject.messageOwner;
            owner.voiceTranscription = null;
            owner.voiceTranscriptionFinal = false;
            owner.voiceTranscriptionOpen = false;
            MessagesStorage.getInstance(messageObject.currentAccount).updateMessageVoiceTranscription(messageObject.getDialogId(), messageObject.getId(), null, owner);
            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject, null, null, (Boolean) false, null);
        });
    }
}
