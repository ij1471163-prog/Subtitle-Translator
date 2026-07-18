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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
public class SubtitleService extends Service {
    private static final String TAG="SubtitleService";
    private static final String CHANNEL_ID="subtitle_ch";
    private static final int NOTIF_ID=1001;
    public static final String ACTION_STOP="com.leno.subtitletranslator.STOP";
    private WindowManager wm;
    private TextView overlay;
    private AudioRecord micRecord;
    private AudioCaptureService audioCapture;
    private GladiaEngine gladia;
    private DeepgramEngine deepgram;
    private EngineQuotaManager quota;
    private EngineQuotaManager.Engine activeEngine=EngineQuotaManager.Engine.LOCAL;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean running=false;
    private String sourceLang="en-US",targetLang="ar";
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(Intent.ACTION_SCREEN_OFF.equals(i.getAction()))stopSelf();
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);
        sourceLang=p.getString(MainActivity.KEY_SOURCE_LANG,"en-US");
        targetLang=p.getString(MainActivity.KEY_TARGET_LANG,"ar");
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ST::lock");
        wakeLock.acquire(3600000L);
        registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        createChannel();
        startForeground(NOTIF_ID,buildNotif());
        addOverlay();
        running=true;
        quota=new EngineQuotaManager(this);
        startBestEngine();
        startAudioCapture();
    }
    private void startBestEngine(){
        EngineQuotaManager.Engine best=quota.getBestEngine();
        activeEngine=best;
        Log.d(TAG,"Best engine: "+best);
        switch(best){
            case GLADIA:
                gladia=new GladiaEngine();
                gladia.start(KeyManager.getGladiaKey(this),sourceLang,t->translate(t));
                showOverlay("Gladia جاهز");
                break;
            case DEEPGRAM:
                deepgram=new DeepgramEngine();
                deepgram.start(KeyManager.getDeepgramKey(this),sourceLang,t->translate(t));
                showOverlay("Deepgram جاهز");
                break;
            default:
                // SpeechRecognizer مجاني
                showOverlay("وضع مجاني");
                break;
        }
    }
    private void sendToEngine(short[]data,int len){
        switch(activeEngine){
            case GLADIA: if(gladia!=null)gladia.sendAudio(data,len); break;
            case DEEPGRAM:     if(deepgram!=null)deepgram.sendAudio(data,len); break;
        }
        // سجّل الاستخدام (~62.5ms لكل buffer 16000hz)
        quota.recordUsage(activeEngine,(long)(len/16.0));
    }
    private void translate(String text){
        MLKitTranslator.translate(text,sourceLang,targetLang,t->showOverlay(t));
    }
    private void startAudioCapture(){
        Intent proj=MainActivity.getProjectionData();
        if(AudioCaptureService.isSupported()&&proj!=null){
            audioCapture=new AudioCaptureService();
            boolean ok=audioCapture.onActivityResult(null,android.app.Activity.RESULT_OK,proj);
            if(ok){
                boolean started=audioCapture.startCapture((data,len)->sendToEngine(data,len));
                if(started){showOverlay("يلتقط صوت الفيديو");return;}
            }
        }
        startMic();
    }
    private void startMic(){
        int buf=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        micRecord=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buf*4);
        if(micRecord.getState()!=AudioRecord.STATE_INITIALIZED){showOverlay("خطأ في الميكروفون");return;}
        micRecord.startRecording();
        showOverlay("يستمع بالميكروفون");
        new Thread(()->{
            short[]b=new short[buf];
            while(running){
                int r=micRecord.read(b,0,b.length);
                if(r>0)sendToEngine(b,r);
            }
        },"MicThread").start();
    }
    private void addOverlay(){
        try{
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        overlay=new TextView(this);
        overlay.setTextColor(Color.WHITE);overlay.setTextSize(18f);
        overlay.setGravity(Gravity.CENTER);overlay.setShadowLayer(8f,0f,2f,Color.BLACK);
        overlay.setBackgroundColor(Color.TRANSPARENT);overlay.setPadding(20,8,20,8);overlay.setMaxLines(2);
        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        lp.gravity=gravity|Gravity.CENTER_HORIZONTAL;
        lp.y=posIndex==0?120:0;wm.addView(overlay,lp);
        }catch(Exception e){
            Log.e(TAG,"addOverlay FAILED: "+e.getMessage());
            overlay=null;
        }
    }
    private void showOverlay(String t){
        handler.post(()->{
            if(overlay==null)return;
            overlay.setText(t);
            overlay.setAlpha(1f);
            // اختفاء تلقائي بعد 3 ثواني
            handler.removeCallbacksAndMessages("hide");
            handler.postAtTime(()->{
                if(overlay!=null)overlay.setText("");
            },"hide",android.os.SystemClock.uptimeMillis()+3000);
        });
    }
    @Override public int onStartCommand(Intent i,int f,int id){
        if(i!=null&&ACTION_STOP.equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    @Override public void onDestroy(){
        running=false;
        if(gladia!=null)gladia.stop();
        if(deepgram!=null)deepgram.stop();
        if(audioCapture!=null)audioCapture.stop();
        if(micRecord!=null){try{micRecord.stop();micRecord.release();}catch(Exception ignored){}}
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        if(wm!=null&&overlay!=null){try{wm.removeView(overlay);}catch(Exception ignored){}}
        try{unregisterReceiver(screenOff);}catch(Exception ignored){}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
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
