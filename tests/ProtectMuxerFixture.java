package local.codex.portalprotectcamera;
import java.io.*;
import java.util.*;
public final class ProtectMuxerFixture {
 public static void main(String[] args)throws Exception{
  try(DataInputStream in=new DataInputStream(new FileInputStream(args[0]));OutputStream out=new FileOutputStream(args[1])){
   byte[] sps=new byte[in.readUnsignedShort()];in.readFully(sps);byte[] pps=new byte[in.readUnsignedShort()];in.readFully(pps);
   ProtectMuxer mux=new ProtectMuxer(out,1803312000000L,args.length>4?Integer.parseInt(args[4]):750,args.length>2?Long.parseLong(args[2]):30000,args.length>3?Long.parseLong(args[3]):250,args.length>5?Long.parseLong(args[5]):0,args.length>6&&Boolean.parseBoolean(args[6]));mux.start("fixture",sps,pps);long start=-1;
   while(in.available()>0){int type=in.readUnsignedByte();long stamp=in.readLong();int size=in.readInt();if(size<0||size>2000000)throw new IOException();byte[] payload=new byte[size];in.readFully(payload);if(start<0)start=stamp;stamp-=start;if(stamp<0)continue;
    if(type==8)mux.audio(payload,stamp);else{
     DataInputStream data=new DataInputStream(new ByteArrayInputStream(payload));List<byte[]> units=new ArrayList<>();while(data.available()>0){int n=data.readInt();byte[] unit=new byte[n];data.readFully(unit);units.add(unit);}mux.video(units,stamp);
    }
   }
  }
 }
}
