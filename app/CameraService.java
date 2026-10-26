package local.codex.portalprotectcamera;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import android.view.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Small, explicit Camera2 → H.264 + software AAC RTSP source for the local NVR. */
public class CameraService extends Service {
  static final String TAG="PortalProtectCamera";
  static final int WIDTH=1280,HEIGHT=720,FPS=30,VIDEO_BITRATE=2000000;
  static final int SAMPLE_RATE=48000,AUDIO_BITRATE=64000,PORT=8556;
  volatile boolean running=false;
  volatile byte[] sps,pps;
  volatile long originUs;
  volatile long originWallMs;
  volatile MediaCodec video,audio;
  volatile ProtectOpusEncoder opus;
  volatile boolean opusAvailable;
  volatile AudioRecord microphone;
  volatile CameraDevice camera;
  volatile CameraCaptureSession capture;
  Surface surface;
  HandlerThread cameraThread;
  Handler cameraHandler;
  ProtectBridge protectBridge;
  ServerSocket server;
  PowerManager.WakeLock wake;
  View overlay;
  final CopyOnWriteArrayList<Client> clients=new CopyOnWriteArrayList<>();
  final java.util.concurrent.atomic.AtomicBoolean recovering=new java.util.concurrent.atomic.AtomicBoolean(false);

