package local.codex.portalprotectcamera;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/**
 * Extended FLV transport; compressed H264/AAC payloads are never re-encoded.
 *
 * Portal/Protect iPhone live-audio compatibility findings, 2026-10-24:
 * The accepted runtime profile is audioAdvanceMs=75, audioStartupDropMs=0,
 * audioRetainStartup=true, clockSyncIntervalMs=250 (media origin 30000 ms).
 * Advancing audio timestamps is needed for reliable live sound in our tests;
 * it is not an extra encoder, a sleep, or a permanent audio buffering delay.
 *
 * User-observed timestamp-only trials, with startup audio retained:
 *   0 ms: intermittent silence; reproduced again after restoring working 750 ms.
 *   25 ms: failed. 50 ms: failed.
 *   75 ms: worked, then worked again after the failed 50 ms trial; selected.
 *   100 ms, 300 ms, 750 ms: worked.
 * Separately, suppressing the first 750 ms of audio with ZERO timestamp advance
 * still failed. The legacy 750 ms correction did both suppression and advance;
 * these controls exist to isolate the two effects rather than conflate them.
 * Retention here covers AAC passed to this muxer; Sink.run still discards real
 * preroll before its selected video keyframe. Later audit found failed clients'
 * first audio at/after first video, versus a useful audio lead in working cases.
 * This supports a startup-ordering hypothesis, not proof of the phone's internals.
 *
 * Direct audio, recordings and independent Protect live captures contained
 * decodable sound even during reported iPhone failures. Some fresh live clients
 * showed an initial relative-to-wall-clock timestamp jump. Playback startup or
 * buffer scheduling is therefore a hypothesis, not a proven receiver defect.
 * Our API captures are separate clients and do not establish what iPhone decoded.
 * The observed 50/75 ms boundary is device/session-specific, not a universal AAC
 * requirement; 75 ms is the smallest tested working value, not a proven minimum.
 * Do not remove the advance solely because recordings appear aligned: matching
 * packets showed Protect's export changes relative A/V timing versus live output.
 * See portal-protect/portal-native/clock-origin-trial/README.md and sync-capture/.
 */
