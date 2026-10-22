package si.zya.grokwear;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pre-parsed original clip keyframes; sampling does not allocate. Times are seconds. */
final class AvatarMotion {
    static final class Track {
        final double[] times;final double[][] values,ease;
        Track(JSONArray frames)throws Exception {
            int count=frames==null?0:frames.length();times=new double[count];values=new double[count][];ease=new double[count][];
            for(int i=0;i<count;i++){JSONObject frame=frames.getJSONObject(i);times[i]=frame.getDouble("t");
                JSONArray v=frame.optJSONArray("v");values[i]=new double[v==null?0:v.length()];
                for(int j=0;j<values[i].length;j++)values[i][j]=v.getDouble(j);
                JSONArray e=frame.optJSONArray("ease");ease[i]=new double[]{.42,0,.58,1};
                if(e!=null&&e.length()==4)for(int j=0;j<4;j++)ease[i][j]=e.getDouble(j);
            }
        }
        double at(double time,int axis,double fallback){
            if(times.length==0)return fallback;int start=0;
            if(time<=times[0])return axis<values[0].length?values[0][axis]:fallback;
            while(start<times.length-1&&times[start+1]<=time)start++;
            if(axis>=values[start].length)return fallback;
            if(start==times.length-1)return values[start][axis];
            double amount=bezier((time-times[start])/(times[start+1]-times[start]),ease[start]);
            double target=axis<values[start+1].length?values[start+1][axis]:fallback;
            return values[start][axis]+(target-values[start][axis])*amount;
        }
    }
    final double duration;final Track position,rotation,squash,scale,lids;final double[] blinks;
    AvatarMotion(JSONObject clip)throws Exception {
        duration=clip.getDouble("duration");JSONObject tracks=clip.getJSONObject("tracks");
        position=new Track(tracks.optJSONArray("position"));rotation=new Track(tracks.optJSONArray("rotation"));
        squash=new Track(tracks.optJSONArray("squash"));scale=new Track(tracks.optJSONArray("scale"));lids=new Track(tracks.optJSONArray("lids"));
        JSONArray expression=tracks.optJSONArray("expression");int count=0;
        if(expression!=null)for(int i=0;i<expression.length();i++)if("blink".equals(expression.getJSONObject(i).optString("v")))count++;
        blinks=new double[count];int n=0;if(expression!=null)for(int i=0;i<expression.length();i++){JSONObject e=expression.getJSONObject(i);if("blink".equals(e.optString("v")))blinks[n++]=e.getDouble("t");}
    }
    double blink(double time){double result=lids.at(time,0,0);for(double start:blinks){double elapsed=time-start;if(elapsed>=0&&elapsed<=.26){double part=elapsed/.26;result=Math.max(result,part<=.45?part/.45:(1-part)/.55);}}return Math.max(0,Math.min(1,result));}
    static double bezier(double amount,double[] e){double t=Math.max(0,Math.min(1,amount));for(int i=0;i<8;i++){double delta=cubic(t,e[0],e[2])-amount;double derivative=3*(1-t)*(1-t)*e[0]+6*(1-t)*t*(e[2]-e[0])+3*t*t*(1-e[2]);if(Math.abs(delta)<1e-6||Math.abs(derivative)<1e-6)break;t=Math.max(0,Math.min(1,t-delta/derivative));}return cubic(t,e[1],e[3]);}
    private static double cubic(double t,double a,double b){double s=1-t;return 3*s*s*t*a+3*s*t*t*b+t*t*t;}
}
