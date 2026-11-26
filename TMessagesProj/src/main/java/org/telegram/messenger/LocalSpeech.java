package org.telegram.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.icu.util.ULocale;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.system.Os;
import android.text.TextUtils;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MikGram: recognizes decoded audio (16 kHz mono 16-bit PCM) with Android's speech recognition
 * services, for LocalTranscribe. The audio goes through a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE,
 * Android 13 and later), cut at its pauses, one session per piece.
 *
 * Only the languages asked for are used: a recognizer set to another language turns speech into
 * nonsense. When no service has the model of the language yet, Android's download prompt opens,
 * and recognition starts once the model is installed.
 */
public class LocalSpeech {

    public interface Listener {
        void onText(String text);

        /** The text so far, and how much of the audio it covers (0 to 100). */
        void onProgress(String text, int percent);

        /**
         * The model of this language (a tag such as "fr-FR") is needed: Android's prompt waits for
         * the user, or the download is under way (confirmed) since elapsed ms. Recognition follows.
         */
        void onDownloading(String language, boolean confirmed, long elapsed);

        void onFailure(String details);
    }

    public static final int SAMPLE_RATE = 16000;
    private static final int FRAME_BYTES = SAMPLE_RATE / 50 * 2;
    private static final int PAUSE_FRAMES = 35;
    private static final int QUIET_SEARCH_FRAMES = 250;
    private static final int LEAD_FRAMES = 15;
    private static final int TRAIL_FRAMES = 30;
    private static final int MAX_CHUNK_BYTES = SAMPLE_RATE * 2 * 20;
    private static final int PIPE_BYTES = 4096;
    private static final int DEFAULT_PIPE_BYTES = 65536;
    private static final int F_SETPIPE_SZ = 1031;
    private static final int MIN_PROGRESS_BYTES = SAMPLE_RATE;
    private static final long SESSION_GAP_MS = 300;
    private static final long RETRY_DELAY_MS = 1_500;
    private static final long SUPPORT_TIMEOUT_MS = 5_000;
    private static final long SUPPORT_GRACE_MS = 2_000;
    private static final long MODEL_POLL_MS = 5_000;
    private static final long MODEL_PROMPT_MS = 60_000;
    private static final long MODEL_DOWNLOAD_MS = 5 * 60_000;
    // Google's speech services fail (ERROR_CLIENT) when more than about 8 s of audio arrives at
    // once: the audio is fed at a multiple of real time, halved after each such failure.
    private static final int START_PACE = 8;
    private static final int BYTES_PER_MS = SAMPLE_RATE * 2 / 1000;
    // The last download prompt opened, so that another transcription waits instead of opening another.
    private static String promptedLanguage;
    private static long promptedAt;
    // Opens the download prompt of Google's speech services for EXTRA_LANGUAGE.
    private static final String ACTION_DOWNLOAD_LANGUAGE = "com.google.recognition.action.DOWNLOAD_LANGUAGE";

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** A recognition service; no component means Android's on-device recognizer. */
    private static class Service {
        final ComponentName component;
        String language;

        Service(ComponentName component) {
            this.component = component;
        }

        String label() {
            if (component == null) {
                return "on-device";
            }
            final String name = component.getPackageName();
            return name.substring(name.lastIndexOf('.') + 1);
        }

        SpeechRecognizer create(Context context) {
            return component == null
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                : SpeechRecognizer.createSpeechRecognizer(context, component);
        }
    }

    /** What a service can do with a language: the matching tags, or nulls. */
    private static class Support {
        String installed;
        String pending;
        String downloadable;
        String online;
        boolean unknown;
    }

    private static class Job {
        final Context context;
        final byte[] pcm;
        final ArrayList<int[]> chunks;
        final List<String> languages;
        final Listener listener;
        final ArrayList<Service> services;
        final ArrayList<Service> queue = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        final StringBuilder notes = new StringBuilder();
        Support[] supports;
        int language;
        int service;
        int chunk;
        int offset;
        boolean retried;
        boolean verified;
        boolean downloaded;
        int pace = START_PACE;

        Job(Context context, byte[] pcm, ArrayList<int[]> chunks, List<String> languages, Listener listener) {
            this.context = context;
            this.pcm = pcm;
            this.chunks = chunks;
            this.languages = languages;
            this.listener = listener;
            this.services = services(context);
        }

