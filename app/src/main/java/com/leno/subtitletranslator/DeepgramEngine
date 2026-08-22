package com.leno.subtitletranslator;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.*;
import okio.ByteString;

public class DeepgramEngine {

    private static final String TAG = "DeepgramEngine";

    // البروكسي الخاص بك - مفتاح Deepgram الحقيقي يبقى على Vercel
    private static final String PROXY_WS_BASE =
            "wss://subtitle-translator-khaki.vercel.app/deepgram";

    public interface ResultCallback {
        void onResult(String text, boolean isFinal);
    }

    private OkHttpClient client;
    private WebSocket webSocket;

    private volatile boolean connected = false;
    private volatile boolean reconnect = true;
    private volatile boolean ready = false;

    private int retryCount = 0;
    private static final int MAX_RETRY = 5;

    private ResultCallback callback;

    // "pro" أو "plus"
    private String tier = "pro";

    // السر المشترك مع البروكسي
    private String proxySecret = "";

    private String sourceLang = "en-US";

    private static final boolean DEBUG_LOG = false;

    /**
     * يبدأ الاتصال بالبروكسي.
     *
     * لا يتم إرسال مفتاح Deepgram الحقيقي من التطبيق.
     * التطبيق يرسل tier + secret فقط، والسيرفر يتولى مفتاح Deepgram.
     */
    public void start(
            String tierParam,
            String secret,
            String sourceLangCode,
            ResultCallback cb
    ) {

        this.tier = (tierParam != null && !tierParam.isEmpty())
                ? tierParam
                : "pro";

        this.proxySecret = secret != null
                ? secret
                : "";

        this.callback = cb;

        this.reconnect = true;
        this.retryCount = 0;
        this.connected = false;
        this.ready = false;

        this.sourceLang = (sourceLangCode != null && !sourceLangCode.isEmpty())
                ? sourceLangCode
                : "en-US";

        if (client != null) {
            try {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
            } catch (Exception ignored) {
            }
        }

        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();

        connect();
    }

    /**
     * يحوّل:
     * en-US -> en
     * ar-SA -> ar
     * ja-JP -> ja
     */
    private String toDeepgramLang(String code) {

        if (code == null || code.isEmpty()) {
            return "en";
        }

        int dash = code.indexOf('-');

        if (dash > 0) {
            return code.substring(0, dash);
        }

        return code;
    }

    /**
     * ترميز قيمة Query Parameter حتى لا تكسر الـ WebSocket URL.
     */
    private String encode(String value) {

        if (value == null) {
            return "";
        }

        try {
            return URLEncoder.encode(
                    value,
                    StandardCharsets.UTF_8.name()
            );
        } catch (Exception e) {
            return "";
        }
    }

