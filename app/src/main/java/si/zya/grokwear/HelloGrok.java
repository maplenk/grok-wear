package si.zya.grokwear;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * The assistant button and "Hello Grok". Both need Grok Wear to be the watch's digital assistant: holding
 * the watch's assistant button then calls the main Bot, with no extra battery. "Hello Grok" is a separate
 * switch on top: listening runs in GrokAssistantService, which Android keeps bound only while Grok Wear
 * holds that role, so the switch alone isn't enough: both must hold. Also the words said right after it.
 */
final class HelloGrok {
    private HelloGrok(){}
    /** MainActivity's action for a call started by the wake word. */
    static final String ACTION_HELLO="si.zya.grokwear.HELLO";
    /** Package-local: the assistant service re-reads the switch and its conditions. */
    static final String ACTION_APPLY="si.zya.grokwear.HELLO_APPLY";
    private static final String PREFS="grokwear_ui",ENABLED="hello_grok",SETUP="button_setup_pending",OFFERED="button_offered";

    /** The Settings switch. Off unless turned on: listening costs battery. */
    static boolean enabled(Context context){try{return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(ENABLED,false);}catch(RuntimeException unavailable){return false;}}
    static void setEnabled(Context context,boolean on){try{context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(ENABLED,on).apply();}catch(RuntimeException ignored){}apply(context);}
    /** The assistant button is offered once after a new sign-in, and once for people already signed in when this version arrives. */
    static boolean setupPending(Context context){
        try{android.content.SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            if(!prefs.contains(OFFERED)){boolean ask=!isAssistant(context);prefs.edit().putBoolean(OFFERED,true).putBoolean(SETUP,ask).apply();return ask;}
            return prefs.getBoolean(SETUP,false);}
        catch(RuntimeException unavailable){return false;}
    }
    static void setSetupPending(Context context,boolean pending){try{context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(SETUP,pending).apply();}catch(RuntimeException ignored){}}
    static boolean microphoneAllowed(Context context){return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    /** Grok Wear is the watch's digital assistant: the assistant button calls the main Bot, and Android keeps GrokAssistantService running. */
    static boolean isAssistant(Context context){
        try{RoleManager roles=context.getSystemService(RoleManager.class);return roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)&&roles.isRoleHeld(RoleManager.ROLE_ASSISTANT);}
        catch(RuntimeException unavailable){return false;}
    }
    /** Listening needs all three: the switch, the microphone and the assistant role. */
    static boolean ready(Context context){return enabled(context)&&microphoneAllowed(context)&&isAssistant(context);}
    /**
     * Where the watch's digital assistant is chosen. Apps can't request that role, so this opens the
     * page (Wear OS: Settings › Apps › Default apps › Digital assistant app), as the official app does.
     */
    static Intent assistantSettings(Context context){
        PackageManager packages=context.getPackageManager();
        for(String action:new String[]{Settings.ACTION_VOICE_INPUT_SETTINGS,Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS}){
            Intent intent=new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(intent.resolveActivity(packages)!=null)return intent;}
        return new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    /** Tells a running assistant service to start or stop listening to match the switch. */
    static void apply(Context context){try{context.sendBroadcast(new Intent(ACTION_APPLY).setPackage(context.getPackageName()));}catch(RuntimeException ignored){}}

    /** The audio after "Hello Grok", kept while the call connects so a question in the same breath reaches the Bot. */
    interface Carry{short[] take();}
    private static Carry carry;private static long carryAt,heardAt;
    static final long CARRY_TTL_MS=15000;
    static synchronized void holdCarry(Carry source){carry=source;carryAt=android.os.SystemClock.elapsedRealtime();heardAt=carryAt;}
    /**
     * Whether a HELLO launch is the listener's own, heard moments ago. MainActivity is exported, so another
     * app's intent with that action must not start a call; it opens the app like any launch instead.
     */
    static synchronized boolean justHeard(){boolean fresh=heardAt>0&&android.os.SystemClock.elapsedRealtime()-heardAt<=CARRY_TTL_MS;heardAt=0;return fresh;}
    static synchronized void dropCarry(Carry source){if(carry==source)carry=null;}
    /** 16 kHz PCM said since the wake word, or null; the listener stops recording before this returns. */
    static short[] takeCarry(){Carry source;synchronized(HelloGrok.class){source=carry;carry=null;if(source==null||android.os.SystemClock.elapsedRealtime()-carryAt>CARRY_TTL_MS)return null;}
        return source.take();}
    /** 16 kHz to the call's 24 kHz, little-endian PCM bytes; linear interpolation is enough for speech. */
    static byte[] toCallRate(short[] in){
        if(in==null||in.length==0)return new byte[0];int frames=(int)((long)in.length*3/2);byte[] out=new byte[frames*2];
        for(int i=0;i<frames;i++){double at=i*2/3.0;int a=(int)at;int b=Math.min(a+1,in.length-1);double f=at-a;
            int value=(int)Math.round(in[a]*(1-f)+in[b]*f);out[2*i]=(byte)value;out[2*i+1]=(byte)(value>>8);}
        return out;
    }
}
