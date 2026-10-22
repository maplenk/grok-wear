package si.zya.grokwear;

/** Motion sampled from the approved Wear prototype; original outlines remain in bot-avatar.json. */
final class BotAppearance {
    private static final double[] BLINKS={1.48,3.46};
    private BotAppearance(){}
    static boolean loops(String s){return s.equals("idle")||s.equals("listening")||s.equals("hearing")||s.equals("speaking")||s.equals("thinking");}
    static float morph(double seconds){return (float)Math.max(0,Math.min(1,seconds/.28));}
    static float breathe(String state,double t){return state.equals("idle")?1+.02f*(1-(float)Math.cos(t*Math.PI/2)):1;}
    static float gaze(String state,double t){return (float)Math.sin(t*2*Math.PI/((state.equals("listening")||state.equals("hearing"))?3:4))*((state.equals("listening")||state.equals("hearing"))?6:state.equals("idle")?3:0);}
    static float bob(String state,double t){return state.equals("speaking")?(float)(-3*(1-Math.cos(t*2*Math.PI/.62))):0;}
    static double blink(String state,double t){if(!state.equals("idle"))return 0;double phase=t%4;for(double start:BLINKS){double p=(phase-start)/.26;if(p>=0&&p<=1)return Math.sin(p*Math.PI)*.92;}return 0;}
    static float dotY(double t,int dot){double phase=((t-dot*.16)%1.4+1.4)%1.4/1.4;return phase>.6?0:(float)(-12*Math.sin(phase/.6*Math.PI));}
}
