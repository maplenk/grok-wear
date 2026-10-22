package si.zya.grokwear;

import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded process memory only. Immutable JSON snapshots never share caller-owned objects. */
final class VaultSnapshotCache {
    interface Version { String current()throws Exception; }
    interface Reader { JSONObject load()throws Exception; }
    interface Writer { Saved persist()throws Exception; }
    static final class Saved {
        final String version,json;
        Saved(String version,String json){this.version=version;this.json=json;}
    }
    private final int maxEntries,maxCharacters;
    private int characters;
    private final LinkedHashMap<String,Saved> entries=new LinkedHashMap<>(4,.75f,true);
    VaultSnapshotCache(int maxEntries,int maxCharacters){this.maxEntries=maxEntries;this.maxCharacters=maxCharacters;}
    synchronized JSONObject read(String key,Version version,Reader reader)throws Exception {
        String before=version.current();Saved cached=entries.get(key);
        if(before!=null&&cached!=null&&before.equals(cached.version))return new JSONObject(cached.json);
        invalidate(key);
        JSONObject value=reader.load();String after=version.current();
        // AtomicFile recovery or an external replacement may change the files while loading.
        // Return the authenticated read, but do not associate it with a different file version.
        if(before!=null&&before.equals(after))publish(key,new Saved(after,value.toString()));
        else invalidate(key);
        return value;
    }
    synchronized void replace(String key,Writer writer)throws Exception {
        invalidate(key);boolean complete=false;
        try{Saved saved=writer.persist();if(saved!=null&&saved.version!=null)publish(key,saved);complete=true;}
        finally{if(!complete)invalidate(key);}
    }
    synchronized void invalidate(String key){Saved old=entries.remove(key);if(old!=null)characters-=old.json.length();}
    private void publish(String key,Saved saved){
        invalidate(key);
        if(saved.json.length()>maxCharacters||maxEntries<1)return;
        entries.put(key,saved);characters+=saved.json.length();
        while(entries.size()>maxEntries||characters>maxCharacters){Map.Entry<String,Saved> oldest=entries.entrySet().iterator().next();invalidate(oldest.getKey());}
    }
}
