package si.zya.grokwear;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * The assistant button: while Grok Wear is the watch's digital assistant, holding the watch's assistant
 * button calls the main Bot, as the official app's assistant does on phones. It keeps no microphone open.
 */
final class AssistantRole {
    private AssistantRole(){}
    private static final String PREFS="grokwear_ui",SETUP="button_setup_pending",OFFERED="button_offered";

    /** The assistant button is offered once after a new sign-in, and once for people already signed in when this version arrives. */
    static boolean setupPending(Context context){
        try{android.content.SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            if(!prefs.contains(OFFERED)){boolean ask=!held(context);prefs.edit().putBoolean(OFFERED,true).putBoolean(SETUP,ask).apply();return ask;}
            return prefs.getBoolean(SETUP,false);}
        catch(RuntimeException unavailable){return false;}
    }
    static void setSetupPending(Context context,boolean pending){try{context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(SETUP,pending).apply();}catch(RuntimeException ignored){}}
    /** Grok Wear is the watch's digital assistant, so the assistant button calls the main Bot. */
    static boolean held(Context context){
        try{RoleManager roles=context.getSystemService(RoleManager.class);return roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)&&roles.isRoleHeld(RoleManager.ROLE_ASSISTANT);}
        catch(RuntimeException unavailable){return false;}
    }
    /**
     * Where the watch's digital assistant is chosen. Apps can't request that role, so this opens the
     * page (Wear OS: Settings › Apps › Default apps › Digital assistant app), as the official app does.
     */
    static Intent settings(Context context){
        PackageManager packages=context.getPackageManager();
        for(String action:new String[]{Settings.ACTION_VOICE_INPUT_SETTINGS,Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS}){
            Intent intent=new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(intent.resolveActivity(packages)!=null)return intent;}
        return new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
