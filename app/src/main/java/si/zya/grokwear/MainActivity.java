package si.zya.grokwear;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native round-watch UI. Debug preview is isolated from account, network and voice code. */
public final class MainActivity extends Activity {
    private static final int WHITE=0xffffffff,SECONDARY=0xff8d8d93,SURFACE=0xff1e1e1e,BLUE=0xff006ceb,GLASS=0xff2c2c2e,CARD=0xff202020,RED=0xffe5484d,GREEN=0xff30d158,CARD_ROW=0xff1c1c1e;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService preferencesWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService rosterWorker=Executors.newSingleThreadExecutor();
    private boolean preview,previewMedia,visible,polling,simulated,scrollToEnd;
    private int epoch,previewEpoch,botRequest,navigation,activeRosterRequest;
    private boolean botsLoading,botSelectionPending,botsCached,rosterFetchActive;
    private long botsFetchedAtMs,lastBotRefreshAttemptElapsed;
    private static final long BOT_REFRESH_INTERVAL_MS=60000;
    private boolean rosterRetryUsed;
    private Runnable rosterRetry;
    private String settingsReturnScreen="bots";
    private static final java.util.concurrent.atomic.AtomicBoolean SIGN_OUT_ACTIVE=new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean signOutFailed;
    private final Runnable signOutObserver=new Runnable(){public void run(){if(!visible)return;if(SIGN_OUT_ACTIVE.get()){main.postDelayed(this,100);return;}finishSignOut();}};
    private String pendingTalkIdentity="";
    private long pendingTalkAt,pendingTalkGrantedAt;
    private int pendingTalkNavigation;
    private boolean awaitingMicrophonePermission,pendingTalkGranted;
    private final Runnable finishGrantedTalk=()->resumeGrantedTalk();
    private String botListError="",botsAccountBinding="";
    private int historyRequest,historyPages,pendingHistory;
    private boolean historyLoading,historyStale,historyHasMore;
    private String historyError="",historyAgent="",historyAccount="";
    private Long historyBefore;
    private JSONObject historyBinding,historyDetail;
    private JSONArray historyEntries=new JSONArray();
    private android.window.OnBackInvokedCallback modernBack;
    private String screen="welcome",botName="Main Bot",caption="",speaker="",safeError="";
    private boolean previewMuted,previewSaving;private long previewElapsedMs=42000,previewCallStartMs;
    /** Media captures count up from Listening like a real call; other previews show a fixed time. */
    private long previewElapsed(){return !previewMedia?previewElapsedMs:previewCallStartMs>0?android.os.SystemClock.elapsedRealtime()-previewCallStartMs:0;}
    private BotAccount account;
    // Pinned Bots: one write at a time, and Home always shows the list the account stored.
    private String pinSavingId="",pinErrorId="",justPinnedId="",pinFadeId="";private boolean pinSavingTarget,pinGridExpanded;private int pinRequest,pinsFullAt=-1,homeScrollY;private long lastPinsReadElapsed;private JSONObject actionsBot,readerEntry;private int readerReturnY;
    // Text messages: the draft under review, this Bot's messages from the watch, and the reply watch after sending.
    private String draftMessage="",editDraft="",markedReadId="";private JSONArray botMessages=new JSONArray();private boolean messageSending;private int replyChecks;private long replyWatchSince;private String replyWatchText="";private Runnable replyWatch;
    private static final int MESSAGE_INPUT=31;
    private FrameLayout root;
    private LinearLayout body;
    private BotAvatarView orb;
    private final java.util.ArrayList<BotAvatarView> avatars=new java.util.ArrayList<>();
    private ScrollView currentScroll;
    private TextView callBot,callCaption,callHint;
    private Button callMute,qrCancel;
    private WaveformView waveform;private TextView callStatus;private FrameLayout callStage;private boolean callCompact;private long previewCallAt;
    private String captionLine="",shownCaption,renderedScreen="";private int shownMuteColor;private float shownStatusSize;private boolean bottomAction;
    private final java.util.ArrayList<String> transcript=new java.util.ArrayList<>();private String transcriptLast="";
    private final Runnable callClock=new Runnable(){public void run(){if(visible&&isCall()){updateCallText();main.postDelayed(this,1000);}}};
    private String loginAttempt="";
    private int side;
    private JSONArray bots=new JSONArray();
    private String receiptIdentity="",receiptSync="";private long receiptElapsed;private boolean receiptRefreshPending;private JSONObject avatarTheme;
    private JSONObject selectedMetadata=new JSONObject();private JSONObject botPreferences=new JSONObject();private LinearLayout transcriptBody;private boolean micTicked;
    private final Runnable voiceListener=()->{
        if(!visible||preview)return;VoiceService.Snapshot status=VoiceService.snapshot();
        if(!status.active&&!receiptIdentity.isEmpty()){receiptElapsed=status.elapsedMs;receiptSync=status.syncStatus;}
        if(status.active||isCall()||screen.equals("done")||screen.equals("error")||screen.equals("dropped"))renderVoice();
        else if(screen.equals("ready"))render(); // A save completion must not navigate History, Settings or the picker.
        if(takeOwnHistoryRefresh(status.active,historyStaging()))loadBotChat();
    };
    private final Runnable pollTick=()->pollLogin();
    @Override public void onCreate(Bundle state){super.onCreate(state);
        preview=BuildConfig.DEBUG&&getIntent().getBooleanExtra("ui_preview",false);
        // Launch-media captures: no preview label, a short demo chat per Bot. Synthetic data only.
        previewMedia=preview&&getIntent().getBooleanExtra("preview_media",false);
        // Real launches show a blank frame until the saved sign-in is read, never a flash of "Sign in".
        if(!preview){botName="Your Bot";screen="launch";getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);account=new BotAccount(this);}
        side=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
        if(android.os.Build.VERSION.SDK_INT>=33){modernBack=this::handleBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,modernBack);}
        if(preview)setPreviewScreen(getIntent().getStringExtra("preview_screen"));else render();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);
        // An existing real-account activity cannot be switched into screenshot mode.
        if(preview){previewMedia=intent.getBooleanExtra("preview_media",false);setPreviewScreen(intent.getStringExtra("preview_screen"));}
    }
    @Override protected void onResume(){super.onResume();visible=true;for(BotAvatarView face:avatars)face.running(true);if(waveform!=null)waveform.running(true);if(isCall()){main.removeCallbacks(callClock);main.post(callClock);}
        if(!preview){VoiceService.addListener(voiceListener);if(SIGN_OUT_ACTIVE.get()){screen="signout_confirm";render();main.removeCallbacks(signOutObserver);main.post(signOutObserver);return;}if(awaitingMicrophonePermission&&!pendingTalkCurrent())clearPendingTalk();rosterRetryUsed=false;if(VoiceService.snapshot().active||isCall())renderVoice();else if(screen.equals("done")||screen.equals("dropped")||screen.equals("error"))render();else if(!awaitingMicrophonePermission&&!pendingTalkGranted)loadAccount();}
    }
    @Override protected void onPause(){cancelRosterRetry();if(pendingTalkGranted)clearPendingTalk();visible=false;epoch++;historyRequest++;historyLoading=false;polling=false;main.removeCallbacks(pollTick);for(BotAvatarView face:avatars)face.running(false);if(waveform!=null)waveform.running(false);main.removeCallbacks(callClock);
        if(!preview)VoiceService.removeListener(voiceListener);super.onPause();}
    @Override protected void onStop(){if(!awaitingMicrophonePermission)clearPendingTalk();super.onStop();}
    @Override protected void onDestroy(){if(android.os.Build.VERSION.SDK_INT>=33&&modernBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(modernBack);previewEpoch++;worker.shutdownNow();preferencesWorker.shutdownNow();rosterWorker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
    // API30–32 fallback; API33+ uses the platform gesture dispatcher above.
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){if(android.os.Build.VERSION.SDK_INT<33)handleBack();}
    public void handleBack(){
        if(!preview&&SIGN_OUT_ACTIVE.get())return;
        navigation++;clearPendingTalk();
        if(screen.equals("signout_confirm")){screen="settings";render();return;}
        if(screen.equals("settings")){leaveSettings();return;}
        if(screen.equals("history_detail")){screen="history";render();return;}
        if(screen.equals("bot_actions")){closeBotActions();return;}
        if(screen.equals("reader")){closeReader();return;}
        if(screen.equals("compose_edit")){screen=draftMessage.isEmpty()?"ready":"compose_review";render();return;}
        if(screen.equals("compose_review")){if(!messageSending){draftMessage="";screen="ready";render();}return;}
        if(preview){previewEpoch++;simulated=false;if(isCall()){screen="ready";render();}else if(screen.equals("ready")){screen="bots";render();}else if(!screen.equals("welcome")){screen="ready";render();}else finish();return;}
        if(screen.equals("qr")){cancelLogin();return;}
        if(VoiceService.snapshot().active){moveTaskToBack(true);return;}
        if(screen.equals("ready")){openBotPicker();}else if(!screen.equals("bots")&&!screen.equals("welcome")){goHome();}else finish();
    }
    public void setPreviewScreen(String value){if(!preview||!BuildConfig.DEBUG)return;
        previewEpoch++;simulated=false;previewSaving=false;caption="";speaker="";safeError="";
        screen=value==null?"welcome":value;if(screen.equals("settings")||screen.equals("signout_confirm"))settingsReturnScreen="bots";previewElapsedMs=screen.equals("last_minute")?240000:42000;if(screen.equals("last_minute"))screen="listening";if(screen.equals("done_saving")){previewSaving=true;screen="done";}if(screen.equals("completed"))screen="done";if(screen.equals("chats"))screen="recent";previewMuted=screen.equals("muted");
        if(screen.equals("speaking")){speaker=botName;caption="Your next meeting is at ten. You have time for a short walk.";}
        if(screen.equals("thinking")){speaker="You";caption="What is next on my calendar?";}
        if(screen.equals("home_primary")){try{bots=new JSONArray().put(new JSONObject().put("id","preview-primary").put("name","Main Bot").put("lastMessagePreview","Ready when you are"));botPreferences=new JSONObject().put("mainAgentId","preview-primary");}catch(Exception ignored){}screen="bots";}else if(screen.equals("bots")){botPreferences=new JSONObject();}
        if(java.util.Set.of("home_pinned","home_overflow","bot_actions","pin_error","pins_full","bot_pinned").contains(screen))previewPins(screen);
        if(screen.equals("compose_review")){draftMessage=previewMedia?previewDraft("Travel Bot"):"Use three sentences to introduce yourself";botName=previewMedia?"Travel Bot":"Wear test";}
        if(screen.equals("reader")){try{readerEntry=new JSONObject().put("role","assistant").put("atMs",System.currentTimeMillis()).put("text","**Alert:** Taipei to Tokyo one-way. The cheapest this month is **Oct 10 (Sat) NT$3,430**, NT$210 below last time (about **5.8%**), and your lowest on record.\n\nThe cheapest day moved to **Oct 10**; only 2 seats left at this fare. Your Oct 27 pick is now NT$3,980.");botName="Travel Bot";}catch(Exception ignored){}}
        if(screen.equals("ready_long")){clearHistory();try{historyEntries.put(new JSONObject().put("id","long-preview").put("role","assistant").put("text","**Research Bot** has an update.\nThe first task is ready for review.\nThe second task is waiting for input.\nThe third task has a draft.\nThe fourth task is ready tomorrow.\nThe fifth task has a short note.\nThe sixth task is still working.\nThe seventh task is waiting.\nThe eighth task is complete.\nThe ninth task needs review.\nThe tenth task has a summary.\nThe final task is done."));}catch(Exception ignored){}screen="ready";}
        if(screen.startsWith("history"))previewHistory(screen);
        render();
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void render(){
        // Re-rendering the same screen (history refresh, sync status) keeps the reader's place.
        final int keepY=currentScroll!=null&&screen.equals(renderedScreen)&&!isCall()?currentScroll.getScrollY():0;renderedScreen=screen;bottomAction=false;
        for(BotAvatarView face:avatars)face.running(false);avatars.clear();orb=null;if(waveform!=null)waveform.running(false);waveform=null;transcriptBody=null;callStatus=null;main.removeCallbacks(callClock);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
        TextView top=text(preview&&!previewMedia?"UI preview":"",10,SECONDARY);top.setLetterSpacing(.08f);
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,dp(18),Gravity.TOP|Gravity.CENTER_HORIZONTAL);tp.topMargin=dp(9);root.addView(top,tp);if(!preview||screen.equals("ready")||screen.equals("done")||isCall())top.setVisibility(View.GONE);
        ScrollView scroll=new ScrollView(this);currentScroll=scroll;scroll.setFocusable(true);scroll.setFocusableInTouchMode(true);scroll.requestFocus();scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,-1);sp.topMargin=dp(isCall()?(preview?27:19):23);sp.bottomMargin=dp(isCall()?side*.12f+50:screen.equals("bots")?9:screen.equals("done")?12:30);root.addView(scroll,sp);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setGravity(Gravity.CENTER_HORIZONTAL);body.setPadding(dp(side*.12f),dp(isCall()||screen.equals("ready")?0:3),dp(side*.12f),dp(isCall()?0:screen.equals("bots")||screen.equals("done")?12:58));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        if(isCall()){call();return;}
        // After sending, and when the Bot's reply lands, show the newest message rather than the top of the page.
        if(scrollToEnd&&screen.equals("ready")){scrollToEnd=false;final ScrollView end=scroll;end.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            public boolean onPreDraw(){end.getViewTreeObserver().removeOnPreDrawListener(this);end.scrollTo(0,Math.max(0,end.getChildAt(0).getHeight()-end.getHeight()));return true;}});}
        else if(keepY>0){final ScrollView restored=scroll;restored.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            public boolean onPreDraw(){restored.getViewTreeObserver().removeOnPreDrawListener(this);restored.scrollTo(0,keepY);return true;}});}
        TextView more=text("↓ More",12,SECONDARY);more.setContentDescription("Scroll for more options");more.setBackground(shape(Color.BLACK,12));more.setVisibility(View.GONE);
        FrameLayout.LayoutParams moreLayout=new FrameLayout.LayoutParams(dp(80),dp(18),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);moreLayout.bottomMargin=dp(9);root.addView(more,moreLayout);
        more.setOnClickListener(v->currentScroll.smoothScrollBy(0,dp(side*.5f)));
        scroll.getViewTreeObserver().addOnScrollChangedListener(()->more.setVisibility(!screen.equals("ready")&&!screen.equals("done")&&!screen.equals("bots")&&!screen.equals("settings")&&!screen.equals("signout_confirm")&&!isHistory()&&!bottomAction&&scroll.canScrollVertically(1)?View.VISIBLE:View.GONE));
        more.post(()->more.setVisibility(!screen.equals("ready")&&!screen.equals("done")&&!screen.equals("bots")&&!screen.equals("settings")&&!screen.equals("signout_confirm")&&!isHistory()&&!bottomAction&&scroll.canScrollVertically(1)?View.VISIBLE:View.GONE));
        switch(screen){
            case "welcome":welcome();break;
            case "qr":qr();break;
            case "bots":botList();break;
            case "bot_actions":if(actionsBot==null){screen="bots";botList();}else botActions();break;
            case "reader":reader();break;
            case "compose_review":composeReview();break;
            case "compose_edit":composeEdit();break;
            case "ready":ready();break;
            case "recent":recent();break;
            case "history":history();break;
            case "history_detail":historyDetail();break;
            case "done":ready();break;
            case "launch":break;
            case "dropped":message("Call dropped",safeError.isEmpty()?"The call ended unexpectedly.":safeError,"Talk again",()->talkAgain());break;
            case "attention":task();break;
            case "account_error":message("Needs attention",safeError,"Try again",()->recover());break;
            case "settings":settings();break;
            case "signout_confirm":confirmSignOut();break;
            case "approval":approval();break;
            case "task":task();break;
            case "expired":message("Sign-in expired","Get a new code to connect your account.","New code",()->beginLogin());break;
            case "offline":message("You’re offline","Check Wi-Fi or your watch’s mobile connection.","Try again",()->talkAgain());break;
            case "error":message("Voice chat failed",safeError.isEmpty()?"The call ended unexpectedly.":safeError,"Try again",()->talkAgain());break;
            default:screen="ready";ready();break;
        }
    }
    private TextView text(String value,float size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER);v.setIncludeFontPadding(false);v.setPadding(0,0,0,0);
        // Whole words wrap; hyphenated fragments are hard to read on a small round screen.
        v.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);return v;}
    private TextView label(String value,float size,int color){TextView v=text(value,size,color);body.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private void gap(int amount){View v=new View(this);body.addView(v,new LinearLayout.LayoutParams(1,dp(amount)));}
    private void title(String value){TextView v=label(value,20,WHITE);v.setTypeface(Typeface.create(Typeface.DEFAULT,600,false),Typeface.NORMAL);gap(7);}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setTextSize(getResources().getConfiguration().fontScale>1.2f?12:14);b.setTextColor(WHITE);b.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));b.setAllCaps(false);b.setIncludeFontPadding(false);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(8),dp(6),dp(8),dp(6));b.setBackground(shape(GLASS,24));b.setContentDescription(value);b.setOnClickListener(v->{navigation++;action.run();});return b;}
    private Button action(String value,Runnable action){Button b=button(value,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(7);body.addView(b,p);return b;}
    private void welcome(){boolean large=getResources().getConfiguration().fontScale>1.2f;
        TextView welcomeTitle=label("Grok Wear",large?14:16,WHITE);welcomeTitle.setTypeface(Typeface.create(Typeface.DEFAULT,600,false),Typeface.NORMAL);gap(4);
        label("Talk to your Bots.",12,SECONDARY);gap(4);
        Button connect=action("Sign in",()->beginLogin());((LinearLayout.LayoutParams)connect.getLayoutParams()).topMargin=0;connect.setTextColor(Color.BLACK);connect.setBackground(shape(WHITE,24));
        label(preview?"Simulated · no microphone":"Your own Grok account",12,SECONDARY);gap(12);label("Unofficial Grok client",12,SECONDARY);
    }
    private void qr(){TextView context=label(preview?"Demo QR":"Connect Grok",13,WHITE);context.setTypeface(Typeface.create(Typeface.DEFAULT,600,false),Typeface.NORMAL);gap(3);
        ImageView qr=new ImageView(this);qr.setContentDescription(preview?"Demo QR code":"Grok sign-in QR code");
        if(preview)drawQr(qr,"https://example.invalid/ui-preview");else loadQr(qr);
        int size=dp(Math.min(94,side*.49f));body.addView(qr,new LinearLayout.LayoutParams(size,size));gap(3);
        label(preview?"Demo only · no sign-in":"Scan with your phone",12,SECONDARY);
        action("New code",()->beginLogin());qrCancel=action("Cancel",()->cancelLogin());qrCancel.setEnabled(preview||!loginAttempt.isEmpty());
    }
    private void drawQr(ImageView image,String data){try{BitMatrix bits=new MultiFormatWriter().encode(data,BarcodeFormat.QR_CODE,320,320);Bitmap bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);for(int y=0;y<320;y++)for(int x=0;x<320;x++)bitmap.setPixel(x,y,bits.get(x,y)?Color.BLACK:Color.WHITE);image.setImageBitmap(bitmap);}catch(Exception ignored){image.setContentDescription("QR code unavailable");}}
    private void loadQr(ImageView image){task(()->account.pendingLogin(),value->{String url=value.optString("login_url","");loginAttempt=value.optString("login_attempt","");if(qrCancel!=null)qrCancel.setEnabled(!loginAttempt.isEmpty());if(!url.isEmpty())drawQr(image,url);else{screen="expired";render();}});}
    private java.util.List<JSONObject> orderedBots(){java.util.ArrayList<JSONObject> result=new java.util.ArrayList<>();java.util.HashSet<String> added=new java.util.HashSet<>();JSONArray pins=botPreferences.optJSONArray("pinnedAgentIds");if(pins!=null)for(int p=0;p<pins.length();p++)for(int i=0;i<bots.length();i++){JSONObject b=bots.optJSONObject(i);if(b!=null&&pins.optString(p).equals(b.optString("id"))&&added.add(b.optString("id")))result.add(b);}for(int i=0;i<bots.length();i++){JSONObject b=bots.optJSONObject(i);if(b!=null&&added.add(b.optString("id")))result.add(b);}return result;}
    private JSONObject primaryBot(){String id=botPreferences.optString("mainAgentId","");if(id.isEmpty())return null;for(int i=0;i<bots.length();i++){JSONObject b=bots.optJSONObject(i);if(b!=null&&id.equals(b.optString("id")))return b;}return null;}
    private boolean compactHero(){return side<200;}
    private JSONObject previewBot(String name,String message,boolean unread){try{return new JSONObject().put("id","preview-"+name.toLowerCase(java.util.Locale.ROOT).replace(' ','-')).put("name",name).put("lastMessagePreview",message).put("hasUnread",unread).put("isRunning",!unread);}catch(Exception impossible){return new JSONObject();}}
    private void styleTalk(Button talk){styleTalk(talk,"Talk");}
    private void styleTalk(Button talk,String label){talk.setText(label);talk.setTextSize(label.equals("Talk")?15:12);talk.setCompoundDrawablesWithIntrinsicBounds(new VoiceGlyph(),null,null,null);talk.setCompoundDrawablePadding(dp(6));talk.setSingleLine(true);talk.setEllipsize(android.text.TextUtils.TruncateAt.END);int inset=Math.max(dp(8),(dp(side*.62f)-dp(26)-dp(8)-(int)Math.ceil(talk.getPaint().measureText(label)))/2);talk.setPadding(inset,dp(6),inset,dp(6));}
    private void styleHomeTalk(Button talk,String name){
        talk.setText("Talk to\n"+name);talk.setTextSize(12);talk.setSingleLine(false);talk.setMaxLines(2);talk.setEllipsize(android.text.TextUtils.TruncateAt.END);talk.setPadding(dp(8),dp(4),dp(8),dp(4));
        talk.setCompoundDrawables(null,null,null,null);talk.setContentDescription("Talk to "+name);
    }
    private void addBotScrollIndicator(){
        View cue=new View(this){final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas){
                if(currentScroll==null)return;float travel=Math.max(1,currentScroll.getChildAt(0).getHeight()-currentScroll.getHeight());
                float progress=Math.min(1,Math.max(0,currentScroll.getScrollY()/travel));p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeWidth(getWidth());
                p.setColor(GLASS);canvas.drawLine(getWidth()/2f,getWidth()/2f,getWidth()/2f,getHeight()-getWidth()/2f,p);
                float length=getHeight()*.35f,y=getWidth()/2f+progress*(getHeight()-length-getWidth());p.setColor(SECONDARY);canvas.drawLine(getWidth()/2f,y,getWidth()/2f,y+length,p);
            }};
        cue.setContentDescription("Scroll for more Bots");FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(3),dp(36),Gravity.RIGHT|Gravity.CENTER_VERTICAL);cp.rightMargin=dp(9);root.addView(cue,cp);
        final ScrollView scroll=currentScroll;Runnable update=()->{cue.setVisibility(scroll.canScrollVertically(1)||scroll.canScrollVertically(-1)?View.VISIBLE:View.GONE);cue.invalidate();};
        scroll.getViewTreeObserver().addOnScrollChangedListener(update::run);cue.post(update);
    }
    private void primaryAction(JSONObject bot,boolean start){if(preview){choosePreview(bot.optString("name"));if(start)talk();}else chooseBot(bot.optString("id"),start);}
    private void botList(){
        JSONObject primary=primaryBot();java.util.List<JSONObject> pinned=pinnedBots(primary);
        if(!pinned.isEmpty())markPinHintDone();
        if(primary!=null){FrameLayout.LayoutParams scroll=(FrameLayout.LayoutParams)currentScroll.getLayoutParams();scroll.topMargin=0;scroll.bottomMargin=dp(67);currentScroll.setLayoutParams(scroll);body.setPadding(0,0,0,dp(24));FrameLayout hero=new FrameLayout(this);body.addView(hero,new LinearLayout.LayoutParams(-1,dp(side-67)));
            // With no pins yet, the hero moves up to make room for one hint line.
            boolean hint=pinned.isEmpty()&&!pinHintDone()&&(preview||botsCached);int lift=hint?18:0;
            BotAvatarView face=new BotAvatarView(this);applyAvatar(face,primary.optString("name"),primary);face.state("ready",WHITE,0);face.running(visible);avatars.add(face);face.setContentDescription(primary.optString("name")+", Primary Bot. Open chat");face.setOnClickListener(v->{navigation++;primaryAction(primary,false);});int faceSize=compactHero()?48:64;FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(faceSize),dp(faceSize),Gravity.TOP|Gravity.CENTER_HORIZONTAL);fp.topMargin=dp((compactHero()?30:side*.34f-32)-lift);hero.addView(face,fp);
            TextView star=text("★",16,WHITE);GradientDrawable badge=shape(0xffff9800,12);badge.setStroke(dp(2),Color.BLACK);star.setBackground(badge);star.setContentDescription("Primary Bot");FrameLayout.LayoutParams st=new FrameLayout.LayoutParams(dp(23),dp(23),Gravity.TOP|Gravity.CENTER_HORIZONTAL);st.leftMargin=dp(22);st.topMargin=fp.topMargin+dp(faceSize-21);hero.addView(star,st);
            TextView name=text(primary.optString("name"),getResources().getConfiguration().fontScale>1.2f?12:16,WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(dp(side*.76f),dp(22),Gravity.TOP|Gravity.CENTER_HORIZONTAL);np.topMargin=dp((compactHero()?80:side*.535f-11)-lift);hero.addView(name,np);TextView sub=text(MessageText.plain(primary.optString("lastMessagePreview","Ready to talk")),compactHero()?11:12,SECONDARY);sub.setTag("bot_excerpt");sub.setSingleLine(true);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);FrameLayout.LayoutParams xp=new FrameLayout.LayoutParams(dp(side*.76f),dp(20),Gravity.TOP|Gravity.CENTER_HORIZONTAL);xp.topMargin=dp(Math.min(side-88,side*.615f-10)-lift);hero.addView(sub,xp);
            if(hint){TextView line=text("Hold a Bot to pin it",12,SECONDARY);line.setCompoundDrawablesRelativeWithIntrinsicBounds(new PinGlyph(SECONDARY,12,false,false),null,null,null);line.setCompoundDrawablePadding(dp(4));FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-2,dp(18),Gravity.TOP|Gravity.CENTER_HORIZONTAL);hp.topMargin=xp.topMargin+dp(22);hero.addView(line,hp);}
            Button talk=button("Talk to "+primary.optString("name","your Primary Bot"),()->primaryAction(primary,true));styleHomeTalk(talk,primary.optString("name","your Primary Bot"));talk.setTag("home_primary_talk");talk.setTextColor(Color.BLACK);talk.setBackground(shape(WHITE,28));talk.setEnabled(!historyStaging()&&!botSelectionPending);FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(side*.62f),dp(54),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);ep.bottomMargin=dp(12);root.addView(talk,ep);
            View fade=new View(this);fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xff000000}));FrameLayout.LayoutParams fl=new FrameLayout.LayoutParams(-1,dp(18),Gravity.BOTTOM);fl.bottomMargin=dp(67);root.addView(fade,fl);
            // The fade appears once the list scrolls, so it never dims the hero at rest.
            final ScrollView home=currentScroll;fade.setAlpha(0);home.getViewTreeObserver().addOnScrollChangedListener(()->fade.setAlpha(Math.min(1f,home.getScrollY()/(float)dp(24))));
            LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(side*.12f),dp(10),dp(side*.12f),0);body.addView(list,new LinearLayout.LayoutParams(-1,-2));body=list;
            if(!pinned.isEmpty())pinnedGrid(pinned);
            // Every other Bot pinned: no empty "Bots" header.
            if(pinned.isEmpty()||bots.length()-1>pinned.size())label("Bots",17,WHITE);
        }else{TextView title=label("Bots",17,WHITE);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));gap(8);if(!pinned.isEmpty())pinnedGrid(pinned);}
        addBotScrollIndicator();
        if(preview&&bots.length()<2){if(primary==null)botChip("Main Bot",BLUE,()->choosePreview("Main Bot"),previewBot("Main Bot","Latest reply from your Bot",true));botChip("Research Bot",BLUE,()->choosePreview("Research Bot"),previewBot("Research Bot","Your task update is ready",false));action("Settings",()->openSettings());return;}
        if(botsLoading&&!botsCached)label("Loading Bots…",12,SECONDARY);if(botSelectionPending)label("Switching Bot…",12,SECONDARY);
        if(bots.length()==0&&!botsCached&&!selectedMetadata.optString("id","").isEmpty())botChip(botName,BLUE,()->chooseBot(selectedMetadata.optString("id")),selectedMetadata);
        else{java.util.HashSet<String> shown=new java.util.HashSet<>();for(JSONObject bot:pinned)shown.add(bot.optString("id"));
            for(JSONObject bot:orderedBots()){if(primary!=null&&primary.optString("id").equals(bot.optString("id")))continue;if(shown.contains(bot.optString("id")))continue;String name=bot.optString("name","Unnamed Bot");botChip(name,BLUE,()->primaryAction(bot,false),bot);}}
        if(!botListError.isEmpty())label(botListError,12,SECONDARY);if(botsCached||!botsLoading){if(bots.length()==0&&botListError.isEmpty())label("No Bots found",14,SECONDARY);action(botsCached?"Refresh":"Try again",()->loadBots());}action("Settings",()->openSettings());
    }
    private void botChip(String name,int accent,Runnable action){botChip(name,accent,action,null);}
    private void botChip(String name,int accent,Runnable action,JSONObject metadata){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),0,dp(10),0);row.setBackground(shape(CARD_ROW,25));row.setClickable(true);row.setFocusable(true);row.setContentDescription(name);row.setOnClickListener(v->{navigation++;action.run();});row.setEnabled(!botSelectionPending);
        boolean selected=preview?name.equals(botName):metadata!=null&&!selectedMetadata.optString("id","").isEmpty()&&metadata.optString("id","").equals(selectedMetadata.optString("id"));
        row.setSelected(selected);if(selected)row.setStateDescription("Selected");
        boolean working=working(metadata);int unread=metadata==null?0:Math.max(0,metadata.optInt("unreadCount",0));boolean hasUnread=metadata!=null&&(unread>0||metadata.optBoolean("hasUnread"));
        // Avatar with a green dot while the Bot is working, like the live status in Grok Bot's watch views.
        FrameLayout avatar=new FrameLayout(this);BotAvatarView face=new BotAvatarView(this);applyAvatar(face,name,metadata);face.state("ready",BLUE,0);face.running(visible);avatars.add(face);avatar.addView(face,new FrameLayout.LayoutParams(dp(34),dp(34)));
        if(working)avatar.addView(statusDot(GREEN,CARD_ROW),dotAt(10,24,24));row.addView(avatar,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView nameLabel=text(name,15,WHITE);nameLabel.setTag("bot_name");nameLabel.setSingleLine(true);nameLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);nameLabel.setGravity(Gravity.START);names.addView(nameLabel,new LinearLayout.LayoutParams(-1,-2));
        // Second line: "Working…" while busy, else the latest message, else the Bot's role.
        String previewText=metadata==null?"":metadata.optString("lastMessagePreview",""),role=metadata==null?"":metadata.optString("title","");
        String second=working?"Working…":!previewText.isEmpty()?MessageText.plain(previewText):role;
        if(!second.isEmpty()){TextView sub=text(second,12,working?GREEN:SECONDARY);sub.setTag(working?"bot_status":"bot_excerpt");sub.setSingleLine(true);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);sub.setGravity(Gravity.START);names.addView(sub,new LinearLayout.LayoutParams(-1,-2));}
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=dp(8);row.addView(names,np);
        if(hasUnread){View badge=unreadBadge(unread);LinearLayout.LayoutParams ub=new LinearLayout.LayoutParams(-2,dp(unread>0?18:8));ub.leftMargin=dp(4);row.addView(badge,ub);}
        row.setContentDescription(name+(working?", working":"")+(unread>0?", "+unread+" unread":hasUnread?", unread":""));
        if(selected){TextView check=text("✓",16,BLUE);check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(check,new LinearLayout.LayoutParams(dp(20),-2));}
        if(metadata!=null&&!metadata.optString("id","").isEmpty()&&!isPrimaryId(metadata.optString("id")))row.setOnLongClickListener(v->{openBotActions(metadata);return true;});
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(getResources().getConfiguration().fontScale>1.2f?62:52));rp.topMargin=dp(6);body.addView(row,rp);
    }
    private boolean working(JSONObject metadata){return metadata!=null&&(metadata.optBoolean("isRunning")||metadata.optBoolean("isRunningTurn")||metadata.optBoolean("isComposingMessage"));}
    /** A round status dot with a ring in the surface colour, so it reads against the avatar. */
    private View statusDot(int color,int ring){View dot=new View(this);GradientDrawable mark=new GradientDrawable();mark.setShape(GradientDrawable.OVAL);mark.setColor(color);mark.setStroke(dp(2),ring);dot.setBackground(mark);dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return dot;}
    private FrameLayout.LayoutParams dotAt(int size,int left,int top){FrameLayout.LayoutParams at=new FrameLayout.LayoutParams(dp(size),dp(size),Gravity.TOP|Gravity.LEFT);at.leftMargin=dp(left);at.topMargin=dp(top);return at;}
    /** Blue pill with the unread count (99+ at most), or a small dot when the count is unknown. */
    private View unreadBadge(int count){
        if(count<=0)return statusDot(BLUE,BLUE);
        TextView badge=text(count>99?"99+":Integer.toString(count),11,WHITE);badge.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));badge.setMinWidth(dp(18));badge.setPadding(dp(5),0,dp(5),0);badge.setBackground(shape(BLUE,9));badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return badge;
    }
    private JSONArray pinIds(){JSONArray pins=botPreferences.optJSONArray("pinnedAgentIds");return pins==null?new JSONArray():pins;}
    private boolean isPinned(String id){JSONArray pins=pinIds();for(int i=0;i<pins.length();i++)if(id.equals(pins.optString(i)))return true;return false;}
    private boolean isPrimaryId(String id){return !id.isEmpty()&&id.equals(botPreferences.optString("mainAgentId",""));}
    private boolean pinsFull(){return pinsFullAt>=0&&pinIds().length()>=pinsFullAt;}
    /** Pins in account order that are in the roster, without the Primary. Ids not loaded are skipped, never removed. */
    private java.util.List<JSONObject> pinnedBots(JSONObject primary){
        java.util.ArrayList<JSONObject> result=new java.util.ArrayList<>();java.util.HashSet<String> added=new java.util.HashSet<>();JSONArray pins=pinIds();
        for(int p=0;p<pins.length();p++)for(int i=0;i<bots.length();i++){JSONObject b=bots.optJSONObject(i);String id=b==null?"":b.optString("id");
            if(!id.isEmpty()&&id.equals(pins.optString(p))&&(primary==null||!id.equals(primary.optString("id")))&&added.add(id))result.add(b);}
        return result;
    }
    private boolean pinHintDone(){if(preview)return false;try{return getSharedPreferences("grokwear_ui",MODE_PRIVATE).getBoolean("pin_hint_done",false);}catch(Exception unavailable){return false;}}
    private void markPinHintDone(){if(preview||pinHintDone())return;try{getSharedPreferences("grokwear_ui",MODE_PRIVATE).edit().putBoolean("pin_hint_done",true).apply();}catch(Exception unavailable){}}
    /** Two columns of 74 × 70 dp tiles. Nine or more pins fold to seven tiles and a "+N" tile. */
    private void pinnedGrid(java.util.List<JSONObject> pins){
        TextView heading=label("Pinned",12,SECONDARY);heading.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));heading.setAccessibilityHeading(true);heading.getLayoutParams().height=dp(24);
        boolean folded=pins.size()>8&&!pinGridExpanded;int shown=folded?7:pins.size(),cells=shown+(folded?1:0);LinearLayout row=null;
        for(int i=0;i<cells;i++){
            if(i%2==0){row=new LinearLayout(this);row.setGravity(Gravity.CENTER_HORIZONTAL);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(70));if(i>0)rp.topMargin=dp(6);body.addView(row,rp);}
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(dp(74),dp(70));if(i%2==1)tp.leftMargin=dp(6);row.addView(i<shown?pinTile(pins.get(i)):moreTile(pins.size()-7),tp);
        }
        gap(10);
    }
    private View pinTile(JSONObject bot){
        String id=bot.optString("id",""),name=bot.optString("name","Unnamed Bot");
        FrameLayout tile=new FrameLayout(this);tile.setClickable(true);tile.setFocusable(true);tile.setEnabled(!botSelectionPending);
        boolean selected=preview?name.equals(botName):!selectedMetadata.optString("id","").isEmpty()&&id.equals(selectedMetadata.optString("id"));
        if(selected){View ring=new View(this);GradientDrawable halo=new GradientDrawable();halo.setShape(GradientDrawable.OVAL);halo.setStroke(Math.max(1,Math.round(1.5f*getResources().getDisplayMetrics().density)),WHITE);ring.setBackground(halo);FrameLayout.LayoutParams rl=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.TOP|Gravity.CENTER_HORIZONTAL);rl.topMargin=dp(3);tile.addView(ring,rl);}
        BotAvatarView face=new BotAvatarView(this);applyAvatar(face,name,bot);face.state("ready",BLUE,0);face.running(visible);avatars.add(face);face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // The avatar view draws its face at 76% of its box: a 58 dp box shows the 44 dp face.
        tile.addView(face,new FrameLayout.LayoutParams(dp(58),dp(58),Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        int count=Math.max(0,bot.optInt("unreadCount",0));boolean unread=count>0||bot.optBoolean("hasUnread"),working=working(bot);
        // Working: a green dot at the avatar's lower right. Unread: the count badge (or a dot) at its upper right.
        if(working)tile.addView(statusDot(GREEN,Color.BLACK),dotAt(12,47,40));
        if(unread){View badge=unreadBadge(count);FrameLayout.LayoutParams at=new FrameLayout.LayoutParams(count>0?-2:dp(12),dp(count>0?18:12),Gravity.TOP|Gravity.LEFT);at.leftMargin=dp(count>0?44:47);at.topMargin=dp(count>0?2:6);tile.addView(badge,at);}
        TextView label=text(name,12,WHITE);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(16),Gravity.TOP);lp.topMargin=dp(54);tile.addView(label,lp);
        tile.setContentDescription(name+", pinned"+(working?", working":"")+(count>0?", "+count+" unread":unread?", unread":"")+(selected?", selected":""));
        tile.setOnClickListener(v->{navigation++;primaryAction(bot,false);});tile.setOnLongClickListener(v->{openBotActions(bot);return true;});
        if(id.equals(justPinnedId)){justPinnedId="";tile.setScaleX(.6f);tile.setScaleY(.6f);tile.setAlpha(0);tile.animate().scaleX(1).scaleY(1).alpha(1).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator()).start();}
        return tile;
    }
    private View moreTile(int hidden){
        FrameLayout tile=new FrameLayout(this);tile.setClickable(true);tile.setFocusable(true);
        TextView disc=text("+"+hidden,16,WHITE);disc.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));GradientDrawable round=new GradientDrawable();round.setShape(GradientDrawable.OVAL);round.setColor(GLASS);disc.setBackground(round);disc.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.CENTER_HORIZONTAL);fp.topMargin=dp(4);tile.addView(disc,fp);
        TextView more=text("More",12,SECONDARY);more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(16),Gravity.TOP);lp.topMargin=dp(54);tile.addView(more,lp);
        tile.setContentDescription("Show "+hidden+" more pinned Bots");tile.setOnClickListener(v->{pinGridExpanded=true;renderBotListPreservingScroll();});
        return tile;
    }
    private void openBotActions(JSONObject bot){
        if(botSelectionPending||isPrimaryId(bot.optString("id")))return;
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        homeScrollY=currentScroll==null?0:currentScroll.getScrollY();actionsBot=bot;navigation++;screen="bot_actions";render();
    }
    private void closeBotActions(){
        actionsBot=null;screen="bots";render();final ScrollView restored=currentScroll;final int y=homeScrollY;
        restored.post(()->{if(currentScroll==restored&&screen.equals("bots"))restored.scrollTo(0,y);});
    }
    /** Long-press sheet: Pin or Unpin, Talk, Open chat. */
    private void botActions(){
        JSONObject bot=actionsBot;String name=bot.optString("name","Unnamed Bot");
        BotAvatarView face=new BotAvatarView(this);applyAvatar(face,name,bot);face.state("ready",BLUE,0);face.running(visible);avatars.add(face);face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);body.addView(face,new LinearLayout.LayoutParams(dp(48),dp(48)));gap(2);
        TextView title=label(name,15,WHITE);title.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);gap(2);
        View[] pin=pinViews(bot,true);if(pin[0]!=null)body.addView(pin[0],new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(48));pp.topMargin=dp(6);body.addView(pin[1],pp);
        Button talk=action("Talk",()->{actionsBot=null;screen="bots";primaryAction(bot,true);});styleTalk(talk);talk.setTextColor(Color.BLACK);talk.setBackground(shape(WHITE,24));talk.setContentDescription("Talk to "+name);
        action("Open chat",()->{actionsBot=null;screen="bots";primaryAction(bot,false);});
    }
    /** The pin control and the line above it: an error, pins full, offline, or "Pinned" on the Bot page. */
    private View[] pinViews(JSONObject bot,boolean sheet){
        String id=bot.optString("id",""),name=bot.optString("name","Unnamed Bot");boolean pinned=isPinned(id),saving=!pinSavingId.isEmpty()&&pinSavingId.equals(id);
        boolean online=preview||VoiceService.online(this),full=!pinned&&!saving&&pinsFull();
        TextView line=null;
        if(!saving&&id.equals(pinErrorId)){line=text("Couldn’t save. Try again",12,0xffff7a72);line.setCompoundDrawablesRelativeWithIntrinsicBounds(new AlertGlyph(),null,null,null);}
        else if(full){android.text.SpannableString words=new android.text.SpannableString("Pins are full. Unpin one first.");words.setSpan(new android.text.style.ForegroundColorSpan(0xffffb340),0,14,0);words.setSpan(new android.text.style.ForegroundColorSpan(SECONDARY),14,words.length(),0);line=text("",12,SECONDARY);line.setText(words);line.setMaxLines(2);}
        else if(!online&&!saving)line=text("Connect to pin",12,SECONDARY);
        else if(pinned&&!sheet){line=text("Pinned",12,SECONDARY);line.setCompoundDrawablesRelativeWithIntrinsicBounds(new PinGlyph(SECONDARY,11,true,false),null,null,null);if(id.equals(pinFadeId)){pinFadeId="";line.setAlpha(0);line.animate().alpha(1).setDuration(200).start();}}
        if(line!=null)line.setCompoundDrawablePadding(dp(4));
        LinearLayout control=new LinearLayout(this);control.setGravity(Gravity.CENTER);control.setPadding(dp(14),0,dp(14),0);control.setMinimumWidth(dp(90));control.setClickable(true);control.setFocusable(true);
        control.setBackground(new android.graphics.drawable.InsetDrawable(shape(GLASS,22),0,dp(2),0,dp(2)));
        if(saving){ProgressBar spinner=new ProgressBar(this);spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(WHITE));spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);control.addView(spinner,new LinearLayout.LayoutParams(dp(14),dp(14)));}
        else{ImageView icon=new ImageView(this);icon.setImageDrawable(new PinGlyph(WHITE,16,false,pinned));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);control.addView(icon,new LinearLayout.LayoutParams(dp(16),dp(16)));}
        TextView words=text(saving?(pinSavingTarget?"Pinning…":"Unpinning…"):pinned?"Unpin":"Pin",14,WHITE);words.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));words.setSingleLine(true);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-2,-2);wp.leftMargin=dp(6);control.addView(words,wp);
        boolean usable=!saving&&online&&!full&&!botSelectionPending;control.setEnabled(usable);if(!usable&&!saving)control.setAlpha(.4f);
        control.setContentDescription(saving?(pinSavingTarget?"Pinning ":"Unpinning ")+name:(pinned?"Unpin ":"Pin ")+name);
        control.setOnClickListener(v->{navigation++;setPinned(bot,!pinned);});
        return new View[]{line,control};
    }
    private void setPinned(JSONObject bot,boolean pin){
        String id=bot.optString("id","");if(id.isEmpty()||!pinSavingId.isEmpty()||isPrimaryId(id))return;
        pinErrorId="";pinSavingId=id;pinSavingTarget=pin;rerenderPins();
        final int request=++pinRequest;
        // The account may still store a late write; Home then shows whatever it stored.
        main.postDelayed(()->{if(request==pinRequest&&id.equals(pinSavingId))pinFailed(request,id,false);},10000);
        if(preview){int mine=previewEpoch;main.postDelayed(()->{if(mine!=previewEpoch)return;
            try{JSONArray ids=GrokCore.withPin(new JSONObject().put("settings",new JSONObject().put("pinnedAgents",new JSONObject().put("agentIds",pinIds()))),id,pin);pinSaved(request,id,pin,new JSONObject(botPreferences.toString()).put("pinnedAgentIds",ids));}
            catch(Exception impossible){pinFailed(request,id,false);}},700);return;}
        final String expected=botsAccountBinding;
        preferencesWorker.execute(()->{
            try{JSONObject prefs=account.setPinned(id,pin);main.post(()->{if(expected.equals(botsAccountBinding))pinSaved(request,id,pin,prefs);});}
            catch(GrokCore.Failure refused){boolean full=BotAccount.PINS_FULL.equals(refused.code);main.post(()->pinFailed(request,id,full));}
            catch(Exception failed){main.post(()->pinFailed(request,id,false));}
        });
    }
    private void pinSaved(int request,String id,boolean pin,JSONObject prefs){
        botPreferences=prefs;if(pinsFullAt>=0&&pinIds().length()<pinsFullAt)pinsFullAt=-1;
        if(request!=pinRequest){rerenderPins();return;}
        pinSavingId="";pinErrorId="";if(pin){markPinHintDone();justPinnedId=id;pinFadeId=id;}
        if(root!=null)root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        if(visible&&screen.equals("bot_actions")&&actionsBot!=null&&id.equals(actionsBot.optString("id"))){closeBotActions();return;}
        rerenderPins();
    }
    private void pinFailed(int request,String id,boolean full){
        if(request!=pinRequest||!id.equals(pinSavingId))return;
        pinSavingId="";if(full)pinsFullAt=pinIds().length();else pinErrorId=id;
        if(root!=null)root.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT);
        rerenderPins();refreshPins(true);
    }
    private void rerenderPins(){if(visible&&java.util.Set.of("bots","ready","bot_actions").contains(screen)&&(preview||!VoiceService.snapshot().active))render();}
    /** Pins can change on the phone or desktop: re-read them at most every 30 seconds, or now after a failed write. */
    private void refreshPins(boolean now){
        if(preview||botsAccountBinding.isEmpty())return;long elapsed=android.os.SystemClock.elapsedRealtime();
        if(!now&&lastPinsReadElapsed>0&&elapsed-lastPinsReadElapsed<30000)return;lastPinsReadElapsed=elapsed;
        final int request=botRequest;final String expected=botsAccountBinding;
        preferencesWorker.execute(()->{try{
            if(!expected.equals(accountIdentity(account.authState())))return;
            JSONObject prefs=account.botPreferences();main.post(()->applyBotPreferences(request,expected,prefs));
        }catch(Exception optionalUnavailable){}});
    }
    /** The Bot page's own Bot for the pin control; null for the Primary Bot or an unknown Bot. */
    private JSONObject currentPinBot(){
        if(preview){for(int i=0;i<bots.length();i++){JSONObject b=bots.optJSONObject(i);if(b!=null&&botName.equals(b.optString("name")))return isPrimaryId(b.optString("id"))?null:b;}return null;}
        String id=selectedMetadata.optString("id","");return id.isEmpty()||isPrimaryId(id)?null:selectedMetadata;
    }
    private void previewPins(String value){
        try{bots=new JSONArray().put(new JSONObject().put("id","preview-primary").put("name","Main Bot").put("lastMessagePreview","Ready when you are"));
            String[] names={"Research Bot","Travel Bot","Inbox Bot","Release Bot","Notes Bot","Fitness Bot","Budget Bot","Reading Bot","Garden Bot","Music Bot","Recipe Bot"};
            String[] lines={"3 new papers summarised","Tokyo fare dropped to NT$3,430","","Build 0.4.1 passed all checks","Saved your meeting notes","10,000 steps today","October spending is under plan","","Time to water the tomatoes","New playlist for your run","Dinner idea: miso salmon"};
            for(int i=0;i<names.length;i++){JSONObject b=previewBot(names[i],previewMedia&&!lines[i].isEmpty()?lines[i]:"Latest update from "+names[i],i==1||i==6).put("isRunning",i==2||i==7);
                if(i==1)b.put("unreadCount",3);if(i==6)b.put("unreadCount",12);if(i==8&&!previewMedia){b.remove("lastMessagePreview");b.put("title","Platform ops");}bots.put(b);}
            JSONArray pins=new JSONArray().put("preview-primary");int count=value.equals("home_overflow")?11:value.equals("home_pinned")||value.equals("bot_pinned")?4:0;
            for(int i=0;i<count;i++)pins.put(bots.getJSONObject(i+1).getString("id"));
            botPreferences=new JSONObject().put("mainAgentId","preview-primary").put("pinnedAgentIds",pins);
            pinErrorId="";pinSavingId="";pinsFullAt=-1;pinGridExpanded=false;actionsBot=null;
            if(value.equals("home_pinned")){botName="Travel Bot";screen="bots";}
            else if(value.equals("home_overflow"))screen="bots";
            else if(value.equals("bot_pinned")){botName="Research Bot";screen="ready";}
            else{actionsBot=bots.getJSONObject(1);screen="bot_actions";if(value.equals("pin_error"))pinErrorId=actionsBot.getString("id");if(value.equals("pins_full"))pinsFullAt=0;}
        }catch(Exception ignored){}
    }
    /** Material Symbols "keep" and "keep_off" shapes, drawn on a 24-unit grid. */
    private final class PinGlyph extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);private final int color,size;private final boolean filled,off;
        PinGlyph(int color,int size,boolean filled,boolean off){this.color=color;this.size=size;this.filled=filled;this.off=off;}
        @Override public int getIntrinsicWidth(){return dp(size);}@Override public int getIntrinsicHeight(){return dp(size);}
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
            p.setColor(color);p.setStrokeWidth(2);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            android.graphics.Path head=new android.graphics.Path();head.moveTo(9,3);head.lineTo(15,3);head.lineTo(14,9);head.lineTo(17,12);head.lineTo(7,12);head.lineTo(10,9);head.close();
            p.setStyle(filled?android.graphics.Paint.Style.FILL_AND_STROKE:android.graphics.Paint.Style.STROKE);canvas.drawPath(head,p);
            p.setStyle(android.graphics.Paint.Style.STROKE);canvas.drawLine(12,12,12,21,p);if(off)canvas.drawLine(4,4,20,20,p);canvas.restore();}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    /** Edit pencil, stroked in the text colour. */
    private final class PencilGlyph extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override public int getIntrinsicWidth(){return dp(16);}@Override public int getIntrinsicHeight(){return dp(16);}
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
            p.setColor(WHITE);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(2);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            android.graphics.Path pen=new android.graphics.Path();pen.moveTo(4,20);pen.lineTo(4.5f,16);pen.lineTo(16,4.5f);pen.lineTo(19.5f,8);pen.lineTo(8,19.5f);pen.close();canvas.drawPath(pen,p);canvas.drawLine(13.5f,7,17,10.5f,p);canvas.restore();}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    /** Paper-plane send arrow, filled black for the white Send pill. */
    private final class SendGlyph extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override public int getIntrinsicWidth(){return dp(18);}@Override public int getIntrinsicHeight(){return dp(18);}
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
            p.setColor(Color.BLACK);p.setStyle(android.graphics.Paint.Style.FILL);
            android.graphics.Path plane=new android.graphics.Path();plane.moveTo(3,20.5f);plane.lineTo(21.5f,12);plane.lineTo(3,3.5f);plane.lineTo(3,10);plane.lineTo(15,12);plane.lineTo(3,14);plane.close();canvas.drawPath(plane,p);canvas.restore();}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private final class AlertGlyph extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override public int getIntrinsicWidth(){return dp(12);}@Override public int getIntrinsicHeight(){return dp(12);}
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
            p.setColor(0xffff7a72);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(2.4f);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);canvas.drawCircle(12,12,10,p);canvas.drawLine(12,7,12,13,p);canvas.drawPoint(12,17,p);canvas.restore();}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private void applyAvatar(BotAvatarView face,String name,JSONObject metadata){
        face.setPreview(preview);face.setBotName(name);
        if(!preview&&metadata!=null&&(metadata.has("avatarShape")||metadata.has("avatarColor")))face.setAvatar(metadata.optString("avatarShape",""),metadata.optString("avatarColor",""));
        if(!preview&&metadata!=null&&metadata.has("avatarDataUrl"))face.setPhotoDataUrl(metadata.optString("avatarDataUrl",""));
    }
    private void choosePreview(String name){clearHistory();botName=name;
        if(previewMedia)try{String[] chat=name.equals("Travel Bot")?new String[]{"Watch fares to Tokyo for me","On it. I'll message you here when a fare drops."}:new String[]{"What is next on my calendar?","Your next meeting is at ten."};
            long now=System.currentTimeMillis();historyEntries.put(new JSONObject().put("id","demo-user").put("role","user").put("text",chat[0]).put("atMs",now-3600_000L));
            historyEntries.put(new JSONObject().put("id","demo-bot").put("role","assistant").put("text",chat[1]).put("atMs",now-3540_000L));}catch(Exception ignored){}
        screen="ready";render();}
    private static String previewDraft(String bot){return bot.equals("Travel Bot")?"Find the cheapest flight to Tokyo next week":"Use three sentences to introduce yourself";}
    private static String previewReply(String bot){return bot.equals("Travel Bot")?"Cheapest next week: **Thu Oct 29, NT$3,430**, the lowest this month. Want me to hold a seat?":"Hi! I'm your Bot. Ask me anything from your watch and I'll take care of it.";}
    private int accent(){try{if(avatarTheme==null){try(java.io.InputStream input=getAssets().open("bot-avatar.json")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] block=new byte[4096];int count;while((count=input.read(block))!=-1){bytes.write(block,0,count);}avatarTheme=new JSONObject(bytes.toString("UTF-8")).getJSONObject("themeColors");}}String token=preview?BotAvatarView.previewLook(botName)[1]:selectedMetadata.optString("avatarColor","");JSONObject theme=avatarTheme.optJSONObject(token);return theme==null?WHITE:Color.parseColor(theme.getString("dark"));}catch(Exception ignored){return WHITE;}}
    private String currentConversationIdentity(){String id=selectedMetadata.optString("id","");if(id.isEmpty()||botsAccountBinding.isEmpty())return "";return new JSONArray().put(botsAccountBinding).put(id).put(selectedMetadata.optBoolean("viewerIsOwner",true)?"":selectedMetadata.optString("viewerSessionId","")).toString();}
    private boolean hasCurrentReceipt(){return !receiptIdentity.isEmpty()&&receiptIdentity.equals(currentConversationIdentity());}
    private boolean takeOwnHistoryRefresh(boolean active,boolean staging){if(active||staging||historyLoading||!receiptRefreshPending||!screen.equals("ready")||!hasCurrentReceipt())return false;receiptRefreshPending=false;return true;}
    private void edgeTalk(){
        Button talk=button("Start voice chat",()->talk());styleTalk(talk);talk.setTextColor(Color.BLACK);talk.setBackground(shape(WHITE,28));talk.setEnabled(!historyStaging()&&!botSelectionPending);
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(side*.62f),dp(54),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);ep.bottomMargin=dp(12);root.addView(talk,ep);
    }
    private void ready(){
        FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)currentScroll.getLayoutParams();sp.topMargin=0;sp.bottomMargin=dp(67);currentScroll.setLayoutParams(sp);body.setPadding(0,0,0,dp(28));
        // A Bot other than the Primary gets a compact hero with its pin control under the name.
        JSONObject pinBot=currentPinBot();View[] pin=pinBot==null?null:pinViews(pinBot,false);boolean caption=pin!=null&&pin[0]!=null;
        FrameLayout hero=new FrameLayout(this);hero.setMinimumHeight(dp(side-67));body.addView(hero,new LinearLayout.LayoutParams(-1,pin==null?dp(side-67):-2));
        Button back=button("Bots",()->openBotPicker());back.setText("‹ Bots");back.setTextSize(12);back.setPadding(0,0,0,0);back.setBackground(shape(Color.TRANSPARENT,0));back.setElevation(0);back.setStateListAnimator(null);FrameLayout.LayoutParams navp=new FrameLayout.LayoutParams(dp(68),dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);navp.topMargin=dp(pin!=null?-6:3);hero.addView(back,navp);
        BotAvatarView face=new BotAvatarView(this);applyAvatar(face,botName,selectedMetadata);face.state(screen.equals("done")?"done":"ready",accent(),0);face.running(visible);avatars.add(face);int faceSize=pin!=null?(caption?40:47):compactHero()?48:64;FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(faceSize),dp(faceSize),Gravity.TOP|Gravity.CENTER_HORIZONTAL);fp.topMargin=dp(pin!=null?(caption?22:21):compactHero()?30:side*.34f-32);hero.addView(face,fp);
        TextView name=text(botName,getResources().getConfiguration().fontScale>1.2f?12:16,WHITE);name.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setContentDescription("Current Bot: "+botName);FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(dp(side*.76f),dp(22),Gravity.TOP|Gravity.CENTER_HORIZONTAL);np.topMargin=dp(pin!=null?(caption?57:64):compactHero()?80:side*.535f-11);hero.addView(name,np);
        if(pin!=null){LinearLayout stack=new LinearLayout(this);stack.setOrientation(LinearLayout.VERTICAL);stack.setGravity(Gravity.CENTER_HORIZONTAL);
            if(caption){pin[0].setLayoutParams(new LinearLayout.LayoutParams(-2,-2));stack.addView(pin[0]);}
            stack.addView(pin[1],new LinearLayout.LayoutParams(-2,dp(48)));
            FrameLayout.LayoutParams sp2=new FrameLayout.LayoutParams(dp(side*.76f),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);sp2.topMargin=np.topMargin+dp(22);hero.addView(stack,sp2);}
        else{String last="";if(historyEntries.length()>0){JSONObject entry=historyEntries.optJSONObject(historyEntries.length()-1);if(entry!=null)last=entry.optString("text");}if(last.isEmpty())last=selectedMetadata.optString("lastMessagePreview","");
            TextView excerpt=text(last.isEmpty()?(preview?"Ready when you are":"Ready to talk"):MessageText.plain(last),compactHero()?11:12,SECONDARY);excerpt.setTag("bot_excerpt");excerpt.setSingleLine(true);excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);FrameLayout.LayoutParams xp=new FrameLayout.LayoutParams(dp(side*.76f),dp(20),Gravity.TOP|Gravity.CENTER_HORIZONTAL);xp.topMargin=dp(Math.min(side-88,side*.615f-10));hero.addView(excerpt,xp);}
        LinearLayout chat=new LinearLayout(this);chat.setOrientation(LinearLayout.VERTICAL);chat.setPadding(dp(side*.12f),dp(10),dp(side*.12f),0);body.addView(chat,new LinearLayout.LayoutParams(-1,-2));body=chat;label("Chat",17,WHITE);messageButton();if(historyLoading&&historyEntries.length()==0)label("Loading history…",12,SECONDARY);if(historyStale)label("Showing last loaded messages",12,SECONDARY);if(!historyError.isEmpty())label(historyError,12,SECONDARY);
        if(historyEntries.length()==0&&!historyLoading)label("No messages loaded",12,SECONDARY);
        for(int i=0;i<historyEntries.length();i++){JSONObject e=historyEntries.optJSONObject(i);if(e==null)continue;chatBubble(e);}
        outgoingBubbles();
        if(historyHasMore&&historyPages<5){Button older=action("Load older messages",()->loadHistory(true));older.setEnabled(!historyLoading);}
        if(screen.equals("done")||(!preview&&hasCurrentReceipt()&&receiptElapsed>0)){long ms=preview?102000:receiptElapsed;label("Voice chat · "+duration(ms),12,SECONDARY);}
        String sync=preview?(previewSaving?"Saving shared history":""):hasCurrentReceipt()?receiptSync:"";if(!sync.isEmpty())label(sync.equals("Saving shared history")?"Saving shared history…":sync.equals("synced")?"Shared history saved":sync.equals("pending")||sync.equals("uncertain")?"Voice sync pending":sync.equals("rejected")?"History could not be saved":sync,12,SECONDARY);
        if(!safeError.isEmpty())label(safeError,12,SECONDARY);edgeTalk();
    }
    private void chatBubble(JSONObject entry){
        String raw=entry.optString("text"),value=MessageText.plain(raw);String who=entry.optString("role").equals("user")?"You":botName;
        TextView bubble=label(value,14,WHITE);bubble.setText(rich(raw));bubble.setTag("chat_message");bubble.setGravity(Gravity.START);bubble.setMaxLines(4);bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bubble.setContentDescription(who+": "+value+". Tap to read");bubble.setFocusable(true);bubble.setClickable(true);
        // A tap opens the whole message full screen, in larger text, scrolled with the crown.
        bubble.setOnClickListener(v->{navigation++;readerEntry=entry;readerReturnY=currentScroll==null?0:currentScroll.getScrollY();screen="reader";render();});
        bubble.setPadding(dp(10),dp(8),dp(10),dp(8));bubble.setBackground(shape(entry.optString("role").equals("user")?userBubble():CARD,14));LinearLayout.LayoutParams bp=(LinearLayout.LayoutParams)bubble.getLayoutParams();bp.topMargin=dp(6);bubble.setLayoutParams(bp);
    }
    /** Message text with **bold** key words and numbers kept bold. */
    private CharSequence rich(String raw){
        MessageText.Styled styled=MessageText.styled(raw);android.text.SpannableString text=new android.text.SpannableString(styled.text);
        for(int i=0;i+1<styled.bold.length;i+=2)text.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),styled.bold[i],styled.bold[i+1],android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
    /** "Message" pill: dictate or type a text message to this Bot, then review it before sending. */
    private void messageButton(){
        if(!preview&&selectedMetadata.optString("id","").isEmpty())return;
        Button message=action("Message",()->composeMessage());message.setCompoundDrawablesRelativeWithIntrinsicBounds(new PencilGlyph(),null,null,null);message.setCompoundDrawablePadding(dp(6));
        message.setContentDescription("Send a text message to "+botName);message.setEnabled(!messageSending&&!botSelectionPending);
    }
    private void composeMessage(){
        if(preview){draftMessage=previewDraft(botName);screen="compose_review";render();return;}
        try{
            android.content.Intent input=androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent();
            android.app.RemoteInput field=new android.app.RemoteInput.Builder("message").setLabel("Message "+botName).build();
            androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(input,java.util.Collections.singletonList(field));
            startActivityForResult(input,MESSAGE_INPUT);
        }catch(RuntimeException unavailable){draftMessage="";editDraft="";screen="compose_edit";render();}
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){
        super.onActivityResult(request,result,data);
        if(request!=MESSAGE_INPUT||result!=RESULT_OK||data==null)return;
        Bundle values=android.app.RemoteInput.getResultsFromIntent(data);CharSequence text=values==null?null:values.getCharSequence("message");
        if(text==null||text.toString().trim().isEmpty())return;
        draftMessage=text.toString().trim();screen="compose_review";render();
    }
    /** Review before sending, like Grok Bot's watch views: the text, Edit, and a big Send. */
    private void composeReview(){
        body.setPadding(dp(side*.12f),dp(20),dp(side*.12f),dp(8));
        // Inset further than the card so a long Bot name wraps inside the round edge.
        TextView title=label("Send to "+botName+"?",14,WHITE);title.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(side*.06f),0,dp(side*.06f),0);gap(4);
        TextView card=label(draftMessage,14,WHITE);card.setGravity(Gravity.START);card.setPadding(dp(12),dp(8),dp(12),dp(8));card.setBackground(shape(GLASS,20));card.setMaxLines(8);card.setEllipsize(android.text.TextUtils.TruncateAt.END);card.setContentDescription("Message: "+draftMessage);
        gap(6);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);
        Button change=button("Edit",()->{editDraft=draftMessage;screen="compose_edit";render();});change.setCompoundDrawablesRelativeWithIntrinsicBounds(new PencilGlyph(),null,null,null);change.setCompoundDrawablePadding(dp(6));change.setPadding(dp(16),0,dp(16),0);change.setEnabled(!messageSending);
        row.addView(change,new LinearLayout.LayoutParams(-2,dp(40)));body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        Button send=pinnedAction(messageSending?"Sending…":"Send",new SendGlyph(),()->sendDraft());send.setEnabled(!messageSending&&!draftMessage.isEmpty());send.setContentDescription("Send to "+botName);
        if(!safeError.isEmpty()){gap(6);label(safeError,12,0xffff7a72);}
    }
    /** A big white button pinned above the bottom edge, like Talk, so it can never scroll off the round screen. */
    private Button pinnedAction(String value,android.graphics.drawable.Drawable icon,Runnable action){
        FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)currentScroll.getLayoutParams();sp.topMargin=0;sp.bottomMargin=dp(67);currentScroll.setLayoutParams(sp);
        Button b=button(value,action);b.setTextColor(Color.BLACK);b.setBackground(shape(WHITE,28));b.setTextSize(16);
        if(icon!=null){b.setCompoundDrawablesRelativeWithIntrinsicBounds(icon,null,null,null);b.setCompoundDrawablePadding(dp(8));
            // Centres the icon and label together.
            int inset=Math.max(dp(8),(dp(side*.62f)-icon.getIntrinsicWidth()-dp(8)-(int)Math.ceil(b.getPaint().measureText(value)))/2);b.setPadding(inset,dp(6),inset,dp(6));}
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(side*.62f),dp(54),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);ep.bottomMargin=dp(12);root.addView(b,ep);bottomAction=true;
        return b;
    }
    /** Fix the text with the watch keyboard (dictation from the keyboard works too). */
    private void composeEdit(){
        body.setPadding(dp(side*.12f),dp(24),dp(side*.12f),dp(8));
        label("Edit message",13,SECONDARY);gap(6);
        EditText field=new EditText(this);field.setText(editDraft);field.setTextColor(WHITE);field.setTextSize(15);field.setHint("Message "+botName);field.setHintTextColor(SECONDARY);
        field.setBackground(shape(GLASS,18));field.setPadding(dp(12),dp(10),dp(12),dp(10));
        // The keyboard's action key finishes the edit (a watch keyboard has no other way out); long text still wraps.
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setHorizontallyScrolling(false);field.setMinLines(2);field.setMaxLines(6);field.setSelection(field.getText().length());
        field.setOnEditorActionListener((v,id,event)->{if(id!=android.view.inputmethod.EditorInfo.IME_ACTION_DONE)return false;finishEdit(v);return true;});
        // Kept as typed so a re-render on resume shows the same text.
        field.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int b,int c){}public void onTextChanged(CharSequence t,int a,int b,int c){}public void afterTextChanged(android.text.Editable t){editDraft=t.toString();}});
        body.addView(field,new LinearLayout.LayoutParams(-1,-2));
        pinnedAction("Done",null,()->finishEdit(field));
    }
    private void finishEdit(View field){
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);
        draftMessage=editDraft.trim();safeError="";screen=draftMessage.isEmpty()?"ready":"compose_review";render();
    }
    private void sendDraft(){
        if(messageSending||draftMessage.isEmpty())return;
        String text=draftMessage;
        if(preview){
            // Simulated: Sending…, then Sent, then the message and a demo reply land in the chat.
            messageSending=true;render();final int mine=++previewEpoch;final long created=System.currentTimeMillis();final String bot=botName;
            main.postDelayed(()->{if(mine!=previewEpoch)return;messageSending=false;draftMessage="";screen="ready";
                try{botMessages=new JSONArray().put(new JSONObject().put("id","demo-send").put("text",text).put("state","submitting").put("created",created));}catch(Exception ignored){}
                scrollToEnd=true;render();},600);
            main.postDelayed(()->{if(mine!=previewEpoch)return;try{botMessages.getJSONObject(0).put("state","sent");}catch(Exception ignored){}render();},1800);
            main.postDelayed(()->{if(mine!=previewEpoch)return;try{historyEntries.put(new JSONObject().put("id","demo-sent").put("role","user").put("text",text).put("atMs",created));
                historyEntries.put(new JSONObject().put("id","demo-reply").put("role","assistant").put("text",previewReply(bot)).put("atMs",System.currentTimeMillis()));}catch(Exception ignored){}
                botMessages=new JSONArray();scrollToEnd=true;render();},4200);
            return;}
        final String botId=selectedMetadata.optString("id","");if(botId.isEmpty())return;
        messageSending=true;safeError="";render();final int mine=epoch;
        worker.execute(()->{JSONObject result=null;String error="";
            try{String id=account.prepareMessage(text,botId);
                // Once admitted the request can't be recalled; leaving the screen before that cancels it.
                result=account.sendMessage(id,()->visible&&mine==epoch);
            }catch(Exception failed){error=failed instanceof GrokCore.Failure&&failed.getMessage()!=null?failed.getMessage():"Couldn’t send. Check your connection.";}
            JSONObject sent=result;String problem=error;
            main.post(()->{messageSending=false;
                if(!problem.isEmpty()){safeError=problem;if(screen.equals("compose_review"))render();return;}
                draftMessage="";safeError="";if(sent!=null&&"sent".equals(sent.optString("state")))root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                screen="ready";scrollToEnd=true;refreshMessages(botId);watchForReply(text,sent==null?System.currentTimeMillis():sent.optLong("created",System.currentTimeMillis()));render();});
        });
    }
    /** The watch's own messages that the chat doesn't show yet, with their delivery state. */
    private void outgoingBubbles(){
        long now=System.currentTimeMillis();
        for(int i=0;i<botMessages.length();i++){JSONObject job=botMessages.optJSONObject(i);if(job==null)continue;
            String state=job.optString("state"),text=job.optString("text");long created=job.optLong("created");
            if(inHistory(text,created))continue;
            if(state.equals("sent")&&now-created>10*60_000L)continue;
            TextView bubble=label(text,14,WHITE);bubble.setGravity(Gravity.START);bubble.setPadding(dp(10),dp(8),dp(10),dp(8));bubble.setBackground(shape(userBubble(),14));
            LinearLayout.LayoutParams bp=(LinearLayout.LayoutParams)bubble.getLayoutParams();bp.topMargin=dp(6);bubble.setLayoutParams(bp);
            String status=state.equals("sent")?"Sent":state.equals("failed")?job.optString("notice","Not sent"):state.equals("unknown")?"Not confirmed · Tap to try again":"Sending…";
            TextView line=label(status,11,state.equals("failed")?0xffff7a72:SECONDARY);line.setGravity(Gravity.END);
            if(state.equals("unknown")){final String id=job.optString("id");line.setClickable(true);line.setOnClickListener(v->retryMessage(id));bubble.setOnClickListener(v->retryMessage(id));}
            bubble.setContentDescription("You: "+text+". "+status);
        }
    }
    private boolean inHistory(String text,long created){
        for(int i=historyEntries.length()-1;i>=0;i--){JSONObject e=historyEntries.optJSONObject(i);
            if(e!=null&&"user".equals(e.optString("role"))&&text.equals(e.optString("text").trim())&&e.optLong("atMs")>=created-120_000L)return true;}
        return false;
    }
    private void retryMessage(String id){
        if(preview||messageSending)return;messageSending=true;render();final String botId=selectedMetadata.optString("id","");
        JSONObject job=new JSONObject();for(int i=0;i<botMessages.length();i++){JSONObject j=botMessages.optJSONObject(i);if(j!=null&&id.equals(j.optString("id")))job=j;}final JSONObject retried=job;
        worker.execute(()->{try{account.retryMessage(id);}catch(Exception ignored){}main.post(()->{messageSending=false;refreshMessages(botId);watchForReply(retried.optString("text"),retried.optLong("created",System.currentTimeMillis()));});});
    }
    private void refreshMessages(String botId){
        if(preview||botId.isEmpty())return;
        worker.execute(()->{try{JSONArray mine=account.messages(botId);
            // Confirm anything uncertain from the chat; this never sends.
            for(int i=0;i<mine.length();i++){JSONObject job=mine.optJSONObject(i);if(job!=null&&("unknown".equals(job.optString("state"))||"submitting".equals(job.optString("state"))))mine.put(i,account.confirmMessage(job.optString("id")));}
            JSONArray latest=mine;main.post(()->{if(!botId.equals(selectedMetadata.optString("id")))return;botMessages=latest;if(screen.equals("ready"))render();});
        }catch(Exception ignored){}});
    }
    /** After sending, refresh the chat with backoff until the Bot replies (about 3 minutes at most). */
    private void watchForReply(String text,long created){
        if(replyWatch!=null)main.removeCallbacks(replyWatch);replyChecks=0;replyWatchText=text;replyWatchSince=created;final String botId=selectedMetadata.optString("id","");
        replyWatch=new Runnable(){public void run(){
            if(!visible||!screen.equals("ready")||!botId.equals(selectedMetadata.optString("id"))||replyChecks>=12)return;
            if(replyArrived()){markRead(botId,true);scrollToEnd=true;render();return;}
            replyChecks++;loadBotChat();refreshMessages(botId);
            long[] steps={3000,4000,5000,8000,10000,15000,15000,20000,25000,30000,30000,30000};main.postDelayed(this,steps[Math.min(replyChecks,steps.length-1)]);
        }};
        main.postDelayed(replyWatch,3000);
    }
    /** A Bot reply follows the watch's message in the chat. Order, not clocks: the reply can land before the send's own answer. */
    private boolean replyArrived(){boolean reply=false;
        for(int i=historyEntries.length()-1;i>=0;i--){JSONObject e=historyEntries.optJSONObject(i);if(e==null)continue;
            if("assistant".equals(e.optString("role")))reply=true;
            else if("user".equals(e.optString("role"))&&replyWatchText.equals(e.optString("text").trim())&&e.optLong("atMs")>=replyWatchSince-120_000L)return reply;}
        return false;}
    /** Marks the Bot's chat read on open (and when a reply arrives), as the official apps do. */
    private void markRead(String botId,boolean force){
        if(preview||botId.isEmpty())return;
        boolean unread=selectedMetadata.optBoolean("hasUnread")||selectedMetadata.optInt("unreadCount",0)>0;
        if(!force&&(!unread||botId.equals(markedReadId)))return;markedReadId=botId;
        try{selectedMetadata.put("hasUnread",false).put("unreadCount",0);for(int i=0;i<bots.length();i++){JSONObject b=bots.optJSONObject(i);if(b!=null&&botId.equals(b.optString("id")))b.put("hasUnread",false).put("unreadCount",0);}}catch(Exception ignored){}
        preferencesWorker.execute(()->{try{account.markRead(botId);}catch(Exception optional){}});
    }
    /** Full-screen reading view for one chat message. */
    private void reader(){
        JSONObject entry=readerEntry==null?new JSONObject():readerEntry;String who=entry.optString("role").equals("user")?"You":botName;
        body.setPadding(dp(side*.11f),dp(18),dp(side*.11f),dp(side*.22f));
        TextView from=label(who+historyTime(entry),12,SECONDARY);from.setSingleLine(true);from.setEllipsize(android.text.TextUtils.TruncateAt.END);gap(6);
        String raw=entry.optString("text","Message unavailable");
        TextView message=label(MessageText.plain(raw),16,WHITE);message.setText(rich(raw));message.setGravity(Gravity.START);message.setLineSpacing(0,1.12f);
        message.setContentDescription(who+": "+MessageText.plain(raw));
    }
    private void closeReader(){
        screen="ready";render();final ScrollView restored=currentScroll;final int y=readerReturnY;
        restored.post(()->{if(currentScroll==restored&&screen.equals("ready"))restored.scrollTo(0,y);});
    }
    private int userBubble(){if(selectedMetadata.has("avatarDataUrl"))return GLASS;int color=accent();double[] rgb={Color.red(color)/255d,Color.green(color)/255d,Color.blue(color)/255d};for(int i=0;i<3;i++)rgb[i]=rgb[i]<=.04045?rgb[i]/12.92:Math.pow((rgb[i]+.055)/1.055,2.4);double luminance=.2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2];return 1.05/(luminance+.05)>=4.5?color:GLASS;}
    private String duration(long ms){return String.format(java.util.Locale.ROOT,"%d:%02d",Math.max(0,ms)/60000,(Math.max(0,ms)/1000)%60);}
    private void loadBotChat(){if(preview){previewHistory("history");screen="ready";render();return;}if(historyLoading||VoiceService.snapshot().active)return;historyAgent=selectedMetadata.optString("id");historyAccount=botsAccountBinding;loadHistory(false);
        String id=selectedMetadata.optString("id","");if(replyChecks==0)refreshMessages(id);markRead(id,false);}
    private String callState(){switch(screen){case "listening":return "Listening…";case "thinking":return "Thinking…";case "connecting":return "Connecting…";case "speaking":return "Speaking…";case "reconnecting":return "Reconnecting…";default:return screen.substring(0,1).toUpperCase(java.util.Locale.ROOT)+screen.substring(1);}}
    private boolean isCall(){return java.util.Set.of("connecting","listening","thinking","speaking","muted","paused","reconnecting").contains(screen);}
    private void call(){
        shownCaption=null;shownMuteColor=0;shownStatusSize=0;
        FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)currentScroll.getLayoutParams();sp.topMargin=0;sp.bottomMargin=dp(62);currentScroll.setLayoutParams(sp);body.setPadding(0,0,0,dp(24));
        callStage=new FrameLayout(this);body.addView(callStage,new LinearLayout.LayoutParams(-1,dp(side-62)));callCompact=!caption.isEmpty();
        orb=new BotAvatarView(this);avatars.add(orb);applyAvatar(orb,botName,selectedMetadata);orb.state(screen,accent(),0);orb.running(visible);orb.setOnClickListener(v->{if(preview){screen="listening";caption="";updateCallText();}else VoiceService.interrupt(this);});
        int faceSize=callCompact?44:64;FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(faceSize),dp(faceSize),Gravity.TOP|Gravity.CENTER_HORIZONTAL);fp.topMargin=dp(side*(callCompact?.25f:.34f)-faceSize/2f);callStage.addView(orb,fp);
        callBot=text(botName,getResources().getConfiguration().fontScale>1.2f?12:15,WHITE);callBot.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));callBot.setSingleLine(true);callBot.setEllipsize(android.text.TextUtils.TruncateAt.END);callBot.setContentDescription("Current Bot: "+botName);placeCall(callBot,callCompact?.385f:.535f,22);
        LinearLayout status=new LinearLayout(this);status.setGravity(Gravity.CENTER);waveform=new WaveformView(this);waveform.setColor(accent());waveform.running(visible);status.addView(waveform,new LinearLayout.LayoutParams(dp(36),dp(18)));callStatus=text("",13,SECONDARY);callStatus.setSingleLine(true);callStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams statusText=new LinearLayout.LayoutParams(-2,-2);statusText.leftMargin=dp(10);status.addView(callStatus,statusText);placeCall(status,callCompact?.47f:.615f,22);
        callCaption=text(caption,getResources().getConfiguration().fontScale>1.2f?12:14,WHITE);callCaption.setMaxLines(side<200||getResources().getConfiguration().fontScale>1.2f?1:2);callCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);callCaption.setOnClickListener(v->showCaption());placeCall(callCaption,.56f,side<200?24:40);FrameLayout.LayoutParams captionPosition=(FrameLayout.LayoutParams)callCaption.getLayoutParams();captionPosition.topMargin+=dp(7);callCaption.setLayoutParams(captionPosition);callHint=null;
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(108),dp(48),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);cp.bottomMargin=dp(side*.04f);root.addView(controls,cp);
        boolean muted=preview?previewMuted:VoiceService.snapshot().muted;callMute=button(muted?"Unmute":"Mute",()->toggleMute());callMute.setText("");callMute.setPadding(0,0,0,0);callMute.setForeground(new MicGlyph(muted,false));callMute.setForegroundGravity(Gravity.CENTER);callMute.setBackground(shape(GLASS,24));controls.addView(callMute,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button end=button("Hang up",()->endCall());end.setText("×");end.setTextSize(28);end.setPadding(0,0,0,0);end.setBackground(shape(RED,24));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(dp(48),dp(48));ep.leftMargin=dp(12);controls.addView(end,ep);
        LinearLayout extra=new LinearLayout(this);extra.setOrientation(LinearLayout.VERTICAL);extra.setPadding(dp(side*.12f),dp(12),dp(side*.12f),0);body.addView(extra,new LinearLayout.LayoutParams(-1,-2));TextView heading=text("Voice chat transcript",15,WHITE);extra.addView(heading);transcriptBody=new LinearLayout(this);transcriptBody.setOrientation(LinearLayout.VERTICAL);extra.addView(transcriptBody);renderTranscript();extra.addView(text("Voice call settings",15,WHITE));extra.addView(text("Voice settings are managed on your phone or Mac.",12,SECONDARY));extra.addView(text("Hang up to switch Bots.",12,SECONDARY));updateCallText();main.postDelayed(callClock,1000);
    }
    private void placeCall(View view,float at,int height){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(side*.76f),dp(height),Gravity.TOP|Gravity.CENTER_HORIZONTAL);p.topMargin=dp(side*at-height/2f);callStage.addView(view,p);}
    private final class VoiceGlyph extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override public int getIntrinsicWidth(){return dp(20);}@Override public int getIntrinsicHeight(){return dp(20);}
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/20f,b.height()/20f);p.setColor(Color.BLACK);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeWidth(2.2f);float[] lengths={6,12,16,10,5};for(int i=0;i<5;i++)canvas.drawLine(3+i*3.5f,10-lengths[i]/2,3+i*3.5f,10+lengths[i]/2,p);canvas.restore();}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private final class MicGlyph extends android.graphics.drawable.Drawable {
        private final boolean muted,resume;
        private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        MicGlyph(boolean muted,boolean resume){this.muted=muted;this.resume=resume;}
        @Override public int getIntrinsicWidth(){return dp(24);}
        @Override public int getIntrinsicHeight(){return dp(24);}
        @Override public void draw(android.graphics.Canvas canvas){
            android.graphics.Rect bounds=getBounds();canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);
            p.setColor(WHITE);p.setStrokeWidth(1.8f);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStyle(android.graphics.Paint.Style.STROKE);
            if(resume){p.setStyle(android.graphics.Paint.Style.FILL);android.graphics.Path triangle=new android.graphics.Path();triangle.moveTo(7,3);triangle.lineTo(20,12);triangle.lineTo(7,21);triangle.close();canvas.drawPath(triangle,p);canvas.restore();return;}
            canvas.drawRoundRect(9,2,15,14,3,3,p);canvas.drawArc(6,6,18,18,0,180,false,p);canvas.drawLine(12,18,12,22,p);canvas.drawLine(8,22,16,22,p);
            if(muted)canvas.drawLine(3,3,21,21,p);canvas.restore();
        }
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private void renderTranscript(){if(transcriptBody==null)return;transcriptBody.removeAllViews();if(transcript.isEmpty())transcriptBody.addView(text("No speech transcribed yet",12,SECONDARY));for(String line:transcript){TextView t=text(MessageText.plain(line),14,line.startsWith("You: ")?SECONDARY:WHITE);t.setGravity(Gravity.START);t.setPadding(0,dp(6),0,dp(6));transcriptBody.addView(t);}}
    private String callLimitNotice(long elapsedMs){if(elapsedMs<240000)return "";long seconds=Math.max(0,(300000-elapsedMs+999)/1000);return seconds>=60?"1 min left":seconds+"s left";}
    private void updateCallText(){
        if(callBot!=null&&!android.text.TextUtils.equals(callBot.getText(),botName))callBot.setText(botName);
        VoiceService.Snapshot snap=preview?null:VoiceService.snapshot();String phase=preview?screen:snap.state.toLowerCase(java.util.Locale.ROOT);
        if(callStatus!=null){String status=phase.equals("connecting")?(preview?"Calling…":snap.syncStatus.equals("Loading shared history")?"Loading chat…":"Connecting…"):phase.equals("reconnecting")?(preview?"Calling…":"Reconnecting…"):phase.equals("ending")?"Ending…":duration(preview?previewElapsed():snap.elapsedMs);if(phase.equals("muted"))status="Muted · "+status;if(phase.equals("paused"))status="Paused · "+status;long elapsed=preview?previewElapsed():snap.elapsedMs;long budgetElapsed=preview?previewElapsed():snap.deadlineElapsedMs>0?300000-Math.max(0,snap.deadlineElapsedMs-android.os.SystemClock.elapsedRealtime()):0;String warning=callLimitNotice(budgetElapsed);
            boolean showLimit=!warning.isEmpty()&&!phase.equals("connecting")&&!phase.equals("reconnecting")&&!phase.equals("ending");
            if(showLimit)status=(phase.equals("muted")?"Muted · ":phase.equals("paused")?"Paused · ":"")+duration(elapsed)+" · "+warning;
            // Snapshots arrive many times a second; only touch views whose content changed.
            float size=showLimit?(getResources().getConfiguration().fontScale>1.2f?11:12):13;if(size!=shownStatusSize){shownStatusSize=size;callStatus.setTextSize(size);}
            if(!android.text.TextUtils.equals(callStatus.getText(),status)){callStatus.setText(status);callStatus.setContentDescription("Voice chat status: "+status+(!preview&&snap.muted?" · Microphone muted":""));}
            if(waveform!=null)waveform.setVisibility(showLimit?View.GONE:View.VISIBLE);
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)callStatus.getLayoutParams();int margin=showLimit?0:dp(10);if(p.leftMargin!=margin){p.leftMargin=margin;callStatus.setLayoutParams(p);}
        }
        if(waveform!=null)waveform.state(phase,preview?(phase.equals("speaking")?.55f:phase.equals("listening")?.22f:0):snap.muted?0:screen.equals("speaking")?snap.outputLevel:snap.inputLevel);
        if(callMute!=null){boolean muted=preview?previewMuted:snap.muted;String muteLabel=phase.equals("paused")?"Resume":muted?"Unmute":"Mute";if(!muteLabel.contentEquals(callMute.getContentDescription())){callMute.setContentDescription(muteLabel);callMute.setForeground(new MicGlyph(muted,phase.equals("paused")));}int muteColor=phase.equals("paused")?BLUE:muted?0xff493725:GLASS;if(muteColor!=shownMuteColor){shownMuteColor=muteColor;callMute.setBackground(shape(muteColor,24));}callMute.setStateDescription(phase.equals("paused")?"Call paused. Tap to resume":muted?"Microphone muted. Bot audio stays on":"Microphone on");}
        // The on-screen line follows playback; the transcript below keeps the whole answer.
        String spoken=preview||captionLine.isEmpty()?caption:captionLine;
        if(callCaption!=null&&!spoken.equals(shownCaption)){shownCaption=spoken;callCaption.setText(MessageText.plain(spoken));callCaption.setVisibility(spoken.isEmpty()?View.GONE:View.VISIBLE);callCaption.setTextColor("You".equalsIgnoreCase(speaker)?SECONDARY:WHITE);callCaption.setContentDescription((speaker.isEmpty()?"":speaker+": ")+spoken);}
        if(!caption.isEmpty()&&!caption.equals(transcriptLast)){String who="You".equalsIgnoreCase(speaker)?"You":botName;String line=who+": "+caption;if(!transcript.isEmpty()&&transcript.get(transcript.size()-1).startsWith(who+": "))transcript.set(transcript.size()-1,line);else transcript.add(line);while(transcript.size()>40)transcript.remove(0);transcriptLast=caption;renderTranscript();}
        if(!caption.isEmpty()&&!callCompact&&orb!=null){callCompact=true;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)orb.getLayoutParams();p.width=dp(44);p.height=dp(44);p.topMargin=dp(side*.25f-22);orb.setLayoutParams(p);orb.setScaleX(64f/44);orb.setScaleY(64f/44);orb.animate().scaleX(1).scaleY(1).setDuration(android.animation.ValueAnimator.areAnimatorsEnabled()?520:0).start();moveCall(callBot,.385f);if(callStatus!=null)moveCall((View)callStatus.getParent(),.47f);}
    }
    private void moveCall(View v,float at){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)v.getLayoutParams();p.topMargin=dp(side*at-11);v.setLayoutParams(p);}
    @Override public boolean onGenericMotionEvent(android.view.MotionEvent event){
        if(event.getAction()==android.view.MotionEvent.ACTION_SCROLL&&event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)&&currentScroll!=null){
            float factor=android.view.ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            currentScroll.scrollBy(0,-Math.round(event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)*factor));return true;
        }
        return super.onGenericMotionEvent(event);
    }
    private void showCaption(){String full=caption;if(full.isEmpty())return;ScrollView scroll=new ScrollView(this);TextView t=text(MessageText.plain(full),14,"You".equalsIgnoreCase(speaker)?SECONDARY:WHITE);t.setPadding(dp(18),dp(12),dp(18),dp(12));scroll.addView(t);new android.app.AlertDialog.Builder(this).setTitle(speaker.isEmpty()?"Caption":speaker).setView(scroll).setPositiveButton("Close",(d,w)->{}).show();}
    private void message(String heading,String detail,String action,Runnable run){
        if(java.util.Set.of("error","dropped","offline").contains(screen)){BotAvatarView face=new BotAvatarView(this);applyAvatar(face,botName,selectedMetadata);face.state(screen.equals("error")?"error":"dropped",BLUE,0);face.running(visible);avatars.add(face);body.addView(face,new LinearLayout.LayoutParams(dp(40),dp(40)));}
        title(heading);label(detail,14,SECONDARY);gap(9);this.action("Back",()->goHome());edgeAction(action,run);}
    /** Primary action pinned above the bottom edge, like Talk: it can never scroll off a round screen. */
    private void edgeAction(String value,Runnable run){
        bottomAction=true;FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)currentScroll.getLayoutParams();sp.bottomMargin=dp(67);currentScroll.setLayoutParams(sp);
        Button primary=button(value,run);primary.setTextColor(Color.BLACK);primary.setBackground(shape(WHITE,28));
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(side*.62f),dp(54),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);ep.bottomMargin=dp(12);root.addView(primary,ep);
    }
    /** Real calls retry Talk directly; demo previews return to the Bot page. */
    private void talkAgain(){safeError="";screen="ready";render();if(!preview)talk();}
    private void feedback(String message){if(!visible)return;safeError=message;render();}
    private void goHome(){
        if(!preview&&VoiceService.snapshot().active){renderVoice();return;}
        historyRequest++;historyLoading=false;botListError="";caption="";speaker="";safeError="";
        if(!preview&&selectedMetadata.optString("id","").isEmpty()){finish();return;}
        screen="ready";render();
    }
    private void legacyDone(){
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER);
        BotAvatarView face=new BotAvatarView(this);applyAvatar(face,botName,selectedMetadata);avatars.add(face);face.state("done",BLUE,0);face.running(visible);heading.addView(face,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView title=text("Call ended",14,WHITE);title.setSingleLine(true);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));body.addView(heading,new LinearLayout.LayoutParams(-1,dp(28)));
        Button again=action("Talk again",()->talk());again.setEnabled(!historyStaging());((LinearLayout.LayoutParams)again.getLayoutParams()).topMargin=dp(3);Button change=action("Switch Bot",()->openBotPicker());((LinearLayout.LayoutParams)change.getLayoutParams()).topMargin=dp(3);
        gap(4);if(preview)label("Demo · 1:42",12,SECONDARY);else{long ms=VoiceService.snapshot().elapsedMs;if(ms>0)label(String.format(java.util.Locale.ROOT,"%d:%02d",ms/60000,(ms/1000)%60),12,SECONDARY);}
        String sync=preview?(previewSaving?"Saving shared history":""):VoiceService.snapshot().syncStatus;
        if(!sync.isEmpty())label(sync.equals("Saving shared history")?"Saving shared history…":sync.equals("synced")?"Shared history saved":(sync.equals("uncertain")||sync.equals("pending"))?"Voice sync pending":sync.equals("rejected")?"History could not be saved":sync,12,SECONDARY);
        action("Back",()->goHome());
    }
    private boolean historyStaging(){return preview?previewSaving:VoiceService.historyStaging();}
    private boolean isHistory(){return screen.equals("history")||screen.equals("history_detail");}
    private void clearHistory(){historyRequest++;historyLoading=false;historyStale=false;historyHasMore=false;historyBefore=null;historyBinding=null;historyDetail=null;
        historyEntries=new JSONArray();historyError="";historyAgent="";historyAccount="";historyPages=0;pendingHistory=0;}
    private void openHistory(){
        if(!preview&&VoiceService.snapshot().active){renderVoice();return;}
        if(preview){previewHistory("history");render();return;}
        String id=selectedMetadata.optString("id","");if(id.isEmpty()){openBotPicker();return;}
        if(!id.equals(historyAgent)||!botsAccountBinding.equals(historyAccount))clearHistory();
        historyAgent=id;historyAccount=botsAccountBinding;screen="history";loadHistory(false);
    }
    private void historyHeader(String heading,Runnable back){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        Button b=button("Back",back);b.setText("‹");b.setTextSize(24);b.setPadding(0,0,0,0);bar.addView(b,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setGravity(Gravity.CENTER_VERTICAL);TextView title=text(heading,16,WHITE);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setIncludeFontPadding(false);titles.addView(title);TextView bot=text(botName,10,SECONDARY);bot.setSingleLine(true);bot.setEllipsize(android.text.TextUtils.TruncateAt.END);bot.setIncludeFontPadding(false);bot.setContentDescription("History for Bot: "+botName);titles.addView(bot);bar.addView(titles,new LinearLayout.LayoutParams(0,-1,1));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(side*.76f),dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);hp.topMargin=dp(23);root.addView(bar,hp);
        FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)currentScroll.getLayoutParams();sp.topMargin=dp(73);sp.bottomMargin=dp(14);currentScroll.setLayoutParams(sp);currentScroll.setVerticalScrollBarEnabled(true);
        body.setPadding(dp(side*.12f),dp(2),dp(side*.12f),dp(30));
    }
    private void history(){
        historyHeader("History",()->goHome());
        if(historyLoading)label("Loading history…",12,SECONDARY);
        if(pendingHistory>0)label("Voice sync pending · "+pendingHistory,12,SECONDARY);
        if(historyStale)label("Showing last loaded messages",12,SECONDARY);
        if(!historyError.isEmpty())label(historyError,12,SECONDARY);
        if(!historyLoading&&historyEntries.length()==0&&historyError.isEmpty())label("No messages yet",14,SECONDARY);
        for(int i=historyEntries.length()-1;i>=0;i--){JSONObject entry=historyEntries.optJSONObject(i);if(entry==null)continue;
            String role=entry.optString("role"),who=role.equals("user")?"You":role.equals("assistant")?botName:"Conversation";
            String full=MessageText.plain(entry.optString("text",""));if(full.isEmpty())continue;
            Button row=action(who+": "+full,()->{navigation++;historyRequest++;historyLoading=false;historyDetail=entry;screen="history_detail";render();});
            row.setContentDescription("Message from "+who+": "+full);row.setTextColor(WHITE);row.setBackground(shape(role.equals("user")?userBubble():CARD,14));row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            row.setText((role.equals("assistant")?"Bot":who)+historyTime(entry)+"\n"+full);row.setMaxLines(3);row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams rp=(LinearLayout.LayoutParams)row.getLayoutParams();rp.height=dp(getResources().getConfiguration().fontScale>1.2f?84:72);row.setLayoutParams(rp);
        }
        label(historyBinding!=null&&!historyBinding.optString("sessionId").isEmpty()?"Selected conversation":"Main conversation",12,SECONDARY);
        Button refresh=action("Refresh",()->loadHistory(false));refresh.setEnabled(!historyLoading);
        if(historyHasMore&&historyPages<5){Button older=action("Load older",()->loadHistory(true));older.setEnabled(!historyLoading);}
        else if(historyHasMore)label("Showing the latest 100 messages",12,SECONDARY);
    }
    private String historyTime(JSONObject entry){long at=entry.optLong("atMs",0);return at>0?" · "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at)):"";}
    private void historyDetail(){
        historyHeader("Message",()->{screen="history";render();});
        JSONObject entry=historyDetail==null?new JSONObject():historyDetail;String role=entry.optString("role"),who=role.equals("user")?"You":role.equals("assistant")?botName:"Conversation";
        label((role.equals("user")?"You · ":"")+botName+historyTime(entry),12,SECONDARY);TextView message=label(MessageText.plain(entry.optString("text","Message unavailable")),16,WHITE);message.setText(rich(entry.optString("text","Message unavailable")));
        message.setGravity(Gravity.START);message.setTextIsSelectable(true);message.setContentDescription("Full message from "+who+": "+entry.optString("text"));
    }
    private boolean historyCurrent(int request,int mine,int route,String id,String binding){
        if(!visible||request!=historyRequest||mine!=epoch||route!=navigation||!(screen.equals("history")||screen.equals("ready")))return false;
        if(!preview&&VoiceService.snapshot().active){renderVoice();return false;}
        return id.equals(selectedMetadata.optString("id"))&&binding.equals(botsAccountBinding);
    }
    private void loadHistory(boolean older){
        if(preview){if(older){try{JSONArray next=new JSONArray();next.put(new JSONObject().put("id","demo-old").put("role","assistant").put("text","Earlier message from this Bot."));for(int i=0;i<historyEntries.length();i++)next.put(historyEntries.get(i));historyEntries=next;}catch(Exception ignored){}historyHasMore=false;historyPages++;historyLoading=false;render();}
            else{previewHistory("history");render();}return;}
        if(VoiceService.snapshot().active){renderVoice();return;}if(historyLoading)return;
        final String id=selectedMetadata.optString("id","");if(id.isEmpty()){openBotPicker();return;}
        final String binding=botsAccountBinding;final JSONObject prior=historyBinding;final Long before=older?historyBefore:null;
        if(older&&(before==null||historyPages>=5))return;
        final int request=++historyRequest,mine=epoch,route=navigation;historyLoading=true;historyError="";if(!screen.equals("ready"))screen="history";render();
        worker.execute(()->{try{
            JSONObject pinned=older&&prior!=null?prior:account.historyBinding();
            if(!id.equals(pinned.optString("agentId"))||!binding.equals(pinned.optString("accountKey")+"/"+pinned.optString("authGeneration")))throw new IllegalStateException();
            // Show the saved chat at once; the network refresh below replaces it in place.
            if(!older){JSONObject saved=null;try{saved=account.savedHistory(pinned);}catch(Exception unavailable){}
                if(saved!=null){final JSONObject show=saved;main.post(()->{if(!historyCurrent(request,mine,route,id,binding)||historyEntries.length()>0)return;
                    try{if(!sameHistoryBinding(pinned,show.getJSONObject("binding")))return;historyEntries=show.getJSONArray("entries");historyBinding=show.getJSONObject("binding");
                        historyHasMore=show.optBoolean("hasMore");historyBefore=show.isNull("nextBeforeSeq")?null:show.getLong("nextBeforeSeq");historyPages=1;render();}catch(Exception ignored){}});}}
            JSONObject page=account.historyPage(pinned,before,20);
            // A dropped result still ends the load, or the chat could never refresh again until the app pauses.
            main.post(()->{if(!historyCurrent(request,mine,route,id,binding)){if(request==historyRequest)historyLoading=false;return;}
                try{JSONObject accepted=page.getJSONObject("binding");if(!sameHistoryBinding(pinned,accepted))throw new IllegalStateException();
                    JSONArray fetched=page.getJSONArray("entries"),merged=new JSONArray();java.util.HashSet<String> seen=new java.util.HashSet<>();
                    if(older){for(int i=0;i<fetched.length();i++){JSONObject row=fetched.optJSONObject(i);if(row!=null&&seen.add(row.optString("id")))merged.put(row);}
                        for(int i=0;i<historyEntries.length();i++){JSONObject row=historyEntries.optJSONObject(i);if(row!=null&&seen.add(row.optString("id")))merged.put(row);}}
                    else merged=fetched;
                    historyEntries=merged;historyBinding=accepted;historyHasMore=page.optBoolean("hasMore");historyBefore=page.isNull("nextBeforeSeq")?null:page.getLong("nextBeforeSeq");
                    historyPages=older?historyPages+1:1;historyLoading=false;historyStale=page.optBoolean("stale");pendingHistory=Math.max(0,page.optInt("pendingCount"));
                    historyError="offline".equals(page.optString("syncStatus"))?"Couldn’t refresh history.":"partial".equals(page.optString("syncStatus"))?"Some voice messages couldn’t load.":"";render();
                    if(historyError.isEmpty()&&takeOwnHistoryRefresh(VoiceService.snapshot().active,historyStaging()))loadBotChat();
                }catch(Exception ignored){historyFailure();}});
        }catch(Exception ignored){main.post(()->{if(historyCurrent(request,mine,route,id,binding))historyFailure();else if(request==historyRequest)historyLoading=false;});}});
    }
    private static boolean sameHistoryBinding(JSONObject a,JSONObject b){
        for(String key:new String[]{"accountKey","authGeneration","agentId","sessionId"})if(!a.optString(key).equals(b.optString(key)))return false;return true;
    }
    private void historyFailure(){historyLoading=false;historyStale=historyEntries.length()>0;historyError="Couldn’t load messages. They’ll refresh when you return.";render();}
    private void previewHistory(String variant){
        screen=variant.equals("history_detail")?"history_detail":"history";historyLoading=variant.equals("history_loading");historyStale=variant.equals("history_stale");
        pendingHistory=variant.equals("history_pending")?1:0;historyError=variant.equals("history_error")?"Couldn’t load history. Try Refresh.":historyStale?"Couldn’t refresh history.":"";
        historyPages=1;historyHasMore=!historyLoading&&!variant.equals("history_empty")&&!variant.equals("history_error");historyBefore=100L;historyEntries=new JSONArray();
        try{if(!historyLoading&&!variant.equals("history_empty")&&!variant.equals("history_error")){
            historyEntries.put(new JSONObject().put("id","demo-user").put("role","user").put("text","What is next on my calendar?").put("atMs",1000));
            historyEntries.put(new JSONObject().put("id","demo-bot").put("role","assistant").put("text","Your next meeting is at ten.").put("atMs",2000));}
            historyDetail=historyEntries.optJSONObject(1);
        }catch(Exception ignored){}
    }
    private void recent(){title("Chats");if(preview){label("Demo chats",12,SECONDARY);action("Voice chat ended · 1:42",()->setPreviewScreen("done"));action("Async tasks · Subagent",()->{screen="task";render();});}
        else label("Chat history isn’t synced to this watch.",14,SECONDARY);action("Back",()->goHome());}
    private void openSettings(){settingsReturnScreen=screen.equals("ready")||screen.equals("done")?"ready":"bots";screen="settings";render();}
    private void leaveSettings(){screen=settingsReturnScreen.equals("ready")?"ready":"bots";render();}
    private void settings(){title("Settings");label("Unofficial Grok client",12,SECONDARY);label("Not affiliated with or endorsed by xAI or Cursor.",12,SECONDARY);gap(4);label("5-minute calls · tasks go to your Bot",12,SECONDARY);label("Calls share this Bot’s main chat.",12,SECONDARY);
        action("Back",()->leaveSettings());gap(24);
        action("Sign out…",()->{screen="signout_confirm";render();});
    }
    private void confirmSignOut(){
        if(!preview&&SIGN_OUT_ACTIVE.get()){title("Signing out…");label("Removing this watch’s sign-in and local Grok data.",13,SECONDARY);return;}
        title("Sign out?");label("Remove this watch’s Grok sign-in and local Grok data? Your Bots and chats stay in your Grok account.",13,SECONDARY);
        action("Cancel",()->{screen="settings";render();});gap(18);
        Button signOut=action("Sign out",()->beginSignOut());signOut.setTextColor(RED);
    }
    private void beginSignOut(){
        if(preview){screen="welcome";render();return;}
        if(!SIGN_OUT_ACTIVE.compareAndSet(false,true))return;
        signOutFailed=false;epoch++;polling=false;main.removeCallbacks(pollTick);clearBotCache();screen="signout_confirm";render();
        final BotAccount signingOutAccount=account;
        // Only local encrypted writes: do not queue behind HTTP, or cancel on Activity rotation.
        new Thread(()->{try{VoiceService.stop(getApplicationContext());signingOutAccount.unlink();}catch(Exception failure){signOutFailed=true;}finally{SIGN_OUT_ACTIVE.set(false);main.post(()->{if(visible&&!isDestroyed())finishSignOut();});}},"grok-local-signout").start();
    }
    private void finishSignOut(){
        main.removeCallbacks(signOutObserver);epoch++;clearBotCache();
        if(signOutFailed){safeError="Sign-out couldn’t finish. Check your account state before trying again.";screen="account_error";render();}
        else{screen="welcome";render();loadAccount();}
    }
    private void approval(){title("Needs your approval");label(preview?"Demo request · calendar change":"Approvals aren’t available on this watch yet. Approve them on your phone or desktop.",14,SECONDARY);
        if(preview){action("Approve",()->{goHome();feedback("Demo only · no action sent");});action("Deny",()->{goHome();feedback("Demo dismissed");});}
        else action("Back",()->goHome());}
    private void task(){label("Async tasks · Subagent",12,SECONDARY);title("Needs attention");label(preview?"Demo task · a calendar change needs review":"Tasks aren’t available on this watch yet.",14,SECONDARY);
        if(preview)action("Review request",()->setPreviewScreen("approval"));action("Chats",()->{screen="recent";render();});action("Back",()->goHome());}
    private void talk(){if(botSelectionPending)return;if(historyStaging()){render();return;}if(!preview&&VoiceService.snapshot().active){renderVoice();return;}transcript.clear();transcriptLast="";micTicked=false;if(preview){demo();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            clearPendingTalk();pendingTalkIdentity=currentConversationIdentity();pendingTalkAt=android.os.SystemClock.elapsedRealtime();pendingTalkNavigation=navigation;awaitingMicrophonePermission=true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},17);return;
        }
        // No network: say so now, instead of a long "Loading chat" that can only fail.
        if(!VoiceService.online(this)){clearPendingTalk();safeError="";screen="offline";render();return;}
        clearPendingTalk();final String callIdentity=currentConversationIdentity();if(!VoiceService.start(this)){safeError="Tap Talk while your watch is unlocked.";screen="ready";render();}else{receiptIdentity=callIdentity;receiptElapsed=0;receiptSync="";receiptRefreshPending=true;historyRequest++;historyLoading=false;renderVoice();}}
    private void clearPendingTalk(){main.removeCallbacks(finishGrantedTalk);pendingTalkIdentity="";pendingTalkAt=0;pendingTalkGrantedAt=0;awaitingMicrophonePermission=false;pendingTalkGranted=false;}
    private boolean pendingTalkCurrent(){return !pendingTalkIdentity.isEmpty()&&pendingTalkIdentity.equals(currentConversationIdentity())&&pendingTalkNavigation==navigation&&android.os.SystemClock.elapsedRealtime()-pendingTalkAt<=60000&&(screen.equals("ready")||screen.equals("bots"));}
    private void resumeGrantedTalk(){
        if(!pendingTalkGranted)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(!pendingTalkCurrent()||now-pendingTalkGrantedAt>2000||VoiceService.snapshot().active){clearPendingTalk();return;}
        android.os.PowerManager power=getSystemService(android.os.PowerManager.class);android.app.KeyguardManager keyguard=getSystemService(android.app.KeyguardManager.class);
        if(visible&&hasWindowFocus()&&getWindow().getDecorView().isShown()&&power.isInteractive()&&!keyguard.isKeyguardLocked()){
            // Resume only this one explicit Talk after its permission dialog; never from onResume.
            clearPendingTalk();talk();
        }else main.postDelayed(finishGrantedTalk,100);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);
        if(request!=17||preview)return;
        boolean granted=false;for(int i=0;i<Math.min(permissions.length,results.length);i++)if(Manifest.permission.RECORD_AUDIO.equals(permissions[i])&&results[i]==PackageManager.PERMISSION_GRANTED)granted=true;
        if(!awaitingMicrophonePermission||!pendingTalkCurrent()){clearPendingTalk();return;}
        awaitingMicrophonePermission=false;
        if(granted){pendingTalkGranted=true;pendingTalkGrantedAt=android.os.SystemClock.elapsedRealtime();main.post(finishGrantedTalk);}
        else{clearPendingTalk();safeError="Microphone permission is needed. Enable it in watch settings, then tap Talk.";screen="ready";render();}
    }
    private void toggleMute(){if(preview){previewEpoch++;simulated=false;if(screen.equals("paused")){screen="listening";previewMuted=false;}else{previewMuted=!previewMuted;screen=previewMuted?"muted":"listening";}render();}else VoiceService.toggleMute(this);}
    private void endCall(){root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);if(preview){previewEpoch++;simulated=false;previewMuted=false;caption="";screen="done";render();}else{VoiceService.stop(this);screen="ready";render();}}
    private void demo(){int mine=++previewEpoch;simulated=true;previewMuted=false;previewCallStartMs=0;screen="connecting";caption="";render();String[] states={"listening","thinking","speaking","listening","thinking","speaking","listening","thinking","speaking","speaking","done"};for(int i=0;i<states.length;i++){final int turn=i;main.postDelayed(()->{if(!visible||!preview||mine!=previewEpoch)return;screen=states[turn];if(previewCallStartMs==0&&screen.equals("listening"))previewCallStartMs=android.os.SystemClock.elapsedRealtime();caption=turn==states.length-2?"Goodbye, talk soon.":screen.equals("thinking")?"What is next on my calendar?":screen.equals("speaking")?"Your next meeting is at ten.":"";speaker=screen.equals("thinking")?"You":botName;render();},1300L+i*1400L);}}  // Listening at 1.3 s: the measured warm Talk to Listening, not faster.
    private void recover(){if(preview){setPreviewScreen("ready");}else{safeError="";loadAccount();}}
    private void renderVoice(){if(preview)return;VoiceService.Snapshot s=VoiceService.snapshot();String previous=screen;
        if(s.active){if(!micTicked&&!s.state.equals("Connecting")&&!s.state.equals("Ending")){micTicked=true;root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);}botName=s.botName.isEmpty()?botName:s.botName;caption=s.caption;captionLine=s.captionLine;speaker=s.speaker;screen=s.muted?"muted":s.state.toLowerCase(java.util.Locale.ROOT);if(!isCall())screen="connecting";}
        else{safeError=s.error;screen="Call dropped".equals(s.state)?"dropped":s.error.isEmpty()?(java.util.Set.of("connecting","listening","thinking","speaking","muted","paused","reconnecting","done").contains(previous)?"ready":"ready"):"error";caption="";captionLine="";}
        if(isCall()&&isCallScreen(previous)&&orb!=null){
            orb.state(screen,accent(),screen.equals("speaking")?s.outputLevel:s.inputLevel);updateCallText();
        }else render();
    }
    private boolean isCallScreen(String value){return java.util.Set.of("connecting","listening","thinking","speaking","muted","paused","reconnecting").contains(value);}
    private interface Fetch{JSONObject run()throws Exception;}
    private interface Result{void apply(JSONObject value)throws Exception;}
    private void task(Fetch fetch,Result result){int mine=epoch,route=navigation;worker.execute(()->{try{JSONObject value=fetch.run();main.post(()->{if(!visible||mine!=epoch||route!=navigation)return;if(VoiceService.snapshot().active){renderVoice();return;}try{result.apply(value);}catch(Exception ignored){showSafeError();}});}catch(Exception ignored){main.post(()->{if(visible&&mine==epoch&&route==navigation){if(VoiceService.snapshot().active)renderVoice();else showSafeError();}});}});}
    private void showSafeError(){polling=false;main.removeCallbacks(pollTick);safeError="Couldn’t connect to Grok. Check your connection or start a new sign-in.";screen="account_error";render();}
    private boolean accountResultCurrent(int route){
        if(route!=navigation)return false;
        if(VoiceService.snapshot().active){renderVoice();return false;}
        return true;
    }
    private void clearBotCache(){cancelRosterRetry();rosterRetryUsed=false;clearPendingTalk();receiptIdentity="";receiptSync="";receiptElapsed=0;receiptRefreshPending=false;clearHistory();botRequest++;botsLoading=false;rosterFetchActive=false;activeRosterRequest=0;bots=new JSONArray();botPreferences=new JSONObject();botsCached=false;botsFetchedAtMs=0;lastBotRefreshAttemptElapsed=0;botsAccountBinding="";selectedMetadata=new JSONObject();botName="Your Bot";}
    private void acceptAccountBot(JSONObject bot){
        // A resumed detail belongs to its captured account, Bot and viewer conversation.
        String session=bot.optBoolean("viewerIsOwner",true)?"":bot.optString("viewerSessionId","");
        boolean retained=isHistory()&&historyBinding!=null
            &&botsAccountBinding.equals(historyBinding.optString("accountKey")+"/"+historyBinding.optString("authGeneration"))
            &&bot.optString("id").equals(historyBinding.optString("agentId"))
            &&session.equals(historyBinding.optString("sessionId"));
        if(isHistory()&&!retained){clearHistory();screen="history";}
        selectedMetadata=bot;botName=bot.optString("name","Your Bot");
    }
    private void loadAccount(){int route=navigation;task(()->account.authState(),state->{
        if(!accountResultCurrent(route))return;String status=state.optString("status","unlinked");
        if(status.equals("ready")){
            String binding=state.optString("account_key","")+"/"+state.optString("auth_generation","");
            if(!binding.equals(botsAccountBinding)){clearBotCache();botsAccountBinding=binding;}
            task(()->{
                JSONObject bot;try{bot=account.selectedBot();}catch(Exception interruptedBinding){account.pollLogin();bot=account.selectedBot();}
                // Both reads are local. Never wait for roster HTTP before restoring saved rows.
                JSONObject roster=account.cachedRoster();
                return new JSONObject().put("bot",bot).put("roster",roster).put("identity",accountIdentity(account.authState()));
            },value->{
                if(!accountResultCurrent(route)||!binding.equals(value.optString("identity")))return;
                JSONObject cached=value.getJSONObject("roster");
                if(cached.optBoolean("present")&&(!botsCached||cached.optLong("fetchedAtMs")>=botsFetchedAtMs)){
                    bots=cached.getJSONArray("bots");botPreferences=cached.getJSONObject("preferences");botsCached=true;botsFetchedAtMs=cached.optLong("fetchedAtMs");
                }
                JSONObject bot=value.getJSONObject("bot");acceptAccountBot(bot);
                account.recoverPendingInBackground(binding,bot.optString("id"));
                if(bot.optString("id","").isEmpty()||screen.equals("bots"))openBotPicker();
                else if(isHistory()){if(screen.equals("history_detail"))render();else loadHistory(false);}else if(screen.equals("settings")||screen.equals("signout_confirm")||screen.equals("reader")||screen.startsWith("compose_"))render();else if(screen.equals("ready")){render();loadBotChat();}else openBotPicker();
            });
        }else if(status.equals("connecting")){task(()->account.pollLogin(),v->{if(accountResultCurrent(route))loadAccount();});}
        else if(status.equals("waiting")){screen="qr";render();schedulePoll();}
        else if(status.equals("unlinked")){clearBotCache();screen="welcome";render();}
        else{safeError="Sign-in needs attention. Start a new sign-in.";screen="account_error";render();}
    });}
    private void openBotPicker(){
        if(!preview&&VoiceService.snapshot().active){renderVoice();return;}
        screen="bots";render();if(!preview)refreshBots(false);
    }
    private String accountIdentity(JSONObject state){return state.optString("account_key","")+"/"+state.optString("auth_generation","");}
    private void loadBots(){rosterRetryUsed=false;cancelRosterRetry();refreshBots(true);}
    private void refreshBots(boolean explicit){
        if(preview||botSelectionPending)return;
        if(VoiceService.snapshot().active){renderVoice();return;}
        if(rosterFetchActive)return;
        long now=System.currentTimeMillis(),elapsed=android.os.SystemClock.elapsedRealtime();
        boolean fresh=botsCached&&botsFetchedAtMs>0&&now>=botsFetchedAtMs&&now-botsFetchedAtMs<BOT_REFRESH_INTERVAL_MS;
        boolean recentlyAttempted=lastBotRefreshAttemptElapsed>0&&elapsed-lastBotRefreshAttemptElapsed<BOT_REFRESH_INTERVAL_MS;
        if(!explicit&&(fresh||recentlyAttempted)){refreshPins(false);return;}
        boolean hadError=!botListError.isEmpty();
        cancelRosterRetry();lastBotRefreshAttemptElapsed=elapsed;rosterFetchActive=true;botsLoading=true;botListError="";
        if(!botsCached||hadError)renderBotListPreservingScroll();
        final int request=++botRequest;activeRosterRequest=request;final String expected=botsAccountBinding;
        rosterWorker.execute(()->{try{
            if(!expected.equals(accountIdentity(account.authState())))throw new IllegalStateException();
            JSONArray found=account.bots();JSONObject saved=account.cachedRoster();
            if(!expected.equals(accountIdentity(account.authState())))throw new IllegalStateException();
            // bots() saves the full sanitized roster before this UI callback, even if navigation changed.
            main.post(()->{if(activeRosterRequest==request)rosterFetchActive=false;applyBotRefresh(request,expected,found);if(currentBotRefresh(request,expected))botsFetchedAtMs=saved.optLong("fetchedAtMs");});
            preferencesWorker.execute(()->{try{
                if(!expected.equals(accountIdentity(account.authState())))return;
                JSONObject prefs=account.botPreferences();
                if(!expected.equals(accountIdentity(account.authState())))return;
                main.post(()->applyBotPreferences(request,expected,prefs));
            }catch(Exception optionalUnavailable){}});
        }catch(Exception ignored){main.post(()->{if(activeRosterRequest==request)rosterFetchActive=false;applyBotRefreshFailure(request,expected);});}});
    }
    private boolean currentBotRefresh(int request,String expected){return request==botRequest&&!expected.isEmpty()&&expected.equals(botsAccountBinding);}
    private void applyBotRefresh(int request,String expected,JSONArray found){
        if(!currentBotRefresh(request,expected))return;
        boolean changed=!botsCached||!bots.toString().equals(found.toString())||!botListError.isEmpty();
        cancelRosterRetry();bots=found;botsCached=true;botsFetchedAtMs=System.currentTimeMillis();botsLoading=false;botListError="";
        if(changed)renderBotListPreservingScroll();
    }
    private void applyBotPreferences(int request,String expected,JSONObject prefs){
        if(!currentBotRefresh(request,expected))return;
        boolean changed=!botPreferences.toString().equals(prefs.toString());botPreferences=prefs;
        if(changed)renderBotListPreservingScroll();
    }
    private void applyBotRefreshFailure(int request,String expected){
        if(!currentBotRefresh(request,expected))return;botsLoading=false;
        // Keep saved rows usable. One foreground retry can recover a transient slow link.
        botListError=botsCached?"Showing saved Bots":"Bots aren’t available yet. Trying again shortly.";renderBotListPreservingScroll();
        if(!rosterRetryUsed&&visible&&screen.equals("bots")){
            rosterRetryUsed=true;rosterRetry=()->{rosterRetry=null;if(visible&&screen.equals("bots")&&currentBotRefresh(request,expected)&&!botSelectionPending&&!VoiceService.snapshot().active)refreshBots(true);};main.postDelayed(rosterRetry,15000);
        }else if(!botsCached){botListError="Bots aren’t available yet. Try again later.";renderBotListPreservingScroll();}
    }
    private void cancelRosterRetry(){if(rosterRetry!=null)main.removeCallbacks(rosterRetry);rosterRetry=null;}
    private void renderBotListPreservingScroll(){
        if(!visible||!screen.equals("bots")||VoiceService.snapshot().active)return;
        int y=currentScroll==null?0:currentScroll.getScrollY();render();final ScrollView restored=currentScroll;
        restored.post(()->{if(currentScroll==restored&&screen.equals("bots"))restored.scrollTo(0,y);});
    }
    private void chooseBot(String id){chooseBot(id,false);}
    private void chooseBot(String id,boolean startAfter){
        if(botSelectionPending)return;if(VoiceService.snapshot().active){renderVoice();return;}
        botSelectionPending=true;render();int mine=epoch,route=navigation;final long tappedAt=android.os.SystemClock.elapsedRealtime();
        worker.execute(()->{try{
            if(VoiceService.snapshot().active)throw new IllegalStateException();
            JSONObject chosen=account.chooseBot(id);
            main.post(()->{botSelectionPending=false;if(!visible||mine!=epoch)return;
                if(VoiceService.snapshot().active){renderVoice();return;}clearHistory();selectedMetadata=chosen;botName=chosen.optString("name","Your Bot");if(screen.equals("bots")||screen.equals("ready")){goHome();if(startAfter&&route==navigation&&android.os.SystemClock.elapsedRealtime()-tappedAt<=5000&&hasWindowFocus())talk();else loadBotChat();}else render();});
        }catch(Exception ignored){main.post(()->{botSelectionPending=false;if(!visible||mine!=epoch)return;
            if(VoiceService.snapshot().active){renderVoice();return;}botListError="Couldn’t switch Bots. Try again.";render();});}});
    }
    private void beginLogin(){if(preview){setPreviewScreen("qr");return;}clearBotCache();epoch++;polling=false;main.removeCallbacks(pollTick);task(()->account.beginLogin(),value->{loginAttempt=value.optString("login_attempt","");screen="qr";render();schedulePoll();});}
    private void cancelLogin(){if(preview){setPreviewScreen("welcome");return;}final String attempt=loginAttempt;if(attempt.isEmpty())return;epoch++;polling=false;main.removeCallbacks(pollTick);task(()->{account.cancelLogin(attempt);return new JSONObject();},value->{screen="welcome";render();});}
    private void schedulePoll(){if(!visible||preview||!screen.equals("qr"))return;polling=true;main.removeCallbacks(pollTick);main.postDelayed(pollTick,3000);}
    private void pollLogin(){if(preview||!visible||!polling||!screen.equals("qr"))return;task(()->account.pollLogin(),value->{if("waiting".equals(value.optString("status")))schedulePoll();else{polling=false;loadAccount();}});}
}
