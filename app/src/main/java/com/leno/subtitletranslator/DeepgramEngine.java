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
    private static final String WS_BASE="wss://api.deepgram.com/v1/listen?encoding=linear16&sample_rate=16000&channels=1&punctuate=true&interim_results=true&utterance_end_ms=1000&model=nova-2&vad_events=true&smart_format=true";
    public interface ResultCallback{void onResult(String text,boolean isFinal);}
    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean connected=false,reconnect=true,ready=false;
    private int retryCount=0;
    private static final int MAX_RETRY=5;
    private ResultCallback callback;
    private String apiKey="";
    private String sourceLang="en-US";
    public void start(String key,String sourceLangCode,ResultCallback cb){
        this.apiKey=key!=null?key:"";
        this.sourceLang=sourceLangCode!=null?sourceLangCode:"en-US";
        this.callback=cb;
        this.reconnect=true;this.retryCount=0;this.connected=false;this.ready=false;
        if(client!=null){try{client.dispatcher().cancelAll();client.connectionPool().evictAll();}catch(Exception ignored){}}
        client=new OkHttpClient.Builder()
            .connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(0,TimeUnit.SECONDS)
            .writeTimeout(10,TimeUnit.SECONDS)
            .pingInterval(20,TimeUnit.SECONDS)
            .build();
        connect();
    }
    private String toLang(String code){
        if(code==null||code.isEmpty())return "en";
        int d=code.indexOf('-');
        return d>0?code.substring(0,d):code;
    }
    private void connect(){
        if(!reconnect||client==null)return;
        ready=false;
        String dgLang=toLang(sourceLang);
        String url=WS_BASE+"&language="+dgLang;
        Request req=new Request.Builder().url(url).header("Authorization","Token "+apiKey).build();
        webSocket=client.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response r){
                connected=true;ready=true;retryCount=0;
                Log.d(TAG,"Connected lang="+dgLang);
            }
            @Override public void onMessage(WebSocket ws,String text){
                if(text==null||text.isEmpty())return;
                try{
                    JSONObject j=new JSONObject(text);
                    if(!j.has("channel"))return;
                    JSONObject ch=j.getJSONObject("channel");
                    if(!ch.has("alternatives"))return;
                    if(ch.getJSONArray("alternatives").length()==0)return;
                    String t=ch.getJSONArray("alternatives").getJSONObject(0).optString("transcript","");
                    if(t.trim().isEmpty())return;
                    boolean isFinal=j.optBoolean("is_final",false);
                    if(callback!=null)callback.onResult(t,isFinal);
                }catch(Exception e){Log.w(TAG,"parse: "+e.getMessage());}
            }
            @Override public void onFailure(WebSocket ws,Throwable t,Response r){
                connected=false;ready=false;
                Log.e(TAG,"failure: "+(t!=null?t.getMessage():"unknown"));
                if(r!=null&&(r.code()==401||r.code()==400)){reconnect=false;return;}
                if(!reconnect)return;
                retryCount++;
                if(retryCount>MAX_RETRY){reconnect=false;return;}
                long delay=Math.min(3000L*retryCount,15000L);
                new Handler(Looper.getMainLooper()).postDelayed(()->{ if(reconnect)connect(); },delay);
            }
            @Override public void onClosed(WebSocket ws,int code,String reason){
                connected=false;ready=false;
                Log.d(TAG,"closed: "+reason+" code="+code);
            }
        });
    }
    public void sendAudio(short[]data,int len){
        if(!connected||!ready||webSocket==null||data==null||len<=0)return;
        if(len>data.length)len=data.length;
        byte[]pcm=new byte[len*2];
        for(int i=0;i<len;i++){pcm[i*2]=(byte)(data[i]&0xFF);pcm[i*2+1]=(byte)((data[i]>>8)&0xFF);}
        try{webSocket.send(ByteString.of(pcm));}catch(Exception e){Log.e(TAG,"send: "+e.getMessage());}
    }
    public void stop(){
        reconnect=false;connected=false;ready=false;
        if(webSocket!=null){try{webSocket.send("{\"type\":\"CloseStream\"}");}catch(Exception ignored){}
            try{webSocket.close(1000,"done");}catch(Exception ignored){}webSocket=null;}
        if(client!=null){try{client.connectionPool().evictAll();}catch(Exception ignored){}
            try{client.dispatcher().executorService().shutdown();}catch(Exception ignored){}client=null;}
        callback=null;
    }
}
