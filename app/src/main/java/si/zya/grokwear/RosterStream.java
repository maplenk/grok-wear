package si.zya.grokwear;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads a listAgents reply as it arrives and keeps only what the Bot list can use, so an account with
 * many Bots or custom Bot photos loads without the watch holding the whole reply. Each Bot photo
 * (avatarDataUrl, up to the service's 1 MB) is shrunk as soon as it has arrived, and shrunk photos are
 * kept while 1 MB of them remain. Other strings over 4 KB are kept only as message previews (up to 16 KB);
 * any other long string, which GrokCore.rosterRow would drop anyway, becomes "". JSON's quote and
 * backslash are single bytes that never occur inside a multibyte UTF-8 character, so the bytes are
 * scanned without decoding.
 */
final class RosterStream {
    private RosterStream(){}
    /** Turns one Bot photo data URL into the small one the watch keeps, or "". */
    interface Photos { String shrink(String dataUrl); }
    /** At most this much is downloaded, and at most this much is kept to parse. */
    static final int MAX_RAW=32*1024*1024,MAX_KEPT=8*1024*1024;
    static final int SHORT=4096,PREVIEW=16384,PHOTO_IN=1_500_000,PHOTOS=1024*1024;

    /** The reply with photos shrunk and long unused strings emptied; raw[0] is set to the bytes downloaded. */
    static byte[] read(InputStream in,long[] raw,Photos photos)throws IOException{return read(in,raw,photos,MAX_RAW,MAX_KEPT);}
    static byte[] read(InputStream in,long[] raw,Photos photos,int maxRaw,int maxKept)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream(),string=new ByteArrayOutputStream();
        byte[] block=new byte[8192];long total=0;int photoBytes=0,limit=0,n;
        // key: the member name the next value belongs to; last: the latest short string, a key if ':' follows.
        boolean inString=false,escaped=false,dropped=false,photo=false;String key="",last="";
        while((n=in.read(block))!=-1){
            total+=n;raw[0]=total;if(total>maxRaw)throw new GrokVault.ResponseTooLarge("Bot list",maxRaw);
            for(int i=0;i<n;i++){byte c=block[i];
                if(!inString){
                    if(c=='"'){inString=true;escaped=false;dropped=false;string.reset();
                        // Past the longest string that could be used, stop holding it.
                        photo="avatarDataUrl".equals(key);limit=photo?PHOTO_IN:PREVIEW;continue;}
                    if(c==':')key=last;else if(c==','||c=='{'||c=='}'||c=='['||c==']')key="";
                    out.write(c);continue;}
                if(escaped)escaped=false;
                else if(c=='\\')escaped=true;
                else if(c=='"'){inString=false;int size=string.size();
                    out.write('"');
                    if(dropped){}
                    // JSON may escape "/" as "\/", which base64 uses.
                    else if(photo){String small=photos.shrink(string.toString("UTF-8").replace("\\/","/"));
                        if(!small.isEmpty()&&small.length()<=PREVIEW&&photoBytes+small.length()<=PHOTOS){out.write(small.getBytes(StandardCharsets.UTF_8));photoBytes+=small.length();}}
                    else if(size<=SHORT||"lastMessagePreview".equals(key)&&size<=PREVIEW)string.writeTo(out);
                    out.write('"');
                    last=!dropped&&size<=64?string.toString("UTF-8"):"";
                    continue;}
                if(!dropped){string.write(c);if(string.size()>limit){dropped=true;string.reset();}}
            }
            if(out.size()>maxKept)throw new GrokVault.ResponseTooLarge("Bot list kept",maxKept);
        }
        // Cut off mid-string: pass on what arrived, so parsing fails as it would have before.
        if(inString){out.write('"');if(!dropped)string.writeTo(out);}
        return out.toByteArray();
    }
}
