package si.zya.grokwear;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VaultSnapshotCacheTest {
    private static final class Disk {
        String json="{\"bot\":{\"name\":\"Saved\"}}",version="inode-1:time-1";
        int reads;
        JSONObject load()throws Exception{reads++;return new JSONObject(json);}
    }
    private static final class Vault {
        final VaultSnapshotCache cache;final Disk disk;final String key;
        Vault(VaultSnapshotCache cache,Disk disk,String key){this.cache=cache;this.disk=disk;this.key=key;}
        JSONObject read()throws Exception{return cache.read(key,()->disk.version,disk::load);}
        void write(JSONObject value)throws Exception {
            String serialized=value.toString();cache.replace(key,()->{
                disk.json=serialized;disk.version=disk.version+":next";
                return new VaultSnapshotCache.Saved(disk.version,serialized);
            });
        }
    }
    @Test public void repeatedReadsAndCallerMutationsCannotChangeSharedSnapshot()throws Exception {
        VaultSnapshotCache cache=new VaultSnapshotCache(8,2000);Disk disk=new Disk();Vault vault=new Vault(cache,disk,"account-vault");
        JSONObject first=vault.read();first.getJSONObject("bot").put("name","Mutated first read");
        JSONObject second=vault.read();assertEquals("Saved",second.getJSONObject("bot").getString("name"));
        second.getJSONObject("bot").put("name","Mutated cache hit");
        assertEquals("Saved",vault.read().getJSONObject("bot").getString("name"));assertEquals(1,disk.reads);
        JSONObject written=new JSONObject().put("bot",new JSONObject().put("name","New"));vault.write(written);
        written.getJSONObject("bot").put("name","Mutated write input");
        assertEquals("New",vault.read().getJSONObject("bot").getString("name"));assertEquals(1,disk.reads);
    }
    @Test public void multipleInstancesShareVerifiedWritesAndDetectExternalReplacement()throws Exception {
        VaultSnapshotCache cache=new VaultSnapshotCache(8,2000);Disk disk=new Disk();
        Vault activity=new Vault(cache,disk,"same-path-and-alias"),service=new Vault(cache,disk,"same-path-and-alias");
        activity.read();service.read();assertEquals(1,disk.reads);
        service.write(new JSONObject().put("selected","second"));assertEquals("second",activity.read().getString("selected"));assertEquals(1,disk.reads);
        disk.json="{\"selected\":\"external\"}";disk.version="replacement-inode:same-size-and-mtime";
        assertEquals("external",activity.read().getString("selected"));assertEquals(2,disk.reads);
        disk.json="{\"selected\":\"in-place\"}";disk.version="replacement-inode:changed-ctime-nanoseconds";
        assertEquals("in-place",service.read().getString("selected"));assertEquals(3,disk.reads);
    }
    @Test public void failedOrUncertainWriteInvalidatesOldAndTentativeSnapshots()throws Exception {
        VaultSnapshotCache cache=new VaultSnapshotCache(8,2000);Disk disk=new Disk();Vault vault=new Vault(cache,disk,"private");
        vault.read();
        try{cache.replace("private",()->{throw new java.io.IOException("Before durable write");});fail();}catch(java.io.IOException expected){}
        assertEquals("Saved",vault.read().getJSONObject("bot").getString("name"));assertEquals(2,disk.reads);
        try{cache.replace("private",()->{
            disk.json="{\"selected\":\"uncertain\"}";disk.version="new-file";
            throw new java.io.IOException("Readback or fsync failed after replacement");
        });fail();}catch(java.io.IOException expected){}
        assertEquals("uncertain",vault.read().getString("selected"));assertEquals(3,disk.reads);
    }
    @Test public void resetDeletionAndDifferentVaultsNeverReusePriorAccountData()throws Exception {
        VaultSnapshotCache cache=new VaultSnapshotCache(8,2000);Disk disk=new Disk();Vault vault=new Vault(cache,disk,"private");
        vault.read();vault.write(new JSONObject());assertEquals(0,vault.read().length());
        vault.write(new JSONObject().put("newAccount","example"));disk.json="{}";disk.version="all-files-missing";
        assertEquals(0,vault.read().length());assertEquals(2,disk.reads);
        Disk other=new Disk();other.json="{\"separate\":true}";
        Vault otherVault=new Vault(cache,other,"different-path-and-alias");assertTrue(otherVault.read().getBoolean("separate"));assertEquals(0,vault.read().length());
    }
    @Test public void recoveryReplacementDuringLoadCannotBePublishedUnderNewVersion()throws Exception {
        VaultSnapshotCache cache=new VaultSnapshotCache(8,2000);Disk disk=new Disk();
        JSONObject observed=cache.read("private",()->disk.version,()->{
            JSONObject old=disk.load();disk.json="{\"recovered\":true}";disk.version="recovered-file";return old;
        });
        assertEquals("Saved",observed.getJSONObject("bot").getString("name"));
        assertTrue(cache.read("private",()->disk.version,disk::load).getBoolean("recovered"));assertEquals(2,disk.reads);
    }
    @Test public void UnknownMetadataOrUnreadableReplacementCannotReturnStaleSnapshot()throws Exception {
        VaultSnapshotCache cache=new VaultSnapshotCache(8,2000);Disk disk=new Disk();Vault vault=new Vault(cache,disk,"private");
        vault.read();disk.version=null;vault.read();vault.read();assertEquals(3,disk.reads);
        disk.version="changed-unreadable";
        try{cache.read("private",()->disk.version,()->{throw new java.io.IOException("Authenticated read failed");});fail();}catch(java.io.IOException expected){}
        assertEquals("Saved",vault.read().getJSONObject("bot").getString("name"));assertEquals(4,disk.reads);
    }
    @Test public void memoryBudgetEvictsOldEntriesAndDoesNotRetainOversizeValues()throws Exception {
        VaultSnapshotCache cache=new VaultSnapshotCache(2,100);Disk a=new Disk(),b=new Disk(),c=new Disk();
        Vault first=new Vault(cache,a,"a"),second=new Vault(cache,b,"b"),third=new Vault(cache,c,"c");
        first.read();second.read();third.read();first.read();assertEquals(2,a.reads);
        Disk large=new Disk();large.json=new JSONObject().put("large","x".repeat(101)).toString();Vault oversize=new Vault(cache,large,"large");
        oversize.read();oversize.read();assertEquals(2,large.reads);
    }
}
