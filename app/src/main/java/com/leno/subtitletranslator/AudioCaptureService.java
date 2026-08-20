package com.leno.subtitletranslator;
import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
public class AudioCaptureService {
    private static final String TAG = "ACS_DIAG";
    public static final int REQUEST_CODE = 200;
    private static final int BUFFER_MULTIPLIER = 4;
    private static final boolean DEBUG = BuildConfig.DEBUG; // FIX: يوقف الـ verbose logging بالـ release
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback; // FIX: يكتشف إيقاف المشاركة من النظام (Android 14+)
    private AudioRecord audioRecord;
    private final SmartSleepManager sleepManager;
    private final AudioProcessor processor;
    private volatile boolean capturing = false; // FIX: volatile لضمان رؤية التحديث بين الـ threads
    private ProjectionStopListener stopListener;

    /** يُستدعى لما النظام يوقف المشاركة خارجياً (المستخدم ضغط Stop من إشعار النظام). */
    public interface ProjectionStopListener { void onProjectionStopped(); }

    public AudioCaptureService() {
        this(new SmartSleepManager());
    }
    /** يسمح بحقن SmartSleepManager مشترك بدل ما ينشئ كل مكوّن نسخته الخاصة. */
    public AudioCaptureService(SmartSleepManager sharedSleepManager) {
        this.sleepManager = sharedSleepManager;
        this.processor = new AudioProcessor();
    }
    public void setProjectionStopListener(ProjectionStopListener l){ this.stopListener = l; }

    public static boolean isSupported() {
        boolean s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        if(DEBUG) Log.d(TAG,"[1] isSupported="+s+" SDK="+Build.VERSION.SDK_INT);
        return s;
    }
    public static void requestPermission(Activity a) {
        if(DEBUG) Log.d(TAG,"[2] requestPermission");
        if(!isSupported())return;
        MediaProjectionManager m=(MediaProjectionManager)a.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        a.startActivityForResult(m.createScreenCaptureIntent(),REQUEST_CODE);
    }
    public boolean onActivityResult(Activity a,int code,Intent data) {
        if(DEBUG) Log.d(TAG,"[3] onActivityResult code="+code+" data="+(data!=null));
        if(code!=Activity.RESULT_OK||data==null){Log.w(TAG,"[3] denied");return false;}
        if(!isSupported())return false;
        MediaProjectionManager m=(MediaProjectionManager)a.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        mediaProjection=m.getMediaProjection(code,data);
        if(DEBUG) Log.d(TAG,"[4] projection="+(mediaProjection!=null?"OK":"NULL"));
        // FIX: تسجيل Callback إلزامي فعلياً من Android 14 قبل أي استخدام للـ MediaProjection،
        // وإلا capture ممكن يفشل بصمت أو ما تعرف لما المستخدم يوقفه من النظام
        if(mediaProjection!=null){
            projectionCallback=new MediaProjection.Callback(){
                @Override public void onStop(){
                    Log.d(TAG,"[4b] projection stopped by system/user");
                    capturing=false;
                    if(stopListener!=null) stopListener.onProjectionStopped();
                }
            };
            mediaProjection.registerCallback(projectionCallback,new Handler(Looper.getMainLooper()));
        }
        return mediaProjection!=null;
    }
    public boolean startCapture(AudioDataCallback cb) {
        if(DEBUG) Log.d(TAG,"[5] startCapture proj="+(mediaProjection!=null));
        if(!isSupported()||mediaProjection==null)return false;
        if(capturing){ Log.w(TAG,"[5] already capturing, ignoring duplicate start"); return false; } // FIX: يمنع تشغيل ثريدين
        try {
            AudioPlaybackCaptureConfiguration cfg=new AudioPlaybackCaptureConfiguration.Builder(mediaProjection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build();
            int buf=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            audioRecord=new AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(buf*BUFFER_MULTIPLIER).build();
            if(DEBUG) Log.d(TAG,"[5c] state="+audioRecord.getState());
            if(audioRecord.getState()!=AudioRecord.STATE_INITIALIZED)return false;
            capturing=true;
            audioRecord.startRecording();
            if(DEBUG) Log.d(TAG,"[6] recording="+audioRecord.getRecordingState());
            new Thread(()->{
                short[]b=new short[buf];
                short[]detectionBuf=new short[buf]; // يُعاد استخدامها كل فريم بدل تخصيص جديد
                int n=0;
                while(capturing){
                    int r=audioRecord.read(b,0,b.length);
                    if(r>0){
                        n++;
                        if(DEBUG && (n==1||n%50==0)){long s=0;for(int i=0;i<r;i++)s+=Math.abs(b[i]);Log.d(TAG,"[7] read#"+n+" amp="+(s/r));}
                        if(sleepManager.shouldProcess()){
                            // ننسخ الـ buffer قبل التمرير لـ AudioProcessor لأن normalizeAndDetectVoice
                            // يعدّل المصفوفة (highpass/normalize/compress). نبي نفس دقة الكشف
                            // للنوم الذكي فقط، بدون ما نغيّر الصوت الخام المرسل لـ Deepgram.
                            System.arraycopy(b,0,detectionBuf,0,r);
                            boolean voice=processor.normalizeAndDetectVoice(detectionBuf,r);
                            sleepManager.reportFrame(voice);
                            if(cb!=null)cb.onAudioData(b,r);
                        }else{
                            sleepManager.reportSkipped();
                        }
                    }
                    else if(r<0){Log.e(TAG,"[7] err="+r);break;}
                }
                if(DEBUG) Log.d(TAG,"[7] thread done reads="+n);
            },"AC-Thread").start();
            return true;
        } catch(Exception e){Log.e(TAG,"[5] ex="+e.getMessage());capturing=false;return false;}
    }
    public void stop(){
        capturing=false;
        if(audioRecord!=null){
            try{audioRecord.stop();audioRecord.release();}catch(Exception e){Log.e(TAG,"stop audioRecord: "+e.getMessage());}
            audioRecord=null;
        }
        if(mediaProjection!=null){
            if(projectionCallback!=null){
                try{mediaProjection.unregisterCallback(projectionCallback);}catch(Exception e){Log.e(TAG,"unregister callback: "+e.getMessage());}
                projectionCallback=null;
            }
            try{mediaProjection.stop();}catch(Exception e){Log.e(TAG,"stop projection: "+e.getMessage());} // FIX: كان بدون try/catch
            mediaProjection=null;
        }
        if(processor!=null){
            try{processor.releaseEffects();}catch(Exception e){Log.e(TAG,"release effects: "+e.getMessage());} // FIX: كان بدون try/catch
        }
    }
    public interface AudioDataCallback{void onAudioData(short[]data,int len);}
}