    private void connect() {

        if (!reconnect || client == null) {
            return;
        }

        ready = false;

        String dgLang = toDeepgramLang(sourceLang);

        /*
         * مهم:
         *
         * التطبيق لا يضع مفتاح Deepgram هنا.
         *
         * السيرفر يستقبل:
         * tier
         * lang
         * secret
         *
         * ثم يختار مفتاح Deepgram من متغيرات البيئة الموجودة على Vercel.
         */

        String url = PROXY_WS_BASE
                + "?tier=" + encode(tier)
                + "&lang=" + encode(dgLang)
                + "&secret=" + encode(proxySecret);

        if (DEBUG_LOG) {
            Log.d(
                    TAG,
                    "Connecting proxy: tier="
                            + tier
                            + " lang="
                            + dgLang
            );
        }

        Request req = new Request.Builder()
                .url(url)
                .build();

        webSocket = client.newWebSocket(
                req,
                new WebSocketListener() {

                    @Override
                    public void onOpen(
                            WebSocket ws,
                            Response response
                    ) {

                        connected = true;
                        ready = true;
                        retryCount = 0;

                        Log.d(
                                TAG,
                                "Connected via proxy, lang="
                                        + dgLang
                                        + " tier="
                                        + tier
                        );
                    }

                    @Override
                    public void onMessage(
                            WebSocket ws,
                            String text
                    ) {

                        if (text == null || text.isEmpty()) {
                            return;
                        }

                        try {

                            JSONObject j = new JSONObject(text);

                            if (!j.has("channel")) {
                                return;
                            }

                            JSONObject channel =
                                    j.getJSONObject("channel");

                            if (!channel.has("alternatives")) {
                                return;
                            }

                            if (channel
                                    .getJSONArray("alternatives")
                                    .length() == 0) {
                                return;
                            }

                            String t =
                                    channel
                                            .getJSONArray("alternatives")
                                            .getJSONObject(0)
                                            .optString(
                                                    "transcript",
                                                    ""
                                            );

                            if (t.trim().isEmpty()) {
                                return;
                            }

                            boolean isFinal =
                                    j.optBoolean(
                                            "is_final",
                                            false
                                    );

                            if (callback != null) {

                                if (DEBUG_LOG) {

                                    Log.d(
                                            TAG,
                                            isFinal
                                                    ? "transcript(final): " + t
                                                    : "transcript(interim): " + t
                                    );
                                }

                                callback.onResult(
                                        t,
                                        isFinal
                                );
                            }

                        } catch (Exception e) {

                            Log.w(
                                    TAG,
                                    "parse: " + e.getMessage()
                            );
                        }
                    }

                    @Override
                    public void onFailure(
                            WebSocket ws,
                            Throwable t,
                            Response response
                    ) {

                        connected = false;
                        ready = false;

                        Log.e(
                                TAG,
                                "WebSocket failure: "
                                        + (t != null
                                        ? t.getMessage()
                                        : "unknown")
                        );

                        /*
                         * 4001:
                         * السر المشترك غير صحيح.
                         */
                        if (response != null
                                && response.code() == 4001) {

                            reconnect = false;

                            Log.e(
                                    TAG,
                                    "Unauthorized - تحقق من APP_SECRET"
                            );

                            return;
                        }

                        /*
                         * 4002:
                         * tier غير صالح.
                         */
                        if (response != null
                                && response.code() == 4002) {

                            reconnect = false;

                            Log.e(
                                    TAG,
                                    "Invalid tier من السيرفر"
                            );

                            return;
                        }

                        if (!reconnect) {
                            return;
                        }

                        retryCount++;

                        if (retryCount > MAX_RETRY) {

                            reconnect = false;

                            Log.e(
                                    TAG,
                                    "تجاوزنا الحد الأقصى لمحاولات إعادة الاتصال ("
                                            + MAX_RETRY
                                            + ")"
                            );

                            return;
                        }

                        long delay =
                                Math.min(
                                        3000L * retryCount,
                                        15000L
                                );

                        Log.w(
                                TAG,
                                "إعادة محاولة الاتصال #"
                                        + retryCount
                                        + "/"
                                        + MAX_RETRY
                                        + " بعد "
                                        + delay
                                        + "ms"
                        );

                        new Handler(
                                Looper.getMainLooper()
                        ).postDelayed(
                                () -> {

                                    if (reconnect) {
                                        connect();
                                    }

                                },
                                delay
                        );
                    }

                    @Override
                    public void onClosed(
                            WebSocket ws,
                            int code,
                            String reason
                    ) {

                        connected = false;
                        ready = false;

                        Log.d(
                                TAG,
                                "closed: "
                                        + reason
                                        + " code="
                                        + code
                        );
                    }
                }
        );
    }

    /**
     * إرسال PCM 16-bit Little Endian إلى البروكسي.
     */
    public void sendAudio(
            short[] data,
            int len
    ) {

        if (!connected
                || !ready
                || webSocket == null
                || data == null
                || len <= 0) {

            return;
        }

        if (len > data.length) {
            len = data.length;
        }

        byte[] pcm = new byte[len * 2];

        for (int i = 0; i < len; i++) {

            pcm[i * 2] =
                    (byte) (data[i] & 0xFF);

            pcm[i * 2 + 1] =
                    (byte) ((data[i] >> 8) & 0xFF);
        }

        boolean ok;

        try {

            ok = webSocket.send(
                    ByteString.of(pcm)
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "send exception: "
                            + e.getMessage()
            );

            return;
        }

        if (!ok) {

            Log.e(
                    TAG,
                    "send failed"
            );
        }
    }

    /**
     * إيقاف الاتصال بالكامل.
     */
    public void stop() {

        reconnect = false;
        connected = false;
        ready = false;

        if (webSocket != null) {

            try {

                webSocket.send(
                        "{\"type\":\"CloseStream\"}"
                );

            } catch (Exception ignored) {
            }

            try {

                webSocket.close(
                        1000,
                        "done"
                );

            } catch (Exception ignored) {
            }

            webSocket = null;
        }

        if (client != null) {

            try {
                client.connectionPool().evictAll();
            } catch (Exception ignored) {
            }

            try {
                client.dispatcher()
                        .executorService()
                        .shutdown();
            } catch (Exception ignored) {
            }

            client = null;
        }

        callback = null;
    }
}