  public void onCreate(){
    super.onCreate();
    NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(new NotificationChannel("camera","Portal camera recording",NotificationManager.IMPORTANCE_LOW));
    startForeground(1,new Notification.Builder(this,"camera").setContentTitle("Portal camera recording")
      .setContentText("720p · 30 fps · microphone audio to your local NVR")
      .setSmallIcon(android.R.drawable.presence_video_online).build());
    wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PortalProtect:Camera");wake.acquire();
    try{
      overlay=new View(this);
      WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);
      p.gravity=Gravity.TOP|Gravity.LEFT;
      ((WindowManager)getSystemService(WINDOW_SERVICE)).addView(overlay,p);
    }catch(Exception e){Log.w(TAG,"Overlay unavailable: "+e);}
    running=true;originUs=System.nanoTime()/1000;originWallMs=System.currentTimeMillis();
    cameraThread=new HandlerThread("ProtectCameraControl");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());
    new Thread(()->serve(),"ProtectRtspAccept").start();
    protectBridge=new ProtectBridge(this);protectBridge.configure();
    cameraHandler.post(()->startVideo());
    new Thread(()->captureAudio(),"ProtectAudioCapture").start();
  }
  public int onStartCommand(Intent intent,int flags,int id){if(protectBridge!=null){protectBridge.configure();if(intent!=null&&intent.getBooleanExtra("protect_reconnect",false))protectBridge.closeConnection();}return START_STICKY;}
  public IBinder onBind(Intent intent){return null;}

  void startVideo(){
    if(!running||video!=null)return;
    try{
      MediaFormat format=MediaFormat.createVideoFormat("video/avc",WIDTH,HEIGHT);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE,VIDEO_BITRATE);format.setInteger(MediaFormat.KEY_FRAME_RATE,FPS);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
      format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
      format.setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.AVCLevel31);
      MediaCodec codec=MediaCodec.createEncoderByType("video/avc");
      codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
      surface=codec.createInputSurface();codec.start();video=codec;
      new Thread(()->drainVideo(codec),"ProtectVideoEncoder").start();
      ((CameraManager)getSystemService(CAMERA_SERVICE)).openCamera("0",new CameraDevice.StateCallback(){
        public void onOpened(CameraDevice device){
          if(!running){device.close();return;}camera=device;
          try{
            device.createCaptureSession(Collections.singletonList(surface),new CameraCaptureSession.StateCallback(){
              public void onConfigured(CameraCaptureSession session){
                capture=session;
                try{
                  CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                  request.addTarget(surface);
                  request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new android.util.Range<Integer>(FPS,FPS));
                  session.setRepeatingRequest(request.build(),null,cameraHandler);
                  Log.i(TAG,"VIDEO_STARTED 1280x720 fps=30 bitrate=2000000");
                }catch(Exception e){recoverVideo(e);}
              }
              public void onConfigureFailed(CameraCaptureSession session){recoverVideo(new Exception("Camera configuration failed"));}
            },cameraHandler);
          }catch(Exception e){recoverVideo(e);}
        }
        public void onDisconnected(CameraDevice device){recoverVideo(new Exception("Camera temporarily taken"));}
        public void onError(CameraDevice device,int code){recoverVideo(new Exception("Camera error "+code));}
      },cameraHandler);
    }catch(Exception e){recoverVideo(e);}
  }
  void recoverVideo(Exception error){
    if(!running||!recovering.compareAndSet(false,true))return;
    Log.w(TAG,"VIDEO_RECOVERY "+error);
    if(cameraHandler!=null)cameraHandler.post(()->{
      stopVideo();
      if(running){
        if(error.toString().contains("from background")||error.toString().contains("CAMERA_DISABLED")){
          AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
          if(am.getMode()==AudioManager.MODE_NORMAL)
            startActivity(new Intent(this,CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
        cameraHandler.postDelayed(()->{recovering.set(false);startVideo();},5000);
      }
    });
  }
  void stopVideo(){
    CameraCaptureSession oldSession=capture;capture=null;
    try{if(oldSession!=null)oldSession.close();}catch(Exception ignored){}
    CameraDevice oldCamera=camera;camera=null;try{if(oldCamera!=null)oldCamera.close();}catch(Exception ignored){}
    MediaCodec old=video;video=null;try{if(old!=null){old.stop();old.release();}}catch(Exception ignored){}
    if(surface!=null){surface.release();surface=null;}
  }
  void drainVideo(MediaCodec codec){
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long frames=0;
    try{
      while(running&&video==codec){
        int index=codec.dequeueOutputBuffer(info,10000);
        if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
          MediaFormat f=codec.getOutputFormat();
          updateParameterSets(bytes(f.getByteBuffer("csd-0")));
          if(f.containsKey("csd-1"))updateParameterSets(bytes(f.getByteBuffer("csd-1")));
        }else if(index>=0){
          ByteBuffer b=codec.getOutputBuffer(index);b.position(info.offset);b.limit(info.offset+info.size);
          byte[] data=bytes(b);
          if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)updateParameterSets(data);
          else if(data.length>0){
            updateParameterSets(data);
            broadcast(new Frame(false,data,Math.max(0,info.presentationTimeUs-originUs)));
            if(++frames%900==0)Log.i(TAG,"VIDEO_FRAMES "+frames);
          }
          codec.releaseOutputBuffer(index,false);
        }
      }
    }catch(Exception e){if(running&&video==codec)recoverVideo(e);}
  }
  static byte[] bytes(ByteBuffer buffer){if(buffer==null)return new byte[0];ByteBuffer b=buffer.duplicate();byte[] data=new byte[b.remaining()];b.get(data);return data;}
  void updateParameterSets(byte[] data){for(byte[] n:nalUnits(data)){int type=n[0]&31;if(type==7)sps=n;else if(type==8)pps=n;}}
  static List<byte[]> nalUnits(byte[] data){
    ArrayList<byte[]> out=new ArrayList<>();int start=-1;
    for(int i=0;i+3<=data.length;){
      int n=(i+4<=data.length&&data[i]==0&&data[i+1]==0&&data[i+2]==0&&data[i+3]==1)?4:
        (data[i]==0&&data[i+1]==0&&data[i+2]==1?3:0);
      if(n>0){if(start>=0&&i>start)out.add(Arrays.copyOfRange(data,start,i));start=i+n;i+=n;}else i++;
    }
    if(start>=0&&start<data.length)out.add(Arrays.copyOfRange(data,start,data.length));
    if(out.isEmpty()&&data.length>0){
      // Some Android encoders return AVCC length-prefixed access units.
      int at=0;
      while(at+4<=data.length){int n=ByteBuffer.wrap(data,at,4).getInt();at+=4;if(n<=0||at+n>data.length){out.clear();break;}out.add(Arrays.copyOfRange(data,at,at+n));at+=n;}
      if(out.isEmpty())out.add(data);
    }
    return out;
  }

  AudioRecord openMicrophone(int channels)throws IOException{
    int mask=channels==2?AudioFormat.CHANNEL_IN_STEREO:AudioFormat.CHANNEL_IN_MONO;
    int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,mask,AudioFormat.ENCODING_PCM_16BIT);
    if(min<=0)throw new IOException("Unsupported microphone channels="+channels);
    AudioRecord rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,SAMPLE_RATE,mask,
      AudioFormat.ENCODING_PCM_16BIT,Math.max(min*4,16384));
    try{
      if(rec.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone could not initialize");
      rec.startRecording();return rec;
    }catch(Exception e){rec.release();throw new IOException("Microphone channels="+channels,e);}
  }
  void captureAudio(){
    MediaCodec codec=null;AudioRecord rec=null;
    try{
      String encoder="OMX.google.aac.encoder";
      for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos())
        if(info.isEncoder()&&info.getName().equals("c2.android.aac.encoder"))encoder=info.getName();
      codec=MediaCodec.createByCodecName(encoder);
      MediaFormat format=MediaFormat.createAudioFormat("audio/mp4a-latm",SAMPLE_RATE,2);
      format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      format.setInteger(MediaFormat.KEY_BIT_RATE,AUDIO_BITRATE);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);
      codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();audio=codec;
      // Independent Opus encode from the SAME PCM and sample clock as AAC. No
      // AAC decode/re-encode and no additional microphone or video encoder.
      if(protectBridge!=null&&protectBridge.opusRequested())try{opus=new ProtectOpusEncoder(this);}catch(Exception e){Log.w(TAG,"OPUS_UNAVAILABLE "+e.getClass().getSimpleName());}
      final MediaCodec drainCodec=codec;
      new Thread(()->drainAudio(drainCodec),"ProtectAudioEncoder").start();
      Log.i(TAG,"AUDIO_ENCODER "+encoder+" sampleRate="+SAMPLE_RATE+" channels=2 bitrate="+AUDIO_BITRATE);
      int captureChannels=2;
      short[] pcm=new short[SAMPLE_RATE/25*captureChannels];long samples=0,stamp=System.nanoTime()/1000,lastLog=0;long energy=0,measured=0;int peak=0;
      long stereoDifferenceEnergy=0,stereoPairs=0;
      AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
      while(running&&audio==codec){
        boolean call=am.getMode()==AudioManager.MODE_IN_COMMUNICATION||am.getMode()==AudioManager.MODE_IN_CALL||am.getMode()==AudioManager.MODE_RINGTONE;
        int n;
        if(call){
          if(rec!=null){rec.stop();rec.release();rec=null;microphone=null;}
          Arrays.fill(pcm,(short)0);n=pcm.length;Thread.sleep(40);
        }else{
          if(rec==null){
            try{rec=openMicrophone(2);captureChannels=2;}
            catch(Exception stereoError){Log.w(TAG,"STEREO_CAPTURE_FALLBACK "+stereoError);rec=openMicrophone(1);captureChannels=1;}
            pcm=new short[SAMPLE_RATE/25*captureChannels];
            microphone=rec;Log.i(TAG,"MICROPHONE_STARTED channels="+captureChannels+" rate="+rec.getSampleRate());
          }
          n=rec.read(pcm,0,pcm.length);
          if(n<=0){rec.release();rec=null;microphone=null;Thread.sleep(500);continue;}
        }
        for(int i=0;i<n;i++){int v=pcm[i];energy+=(long)v*v;peak=Math.max(peak,Math.abs(v));}measured+=n;
        if(captureChannels==2){for(int i=0;i+1<n;i+=2){int delta=pcm[i]-pcm[i+1];stereoDifferenceEnergy+=(long)delta*delta;stereoPairs++;}}
        ProtectOpusEncoder op=opus;
        if(op!=null)op.offer(pcm,n,captureChannels,stamp+samples*1000000L/SAMPLE_RATE);
        int input=codec.dequeueInputBuffer(10000);
        if(input>=0){
          ByteBuffer b=codec.getInputBuffer(input);b.clear();b.order(ByteOrder.LITTLE_ENDIAN);
          for(int i=0;i<n;i++){b.putShort(pcm[i]);if(captureChannels==1)b.putShort(pcm[i]);}
          int outputBytes=n*2*(captureChannels==1?2:1);
          codec.queueInputBuffer(input,0,outputBytes,stamp+samples*1000000L/SAMPLE_RATE,0);samples+=n/captureChannels;
        }
        long now=System.currentTimeMillis();
        if(now-lastLog>30000&&measured>0){Log.i(TAG,"AUDIO_LEVEL rms="+Math.sqrt(energy/(double)measured)+" peak="+peak+" samples="+measured+
          " input_channels="+captureChannels+" input_difference_rms="+(stereoPairs>0?Math.sqrt(stereoDifferenceEnergy/(double)stereoPairs):0));
          lastLog=now;energy=0;measured=0;peak=0;stereoDifferenceEnergy=0;stereoPairs=0;}
      }
    }catch(Exception e){Log.e(TAG,"AUDIO_ERROR "+e);if(running)cameraHandler.postDelayed(()->new Thread(()->captureAudio(),"ProtectAudioRetry").start(),5000);}
    finally{
      ProtectOpusEncoder op=opus;opus=null;opusAvailable=false;if(op!=null)op.close();
      if(microphone==rec)microphone=null;
      try{if(rec!=null){rec.stop();rec.release();}}catch(Exception ignored){}
      if(audio==codec)audio=null;
      try{if(codec!=null){codec.stop();codec.release();}}catch(Exception ignored){}
    }
  }
  void drainAudio(MediaCodec codec){
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    try{
      while(running&&audio==codec){
        int index=codec.dequeueOutputBuffer(info,10000);
        if(index>=0){
          if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
            ByteBuffer b=codec.getOutputBuffer(index);b.position(info.offset);b.limit(info.offset+info.size);
            broadcast(new Frame(true,bytes(b),Math.max(0,info.presentationTimeUs-originUs)));
          }
          codec.releaseOutputBuffer(index,false);
        }
      }
    }catch(Exception e){if(running&&audio==codec)Log.w(TAG,"Audio drain ended: "+e);}
  }
  static class Frame{final boolean audio,opus;final byte[] data;final long pts;
    Frame(boolean a,byte[] d,long p){this(a,false,d,p);}
    Frame(boolean a,boolean o,byte[] d,long p){audio=a;opus=o;data=d;pts=p;}}
  void broadcast(Frame frame){if(protectBridge!=null)protectBridge.offer(frame);if(!frame.opus)for(Client client:clients)if(client.playing&&!client.queue.offer(frame)){client.close();}}
  void serve(){
    try{server=new ServerSocket(PORT);Log.i(TAG,"RTSP_LISTEN port="+PORT);while(running){Socket socket=server.accept();if(clients.size()>=8){socket.close();continue;}Client client=new Client(socket);clients.add(client);new Thread(()->client.read(),"ProtectRtspClient").start();}}
    catch(Exception e){if(running)Log.e(TAG,"RTSP_SERVER_ERROR "+e);}
  }
  String sdp(){
    byte[] vsps=sps,vpps=pps;if(vsps==null||vpps==null)return null;
    String profile=String.format(Locale.US,"%02x%02x%02x",vsps[1]&255,vsps[2]&255,vsps[3]&255);
    return "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=Portal Protect Camera\r\nt=0 0\r\na=control:*\r\n"+
      "m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\na=rtpmap:96 H264/90000\r\na=framerate:30\r\n"+
      "a=fmtp:96 packetization-mode=1;profile-level-id="+profile+";sprop-parameter-sets="+android.util.Base64.encodeToString(vsps,2)+","+android.util.Base64.encodeToString(vpps,2)+"\r\na=control:streamid=0\r\n"+
      "m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\na=rtpmap:97 MPEG4-GENERIC/"+SAMPLE_RATE+"/2\r\na=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;config=1190;SizeLength=13;IndexLength=3;IndexDeltaLength=3\r\na=control:streamid=1\r\n";
  }
  class Client {
    final Socket socket;final InputStream input;final OutputStream output;
    final ArrayBlockingQueue<Frame> queue=new ArrayBlockingQueue<>(150);
    volatile boolean playing=false,closed=false;int videoChannel=0,audioChannel=2,videoSeq=0,audioSeq=0;
    volatile boolean waitingForKeyframe=true;
    final long[] lastReportMs={0,0};
    final int[] packetCounts={0,0},octetCounts={0,0};
    final int videoSsrc=new Random().nextInt(),audioSsrc=new Random().nextInt();
    boolean videoSetup=false,audioSetup=false;String baseUrl;
    Client(Socket s)throws IOException{socket=s;s.setSendBufferSize(1024*1024);s.setTcpNoDelay(true);input=s.getInputStream();output=s.getOutputStream();new Thread(()->writeFrames(),"ProtectRtspWriter").start();}
    String line()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=input.read())!=-1){if(c==10)break;if(c!=13)b.write(c);if(b.size()>16384)throw new IOException("Oversized RTSP line");}return c<0&&b.size()==0?null:b.toString("UTF-8");}
    void read(){
      try{
        while(!closed){
          int first=input.read();if(first<0)break;
          if(first==36){int channel=input.read();int hi=input.read(),lo=input.read();int n=(hi<<8)|lo;for(int i=0;i<n;i++)if(input.read()<0)throw new EOFException();continue;}
          String rest=line();if(rest==null)break;String request=(char)first+rest;
          String[] parts=request.split(" ");if(parts.length<2)break;
          HashMap<String,String> headers=new HashMap<>();String h;
          while((h=line())!=null&&!h.isEmpty()){int colon=h.indexOf(':');if(colon>0)headers.put(h.substring(0,colon).toLowerCase(Locale.US),h.substring(colon+1).trim());}
          int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length>65536)break;for(int i=0;i<length;i++)input.read();
          String seq=headers.getOrDefault("cseq","1"),method=parts[0],url=parts[1];baseUrl=url.contains("streamid=")?url.substring(0,url.indexOf("streamid=")):url;if(!baseUrl.endsWith("/"))baseUrl+="/";
          if(method.equals("OPTIONS"))reply(seq,200,"Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n",null);
          else if(method.equals("DESCRIBE")){String body=sdp();if(body==null)reply(seq,503,"Retry-After: 2\r\n",null);else reply(seq,200,"Content-Type: application/sdp\r\nContent-Base: "+baseUrl+"\r\n",body);}
          else if(method.equals("SETUP")){
            String t=headers.getOrDefault("transport","");if(!t.toUpperCase(Locale.US).contains("TCP")){reply(seq,461,"",null);continue;}
            boolean a=url.contains("streamid=1");int channel=a?2:0;
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("interleaved=(\\d+)-(\\d+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);if(match.find())channel=Integer.parseInt(match.group(1));
            if(a){audioChannel=channel;audioSetup=true;}else{videoChannel=channel;videoSetup=true;}
            reply(seq,200,"Transport: RTP/AVP/TCP;unicast;interleaved="+channel+"-"+(channel+1)+"\r\nSession: portalcamera\r\n",null);
          }else if(method.equals("PLAY")){
            reply(seq,200,"Session: portalcamera\r\n",null);queue.clear();waitingForKeyframe=true;playing=true;
            try{Bundle b=new Bundle();b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);if(video!=null)video.setParameters(b);}catch(Exception ignored){}
          }else if(method.equals("PAUSE")){playing=false;reply(seq,200,"Session: portalcamera\r\n",null);}
          else if(method.equals("TEARDOWN")){reply(seq,200,"",null);break;}
          else if(method.equals("GET_PARAMETER"))reply(seq,200,"Session: portalcamera\r\n",null);
          else reply(seq,405,"",null);
        }
      }catch(Exception ignored){}finally{close();}
    }
    void reply(String seq,int status,String headers,String body)throws IOException{
      byte[] data=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
      String phrase=status==200?"OK":status==461?"Unsupported Transport":status==503?"Service Unavailable":"Method Not Allowed";
      byte[] head=("RTSP/1.0 "+status+" "+phrase+"\r\nCSeq: "+seq+"\r\nServer: PortalProtectCamera/1.0\r\n"+headers+"Content-Length: "+data.length+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);
      synchronized(output){output.write(head);output.write(data);output.flush();}
    }
    void writeFrames(){
      try{while(!closed){Frame frame=queue.poll(1,TimeUnit.SECONDS);if(frame==null||!playing)continue;
        if(frame.audio){if(!audioSetup)continue;int size=frame.data.length;byte[] payload=new byte[size+4];payload[1]=16;payload[2]=(byte)(size>>5);payload[3]=(byte)(size<<3);System.arraycopy(frame.data,0,payload,4,size);rtp(true,payload,frame.pts*SAMPLE_RATE/1000000L,true);}
        else if(videoSetup){List<byte[]> units=nalUnits(frame.data);
          if(waitingForKeyframe){boolean key=false;for(byte[] unit:units)if((unit[0]&31)==5)key=true;if(!key)continue;waitingForKeyframe=false;}
          for(int n=0;n<units.size();n++){byte[] nal=units.get(n);boolean last=n==units.size()-1;
          if(nal.length<=1200)rtp(false,nal,frame.pts*90/1000,last);
          else{int offset=1;while(offset<nal.length){int count=Math.min(1198,nal.length-offset);byte[] payload=new byte[count+2];payload[0]=(byte)((nal[0]&224)|28);payload[1]=(byte)((nal[0]&31)|(offset==1?128:0)|(offset+count==nal.length?64:0));System.arraycopy(nal,offset,payload,2,count);rtp(false,payload,frame.pts*90/1000,last&&offset+count==nal.length);offset+=count;}}
        }}
      }}catch(Exception ignored){}finally{close();}
    }
    void rtp(boolean a,byte[] payload,long timestamp,boolean marker)throws IOException{
      ByteBuffer packet=ByteBuffer.allocate(12+payload.length);packet.put((byte)128);packet.put((byte)((a?97:96)|(marker?128:0)));packet.putShort((short)(a?audioSeq++:videoSeq++));packet.putInt((int)timestamp);packet.putInt(a?audioSsrc:videoSsrc);packet.put(payload);
      byte[] bytes=packet.array();synchronized(output){
        output.write(new byte[]{36,(byte)(a?audioChannel:videoChannel),(byte)(bytes.length>>8),(byte)bytes.length});output.write(bytes);
        int track=a?1:0;packetCounts[track]++;octetCounts[track]+=payload.length;
        long now=System.currentTimeMillis();
        if(now-lastReportMs[track]>=2000){sendReport(a,timestamp);lastReportMs[track]=now;}
        output.flush();
      }
    }
    void sendReport(boolean a,long timestamp)throws IOException{
      int track=a?1:0,ssrc=a?audioSsrc:videoSsrc,clock=a?SAMPLE_RATE:90000;
      long mediaMs=originWallMs+timestamp*1000L/clock;
      long seconds=mediaMs/1000L+2208988800L,fraction=(mediaMs%1000L)*0x100000000L/1000L;
      byte[] cname="portal-protect".getBytes(StandardCharsets.US_ASCII);
      int sdesSize=(4+4+2+cname.length+1+3)&~3;
      ByteBuffer report=ByteBuffer.allocate(28+sdesSize);
      report.put((byte)128).put((byte)200).putShort((short)6).putInt(ssrc);
      report.putInt((int)seconds).putInt((int)fraction).putInt((int)timestamp).putInt(packetCounts[track]).putInt(octetCounts[track]);
      report.put((byte)129).put((byte)202).putShort((short)(sdesSize/4-1)).putInt(ssrc);
      report.put((byte)1).put((byte)cname.length).put(cname).put((byte)0);
      byte[] data=report.array();
      output.write(new byte[]{36,(byte)((a?audioChannel:videoChannel)+1),(byte)(data.length>>8),(byte)data.length});output.write(data);
    }
    void close(){if(closed)return;closed=true;playing=false;clients.remove(this);queue.clear();try{socket.close();}catch(Exception ignored){}}
  }
  public void onDestroy(){
    running=false;if(protectBridge!=null)protectBridge.close();for(Client c:clients)c.close();try{if(server!=null)server.close();}catch(Exception ignored){}
    if(cameraHandler!=null){cameraHandler.removeCallbacksAndMessages(null);cameraHandler.post(()->stopVideo());}
    try{if(microphone!=null)microphone.stop();}catch(Exception ignored){}
    try{if(overlay!=null)((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(overlay);}catch(Exception ignored){}
    if(wake!=null&&wake.isHeld())wake.release();if(cameraThread!=null)cameraThread.quitSafely();super.onDestroy();
  }
}
