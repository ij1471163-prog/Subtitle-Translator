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
import android.util.Log;
public class AudioCaptureService {
    private static final String TAG = "ACS_DIAG";
    public static final int REQUEST_CODE = 200;
    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private boolean capturing = false;
    public static boolean isSupported() {
        boolean s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        Log.d(TAG,"[1] isSupported="+s+" SDK="+Build.VERSION.SDK_INT);
        return s;
    }
    public static void requestPermission(Activity a) {
        Log.d(TAG,"[2] requestPermission");
        if(!isSupported())return;
        MediaProjectionManager m=(MediaProjectionManager)a.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        a.startActivityForResult(m.createScreenCaptureIntent(),REQUEST_CODE);
    }
    public boolean onActivityResult(Activity a,int code,Intent data) {
        Log.d(TAG,"[3] onActivityResult code="+code+" data="+(data!=null));
        if(code!=Activity.RESULT_OK||data==null){Log.w(TAG,"[3] denied");return false;}
        if(!isSupported())return false;
        MediaProjectionManager m=(MediaProjectionManager)a.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        mediaProjection=m.getMediaProjection(code,data);
        Log.d(TAG,"[4] projection="+(mediaProjection!=null?"OK":"NULL"));
        return mediaProjection!=null;
    }
    public boolean startCapture(AudioDataCallback cb) {
        Log.d(TAG,"[5] startCapture proj="+(mediaProjection!=null));
        if(!isSupported()||mediaProjection==null)return false;
        try {
            AudioPlaybackCaptureConfiguration cfg=new AudioPlaybackCaptureConfiguration.Builder(mediaProjection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build();
            int buf=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            audioRecord=new AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(buf*4).build();
            Log.d(TAG,"[5c] state="+audioRecord.getState());
            if(audioRecord.getState()!=AudioRecord.STATE_INITIALIZED)return false;
            capturing=true;
            audioRecord.startRecording();
            Log.d(TAG,"[6] recording="+audioRecord.getRecordingState());
            new Thread(()->{
                short[]b=new short[buf];int n=0;
                while(capturing){
                    int r=audioRecord.read(b,0,b.length);
                    if(r>0){n++;if(n==1||n%50==0){long s=0;for(int i=0;i<r;i++)s+=Math.abs(b[i]);Log.d(TAG,"[7] read#"+n+" amp="+(s/r));}if(cb!=null)cb.onAudioData(b,r);}
                    else if(r<0){Log.e(TAG,"[7] err="+r);break;}
                }
                Log.d(TAG,"[7] thread done reads="+n);
            },"AC-Thread").start();
            return true;
        } catch(Exception e){Log.e(TAG,"[5] ex="+e.getMessage());return false;}
    }
    public void stop(){capturing=false;if(audioRecord!=null){try{audioRecord.stop();audioRecord.release();}catch(Exception e){}audioRecord=null;}if(mediaProjection!=null){mediaProjection.stop();mediaProjection=null;}}
    public interface AudioDataCallback{void onAudioData(short[]data,int len);}
}
