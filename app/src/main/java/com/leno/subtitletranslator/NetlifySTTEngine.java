package com.leno.subtitletranslator;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class NetlifySTTEngine {
    private static final String TAG="NetlifySTTEngine";
    private static final String URL_STR="https://mellifluous-chaja-3057d6.netlify.app/.netlify/functions/transcribe";
    public interface ResultCallback{void onResult(String text);}
    private final ExecutorService exec=Executors.newSingleThreadExecutor();
    private volatile boolean running=false;
    private final ByteArrayOutputStream buf=new ByteArrayOutputStream();
    private ResultCallback cb;
    private String lang="en";
    private static final int FLUSH=16000*2*3;
    public void start(String language,ResultCallback callback){
        this.lang=language.contains("-")?language.split("-")[0]:language;
        this.cb=callback;this.running=true;
        Log.d(TAG,"✅ lang="+this.lang);
    }
    public void sendAudio(short[]data,int len){
        if(!running)return;
        byte[]pcm=new byte[len*2];
        for(int i=0;i<len;i++){pcm[i*2]=(byte)(data[i]&0xFF);pcm[i*2+1]=(byte)((data[i]>>8)&0xFF);}
        synchronized(buf){
            buf.write(pcm,0,pcm.length);
            if(buf.size()>=FLUSH){byte[]s=buf.toByteArray();buf.reset();exec.execute(()->send(s));}
        }
    }
    private void send(byte[]audio){
        try{
            String b64=Base64.encodeToString(audio,Base64.NO_WRAP);
            HttpURLConnection c=(HttpURLConnection)new URL(URL_STR).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type","application/octet-stream");
            c.setRequestProperty("x-language",lang);
            c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setDoOutput(true);
            try(OutputStream o=c.getOutputStream()){o.write(b64.getBytes());}
            if(c.getResponseCode()==200){
                BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder s=new StringBuilder();String l;
                while((l=r.readLine())!=null)s.append(l);
                r.close();
                JSONObject j=new JSONObject(s.toString());
                String text=j.optString("text","");
                String engine=j.optString("engine","?");
                Log.d(TAG,"✅ "+engine+": "+text);
                if(!text.isEmpty()&&cb!=null)cb.onResult(text);
            }else{Log.e(TAG,"HTTP: "+c.getResponseCode());}
            c.disconnect();
        }catch(Exception e){Log.e(TAG,"err: "+e.getMessage());}
    }
    public void stop(){
        running=false;
        synchronized(buf){buf.reset();}
        exec.shutdown();
    }
}
