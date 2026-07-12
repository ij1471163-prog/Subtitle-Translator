package com.leno.subtitletranslator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;

public class SpeechmaticsEngine {
    private static final String TAG="SpeechmaticsEngine";
    private static final String WS_URL="wss://eu2.rt.speechmatics.com/v2";
    public interface ResultCallback{void onResult(String text);}
    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean connected=false,reconnect=true,ready=false;
    private ResultCallback callback;
    private String apiKey,lang;

    public void start(String key,String language,ResultCallback cb){
        this.apiKey=key;this.lang=language.contains("-")?language.split("-")[0]:language;
        this.callback=cb;this.reconnect=true;
        client=new OkHttpClient.Builder()
            .connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(0,TimeUnit.SECONDS)
            .pingInterval(20,TimeUnit.SECONDS)
            .build();
        connect();
    }

    private void connect(){
        if(!reconnect)return;
        ready=false;
        Request req=new Request.Builder()
            .url(WS_URL)
            .header("Authorization","Bearer "+apiKey)
            .build();
        webSocket=client.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response r){
                connected=true;
                // أرسل Start Recognition message
                try{
                    JSONObject startMsg=new JSONObject();
                    startMsg.put("message","StartRecognition");
                    JSONObject audio=new JSONObject();
                    audio.put("type","raw");
                    audio.put("encoding","pcm_s16le");
                    audio.put("sample_rate",16000);
                    startMsg.put("audio_format",audio);
                    JSONObject tc=new JSONObject();
                    tc.put("language",lang);
                    startMsg.put("transcription_config",tc);
                    ws.send(startMsg.toString());
                    ready=true;
                    Log.d(TAG,"✅ Connected lang="+lang);
                }catch(Exception e){Log.e(TAG,"start msg error: "+e.getMessage());}
            }
            @Override public void onMessage(WebSocket ws,String text){
                try{
                    JSONObject j=new JSONObject(text);
                    String msg=j.optString("message","");
                    if(!"AddTranscript".equals(msg))return;
                    String t=j.optString("transcript","").trim();
                    if(!t.isEmpty()&&callback!=null){
                        Log.d(TAG,"transcript: "+t);
                        callback.onResult(t);
                    }
                }catch(Exception e){Log.w(TAG,"parse: "+e.getMessage());}
            }
            @Override public void onFailure(WebSocket ws,Throwable t,Response r){
                connected=false;ready=false;
                Log.e(TAG,"failure: "+t.getMessage());
                if(r!=null&&r.code()==401){reconnect=false;return;}
                if(reconnect)new Handler(Looper.getMainLooper()).postDelayed(()->connect(),3000);
            }
            @Override public void onClosed(WebSocket ws,int code,String reason){
                connected=false;ready=false;
            }
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
        if(webSocket!=null){
            try{
                JSONObject end=new JSONObject();
                end.put("message","EndOfStream");
                end.put("last_seq_no",0);
                webSocket.send(end.toString());
            }catch(Exception ignored){}
            webSocket.close(1000,"done");
        }
        if(client!=null){
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
}