        void note(String note) {
            if (notes.length() > 0) {
                notes.append("; ");
            }
            notes.append(note);
        }
    }

    /** Recognizes pcm in the first of the languages ("fr-FR") that a service supports. Main thread. */
    public static void recognize(Context context, byte[] pcm, List<String> languages, Listener listener) {
        new Thread(() -> {
            final ArrayList<int[]> chunks = split(pcm);
            main.post(() -> start(new Job(context, pcm, chunks, languages, listener)));
        }, "MikGramSpeechSplit").start();
    }

    private static void start(Job job) {
        final Listener listener = job.listener;
        if (job.chunks.isEmpty()) {
            listener.onText("");
        } else if (job.services.isEmpty()) {
            listener.onFailure("no speech recognition service");
        } else {
            checkLanguage(job);
        }
    }

    /**
     * Cuts the audio at its pauses: some recognizers end the session at the first pause, and a
     * session that starts with more than a second of silence may come back empty. Each piece keeps
     * a little silence around its speech; long stretches without a pause are cut at their quietest
     * point, and pieces without speech are left out. Returns {start, end, speech start} in bytes.
     */
    static ArrayList<int[]> split(byte[] pcm) {
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

        final ArrayList<int[]> cuts = new ArrayList<>();
        int start = 0;
        int silent = 0;
        boolean speech = false;
        for (int f = 0; f < frames; f++) {
            if (level[f] <= silence) {
                silent++;
            } else {
                if (silent >= PAUSE_FRAMES && speech) {
                    final int cut = (f - silent / 2) * FRAME_BYTES;
                    cuts.add(new int[]{start, cut});
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
                    cuts.add(new int[]{start, cut});
                }
                start = cut;
                speech = false;
                for (int q = quietest; q <= f; q++) {
                    speech |= level[q] > silence;
                }
            }
        }
        if (speech) {
            cuts.add(new int[]{start, pcm.length});
        }

        final ArrayList<int[]> result = new ArrayList<>();
        for (int[] cut : cuts) {
            final int first = cut[0] / FRAME_BYTES;
            final int last = Math.min(frames, (cut[1] + FRAME_BYTES - 1) / FRAME_BYTES);
            int onset = -1;
            int end = -1;
            for (int f = first; f < last; f++) {
                if (level[f] > silence) {
                    if (onset < 0) {
                        onset = f;
                    }
                    end = f;
                }
            }
            if (onset < 0) {
                continue;
            }
            result.add(new int[]{
                Math.max(cut[0], (onset - LEAD_FRAMES) * FRAME_BYTES),
                Math.min(cut[1], (end + 1 + TRAIL_FRAMES) * FRAME_BYTES),
                onset * FRAME_BYTES,
            });
        }
        return result;
    }

    /** Android's on-device recognizer, then every recognition service, Google's first. */
    private static ArrayList<Service> services(Context context) {
        final ArrayList<Service> result = new ArrayList<>();
        try {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                result.add(new Service(null));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        final List<ResolveInfo> found = context.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        // Google's services read the audio they are given; others may listen to the microphone instead.
        for (int pass = 0; pass < 2; pass++) {
            for (ResolveInfo info : found) {
                if (info.serviceInfo != null && info.serviceInfo.packageName.startsWith("com.google.") == (pass == 0)) {
                    result.add(new Service(new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)));
                }
            }
        }
        return result;
    }

    private static Intent intent(String language) {
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        return intent;
    }

    private static String normalized(String tag) {
        return tag.replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static String languageOf(String tag) {
        return normalized(tag).split("-")[0];
    }

    /** The tag of the list for this language: the same tag, else the likeliest region ("fr-FR"), else any. */
    private static String match(List<String> tags, String wanted) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        final String language = languageOf(wanted);
        final String likely = normalized(ULocale.addLikelySubtags(new ULocale(language)).toLanguageTag());
        String any = null;
        String likeliest = null;
        for (String tag : tags) {
            if (normalized(tag).equals(normalized(wanted))) {
                return tag;
            }
            if (languageOf(tag).equals(language)) {
                if (any == null) {
                    any = tag;
                }
                final String region = new ULocale(normalized(tag).replace('-', '_')).getCountry();
                if (likeliest == null && !TextUtils.isEmpty(region) && likely.endsWith("-" + region.toLowerCase(Locale.ROOT))) {
                    likeliest = tag;
                }
            }
        }
        return likeliest != null ? likeliest : any;
    }

