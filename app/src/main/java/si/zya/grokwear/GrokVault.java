package si.zya.grokwear;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Grok credentials and inbox use a separate non-exportable Keystore key and no-backup file. */
final class GrokVault implements GrokCore.Store {
    private static final String ALIAS="si.zya.grokwear.credentials.v1";
    private static final byte[] AAD="grokwear/grok/v1".getBytes(StandardCharsets.UTF_8);
    private static final Object LOCK=new Object();
    private static final VaultSnapshotCache CACHE=new VaultSnapshotCache(8,2_000_000);
    private final AtomicFile file, legacy;
    private final File root;
    private final String alias,cacheKey;
    GrokVault(Context context){this(context,"");}
    GrokVault(Context context,String suffix){
        alias=ALIAS+suffix;
        root=context.getNoBackupFilesDir();
        file=new AtomicFile(new File(root,"grokwear/grok-v1"+suffix+".enc"));
        legacy=new AtomicFile(new File(root,"grokwear-grok-v1"+suffix+".enc"));
        cacheKey=file.getBaseFile().getAbsolutePath()+"\n"+alias;
    }
    private void migrate() throws Exception {
        if(!file.getBaseFile().exists() && !new File(file.getBaseFile()+".bak").exists()
                && (legacy.getBaseFile().exists() || new File(legacy.getBaseFile()+".bak").exists())) {
            JSONObject old=readFile(legacy);
            write(old);
        }
    }
    public synchronized JSONObject read() throws Exception {
        synchronized(LOCK){return CACHE.read(cacheKey,()->version().cacheVersion,()->{migrate();return readFile(file);});}
    }
    private JSONObject readFile(AtomicFile source) throws Exception {
        if(!source.getBaseFile().exists()&&!new File(source.getBaseFile()+".bak").exists())return new JSONObject();
        try(InputStream in=source.openRead()) {
            byte[] encoded=readBounded(in,2*1024*1024);
            if(encoded.length<29)throw new IOException();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Arrays.copyOf(encoded,12)));c.updateAAD(AAD);
            byte[] clear=c.doFinal(encoded,12,encoded.length-12);
            try{return new JSONObject(new String(clear,StandardCharsets.UTF_8));}finally{Arrays.fill(clear,(byte)0);}
        }catch(Exception e){throw new GrokCore.Failure("Grok storage is unreadable. Unlink Grok before linking again.",true);}
    }
    public synchronized void write(JSONObject value) throws Exception {
        synchronized(LOCK){
        CACHE.replace(cacheKey,()->{
        // The caller supplies the complete replacement. In particular, explicit unlink/reset
        // must not read a corrupt legacy credential before it can replace local state.
        File parent=file.getBaseFile().getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Could not open private work storage.");
        String serialized=value.toString();byte[] clear=serialized.getBytes(StandardCharsets.UTF_8);FileOutputStream out=null;
        try {
            if(clear.length>1900000)throw new IOException();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(true));c.updateAAD(AAD);
            out=file.startWrite();out.write(c.getIV());out.write(c.doFinal(clear));out.getFD().sync();file.finishWrite(out);out=null;
            for(File dir=parent;dir!=null;dir=dir.getParentFile()){
                syncDirectory(dir);if(dir.equals(root))break;
            }
            FileVersion verifiedVersion=version();
            if(!readFile(file).toString().equals(serialized))throw new IOException();
            // The old copy remains recoverable until the new file and its directory links verify.
            legacy.delete();
            if(legacy.getBaseFile().exists()||new File(legacy.getBaseFile()+".bak").exists()
                    ||new File(legacy.getBaseFile()+".new").exists())throw new IOException();
            syncDirectory(root);
            FileVersion savedVersion=version();
            if(verifiedVersion.primary!=null&&savedVersion.primary!=null&&!verifiedVersion.primary.equals(savedVersion.primary))throw new IOException();
            // Publish only after encryption, fsync, authenticated readback and legacy cleanup.
            // Unknown/replaced metadata disables caching rather than trusting an old snapshot.
            String stamp=verifiedVersion.primary!=null&&verifiedVersion.primary.equals(savedVersion.primary)?savedVersion.cacheVersion:null;
            return new VaultSnapshotCache.Saved(stamp,serialized);
        }catch(Exception e){if(out!=null)file.failWrite(out);throw new GrokCore.Failure("Could not save Grok state. Check Grok before sending again.",true);}
        finally{Arrays.fill(clear,(byte)0);}
        });
        }
    }
    private static final class FileVersion {
        final String primary,cacheVersion;
        FileVersion(String primary,String cacheVersion){this.primary=primary;this.cacheVersion=cacheVersion;}
    }
    private FileVersion version(){
        File base=file.getBaseFile(),old=legacy.getBaseFile();
        String[] identities={identity(base),identity(new File(base+".bak")),identity(new File(base+".new")),
            identity(old),identity(new File(old+".bak")),identity(new File(old+".new"))};
        boolean cacheable=true;StringBuilder stamp=new StringBuilder();
        for(int i=0;i<identities.length;i++){
            if(identities[i]==null||(i>0&&!identities[i].equals("missing")))cacheable=false;
            stamp.append(i).append(':').append(identities[i]).append(';');
        }
        return new FileVersion(identities[0],cacheable?stamp.toString():null);
    }
    private static String identity(File path){
        try{
            android.system.StructStat stat=android.system.Os.stat(path.getAbsolutePath());
            return stat.st_dev+":"+stat.st_ino+":"+stat.st_size+":"+stat.st_mode+":"
                +stat.st_mtim.tv_sec+":"+stat.st_mtim.tv_nsec+":"+stat.st_ctim.tv_sec+":"+stat.st_ctim.tv_nsec;
        }catch(android.system.ErrnoException absent){return absent.errno==android.system.OsConstants.ENOENT?"missing":null;}
        catch(RuntimeException unavailable){return null;}
    }
    private static void syncDirectory(File directory)throws Exception {
        if(!directory.isDirectory())throw new IOException();
        java.io.FileDescriptor descriptor=android.system.Os.open(directory.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);
        try{android.system.Os.fsync(descriptor);}finally{android.system.Os.close(descriptor);}
    }
    private javax.crypto.SecretKey key(boolean create)throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);javax.crypto.SecretKey k=(javax.crypto.SecretKey)ks.getKey(alias,null);
        if(k!=null)return k;if(!create)throw new IOException();
        KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return gen.generateKey();
    }
    static byte[] readBounded(InputStream in,int max)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;
        while((n=in.read(block))!=-1){if(b.size()+n>max)throw new ResponseTooLarge();b.write(block,0,n);}return b.toByteArray();
    }
    /** Its own type so Report a problem can tell it from a dropped connection. */
    static final class ResponseTooLarge extends IOException {}
}
