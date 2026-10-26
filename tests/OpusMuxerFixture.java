package local.codex.portalprotectcamera;
import java.io.*;
public class OpusMuxerFixture {
 public static void main(String[] args)throws Exception{
  try(DataInputStream in=new DataInputStream(new FileInputStream(args[0]));OutputStream out=new FileOutputStream(args[1])){
   ProtectMuxer mux=new ProtectMuxer(out,1803312000000L,75,30000,250,0,true);
   mux.start("synthetic-opus-test",new byte[]{0x67,0x42,0,0x1f},new byte[]{0x68,0},0,args.length<3||Boolean.parseBoolean(args[2]));mux.startOpus(0);
   while(in.available()>0){long pts=in.readLong();byte[] p=new byte[in.readInt()];in.readFully(p);mux.opus(p,pts/1000);}
  }
 }
}