    private static void checkSupport(Context context, Service service, String wanted, OnSupport done) {
        final SpeechRecognizer recognizer;
        try {
            recognizer = service.create(context);
        } catch (Exception e) {
            FileLog.e(e);
            final Support unknown = new Support();
            unknown.unknown = true;
            done.run(unknown);
            return;
        }
        final boolean[] finished = {false};
        final Runnable[] fallback = new Runnable[1];
        final OnSupport finish = support -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            main.removeCallbacks(fallback[0]);
            try {
                recognizer.destroy();
            } catch (Exception ignore) {}
            done.run(support);
        };
        fallback[0] = () -> {
            final Support unknown = new Support();
            unknown.unknown = true;
            finish.run(unknown);
        };
        main.postDelayed(fallback[0], SUPPORT_TIMEOUT_MS);
        try {
            recognizer.checkRecognitionSupport(intent(wanted), context.getMainExecutor(), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport result) {
                    final Support support = new Support();
                    support.installed = match(result.getInstalledOnDeviceLanguages(), wanted);
                    support.pending = match(result.getPendingOnDeviceLanguages(), wanted);
                    support.downloadable = match(result.getSupportedOnDeviceLanguages(), wanted);
                    support.online = match(result.getOnlineLanguages(), wanted);
                    finish.run(support);
                }

                @Override
                public void onError(int error) {
                    // Some services report an error first, then their answer: wait a little for it.
                    main.removeCallbacks(fallback[0]);
                    main.postDelayed(fallback[0], SUPPORT_GRACE_MS);
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
            fallback[0].run();
        }
    }

    private interface OnSupport {
        void run(Support support);
    }

    /** Asks every service about the current language, then picks the services to use. */
    private static void checkLanguage(Job job) {
        if (job.language >= job.languages.size()) {
            job.listener.onFailure(job.notes.toString());
            return;
        }
        job.supports = new Support[job.services.size()];
        job.queue.clear();
        checkNext(job, 0);
    }

    private static void checkNext(Job job, int index) {
        if (index >= job.services.size()) {
            choose(job);
            return;
        }
        checkSupport(job.context, job.services.get(index), job.languages.get(job.language), support -> {
            job.supports[index] = support;
            main.postDelayed(() -> checkNext(job, index + 1), SESSION_GAP_MS);
        });
    }

    /**
     * Models on the device first, then online recognition. Without either, the model is downloaded;
     * services that cannot tell what they support come last: they may not follow the language asked.
     */
    private static void choose(Job job) {
        final String wanted = job.languages.get(job.language);
        FileLog.d("MikGram local transcription: " + wanted + " → " + describe(job));
        queue(job, 0);
        queue(job, 1);
        if (job.queue.isEmpty() && downloadModel(job)) {
            return;
        }
        queue(job, 2);
        if (!job.queue.isEmpty()) {
            startService(job, 0);
        } else {
            job.note(wanted + " not supported");
            job.language++;
            checkLanguage(job);
        }
    }

    /** Adds the services of one kind: 0 with the model on the device, 1 online, 2 unknown. */
    private static void queue(Job job, int kind) {
        for (int i = 0; i < job.services.size(); i++) {
            final Support support = job.supports[i];
            final String tag = kind == 0 ? support.installed : kind == 1 ? support.online : support.unknown ? job.languages.get(job.language) : null;
            final Service service = job.services.get(i);
            if (tag != null && !job.queue.contains(service)) {
                service.language = tag;
                job.queue.add(service);
            }
        }
    }

