package si.zya.grokwear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Seven metered bars. No synthetic activity, timer, audio access or hidden animation loop. */
public final class WaveformView extends View {
    private static final float[] SHAPE={.38f,.72f,.48f,1,.6f,.85f,.42f};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float level;private boolean running=true;private String state="idle";
    public WaveformView(Context context){super(context);paint.setColor(0xfff2efe8);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void state(String state,float level){String next=state==null?"idle":state;boolean changed=!this.state.equals(next);this.state=next;setLevel(level);if(changed&&running&&isShown())invalidate();}
    public void setLevel(float value){float next=Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;if(next!=level){level=next;if(running&&isShown())invalidate();}}
    public void running(boolean value){if(running!=value){running=value;invalidate();}}
    public void setColor(int value){if(paint.getColor()!=value){paint.setColor(value);invalidate();}}
    @Override protected void onMeasure(int widthSpec,int heightSpec){float d=getResources().getDisplayMetrics().density;setMeasuredDimension(resolveSize(Math.round(36*d),widthSpec),resolveSize(Math.round(20*d),heightSpec));}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,w=3*d,gap=2.5f*d,total=7*w+6*gap,x=(getWidth()-total)/2,center=getHeight()/2f;
        float amplitude=running&&(state.equals("listening")||state.equals("hearing")||state.equals("speaking"))?(float)Math.sqrt(level):0;
        // A fixed vertical resting bar remains distinguishable from a loading dot. Height
        // changes only with the real meter; the curve keeps quiet speech and output visible.
        float available=Math.max(0,getHeight()-2*d),rest=Math.min(available,6*d);
        for(int i=0;i<7;i++){float h=rest+(available-rest)*amplitude*SHAPE[i];canvas.drawRoundRect(x,center-h/2,x+w,center+h/2,w/2,w/2,paint);x+=w+gap;}
    }
}
