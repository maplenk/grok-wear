package si.zya.grokwear;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;

/**
 * A Bot photo shrunk to the size the watch shows it. The official apps save a custom Bot photo as a
 * 256 px PNG, often 100–200 KB, and the service allows up to 1 MB; the largest avatar on the watch is
 * 64 dp, about 150 px. Each photo becomes a centered 160 px square WebP of a few KB, so every Bot in a
 * long list keeps its own photo. Anything that isn't a readable PNG, JPEG or WebP becomes "".
 */
final class PhotoShrink implements RosterStream.Photos {
    static final PhotoShrink INSTANCE=new PhotoShrink();
    static final int SIDE=160;
    private PhotoShrink(){}
    @Override public String shrink(String value){
        Bitmap source=null,square=null;
        try{int comma=value.indexOf(',');if(comma<0)return "";
            String header=value.substring(0,comma);
            if(!header.equals("data:image/png;base64")&&!header.equals("data:image/jpeg;base64")&&!header.equals("data:image/webp;base64"))return "";
            byte[] bytes=android.util.Base64.decode(value.substring(comma+1),android.util.Base64.NO_WRAP);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>4096||bounds.outHeight>4096)return "";
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;
            int shorter=Math.min(bounds.outWidth,bounds.outHeight);
            while(shorter/(options.inSampleSize*2)>=SIDE)options.inSampleSize*=2;
            source=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(source==null)return "";
            int edge=Math.min(source.getWidth(),source.getHeight()),side=Math.min(SIDE,edge);
            int left=(source.getWidth()-edge)/2,top=(source.getHeight()-edge)/2;
            square=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
            new Canvas(square).drawBitmap(source,new Rect(left,top,left+edge,top+edge),new Rect(0,0,side,side),new Paint(Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(!square.compress(Bitmap.CompressFormat.WEBP_LOSSY,80,out))return "";
            return "data:image/webp;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);
        }catch(RuntimeException|OutOfMemoryError unreadable){return "";}
        finally{if(source!=null)source.recycle();if(square!=null)square.recycle();}
    }
}
