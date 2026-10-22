package si.zya.grokwear;

import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Scripted native protocol, fake backend and in-memory PCM only: no account/network/microphone. */
public final class NativeVoiceSessionTest {
    static final class Fixture implements AutoCloseable,NativeVoiceSession.Listener {
        final BlockingQueue<String> callbacks=new LinkedBlockingQueue<>();
        final BlockingQueue<String> captions=new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> sent=new LinkedBlockingQueue<>();
        final List<JSONObject> tools=new CopyOnWriteArrayList<>();
        final AtomicInteger ready=new AtomicInteger(),audioCount=new AtomicInteger(),backendCalls=new AtomicInteger(),completions=new AtomicInteger(),failures=new AtomicInteger(),disconnections=new AtomicInteger();
        volatile byte[] heard;volatile boolean failLookup,cancelled,unheard;volatile long queued;
        volatile NativeVoiceSession.WireEvents events;
        volatile CountDownLatch lookupEntered,lookupRelease;
        final NativeVoiceSession session;JSONObject settings;volatile String toolKind="served";
        Fixture()throws Exception {this(new JSONArray());}
        Fixture(JSONArray history)throws Exception {this(history,"");}
        Fixture(JSONArray history,String greeting)throws Exception {this(history,greeting,false,false,()->{});}
        Fixture(JSONArray history,String greeting,boolean prepared,boolean expired,NativeVoiceSession.StartupGuard guard)throws Exception {
            this(history,greeting,prepared,expired,guard,false);
        }
        Fixture(JSONArray history,String greeting,boolean prepared,boolean expired,NativeVoiceSession.StartupGuard guard,boolean deferred)throws Exception {
            NativeVoiceSession.Backend backend=(method,body)->{
                backendCalls.incrementAndGet();
                if(method.equals("VoiceCallHarnessSession")){JSONArray offered=new JSONArray();for(String name:new String[]{"recall_text_messages","search_conversations","send_task","end_the_call"})offered.put(new JSONObject().put("type","function").put("name",name));
                    return new JSONObject().put("instructions","Talk to this Bot").put("tools",offered).put("greeting",new JSONObject().put("direction",greeting));}
                if(method.equals("MintSandVoiceCallSecret"))return new JSONObject().put("clientSecret","fake-public-test").put("model","fake-model").put("expiresAtUnixSeconds",System.currentTimeMillis()/1000+600).put("websocketUrl","wss://api.x.ai/test-only");
                tools.add(new JSONObject(body.toString()));
                if(lookupEntered!=null)lookupEntered.countDown();if(lookupRelease!=null)lookupRelease.await(3,TimeUnit.SECONDS);
                if(failLookup)throw new Exception("offline fixture failure");
                return new JSONObject().put("kind",toolKind).put("output",new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("text","found it"))));
            };
            NativeVoiceSession.WireFactory factory=(url,secret,e)->{events=e;e.opened();return new NativeVoiceSession.Wire(){
                public boolean send(String text){try{sent.add(new JSONObject(text));return true;}catch(Exception ex){throw new AssertionError(ex);}}
                public long queueSize(){return queued;}public void cancel(){cancelled=true;}
            };};
            NativeVoiceSession.PcmCodec codec=new NativeVoiceSession.PcmCodec(){public String encode(byte[] bytes){return Base64.getEncoder().encodeToString(bytes);}public byte[] decode(String text){return Base64.getDecoder().decode(text);}};
            session=new NativeVoiceSession(backend,nextBot!=null?nextBot:new JSONObject().put("id","bot-test").put("name","Test Bot"),factory,codec,this);nextBot=null;
            if(nextGraceMs>0){session.greetingGraceMs=nextGraceMs;nextGraceMs=0;}
            session.setHistory(history);if(deferred)session.deferHistory();
            if(prepared)session.preparedStartup(new JSONObject().put("instructions","Prepared harness").put("tools",new JSONArray()).put("greeting",new JSONObject().put("direction","Say hello")),
                new JSONObject().put("clientSecret","prepared-public-test").put("model","fake-model").put("expiresAtUnixSeconds",expired?1:System.currentTimeMillis()/1000+600).put("websocketUrl","wss://api.x.ai/test-only"),guard);
            session.start();if(expired){callback("failure");return;}settings=frame("session.update");assertTrue(settings.getJSONObject("session").getJSONObject("resumption").getBoolean("enabled"));
        }
        void receive(String type)throws Exception{events.message(new JSONObject().put("type",type).toString());}
        void ready()throws Exception{receive("session.created");receive("session.updated");callback("ready");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!session.isLive()&&System.nanoTime()<until)Thread.sleep(2);assertTrue("Settings never confirmed",session.isLive());}
        void tool(String id,String name)throws Exception{events.message(new JSONObject().put("type","response.function_call_arguments.done").put("call_id",id).put("name",name).put("arguments","{}").toString());}
        JSONObject frame(String type)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){JSONObject frame=sent.poll(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS);if(frame==null)break;if(type.equals(frame.optString("type")))return frame;}throw new AssertionError("Missing frame "+type);}
        void callback(String expected)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){String event=callbacks.poll(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS);if(expected.equals(event))return;if(event==null)break;}throw new AssertionError("Missing callback "+expected);}
        void barrier()throws Exception{String marker="barrier-"+UUID.randomUUID();events.message(new JSONObject().put("type","conversation.item.input_audio_transcription.completed").put("transcript",marker).toString());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){String text=captions.poll(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS);if(marker.equals(text))return;if(text==null)break;}throw new AssertionError("Missing ordered barrier");}
        public void onState(String state){callbacks.add(state);}public void onAudio(byte[] bytes){heard=bytes.clone();audioCount.incrementAndGet();callbacks.add("audio");}
        public void onCaption(String who,String text){captions.add(text);callbacks.add("caption");}public void onSpeechStarted(){callbacks.add("speech");}
        public void onComplete(String reason){completions.incrementAndGet();callbacks.add("complete");}public void onFailure(String safe){failures.incrementAndGet();callbacks.add("failure");}
        public void onDisconnected(String safe){disconnections.incrementAndGet();callbacks.add("disconnected");}
        public void onReady(){ready.incrementAndGet();callbacks.add("ready");}
        public boolean hasUnheardReplyAudio(){return unheard;}
        public void close(){session.end();}
    }
    @Test public void lookupServedUnderTheWireEnumNameReachesTheModel()throws Exception {
        try(Fixture f=new Fixture()){
            f.toolKind="VOICE_CALL_HARNESS_TOOL_OUTCOME_KIND_SERVED";f.ready();f.receive("response.created");
            f.tool("lookup-1","search_conversations");
            JSONObject item=f.frame("conversation.item.create").getJSONObject("item");
            assertEquals("function_call_output",item.getString("type"));assertTrue(item.getString("output"),item.getString("output").contains("found it"));
        }
    }
    static long nextGraceMs;
    @Test public void mutingMidSentenceSendsSilenceSoTheTurnEnds()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("input_audio_buffer.speech_started");f.callback("speech");f.session.mute(true);
            for(int i=0;i<2;i++){JSONObject append=f.frame("input_audio_buffer.append");byte[] pcm=Base64.getDecoder().decode(append.getString("audio"));
                assertEquals(NativeVoiceSession.MUTE_SILENCE_BYTES/2,pcm.length);for(byte b:pcm)assertEquals(0,b);}
        }
    }
    @Test public void botGreetsOnlyAfterTheCallerStaysQuiet()throws Exception {
        nextGraceMs=50;
        try(Fixture f=new Fixture(new JSONArray(),"Say hello now")){
            JSONObject create=f.frame("response.create");assertEquals("Say hello now",create.getJSONObject("response").getString("instructions"));
        }
        nextGraceMs=400;
        try(Fixture f=new Fixture(new JSONArray(),"Say hello now")){
            f.receive("input_audio_buffer.speech_started");f.callback("speech");Thread.sleep(700);
            for(JSONObject frame:f.sent)assertFalse("Greeting sent after the caller spoke","response.create".equals(frame.optString("type")));
        }
    }
    static JSONObject nextBot;
    @Test public void sessionUsesTheBotsOwnVoiceSpeedAndLanguage()throws Exception {
        nextBot=new JSONObject().put("id","bot-voice").put("name","Voice Bot").put("voiceId","ara").put("voiceSpeed",1.5).put("voiceLanguage","hi");
        try(Fixture f=new Fixture()){
            JSONObject session=f.settings.getJSONObject("session");assertEquals("ara",session.getString("voice"));
            assertEquals(1.5,session.getJSONObject("audio").getJSONObject("output").getDouble("speed"),0);
            assertEquals("hi",session.getJSONObject("audio").getJSONObject("input").getJSONObject("transcription").getString("language_hint"));
        }
        try(Fixture f=new Fixture()){
            JSONObject session=f.settings.getJSONObject("session");assertFalse(session.has("voice"));
            assertEquals(1.0,session.getJSONObject("audio").getJSONObject("output").getDouble("speed"),0);
            assertFalse(session.getJSONObject("audio").getJSONObject("input").getJSONObject("transcription").has("language_hint"));
        }
    }
    @Test public void preparedStartupSkipsBackendAndStillRequiresEveryHistoryAcknowledgement()throws Exception {
        JSONArray history=new JSONArray().put(new JSONObject().put("speaker","user").put("text","Earlier question"))
            .put(new JSONObject().put("speaker","assistant").put("text","Earlier answer"));
        try(Fixture f=new Fixture(history,"",true,false,()->{})){
            assertEquals(0,f.backendCalls.get());f.receive("session.updated");
            JSONObject one=f.frame("conversation.item.create").getJSONObject("item"),two=f.frame("conversation.item.create").getJSONObject("item");
            assertEquals("Earlier question",one.getJSONArray("content").getJSONObject(0).getString("text"));
            assertEquals("Earlier answer",two.getJSONArray("content").getJSONObject(0).getString("text"));
            f.events.message(new JSONObject().put("type","conversation.item.created").put("item",one).toString());
            // Ordered session.updated is an observable barrier after the first acknowledgement.
            f.receive("session.updated");assertEquals(0,f.ready.get());
            f.events.message(new JSONObject().put("type","conversation.item.created").put("item",two).toString());f.callback("ready");
            assertEquals(0,f.backendCalls.get());assertTrue("No proactive response.create",f.sent.isEmpty());
        }
    }
    @Test public void earlySettingsWaitForFreshHistoryAndEveryAck()throws Exception {
        try(Fixture f=new Fixture(new JSONArray(),"",true,false,()->{},true)){
            f.receive("session.updated");f.session.sendPcm(new byte[]{1,2});
            assertNull("No empty-history admission or audio",f.sent.poll(100,TimeUnit.MILLISECONDS));assertEquals(0,f.ready.get());
            f.session.provideHistory(new JSONArray().put(new JSONObject().put("speaker","user").put("text","Fresh remote context")));
            JSONObject item=f.frame("conversation.item.create").getJSONObject("item");assertEquals(0,f.ready.get());
            f.events.message(new JSONObject().put("type","conversation.item.added").put("item",item).toString());f.callback("ready");
            assertEquals(1,f.ready.get());assertTrue("Quiet startup",f.sent.isEmpty());
        }
    }
    @Test public void freshHistoryCanArriveBeforeSettingsButCannotSkipThem()throws Exception {
        try(Fixture f=new Fixture(new JSONArray(),"",true,false,()->{},true)){
            f.session.provideHistory(new JSONArray());
            assertNull(f.sent.poll(100,TimeUnit.MILLISECONDS));assertEquals(0,f.ready.get());
            f.receive("session.updated");f.callback("ready");
            try{f.session.provideHistory(new JSONArray());fail("Second history delivery accepted");}catch(IllegalStateException expected){}
        }
    }
    @Test public void missingDeferredHistoryExpiresAndLateHistoryCannotAdmitMicrophone()throws Exception {
        CountDownLatch failed=new CountDownLatch(1),settingsSent=new CountDownLatch(1);
        AtomicInteger ready=new AtomicInteger(),frames=new AtomicInteger(),failures=new AtomicInteger();
        AtomicBoolean cancelled=new AtomicBoolean();AtomicReference<NativeVoiceSession.WireEvents> events=new AtomicReference<>();
        NativeVoiceSession call=new NativeVoiceSession((method,body)->{throw new AssertionError("Prepared startup must not call backend");},
            new JSONObject().put("id","bot").put("name","Bot"),(url,secret,listener)->{
                events.set(listener);listener.opened();return new NativeVoiceSession.Wire(){
                    public boolean send(String text){frames.incrementAndGet();settingsSent.countDown();return true;}
                    public long queueSize(){return 0;}public void cancel(){cancelled.set(true);}
                };
            },new NativeVoiceSession.PcmCodec(){public String encode(byte[] pcm){throw new AssertionError("No microphone admission");}public byte[] decode(String text){return new byte[0];}},
            new NativeVoiceSession.Listener(){
                public void onState(String state){}public void onAudio(byte[] pcm){throw new AssertionError("No playback");}
                public void onCaption(String who,String text){}public void onSpeechStarted(){}
                public void onComplete(String reason){}public void onReady(){ready.incrementAndGet();}
                public void onFailure(String safe){failures.incrementAndGet();failed.countDown();}
            },1000);
        try{
            call.deferHistory();call.preparedStartup(new JSONObject().put("instructions","Stay quiet").put("tools",new JSONArray()),
                new JSONObject().put("clientSecret","public-fixture").put("model","fixture").put("expiresAtUnixSeconds",System.currentTimeMillis()/1000+600).put("websocketUrl","wss://api.x.ai/test-only"),()->{});
            call.start();assertTrue(settingsSent.await(2,TimeUnit.SECONDS));
            events.get().message(new JSONObject().put("type","session.updated").toString());
            assertTrue("Independent watchdog must expire without history",failed.await(3,TimeUnit.SECONDS));
            assertTrue(cancelled.get());assertEquals(1,failures.get());assertEquals(0,ready.get());
            call.provideHistory(new JSONArray().put(new JSONObject().put("speaker","user").put("text","Too late")));
            events.get().message(new JSONObject().put("type","session.updated").toString());call.sendPcm(new byte[]{1,2});
            assertEquals("Late history/audio must not send another frame",1,frames.get());assertEquals(0,ready.get());
        }finally{call.end();}
    }
    @Test public void endedCallCannotResumeWhenLateHistoryArrives()throws Exception {
        try(Fixture f=new Fixture(new JSONArray(),"",true,false,()->{},true)){
            f.receive("session.updated");f.session.end();f.callback("complete");
            f.session.provideHistory(new JSONArray().put(new JSONObject().put("speaker","user").put("text","Late context")));
            assertTrue(f.cancelled);assertTrue(f.sent.isEmpty());assertEquals(0,f.ready.get());
        }
    }
    @Test public void bindingChangeBeforeDeferredHistoryCannotSeedOrAdmit()throws Exception {
        AtomicBoolean changed=new AtomicBoolean();
        try(Fixture f=new Fixture(new JSONArray(),"",true,false,()->{if(changed.get())throw new IllegalStateException();},true)){
            f.receive("session.updated");changed.set(true);
            f.session.provideHistory(new JSONArray().put(new JSONObject().put("speaker","user").put("text","Old account context")));
            f.callback("failure");assertTrue(f.sent.isEmpty());assertEquals(0,f.ready.get());
        }
    }
    @Test public void preparedExpiredCredentialsCannotOpenWire()throws Exception {
        try(Fixture f=new Fixture(new JSONArray(),"",true,true,()->{})){
            assertNull(f.events);assertEquals(0,f.backendCalls.get());assertEquals(0,f.ready.get());
        }
    }
    @Test public void bindingChangeBeforeSettingsAreConfirmedEndsTheCall()throws Exception {
        AtomicBoolean changed=new AtomicBoolean();
        try(Fixture f=new Fixture(new JSONArray(),"",true,false,()->{if(changed.get())throw new IllegalStateException();})){
            f.callback("ready");changed.set(true);f.receive("session.updated");f.callback("failure");
        }
    }
    @Test public void audioFollowsTheSettingsWithoutWaitingForConfirmation()throws Exception {
        try(Fixture f=new Fixture()){
            f.callback("ready");f.session.sendPcm(new byte[]{1,2,3,4});
            assertEquals("input_audio_buffer.append",f.frame("input_audio_buffer.append").getString("type"));
        }
    }
    @Test public void settingsErrorBeforeConfirmationIsFatalEvenWithAudioFlowing()throws Exception {
        try(Fixture f=new Fixture()){
            f.callback("ready");f.events.message(new JSONObject().put("type","error").put("error",new JSONObject().put("type","invalid_request_error")).toString());f.callback("failure");
        }
    }
    @Test public void reopeningSharedConversationWaitsForFreshSpeechDespiteHarnessGreeting()throws Exception {
        JSONArray history=new JSONArray().put(new JSONObject().put("speaker","user").put("text","Earlier harmless question"))
                .put(new JSONObject().put("speaker","assistant").put("text","Earlier harmless answer"));
        try(Fixture f=new Fixture(history,"Continue the conversation with a greeting")){
            f.receive("session.updated");
            JSONObject one=f.frame("conversation.item.create").getJSONObject("item"),two=f.frame("conversation.item.create").getJSONObject("item");
            assertEquals("Earlier harmless question",one.getJSONArray("content").getJSONObject(0).getString("text"));
            assertEquals("Earlier harmless answer",two.getJSONArray("content").getJSONObject(0).getString("text"));
            f.events.message(new JSONObject().put("type","conversation.item.created").put("item",one).toString());
            f.events.message(new JSONObject().put("type","conversation.item.created").put("item",two).toString());f.callback("ready");
            // A real call immediately streams microphone PCM, even during silence. That
            // must not count as a new user turn or trigger the harness greeting after600ms.
            f.session.sendPcm(new byte[960]);f.frame("input_audio_buffer.append");
            assertNull("Opening a call must not ask the model to answer seeded history",
                    f.sent.poll(1250,TimeUnit.MILLISECONDS));
            assertEquals(0,f.audioCount.get());assertTrue(f.tools.isEmpty());
            assertEquals(0,f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns").length());

            // Fresh voice still goes through the same server VAD and audio reply path.
            f.receive("input_audio_buffer.speech_started");f.callback("speech");
            f.events.message(new JSONObject().put("type","conversation.item.input_audio_transcription.completed").put("item_id","fresh-question").put("transcript","A fresh harmless question").toString());
            f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");
            f.receive("response.created");
            f.events.message(new JSONObject().put("type","response.audio.delta").put("delta","AQIDBA==").toString());f.callback("audio");
            assertArrayEquals(new byte[]{1,2,3,4},f.heard);
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("item_id","fresh-answer").put("transcript","A fresh harmless answer").toString());
            f.receive("response.done");f.callback("speechidle");
            JSONArray turns=f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns");assertEquals(2,turns.length());
            assertEquals("A fresh harmless question",turns.getJSONObject(0).getString("text"));
            assertEquals("A fresh harmless answer",turns.getJSONObject(1).getString("text"));
        }
    }
    @Test public void historicalRolesRequireEveryAcknowledgementBeforeMicAndNeverCreateResponses()throws Exception {
        JSONArray history=new JSONArray().put(new JSONObject().put("speaker","user").put("text","Earlier harmless question"))
            .put(new JSONObject().put("speaker","assistant").put("text","Earlier harmless answer"));
        try(Fixture f=new Fixture(history)){
            f.receive("session.updated");JSONObject first=f.frame("conversation.item.create").getJSONObject("item"),second=f.frame("conversation.item.create").getJSONObject("item");
            assertEquals("user",first.getString("role"));assertEquals("input_text",first.getJSONArray("content").getJSONObject(0).getString("type"));
            assertEquals("assistant",second.getString("role"));assertEquals("output_text",second.getJSONArray("content").getJSONObject(0).getString("type"));
            assertEquals(0,f.ready.get());f.session.sendPcm(new byte[]{1,2});
            f.events.message(new JSONObject().put("type","conversation.item.created").put("item",first).toString());
            f.events.message(new JSONObject().put("type","error").toString());f.callback("failure");
            assertEquals(0,f.ready.get());assertTrue(f.sent.isEmpty());
        }
        try(Fixture f=new Fixture(history)){
            f.receive("session.updated");JSONObject one=f.frame("conversation.item.create"),two=f.frame("conversation.item.create");
            f.events.message(new JSONObject().put("type","conversation.item.added").put("item",two.getJSONObject("item")).toString());
            f.events.message(new JSONObject().put("type","conversation.item.created").put("item",one.getJSONObject("item")).toString());f.callback("ready");
            assertEquals(0,f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns").length());
            assertTrue(f.sent.isEmpty());
        }
    }
    @Test public void recordUpsertsCorrectedTranscriptsAndCapturesConversationBeforeReady()throws Exception {
        try(Fixture f=new Fixture()){
            f.events.message(new JSONObject().put("type","conversation.created").put("conversation",new JSONObject().put("id","conversation-fixture")).toString());f.ready();
            for(String text:new String[]{"old partial","corrected final"})f.events.message(new JSONObject().put("type","conversation.item.input_audio_transcription.updated").put("item_id","user-item").put("transcript",text).toString());
            f.events.message(new JSONObject().put("type","conversation.item.input_audio_transcription.completed").put("item_id","user-item").put("transcript","corrected final").toString());
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.delta").put("item_id","bot-item").put("delta","Partial").toString());
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("item_id","bot-item").put("transcript","Final answer").toString());f.barrier();
            JSONObject record=f.session.recordSnapshot("hung-up",12345,678);assertEquals("conversation-fixture",record.getString("realtimeConversationId"));
            JSONArray turns=record.getJSONArray("turns");assertEquals("corrected final",turns.getJSONObject(0).getString("text"));
            assertEquals("user-item",turns.getJSONObject(0).getString("id"));assertEquals("Final answer",turns.getJSONObject(1).getString("text"));
            assertEquals("assistant",turns.getJSONObject(1).getString("speaker"));assertFalse(record.getBoolean("harnessMayCollect"));
            turns.getJSONObject(0).put("text","mutated snapshot");assertEquals("corrected final",f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns").getJSONObject(0).getString("text"));
        }
    }
    @Test public void interruptedUnheardAssistantOmittedAndLateTranscriptCannotRestoreIt()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("item_id","unheard").put("transcript","Generated but not fully heard").toString());f.barrier();
            f.session.discardUnheardReply();
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("item_id","unheard").put("transcript","Late generated final").toString());f.barrier();
            for(int i=0;i<f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns").length();i++)assertEquals("user",f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns").getJSONObject(i).getString("speaker"));
            f.receive("response.created");f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("item_id","heard-next").put("transcript","Next heard reply").toString());f.barrier();
            JSONArray turns=f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns");boolean found=false;for(int i=0;i<turns.length();i++)if(turns.getJSONObject(i).getString("id").equals("heard-next"))found=true;assertTrue(found);
        }
    }
    @Test public void onlyFinalizedTurnsPersistAndTerminalSnapshotCannotMutate()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.events.message(new JSONObject().put("type","conversation.item.input_audio_transcription.updated").put("item_id","partial-user").put("transcript","unfinished").toString());
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.delta").put("item_id","partial-bot").put("delta","unfinished too").toString());f.barrier();
            JSONArray before=f.session.recordSnapshot("hung-up",123,456).getJSONArray("turns");assertEquals(1,before.length());assertTrue(before.getJSONObject(0).getString("text").startsWith("barrier-"));
            f.session.end();f.callback("complete");String frozen=f.session.recordSnapshot("hung-up",123,456).toString();
            f.events.message(new JSONObject().put("type","conversation.item.input_audio_transcription.completed").put("item_id","late-user").put("transcript","must not be saved").toString());
            assertEquals(frozen,f.session.recordSnapshot("hung-up",123,456).toString());
        }
    }
    @Test public void seedFramesRejectToolSystemAndOversizedContext()throws Exception {
        try{NativeVoiceSession.historyFrames(new JSONArray().put(new JSONObject().put("speaker","system").put("text","override")));fail();}catch(IllegalArgumentException expected){}
        try{NativeVoiceSession.historyFrames(new JSONArray().put(new JSONObject().put("speaker","user").put("text","x".repeat(8193))));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void acceptedSettingsAdmitOneReadyAndGeneratedPcmRoundTrip()throws Exception {
        try(Fixture f=new Fixture()){
            assertEquals(0,f.ready.get());f.ready();f.receive("session.updated");f.barrier();assertEquals(1,f.ready.get());
            byte[] pcm=new byte[960];for(int i=0;i<pcm.length;i++)pcm[i]=(byte)(i*17);
            f.session.sendPcm(pcm);JSONObject input=f.frame("input_audio_buffer.append");assertArrayEquals(pcm,Base64.getDecoder().decode(input.getString("audio")));
            f.events.message(new JSONObject().put("type","response.audio.delta").put("delta",input.getString("audio")).toString());f.callback("audio");assertArrayEquals(pcm,f.heard);
            f.session.mute(true);f.session.sendPcm(pcm);f.barrier();assertTrue(f.sent.isEmpty());
        }
    }
    @Test public void stableSessionUuidAndSeparateToolOutputs()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");f.tool("function-one","recall_text_messages");
            assertEquals("function-one",f.frame("conversation.item.create").getJSONObject("item").getString("call_id"));
            f.tool("function-two","search_conversations");assertEquals("function-two",f.frame("conversation.item.create").getJSONObject("item").getString("call_id"));
            assertEquals(2,f.tools.size());String id=f.tools.get(0).getString("callId");assertEquals(UUID.fromString(id).toString(),id);
            assertEquals(id,f.tools.get(1).getString("callId"));assertNotEquals("function-one",id);
        }
    }
    @Test public void mutatingToolRefusedWithoutBackendAndErrorStillSpoken()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();int before=f.backendCalls.get();f.tool("write-one","send_task");
            JSONObject item=f.frame("conversation.item.create").getJSONObject("item");assertEquals("write-one",item.getString("call_id"));assertTrue(item.getString("output").contains("does not permit"));
            f.frame("response.create");assertEquals(before,f.backendCalls.get());assertTrue(f.tools.isEmpty());
        }
    }
    @Test public void failedLookupSchedulesResponse()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.failLookup=true;f.tool("lookup-error","recall_text_messages");
            assertTrue(f.frame("conversation.item.create").getJSONObject("item").getString("output").contains("lookup failed"));f.frame("response.create");
        }
    }
    @Test public void gracefulEndingWaitsForResponseAndDrainAndIgnoresLateEvents()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");f.tool("end-tool","end_the_call");f.frame("conversation.item.create");f.callback("finishing");
            f.session.completeAfterPlayback();f.barrier();assertFalse(f.cancelled);
            f.receive("response.done");f.callback("ending");f.session.completeAfterPlayback();f.callback("complete");assertTrue(f.cancelled);
            int ready=f.ready.get(),audio=f.audioCount.get(),calls=f.backendCalls.get();
            f.receive("session.updated");f.events.message("{\"type\":\"response.audio.delta\",\"delta\":\"AAAA\"}");f.tool("late","search_conversations");f.session.sendPcm(new byte[]{0,0});
            assertEquals(ready,f.ready.get());assertEquals(audio,f.audioCount.get());assertEquals(calls,f.backendCalls.get());
        }
    }
    @Test public void startupDeadlineExpiresWhileHarnessWorkerIsBlocked()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1),failed=new CountDownLatch(1);
        AtomicInteger requests=new AtomicInteger(),ready=new AtomicInteger(),opens=new AtomicInteger();
        NativeVoiceSession.Backend backend=(method,body)->{
            requests.incrementAndGet();entered.countDown();
            try{release.await(2,TimeUnit.SECONDS);return new JSONObject().put("instructions","late plan").put("tools",new JSONArray());}
            finally{returned.countDown();}
        };
        NativeVoiceSession.Listener listener=new NativeVoiceSession.Listener(){
            public void onState(String s){}public void onAudio(byte[] p){}public void onCaption(String w,String t){}public void onSpeechStarted(){}
            public void onComplete(String r){}public void onFailure(String s){failed.countDown();}public void onReady(){ready.incrementAndGet();}
        };
        NativeVoiceSession.PcmCodec codec=new NativeVoiceSession.PcmCodec(){public String encode(byte[] p){return "";}public byte[] decode(String t){return new byte[0];}};
        NativeVoiceSession call=new NativeVoiceSession(backend,new JSONObject().put("id","offline").put("name","Offline"),
            (u,s,e)->{opens.incrementAndGet();throw new AssertionError("Timed-out startup opened a socket");},codec,listener,100);
        try{call.start();assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(failed.await(3,TimeUnit.SECONDS));
            release.countDown();assertTrue(returned.await(1,TimeUnit.SECONDS));assertEquals(1,requests.get());assertEquals(0,opens.get());assertEquals(0,ready.get());
        }finally{release.countDown();call.end();}
    }

    @Test public void diagnosticsKeepSafeHttpStatusAndRejectArbitraryExceptionText(){
        assertEquals("Grok request failed (HTTP 404).",NativeVoiceSession.setupFailure(new GrokCore.Failure("Grok request failed (HTTP 404).",true)));
        assertFalse(NativeVoiceSession.setupFailure(new Exception("tokenlike-secret-value")).contains("tokenlike"));
        assertTrue(NativeVoiceSession.websocketFailure(404).contains("HTTP 404"));
        assertFalse(NativeVoiceSession.websocketFailure(null).contains("HTTP"));
        assertFalse(NativeVoiceSession.websocketFailure(-1).contains("-1"));
    }

    @Test public void unexpectedCloseAfterReadyIsDroppedNeverHappyAndLateCallbacksIgnored()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.events.closed();f.callback("disconnected");assertTrue(f.cancelled);
            assertEquals(0,f.completions.get());assertEquals(0,f.failures.get());assertEquals(1,f.disconnections.get());
            f.events.closed();f.events.failed(null);f.receive("session.updated");f.tool("late","recall_text_messages");
            assertEquals(1,f.disconnections.get());assertEquals(0,f.completions.get());assertEquals(1,f.ready.get());assertTrue(f.tools.isEmpty());
        }
    }
    @Test public void networkFailureAfterReadyIsDroppedButHttpFailureIsServiceFailure()throws Exception {
        try(Fixture f=new Fixture()){f.ready();f.events.failed(null);f.callback("disconnected");assertEquals(0,f.completions.get());}
        try(Fixture f=new Fixture()){f.ready();f.events.failed(403);f.callback("failure");assertEquals(0,f.disconnections.get());assertEquals(0,f.completions.get());}
    }
    @Test public void closeBeforeReadyIsFailureRatherThanCompletedCall()throws Exception {
        try(Fixture f=new Fixture()){f.events.closed();f.callback("failure");assertEquals(0,f.disconnections.get());assertEquals(0,f.completions.get());}
    }

    @Test public void explicitlyEndingRemoteCloseStillWaitsForPlaybackAndCompletes()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");f.tool("goodbye","end_the_call");f.frame("conversation.item.create");f.callback("finishing");
            f.events.closed();f.callback("ending");assertEquals(0,f.completions.get());assertEquals(0,f.disconnections.get());
            f.session.completeAfterPlayback();f.callback("complete");assertEquals(1,f.completions.get());assertEquals(0,f.disconnections.get());
        }
    }

    @Test public void gracefulEndingDoesNotCutQueuedSpeechAtFormerFiveSecondTimeout()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");f.tool("long-goodbye","end_the_call");
            f.frame("conversation.item.create");f.callback("finishing");f.receive("response.done");f.callback("ending");
            // A valid burst can now hold43s of PCM. Only the hardware-drain acknowledgement ends it.
            assertNull("Graceful ending must not discard accepted audio after5s",f.callbacks.poll(5250,TimeUnit.MILLISECONDS));
            assertEquals(0,f.completions.get());assertFalse(f.cancelled);
            f.session.completeAfterPlayback();f.callback("complete");assertEquals(1,f.completions.get());assertTrue(f.cancelled);
        }
    }

    @Test public void sequentialRepliesMayExceedFormerLifetimeReceiveBudgetWithoutBacklog()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();
            // One second of ordinary PCM per event. Await its callback before sending
            // another: this exercises lifetime traffic, never an event/playback burst.
            String audio=new JSONObject().put("type","response.audio.delta")
                .put("delta",Base64.getEncoder().encodeToString(new byte[48000])).toString();
            long receivedBytes=0;
            for(int reply=0;reply<20;reply++){
                f.receive("input_audio_buffer.speech_started");f.callback("speech");
                f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");
                f.receive("response.created");
                for(int second=0;second<10;second++){
                    f.events.message(audio);receivedBytes+=audio.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    String outcome;do{outcome=f.callbacks.poll(3,TimeUnit.SECONDS);}
                    while(outcome!=null&&!Set.of("audio","complete","failure","disconnected").contains(outcome));
                    assertEquals("Ordinary sequential audio ended at reply "+reply+", second "+second,"audio",outcome);
                }
                f.receive("response.done");f.callback("speechidle");
            }
            assertTrue("Fixture must cross the former12MiB lifetime budget",receivedBytes>12L*1024*1024);
            assertEquals(200,f.audioCount.get());assertEquals(0,f.completions.get());assertEquals(0,f.failures.get());
            f.session.sendPcm(new byte[]{0,0});f.frame("input_audio_buffer.append");
        }
    }

    @Test public void oversizedIndividualVoiceEventStillFailsWithoutPlayingAudio()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();
            f.events.message(new JSONObject().put("type","response.audio.delta").put("delta","A".repeat(512*1024)).toString());
            f.callback("failure");assertEquals(1,f.failures.get());assertEquals(0,f.audioCount.get());assertTrue(f.cancelled);
        }
    }

    @Test public void recoverableRequestErrorDuringLiveCallMustNotEndCall()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");
            // Recoverable request errors must be independent of whether this
            // client sent a redundant cancellation or a different stale request.
            // Matches the type/code captured in the emulator barge-in baseline.
            // The raw server message is deliberately replaced by fixture text.
            f.events.message(new JSONObject().put("type","error").put("error",new JSONObject()
                .put("type","invalid_request_error").put("code","invalid_request_error")
                .put("message","Cancellation failed: no active response")).toString());
            // Model/server continues on the same socket after the benign request error.
            f.receive("response.done");f.receive("response.created");
            f.events.message(new JSONObject().put("type","response.audio.delta").put("delta","AQIDBA==").toString());
            String outcome;do{outcome=f.callbacks.poll(3,TimeUnit.SECONDS);}
            while(outcome!=null&&!Set.of("audio","complete","failure","disconnected").contains(outcome));
            assertEquals("Recoverable mid-call request error must allow the next answer","audio",outcome);
            assertEquals(0,f.failures.get());assertFalse(f.cancelled);
        }
    }

    @Test public void serverVadBargeInDoesNotSendRedundantResponseCancel()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");
            f.receive("input_audio_buffer.speech_started");f.callback("speech");
            f.barrier();
            assertTrue("Server VAD already owns cancellation",f.sent.isEmpty());
            assertEquals(0,f.failures.get());
        }
    }

    @Test public void interruptedResponseAudioCannotResumeAfterCallerStopsSpeaking()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");
            String audio=new JSONObject().put("type","response.audio.delta").put("delta","AQIDBA==").toString();
            f.events.message(audio);f.callback("audio");
            f.receive("input_audio_buffer.speech_started");f.callback("speech");
            f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");
            // In-flight network audio from the interrupted response can outlive
            // VAD speech-stopped. Only a new response may restore audio delivery.
            f.events.message(audio);f.barrier();
            assertEquals("Interrupted old response must remain silent",1,f.audioCount.get());
            f.receive("response.done");f.receive("response.created");
            f.events.message(audio);f.callback("audio");assertEquals(2,f.audioCount.get());
        }
    }

    @Test public void lateInterruptedResponseCannotPolluteNewResponse()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();
            f.events.message(new JSONObject().put("type","response.created").put("response",new JSONObject().put("id","reply-a")).toString());
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("response_id","reply-a").put("item_id","item-a").put("transcript","Unheard A").toString());f.barrier();
            f.receive("input_audio_buffer.speech_started");f.callback("speech");
            f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");
            f.events.message(new JSONObject().put("type","response.created").put("response",new JSONObject().put("id","reply-b")).toString());
            for(String type:new String[]{"response.audio.delta","response.output_audio_transcript.done","response.done"})
                f.events.message(new JSONObject().put("type",type).put("response_id","reply-a").put("item_id","item-a").put("delta","AQIDBA==").put("transcript","Late A").toString());
            f.barrier();assertEquals(0,f.audioCount.get());assertFalse("Stale done cannot idle B",f.callbacks.contains("speechidle"));
            f.events.message(new JSONObject().put("type","response.audio.delta").put("response_id","reply-b").put("delta","AQIDBA==").toString());f.callback("audio");
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("response_id","reply-b").put("item_id","item-b").put("transcript","Heard B").toString());f.barrier();
            JSONArray turns=f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns");
            boolean heard=false;for(int i=0;i<turns.length();i++){String text=turns.getJSONObject(i).getString("text");assertFalse(text.contains("A"));heard|=text.equals("Heard B");}assertTrue(heard);
        }
    }

    @Test public void liveAuthenticationErrorRemainsFatalAndDiagnosticsExcludeRawData()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.events.message(new JSONObject().put("type","error").put("error",new JSONObject().put("type","authentication_error").put("code","private-code").put("message","private-message").put("event_id","private-id")).toString());
            f.callback("failure");String diagnostics=f.session.protocolDiagnostics();assertTrue(diagnostics.contains("authentication_error"));assertTrue(diagnostics.contains("errors=1"));assertFalse(diagnostics.contains("private"));
        }
    }

    @Test public void freshSpeechPreservesCompletedHeardReplyHistory()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("item_id","heard").put("transcript","Fully heard answer").toString());
            f.receive("response.done");f.callback("speechidle");
            f.receive("input_audio_buffer.speech_started");f.callback("speech");f.barrier();
            JSONArray turns=f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns");boolean found=false;
            for(int i=0;i<turns.length();i++)found|=turns.getJSONObject(i).optString("text").equals("Fully heard answer");assertTrue(found);
        }
    }

    @Test public void freshSpeechDiscardsCompletedButUnheardReplyBeforeCallback()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");
            f.events.message(new JSONObject().put("type","response.output_audio_transcript.done").put("item_id","unheard-completed").put("transcript","Queued answer").toString());
            f.receive("response.done");f.callback("speechidle");f.unheard=true;
            f.receive("input_audio_buffer.speech_started");f.callback("speech");
            JSONArray turns=f.session.recordSnapshot("hung-up",1,1).getJSONArray("turns");assertEquals(0,turns.length());
        }
    }

    @Test public void manualCancelRemainsAvailableAndDoesNotSendDuplicates()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.receive("response.created");f.barrier();
            f.session.cancelReply();f.frame("response.cancel");f.session.cancelReply();f.barrier();
            assertTrue("Repeated manual cancel must not race another cancellation",f.sent.isEmpty());
            f.receive("response.cancelled");f.callback("speechidle");assertEquals(0,f.failures.get());
        }
    }

    @Test public void oversizedServerIdentifiersFailBeforeRetentionOrOwnershipFallback()throws Exception {
        for(String field:new String[]{"response_id","item_id","call_id","response.id"}){
            try(Fixture f=new Fixture()){
                f.ready();JSONObject event=new JSONObject().put("type",field.equals("call_id")?"response.function_call_arguments.done":field.equals("item_id")?"response.output_audio_transcript.done":"response.created");
                if(field.equals("response.id"))event.put("response",new JSONObject().put("id","x".repeat(257)));
                else event.put(field,"x".repeat(257));
                event.put("transcript","Must not persist").put("name","recall_text_messages").put("arguments","{}");
                f.events.message(event.toString());f.callback("failure");assertTrue(f.cancelled);assertTrue(f.tools.isEmpty());
                assertEquals(0,f.session.recordSnapshot("failed",1,1).getJSONArray("turns").length());
            }
        }
    }

    @Test public void lookupFromPreviousCallerTurnReturnsResultWithoutExtraResponse()throws Exception {
        try(Fixture f=new Fixture()){
            f.lookupEntered=new CountDownLatch(1);f.lookupRelease=new CountDownLatch(1);
            try{
                f.ready();f.receive("input_audio_buffer.speech_started");f.callback("speech");
                f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");
                f.receive("response.created");f.tool("slow-lookup","recall_text_messages");
                assertTrue(f.lookupEntered.await(2,TimeUnit.SECONDS));
                f.receive("input_audio_buffer.speech_started");f.callback("speech");
                f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");
                f.receive("response.cancelled");f.callback("speechidle");
                f.lookupRelease.countDown();JSONObject result=f.frame("conversation.item.create").getJSONObject("item");
                assertEquals("slow-lookup",result.getString("call_id"));f.barrier();
                assertTrue("An obsolete lookup must not request a competing answer",f.sent.isEmpty());
                assertEquals(0,f.failures.get());
            }finally{f.lookupRelease.countDown();}
        }
    }


    @Test public void speechThatNeverBecomesAReplyReturnsToListening()throws Exception {
        try(Fixture f=new Fixture()){f.ready();f.session.thinkingFallbackMs=100;
            f.receive("input_audio_buffer.speech_started");f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");f.callback("speechidle");}
    }
    @Test public void aStartedReplyKeepsThinkingInsteadOfFallingBack()throws Exception {
        try(Fixture f=new Fixture()){f.ready();f.session.thinkingFallbackMs=100;
            f.receive("input_audio_buffer.speech_started");f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");f.receive("response.created");
            Thread.sleep(300);f.barrier();assertFalse("A reply in progress must not be shown as idle",f.callbacks.contains("speechidle"));}
    }
    @Test public void runningLookupKeepsThinkingAfterItsFunctionResponseEnds()throws Exception {
        try(Fixture f=new Fixture()){f.lookupEntered=new CountDownLatch(1);f.lookupRelease=new CountDownLatch(1);f.ready();
            f.receive("input_audio_buffer.speech_started");f.receive("input_audio_buffer.speech_stopped");f.callback("Thinking");
            f.receive("response.created");f.tool("lookup-1","recall_text_messages");assertTrue(f.lookupEntered.await(2,TimeUnit.SECONDS));
            f.receive("response.done");f.callback("Thinking");
            f.lookupRelease.countDown();f.frame("response.create");}
    }
    @Test public void lostNetworkDisconnectsALiveCallWithoutAFailure()throws Exception {
        try(Fixture f=new Fixture()){f.ready();f.session.networkLost();f.callback("disconnected");assertEquals(0,f.failures.get());}
    }
    @Test public void outgoingQueueThatStopsDrainingIsALostConnection()throws Exception {
        try(Fixture f=new Fixture()){f.ready();f.queued=300*1024;f.session.sendPcm(new byte[]{1,2});f.callback("disconnected");assertEquals(0,f.failures.get());}
    }
}
