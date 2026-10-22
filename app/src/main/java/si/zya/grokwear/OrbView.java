package si.zya.grokwear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.provider.Settings;
import android.view.View;

/** Shape identifies state even without motion or color. No background animation loop. */
public final class OrbView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval=new RectF();
    private String state="Listening";
    private int accent=0xff8db8ff;
    private float level;
    private boolean running=true,motionAllowed=true;
    public OrbView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    public void state(String state,int accent,float level){this.state=state;this.accent=accent;this.level=Math.max(0,Math.min(1,level));setContentDescription(state+" orb");invalidate();}
    public void running(boolean running){this.running=running;motionAllowed=Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;invalidate();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
        boolean animate=running&&isShown()&&getWindowVisibility()==VISIBLE&&motionAllowed;
        float phase=animate?(android.os.SystemClock.uptimeMillis()%2400)/2400f:0;
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)*.79f;
        boolean muted=state.equalsIgnoreCase("Muted")||state.equalsIgnoreCase("Paused");
        paint.setColor(muted?0xffb4b4ba:accent);paint.setStrokeWidth(Math.max(2,getResources().getDisplayMetrics().density*2));paint.setStyle(Paint.Style.STROKE);
        oval.set(cx-r,cy-r,cx+r,cy+r);
        if(state.equalsIgnoreCase("Thinking")||state.equalsIgnoreCase("Connecting")){
            for(int i=0;i<3;i++)canvas.drawArc(oval,phase*360+i*120,76,false,paint);
        }else if(state.equalsIgnoreCase("Speaking")){
            paint.setStyle(Paint.Style.FILL);float pulse=animate?(float)Math.sin(phase*Math.PI*2)*.04f:0;
            canvas.drawCircle(cx,cy,r*(.82f+level*.14f+pulse),paint);
            paint.setStyle(Paint.Style.STROKE);paint.setAlpha(65);canvas.drawCircle(cx,cy,r,paint);paint.setAlpha(255);
        }else{
            canvas.drawCircle(cx,cy,r,paint);
            if(muted)canvas.drawLine(cx-r*.65f,cy+r*.65f,cx+r*.65f,cy-r*.65f,paint);
            else if(level>.02f){paint.setAlpha(70);canvas.drawCircle(cx,cy,r*(.65f+level*.22f),paint);paint.setAlpha(255);}
        }
        if(animate&&(state.equalsIgnoreCase("Thinking")||state.equalsIgnoreCase("Connecting")||state.equalsIgnoreCase("Speaking")))postInvalidateDelayed(50);
    }
}
