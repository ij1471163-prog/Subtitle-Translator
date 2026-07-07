package com.leno.subtitletranslator;

import android.util.Log;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeepgramEngine — يرسل PCM مباشرة لـ Deepgram عبر HTTP streaming
 * بدون WebSocket library إضافية
 */
public class DeepgramEngine {

    private static final String TAG = "DeepgramEngine";
    private static final String API_KEY = "4220c96edf78e32873705492bf32091a84a50353"; // غيّر هذا
    private static final String URL = "https://api.deepgram.com/v1/listen"
        + "?encoding=linear16&sample_rate=16000&channels=1&language=en&punctuate=true";

    public interface ResultCallback {
        void onResult(String text);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private OutputStream outputStream;
    private HttpURLConnection connection;

    public void start(ResultCallback callback) {
        running = true;
        executor.execute(() -> {
            try {
                URL url = new URL(URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Token " + API_KEY);
                connection.setRequestProperty("Content-Type", "audio/raw");
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setChunkedStreamingMode(4096);
                connection.connect();

                outputStream = connection.getOutputStream();
                Log.d(TAG, "✅ Connected to Deepgram");

                // اقرأ الرد في thread ثاني
                new Thread(() -> {
                    try {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getInputStream()));
                        String line;
                        while (running && (line = reader.readLine()) != null) {
                            if (!line.isEmpty()) {
                                try {
                                    JSONObject json = new JSONObject(line);
                                    String transcript = json
                                        .getJSONArray("results")
                                        .getJSONObject(0)
                                        .getJSONArray("alternatives")
                                        .getJSONObject(0)
                                        .getString("transcript");
                                    if (!transcript.isEmpty()) {
                                        Log.d(TAG, "Result: " + transcript);
                                        callback.onResult(transcript);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Reader error: " + e.getMessage());
                    }
                }).start();

            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            }
        });
    }

    public void sendAudio(short[] data, int length) {
        if (!running || outputStream == null) return;
        executor.execute(() -> {
            try {
                byte[] bytes = new byte[length * 2];
                for (int i = 0; i < length; i++) {
                    bytes[i * 2]     = (byte) (data[i] & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((data[i] >> 8) & 0xFF);
                }
                outputStream.write(bytes);
                outputStream.flush();
            } catch (Exception e) {
                Log.e(TAG, "Send error: " + e.getMessage());
            }
        });
    }

    public void stop() {
        running = false;
        try { if (outputStream != null) outputStream.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.disconnect(); } catch (Exception ignored) {}
        executor.shutdown();
    }
}
