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
    private static final String WS_URL="wss://api.deepgram.com/v1/listen?encoding=linear16&sample_rate=16000&channels=1&punctuate=true&interim_results=false";
    public interface ResultCallback{void onResult(String text);}
    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean connected=false,reconnect=true,ready=false;
    private ResultCallback callback;
    private String apiKey;
    public void start(String key,ResultCallback cb){
        this.apiKey=key;this.callback=cb;this.reconnect=true;
        client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).build();
        connect();
    }
    private void connect(){
        if(!reconnect)return;
        ready=false;
        Request req=new Request.Builder().url(WS_URL).header("Authorization","Token "+apiKey).build();
        webSocket=client.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response r){connected=true;ready=true;Log.d(TAG,"✅ Connected");}
            @Override public void onMessage(WebSocket ws,String text){
                try{
                    JSONObject j=new JSONObject(text);
                    if(!j.has("channel"))return;
                    String t=j.getJSONObject("channel").getJSONArray("alternatives").getJSONObject(0).getString("transcript");
                    if(!t.isEmpty()&&callback!=null){Log.d(TAG,"transcript: "+t);callback.onResult(t);}
                }catch(Exception e){Log.w(TAG,"parse: "+e.getMessage());}
            }
            @Override public void onFailure(WebSocket ws,Throwable t,Response r){
                connected=false;ready=false;Log.e(TAG,"failure: "+t.getMessage());
                if(r!=null&&r.code()==401){reconnect=false;Log.e(TAG,"Invalid API Key");return;}
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
