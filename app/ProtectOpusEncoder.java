package local.codex.portalprotectcamera;
import android.media.*;
import android.util.Log;
import java.nio.*;
/** Optional parallel mono Opus encoder; AAC/HA and video continue independently. */
final class ProtectOpusEncoder {
  final CameraService camera;final MediaCodec codec;final Thread drain;
  volatile boolean closed;long packets,dropped,lastLog;int firstPacketSize;
  ProtectOpusEncoder(CameraService camera)throws Exception{
    this.camera=camera;
    MediaCodec c=MediaCodec.createEncoderByType("audio/opus");
    try{
      MediaFormat f=MediaFormat.createAudioFormat("audio/opus",48000,1);
      f.setInteger(MediaFormat.KEY_BIT_RATE,64000);f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,3840);
      c.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);c.start();
    }catch(Exception e){c.release();throw e;}
    codec=c;drain=new Thread(this::drain,"ProtectOpusEncoder");drain.start();
    Log.i(CameraService.TAG,"OPUS_ENCODER "+c.getName()+" sampleRate=48000 channels=1 bitrate=64000");
  }
  void offer(short[] pcm,int count,int channels,long ptsUs){
    if(closed)return;
    try{
      // Capture uses 40 ms blocks. Submit at most 20 ms at a time, retaining the
      // original shared sample PTS. Nonblocking input prevents Opus backpressure
      // from delaying the established AAC path. Dropped input keeps real PTS.
      int frames=count/channels;
      for(int at=0;at<frames;at+=960){int n=Math.min(960,frames-at);int i=codec.dequeueInputBuffer(0);
        if(i<0){dropped++;continue;}
        ByteBuffer b=codec.getInputBuffer(i);b.clear();b.order(ByteOrder.LITTLE_ENDIAN);
        for(int j=0;j<n;j++){int v=pcm[(at+j)*channels];if(channels==2)v=(v+(int)pcm[(at+j)*channels+1])/2;b.putShort((short)v);}
        codec.queueInputBuffer(i,0,n*2,ptsUs+at*1000000L/48000,0);
      }
    }catch(Exception e){if(!closed)Log.w(CameraService.TAG,"OPUS_INPUT_ERROR "+e.getClass().getSimpleName());}
  }
  void drain(){MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    try{while(!closed&&camera.running){int i=codec.dequeueOutputBuffer(info,10000);if(i<0)continue;
      try{if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
        ByteBuffer b=codec.getOutputBuffer(i);b.position(info.offset);b.limit(info.offset+info.size);byte[] data=CameraService.bytes(b);
        camera.broadcast(new CameraService.Frame(true,true,data,Math.max(0,info.presentationTimeUs-camera.originUs)));
        if(++packets==1){firstPacketSize=data.length;camera.opusAvailable=true;
          Log.i(CameraService.TAG,"OPUS_READY bytes="+data.length+" toc="+(data[0]&255));
          if(camera.protectBridge!=null)camera.protectBridge.closeConnection();
        }
        long now=System.currentTimeMillis();if(now-lastLog>=30000){lastLog=now;Log.i(CameraService.TAG,"OPUS_OK packets="+packets+" droppedInputs="+dropped);}
      }}finally{codec.releaseOutputBuffer(i,false);}
    }}catch(Exception e){if(!closed){camera.opusAvailable=false;Log.w(CameraService.TAG,"OPUS_DRAIN_ERROR "+e.getClass().getSimpleName());if(camera.protectBridge!=null)camera.protectBridge.closeConnection();}}
  }
  void close(){closed=true;try{drain.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}try{codec.stop();}catch(Exception ignored){}codec.release();}
}
