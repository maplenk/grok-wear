package si.zya.grokwear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Right-edge call volume. Hidden until a crown turn, then fades. Touches pass through. */
public final class CallVolumeArc extends View {
    private static final float STROKE_DP=4f,INSET_DP=6f;
    private static final long HOLD_MS=1500,FADE_MS=250;
    private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval=new RectF();
    private final Rect arcBounds=new Rect();
    private final Rect screenBounds=new Rect();
    private final int[] window=new int[2];
    private float fraction,barX,centerY,span;
    private final Runnable finishFade=() -> {setVisibility(GONE);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);};
    private final Runnable startFade=() -> {animate().alpha(0).setDuration(FADE_MS);postDelayed(finishFade,FADE_MS);};
    public CallVolumeArc(Context context){super(context);
        float stroke=STROKE_DP*getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);track.setStrokeCap(Paint.Cap.ROUND);track.setStrokeWidth(stroke);track.setColor(0x40FFFFFF);
        fill.setStyle(Paint.Style.STROKE);fill.setStrokeCap(Paint.Cap.ROUND);fill.setStrokeWidth(stroke);
        setClickable(false);setFocusable(false);setAlpha(0);setVisibility(GONE);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setAccent(int color){fill.setColor(color);}
    /** Shows the level at once and restarts the fade. N and M are the stream volume and its maximum. */
    void show(float level,int volume,int max){
        fraction=level<=0?0:level>=1?1:level;
        animate().cancel();removeCallbacks(startFade);removeCallbacks(finishFade);
        setAlpha(1);setVisibility(VISIBLE);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setStateDescription("Call volume "+volume+" of "+max);invalidate();
        postDelayed(startFade,HOLD_MS);
    }
    @Override protected void onDetachedFromWindow(){
        animate().cancel();removeCallbacks(startFade);removeCallbacks(finishFade);
        super.onDetachedFromWindow();
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);place();getLocationOnScreen(window);
        screenBounds.set(arcBounds.left+window[0],arcBounds.top+window[1],arcBounds.right+window[0],arcBounds.bottom+window[1]);
        info.setBoundsInScreen(screenBounds);
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);place();if(arcBounds.isEmpty())return;
        if(getResources().getConfiguration().isScreenRound()){
            canvas.drawArc(oval,CallVolume.trackStart(CallVolume.SWEEP),CallVolume.SWEEP,false,track);
            float drawn=CallVolume.fillSweep(CallVolume.SWEEP,fraction);
            if(drawn>0)canvas.drawArc(oval,CallVolume.fillStart(CallVolume.SWEEP,fraction),drawn,false,fill);
        }else{
            canvas.drawLine(barX,centerY-span,barX,centerY+span,track);
            if(fraction>0)canvas.drawLine(barX,centerY+span-span*2*fraction,barX,centerY+span,fill);
        }
    }
    private void place(){
        float d=getResources().getDisplayMetrics().density,stroke=STROKE_DP*d,inset=INSET_DP*d;
        float cx=getWidth()/2f;centerY=getHeight()/2f;float radius=Math.min(cx,centerY)-inset-stroke/2f;
        if(radius<=0||getWidth()==0){arcBounds.setEmpty();return;}
        double rad=Math.toRadians(CallVolume.SWEEP/2d);float rise=(float)(radius*Math.sin(rad));
        if(getResources().getConfiguration().isScreenRound()){
            oval.set(cx-radius,centerY-radius,cx+radius,centerY+radius);
            float inner=cx+(float)(radius*Math.cos(rad));
            arcBounds.set(Math.round(inner-stroke),Math.round(centerY-rise-stroke),Math.round(cx+radius+stroke/2f),Math.round(centerY+rise+stroke));
        }else{
            span=rise;barX=getWidth()-inset-stroke/2f;
            arcBounds.set(Math.round(barX-stroke),Math.round(centerY-span-stroke/2f),Math.round(barX+stroke),Math.round(centerY+span+stroke/2f));
        }
    }
}
