package local.codex.portalprotectcamera;
import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
/** One-shot decoder, only for requested JPEGs. No extra camera or continuous decode. */
final class ProtectSnapshot {
  final CameraService camera;final ProtectBridge bridge;byte[] cached;long cachedAt;
  ProtectSnapshot(CameraService c,ProtectBridge b){camera=c;bridge=b;}
  // Serialize decoder access and reuse a short-lived JPEG; native multi-view
  // polling should not create a continuous second video-decode workload.
  synchronized byte[] get()throws Exception{
    long now=SystemClock.elapsedRealtime();if(cached!=null&&now-cachedAt<5000)return cached;
    long limit=now+1500;byte[] key;
    while((key=bridge.latestKeyframe)==null&&SystemClock.elapsedRealtime()<limit)Thread.sleep(40);
    if(key==null||camera.sps==null||camera.pps==null)throw new IOException("No snapshot keyframe");
    long currentUs=System.nanoTime()/1000-camera.originUs;if(currentUs-bridge.latestKeyUs>3000000)throw new IOException("Stale keyframe");
    MediaCodec codec=MediaCodec.createDecoderByType("video/avc");
    try{
      MediaFormat f=MediaFormat.createVideoFormat("video/avc",1280,720);f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
      f.setByteBuffer("csd-0",ByteBuffer.wrap(annex(camera.sps)));f.setByteBuffer("csd-1",ByteBuffer.wrap(annex(camera.pps)));
      codec.configure(f,null,null,0);codec.start();int input=codec.dequeueInputBuffer(1000000);if(input<0)throw new IOException("Snapshot input unavailable");
      ByteBuffer b=codec.getInputBuffer(input);if(key.length>b.capacity())throw new IOException("Keyframe oversized");b.put(key);codec.queueInputBuffer(input,0,key.length,0,0);
      int eos=codec.dequeueInputBuffer(500000);if(eos>=0)codec.queueInputBuffer(eos,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long deadline=SystemClock.elapsedRealtime()+2500;
      while(SystemClock.elapsedRealtime()<deadline){int index=codec.dequeueOutputBuffer(info,100000);if(index<0)continue;
        try(Image image=codec.getOutputImage(index)){
          if(image==null)throw new IOException("Decoder returned no image");
          Rect crop=image.getCropRect();int width=(crop.width()/2)&~1,height=(crop.height()/2)&~1;
          byte[] nv21=new byte[width*height*3/2];Image.Plane[] planes=image.getPlanes();
          for(int y=0;y<height;y++)for(int x=0;x<width;x++)nv21[y*width+x]=sample(planes[0],crop.left+x*2,crop.top+y*2);
          int at=width*height;for(int y=0;y<height/2;y++)for(int x=0;x<width/2;x++){int sx=crop.left/2+x*2,sy=crop.top/2+y*2;nv21[at++]=sample(planes[2],sx,sy);nv21[at++]=sample(planes[1],sx,sy);}
          ByteArrayOutputStream out=new ByteArrayOutputStream();if(!new YuvImage(nv21,ImageFormat.NV21,width,height,null).compressToJpeg(new Rect(0,0,width,height),78,out))throw new IOException("JPEG failed");
          cached=out.toByteArray();cachedAt=SystemClock.elapsedRealtime();return cached;
        }finally{codec.releaseOutputBuffer(index,false);}
      }
      throw new IOException("Snapshot decode timeout");
    }finally{try{codec.stop();}catch(Exception ignored){}codec.release();}
  }
  static byte sample(Image.Plane p,int x,int y){return p.getBuffer().get(y*p.getRowStride()+x*p.getPixelStride());}
  static byte[] annex(byte[] n){byte[] b=new byte[n.length+4];b[3]=1;System.arraycopy(n,0,b,4,n.length);return b;}
}
