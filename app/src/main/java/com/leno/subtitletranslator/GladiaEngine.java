package com.leno.subtitletranslator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
public class GladiaEngine {
    private static final String TAG="GladiaEngine";
    public interface ResultCallback{void onResult(String text);}
    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean connected=false,reconnect=true;
    private ResultCallback callback;
    private String apiKey,lang;
    public void start(String key,String language,ResultCallback cb){
        this.apiKey=key.trim();this.lang=language.contains("-")?language.split("-")[0]:language;
        this.callback=cb;this.reconnect=true;
        client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).build();
        initSession();
    }
    private void initSession(){
        new Thread(()->{
            try{
                RequestBody body=RequestBody.create("{\"encoding\":\"wav/pcm\",\"sample_rate\":16000,\"language\":\""+lang+"\"}",MediaType.parse("application/json"));
                Response resp=client.newCall(new Request.Builder().url("https://api.gladia.io/v2/live").header("x-gladia-key",apiKey).post(body).build()).execute();
                if(resp.isSuccessful()&&resp.body()!=null){
                    String url=new JSONObject(resp.body().string()).optString("url","");
                    Log.d(TAG,"Session: "+url);
                    if(!url.isEmpty())connectWS(url);
                }
            }catch(Exception e){Log.e(TAG,"init: "+e.getMessage());}
        }).start();
    }
    private void connectWS(String url){
        webSocket=client.newWebSocket(new Request.Builder().url(url).build(),new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response r){connected=true;Log.d(TAG,"Connected");}
            @Override public void onMessage(WebSocket ws,String text){
                try{
                    JSONObject j=new JSONObject(text);
                    if(!"transcript".equals(j.optString("type","")))return;
                    JSONObject d=j.optJSONObject("data");
                    if(d==null||!d.optBoolean("is_final",false))return;
                    String t=d.optString("transcription","").trim();
                    if(!t.isEmpty()&&callback!=null)callback.onResult(t);
                }catch(Exception ignored){}
            }
            @Override public void onFailure(WebSocket ws,Throwable t,Response r){
                connected=false;
                if(reconnect)new Handler(Looper.getMainLooper()).postDelayed(()->initSession(),3000);
            }
            @Override public void onClosed(WebSocket ws,int c,String r){connected=false;}
        });
    }
    public void sendAudio(short[]data,int len){
        if(!connected||webSocket==null)return;
        byte[]pcm=new byte[len*2];
        for(int i=0;i<len;i++){pcm[i*2]=(byte)(data[i]&0xFF);pcm[i*2+1]=(byte)((data[i]>>8)&0xFF);}
        webSocket.send(ByteString.of(pcm));
    }
    public void stop(){
        reconnect=false;connected=false;
        if(webSocket!=null)webSocket.close(1000,"done");
        if(client!=null){client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
}
