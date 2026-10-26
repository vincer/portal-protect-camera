package local.codex.portalprotectcamera;
import java.io.*;
import java.util.*;
/** Exercise genuine compressed preroll against a real source packet fixture. */
public final class ProtectPrerollFixture {
 static List<byte[]> units(byte[] payload)throws IOException{
  DataInputStream data=new DataInputStream(new ByteArrayInputStream(payload));List<byte[]> result=new ArrayList<>();
  while(data.available()>0){int n=data.readInt();byte[] unit=new byte[n];data.readFully(unit);result.add(unit);}return result;
 }
 public static void main(String[] args)throws Exception{
  ProtectAudioPreroll bounded=new ProtectAudioPreroll(200);
  for(int i=0;i<1000;i++)bounded.add(i*21333L,new byte[]{1});
  if(bounded.packets.size()>64||bounded.packets.peekLast().pts-bounded.packets.peekFirst().pts>700000)throw new AssertionError("Unbounded history");
  int count=bounded.packets.size();bounded.add(0,new byte[]{2});if(bounded.packets.size()!=count)throw new AssertionError("Out of order audio accepted");
  try(DataInputStream in=new DataInputStream(new FileInputStream(args[0]));OutputStream out=new FileOutputStream(args[1])){
   byte[] sps=new byte[in.readUnsignedShort()];in.readFully(sps);byte[] pps=new byte[in.readUnsignedShort()];in.readFully(pps);
   ProtectAudioPreroll preroll=new ProtectAudioPreroll(200);ProtectMuxer mux=null;long origin=-1,base=-1,firstAudio=0;int initialCount=0;
   while(in.available()>0){
    int type=in.readUnsignedByte();long stamp=in.readLong();int n=in.readInt();byte[] payload=new byte[n];in.readFully(payload);if(base<0)base=stamp;stamp-=base;
    List<byte[]> video=type==9?units(payload):null;boolean key=false;if(video!=null)for(byte[] unit:video)if((unit[0]&31)==5)key=true;
    if(mux==null){
     if(type==8)preroll.add(stamp*1000,payload);
     if(!key||stamp<1000||!preroll.ready(stamp*1000))continue;
     origin=stamp;List<ProtectAudioPreroll.Packet> initial=preroll.startingAt(origin*1000);initialCount=initial.size();firstAudio=(initial.get(0).pts-origin*1000)/1000;
     mux=new ProtectMuxer(out,1803312000000L,0,30000,250,0,true);mux.start("preroll",sps,pps,firstAudio);
     for(ProtectAudioPreroll.Packet a:initial)if(a.pts<origin*1000)mux.audio(a.data,(a.pts-origin*1000)/1000);
     mux.video(video,0);
     for(ProtectAudioPreroll.Packet a:initial)if(a.pts>=origin*1000)mux.audio(a.data,(a.pts-origin*1000)/1000);
    }else if(type==8)mux.audio(payload,stamp-origin);else mux.video(video,stamp-origin);
   }
   if(mux==null)throw new AssertionError("No suitable keyframe");
   System.out.println("{\"video_origin_ms\":"+origin+",\"first_audio_relative_ms\":"+firstAudio+",\"startup_packets\":"+initialCount+",\"bounded_history\":true}");
  }
 }
}
