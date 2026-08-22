package com.leno.subtitletranslator;
import android.Manifest;
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
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
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
import androidx.core.content.ContextCompat;
import java.util.concurrent.atomic.AtomicInteger;
public class SubtitleService extends Service {
    private static final String TAG="SubtitleService";
    private static final String CHANNEL_ID="subtitle_ch";
    private static final int NOTIF_ID=1001;
    public static final String ACTION_STOP="com.leno.subtitletranslator.STOP";
    private static final long WAKELOCK_SLICE_MS=10*60*1000L;
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
    private UserManager usageTracker; // NEW: يتتبع وقت الاستخدام - مربوط بحياة الخدمة نفسها لا بالشاشة
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            // لا نوقف الخدمة عند قفل الشاشة
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);
        sourceLang=p.getString(MainActivity.KEY_SOURCE_LANG,"en-US");
        targetLang=p.getString(MainActivity.KEY_TARGET_LANG,"ar");
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ST::lock");
        wakeLock.acquire(WAKELOCK_SLICE_MS);
        scheduleWakeLockRenewal();

        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
            registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        createChannel();
        startForegroundCompat();
        addOverlay();
        running=true;
        usageTracker=new UserManager(this); // NEW
        usageTracker.startTranslation(); // NEW: التتبع يبدأ هنا - مع الخدمة الفعلية نفسها، مو مع الشاشة
        quota=new EngineQuotaManager(this);
        startBestEngine();
        startAudioCapture();
    }

    private void startForegroundCompat(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
            int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIF_ID,buildNotif(),type);
        } else {
            startForeground(NOTIF_ID,buildNotif());
        }
    }

    private void scheduleWakeLockRenewal(){
        handler.postDelayed(()->{
            if(!running) return;
            try{
                if(wakeLock!=null){
                    if(wakeLock.isHeld()) wakeLock.release();
                    wakeLock.acquire(WAKELOCK_SLICE_MS);
                }
            }catch(Exception e){ Log.e(TAG,"wakelock renew failed: "+e.getMessage()); }
            scheduleWakeLockRenewal();
        },WAKELOCK_SLICE_MS-60*1000L);
    }

    private void startBestEngine(){
        UserManager um = new UserManager(this);
        UserManager.Tier tier = um.getCurrentTier();
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
                // Deepgram للـ PLUS و PRO فقط
                if(tier == UserManager.Tier.FREE){
                    showOverlay("يتطلب اشتراك PLUS");
                    return;
                }
                deepgram=new DeepgramEngine();
                String dgKey=tier==UserManager.Tier.PRO?KeyManager.getDeepgramKey(this):KeyManager.getDeepgramPlusKey(this);
                deepgram.start(dgKey,sourceLang,(text,isFinal)->handleTranscript(text,isFinal));
                showOverlay("Deepgram جاهز");
                break;
            default:
                // SpeechRecognizer مجاني
                showOverlay("وضع مجاني");
                break;
        }
    }
    // Smart Sleep - بدون أي تغيير
    private volatile long lastAudioTime = 0;
    private volatile boolean sleeping = false;

    private boolean hasAudio(short[]data,int len){
        long sum=0;
        for(int i=0;i<len;i++) sum+=Math.abs(data[i]);
        return (sum/len) > 200; // threshold
    }

    private void sendToEngine(short[]data,int len){
        // تحقق إذا في صوت حقيقي
        if(!hasAudio(data,len)){
            long silent = System.currentTimeMillis()-lastAudioTime;
            // سكوت 3 ثواني = وقف الإرسال
            if(silent>3000) sleeping=true;
            if(sleeping) return;
        } else {
            lastAudioTime=System.currentTimeMillis();
            sleeping=false;
        }
        switch(activeEngine){
            case GLADIA: if(gladia!=null)gladia.sendAudio(data,len); break;
            case DEEPGRAM:     if(deepgram!=null)deepgram.sendAudio(data,len); break;
        }
        // سجّل الاستخدام (~62.5ms لكل buffer 16000hz)
        quota.recordUsage(activeEngine,(long)(len/16.0));
    }

    // ===================== نظام توقيت الكابشن الحي (Netflix-style) =====================
    // FIX: تحولت من trailing debounce إلى throttle.
    // قبل: كل interim جديد يلغي المؤقت ويبدأ من الصفر - أثناء كلام مستمر (interim كل ~200-300ms)
    // المؤقت ما يفضل يوصل يصفّر أبداً، فالترجمة ما تتحرك إلا بعد سكوت فعلي أو final.
    // الحين: أول interim بعد فترة هدوء يجدول ترجمة بعد INTERIM_DEBOUNCE_MS، وأي interim
    // يوصل أثناء الانتظار بس يحدّث النص المخزّن بدون ما يعيد الجدولة - فتصير الترجمة تتحدث
    // بمعدل ثابت أثناء الكلام المستمر (طلب واحد كل ~500ms تقريباً) بدل ما تنتظر توقف الكلام بالكامل.

    private static final long INTERIM_DEBOUNCE_MS=500;
    private static final long CAPTION_FINAL_HOLD_MS=1200;
    private static final long CAPTION_INTERIM_SAFETY_MS=1800;

    private final AtomicInteger transcriptSeq=new AtomicInteger(0); // يمنع نتيجة ترجمة متأخرة تكتب فوق نص أحدث
    private volatile String pendingInterimText=null;
    private volatile boolean interimScheduled=false; // FIX: يميّز "فيه ترجمة مجدولة بالفعل" عشان نسوي throttle مو debounce

    private final Runnable interimTranslateRunnable=()->{
        interimScheduled=false;
        String t=pendingInterimText;
        if(t==null||t.trim().isEmpty())return;
        int seq=transcriptSeq.incrementAndGet();
        translateAndShow(t,false,seq);
    };

    private final Runnable hideCaptionRunnable=()->{ if(overlay!=null) overlay.setText(""); };

    /** يُستدعى من Deepgram لكل نتيجة (interim أو final). */
    private void handleTranscript(String text,boolean isFinal){
        if(text==null||text.trim().isEmpty())return;
        if(isFinal){
            handler.removeCallbacks(interimTranslateRunnable);
            interimScheduled=false;
            pendingInterimText=null;
            int seq=transcriptSeq.incrementAndGet();
            translateAndShow(text,true,seq);
        } else {
            if(text.equals(pendingInterimText))return; // نفس النص بالضبط، تجاهل
            pendingInterimText=text;
            if(!interimScheduled){ // FIX: throttle - يجدول مرة وحدة، ما يعيد الجدولة مع كل تحديث
                interimScheduled=true;
                handler.postDelayed(interimTranslateRunnable,INTERIM_DEBOUNCE_MS);
            }
            // لو فيه ترجمة مجدولة أصلاً، خلاص - راح تاخذ آخر pendingInterimText وقت ما تشتغل
        }
    }

    private void translateAndShow(String text,boolean isFinal,int seq){
        MLKitTranslator.translate(text,sourceLang,targetLang,t->{
            if(seq!=transcriptSeq.get())return; // نتيجة قديمة وصلت متأخرة - تجاهل
            updateCaption(t,isFinal);
        });
    }

    /** يعرض نص الترجمة على الـ overlay بمنطق "يتحدث مع الكلام + يثبت عند التوقف". */
    private void updateCaption(String text,boolean isFinal){
        handler.post(()->{
            if(overlay==null||text==null||text.trim().isEmpty())return;
            overlay.setText(text); // استبدال مباشر، مو تراكم فوق النص القديم
            handler.removeCallbacks(hideCaptionRunnable);
            long delay=isFinal?CAPTION_FINAL_HOLD_MS:CAPTION_INTERIM_SAFETY_MS;
            handler.postDelayed(hideCaptionRunnable,delay);
        });
    }

    /** مسار Gladia (نتائج نهائية فقط) - يمر بنفس نظام الكابشن الحي. */
    private void translate(String text){
        if(text==null||text.trim().isEmpty())return;
        int seq=transcriptSeq.incrementAndGet();
        translateAndShow(text,true,seq);
    }
    // ===================== نهاية نظام توقيت الكابشن =====================

    private void startAudioCapture(){
        Intent proj=MainActivity.getProjectionData();
        if(AudioCaptureService.isSupported()&&proj!=null){
            audioCapture=new AudioCaptureService();
            audioCapture.setProjectionStopListener(()->{
                Log.d(TAG,"Screen share stopped externally, falling back to mic");
                handler.post(()->{
                    if(running) startMic();
                });
            });
            boolean ok=audioCapture.onActivityResult(null,android.app.Activity.RESULT_OK,proj);
            if(ok){
                boolean started=audioCapture.startCapture((data,len)->sendToEngine(data,len));
                if(started){showOverlay("يلتقط صوت الفيديو");return;}
            }
        }
        startMic();
    }
    private void startMic(){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED){
            showOverlay("صلاحية المايك مطلوبة");
            return;
        }
        if(micRecord!=null){
            try{micRecord.stop();micRecord.release();}catch(Exception ignored){}
            micRecord=null;
        }
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
        android.content.SharedPreferences sp=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);
        int fs=sp.getInt("font_size",18);
        int pi=sp.getInt("subtitle_position",0);
        overlay.setTextColor(Color.WHITE);overlay.setTextSize(fs);
        overlay.setGravity(Gravity.CENTER);overlay.setShadowLayer(8f,0f,2f,Color.BLACK);
        overlay.setBackgroundColor(Color.TRANSPARENT);overlay.setPadding(20,8,20,8);overlay.setMaxLines(2);
        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        lp.gravity=pi==0?Gravity.BOTTOM:pi==1?Gravity.CENTER_VERTICAL:Gravity.TOP;
        lp.y=pi==0?120:0;wm.addView(overlay,lp);
        }catch(Exception e){
            Log.e(TAG,"addOverlay FAILED: "+e.getMessage());
            overlay=null;
        }
    }
    // showOverlay: رسائل الحالة القصيرة فقط (جاهز/خطأ/إلخ) - مهلة ثابتة 3 ثواني مناسبة لها
    private final Runnable hideRunnable=()->{ if(overlay!=null)overlay.setText(""); };

    private void showOverlay(String t){
        handler.post(()->{
            if(overlay==null)return;
            overlay.setText(t);
            handler.removeCallbacks(hideRunnable);
            handler.postDelayed(hideRunnable,3000);
        });
    }
    @Override public int onStartCommand(Intent i,int f,int id){
        if(i!=null&&ACTION_STOP.equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    @Override public void onDestroy(){
        running=false;
        if(usageTracker!=null) usageTracker.stopTranslation(); // NEW: يغطي كل مسارات الإيقاف (زر التطبيق، الإشعار، أو النظام)
        handler.removeCallbacksAndMessages(null);
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
