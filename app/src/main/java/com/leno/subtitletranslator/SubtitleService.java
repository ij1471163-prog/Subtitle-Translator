package com.leno.subtitletranslator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
public class SubtitleService extends Service {
    private static final String TAG="SubtitleService";
    private static final String CHANNEL_ID="subtitle_ch";
    private static final int NOTIF_ID=1001;
    public static final String ACTION_STOP="com.leno.subtitletranslator.STOP";
    private static final long[]BACKOFF={0,1000,2000,5000,15000,30000,60000};
    private int silence=0;
    private WindowManager wm;
    private TextView overlay;
    private SpeechRecognizer recognizer;
    private AudioCaptureService audioCapture;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean running=false,busy=false,usingPlayback=false;
    private String sourceLang="en-US",targetLang="ar";
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){if(Intent.ACTION_SCREEN_OFF.equals(i.getAction()))stopSelf();}
    };
    @Override public void onCreate(){
        super.onCreate();
        Log.d(TAG,"onCreate");
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);
        sourceLang=p.getString(MainActivity.KEY_SOURCE_LANG,"en-US");
        targetLang=p.getString(MainActivity.KEY_TARGET_LANG,"ar");
        Log.d(TAG,"langs: "+sourceLang+" -> "+targetLang);
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ST::lock");
        wakeLock.acquire(3600000L);
        registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        createChannel();
        startForeground(NOTIF_ID,buildNotif());
        addOverlay();
        running=true;
        startCaptureOrMic();
    }
    @Override public int onStartCommand(Intent i,int f,int id){
        if(i!=null&&ACTION_STOP.equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    @Override public void onDestroy(){
        running=false;
        handler.removeCallbacksAndMessages(null);
        if(audioCapture!=null)audioCapture.stop();
        if(recognizer!=null){try{recognizer.destroy();}catch(Exception e){}}
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        if(wm!=null&&overlay!=null){try{wm.removeView(overlay);}catch(Exception e){}}
        try{unregisterReceiver(screenOff);}catch(Exception e){}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
    private void startCaptureOrMic(){
        Log.d(TAG,"startCaptureOrMic");
        Intent proj=MainActivity.getProjectionData();
        Log.d(TAG,"projectionData="+(proj!=null?"AVAILABLE":"NULL"));
        if(AudioCaptureService.isSupported()&&proj!=null){
            audioCapture=new AudioCaptureService();
            boolean ok=audioCapture.onActivityResult(null,android.app.Activity.RESULT_OK,proj);
            Log.d(TAG,"capture init="+ok);
            if(ok){
                boolean started=audioCapture.startCapture(this::onAudioCaptured);
                Log.d(TAG,"capture started="+started);
                if(started){usingPlayback=true;showOverlay("يلتقط صوت الفيديو");handler.post(this::initRecognizer);return;}
            }
        }
        Log.d(TAG,"fallback to mic");
        showOverlay("يستمع بالميكروفون");
        handler.post(this::initRecognizer);
    }
    private void onAudioCaptured(short[]data,int len){Log.v(TAG,"onAudioCaptured len="+len);}
    private void initRecognizer(){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){Log.e(TAG,"SpeechRecognizer NOT available");showOverlay("غير متاح");return;}
        Log.d(TAG,"initRecognizer OK");
        recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(sl);
        listen();
    }
    private void listen(){
        if(!running||busy||recognizer==null)return;
        busy=true;
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,sourceLang);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,getPackageName());
        try{recognizer.startListening(i);}catch(Exception e){busy=false;scheduleNext();}
    }
    private void scheduleNext(){if(!running)return;handler.postDelayed(this::listen,BACKOFF[Math.min(silence,BACKOFF.length-1)]);}
    private final RecognitionListener sl=new RecognitionListener(){
        @Override public void onResults(Bundle r){
            busy=false;
            ArrayList<String>m=r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if(m!=null&&!m.isEmpty()&&!m.get(0).trim().isEmpty()){
                silence=0;Log.d(TAG,"result: "+m.get(0));
                TranslationHelper.translateAsync(m.get(0),sourceLang,targetLang,t->showOverlay(t));
            }else{silence=Math.min(silence+1,BACKOFF.length-1);}
            scheduleNext();
        }
        @Override public void onError(int e){
            busy=false;
            if(e==SpeechRecognizer.ERROR_NO_MATCH||e==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){silence=Math.min(silence+1,BACKOFF.length-1);}
            else if(e==SpeechRecognizer.ERROR_RECOGNIZER_BUSY){handler.postDelayed(()->{try{recognizer.destroy();}catch(Exception ex){}recognizer=SpeechRecognizer.createSpeechRecognizer(SubtitleService.this);recognizer.setRecognitionListener(this);busy=false;listen();},2000);return;}
            scheduleNext();
        }
        @Override public void onPartialResults(Bundle b){}
        @Override public void onReadyForSpeech(Bundle b){}
        @Override public void onBeginningOfSpeech(){silence=0;}
        @Override public void onEndOfSpeech(){}
        @Override public void onRmsChanged(float v){}
        @Override public void onBufferReceived(byte[]b){}
        @Override public void onEvent(int t,Bundle b){}
    };
    private void addOverlay(){
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        overlay=new TextView(this);
        overlay.setTextColor(Color.WHITE);overlay.setTextSize(18f);overlay.setGravity(Gravity.CENTER);
        overlay.setShadowLayer(8f,0f,2f,Color.BLACK);overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setPadding(20,8,20,8);overlay.setMaxLines(2);
        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.BOTTOM;lp.y=120;wm.addView(overlay,lp);
    }
    private void showOverlay(String t){handler.post(()->{if(overlay!=null)overlay.setText(t);});}
    private void createChannel(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            NotificationChannel c=new NotificationChannel(CHANNEL_ID,"الترجمة",NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);c.setSound(null,null);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }
    private Notification buildNotif(){
        int f=Build.VERSION.SDK_INT>=Build.VERSION_CODES.M?PendingIntent.FLAG_IMMUTABLE:0;
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,SubtitleService.class).setAction(ACTION_STOP),f);
        return new NotificationCompat.Builder(this,CHANNEL_ID).setContentTitle("الترجمة شغالة").setContentText("اضغط إيقاف").setSmallIcon(android.R.drawable.ic_btn_speak_now).addAction(android.R.drawable.ic_media_pause,"إيقاف",stop).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).build();
    }
}
