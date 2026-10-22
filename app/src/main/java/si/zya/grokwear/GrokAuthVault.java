package si.zya.grokwear;

import android.content.Context;
import org.json.JSONObject;

/** Separate Keystore alias and no-backup file; never replaces the manual gateway/inbox vault. */
final class GrokAuthVault implements GrokAuthCore.Store {
    private final GrokVault encrypted;
    GrokAuthVault(Context context) {encrypted=new GrokVault(context,".browser-auth-v1");}
    public JSONObject read() throws Exception {return encrypted.read();}
    public void write(JSONObject value) throws Exception {encrypted.write(value);}
}
