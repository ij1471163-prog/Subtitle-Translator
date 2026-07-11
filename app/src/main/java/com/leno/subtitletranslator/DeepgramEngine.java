package com.leno.subtitletranslator;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;

public class DeepgramEngine {
    private static final String TAG="DeepgramEngine";
    private static final String WS_BASE="wss://api.deepgram.com/v1/listen?encoding=linear16&sample_rate=16000&channels=1&punctuate=true&interim_results=true&utterance_end_ms=1000";

    public interface ResultCallback{void onResult(String text);}

    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean connected=false,reconnect=true,ready=false;
    private ResultCallback callback;
    private String apiKey;
    private String sourceLang="en-US";

    public void start(String key,String sourceLangCode,ResultCallback cb){
        this.apiKey=key;this.callback=cb;this.reconnect=true;
        this.sourceLang=(sourceLangCode!=null&&!sourceLangCode.isEmpty())?sourceLangCode:"en-US";
        client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).build();
        connect();
    }

    // يحوّل كود اللغة من صيغة Android (ar-SA, ja-JP) إلى صيغة Deepgram القصيرة (ar, ja)
    private String toDeepgramLang(String code){
        if(code==null||code.isEmpty())return "en";
        int dash=code.indexOf('-');
        return dash>0?code.substring(0,dash):code;
    }

    private void connect(){
        if(!reconnect)return;
        ready=false;
        String dgLang=toDeepgramLang(sourceLang);
        String url=WS_BASE+"&language="+dgLang;
        Request req=new Request.Builder().url(url).header("Authorization","Token "+apiKey).build();
        webSocket=client.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response r){connected=true;ready=true;Log.d(TAG,"✅ Connected lang="+dgLang);}
            @Override public void onMessage(WebSocket ws,String text){
                try{
                    JSONObject j=new JSONObject(text);
                    if(!j.has("channel"))return;
                    boolean isFinal=j.optBoolean("is_final",false);
                    if(!isFinal)return;
                    JSONObject channel=j.getJSONObject("channel");
                    if(channel.getJSONArray("alternatives").length()==0)return;
                    String t=channel.getJSONArray("alternatives").getJSONObject(0).optString("transcript","");
                    if(t.trim().isEmpty())return;
                    if(callback!=null){Log.d(TAG,"transcript(final): "+t);callback.onResult(t);}
                }catch(Exception e){Log.w(TAG,"parse: "+e.getMessage());}
            }
            @Override public void onFailure(WebSocket ws,Throwable t,Response r){
                connected=false;ready=false;Log.e(TAG,"failure: "+t.getMessage());
                if(r!=null&&r.code()==401){reconnect=false;Log.e(TAG,"Invalid API Key");return;}
                if(r!=null&&r.code()==400){reconnect=false;Log.e(TAG,"⚠️ رمز اللغة '"+dgLang+"' غير مدعوم من Deepgram");return;}
                if(reconnect)new Handler(Looper.getMainLooper()).postDelayed(()->connect(),3000);
            }
            @Override public void onClosed(WebSocket ws,int code,String reason){connected=false;ready=false;Log.d(TAG,"closed: "+reason);}
        });
    }

    public void sendAudio(short[]data,int len){
        if(!connected||!ready||webSocket==null)return;
        byte[]pcm=new byte[len*2];
        for(int i=0;i<len;i++){pcm[i*2]=(byte)(data[i]&0xFF);pcm[i*2+1]=(byte)((data[i]>>8)&0xFF);}
        boolean ok=webSocket.send(ByteString.of(pcm));
        if(!ok)Log.e(TAG,"send failed");
    }

    public void stop(){
        reconnect=false;connected=false;ready=false;
        if(webSocket!=null){try{webSocket.send("{\"type\":\"CloseStream\"}");}catch(Exception ignored){}webSocket.close(1000,"done");}
        if(client!=null){client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
}
