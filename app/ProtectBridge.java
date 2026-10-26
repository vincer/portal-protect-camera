package local.codex.portalprotectcamera;
import android.os.SystemClock;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
/**
 * Unofficial native Protect control and media, independent of direct RTSP.
 * Only app-private files supply the adopted identity, recorder, and TLS pins.
 * Fixed response templates are compatibility stubs, not proof that every
 * advertised ISP/AI/settings feature is implemented by this prototype.
 */
final class ProtectBridge {
  static final String TAG="PortalNative";
  final CameraService camera;
  volatile boolean enabled,closed;volatile ProtectWebSocket ws;volatile Thread control;
  JSONObject config,templates;SSLContext tls;int messageId;
  volatile long offsetMs;final long initMs=SystemClock.elapsedRealtime();
  final ConcurrentHashMap<String,Sink> streams=new ConcurrentHashMap<>();
  final ThreadPoolExecutor snapshots=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(8),r->new Thread(r,"ProtectSnapshot"),new ThreadPoolExecutor.DiscardPolicy());
  volatile byte[] latestKeyframe;volatile long latestKeyUs;
  ProtectSnapshot snapshot;
  ProtectBridge(CameraService camera){this.camera=camera;snapshot=new ProtectSnapshot(camera,this);}
  synchronized void configure(){
    if(closed)return;
    boolean requested=camera.getSharedPreferences("camera",0).getBoolean("protect_native",false);
    if(enabled==requested)return;
    if(!requested){enabled=false;closeConnection();Log.i(TAG,"MODE_NUC native transport disabled");return;}
    try{
      config=new JSONObject(read(new File(camera.getFilesDir(),"protect.json")));
      try(InputStream in=camera.getAssets().open("protect-protocol.json")){templates=new JSONObject(read(in));}
      String host=config.getString("host");if(!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}"))throw new IOException("Invalid recorder host");
      tls=createTls();enabled=true;control=new Thread(this::run,"ProtectControl");control.start();
    }catch(Exception e){Log.e(TAG,"CONFIG_ERROR "+e.getClass().getSimpleName());}
  }
  static String read(File f)throws IOException{try(InputStream in=new FileInputStream(f)){return read(in);}}
  static String read(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))>=0){b.write(buf,0,n);if(b.size()>1024*1024)throw new IOException("Oversized configuration");}return b.toString("UTF-8");}
  // Mutual TLS authenticates the adopted camera. Trust is restricted to the
  // operator-provisioned recorder leaf pins; never weaken this for setup ease.
  SSLContext createTls()throws Exception{
    KeyStore store=KeyStore.getInstance("PKCS12");char[] password=config.getString("keyPassword").toCharArray();
    try(InputStream in=new FileInputStream(new File(camera.getFilesDir(),"protect.p12"))){store.load(in,password);}
    KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password);Arrays.fill(password,'\0');
    String pin=config.getString("serverSha256");
    X509TrustManager trust=new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
      try{if(c.length==0||!Arrays.asList(pin.toLowerCase(Locale.US).split(",")).contains(hex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded()))))throw new CertificateException("Recorder certificate changed");}catch(GeneralSecurityException e){throw new CertificateException(e);}
    }};
    SSLContext context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),new TrustManager[]{trust},new SecureRandom());return context;
  }
  static String safe(Exception e){String s=e.getMessage();if(s==null)return "";s=s.replaceAll("[A-Za-z0-9_+/=-]{32,}","[redacted]");return s.substring(0,Math.min(160,s.length()));}
  static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.US,"%02x",v&255));return s.toString();}
  void run(){int backoff=1;
    while(enabled&&!closed&&control==Thread.currentThread()){
      try{
        Map<String,String> headers=new LinkedHashMap<>();String mac=config.getString("mac");
        headers.put("camera-mac",mac);headers.put("camera-model","0xa598");headers.put("camera-ip",config.getString("ip"));headers.put("camera-firmware","5.5.31");headers.put("device-id",UUID.nameUUIDFromBytes(("portal-native-"+mac).getBytes("UTF-8")).toString());headers.put("x-guid",UUID.randomUUID().toString());
        ProtectWebSocket connection=new ProtectWebSocket(tls,config.getString("host"),headers);if(!enabled||closed||control!=Thread.currentThread()){connection.close();break;}ws=connection;
        if(connection.serverDateMs>0)offsetMs=connection.serverDateMs-System.currentTimeMillis();else syncClock();
        Log.i(TAG,"CONTROL_CONNECTED timeOffsetMs="+offsetMs);hello();
        while(enabled&&!closed&&control==Thread.currentThread()&&ws==connection){process(new JSONObject(connection.receive()));backoff=1;}
      }catch(Exception e){if(enabled&&!closed)Log.w(TAG,"CONTROL_RETRY "+e.getClass().getSimpleName()+" "+safe(e));}
      finally{if(control==Thread.currentThread())closeConnection();}
      if(enabled&&!closed){try{Thread.sleep(backoff*1000L);}catch(InterruptedException ignored){}backoff=Math.min(15,backoff*2);}
    }
  }
  void syncClock(){
    HttpsURLConnection c=null;try{long start=System.currentTimeMillis();c=(HttpsURLConnection)new URL("https://"+config.optString("host")+"/").openConnection();c.setSSLSocketFactory(tls.getSocketFactory());c.setHostnameVerifier((h,s)->h.equals(config.optString("host")));c.setInstanceFollowRedirects(false);c.setRequestMethod("HEAD");c.setConnectTimeout(3000);c.setReadTimeout(3000);long server=c.getHeaderFieldDate("Date",0),end=System.currentTimeMillis();if(server>0)offsetMs=server+500-(start+end)/2;}catch(Exception e){Log.w(TAG,"CLOCK_REFERENCE_UNAVAILABLE "+e.getClass().getSimpleName());}finally{if(c!=null)c.disconnect();}
  }
  void closeConnection(){
    final ProtectWebSocket connection;final List<Sink> old;
    synchronized(this){connection=ws;ws=null;old=new ArrayList<>(streams.values());streams.clear();}
    Runnable cleanup=()->{if(connection!=null)connection.close();for(Sink s:old)s.close();};
    // Android's TLS close writes close_notify and therefore must stay off the UI thread.
    if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())new Thread(cleanup,"ProtectDisconnect").start();else cleanup.run();
  }
  long now(){return camera.originWallMs+(System.nanoTime()/1000-camera.originUs)/1000+offsetMs;}
  JSONObject copy(String name)throws JSONException{return new JSONObject(templates.getJSONObject(name).toString());}
  boolean opusRequested(){return config!=null&&config.optBoolean("enableOpus",false);}
  // Isolation mode removes BOTH AAC capability and media. It worked for live
  // audio but produced recordings without sound on the tested Protect version.
  // Normal operation uses enableOpus=true, protectOpusOnly=false.
  boolean opusOnly(){return opusRequested()&&config.optBoolean("protectOpusOnly",false);}
  JSONObject features()throws JSONException{
    JSONObject f=copy("get_feature_flags");
    // Advertise only after actual Opus packets exist, never a capability-only
    // shortcut to force the browser into a player we cannot supply.
    JSONArray codecs=new JSONArray();if(!opusOnly())codecs.put("aac");
    if(opusRequested()&&camera.opusAvailable){codecs.put("opus");f.put("opusSampleRates",new JSONArray().put(48000));}
    f.put("audioCodecs",codecs);
    return f;
  }
  JSONObject object(Object... pairs)throws JSONException{JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);return o;}
  void send(String fn,int responseTo,JSONObject payload)throws Exception{
    final ProtectWebSocket connection;final int nextId;
    synchronized(this){connection=ws;nextId=++messageId;}
    if(connection==null)throw new IOException("Disconnected");
    java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'+00:00'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));
    JSONObject msg=object("from","ubnt_avclient","functionName",fn,"inResponseTo",responseTo,"messageId",nextId,"payload",payload==null?new JSONObject():payload,"responseExpected",false,"statusCode",0,"timeStamp",f.format(new Date(now())),"to","UniFiVideo");
    connection.text(msg.toString());
  }
  void hello()throws Exception{
    String mac=config.getString("mac");StringBuilder hw=new StringBuilder();for(int i=0;i<12;i+=2){if(i>0)hw.append(':');hw.append(mac.substring(i,i+2).toLowerCase(Locale.US));}
    send("ubnt_avclient_hello",0,object("adoptionCode",JSONObject.NULL,"connectionHost",config.getString("host"),"connectionSecurePort",7442,"fwVersion","5.5.31","hwrev",19,"idleTime",0,"ip",config.getString("ip"),"mac",mac,"model","UVC G5 Pro","name","Portal","protocolVersion",67,"rebootTimeoutSec",30,"semver","v5.5.31","totalLoad",0.1,"upgradeTimeoutSec",150,"uptime",(SystemClock.elapsedRealtime()-initMs)/1000,"features",features(),"sysid",0xa598,"platform","sav837gw","firmwareBuild","92ebf37.260724.1639","hwaddr",hw.toString(),"lensmodel","UVC G5 Pro","cameraName","Portal","isGen5s",true,"isDoorbellSeries",false));
  }
  void process(JSONObject msg)throws Exception{
    String fn=msg.getString("functionName");int id=msg.optInt("messageId");JSONObject payload=msg.optJSONObject("payload");if(payload==null)payload=new JSONObject();
    Log.i(TAG,"CONTROL "+fn);boolean expected=msg.optBoolean("responseExpected");
    if(fn.equals("ubnt_avclient_hello"))return;
    if(fn.equals("GetRequest")){final JSONObject request=msg;snapshots.execute(()->{try{uploadSnapshot(request);}catch(Exception e){Log.w(TAG,"SNAPSHOT_FAILED "+e.getClass().getSimpleName());}});return;}
    JSONObject response=null;String responseName=fn;
    if(fn.equals("ubnt_avclient_paramAgreement"))response=object("authToken",JSONObject.NULL,"features",features());
    else if(fn.equals("ubnt_avclient_time")){responseName="ubnt_avclient_paramAgreement";response=object("monotonicMs",SystemClock.elapsedRealtime()-initMs,"wallMs",now(),"features",new JSONObject());}
    else if(fn.equals("ChangeVideoSettings"))response=videoSettings(payload);
    else if(fn.equals("ResetIspSettings")||fn.equals("ChangeIspSettings"))response=copy("process_isp_settings");
    else if(fn.equals("ChangeDeviceSettings"))response=copy("process_device_settings");
    else if(fn.equals("ChangeOsdSettings"))response=copy("process_osd_settings");
    else if(fn.equals("NetworkStatus")){response=copy("process_network_status");response.put("ipAddress",config.getString("ip"));response.put("gateway",config.getString("host"));response.put("dnsServer",config.getString("host"));response.put("defaultInterface","wlan0");}
    else if(fn.equals("ChangeSoundLedSettings"))response=copy("process_sound_led_settings");
    else if(fn.equals("UpdateFirmwareRequest")){Log.w(TAG,"Firmware update unsupported; no device changes");return;}
    else if(fn.equals("Reboot")){closeConnection();return;}
    else if(expected)response=payload;
    if(response!=null&&(expected||fn.equals("ChangeVideoSettings")))send(responseName,id,response);
  }
  // The recorder names independent media destinations. All three slots reuse
  // the same compressed source; names/withOpus changes rebuild only that sink.
  JSONObject videoSettings(JSONObject request)throws Exception{
    JSONObject video=request.optJSONObject("video");
    if(video!=null)for(String index:new String[]{"video1","video2","video3"}){
      JSONObject item=video.optJSONObject(index);JSONObject serializer=item==null?null:item.optJSONObject("avSerializer");
      if(serializer==null)continue;JSONArray destinations=serializer.optJSONArray("destinations");
      if(destinations==null||destinations.length()==0)continue;
      String dest=destinations.optString(0);if(dest.contains("/dev/null")){Sink old=streams.remove(index);if(old!=null)old.close();continue;}
      JSONObject parameters=serializer.optJSONObject("parameters");if(parameters==null||!parameters.has("streamName"))continue;
      URI uri=new URI(dest);if(!config.getString("host").equals(uri.getHost())||uri.getPort()!=7550)throw new IOException("Unexpected media destination");
      String name=parameters.getString("streamName");if(name.length()>512)throw new IOException("Oversized stream name");
      boolean withOpus=(parameters.optBoolean("withOpus",false)||opusOnly())&&camera.opusAvailable&&opusRequested();
      int opusRate=parameters.optInt("opusSampleRate",48000);
      // The recorder requests 24 kHz here even after our 48 kHz capability.
      // Counteroffer the actual 48 kHz encoder rate in the response below;
      // do not disconnect control on that request. Native Opus clock is always
      // 48 kHz. Verified output: real Opus packets, 20 ms/960 RTP ticks, decode
      // succeeds alongside AAC. This does not prove the iPhone selects Opus.
      Log.i(TAG,"MEDIA_NEGOTIATED "+index+" withOpus="+withOpus+" opusSampleRate="+opusRate);
      Sink old=streams.get(index);if(old!=null&&old.name.equals(name)&&old.withOpus==withOpus&&!old.closed)continue;
      if(old!=null)old.close();Sink next=new Sink(index,name,uri.getHost(),uri.getPort(),withOpus);streams.put(index,next);next.start();
    }
    JSONObject result=copy("process_video_settings");
    if(opusOnly())result.put("audio",object("bitRate",64000,"channels",1,"description","audio track","enabled",true,"sampleRate",48000,"type","opus","volume",100));
    JSONObject videos=result.getJSONObject("video");
    for(String index:new String[]{"video1","video2","video3"}){
      JSONObject serializer=videos.getJSONObject(index).getJSONObject("avSerializer");Sink s=streams.get(index);
      if(s!=null){serializer.put("destinations",new JSONArray().put("tcp://"+s.host+":"+s.port));serializer.put("parameters",object("audioId",JSONObject.NULL,"streamName",s.name,"suppressAudio",JSONObject.NULL,"suppressVideo",JSONObject.NULL,"videoId",JSONObject.NULL,"withOpus",s.withOpus,"opusSampleRate",s.withOpus?48000:JSONObject.NULL));}
    }
    return result;
  }
  void offer(CameraService.Frame frame){
    if(!enabled||closed)return;
    List<byte[]> units=frame.audio?null:CameraService.nalUnits(frame.data);boolean key=false;
    if(units!=null)for(byte[] n:units)if((n[0]&31)==5){key=true;latestKeyframe=frame.data;latestKeyUs=frame.pts;break;}
    NativeFrame f=new NativeFrame(frame,units,key);
    for(Sink sink:streams.values())if(!sink.closed&&!sink.queue.offer(f))sink.disconnect();
  }
  static final class NativeFrame{final CameraService.Frame frame;final List<byte[]> units;final boolean key;NativeFrame(CameraService.Frame f,List<byte[]> u,boolean k){frame=f;units=u;key=k;}}
  // Bounded per-output queues prevent a slow recorder socket accumulating an
  // unlimited backlog. Reconnect waits for a keyframe to start a decodable stream.
  final class Sink extends Thread{
    final String index,name,host;final int port;final boolean withOpus;volatile boolean closed;volatile Socket socket;
    final ArrayBlockingQueue<NativeFrame> queue=new ArrayBlockingQueue<>(180);
    Sink(String index,String name,String host,int port,boolean withOpus){super("ProtectMedia-"+index);this.index=index;this.name=name;this.host=host;this.port=port;this.withOpus=withOpus;}
    void disconnect(){Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}queue.clear();}
    void close(){closed=true;disconnect();interrupt();}
    public void run(){
      while(!closed&&enabled&&!ProtectBridge.this.closed){
        try{
          Socket s=new Socket();socket=s;s.setTcpNoDelay(true);s.setSendBufferSize(64*1024);s.connect(new InetSocketAddress(host,port),5000);
          // Optional genuine preroll tests missing audio-before-video startup as
          // the cause of intermittent iPhone silence. Keep original event PTS;
          // do not combine this comparison with an artificial audio advance.
          long prerollMs=config.optLong("audioPrerollMs",0);
          if(prerollMs<0||prerollMs>500)throw new IOException("Invalid audio preroll");
          if(prerollMs>0&&(config.optInt("audioAdvanceMs",750)!=0||config.optLong("audioStartupDropMs",0)!=0||!config.optBoolean("audioRetainStartup",false)))throw new IOException("Conflicting audio preroll settings");
          ProtectAudioPreroll preroll=prerollMs>0?new ProtectAudioPreroll(prerollMs):null;
          queue.clear();NativeFrame first=null;long waitUntil=SystemClock.elapsedRealtime()+5000;
          while(!closed&&SystemClock.elapsedRealtime()<waitUntil){
            NativeFrame f=queue.poll(1,TimeUnit.SECONDS);if(f==null)continue;
            if(preroll!=null&&f.frame.audio&&!f.frame.opus)preroll.add(f.frame.pts,f.frame.data);
            if(f.key&&(preroll==null||preroll.ready(f.frame.pts))){first=f;break;}
          }
          if(first==null)throw new IOException("No keyframe");
          long startUs=first.frame.pts;OutputStream out=new BufferedOutputStream(s.getOutputStream(),32768);
          // Accepted Portal profile: advance=75, startupDrop=0, retainStartup=true,
          // sync=250 ms. Keep all three private config keys when copying/restoring
          // this profile: legacy fallbacks below describe older trial behavior.
          // ProtectMuxer documents the successful/failed iPhone comparisons.
          int audioAdvance=config.optInt("audioAdvanceMs",750);long startupDrop=config.optLong("audioStartupDropMs",0);
          boolean retainStartup=config.optBoolean("audioRetainStartup",false);
          List<ProtectAudioPreroll.Packet> initialAudio=preroll==null?Collections.emptyList():preroll.startingAt(startUs);
          long firstAudioMs=initialAudio.isEmpty()?0:(initialAudio.get(0).pts-startUs)/1000;
          ProtectMuxer mux=new ProtectMuxer(out,camera.originWallMs+startUs/1000+offsetMs,audioAdvance,config.optLong("mediaClockOriginMs",30000),config.optLong("clockSyncIntervalMs",250),startupDrop,retainStartup);mux.start(name,camera.sps,camera.pps,firstAudioMs,!opusOnly());if(withOpus)mux.startOpus(firstAudioMs);
          for(ProtectAudioPreroll.Packet a:initialAudio)if(!opusOnly()&&a.pts<startUs)mux.audio(a.data,(a.pts-startUs)/1000);
          mux.video(first.units,0);
          for(ProtectAudioPreroll.Packet a:initialAudio)if(!opusOnly()&&a.pts>=startUs)mux.audio(a.data,(a.pts-startUs)/1000);
          out.flush();Log.i(TAG,"MEDIA_STARTED "+index+" withOpus="+withOpus+" opusOnly="+opusOnly()+" audioAdvanceMs="+audioAdvance+" audioStartupDropMs="+startupDrop+" audioRetainStartup="+retainStartup+" audioPrerollMs="+prerollMs+" prerollPackets="+initialAudio.size()+" firstAudioRelativeMs="+firstAudioMs);
          long frames=1,lastLog=SystemClock.elapsedRealtime(),lastFlush=lastLog;
          while(!closed&&enabled){NativeFrame f=queue.poll(3,TimeUnit.SECONDS);if(f==null)throw new IOException("Media timeout");long stamp=(f.frame.pts-startUs)/1000;if(stamp<0&&(!f.frame.audio||prerollMs==0||stamp< -prerollMs))continue;
            if(f.frame.opus){if(withOpus)mux.opus(f.frame.data,stamp);}else if(f.frame.audio){if(!opusOnly())mux.audio(f.frame.data,stamp);}else{mux.video(f.units,stamp);frames++;}
            long now=SystemClock.elapsedRealtime();if(!f.frame.audio||now-lastFlush>=20){out.flush();lastFlush=now;}
            if(now-lastLog>=30000){Log.i(TAG,"MEDIA_OK "+index+" videoFrames="+frames+" queue="+queue.size());lastLog=now;}
          }
        }catch(Exception e){if(!closed&&enabled)Log.w(TAG,"MEDIA_RETRY "+index+" "+e.getClass().getSimpleName()+" "+safe(e));}
        finally{disconnect();}
        if(!closed&&enabled)try{Thread.sleep(1000);}catch(InterruptedException ignored){}
      }
    }
  }
  void uploadSnapshot(JSONObject msg)throws Exception{
    JSONObject p=msg.getJSONObject("payload");URI uri=new URI(p.getString("uri"));
    if(!config.getString("host").equals(uri.getHost())||!uri.getScheme().equals("https"))throw new IOException("Unexpected snapshot destination");
    byte[] image=snapshot.get();String boundary="PortalSnapshot"+UUID.randomUUID().toString().replace("-","");
    HttpsURLConnection conn=(HttpsURLConnection)uri.toURL().openConnection();conn.setSSLSocketFactory(tls.getSocketFactory());conn.setHostnameVerifier((h,s)->h.equals(config.optString("host")));conn.setConnectTimeout(5000);conn.setReadTimeout(10000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
    try{
      ByteArrayOutputStream body=new ByteArrayOutputStream();JSONObject fields=p.optJSONObject("formFields");if(fields!=null){Iterator<String> keys=fields.keys();while(keys.hasNext()){String k=keys.next();if(!k.matches("[A-Za-z0-9_-]+"))throw new IOException("Invalid form field");body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+k+"\"\r\n\r\n"+fields.getString(k)+"\r\n").getBytes("UTF-8"));}}
      body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"payload\"; filename=\"snapshot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes("UTF-8"));body.write(image);body.write(("\r\n--"+boundary+"--\r\n").getBytes("UTF-8"));
      conn.setFixedLengthStreamingMode(body.size());try(OutputStream out=conn.getOutputStream()){body.writeTo(out);}int status=conn.getResponseCode();if(status<200||status>=300)throw new IOException("Snapshot HTTP "+status);
      Log.i(TAG,"SNAPSHOT_UPLOADED bytes="+image.length);if(msg.optBoolean("responseExpected"))send("GetRequest",msg.optInt("messageId"),new JSONObject());
    }finally{conn.disconnect();}
  }
  synchronized void close(){closed=true;enabled=false;closeConnection();Thread t=control;if(t!=null)t.interrupt();snapshots.shutdownNow();}
}
