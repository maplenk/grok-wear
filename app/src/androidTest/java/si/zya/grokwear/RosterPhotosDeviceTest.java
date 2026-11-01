package si.zya.grokwear;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Bot lists with custom photos, through the real decoder and encoder: photos are made like the official
 * apps save them (256 px PNG of a photo), the reply arrives in network-sized pieces, and every kept photo
 * must draw. Emulator only.
 */
@RunWith(AndroidJUnit4.class)
public class RosterPhotosDeviceTest {
    private static final String TAG="RosterPhotosTest";
    /** Photo-like pixels: smooth color fields with grain, which PNG compresses about as poorly as a photo. */
    private static Bitmap photo(int seed,int width,int height){
        Random random=new Random(seed);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        int r0=random.nextInt(256),g0=random.nextInt(256),b0=random.nextInt(256),r1=random.nextInt(256),g1=random.nextInt(256),b1=random.nextInt(256);
        int[] row=new int[width];
        for(int y=0;y<height;y++){for(int x=0;x<width;x++){double t=(x+y)/(double)(width+height),wave=Math.sin(x/17.0+seed)*Math.cos(y/23.0)*40;
            int grain=random.nextInt(36)-18;
            row[x]=0xff000000|clamp(r0+(r1-r0)*t+wave+grain)<<16|clamp(g0+(g1-g0)*t-wave+grain)<<8|clamp(b0+(b1-b0)*t+wave/2+grain);}
            bitmap.setPixels(row,0,width,0,y,width,1);}
        return bitmap;
    }
    private static int clamp(double v){return (int)Math.max(0,Math.min(255,v));}
    private static String dataUrl(Bitmap bitmap,Bitmap.CompressFormat format,String mime){
        ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(format,90,out);bitmap.recycle();
        return "data:"+mime+";base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    /** The reply in 16 KB pieces, as a network read would hand it over. */
    private static InputStream network(byte[] body){return new ByteArrayInputStream(body){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(len,16384));}};}

    @Test public void twentyBotsWithCustomPhotosAllKeepTheirPhotos()throws Exception{
        StringBuilder json=new StringBuilder("{\"agents\":[");long photoTotal=0;
        for(int i=0;i<20;i++){String url=dataUrl(photo(i,256,256),Bitmap.CompressFormat.PNG,"image/png");photoTotal+=url.length();
            json.append(i==0?"":",").append(new JSONObject().put("id","bot-"+i).put("name","Bot "+i).put("avatarShape","circle").put("isGroup",false)
                .put("lastMessagePreview","Hello from Bot "+i).put("avatarDataUrl",url));}
        byte[] body=json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        Log.i(TAG,"listAgents "+body.length+" bytes; photos average "+photoTotal/20+" bytes as base64");
        assertTrue("realistic photos make the list bigger than 1 MB",body.length>1024*1024);
        assertThrows("0.6.1 refused it",GrokVault.ResponseTooLarge.class,()->GrokVault.readBounded(new ByteArrayInputStream(body),1024*1024));

        Runtime runtime=Runtime.getRuntime();runtime.gc();long before=runtime.totalMemory()-runtime.freeMemory();
        long started=System.nanoTime();long[] raw=new long[1];
        byte[] kept=RosterStream.read(network(body),raw,PhotoShrink.INSTANCE);
        long ms=(System.nanoTime()-started)/1_000_000,grew=runtime.totalMemory()-runtime.freeMemory()-before;
        assertEquals(body.length,raw[0]);
        JSONArray bots=new JSONObject(new String(kept,StandardCharsets.UTF_8)).getJSONArray("agents");
        int budget=RosterStream.PHOTOS;long shrunkTotal=0;
        for(int i=0;i<20;i++){JSONObject row=GrokCore.rosterRow(bots.getJSONObject(i),budget);
            String small=row.optString("avatarDataUrl");
            assertTrue("Bot "+i+" keeps its photo",small.startsWith("data:image/webp;base64,"));
            Bitmap drawn=BotAvatarView.decodePhoto(small);assertNotNull("Bot "+i+"'s photo draws",drawn);
            assertEquals(PhotoShrink.SIDE,drawn.getWidth());assertEquals(PhotoShrink.SIDE,drawn.getHeight());drawn.recycle();
            budget-=small.length();shrunkTotal+=small.length();}
        Log.i(TAG,"read "+raw[0]+" bytes in "+ms+" ms; kept "+kept.length+" bytes; shrunk photos average "+shrunkTotal/20
            +" bytes; heap grew "+grew/1024+" KB");
    }
    @Test public void otherPhotoShapesAndBrokenPhotos(){
        String jpeg=PhotoShrink.INSTANCE.shrink(dataUrl(photo(40,256,256),Bitmap.CompressFormat.JPEG,"image/jpeg"));
        String wide=PhotoShrink.INSTANCE.shrink(dataUrl(photo(41,300,200),Bitmap.CompressFormat.PNG,"image/png"));
        String tiny=PhotoShrink.INSTANCE.shrink(dataUrl(photo(42,64,64),Bitmap.CompressFormat.PNG,"image/png"));
        for(String small:new String[]{jpeg,wide,tiny})assertTrue(small.startsWith("data:image/webp;base64,"));
        Bitmap a=BotAvatarView.decodePhoto(jpeg),b=BotAvatarView.decodePhoto(wide),c=BotAvatarView.decodePhoto(tiny);
        assertEquals(160,a.getWidth());assertEquals(160,b.getWidth());assertEquals(160,b.getHeight());
        assertEquals("a small photo is never enlarged",64,c.getWidth());
        assertEquals("",PhotoShrink.INSTANCE.shrink("data:image/png;base64,bm90IGFuIGltYWdl"));
        assertEquals("",PhotoShrink.INSTANCE.shrink("data:image/svg+xml;base64,PHN2Zy8+"));
        assertEquals("",PhotoShrink.INSTANCE.shrink("not a data url"));
        Log.i(TAG,"jpeg "+jpeg.length()+", wide "+wide.length()+", tiny "+tiny.length()+" characters after shrinking");
    }
}
