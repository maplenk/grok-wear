package si.zya.grokwear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * Grok Wear as the watch's digital assistant. Android keeps this bound while it holds that role, which
 * is what lets "Hello Grok" listen in the background and open a call over any app: as the assistant
 * it may start its microphone service and the call screen from the background.
 * It listens only while the screen is on: apps can't use the watch's low-power hotword chip, so listening
 * runs on the main processor and shows the microphone indicator, and with the screen on someone is
 * looking at the watch. Off the wrist, in Theater mode, in Battery Saver, on a low battery or during a
 * call it lets go of the microphone too.
 */
public final class GrokAssistantService extends VoiceInteractionService {
    private static final String TAG="GrokWearHello",CHANNEL="hello_grok",STOP="si.zya.grokwear.HELLO_STOP";
    private static final int NOTIFICATION=9,LOW_BATTERY=15;
    private final Handler main=new Handler(Looper.getMainLooper());
    private HotwordListener listener;private boolean ready,foreground,onBody=true,powerSave,lowBattery,theater,screenOn;
    private final Runnable carryTimeout=()->{if(listener!=null&&listener.carrying()){HelloGrok.dropCarry(listener);listener.stop();}evaluate();};
    private final Runnable callChanged=()->evaluate();
    private final BroadcastReceiver changes=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(Intent.ACTION_BATTERY_CHANGED.equals(action)){int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,100);
            boolean charging=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;lowBattery=!charging&&level>=0&&scale>0&&level*100<LOW_BATTERY*scale;}
        else if(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)){PowerManager power=getSystemService(PowerManager.class);powerSave=power!=null&&power.isPowerSaveMode();}
        // Ambient mode counts as off: SCREEN_OFF comes when the watch dims.
        else if(Intent.ACTION_SCREEN_ON.equals(action)||Intent.ACTION_SCREEN_OFF.equals(action))screenOn=interactive();
        evaluate();}};
    private final SensorEventListener wrist=new SensorEventListener(){
        @Override public void onSensorChanged(SensorEvent event){onBody=event.values.length==0||event.values[0]!=0f;evaluate();}
        @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}};
    private final ContentObserver theaterMode=new ContentObserver(main){@Override public void onChange(boolean self){theater=theaterOn();evaluate();}};

    @Override public void onReady(){
        super.onReady();if(ready)return;ready=true;
        IntentFilter filter=new IntentFilter(HelloGrok.ACTION_APPLY);filter.addAction(Intent.ACTION_BATTERY_CHANGED);filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);screenOn=interactive();
        androidx.core.content.ContextCompat.registerReceiver(this,changes,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        PowerManager power=getSystemService(PowerManager.class);powerSave=power!=null&&power.isPowerSaveMode();
        // Off the wrist nobody is there to say it, and Galaxy watches lock; the sensor is optional.
        SensorManager sensors=getSystemService(SensorManager.class);Sensor offBody=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT,true);
        if(offBody!=null)sensors.registerListener(wrist,offBody,SensorManager.SENSOR_DELAY_NORMAL);
        theater=theaterOn();getContentResolver().registerContentObserver(Settings.Global.getUriFor("theater_mode_on"),false,theaterMode);
        VoiceService.addListener(callChanged);
        evaluate();
    }
    @Override public void onShutdown(){teardown();super.onShutdown();}
    @Override public void onDestroy(){teardown();super.onDestroy();}
    private void teardown(){
        if(!ready)return;ready=false;main.removeCallbacksAndMessages(null);VoiceService.removeListener(callChanged);
        try{unregisterReceiver(changes);}catch(IllegalArgumentException alreadyGone){}
        SensorManager sensors=getSystemService(SensorManager.class);if(sensors!=null)sensors.unregisterListener(wrist);
        getContentResolver().unregisterContentObserver(theaterMode);
        stopListening();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        // The notification's Turn off, and broadcasts sent before onReady registered for them.
        if(intent!=null&&STOP.equals(intent.getAction()))HelloGrok.setEnabled(this,false);
        evaluate();return super.onStartCommand(intent,flags,startId);
    }
    /** The assistant gesture from the lock screen: the same call as the button, once the watch is unlocked. */
    @Override public void onLaunchVoiceAssistFromKeyguard(){openCall(Intent.ACTION_ASSIST);}

    private boolean interactive(){PowerManager power=getSystemService(PowerManager.class);return power==null||power.isInteractive();}
    private boolean theaterOn(){try{return Settings.Global.getInt(getContentResolver(),"theater_mode_on",0)!=0;}catch(RuntimeException unknown){return false;}}
    /** Listens when everything allows it; otherwise frees the microphone. A wake word being carried into a call is left alone. */
    private void evaluate(){
        // Until the call takes the words after "Hello Grok", or carryTimeout drops them.
        if(!ready||listener!=null&&listener.carrying())return;
        // Called for every call update: the call owns the microphone, so nothing else to check.
        if(VoiceService.snapshot().active){if(listener!=null&&listener.running())stopListening();else leaveForeground();return;}
        boolean want=HelloGrok.enabled(this)&&HelloGrok.microphoneAllowed(this)&&screenOn&&onBody&&!powerSave&&!lowBattery&&!theater;
        if(want&&(listener==null||!listener.running()))startListening();
        else if(!want&&listener!=null&&listener.running())stopListening();
        else if(!want)leaveForeground();
    }
    private void startListening(){
        // The microphone service first: background recording needs it, and as the assistant we may start it from here.
        try{startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);foreground=true;}
        catch(RuntimeException refused){Log.w(TAG,"listen_refused "+refused.getClass().getSimpleName());return;}
        if(listener==null)listener=new HotwordListener(this,new HotwordListener.Callback(){
            public void heard(float peak){main.post(()->onHeard(peak));}
            public void failed(){main.post(()->{Log.w(TAG,"listen_failed");leaveForeground();});}});
        listener.start();Log.i(TAG,"listen_start");
    }
    private void stopListening(){if(listener!=null&&listener.running()){HelloGrok.dropCarry(listener);listener.stop();Log.i(TAG,"listen_stop");}leaveForeground();}
    private void leaveForeground(){if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;}}
    private void onHeard(float peak){
        if(!ready||listener==null)return;
        Log.i(TAG,"heard peak="+Math.round(peak*100));
        // A tick says it heard you while the call connects. VibratorManager is Wear OS 4 (API 31) and up.
        try{Vibrator vibrator=android.os.Build.VERSION.SDK_INT>=31?getSystemService(VibratorManager.class).getDefaultVibrator():getSystemService(Vibrator.class);
            if(vibrator!=null)vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));}catch(RuntimeException ignored){}
        HelloGrok.holdCarry(listener);main.removeCallbacks(carryTimeout);main.postDelayed(carryTimeout,HelloGrok.CARRY_TTL_MS);
        openCall(HelloGrok.ACTION_HELLO);
    }
    private void openCall(String action){
        try{startActivity(new Intent(this,MainActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException refused){Log.w(TAG,"open_refused "+refused.getClass().getSimpleName());}
    }
    private Notification notification(){
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Listening for Hello Grok",NotificationManager.IMPORTANCE_MIN));
        PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off=PendingIntent.getService(this,3,new Intent(STOP).setComponent(new ComponentName(this,GrokAssistantService.class)),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_voice_status).setContentTitle("Listening for “Hello Grok”")
            .setContentText("On this watch only").setContentIntent(open).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_voice_status,"Turn off",off).build();
    }
}
