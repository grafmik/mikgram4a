package org.telegram.messenger;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.icu.util.ULocale;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.system.Os;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranscribeButton;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MikGram: transcribes voice and round video messages on the device with Android's own speech
 * recognition, in place of Telegram's server transcription (a Premium feature). Nothing is sent
 * to Telegram: the audio is decoded here and handed to the recognizer through a pipe
 * (RecognizerIntent.EXTRA_AUDIO_SOURCE, Android 13 and later).
 */
public class LocalTranscribe {

    private static final int SAMPLE_RATE = 16000;
    private static final int PERMISSION_REQUEST = 5891;
    private static final long DOWNLOAD_WAIT_MS = 60_000;
    private static final int FRAME_BYTES = SAMPLE_RATE / 50 * 2;
    private static final int PAUSE_FRAMES = 35;
    private static final int QUIET_SEARCH_FRAMES = 250;
    private static final int MAX_CHUNK_BYTES = SAMPLE_RATE * 2 * 20;
    private static final int PIPE_BYTES = 4096;
    private static final int DEFAULT_PIPE_BYTES = 65536;
    private static final int F_SETPIPE_SZ = 1031;
    private static final int MIN_PROGRESS_BYTES = SAMPLE_RATE;
    private static final int MIN_CHAT_TEXT = 20;
    private static final int MAX_CHAT_TEXT = 2000;

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
                final ArrayList<int[]> chunks = split(pcm);
                MessagesStorage.getInstance(messageObject.currentAccount).getStorageQueue().postRunnable(() -> {
                    final String chatText = chatText(messageObject);
                    AndroidUtilities.runOnUIThread(() -> detectLanguage(chatText, language ->
                        recognize(new Job(messageObject, pcm, chunks, attempts(context, language)))));
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
                            while ((outCount + 1) * rate <= inCount * SAMPLE_RATE) {
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

    /** One transcription: the audio cut at its pauses, and the recognizers and languages to try in turn. */
    private static class Job {
        final MessageObject messageObject;
        final byte[] pcm;
        final ArrayList<int[]> chunks;
        final ArrayList<Attempt> attempts;
        final StringBuilder text = new StringBuilder();
        int attempt;
        int chunk;
        int offset;

        Job(MessageObject messageObject, byte[] pcm, ArrayList<int[]> chunks, ArrayList<Attempt> attempts) {
            this.messageObject = messageObject;
            this.pcm = pcm;
            this.chunks = chunks;
            this.attempts = attempts;
            offset = chunks.isEmpty() ? 0 : chunks.get(0)[0];
        }
    }

    private static class Attempt {
        final boolean onDevice;
        final String language;

        Attempt(boolean onDevice, String language) {
            this.onDevice = onDevice;
            this.language = language;
        }
    }

    /**
     * Cuts the audio (16-bit PCM) in the middle of its pauses: some recognizers end the session at
     * the first pause, whatever EXTRA_SEGMENTED_SESSION says. Long stretches without a pause are cut
     * at their quietest point. Chunks without speech are left out. Returns [start, end) in bytes.
     */
    private static ArrayList<int[]> split(byte[] pcm) {
        final int frames = pcm.length / FRAME_BYTES;
        final float[] level = new float[frames];
        for (int f = 0; f < frames; f++) {
            long sum = 0;
            for (int i = f * FRAME_BYTES; i < (f + 1) * FRAME_BYTES; i += 2) {
                sum += Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
            }
            level[f] = sum / (FRAME_BYTES / 2f);
        }
        final float[] sorted = level.clone();
        Arrays.sort(sorted);
        final float floor = frames > 0 ? sorted[frames / 10] : 0;
        final float peak = frames > 0 ? sorted[frames * 9 / 10] : 0;
        final float silence = floor + (peak - floor) * 0.1f;

        final ArrayList<int[]> result = new ArrayList<>();
        int start = 0;
        int silent = 0;
        boolean speech = false;
        for (int f = 0; f < frames; f++) {
            if (level[f] <= silence) {
                silent++;
            } else {
                if (silent >= PAUSE_FRAMES && speech) {
                    final int cut = (f - silent / 2) * FRAME_BYTES;
                    result.add(new int[]{start, cut});
                    start = cut;
                    speech = false;
                }
                silent = 0;
                speech = true;
            }
            if ((f + 1) * FRAME_BYTES - start >= MAX_CHUNK_BYTES) {
                int quietest = f;
                for (int q = Math.max(start / FRAME_BYTES + 1, f - QUIET_SEARCH_FRAMES); q <= f; q++) {
                    if (level[q] < level[quietest]) {
                        quietest = q;
                    }
                }
                final int cut = quietest * FRAME_BYTES;
                if (speech) {
                    result.add(new int[]{start, cut});
                }
                start = cut;
                speech = false;
                for (int q = quietest; q <= f; q++) {
                    speech |= level[q] > silence;
                }
            }
        }
        if (speech) {
            result.add(new int[]{start, pcm.length});
        }
        return result;
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

    /** The chat's language first, then the app's; for each, the on-device recognizer, then Android's default one. */
    private static ArrayList<Attempt> attempts(Context context, String chatLanguage) {
        final ArrayList<String> languages = new ArrayList<>();
        if (chatLanguage != null) {
            addOnce(languages, withRegion(chatLanguage));
            addOnce(languages, withLikelyRegion(chatLanguage));
        }
        final String app = language();
        addOnce(languages, app);
        addOnce(languages, withLikelyRegion(app.split("-")[0]));
        final ArrayList<Attempt> result = new ArrayList<>();
        final boolean onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        final boolean standard = SpeechRecognizer.isRecognitionAvailable(context);
        for (String language : languages) {
            if (onDevice) {
                result.add(new Attempt(true, language));
            }
            if (standard) {
                result.add(new Attempt(false, language));
            }
        }
        return result;
    }

    private static void addOnce(ArrayList<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
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

    private static void nextAttempt(Job job) {
        job.attempt++;
        job.chunk = 0;
        job.offset = job.chunks.isEmpty() ? 0 : job.chunks.get(0)[0];
        job.text.setLength(0);
        recognize(job);
    }

    private static void nextChunk(Job job) {
        job.chunk++;
        if (job.chunk < job.chunks.size()) {
            job.offset = job.chunks.get(job.chunk)[0];
        }
        recognize(job);
    }

    /** Recognizes the job's current chunk, from job.offset. Must run on the main thread, like every SpeechRecognizer call. */
    private static void recognize(Job job) {
        final MessageObject messageObject = job.messageObject;
        if (job.chunk >= job.chunks.size()) {
            succeed(messageObject, job.text.toString().trim());
            return;
        }
        if (job.attempt >= job.attempts.size()) {
            fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_FAILED));
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        final Attempt attempt = job.attempts.get(job.attempt);
        final int start = job.offset;
        final int end = job.chunks.get(job.chunk)[1];
        final SpeechRecognizer recognizer;
        final ParcelFileDescriptor[] pipe;
        try {
            recognizer = attempt.onDevice
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                : SpeechRecognizer.createSpeechRecognizer(context);
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Exception e) {
            FileLog.e(e);
            nextAttempt(job);
            return;
        }
        // A small pipe tells how far the recognizer has read when it stops early.
        int pipeSize = DEFAULT_PIPE_BYTES;
        try {
            pipeSize = Os.fcntlInt(pipe[1].getFileDescriptor(), F_SETPIPE_SZ, PIPE_BYTES);
        } catch (Exception ignore) {}
        final int buffered = pipeSize;
        final StringBuilder text = new StringBuilder();
        final AtomicInteger written = new AtomicInteger();
        final AtomicBoolean complete = new AtomicBoolean();
        final boolean[] done = {false};
        final Runnable[] timeout = new Runnable[1];
        final Thread[] writer = new Thread[1];
        final Utilities.Callback<Integer> finish = error -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout[0]);
            try {
                recognizer.destroy();
            } catch (Exception ignore) {}
            closeQuietly(pipe[0]);
            closeQuietly(pipe[1]);
            try {
                writer[0].join(1000);
            } catch (InterruptedException ignore) {}
            final int read = complete.get() ? end - start : Math.max(0, written.get() - buffered);
            FileLog.d("MikGram local transcription: chunk " + (job.chunk + 1) + "/" + job.chunks.size()
                + " " + attempt.language + (attempt.onDevice ? " on-device" : " default")
                + " error " + error + " read " + read + "/" + (end - start)
                + " text " + text.length());
            final boolean benign = error == null || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (text.length() > 0) {
                if (job.text.length() > 0) {
                    job.text.append(' ');
                }
                job.text.append(text.toString().trim());
            }
            if (!benign || (!complete.get() && read < MIN_PROGRESS_BYTES)) {
                // An error, or a recognizer that never read the audio (one listening to the microphone):
                // before any text, try the next recognizer or language; after, skip the chunk.
                if (job.text.length() == 0) {
                    nextAttempt(job);
                } else {
                    nextChunk(job);
                }
            } else if (!complete.get()) {
                // Stopped at a pause in the middle of the chunk: go on from where it stopped.
                job.offset = start + read;
                recognize(job);
            } else {
                nextChunk(job);
            }
        };
        timeout[0] = () -> finish.run(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        recognizer.setRecognitionListener(new RecognitionListener() {
            private void append(Bundle results) {
                final ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty() && !TextUtils.isEmpty(matches.get(0))) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(matches.get(0).trim());
                }
            }

            @Override public void onSegmentResults(Bundle segmentResults) { append(segmentResults); }
            @Override public void onEndOfSegmentedSession() { finish.run(null); }
            @Override public void onResults(Bundle results) { append(results); finish.run(null); }
            @Override public void onError(int error) { finish.run(error); }
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, attempt.language);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);

        final ParcelFileDescriptor writeEnd = pipe[1];
        writer[0] = new Thread(() -> {
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                for (int offset = start; offset < end; offset += PIPE_BYTES) {
                    final int count = Math.min(PIPE_BYTES, end - offset);
                    stream.write(job.pcm, offset, count);
                    written.addAndGet(count);
                }
                complete.set(true);
            } catch (Exception e) {
                // The recognizer closed its end: it stopped or failed, its listener reports why.
            }
        }, "MikGramTranscribe");
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            FileLog.e(e);
            writer[0].start();
            finish.run(SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        // The recognizer may read in real time: allow twice the audio length, plus a margin.
        final long audioMs = (end - start) * 1000L / (SAMPLE_RATE * 2);
        AndroidUtilities.runOnUIThread(timeout[0], audioMs * 2 + 15_000);
        writer[0].start();
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

    private static void succeed(MessageObject messageObject, String text) {
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

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (Exception ignore) {}
    }
}
