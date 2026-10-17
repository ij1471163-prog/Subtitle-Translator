package com.leno.subtitletranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * VoskOfflineEngine - ترجمة صوتية بدون إنترنت (PRO فقط)
 *
 * يحمّل نموذج Vosk الصغير (~20MB) مرة واحدة عند الطلب.
 * بعدها يشتغل كامل offline بدون مفاتيح.
 *
 * النموذج المستخدم: vosk-model-small-en-us-0.15
 * رابط: https://alphacephei.com/vosk/models
 */
public class VoskOfflineEngine {

    private static final String TAG = "VoskOfflineEngine";

    // رابط النموذج الصغير (~40MB مضغوط، ~20MB بعد الفك)
    private static final String MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

    private static final String PREFS_VOSK   = "vosk_prefs";
    private static final String KEY_DOWNLOADED = "model_downloaded";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Model       model;
    private Recognizer  recognizer;
    private SpeechService speechService;

    private TranscriptCallback callback;

    public interface TranscriptCallback {
        void onTranscript(String text);
        void onError(String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onComplete();
        void onError(String error);
    }

    public VoskOfflineEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── هل النموذج محمّل؟ ──────────────────────────────────────

    public boolean isModelReady() {
        File modelDir = getModelDir();
        boolean dirExists = modelDir.exists() && modelDir.isDirectory();
        boolean flagSet   = context.getSharedPreferences(PREFS_VOSK, Context.MODE_PRIVATE)
                                   .getBoolean(KEY_DOWNLOADED, false);
        return dirExists && flagSet;
    }

    private File getModelDir() {
        return new File(context.getFilesDir(), "vosk-model");
    }

    // ── تحميل النموذج (مرة واحدة فقط) ─────────────────────────

    public void downloadModel(DownloadCallback cb) {
        if (isModelReady()) {
            cb.onComplete();
            return;
        }

        new Thread(() -> {
            try {
                File zipFile = new File(context.getCacheDir(), "vosk-model.zip");
                downloadFile(MODEL_URL, zipFile, cb);
                unzip(zipFile, context.getFilesDir());
                zipFile.delete();

                // رنيّم مجلد الاستخراج لـ vosk-model
                File extracted = findExtractedDir(context.getFilesDir());
                if (extracted != null && !extracted.getName().equals("vosk-model")) {
                    extracted.renameTo(getModelDir());
                }

                // سجّل إن التحميل اكتمل
                context.getSharedPreferences(PREFS_VOSK, Context.MODE_PRIVATE)
                       .edit().putBoolean(KEY_DOWNLOADED, true).apply();

                handler.post(cb::onComplete);

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage());
                handler.post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    // ── تشغيل التعرف على الصوت ──────────────────────────────────

    public void start(TranscriptCallback cb) {
        this.callback = cb;
        new Thread(() -> {
            try {
                model       = new Model(getModelDir().getAbsolutePath());
                recognizer  = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(recognizer, 16000.0f);
                speechService.startListening(new RecognitionListener() {
                    @Override public void onPartialResult(String hypothesis) {
                        try {
                            String text = new JSONObject(hypothesis).optString("partial", "");
                            if (!text.isEmpty()) handler.post(() -> cb.onTranscript(text));
                        } catch (Exception ignored) {}
                    }
                    @Override public void onResult(String hypothesis) {
                        try {
                            String text = new JSONObject(hypothesis).optString("text", "");
                            if (!text.isEmpty()) handler.post(() -> cb.onTranscript(text));
                        } catch (Exception ignored) {}
                    }
                    @Override public void onFinalResult(String hypothesis) { onResult(hypothesis); }
                    @Override public void onError(Exception e) {
                        handler.post(() -> cb.onError(e.getMessage()));
                    }
                    @Override public void onTimeout() {}
                });
            } catch (Exception e) {
                Log.e(TAG, "Start failed: " + e.getMessage());
                handler.post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    public void stop() {
        if (speechService != null) { speechService.stop(); speechService = null; }
        if (recognizer   != null) { recognizer.close();   recognizer   = null; }
        if (model        != null) { model.close();        model        = null; }
    }

    // ── أدوات مساعدة ────────────────────────────────────────────

    private void downloadFile(String urlStr, File dest, DownloadCallback cb) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.connect();
        int total = conn.getContentLength();
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int downloaded = 0, n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    int pct = (int)(downloaded * 100L / total);
                    handler.post(() -> cb.onProgress(pct));
                }
            }
        }
    }

    private void unzip(File zip, File dest) throws IOException {
        java.util.zip.ZipInputStream zis =
            new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip));
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File f = new File(dest, entry.getName());
            if (entry.isDirectory()) { f.mkdirs(); }
            else {
                f.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                }
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private File findExtractedDir(File parent) {
        File[] files = parent.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory() && f.getName().startsWith("vosk-model")) return f;
        }
        return null;
    }
}
