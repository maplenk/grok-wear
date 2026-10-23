package si.zya.grokwear;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import androidx.core.graphics.PathParser;
import org.json.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Cached shipped avatar outlines and eye expressions, sampled with original motion keyframes. */
public final class BotAvatarView extends View {
    private static final class Face {
        final Path body;final Path[] eyes,painted;final RectF[] bounds;final int[] paints;
        Face(JSONObject shape,String expression)throws Exception {
            body=PathParser.createPathFromPathData(shape.getString("body"));JSONArray paths=shape.getJSONObject("eyes").getJSONArray(expression);
            // Fixed-colour parts drawn over the body, such as the pumpkin's stem.
            JSONArray parts=shape.optJSONArray("painted");painted=new Path[parts==null?0:parts.length()];paints=new int[painted.length];
            for(int i=0;i<painted.length;i++){JSONObject part=parts.getJSONObject(i);painted[i]=PathParser.createPathFromPathData(part.getString("d"));paints[i]=Color.parseColor(AvatarPolicy.colorHex(part.getString("fill")));}
            eyes=new Path[paths.length()];bounds=new RectF[eyes.length];
            for(int i=0;i<eyes.length;i++){eyes[i]=PathParser.createPathFromPathData(paths.getString(i));bounds[i]=new RectF();eyes[i].computeBounds(bounds[i],true);}
        }
    }
    private static final class Designs {
        final JSONObject source;final Map<String,Face> faces=new HashMap<>();final Map<String,AvatarMotion> motion=new HashMap<>();final Map<String,Integer> colors=new HashMap<>();
        Designs(Context context)throws Exception {
            try(InputStream stream=context.getAssets().open("bot-avatar.json")){source=new JSONObject(new String(GrokVault.readBounded(stream,1024*1024),StandardCharsets.UTF_8));}
            JSONArray palette=source.getJSONArray("palette");for(int i=0;i<palette.length();i++){JSONObject color=palette.getJSONObject(i);colors.put(color.getString("id"),Color.parseColor(AvatarPolicy.colorHex(color.getString("value"))));}
            JSONArray clips=source.getJSONObject("timings").getJSONArray("clips");for(int i=0;i<clips.length();i++){JSONObject clip=clips.getJSONObject(i);motion.put(clip.getString("name"),new AvatarMotion(clip));}
        }
        synchronized Face face(String shape,String expression)throws Exception {String key=shape+":"+expression;Face face=faces.get(key);if(face==null){face=new Face(source.getJSONObject("shapes").getJSONObject(shape),expression);faces.put(key,face);}return face;}
    }
    private static Designs shared;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Rect visibleRect=new Rect();private final RectF ring=new RectF();
    private Designs designs;private Face face;private AvatarMotion motion;
    private Bitmap photo;private BitmapShader photoShader;private String photoDataUrl;
    private final Matrix photoMatrix=new Matrix();
    private boolean playing,attached,scheduled,reduced,preview;private String state="idle",shape="blob",name="",color="";
    private double frozenTime;
    private int ink=0xffececec;private long epoch=SystemClock.uptimeMillis();
    // One more draw after the idle budget lands the face on its rest pose.
    private final Runnable frame=()->{scheduled=false;invalidate();};
    /** Idle faces breathe and blink briefly, then rest: a list of idle Bots must not redraw forever. */
    static final long IDLE_MOTION_MS=6000;
    private boolean idleSettled(){return state.equals("idle")&&SystemClock.uptimeMillis()-epoch>=IDLE_MOTION_MS;}
    public BotAvatarView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        try{synchronized(BotAvatarView.class){if(shared==null)shared=new Designs(context);designs=shared;}refresh();}catch(Exception invalid){setContentDescription("Bot face unavailable");}
    }
    /** Bounded inline image only; never opens URLs or accepts SVG. Call on the UI thread. */
    public boolean setPhotoDataUrl(String value){
        if(value!=null&&value.equals(photoDataUrl)&&photo!=null)return true;
        releasePhoto();photoDataUrl=null;
        Bitmap decoded=decodePhoto(value);if(decoded==null){refresh();return false;}
        photo=decoded;photoDataUrl=value;photoShader=new BitmapShader(photo,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);refresh();return true;
    }
    static Bitmap decodePhoto(String value){
        if(value==null||value.length()>65536)return null;
        String mime;int comma=value.indexOf(',');if(comma<0)return null;
        String header=value.substring(0,comma);
        if(header.equals("data:image/png;base64"))mime="image/png";
        else if(header.equals("data:image/jpeg;base64"))mime="image/jpeg";
        else if(header.equals("data:image/webp;base64"))mime="image/webp";else return null;
        String encoded=value.substring(comma+1);if(encoded.isEmpty()||!encoded.matches("[A-Za-z0-9+/]*={0,2}"))return null;
        byte[] bytes=null;try{
            bytes=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);if(bytes.length>49152)return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>4096||bounds.outHeight>4096||!mime.equals(bounds.outMimeType))return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            options.inSampleSize=1;while((bounds.outWidth+options.inSampleSize-1)/options.inSampleSize>256||(bounds.outHeight+options.inSampleSize-1)/options.inSampleSize>256)options.inSampleSize*=2;
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(bitmap!=null&&(bitmap.getWidth()>256||bitmap.getHeight()>256)){bitmap.recycle();return null;}return bitmap;
        }catch(IllegalArgumentException invalid){return null;}finally{if(bytes!=null)java.util.Arrays.fill(bytes,(byte)0);}
    }
    private void releasePhoto(){photoShader=null;if(photo!=null){photo.recycle();photo=null;}}
    public void setPreview(boolean value){preview=value;setBotName(name);}
    public void setBotName(String value){String next=value==null?"":value;if(!next.equals(name)){releasePhoto();photoDataUrl=null;}name=next;if(preview){shape=name.equals("Research Bot")?"cloud":"blob";color=name.equals("Research Bot")?"orange":"blue";}else{shape="blob";color="";}refresh();}
    /** Only call for avatarShape/avatarColor metadata already supplied by the account schema. */
    public void setAvatar(String shape,String color){if(designs==null)return;this.shape=designs.source.optJSONObject("shapes").has(shape)?shape:"blob";this.color=designs.colors.containsKey(color)?color:"";refresh();}
    /** Audio meters belong to WaveformView; unchanged state never schedules a redraw. */
    public void state(String value,int unusedAccent,float unusedLevel){String next=AvatarPolicy.state(value);if(next.equals(state))return;
        state=next;epoch=SystemClock.uptimeMillis();frozenTime=0;refresh();}
    public void running(boolean value){if(playing==value)return;if(!value)frozenTime=(SystemClock.uptimeMillis()-epoch)/1000.0;else epoch=SystemClock.uptimeMillis()-(long)(frozenTime*1000);playing=value;updateSchedule();}
    public void setReducedMotion(boolean value){reduced=value;updateSchedule();}
    boolean animationScheduled(){return scheduled;}
    private boolean canAnimate(){return face!=null&&(photo==null||state.equals("thinking"))&&BotAppearance.loops(state)&&!idleSettled()&&playing&&attached&&!reduced&&ValueAnimator.areAnimatorsEnabled()&&getWindowVisibility()==VISIBLE&&isShown()&&getGlobalVisibleRect(visibleRect);}
    private void updateSchedule(){if(frame!=null)removeCallbacks(frame);scheduled=false;invalidate();}
    private void refresh(){if(designs==null)return;try{face=designs.face(shape,state.equals("hearing")?"calm":AvatarPolicy.expression(state));motion=designs.motion.get(AvatarPolicy.clip(state));ink=designs.colors.containsKey(color)?designs.colors.get(color):0xffececec;if(color.equals("black"))ink=Color.parseColor(designs.source.getJSONObject("themeColors").getJSONObject("black").getString("dark"));
        setContentDescription("Bot face: "+state+(color.isEmpty()?". Placeholder avatar":"")+(name.isEmpty()?"":" for "+name)+(photo==null?"":". Photo avatar"));}catch(Exception invalid){face=null;setContentDescription("Bot face unavailable");}invalidate();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;if(photo==null&&photoDataUrl!=null){String retained=photoDataUrl;photoDataUrl=null;setPhotoDataUrl(retained);}updateSchedule();}
    @Override protected void onDetachedFromWindow(){attached=false;removeCallbacks(frame);scheduled=false;releasePhoto();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);updateSchedule();}
    @Override protected void onVisibilityChanged(View view,int visibility){super.onVisibilityChanged(view,visibility);if(frame!=null)updateSchedule();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(face==null)return;
        boolean moving=canAnimate(),reduce=reduced||!ValueAnimator.areAnimatorsEnabled();
        double time=reduce||idleSettled()?0:playing?(SystemClock.uptimeMillis()-epoch)/1000.0:frozenTime;
        float side=Math.min(getWidth(),getHeight()),cx=getWidth()/2f,cy=getHeight()/2f;
        boolean thinking=state.equals("thinking");
        float scale=side*.76f/100;
        canvas.save();canvas.translate(cx,cy);canvas.scale(scale,scale);canvas.translate(-50,-50);
        paint.setStyle(Paint.Style.FILL);paint.setColor(ink);paint.setAlpha(255);
        // Thinking is a complete replacement, including on the first frame and when paused.
        // Blending opaque black eyes with the shrinking body left a muddy half-face here.
        if(!thinking){
            canvas.save();float bodyScale=reduce||photo!=null?1:BotAppearance.breathe(state,time);
            canvas.scale(bodyScale,bodyScale,50,50);canvas.translate(0,reduce||photo!=null?0:BotAppearance.bob(state,time));
            if(photo!=null){float zoom=100f/Math.min(photo.getWidth(),photo.getHeight());photoMatrix.setScale(zoom,zoom);photoMatrix.postTranslate((100-photo.getWidth()*zoom)/2,(100-photo.getHeight()*zoom)/2);photoShader.setLocalMatrix(photoMatrix);paint.setShader(photoShader);canvas.drawCircle(50,50,50,paint);paint.setShader(null);}
            else{canvas.drawPath(face.body,paint);for(int i=0;i<face.painted.length;i++){paint.setColor(face.paints[i]);canvas.drawPath(face.painted[i],paint);}}
            float gaze=reduce?0:BotAppearance.gaze(state,time);double blink=reduce?0:BotAppearance.blink(state,time);
            paint.setColor(Color.BLACK);canvas.save();canvas.translate(gaze,0);
            for(int i=0;photo==null&&i<face.eyes.length;i++){canvas.save();RectF bounds=face.bounds[i];canvas.scale(1,(float)Math.max(.08,1-blink),bounds.centerX(),bounds.centerY());canvas.drawPath(face.eyes[i],paint);canvas.restore();}
            canvas.restore();canvas.restore();
        }
        else{for(int i=0;i<3;i++)canvas.drawCircle(24+i*26,50+(reduce?0:BotAppearance.dotY(time,i)),8,paint);}
        canvas.restore();paint.setAlpha(255);
        if(moving&&!scheduled){scheduled=true;postOnAnimationDelayed(frame,50);}
    }
}
