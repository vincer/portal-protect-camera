package local.codex.portalprotectcamera;
import java.util.*;

/** Bounded compressed AAC history. Selection preserves original capture PTS. */
final class ProtectAudioPreroll {
  static final class Packet {
    final long pts;final byte[] data;
    Packet(long pts,byte[] data){this.pts=pts;this.data=data;}
  }
  final long windowUs;
  final ArrayDeque<Packet> packets=new ArrayDeque<>();
  ProtectAudioPreroll(long windowMs){
    if(windowMs<1||windowMs>500)throw new IllegalArgumentException("Invalid audio preroll");
    windowUs=windowMs*1000;
  }
  void add(long pts,byte[] data){
    if(!packets.isEmpty()&&pts<=packets.peekLast().pts)return;
    packets.addLast(new Packet(pts,data));
    // Keep delivery-lag headroom, but bound both time and packet count. AAC is
    // shared immutable encoded data: this is a few KB, not another encoder.
    while(packets.size()>64||(!packets.isEmpty()&&packets.peekFirst().pts<pts-windowUs-500000))packets.removeFirst();
  }
  boolean ready(long videoPts){
    if(packets.isEmpty()||packets.peekFirst().pts>videoPts-windowUs)return false;
    for(Packet p:packets)if(p.pts>=videoPts-windowUs&&p.pts<videoPts-windowUs/2)return true;
    return false;
  }
  List<Packet> startingAt(long videoPts){
    ArrayList<Packet> selected=new ArrayList<>();
    for(Packet p:packets)if(p.pts>=videoPts-windowUs)selected.add(p);
    return selected;
  }
}
