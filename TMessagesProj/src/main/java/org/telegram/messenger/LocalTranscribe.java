package org.telegram.messenger;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.widget.Toast;

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
import java.util.Locale;

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
                AndroidUtilities.runOnUIThread(() -> recognize(messageObject, pcm, recognizers(context), 0));
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_FAILED) + " (decode)"));
            }
        });
        return true;
    }

    /** On-device first (offline, nothing leaves the phone), then the recognizer chosen in Android's settings. */
    private static ArrayList<Boolean> recognizers(Context context) {
        final ArrayList<Boolean> onDevice = new ArrayList<>(2);
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onDevice.add(true);
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            onDevice.add(false);
        }
        return onDevice;
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

    /** Must run on the main thread, like every SpeechRecognizer call. */
    private static void recognize(MessageObject messageObject, byte[] pcm, ArrayList<Boolean> recognizers, int attempt) {
        if (attempt >= recognizers.size()) {
            fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_FAILED));
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        final boolean onDevice = recognizers.get(attempt);
        final SpeechRecognizer recognizer;
        final ParcelFileDescriptor[] pipe;
        try {
            recognizer = onDevice
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                : SpeechRecognizer.createSpeechRecognizer(context);
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Exception e) {
            FileLog.e(e);
            recognize(messageObject, pcm, recognizers, attempt + 1);
            return;
        }
        final StringBuilder text = new StringBuilder();
        final boolean[] done = {false};
        final Runnable[] timeout = new Runnable[1];
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
            if (text.length() > 0 || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                succeed(messageObject, text.toString().trim());
            } else if (error != null) {
                FileLog.d("MikGram local transcription: recognizer " + (onDevice ? "on-device" : "default") + " error " + error);
                if (attempt + 1 < recognizers.size()) {
                    recognize(messageObject, pcm, recognizers, attempt + 1);
                } else {
                    fail(messageObject, MikGramLang.get(MikGramLang.Phrase.TRANSCRIBE_FAILED) + " (" + error + ")");
                }
            } else {
                succeed(messageObject, "");
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
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
        // Without a segmented session the recognizer stops at the first pause.
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            FileLog.e(e);
            finish.run(SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        // The recognizer may read in real time: allow twice the audio length, plus a margin.
        final long audioMs = pcm.length * 1000L / (SAMPLE_RATE * 2);
        AndroidUtilities.runOnUIThread(timeout[0], audioMs * 2 + 30_000);

        final ParcelFileDescriptor writeEnd = pipe[1];
        new Thread(() -> {
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                for (int offset = 0; offset < pcm.length; offset += 8192) {
                    stream.write(pcm, offset, Math.min(8192, pcm.length - offset));
                }
            } catch (Exception e) {
                // The recognizer closed its end: it stopped or failed, its listener reports why.
                FileLog.d("MikGram local transcription: pipe closed early: " + e);
            }
        }, "MikGramTranscribe").start();
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