    private static String describe(Job job) {
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < job.services.size(); i++) {
            final Support support = job.supports[i];
            result.append(job.services.get(i).label()).append(support.unknown ? " unknown" : "")
                .append(support.installed != null ? " installed " + support.installed : "")
                .append(support.pending != null ? " pending " + support.pending : "")
                .append(support.downloadable != null ? " downloadable " + support.downloadable : "")
                .append(support.online != null ? " online " + support.online : "").append(", ");
        }
        return result.toString();
    }

    /**
     * Gets the model of the current language onto the device: waits for a download under way, else
     * opens Android's download prompt. Returns false when no service offers one.
     */
    private static boolean downloadModel(Job job) {
        if (job.downloaded) {
            return false;
        }
        for (Support support : job.supports) {
            // Android does not list installed languages as downloadable: another variant ("fr-BE"
            // when "fr-FR" is there) would come up instead.
            if (support.installed != null) {
                return false;
            }
        }
        int pending = -1;
        int downloadable = -1;
        int prompt = -1;
        for (int i = 0; i < job.services.size(); i++) {
            final Support support = job.supports[i];
            if (support.pending != null && pending < 0) {
                pending = i;
            }
            if (support.downloadable != null) {
                if (downloadable < 0) {
                    downloadable = i;
                }
                final Service service = job.services.get(i);
                if (prompt < 0 && service.component != null && promptIntent(service, support.downloadable).resolveActivity(job.context.getPackageManager()) != null) {
                    prompt = i;
                }
            }
        }
        if (pending < 0 && downloadable < 0) {
            return false;
        }
        job.downloaded = true;
        final int index = pending >= 0 ? pending : prompt >= 0 ? prompt : downloadable;
        final Service service = job.services.get(index);
        final String tag = pending >= 0 ? job.supports[index].pending : job.supports[index].downloadable;
        final long now = SystemClock.elapsedRealtime();
        final boolean prompted = tag.equals(promptedLanguage) && now - promptedAt < MODEL_PROMPT_MS;
        job.listener.onDownloading(tag, pending >= 0, 0);
        if (pending < 0 && !prompted) {
            promptedLanguage = tag;
            promptedAt = now;
            if (index == prompt) {
                // Google's speech services cannot open this prompt themselves when another app asks
                // (Android blocks activity starts from the background): MikGram opens it.
                try {
                    job.context.startActivity(promptIntent(service, tag).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    FileLog.e(e);
                }
            } else {
                try {
                    final SpeechRecognizer recognizer = service.create(job.context);
                    recognizer.triggerModelDownload(intent(tag));
                    main.postDelayed(recognizer::destroy, SUPPORT_TIMEOUT_MS);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        waitForModel(job, service, tag, now, now + MODEL_PROMPT_MS, now + MODEL_DOWNLOAD_MS, pending >= 0);
        return true;
    }

    private static Intent promptIntent(Service service, String tag) {
        return new Intent(ACTION_DOWNLOAD_LANGUAGE)
            .setPackage(service.component.getPackageName())
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
    }

    private static void waitForModel(Job job, Service service, String tag, long since, long promptUntil, long downloadUntil, boolean started) {
        main.postDelayed(() -> checkSupport(job.context, service, tag, support -> {
            final long now = SystemClock.elapsedRealtime();
            if (support.installed != null) {
                promptedLanguage = null;
                service.language = support.installed;
                job.queue.clear();
                job.queue.add(service);
                main.postDelayed(() -> startService(job, 0), SESSION_GAP_MS);
            } else if (support.pending != null && now < downloadUntil || support.pending == null && !started && now < promptUntil) {
                final boolean confirmed = started || support.pending != null;
                job.listener.onDownloading(tag, confirmed, now - since);
                waitForModel(job, service, tag, since, promptUntil, downloadUntil, confirmed);
            } else {
                job.note(tag + (support.pending != null ? " model still downloading" : " model not downloaded"));
                job.listener.onFailure(job.notes.toString());
            }
        }), MODEL_POLL_MS);
    }

    private static void startService(Job job, int index) {
        if (index >= job.queue.size()) {
            if (!downloadModel(job)) {
                job.listener.onFailure(job.notes.toString());
            }
            return;
        }
        job.service = index;
        job.chunk = 0;
        job.offset = job.chunks.get(0)[0];
        job.retried = false;
        job.verified = false;
        job.text.setLength(0);
        job.listener.onProgress("", 0);
        recognizeChunk(job);
    }

    private static void nextChunk(Job job) {
        job.listener.onProgress(job.text.toString(), (int) (100L * job.chunks.get(job.chunk)[1] / job.pcm.length));
        job.chunk++;
        job.retried = false;
        if (job.chunk < job.chunks.size()) {
            job.offset = job.chunks.get(job.chunk)[0];
        }
        recognizeChunk(job);
    }

    /** Recognizes the current chunk from job.offset, in a session of its own. */
    private static void recognizeChunk(Job job) {
        if (job.chunk >= job.chunks.size()) {
            job.listener.onText(job.text.toString().trim());
            return;
        }
        final Service service = job.queue.get(job.service);
        final int start = job.offset;
        final int end = job.chunks.get(job.chunk)[1];
        final SpeechRecognizer recognizer;
        final ParcelFileDescriptor[] pipe;
        try {
            recognizer = service.create(job.context);
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Exception e) {
            FileLog.e(e);
            job.note(service.label() + " unavailable");
            startService(job, job.service + 1);
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
        final OnError finish = error -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            main.removeCallbacks(timeout[0]);
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
                + " " + service.label() + " " + service.language + " error " + error
                + " read " + read + "/" + (end - start) + " text " + text.length());
            final boolean whole = complete.get();
            final String heard = text.toString().trim();
            // Right after destroy(), the service may still be closing the session: a new one at once
            // fails with ERROR_SERVER_DISCONNECTED or ERROR_RECOGNIZER_BUSY.
            main.postDelayed(() -> afterSession(job, service, start, read, whole, error, heard), SESSION_GAP_MS);
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

        final Intent intent = intent(service.language);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);

        final ParcelFileDescriptor writeEnd = pipe[1];
        final int pace = job.pace;
        writer[0] = new Thread(() -> {
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                final long began = SystemClock.elapsedRealtime();
                for (int offset = start; offset < end; offset += PIPE_BYTES) {
                    final int count = Math.min(PIPE_BYTES, end - offset);
                    stream.write(job.pcm, offset, count);
                    written.addAndGet(count);
                    final long due = (offset + count - start) / ((long) BYTES_PER_MS * pace) - (SystemClock.elapsedRealtime() - began);
                    if (due > 0) {
                        Thread.sleep(due);
                    }
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
        main.postDelayed(timeout[0], audioMs * 2 + 15_000);
        writer[0].start();
    }

    /** Decides what follows a session: the next chunk, the rest of this one, a retry, or the next service. */
    private static void afterSession(Job job, Service service, int start, int read, boolean complete, Integer error, String text) {
        final boolean progressed = complete || read >= MIN_PROGRESS_BYTES;
        if (error != null && error == SpeechRecognizer.ERROR_CLIENT && job.pace > 1) {
            // Fed faster than it recognizes: the same piece again, slower.
            job.pace /= 2;
            job.offset = start;
            recognizeChunk(job);
            return;
        }
        final boolean busy = error != null && (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
        if (!progressed && busy && !job.retried) {
            // The service was still busy with an earlier session: once more, a little later.
            job.retried = true;
            main.postDelayed(() -> recognizeChunk(job), RETRY_DELAY_MS);
            return;
        }
        if (!progressed && !job.verified) {
            // It never read the audio (it listens to the microphone) or failed at once: whatever
            // it heard is not this message.
            job.note(service.label() + (error != null ? " error " + error : " ignores the audio"));
            startService(job, job.service + 1);
            return;
        }
        job.verified |= progressed;
        final boolean failed = error != null && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        final boolean language = error != null && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
        if (failed && !language && !job.retried) {
            job.retried = true;
            job.offset = start;
            recognizeChunk(job);
            return;
        }
        if (text.length() > 0) {
            if (job.text.length() > 0) {
                job.text.append(' ');
            }
            job.text.append(text);
        }
        final int[] chunk = job.chunks.get(job.chunk);
        if (failed) {
            if (job.text.length() == 0) {
                job.note(service.label() + " error " + error);
                startService(job, job.service + 1);
            } else {
                nextChunk(job);
            }
        } else if (text.length() == 0 && complete && start == chunk[0] && !job.retried) {
            // Nothing recognized in a piece that has speech: once more, from the speech itself.
            job.retried = true;
            job.offset = chunk[2];
            recognizeChunk(job);
        } else if (!complete && progressed) {
            // Stopped at a pause in the middle of the chunk: go on from where it stopped.
            job.offset = start + read;
            recognizeChunk(job);
        } else {
            nextChunk(job);
        }
    }

    private interface OnError {
        void run(Integer error);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (Exception ignore) {}
    }
}
