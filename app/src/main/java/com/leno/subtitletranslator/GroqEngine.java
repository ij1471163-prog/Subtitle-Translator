package com.leno.subtitletranslator;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class GroqEngine {
    private static final String TAG="GroqEngine";
    private static final String API_URL="https://api.groq.com/openai/v1/audio/transcriptions";
    public interface ResultCallback{void onResult(String text);}
    private final ExecutorService exec=Executors.newSingleThreadExecutor();
    private volatile boolean running=false;
    private final ByteArrayOutputStream buf=new ByteArrayOutputStream();
    private ResultCallback cb;
    private String apiKey,lang;
    private static final int FLUSH=16000*2*3; // كل 5 ثواني
    public void start(String key,String language,ResultCallback callback){
        this.apiKey=key;
        this.lang=language.contains("-")?language.split("-")[0]:language;
        this.cb=callback;this.running=true;
        Log.d(TAG,"✅ Started lang="+this.lang);
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
            String boundary="----boundary"+System.currentTimeMillis();
            URL url=new URL(API_URL);
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization","Bearer "+apiKey);
            c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setDoOutput(true);
            try(OutputStream os=c.getOutputStream();
                PrintWriter pw=new PrintWriter(new OutputStreamWriter(os,"UTF-8"),true)){
                // model
                pw.append("--"+boundary+"\r\n");
                pw.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
                pw.append("whisper-large-v3-turbo\r\n");
                // language
                pw.append("--"+boundary+"\r\n");
                pw.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
                pw.append(lang+"\r\n");
                // response format
                pw.append("--"+boundary+"\r\n");
                pw.append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n");
                pw.append("json\r\n");
                // audio file
                pw.append("--"+boundary+"\r\n");
                pw.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.raw\"\r\n");
                pw.append("Content-Type: audio/raw\r\n\r\n");
                pw.flush();
                os.write(audio);
                os.flush();
                pw.append("\r\n--"+boundary+"--\r\n");
                pw.flush();
            }
            int code=c.getResponseCode();
            if(code==200){
                BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder s=new StringBuilder();String l;
                while((l=r.readLine())!=null)s.append(l);
                r.close();
                JSONObject j=new JSONObject(s.toString());
                String text=j.optString("text","").trim();
                Log.d(TAG,"✅ result: "+text);
                if(!text.isEmpty()&&cb!=null)cb.onResult(text);
            }else{
                Log.e(TAG,"HTTP: "+code);
            }
            c.disconnect();
        }catch(Exception e){Log.e(TAG,"err: "+e.getMessage());}
    }
    public void stop(){
        running=false;
        synchronized(buf){buf.reset();}
        exec.shutdown();
    }
}