final class ProtectMuxer {
  final OutputStream out;final long wallAnchor;final int advance;
  final long mediaOrigin,clockSyncInterval,audioStartupDropMs;
  final boolean audioRetainStartup;
  long lastSync=-1;int audioRate=48000;
  ProtectMuxer(OutputStream out,long wallAnchor,int advance){this(out,wallAnchor,advance,30000,250);}
  ProtectMuxer(OutputStream out,long wallAnchor,int advance,long mediaOrigin){this(out,wallAnchor,advance,mediaOrigin,250);}
  ProtectMuxer(OutputStream out,long wallAnchor,int advance,long mediaOrigin,long clockSyncInterval){
    this(out,wallAnchor,advance,mediaOrigin,clockSyncInterval,0);
  }
  ProtectMuxer(OutputStream out,long wallAnchor,int advance,long mediaOrigin,long clockSyncInterval,long audioStartupDropMs){
    this(out,wallAnchor,advance,mediaOrigin,clockSyncInterval,audioStartupDropMs,false);
  }
  ProtectMuxer(OutputStream out,long wallAnchor,int advance,long mediaOrigin,long clockSyncInterval,long audioStartupDropMs,boolean audioRetainStartup){
    if(mediaOrigin<0||mediaOrigin>86400000)throw new IllegalArgumentException("Invalid clock origin");
    if(clockSyncInterval<100||clockSyncInterval>5000)throw new IllegalArgumentException("Invalid clock interval");
    if(audioStartupDropMs<0||audioStartupDropMs>5000)throw new IllegalArgumentException("Invalid audio startup drop");
    if(audioRetainStartup&&(advance<0||mediaOrigin<advance))throw new IllegalArgumentException("Insufficient audio timestamp origin");
    this.out=out;this.wallAnchor=wallAnchor;this.advance=advance;this.mediaOrigin=mediaOrigin;this.clockSyncInterval=clockSyncInterval;
    this.audioStartupDropMs=audioStartupDropMs;
    this.audioRetainStartup=audioRetainStartup;
  }
  static void u24(OutputStream o,long n)throws IOException{o.write((int)(n>>16)&255);o.write((int)(n>>8)&255);o.write((int)n&255);}
  static void u32(OutputStream o,long n)throws IOException{o.write((int)(n>>24)&255);u24(o,n);}
  static void string(DataOutputStream o,String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);o.writeByte(2);o.writeShort(b.length);o.write(b);}
  static void object(DataOutputStream o,Map<String,Object> m,boolean ecma)throws IOException{
    o.writeByte(ecma?8:3);if(ecma)o.writeInt(m.size());
    for(Map.Entry<String,Object> e:m.entrySet()){
      byte[] key=e.getKey().getBytes(StandardCharsets.UTF_8);o.writeShort(key.length);o.write(key);Object v=e.getValue();
      if(v instanceof String)string(o,(String)v);else if(v instanceof Boolean){o.writeByte(1);o.writeBoolean((Boolean)v);}
      else if(v instanceof Map)object(o,(Map<String,Object>)v,false);else{o.writeByte(0);o.writeDouble(((Number)v).doubleValue());}
    }o.writeShort(0);o.writeByte(9);
  }
  static LinkedHashMap<String,Object> map(Object... pairs){LinkedHashMap<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
  void script(String name,Map<String,Object> fields,long stamp)throws IOException{
    ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);string(d,name);object(d,fields,true);tag(18,b.toByteArray(),stamp);
  }
  void tag(int kind,byte[] data,long stamp)throws IOException{
    if(stamp<0||stamp>0xffffffffL)throw new IOException("Invalid stream timestamp");
    out.write(kind);u24(out,data.length);u24(out,stamp);out.write((int)(stamp>>24)&255);u24(out,0);out.write(data);u32(out,11+data.length);
    int clock=kind==9?90000:(kind==8||kind==10)?audioRate:11025;out.write(0);u24(out,clock);out.write(new byte[8]);u32(out,stamp*clock/1000);
  }
  void start(String stream,byte[] sps,byte[] pps)throws IOException{
    start(stream,sps,pps,0);
  }
  void start(String stream,byte[] sps,byte[] pps,long firstAudioRelativeMs)throws IOException{
    start(stream,sps,pps,firstAudioRelativeMs,true);
  }
  void start(String stream,byte[] sps,byte[] pps,long firstAudioRelativeMs,boolean includeAac)throws IOException{
    if(sps==null||sps.length<4||pps==null||pps.length==0)throw new IOException("Missing codec headers");
    out.write(new byte[]{70,76,86,1,7,0,0,0,9,0,0,0,0});
    Map<String,Object> metadata=map("duration",0,"width",1280,"height",720,"videodatarate",2000,"framerate",30,"videocodecid",7,"streamName",stream,"encoder","PortalNative");
    if(includeAac)metadata.putAll(map("audiodatarate",64,"audiosamplerate",48000,"audiosamplesize",16,"stereo",true,"audiocodecid",10));
    script("onMetaData",metadata,mediaOrigin);
    ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
    d.write(new byte[]{0x17,0,0,0,0,1,sps[1],sps[2],sps[3],(byte)255,(byte)225});d.writeShort(sps.length);d.write(sps);d.writeByte(1);d.writeShort(pps.length);d.write(pps);tag(9,b.toByteArray(),mediaOrigin);
    // Retained startup AAC can precede the video's origin after timestamp advance.
    // Move its configuration tag too, so it is never later than the first AAC data.
    long audioConfigStamp=mediaOrigin+(audioRetainStartup?Math.min(0,firstAudioRelativeMs)-advance:0);
    if(includeAac)tag(8,new byte[]{(byte)0xaf,0,0x11,(byte)0x90},audioConfigStamp);sync(mediaOrigin);out.flush();
  }
  void sync(long stamp)throws IOException{
    if(lastSync<0||stamp-lastSync>=clockSyncInterval){
      script("onClockSync",map("streamClock",stamp,"streamClockBase",0,"wallClock",wallAnchor+stamp-mediaOrigin),stamp);
      Map<String,Object> bounds=map("cur",2000000,"min",2000000,"max",2000000);
      script("onMpma",map("cs",bounds,"m",bounds,"sp",bounds,"r",0,"t",2000000),stamp);lastSync=stamp;
    }
  }
  void audio(byte[] aac,long stamp)throws IOException{
    if(audioStartupDropMs>0&&stamp<audioStartupDropMs)return;
    // The positive media origin leaves room for early AAC timestamps. Retention
    // separates timestamp advance from the legacy implicit startup packet drop.
    stamp-=advance;if(stamp<0&&!audioRetainStartup)return;stamp+=mediaOrigin;sync(stamp);
    byte[] data=new byte[aac.length+2];data[0]=(byte)0xaf;data[1]=1;System.arraycopy(aac,0,data,2,aac.length);tag(8,data,stamp);
  }
  // UniFi native Opus uses tag type 10 (not standard FLV audio type 8).
  // Observed wire contract: standalone CF000300 configuration followed by
  // complete raw Opus packets; 48 kHz trailer clock. AAC stays in type 8.
  // Reference: xuio/scrypted-unifi-direct src/native-rtsp.ts, pinned in opus-trial/.
  void startOpus(long firstAudioRelativeMs)throws IOException{
    long stamp=mediaOrigin+(audioRetainStartup?Math.min(0,firstAudioRelativeMs)-advance:0);
    tag(10,new byte[]{(byte)0xcf,0,3,0},stamp);
  }
  void opus(byte[] packet,long stamp)throws IOException{
    if(audioStartupDropMs>0&&stamp<audioStartupDropMs)return;
    stamp-=advance;if(stamp<0&&!audioRetainStartup)return;stamp+=mediaOrigin;
    sync(stamp);tag(10,packet,stamp);
  }
  void video(List<byte[]> units,long stamp)throws IOException{
    boolean key=false;int size=5;
    for(byte[] n:units){int type=n[0]&31;if(type==5)key=true;if(type==1||type==5)size+=4+n.length;}
    if(size==5)return;ByteArrayOutputStream b=new ByteArrayOutputStream(size);DataOutputStream d=new DataOutputStream(b);
    d.writeByte(key?0x17:0x27);d.writeByte(1);d.write(new byte[3]);
    for(byte[] n:units){int type=n[0]&31;if(type==1||type==5){d.writeInt(n.length);d.write(n);}}
    stamp+=mediaOrigin;sync(stamp);tag(9,b.toByteArray(),stamp);
  }
}
