package com.leno.subtitletranslator;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class GeminiTranslator {
    private static final String TAG="GeminiTranslator";
    private static final String URL_STR="https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";

    public interface Callback{void onResult(String text);}

    public static void translate(String text, String targetLang, String apiKey, Callback cb){
        new Thread(()->{
            try{
                String prompt="Translate this to "+targetLang+". Return ONLY the translation, no explanation:\n"+text;
                JSONObject body=new JSONObject();
                JSONArray contents=new JSONArray();
                JSONObject content=new JSONObject();
                JSONArray parts=new JSONArray();
                JSONObject part=new JSONObject();
                part.put("text",prompt);
                parts.put(part);
                content.put("parts",parts);
                contents.put(content);
                body.put("contents",contents);

                URL url=new URL(URL_STR+apiKey);
                HttpURLConnection c=(HttpURLConnection)url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type","application/json");
                c.setConnectTimeout(5000);c.setReadTimeout(8000);c.setDoOutput(true);
                try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes());}

                if(c.getResponseCode()==200){
                    BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb=new StringBuilder();String l;
                    while((l=r.readLine())!=null)sb.append(l);
                    r.close();
                    JSONObject resp=new JSONObject(sb.toString());
                    String result=resp.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text").trim();
                    Log.d(TAG,"✅ "+result);
                    if(cb!=null)cb.onResult(result);
                }else{
                    Log.e(TAG,"HTTP: "+c.getResponseCode());
                }
                c.disconnect();
            }catch(Exception e){Log.e(TAG,"err: "+e.getMessage());}
        }).start();
    }
}
