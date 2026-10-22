package si.zya.grokwear;

import java.util.Locale;

/** Watch state mapping; expression names are shipped desktop design tokens. */
final class AvatarPolicy {
    private static final java.util.Set<String> SHAPES=java.util.Set.of("blob","pebble","bean","egg","squircle","tablet","capsule","cylinder","hex","gem","crystal","wedge","shield","dome","arch","cloud","teardrop","leaf","square","sparkle","clover","flower","heart","house","star");
    private static final java.util.Set<String> COLORS=java.util.Set.of("black","brown","red","orange","yellow","green","cyan","blue","violet","magenta","gray");
    static String colorHex(String value){
        if(value!=null&&value.matches("#[0-9a-fA-F]{3}"))return "#"+value.charAt(1)+value.charAt(1)+value.charAt(2)+value.charAt(2)+value.charAt(3)+value.charAt(3);
        return value;
    }
    static boolean validShape(String shape){return shape!=null&&SHAPES.contains(shape);}
    static boolean validColor(String color){return color!=null&&COLORS.contains(color);}

    static String state(String raw){String s=raw==null?"idle":raw.toLowerCase(Locale.ROOT);
        if(s.equals("ready")||s.equals("welcome"))return "idle";
        if(s.equals("completed")||s.equals("done")||s.equals("happy"))return "happy";
        if(s.startsWith("reconnect")||s.equals("dropped")||s.equals("confused"))return "confused";
        if(s.equals("error")||s.equals("sad"))return "sad";
        if(s.equals("paused"))return "sleepy";
        if(s.equals("ending"))return "happy";
        return s;
    }
    static String expression(String state){switch(state){
        case "connecting":case "muted":case "sleepy":return "sleepy";
        case "listening":return "calm";
        case "thinking":return "inspecting";
        case "confused":return "confused";
        case "sad":return "sad";
        case "happy":return "happy";
        default:return "neutral";
    }}
    static boolean loops(String state){return state.equals("listening")||state.equals("thinking")||state.equals("speaking")||state.equals("confused");}
    static String clip(String state){switch(state){
        case "listening":return "Idle_D";
        case "thinking":return "Working_B";
        case "speaking":return "Streaming_A";
        case "confused":return "Blocked_A";
        case "sad":return "Blocked_B";
        case "happy":return "Done_A";
        default:return "Idle_B";
    }}
}
