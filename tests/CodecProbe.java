import android.media.*;import java.io.*;import java.nio.*;
public class CodecProbe {
 public static void main(String[] args)throws Exception {
  MediaCodec c=MediaCodec.createEncoderByType("audio/opus");MediaFormat f=MediaFormat.createAudioFormat("audio/opus",48000,1);f.setInteger(MediaFormat.KEY_BIT_RATE,64000);f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,3840);c.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);c.start();System.out.println("OPUS_ENCODER_STARTED "+c.getName());
  MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int sent=0,received=0;long until=System.currentTimeMillis()+10000;
  try(DataOutputStream out=new DataOutputStream(new FileOutputStream(args[0]))){while(received<100&&System.currentTimeMillis()<until){
   if(sent<105){int i=c.dequeueInputBuffer(1000);if(i>=0){ByteBuffer b=c.getInputBuffer(i);b.clear();b.order(ByteOrder.LITTLE_ENDIAN);for(int j=0;j<960;j++)b.putShort((short)(Math.sin((sent*960+j)*2*Math.PI*440/48000)*10000));c.queueInputBuffer(i,0,1920,sent*20000L,0);sent++;}}
   int i=c.dequeueOutputBuffer(info,1000);if(i>=0){if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){ByteBuffer b=c.getOutputBuffer(i);b.position(info.offset);b.limit(info.offset+info.size);byte[] packet=new byte[info.size];b.get(packet);out.writeLong(info.presentationTimeUs);out.writeInt(packet.length);out.write(packet);received++;}c.releaseOutputBuffer(i,false);}
  }}c.stop();c.release();System.out.println("PACKETS "+received);if(received!=100)throw new Exception("Incomplete encode");
 }
}
