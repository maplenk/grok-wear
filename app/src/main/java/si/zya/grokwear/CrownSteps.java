package si.zya.grokwear;

/**
 * Crown scroll counted in detents. InputFlinger reports AXIS_SCROLL as the wheel's detent
 * count times device.scalingFactor, and that factor defaults to 1, so one detent has
 * magnitude {@code DETENT}. A smaller turn is kept until later events make a step.
 * {@link #reset()} drops the remainder when the call ends, so the next call cannot inherit it.
 */
final class CrownSteps {
    static final float DETENT=1f;
    private float carried;
    int consume(float scroll){
        carried+=scroll;int steps=0;
        while(carried>=DETENT){carried-=DETENT;steps++;}
        while(carried<=-DETENT){carried+=DETENT;steps--;}
        return steps;
    }
    void reset(){carried=0f;}
}
